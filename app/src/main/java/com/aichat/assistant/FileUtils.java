package com.aichat.assistant;

import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.util.Base64;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

public final class FileUtils {

    private static final int MAX_TEXT_CHARS = 30000;
    private static final int MAX_TEXT_BYTES = 2 * 1024 * 1024;

    private static final Set<String> TEXT_EXTENSIONS = new HashSet<String>(Arrays.asList(
            "txt", "md", "markdown", "json", "jsonl", "xml", "csv", "tsv", "log", "ini", "conf",
            "yaml", "yml", "properties", "html", "htm", "css", "js", "jsx", "ts", "tsx", "vue",
            "java", "kt", "kts", "py", "rb", "go", "rs", "c", "h", "cpp", "hpp", "cs", "php",
            "swift", "m", "sql", "sh", "bash", "zsh", "bat", "ps1", "gradle", "toml", "gitignore"
    ));

    private static final Set<String> IMAGE_EXTENSIONS = new HashSet<String>(Arrays.asList(
            "jpg", "jpeg", "png", "webp", "gif", "bmp"
    ));

    private FileUtils() {
    }

    public static String getDisplayName(Context context, Uri uri) {
        if (uri == null) {
            return "文件";
        }
        String name = null;
        Cursor cursor = null;
        try {
            cursor = context.getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME},
                    null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0) {
                    name = cursor.getString(index);
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        if (name == null || name.length() == 0) {
            name = uri.getLastPathSegment();
        }
        if (name == null || name.length() == 0) {
            name = "文件";
        }
        int slash = name.lastIndexOf('/');
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        return name;
    }

    public static String getMimeType(Context context, Uri uri) {
        try {
            return context.getContentResolver().getType(uri);
        } catch (Exception e) {
            return null;
        }
    }

    public static boolean isImage(String name, String mimeType) {
        if (IMAGE_EXTENSIONS.contains(extension(name))) {
            return true;
        }
        if (mimeType == null) {
            return false;
        }
        String mime = mimeType.toLowerCase(Locale.US);
        return "image/jpeg".equals(mime) || "image/jpg".equals(mime) || "image/png".equals(mime)
                || "image/webp".equals(mime) || "image/gif".equals(mime) || "image/bmp".equals(mime)
                || "image/x-ms-bmp".equals(mime);
    }

    public static boolean isTextFile(String name, String mimeType) {
        String ext = extension(name);
        if (TEXT_EXTENSIONS.contains(ext)) {
            return true;
        }
        if (mimeType == null) {
            return false;
        }
        String mime = mimeType.toLowerCase(Locale.US);
        return mime.startsWith("text/") || mime.contains("json") || mime.contains("xml")
                || mime.contains("javascript") || mime.contains("yaml") || mime.contains("x-sh");
    }

    public static String readText(Context context, Uri uri) throws IOException {
        InputStream in = null;
        try {
            in = context.getContentResolver().openInputStream(uri);
            if (in == null) {
                throw new IOException("无法打开文件");
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            int total = 0;
            boolean truncatedBytes = false;
            while ((read = in.read(buffer)) != -1) {
                if (total + read > MAX_TEXT_BYTES) {
                    int remain = MAX_TEXT_BYTES - total;
                    if (remain > 0) {
                        out.write(buffer, 0, remain);
                    }
                    truncatedBytes = true;
                    break;
                }
                out.write(buffer, 0, read);
                total += read;
            }
            byte[] data = out.toByteArray();
            checkTextLike(data);
            String text = decodeText(data);
            if (text.length() > MAX_TEXT_CHARS) {
                text = text.substring(0, MAX_TEXT_CHARS)
                        + "\n\n（文件内容过长，已截断显示部分内容）";
            } else if (truncatedBytes) {
                text = text + "\n\n（文件内容过长，已截断显示部分内容）";
            }
            return text;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    public static String imageToDataUri(Context context, Uri uri) throws IOException {
        Bitmap bitmap = decodeSampledBitmap(context, uri, 1600);
        if (bitmap == null) {
            throw new IOException("无法解码该图片，请换一张图片试试");
        }
        try {
            int maxSide = Math.max(bitmap.getWidth(), bitmap.getHeight());
            if (maxSide > 1280) {
                float scale = 1280f / maxSide;
                Bitmap scaled = Bitmap.createScaledBitmap(bitmap,
                        Math.max(1, Math.round(bitmap.getWidth() * scale)),
                        Math.max(1, Math.round(bitmap.getHeight() * scale)), true);
                if (scaled != bitmap) {
                    bitmap.recycle();
                    bitmap = scaled;
                }
            }
            if (bitmap.hasAlpha()) {
                Bitmap background = Bitmap.createBitmap(bitmap.getWidth(), bitmap.getHeight(),
                        Bitmap.Config.ARGB_8888);
                Canvas canvas = new Canvas(background);
                canvas.drawColor(Color.WHITE);
                canvas.drawBitmap(bitmap, 0f, 0f, null);
                bitmap.recycle();
                bitmap = background;
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)) {
                throw new IOException("图片压缩失败");
            }
            byte[] bytes = out.toByteArray();
            return "data:image/jpeg;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP);
        } finally {
            if (!bitmap.isRecycled()) {
                bitmap.recycle();
            }
        }
    }

    public static Bitmap loadThumbnail(Context context, Uri uri) {
        try {
            return decodeSampledBitmap(context, uri, 200);
        } catch (Exception e) {
            return null;
        }
    }

    private static Bitmap decodeSampledBitmap(Context context, Uri uri, int maxSide) throws IOException {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        InputStream in = null;
        try {
            in = context.getContentResolver().openInputStream(uri);
            if (in == null) {
                throw new IOException("无法打开图片");
            }
            BitmapFactory.decodeStream(in, null, options);
        } finally {
            closeQuietly(in);
        }
        if (options.outWidth <= 0 || options.outHeight <= 0) {
            throw new IOException("无法识别该图片格式");
        }
        int sampleSize = 1;
        while (Math.max(options.outWidth / sampleSize, options.outHeight / sampleSize) > maxSide * 2) {
            sampleSize *= 2;
        }
        BitmapFactory.Options decodeOptions = new BitmapFactory.Options();
        decodeOptions.inSampleSize = sampleSize;
        decodeOptions.inPreferredConfig = Bitmap.Config.ARGB_8888;
        try {
            in = context.getContentResolver().openInputStream(uri);
            return BitmapFactory.decodeStream(in, null, decodeOptions);
        } finally {
            closeQuietly(in);
        }
    }

    private static void checkTextLike(byte[] data) throws IOException {
        int limit = Math.min(data.length, 4096);
        if (limit == 0) {
            return;
        }
        int suspicious = 0;
        for (int i = 0; i < limit; i++) {
            byte b = data[i];
            if (b == 0 || b < 9 || (b > 13 && b < 32)) {
                suspicious++;
            }
        }
        if (suspicious > limit / 20) {
            throw new IOException("该文件不是可读取的文本文件");
        }
    }

    private static String decodeText(byte[] data) {
        String utf8 = new String(data, Charset.forName("UTF-8"));
        int bad = 0;
        int maxCheck = Math.min(utf8.length(), 2000);
        for (int i = 0; i < maxCheck; i++) {
            if (utf8.charAt(i) == '\uFFFD') {
                bad++;
            }
        }
        if (bad > Math.max(3, maxCheck / 100)) {
            try {
                return new String(data, Charset.forName("GBK"));
            } catch (Exception ignored) {
            }
        }
        return utf8;
    }

    private static String extension(String name) {
        if (name == null) {
            return "";
        }
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) {
            return "";
        }
        return name.substring(dot + 1).toLowerCase(Locale.US);
    }

    private static void closeQuietly(InputStream in) {
        if (in != null) {
            try {
                in.close();
            } catch (IOException ignored) {
            }
        }
    }
}
