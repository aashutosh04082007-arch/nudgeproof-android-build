package ai.nudgeproof.mobile;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.UUID;
import java.util.regex.Pattern;

public final class SecureBrowserActivity extends Activity {
    private static final String PORTAL_ORIGIN = "https://projectlinks.pages.dev";
    private static final long SCAN_INTERVAL_MS = 4000L;

    private WebView webView;
    private EditText address;
    private TextView riskBadge;
    private TextView scanState;
    private ProgressBar loadProgress;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean scanningEnabled = true;
    private String warnedUrl = "";
    private int warnedRisk = -1;
    private String lastSavedUrl = "";
    private int lastSavedRisk = -1;
    private RiskResult latest = new RiskResult(0, "Safe", "No high-risk signal detected.");

    private static final class Signal {
        final Pattern pattern;
        final int score;
        final String reason;
        Signal(String regex, int score, String reason) {
            this.pattern = Pattern.compile(regex, Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
            this.score = score;
            this.reason = reason;
        }
    }

    private static final Signal[] SIGNALS = new Signal[] {
            new Signal("(share|send|tell|enter|provide)\\s+(your\\s+)?(otp|one[- ]time password|upi pin|atm pin|card pin)", 42, "The page asks for a secret OTP or PIN."),
            new Signal("(enter|share|provide).{0,25}(cvv|card pin|full card details|bank password|netbanking password)", 40, "Sensitive banking credentials are requested."),
            new Signal("(install|download|open).{0,30}(anydesk|teamviewer|quicksupport|remote access app)", 40, "Remote-access software is being requested."),
            new Signal("(seed phrase|recovery phrase|wallet phrase|private key).{0,30}(enter|share|verify|submit)", 45, "A crypto recovery phrase or private key is requested."),
            new Signal("(gift card|crypto only|bitcoin|usdt|wire transfer|pay outside|direct transfer|urgent upi|scan qr now)", 36, "Hard-to-reverse or off-platform payment pressure detected."),
            new Signal("(account|kyc|bank|sim|parcel).{0,35}(suspended|blocked|closed|deactivated).{0,40}(immediately|now|today|verify)", 28, "Account-loss urgency is being used to pressure action."),
            new Signal("(police|customs|income tax|rbi|bank support|refund officer|courier officer).{0,45}(pay|fine|transfer|otp|verify)", 38, "Authority/support language is combined with payment or verification pressure."),
            new Signal("(won|winner|lottery|prize|cashback).{0,45}(claim|fee|processing|pay|bank details)", 27, "Unexpected prize or lottery claim detected."),
            new Signal("(job|interview|recruitment|work from home).{0,55}(registration fee|security deposit|pay|training fee)", 28, "A job opportunity requests an advance fee."),
            new Signal("(guaranteed|risk[- ]free|double your|fixed daily).{0,30}(return|profit|income|investment)", 30, "Guaranteed or risk-free investment returns detected."),
            new Signal("(platform fee|convenience fee|processing fee|service fee|fees apply|additional charge|handling fee)", 14, "Extra fee language may change the final payable amount."),
            new Signal("(auto.?renew|renews automatically|recurring billing|trial ends.{0,25}charge|monthly subscription)", 15, "Recurring billing terms were detected."),
            new Signal("(limited time|last chance|hurry|expires in|only \\d+ left|act now|immediately)", 7, "Urgency language may reduce verification time.")
    };

    private final Runnable continuousScanner = new Runnable() {
        @Override public void run() {
            if (scanningEnabled && webView != null) scanVisiblePage(false);
            handler.postDelayed(this, SCAN_INTERVAL_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Color.rgb(42, 0, 6));
        getWindow().setNavigationBarColor(Color.BLACK);
        buildUi();
        configureWebView();
        handler.postDelayed(continuousScanner, 1600L);
    }

    private void buildUi() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(Color.rgb(8, 1, 2));
        setContentView(page);

        LinearLayout brandRow = new LinearLayout(this);
        brandRow.setGravity(Gravity.CENTER_VERTICAL);
        brandRow.setPadding(dp(10), dp(6), dp(10), dp(6));
        brandRow.setBackgroundColor(Color.rgb(18, 1, 3));
        page.addView(brandRow, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(58)));

        ImageView icon = new ImageView(this);
        icon.setImageResource(R.drawable.nudgeproof_icon);
        LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(dp(44), dp(44));
        iconParams.setMargins(0, 0, dp(8), 0);
        brandRow.addView(icon, iconParams);

        LinearLayout brandText = new LinearLayout(this);
        brandText.setOrientation(LinearLayout.VERTICAL);
        brandRow.addView(brandText, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView title = new TextView(this);
        title.setText("NudgeProof AI");
        title.setTextColor(Color.WHITE);
        title.setTextSize(18);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        brandText.addView(title);

        scanState = new TextView(this);
        scanState.setText("● LIVE  Continuous Protection Active");
        scanState.setTextColor(Color.rgb(255, 64, 82));
        scanState.setTextSize(11);
        brandText.addView(scanState);

        riskBadge = new TextView(this);
        riskBadge.setText("0% SAFE");
        riskBadge.setTextColor(Color.rgb(126, 255, 164));
        riskBadge.setTextSize(13);
        riskBadge.setTypeface(null, android.graphics.Typeface.BOLD);
        riskBadge.setGravity(Gravity.CENTER);
        riskBadge.setPadding(dp(10), dp(7), dp(10), dp(7));
        riskBadge.setBackgroundColor(Color.rgb(28, 7, 9));
        brandRow.addView(riskBadge, new LinearLayout.LayoutParams(dp(100), dp(40)));

        LinearLayout addressRow = new LinearLayout(this);
        addressRow.setGravity(Gravity.CENTER_VERTICAL);
        addressRow.setPadding(dp(8), dp(5), dp(8), dp(5));
        addressRow.setBackgroundColor(Color.rgb(10, 2, 3));
        page.addView(addressRow, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)));

        Button portal = new Button(this);
        portal.setText("PORTAL");
        portal.setTextSize(10);
        portal.setTextColor(Color.WHITE);
        portal.setBackgroundColor(Color.rgb(65, 8, 14));
        portal.setOnClickListener(v -> finish());
        addressRow.addView(portal, new LinearLayout.LayoutParams(dp(76), dp(44)));

        address = new EditText(this);
        address.setSingleLine(true);
        address.setHint("Enter website, e.g. example.com");
        address.setHintTextColor(Color.rgb(150, 128, 132));
        address.setTextColor(Color.WHITE);
        address.setTextSize(13);
        address.setPadding(dp(10), 0, dp(10), 0);
        address.setBackgroundColor(Color.rgb(24, 6, 9));
        LinearLayout.LayoutParams addressParams = new LinearLayout.LayoutParams(0, dp(44), 1f);
        addressParams.setMargins(dp(6), 0, dp(6), 0);
        addressRow.addView(address, addressParams);
        address.setOnEditorActionListener((v, actionId, event) -> { navigateFromAddress(); return true; });

        Button go = new Button(this);
        go.setText("GO");
        go.setTextColor(Color.WHITE);
        go.setTextSize(11);
        go.setBackgroundColor(Color.rgb(210, 22, 46));
        go.setOnClickListener(v -> navigateFromAddress());
        addressRow.addView(go, new LinearLayout.LayoutParams(dp(58), dp(44)));

        loadProgress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        loadProgress.setMax(100);
        loadProgress.setProgress(0);
        page.addView(loadProgress, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3)));

        webView = new WebView(this);
        webView.setBackgroundColor(Color.rgb(8, 1, 2));
        page.addView(webView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout footer = new LinearLayout(this);
        footer.setGravity(Gravity.CENTER_VERTICAL);
        footer.setPadding(dp(12), dp(7), dp(12), dp(7));
        footer.setBackgroundColor(Color.rgb(22, 4, 6));
        page.addView(footer, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));

        TextView footerText = new TextView(this);
        footerText.setText("NudgeProof AI is actively scanning this page");
        footerText.setTextColor(Color.rgb(225, 214, 216));
        footerText.setTextSize(12);
        footer.addView(footerText, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView pulse = new TextView(this);
        pulse.setText("◎ SCANNING…");
        pulse.setTextColor(Color.rgb(255, 56, 77));
        pulse.setTextSize(11);
        pulse.setTypeface(null, android.graphics.Typeface.BOLD);
        footer.addView(pulse);
    }

    private void configureWebView() {
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setSupportZoom(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setMediaPlaybackRequiresUserGesture(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) s.setSafeBrowsingEnabled(true);
        String ua = s.getUserAgentString();
        if (ua != null && !ua.contains("NudgeProofSecureBrowser")) s.setUserAgentString(ua + " NudgeProofSecureBrowser/4.2.0");

        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);

        webView.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                String scheme = uri.getScheme();
                if (scheme == null || "http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)) return false;
                try { startActivity(new Intent(Intent.ACTION_VIEW, uri)); } catch (Throwable ignored) { }
                return true;
            }

            @Override public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                address.setText(url);
                loadProgress.setProgress(20);
                scanState.setText("● LIVE  Scanning page…");
            }

            @Override public void onPageFinished(WebView view, String url) {
                address.setText(url);
                loadProgress.setProgress(100);
                scanState.setText("● LIVE  Continuous Protection Active");
                scanVisiblePage(true);
            }

            @Override public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) Toast.makeText(SecureBrowserActivity.this, "Website could not be loaded.", Toast.LENGTH_LONG).show();
            }
        });

        webView.setDownloadListener((url, userAgent, contentDisposition, mimeType, contentLength) -> {
            try {
                DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
                request.addRequestHeader("User-Agent", userAgent == null ? "" : userAgent);
                String cookies = CookieManager.getInstance().getCookie(url);
                if (cookies != null) request.addRequestHeader("Cookie", cookies);
                request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                String filename = android.webkit.URLUtil.guessFileName(url, contentDisposition, mimeType);
                request.setTitle(filename);
                request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, filename);
                DownloadManager manager = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
                if (manager != null) manager.enqueue(request);
                Toast.makeText(this, "Download started", Toast.LENGTH_SHORT).show();
            } catch (Throwable e) {
                Toast.makeText(this, "Download could not start", Toast.LENGTH_LONG).show();
            }
        });

        webView.loadDataWithBaseURL(null,
                "<html><body style='background:#080102;color:#fff;font-family:sans-serif;padding:30px'><h2 style='color:#ff304a'>NudgeProof Secure Browser</h2><p>Enter a website above. Every loaded page is rescanned automatically and a red warning appears when high-risk scam or fraud signals are detected.</p></body></html>",
                "text/html", "UTF-8", null);
    }

    private void navigateFromAddress() {
        String input = String.valueOf(address.getText()).trim();
        if (input.isEmpty()) return;
        if (!input.matches("^[a-zA-Z][a-zA-Z0-9+.-]*://.*")) input = "https://" + input;
        try {
            Uri uri = Uri.parse(input);
            if (!"http".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(uri.getScheme())) throw new IllegalArgumentException();
            webView.loadUrl(input);
        } catch (Throwable e) {
            Toast.makeText(this, "Enter a valid http/https website.", Toast.LENGTH_LONG).show();
        }
    }

    private void scanVisiblePage(boolean immediate) {
        if (webView == null) return;
        String js = "(function(){try{return JSON.stringify({url:location.href,title:document.title||'',text:(document.body?document.body.innerText:'').slice(0,120000),passwordFields:document.querySelectorAll('input[type=password]').length});}catch(e){return JSON.stringify({url:location.href,title:'',text:'',passwordFields:0});}})()";
        webView.evaluateJavascript(js, value -> {
            try {
                Object parsed = new JSONTokener(value).nextValue();
                if (!(parsed instanceof String)) return;
                JSONObject page = new JSONObject((String) parsed);
                String url = page.optString("url", webView.getUrl());
                String title = page.optString("title", "");
                String text = page.optString("text", "");
                int passwordFields = page.optInt("passwordFields", 0);
                if (url == null || url.startsWith("data:") || url.startsWith("about:")) return;

                latest = analyze(url, text, passwordFields);
                updateRiskUi(latest);

                if (immediate || !url.equals(lastSavedUrl) || latest.score >= lastSavedRisk + 20) {
                    persistReport(url, title, latest);
                }

                if (latest.score >= 75 && (!url.equals(warnedUrl) || latest.score >= warnedRisk + 10)) {
                    warnedUrl = url;
                    warnedRisk = latest.score;
                    showDangerWarning(url, title, latest);
                }
            } catch (Throwable ignored) { }
        });
    }

    private RiskResult analyze(String url, String text, int passwordFields) {
        int score = 0;
        List<String> reasons = new ArrayList<>();
        String source = text == null ? "" : text;

        for (Signal signal : SIGNALS) {
            if (signal.pattern.matcher(source).find()) {
                score += signal.score;
                reasons.add(signal.reason);
            }
        }

        try {
            Uri uri = Uri.parse(url);
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.US);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.US);
            if ("http".equals(scheme) && (passwordFields > 0 || Pattern.compile("login|pay|bank|wallet|verify|account|checkout", Pattern.CASE_INSENSITIVE).matcher(source).find())) {
                score += 26; reasons.add("Sensitive content is being handled over unencrypted HTTP.");
            }
            if (host.contains("xn--")) { score += 28; reasons.add("Punycode look-alike domain detected."); }
            if (host.matches("^\\d{1,3}(\\.\\d{1,3}){3}$")) { score += 25; reasons.add("The website uses a raw IP address instead of a recognizable domain."); }
            if (url.contains("@")) { score += 30; reasons.add("The URL contains misleading @ syntax."); }
            if (host.split("\\.").length > 5) { score += 12; reasons.add("The domain uses an unusually long subdomain chain."); }
            if (host.matches(".*(?:bit\\.ly|tinyurl\\.com|t\\.co|cutt\\.ly|rb\\.gy|shorturl\\.at)$")) { score += 13; reasons.add("A shortened destination hides the final website."); }
            if (passwordFields > 0 && Pattern.compile("bank|wallet|payment|verify|kyc", Pattern.CASE_INSENSITIVE).matcher(source).find() && !host.contains("google") && !host.contains("microsoft")) {
                score += 18; reasons.add("A credential form is combined with high-risk financial or verification language.");
            }
        } catch (Throwable ignored) { }

        score = Math.min(99, score);
        String label = score >= 75 ? "HIGH RISK" : score >= 25 ? "REVIEW" : "SAFE";
        String reason = reasons.isEmpty() ? "No high-risk scam or fraud signal was detected in the visible page content." : reasons.get(0);
        if (reasons.size() > 1) reason += " " + reasons.get(1);
        return new RiskResult(score, label, reason);
    }

    private void updateRiskUi(RiskResult result) {
        riskBadge.setText(result.score + "% " + result.label);
        if (result.score >= 75) {
            riskBadge.setTextColor(Color.rgb(255, 56, 73));
            riskBadge.setBackgroundColor(Color.rgb(54, 2, 7));
            scanState.setText("● ALERT  Dangerous website detected");
        } else if (result.score >= 25) {
            riskBadge.setTextColor(Color.rgb(255, 190, 80));
            riskBadge.setBackgroundColor(Color.rgb(50, 25, 3));
            scanState.setText("● LIVE  Review recommended");
        } else {
            riskBadge.setTextColor(Color.rgb(126, 255, 164));
            riskBadge.setBackgroundColor(Color.rgb(8, 38, 18));
            scanState.setText("● LIVE  Continuous Protection Active");
        }
    }

    private void showDangerWarning(String url, String title, RiskResult result) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setPadding(dp(24), dp(18), dp(24), dp(8));
        box.setBackgroundColor(Color.rgb(16, 1, 3));

        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.nudgeproof_icon);
        box.addView(logo, new LinearLayout.LayoutParams(dp(110), dp(110)));

        TextView brand = new TextView(this);
        brand.setText("NudgeProof AI");
        brand.setTextColor(Color.WHITE);
        brand.setTextSize(19);
        brand.setTypeface(null, android.graphics.Typeface.BOLD);
        brand.setGravity(Gravity.CENTER);
        box.addView(brand);

        TextView warning = new TextView(this);
        warning.setText("DANGEROUS WEBSITE\nDETECTED");
        warning.setTextColor(Color.rgb(255, 44, 64));
        warning.setTextSize(24);
        warning.setTypeface(null, android.graphics.Typeface.BOLD);
        warning.setGravity(Gravity.CENTER);
        warning.setPadding(0, dp(12), 0, dp(8));
        box.addView(warning);

        TextView score = new TextView(this);
        score.setText("Risk Score: " + result.score + "%");
        score.setTextColor(Color.rgb(255, 63, 82));
        score.setTextSize(22);
        score.setTypeface(null, android.graphics.Typeface.BOLD);
        score.setGravity(Gravity.CENTER);
        score.setPadding(dp(10), dp(10), dp(10), dp(10));
        score.setBackgroundColor(Color.rgb(40, 3, 7));
        box.addView(score, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView reason = new TextView(this);
        reason.setText(result.reason + "\n\n" + url);
        reason.setTextColor(Color.rgb(230, 218, 220));
        reason.setTextSize(14);
        reason.setGravity(Gravity.CENTER);
        reason.setPadding(0, dp(12), 0, dp(8));
        box.addView(reason);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setView(box)
                .setCancelable(false)
                .setNegativeButton("GO BACK", (d, which) -> {
                    if (webView.canGoBack()) webView.goBack(); else webView.loadUrl("about:blank");
                })
                .setPositiveButton("CONTINUE ANYWAY", (d, which) -> { })
                .setNeutralButton("REPORT SITE", (d, which) -> {
                    persistReport(url, title, result);
                    Toast.makeText(this, "Risk report synchronized with your NudgeProof account.", Toast.LENGTH_LONG).show();
                })
                .create();
        dialog.setOnShowListener(d -> {
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(Color.WHITE);
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(Color.rgb(255, 58, 78));
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setTextColor(Color.rgb(255, 115, 128));
        });
        dialog.show();
    }

    private void persistReport(String url, String title, RiskResult result) {
        if (url == null || url.isEmpty()) return;
        lastSavedUrl = url;
        lastSavedRisk = result.score;
        new Thread(() -> {
            HttpURLConnection connection = null;
            try {
                JSONObject report = new JSONObject();
                report.put("id", "rpt_mobile_" + UUID.randomUUID().toString().replace("-", ""));
                report.put("createdAt", isoNow());
                report.put("website", title == null || title.trim().isEmpty() ? hostOf(url) : title);
                report.put("pageTitle", title == null ? "" : title);
                report.put("url", url);
                report.put("domain", hostOf(url));
                report.put("riskScore", result.score);
                report.put("classification", result.score >= 75 ? "Potential Scam/Fraud — High Risk" : result.score >= 25 ? "Neutral — Review Needed" : "Safe — No Scam Detected");
                report.put("statusKey", result.score >= 75 ? "high" : result.score >= 25 ? "neutral" : "safe");
                report.put("confidence", result.score >= 75 ? 94 : result.score >= 25 ? 82 : 86);
                report.put("explanation", result.reason);
                report.put("source", "android-secure-browser");
                report.put("changeSummary", "Continuous mobile browser scan");
                report.put("detectionBasis", "Continuous URL and visible page text scan");
                JSONArray recommendations = new JSONArray();
                if (result.score >= 75) {
                    recommendations.put("Do not enter OTP, PIN, password, CVV, or payment details.");
                    recommendations.put("Go back and open the official website manually.");
                    recommendations.put("Verify the organization using a trusted number or app.");
                } else {
                    recommendations.put("Continue carefully and verify payment and identity details.");
                }
                report.put("recommendations", recommendations);

                JSONObject payload = new JSONObject();
                payload.put("report", report);

                URL endpoint = new URL(PORTAL_ORIGIN + "/api/reports");
                connection = (HttpURLConnection) endpoint.openConnection();
                connection.setRequestMethod("POST");
                connection.setConnectTimeout(10000);
                connection.setReadTimeout(10000);
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json");
                connection.setRequestProperty("Accept", "application/json");
                String cookies = CookieManager.getInstance().getCookie(PORTAL_ORIGIN);
                if (cookies != null && !cookies.isEmpty()) connection.setRequestProperty("Cookie", cookies);
                byte[] body = payload.toString().getBytes(StandardCharsets.UTF_8);
                connection.setFixedLengthStreamingMode(body.length);
                try (OutputStream out = connection.getOutputStream()) { out.write(body); }
                connection.getResponseCode();
            } catch (Throwable ignored) {
            } finally {
                if (connection != null) connection.disconnect();
            }
        }).start();
    }

    private static String hostOf(String url) {
        try { String h = Uri.parse(url).getHost(); return h == null ? url : h; } catch (Throwable e) { return url; }
    }

    private static String isoNow() {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.format(new Date());
    }

    @Override protected void onResume() {
        super.onResume();
        scanningEnabled = true;
    }

    @Override protected void onPause() {
        scanningEnabled = false;
        CookieManager.getInstance().flush();
        super.onPause();
    }

    @Override protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (webView != null) {
            webView.stopLoading();
            webView.loadUrl("about:blank");
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }

    @Override public void onBackPressed() {
        if (webView != null && webView.canGoBack()) webView.goBack(); else super.onBackPressed();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static final class RiskResult {
        final int score;
        final String label;
        final String reason;
        RiskResult(int score, String label, String reason) {
            this.score = score;
            this.label = label;
            this.reason = reason;
        }
    }
}
