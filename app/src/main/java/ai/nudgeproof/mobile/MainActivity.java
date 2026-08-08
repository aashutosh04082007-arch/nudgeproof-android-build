package ai.nudgeproof.mobile;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Message;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

public final class MainActivity extends Activity {
    private static final String START_URL = "https://projectlinks.pages.dev/mobile-app.html?apk=1&android=1&v=4.1.0";
    private static final int FILE_CHOOSER = 4137;

    private FrameLayout root;
    private WebView webView;
    private LinearLayout statusPanel;
    private ProgressBar progress;
    private TextView statusTitle;
    private TextView statusText;
    private Button retryButton;
    private ValueCallback<Uri[]> fileCallback;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Color.rgb(38, 4, 8));
        getWindow().setNavigationBarColor(Color.rgb(20, 3, 5));
        buildUi();
        try {
            buildWebView();
            loadPortal();
        } catch (Throwable error) {
            showError("Android WebView could not start", error.getClass().getSimpleName() + ": " + safe(error.getMessage()));
        }
    }

    private void buildUi() {
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.rgb(20, 3, 5));
        setContentView(root);

        statusPanel = new LinearLayout(this);
        statusPanel.setOrientation(LinearLayout.VERTICAL);
        statusPanel.setGravity(Gravity.CENTER);
        statusPanel.setPadding(dp(28), dp(28), dp(28), dp(28));
        statusPanel.setBackgroundColor(Color.rgb(20, 3, 5));
        root.addView(statusPanel, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        TextView brand = new TextView(this);
        brand.setText("NUDGEPROOF AI");
        brand.setTextColor(Color.rgb(255, 82, 104));
        brand.setTextSize(13);
        brand.setLetterSpacing(0.18f);
        brand.setGravity(Gravity.CENTER);
        statusPanel.addView(brand, matchWrap());

        statusTitle = new TextView(this);
        statusTitle.setText("Starting protection center");
        statusTitle.setTextColor(Color.WHITE);
        statusTitle.setTextSize(25);
        statusTitle.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams titleParams = matchWrap();
        titleParams.setMargins(0, dp(16), 0, dp(8));
        statusPanel.addView(statusTitle, titleParams);

        statusText = new TextView(this);
        statusText.setText("Connecting securely to your NudgeProof account…");
        statusText.setTextColor(Color.rgb(218, 184, 190));
        statusText.setTextSize(15);
        statusText.setGravity(Gravity.CENTER);
        statusPanel.addView(statusText, matchWrap());

        progress = new ProgressBar(this);
        LinearLayout.LayoutParams progressParams = new LinearLayout.LayoutParams(dp(44), dp(44));
        progressParams.setMargins(0, dp(24), 0, 0);
        progressParams.gravity = Gravity.CENTER_HORIZONTAL;
        statusPanel.addView(progress, progressParams);

        retryButton = new Button(this);
        retryButton.setText("RETRY");
        retryButton.setTextColor(Color.WHITE);
        retryButton.setBackgroundColor(Color.rgb(213, 24, 52));
        retryButton.setVisibility(View.GONE);
        retryButton.setOnClickListener(v -> loadPortal());
        LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(dp(180), dp(52));
        buttonParams.setMargins(0, dp(24), 0, 0);
        buttonParams.gravity = Gravity.CENTER_HORIZONTAL;
        statusPanel.addView(retryButton, buttonParams);
    }

    private void buildWebView() {
        webView = new WebView(this);
        webView.setBackgroundColor(Color.rgb(20, 3, 5));
        webView.setVisibility(View.INVISIBLE);
        root.addView(webView, 0, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        s.setSupportMultipleWindows(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(true);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setSupportZoom(false);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setMediaPlaybackRequiresUserGesture(true);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);

        String ua = s.getUserAgentString();
        if (ua != null && !ua.contains("NudgeProofAndroid")) {
            s.setUserAgentString(ua + " NudgeProofAndroid/4.1.0 Android11Compatible");
        }

        CookieManager cookies = CookieManager.getInstance();
        cookies.setAcceptCookie(true);
        cookies.setAcceptThirdPartyCookies(webView, true);

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = callback;
                try {
                    startActivityForResult(params.createIntent(), FILE_CHOOSER);
                    return true;
                } catch (ActivityNotFoundException e) {
                    fileCallback = null;
                    Toast.makeText(MainActivity.this, "No file picker is available", Toast.LENGTH_LONG).show();
                    return false;
                }
            }

            @Override
            public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture, Message resultMsg) {
                final WebView popup = new WebView(MainActivity.this);
                WebSettings popupSettings = popup.getSettings();
                popupSettings.setJavaScriptEnabled(true);
                popupSettings.setDomStorageEnabled(true);
                popupSettings.setJavaScriptCanOpenWindowsAutomatically(true);
                popup.setWebViewClient(new WebViewClient() {
                    private boolean transferred = false;

                    private void transfer(String url) {
                        if (transferred || url == null || url.isEmpty() || "about:blank".equals(url)) return;
                        transferred = true;
                        webView.loadUrl(url);
                        popup.stopLoading();
                        popup.destroy();
                    }

                    @Override
                    public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest request) {
                        transfer(request.getUrl().toString());
                        return true;
                    }

                    @Override
                    public void onPageStarted(WebView v, String url, Bitmap favicon) {
                        super.onPageStarted(v, url, favicon);
                        transfer(url);
                    }
                });

                WebView.WebViewTransport transport = (WebView.WebViewTransport) resultMsg.obj;
                transport.setWebView(popup);
                resultMsg.sendToTarget();
                return true;
            }
        });

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return handleNavigation(request.getUrl());
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                injectAndroidPatches(view);
                statusPanel.setVisibility(View.GONE);
                webView.setVisibility(View.VISIBLE);
                progress.setVisibility(View.GONE);
                CookieManager.getInstance().flush();
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) {
                    showError("Could not load NudgeProof", "Check internet connection and tap Retry. The app will stay open.");
                }
            }
        });

        webView.setDownloadListener((url, userAgent, contentDisposition, mimeType, contentLength) ->
                startDownload(url, userAgent, contentDisposition, mimeType));
    }

    private boolean handleNavigation(Uri uri) {
        if (uri == null) return false;
        String scheme = uri.getScheme();
        if (scheme == null) return false;

        if ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme) || "about".equalsIgnoreCase(scheme)) {
            return false;
        }

        if ("intent".equalsIgnoreCase(scheme)) {
            try {
                Intent intent = Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME);
                if (intent.resolveActivity(getPackageManager()) != null) startActivity(intent);
            } catch (Throwable ignored) { }
            return true;
        }

        try {
            startActivity(new Intent(Intent.ACTION_VIEW, uri));
        } catch (Throwable ignored) { }
        return true;
    }

    private void injectAndroidPatches(WebView view) {
        String js = "(function(){" +
                "try{" +
                "if(!document.getElementById('np-android-red-theme')){" +
                "var s=document.createElement('style');s.id='np-android-red-theme';" +
                "s.textContent=':root{--primary:#e11d3f!important;--primary-2:#ff405f!important;--accent:#ff3150!important;--brand:#e11d3f!important;--violet:#e11d3f!important;--purple:#e11d3f!important;--blue:#e11d3f!important;}html,body{background:#140305!important;}body{accent-color:#e11d3f!important;}button:not(.safe):not(.success),.btn:not(.safe):not(.success),[role=button]:not(.safe):not(.success){border-color:#ef3452!important;}header,nav,.navbar,.topbar,.sidebar{--primary:#e11d3f!important;--accent:#ff3150!important;}a{--link:#ff647d;}input:focus,textarea:focus,select:focus{border-color:#ef3452!important;box-shadow:0 0 0 2px rgba(225,29,63,.20)!important;}';" +
                "document.head.appendChild(s);" +
                "var m=document.querySelector('meta[name=theme-color]');if(m)m.setAttribute('content','#260408');" +
                "}" +
                "if(!window.__NP_ANDROID_NAV_PATCH__){" +
                "window.__NP_ANDROID_NAV_PATCH__=true;" +
                "document.addEventListener('click',function(e){var a=e.target&&e.target.closest?e.target.closest('a[target=\"_blank\"]'):null;if(a&&a.href){e.preventDefault();location.href=a.href;}},true);" +
                "}" +
                "}catch(e){}" +
                "})();";
        view.evaluateJavascript(js, null);
    }

    private void loadPortal() {
        if (webView == null) return;
        retryButton.setVisibility(View.GONE);
        progress.setVisibility(View.VISIBLE);
        statusPanel.setVisibility(View.VISIBLE);
        webView.setVisibility(View.INVISIBLE);
        statusTitle.setText("Starting protection center");
        statusText.setText(isOnline() ? "Connecting securely to your NudgeProof account…" : "No internet connection detected. You can retry when connected.");
        if (!isOnline()) {
            progress.setVisibility(View.GONE);
            retryButton.setVisibility(View.VISIBLE);
            return;
        }
        webView.loadUrl(START_URL);
    }

    private void showError(String title, String message) {
        statusPanel.setVisibility(View.VISIBLE);
        if (webView != null) webView.setVisibility(View.INVISIBLE);
        progress.setVisibility(View.GONE);
        retryButton.setVisibility(View.VISIBLE);
        statusTitle.setText(title);
        statusText.setText(message);
    }

    private boolean isOnline() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkInfo info = cm == null ? null : cm.getActiveNetworkInfo();
            return info != null && info.isConnected();
        } catch (Throwable ignored) {
            return true;
        }
    }

    private void startDownload(String url, String userAgent, String contentDisposition, String mimeType) {
        try {
            DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
            request.addRequestHeader("User-Agent", userAgent == null ? "" : userAgent);
            String cookies = CookieManager.getInstance().getCookie(url);
            if (cookies != null) request.addRequestHeader("Cookie", cookies);
            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            String filename = android.webkit.URLUtil.guessFileName(url, contentDisposition, mimeType);
            request.setTitle(filename);
            request.setDescription("NudgeProof download");
            request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, filename);
            DownloadManager manager = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
            if (manager != null) manager.enqueue(request);
            Toast.makeText(this, "Download started", Toast.LENGTH_SHORT).show();
        } catch (Throwable error) {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
            } catch (Throwable ignored) { }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == FILE_CHOOSER && fileCallback != null) {
            Uri[] result = WebChromeClient.FileChooserParams.parseResult(resultCode, data);
            fileCallback.onReceiveValue(result);
            fileCallback = null;
        }
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.getVisibility() == View.VISIBLE && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onPause() {
        CookieManager.getInstance().flush();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            webView.stopLoading();
            webView.loadUrl("about:blank");
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static String safe(String value) {
        return value == null || value.trim().isEmpty() ? "Unknown error" : value;
    }
}
