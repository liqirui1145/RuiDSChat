package com.aichat.assistant;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/** 全屏查看 Markdown 里的网络图片：后台下载、显示进度、失败提示，点图退出。 */
public class ImageViewerActivity extends Activity {

    private static final String EXTRA_URL = "image_url";
    private static final int MAX_BYTES = 12 * 1024 * 1024;

    private final Handler handler = new Handler(Looper.getMainLooper());

    static void open(Context context, String url) {
        Intent intent = new Intent(context, ImageViewerActivity.class);
        intent.putExtra(EXTRA_URL, url);
        if (!(context instanceof Activity)) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        }
        context.startActivity(intent);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_image_viewer);

        final ImageView image = (ImageView) findViewById(R.id.image_viewer_image);
        final ProgressBar progress = (ProgressBar) findViewById(R.id.image_viewer_progress);
        final TextView hint = (TextView) findViewById(R.id.image_viewer_hint);

        View.OnClickListener finish = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        };
        image.setOnClickListener(finish);
        hint.setOnClickListener(finish);

        String url = getIntent().getStringExtra(EXTRA_URL);
        if (url == null || url.length() == 0) {
            showError(progress, hint, "图片地址无效");
            return;
        }
        loadImage(url, image, progress, hint);
    }

    private void loadImage(final String url, final ImageView image, final ProgressBar progress,
                           final TextView hint) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                HttpURLConnection connection = null;
                try {
                    connection = (HttpURLConnection) new URL(url).openConnection();
                    connection.setConnectTimeout(20000);
                    connection.setReadTimeout(30000);
                    connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Android)");
                    connection.setInstanceFollowRedirects(true);
                    int code = connection.getResponseCode();
                    if (code < 200 || code >= 300) {
                        throw new Exception("HTTP " + code);
                    }
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    InputStream in = connection.getInputStream();
                    byte[] buffer = new byte[8192];
                    int read;
                    int total = 0;
                    while ((read = in.read(buffer)) != -1) {
                        total += read;
                        if (total > MAX_BYTES) {
                            throw new Exception("图片超过 12MB");
                        }
                        out.write(buffer, 0, read);
                    }
                    in.close();
                    final Bitmap bitmap = decode(out.toByteArray());
                    if (bitmap == null) {
                        throw new Exception("无法识别图片格式");
                    }
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            if (isFinishing()) {
                                bitmap.recycle();
                                return;
                            }
                            progress.setVisibility(View.GONE);
                            image.setImageBitmap(bitmap);
                        }
                    });
                } catch (final Exception e) {
                    String message = e.getMessage();
                    if (message == null || message.trim().length() == 0) {
                        message = "图片加载失败";
                    }
                    final String detail = message;
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            if (!isFinishing()) {
                                showError(progress, hint, "图片加载失败：" + detail);
                            }
                        }
                    });
                } finally {
                    if (connection != null) {
                        connection.disconnect();
                    }
                }
            }
        }).start();
    }

    /** 先读尺寸再采样解码，避免大图直接爆内存。 */
    private static Bitmap decode(byte[] data) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return null;
        }
        int sample = 1;
        while (Math.max(bounds.outWidth / sample, bounds.outHeight / sample) > 2400) {
            sample *= 2;
        }
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sample;
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        return BitmapFactory.decodeByteArray(data, 0, data.length, options);
    }

    private void showError(ProgressBar progress, TextView hint, String message) {
        progress.setVisibility(View.GONE);
        hint.setText(message);
        hint.setVisibility(View.VISIBLE);
    }
}
