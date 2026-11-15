package com.clippingalex.clipcaptions;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.DownloadListener;
import android.webkit.JavascriptInterface;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

/** ClipCaptions: envuelve la web de tu servidor en una app de Android. */
public class MainActivity extends Activity {
    private static final String PREFS = "clipcaptions";
    private static final String KEY_SERVER = "server";
    private static final String SETUP = "file:///android_asset/setup.html";
    private static final String ERROR = "file:///android_asset/error.html";
    private static final int REQ_FILE = 1;

    private WebView web;
    private FrameLayout root;
    private ValueCallback<Uri[]> fileCallback;
    private View customView;
    private WebChromeClient.CustomViewCallback customCallback;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        root = new FrameLayout(this);
        web = new WebView(this);
        root.addView(web, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowContentAccess(true);
        web.setBackgroundColor(0xFF0B0B14);
        web.addJavascriptInterface(new Bridge(), "AndroidBridge");

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                Uri u = r.getUrl();
                if ("file".equals(u.getScheme())) return false;
                String server = getServer();
                if (!server.isEmpty() && Uri.parse(server).getHost() != null
                        && Uri.parse(server).getHost().equals(u.getHost())) return false;
                try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (Exception ignored) { }
                return true;
            }

            @Override
            public void onReceivedError(WebView v, WebResourceRequest r, WebResourceError e) {
                if (r.isForMainFrame()) v.loadUrl(ERROR);
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView w, ValueCallback<Uri[]> cb, FileChooserParams p) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = cb;
                Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("video/*");
                try {
                    startActivityForResult(Intent.createChooser(i, "Elige un video"), REQ_FILE);
                } catch (Exception e) {
                    fileCallback = null;
                    cb.onReceiveValue(null);
                    return false;
                }
                return true;
            }

            @Override
            public void onShowCustomView(View view, CustomViewCallback cb) {
                if (customView != null) { cb.onCustomViewHidden(); return; }
                customView = view;
                customCallback = cb;
                root.addView(view, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                web.setVisibility(View.GONE);
            }

            @Override
            public void onHideCustomView() {
                if (customView == null) return;
                root.removeView(customView);
                customView = null;
                web.setVisibility(View.VISIBLE);
                if (customCallback != null) customCallback.onCustomViewHidden();
            }
        });

        web.setDownloadListener(new DownloadListener() {
            @Override
            public void onDownloadStart(String url, String ua, String cd, String mime, long len) {
                try {
                    String name = URLUtil.guessFileName(url, cd, mime);
                    DownloadManager.Request r = new DownloadManager.Request(Uri.parse(url));
                    r.setMimeType(mime);
                    r.addRequestHeader("User-Agent", ua);
                    r.setTitle(name);
                    r.setNotificationVisibility(
                            DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                    r.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name);
                    ((DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE)).enqueue(r);
                    Toast.makeText(MainActivity.this,
                            "Descargando en la carpeta Descargas…", Toast.LENGTH_LONG).show();
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this,
                            "No se pudo descargar: " + e.getMessage(), Toast.LENGTH_LONG).show();
                }
            }
        });

        loadStart();
    }

    private SharedPreferences prefs() { return getSharedPreferences(PREFS, MODE_PRIVATE); }

    private String getServer() { return prefs().getString(KEY_SERVER, ""); }

    private void loadStart() {
        String server = getServer();
        web.loadUrl(server.isEmpty() ? SETUP : server);
    }

    /** "192.168.1.5:8000" -> "http://192.168.1.5:8000"; dominios -> https. */
    static String normalize(String raw) {
        String u = raw == null ? "" : raw.trim();
        if (u.isEmpty()) return "";
        if (!u.matches("(?i)^https?://.*")) {
            boolean local = u.matches("^(localhost|\\d{1,3}(\\.\\d{1,3}){3}|[^/]*\\.local)(:\\d+)?(/.*)?$");
            u = (local ? "http://" : "https://") + u;
        }
        while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        return u;
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_FILE && fileCallback != null) {
            Uri[] out = null;
            if (res == RESULT_OK && data != null && data.getData() != null) {
                out = new Uri[]{data.getData()};
            }
            fileCallback.onReceiveValue(out);
            fileCallback = null;
        }
    }

    @Override
    public void onBackPressed() {
        if (customView != null) {
            web.getWebChromeClient().onHideCustomView();
        } else if (web.canGoBack() && web.getUrl() != null && !web.getUrl().startsWith("file:")) {
            web.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        if (web != null) { root.removeView(web); web.destroy(); }
        super.onDestroy();
    }

    /** Puente que usan setup.html, error.html y la propia web de ClipCaptions. */
    private class Bridge {
        @JavascriptInterface
        public String getServer() { return MainActivity.this.getServer(); }

        @JavascriptInterface
        public void saveServer(final String raw) {
            final String url = normalize(raw);
            if (url.isEmpty()) return;
            prefs().edit().putString(KEY_SERVER, url).apply();
            runOnUiThread(new Runnable() { public void run() { web.loadUrl(url); } });
        }

        @JavascriptInterface
        public void changeServer() {
            runOnUiThread(new Runnable() { public void run() { web.loadUrl(SETUP); } });
        }

        @JavascriptInterface
        public void retry() {
            runOnUiThread(new Runnable() { public void run() { loadStart(); } });
        }

        /** Comprueba {servidor}/healthz y avisa a la pagina con window.onTest(ok, mensaje). */
        @JavascriptInterface
        public void testServer(final String raw) {
            final String base = normalize(raw);
            new Thread(new Runnable() {
                public void run() {
                    boolean ok = false;
                    String msg;
                    try {
                        HttpURLConnection c = (HttpURLConnection) new URL(base + "/healthz").openConnection();
                        c.setConnectTimeout(6000);
                        c.setReadTimeout(6000);
                        int code = c.getResponseCode();
                        String body = "";
                        if (code == 200) {
                            BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream()));
                            body = r.readLine();
                            r.close();
                        }
                        ok = code == 200 && body != null && body.contains("ok");
                        msg = ok ? "Conectado correctamente" : "Responde, pero no parece ClipCaptions (código " + code + ")";
                    } catch (Exception e) {
                        msg = "No se pudo conectar: " + e.getMessage();
                    }
                    final String js = "window.onTest(" + ok + "," + org.json.JSONObject.quote(msg) + ")";
                    runOnUiThread(new Runnable() {
                        public void run() { web.evaluateJavascript(js, null); }
                    });
                }
            }).start();
        }
    }
}
