package com.toonstudio.app;

import android.app.Activity;
import android.os.Bundle;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public class MainActivity extends Activity {
    private WebView web;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        web = new WebView(this);
        setContentView(web);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        web.setWebViewClient(new WebViewClient());
        web.addJavascriptInterface(new Bridge(), "Native");
        web.loadUrl("file:///android_asset/index.html");
    }

    @Override
    public void onBackPressed() {
        if (web.canGoBack()) web.goBack(); else super.onBackPressed();
    }

    private void reply(final String cbId, final String json) {
        final String js = "window.__cb(" + quote(cbId) + "," + quote(json) + ")";
        runOnUiThread(() -> web.evaluateJavascript(js, null));
    }

    private static String quote(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\u2028': sb.append("\\u2028"); break;
                case '\u2029': sb.append("\\u2029"); break;
                default: sb.append(c);
            }
        }
        return sb.append("\"").toString();
    }

    private static String readAll(HttpURLConnection c) throws Exception {
        InputStream in = c.getResponseCode() >= 400 ? c.getErrorStream() : c.getInputStream();
        if (in == null) return "";
        java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
        in.close();
        return o.toString("UTF-8");
    }

    private static String esc(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "");
    }

    private static String result(int status, String body) {
        return "{\"status\":" + status + ",\"body\":\"" + esc(body) + "\"}";
    }

    public class Bridge {
        /** Generic HTTP; headers are "Key: Value" lines. */
        @JavascriptInterface
        public void http(final String cbId, final String method, final String url,
                         final String headers, final String body) {
            new Thread(() -> {
                try {
                    HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                    c.setRequestMethod(method);
                    c.setConnectTimeout(30000);
                    c.setReadTimeout(300000);
                    for (String line : headers.split("\n")) {
                        int i = line.indexOf(':');
                        if (i > 0) c.setRequestProperty(line.substring(0, i).trim(), line.substring(i + 1).trim());
                    }
                    if (body != null && !body.isEmpty()) {
                        c.setDoOutput(true);
                        try (OutputStream os = c.getOutputStream()) {
                            os.write(body.getBytes(StandardCharsets.UTF_8));
                        }
                    }
                    int code = c.getResponseCode();
                    reply(cbId, result(code, readAll(c)));
                } catch (Exception e) {
                    reply(cbId, result(0, String.valueOf(e)));
                }
            }).start();
        }

        /** Downloads videoUrl, then uploads it to YouTube (resumable upload). */
        @JavascriptInterface
        public void uploadYoutube(final String cbId, final String videoUrl,
                                  final String accessToken, final String metaJson) {
            new Thread(() -> {
                File tmp = new File(getCacheDir(), "episode.mp4");
                try {
                    HttpURLConnection d = (HttpURLConnection) new URL(videoUrl).openConnection();
                    d.setRequestProperty("ngrok-skip-browser-warning", "1");
                    try (InputStream in = d.getInputStream(); FileOutputStream out = new FileOutputStream(tmp)) {
                        byte[] buf = new byte[65536];
                        int n;
                        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                    }

                    URL init = new URL("https://www.googleapis.com/upload/youtube/v3/videos?uploadType=resumable&part=snippet,status");
                    HttpURLConnection c = (HttpURLConnection) init.openConnection();
                    c.setRequestMethod("POST");
                    c.setRequestProperty("Authorization", "Bearer " + accessToken);
                    c.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                    c.setRequestProperty("X-Upload-Content-Type", "video/mp4");
                    c.setRequestProperty("X-Upload-Content-Length", String.valueOf(tmp.length()));
                    c.setDoOutput(true);
                    try (OutputStream os = c.getOutputStream()) {
                        os.write(metaJson.getBytes(StandardCharsets.UTF_8));
                    }
                    if (c.getResponseCode() >= 400) {
                        reply(cbId, result(c.getResponseCode(), readAll(c)));
                        return;
                    }
                    String loc = c.getHeaderField("Location");

                    HttpURLConnection u = (HttpURLConnection) new URL(loc).openConnection();
                    u.setRequestMethod("PUT");
                    u.setRequestProperty("Content-Type", "video/mp4");
                    u.setFixedLengthStreamingMode(tmp.length());
                    u.setDoOutput(true);
                    u.setReadTimeout(600000);
                    try (java.io.FileInputStream in = new java.io.FileInputStream(tmp);
                         OutputStream os = u.getOutputStream()) {
                        byte[] buf = new byte[65536];
                        int n;
                        while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
                    }
                    reply(cbId, result(u.getResponseCode(), readAll(u)));
                } catch (Exception e) {
                    reply(cbId, result(0, String.valueOf(e)));
                } finally {
                    tmp.delete();
                }
            }).start();
        }
    }
}
