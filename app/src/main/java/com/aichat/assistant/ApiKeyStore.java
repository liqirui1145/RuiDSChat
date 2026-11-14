package com.aichat.assistant;

import android.content.Context;
import android.os.Build;
import android.security.KeyPairGeneratorSpec;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.Calendar;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.security.auth.x500.X500Principal;

/**
 * 使用 Android Keystore 加密 API Key。
 * <p>
 * Android 6.0（API 23）及以上使用 AES/GCM，密钥由系统安全硬件保护；
 * Android 4.4.4–5.1（API 19–22）的 Keystore 只支持非对称算法，
 * 因此使用 RSA/ECB/PKCS1Padding 加密。
 * SharedPreferences 中只保存带前缀的 Base64 密文。
 */
public final class ApiKeyStore {

    private static final String KEYSTORE = "AndroidKeyStore";
    private static final String ALIAS_AES = "ai_chat_api_key_aes";
    private static final String ALIAS_RSA = "ai_chat_api_key_rsa";
    private static final String PREFIX_AES = "aes:";
    private static final String PREFIX_RSA = "rsa:";
    private static final int GCM_TAG_BITS = 128;

    private ApiKeyStore() {
    }

    /** 加密失败返回 null，由调用方决定降级策略。 */
    public static String encrypt(Context context, String plain) {
        if (plain == null || plain.length() == 0) {
            return "";
        }
        try {
            byte[] data;
            String prefix;
            if (Build.VERSION.SDK_INT >= 23) {
                data = encryptAes(plain);
                prefix = PREFIX_AES;
            } else {
                data = encryptRsa(context, plain);
                prefix = PREFIX_RSA;
            }
            return prefix + Base64.encodeToString(data, Base64.NO_WRAP);
        } catch (Exception e) {
            return null;
        }
    }

    /** 解密失败（例如密钥被系统清除）返回空字符串。 */
    public static String decrypt(Context context, String stored) {
        if (stored == null || stored.length() == 0) {
            return "";
        }
        try {
            if (stored.startsWith(PREFIX_AES)) {
                if (Build.VERSION.SDK_INT < 23) {
                    return "";
                }
                byte[] data = Base64.decode(stored.substring(PREFIX_AES.length()),
                        Base64.NO_WRAP);
                return decryptAes(data);
            }
            if (stored.startsWith(PREFIX_RSA)) {
                byte[] data = Base64.decode(stored.substring(PREFIX_RSA.length()),
                        Base64.NO_WRAP);
                return decryptRsa(context, data);
            }
        } catch (Exception ignored) {
        }
        return "";
    }

    private static SecretKey getAesKey() throws Exception {
        KeyStore keyStore = KeyStore.getInstance(KEYSTORE);
        keyStore.load(null);
        if (keyStore.containsAlias(ALIAS_AES)) {
            KeyStore.SecretKeyEntry entry =
                    (KeyStore.SecretKeyEntry) keyStore.getEntry(ALIAS_AES, null);
            if (entry != null) {
                return entry.getSecretKey();
            }
        }
        KeyGenerator generator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES, KEYSTORE);
        KeyGenParameterSpec spec = new KeyGenParameterSpec.Builder(ALIAS_AES,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build();
        generator.init(spec);
        return generator.generateKey();
    }

    private static byte[] encryptAes(String plain) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, getAesKey());
        byte[] iv = cipher.getIV();
        byte[] encrypted = cipher.doFinal(plain.getBytes("UTF-8"));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write(iv.length);
        output.write(iv);
        output.write(encrypted);
        return output.toByteArray();
    }

    private static String decryptAes(byte[] data) throws Exception {
        if (data.length < 2) {
            return "";
        }
        int ivLength = data[0] & 0xFF;
        if (ivLength <= 0 || data.length < 1 + ivLength) {
            return "";
        }
        byte[] iv = new byte[ivLength];
        System.arraycopy(data, 1, iv, 0, ivLength);
        byte[] encrypted = new byte[data.length - 1 - ivLength];
        System.arraycopy(data, 1 + ivLength, encrypted, 0, encrypted.length);

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, getAesKey(),
                new javax.crypto.spec.GCMParameterSpec(GCM_TAG_BITS, iv));
        return new String(cipher.doFinal(encrypted), "UTF-8");
    }

    private static KeyPair getRsaKeyPair(Context context) throws Exception {
        KeyStore keyStore = KeyStore.getInstance(KEYSTORE);
        keyStore.load(null);
        if (keyStore.containsAlias(ALIAS_RSA)) {
            KeyStore.PrivateKeyEntry entry =
                    (KeyStore.PrivateKeyEntry) keyStore.getEntry(ALIAS_RSA, null);
            if (entry != null) {
                return new KeyPair(entry.getCertificate().getPublicKey(),
                        entry.getPrivateKey());
            }
        }
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA", KEYSTORE);
        if (Build.VERSION.SDK_INT >= 23) {
            KeyGenParameterSpec spec = new KeyGenParameterSpec.Builder(ALIAS_RSA,
                    KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setEncryptionPaddings(
                            KeyProperties.ENCRYPTION_PADDING_RSA_PKCS1)
                    .setKeySize(2048)
                    .build();
            generator.initialize(spec);
        } else {
            Calendar start = Calendar.getInstance();
            Calendar end = Calendar.getInstance();
            end.add(Calendar.YEAR, 20);
            KeyPairGeneratorSpec spec = new KeyPairGeneratorSpec.Builder(context)
                    .setAlias(ALIAS_RSA)
                    .setSubject(new X500Principal("CN=AiChatAssistant"))
                    .setSerialNumber(BigInteger.valueOf(1))
                    .setStartDate(start.getTime())
                    .setEndDate(end.getTime())
                    .setKeySize(2048)
                    .build();
            generator.initialize(spec);
        }
        return generator.generateKeyPair();
    }

    private static byte[] encryptRsa(Context context, String plain) throws Exception {
        KeyPair keyPair = getRsaKeyPair(context);
        PublicKey publicKey = keyPair.getPublic();
        Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
        cipher.init(Cipher.ENCRYPT_MODE, publicKey);
        return cipher.doFinal(plain.getBytes("UTF-8"));
    }

    private static String decryptRsa(Context context, byte[] data) throws Exception {
        KeyPair keyPair = getRsaKeyPair(context);
        PrivateKey privateKey = keyPair.getPrivate();
        Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
        cipher.init(Cipher.DECRYPT_MODE, privateKey);
        return new String(cipher.doFinal(data), "UTF-8");
    }
}
