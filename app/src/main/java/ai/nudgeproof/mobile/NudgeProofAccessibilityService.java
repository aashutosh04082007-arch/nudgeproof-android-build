package ai.nudgeproof.mobile;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class NudgeProofAccessibilityService extends AccessibilityService {
    private static final long RESCAN_MS = 2500L;
    private static final Pattern URL = Pattern.compile("(?i)(https?://)?([a-z0-9-]+\\.)+[a-z]{2,}([/:?][^\\s]*)?");
    private final Handler handler = new Handler(Looper.getMainLooper());
    private WindowManager wm;
    private LinearLayout overlay;
    private String activePackage = "";
    private String lastUrl = "";
    private long suppressUntil = 0L;

    private final Runnable periodic = new Runnable() {
        @Override public void run() {
            inspectCurrentWindow();
            handler.postDelayed(this, RESCAN_MS);
        }
    };

    @Override public void onServiceConnected() {
        super.onServiceConnected();
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        handler.post(periodic);
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || event.getPackageName() == null) return;
        activePackage = event.getPackageName().toString();
        if (isSupportedBrowser(activePackage)) handler.postDelayed(this::inspectCurrentWindow, 120L);
        else hideOverlay();
    }

    @Override public void onInterrupt() { }

    @Override public void onDestroy() {
        handler.removeCallbacks(periodic);
        hideOverlay();
        super.onDestroy();
    }

    private void inspectCurrentWindow() {
        if (!isSupportedBrowser(activePackage)) return;
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;
        StringBuilder text = new StringBuilder();
        collect(root, text, 0);
        try { root.recycle(); } catch (Throwable ignored) { }
        String visible = text.toString();
        String url = extractBestUrl(visible);
        Risk r = analyze(url, visible);
        if (r.score >= 75 && System.currentTimeMillis() >= suppressUntil) showOverlay(url, r);
        else if (r.score < 55) hideOverlay();
        lastUrl = url;
    }

    private void collect(AccessibilityNodeInfo node, StringBuilder out, int depth) {
        if (node == null || depth > 18 || out.length() > 70000) return;
        CharSequence t = node.getText();
        CharSequence d = node.getContentDescription();
        if (t != null && t.length() > 0) out.append(t).append('\n');
        if (d != null && d.length() > 0) out.append(d).append('\n');
        int n = Math.min(node.getChildCount(), 80);
        for (int i = 0; i < n; i++) {
            AccessibilityNodeInfo c = node.getChild(i);
            collect(c, out, depth + 1);
            if (c != null) try { c.recycle(); } catch (Throwable ignored) { }
        }
    }

    private String extractBestUrl(String text) {
        Matcher m = URL.matcher(text == null ? "" : text);
        String best = "";
        while (m.find()) {
            String s = m.group();
            if (s.length() > best.length() && s.length() < 500) best = s;
        }
        return best;
    }

    private Risk analyze(String url, String text) {
        String src = ((url == null ? "" : url) + "\n" + (text == null ? "" : text)).toLowerCase(Locale.ROOT);
        int score = 0;
        List<String> reasons = new ArrayList<>();
        score += hit(src, "otp", 22, reasons, "OTP/PIN request");
        score += hit(src, "upi pin", 34, reasons, "UPI PIN request");
        score += hit(src, "cvv", 28, reasons, "Card security code request");
        score += hit(src, "seed phrase", 45, reasons, "Crypto recovery phrase request");
        score += hit(src, "private key", 45, reasons, "Private key request");
        score += hit(src, "anydesk", 38, reasons, "Remote-access software request");
        score += hit(src, "teamviewer", 38, reasons, "Remote-access software request");
        score += hit(src, "gift card", 28, reasons, "Gift-card payment pressure");
        score += hit(src, "wire transfer", 28, reasons, "Wire-transfer pressure");
        score += hit(src, "account suspended", 24, reasons, "Account suspension urgency");
        score += hit(src, "verify immediately", 20, reasons, "Urgent verification pressure");
        score += hit(src, "lottery", 22, reasons, "Unexpected prize claim");
        score += hit(src, "guaranteed return", 28, reasons, "Guaranteed investment return");
        score += hit(src, "registration fee", 20, reasons, "Advance-fee request");
        score += hit(src, "pay now", 12, reasons, "Immediate payment pressure");
        if (url != null && !url.isEmpty()) {
            String u = url.toLowerCase(Locale.ROOT);
            if (u.contains("xn--")) { score += 22; reasons.add("Punycode domain"); }
            if (u.matches(".*(login|verify|secure|update|wallet|bank).*[0-9]{3,}.*")) { score += 18; reasons.add("Suspicious login-style domain pattern"); }
            if (u.matches(".*(bit\\.ly|tinyurl\\.com|t\\.co|cutt\\.ly).*")) { score += 12; reasons.add("Shortened URL"); }
        }
        score = Math.min(99, score);
        String reason = reasons.isEmpty() ? "Suspicious page signals detected." : joinReasons(reasons);
        return new Risk(score, reason);
    }

    private int hit(String src, String term, int pts, List<String> reasons, String reason) {
        if (src.contains(term)) { reasons.add(reason); return pts; }
        return 0;
    }

    private String joinReasons(List<String> r) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < Math.min(3, r.size()); i++) {
            if (i > 0) b.append(" • ");
            b.append(r.get(i));
        }
        return b.toString();
    }

    private void showOverlay(String url, Risk risk) {
        if (wm == null) return;
        if (overlay != null) {
            TextView score = overlay.findViewWithTag("score");
            TextView reason = overlay.findViewWithTag("reason");
            if (score != null) score.setText("RISK SCORE: " + risk.score + "%");
            if (reason != null) reason.setText(risk.reason);
            return;
        }

        overlay = new LinearLayout(this);
        overlay.setOrientation(LinearLayout.VERTICAL);
        overlay.setGravity(Gravity.CENTER_HORIZONTAL);
        overlay.setPadding(dp(22), dp(22), dp(22), dp(22));
        overlay.setBackgroundColor(Color.rgb(25, 1, 4));

        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.nudgeproof_icon);
        overlay.addView(logo, new LinearLayout.LayoutParams(dp(92), dp(92)));

        TextView title = text("DANGEROUS WEBSITE DETECTED", 22, Color.WHITE, true);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-1, -2); tp.setMargins(0, dp(14), 0, dp(8));
        overlay.addView(title, tp);

        TextView score = text("RISK SCORE: " + risk.score + "%", 28, Color.rgb(255, 45, 66), true);
        score.setTag("score"); overlay.addView(score);

        TextView domain = text(url == null || url.isEmpty() ? "Website detected in browser" : url, 12, Color.rgb(215, 185, 190), false);
        overlay.addView(domain);

        TextView reason = text(risk.reason, 14, Color.rgb(238, 220, 223), false);
        reason.setTag("reason"); LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-1, -2); rp.setMargins(0, dp(12), 0, dp(12)); overlay.addView(reason, rp);

        Button back = button("GO BACK");
        back.setOnClickListener(v -> { performGlobalAction(GLOBAL_ACTION_BACK); hideOverlay(); });
        overlay.addView(back, new LinearLayout.LayoutParams(-1, dp(50)));

        Button proceed = button("CONTINUE ANYWAY");
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(-1, dp(50)); pp.setMargins(0, dp(8), 0, 0);
        proceed.setOnClickListener(v -> { suppressUntil = System.currentTimeMillis() + 5 * 60 * 1000L; hideOverlay(); });
        overlay.addView(proceed, pp);

        Button open = button("OPEN NUDGEPROOF");
        LinearLayout.LayoutParams op = new LinearLayout.LayoutParams(-1, dp(50)); op.setMargins(0, dp(8), 0, 0);
        open.setOnClickListener(v -> {
            android.content.Intent i = new android.content.Intent(this, MainActivity.class);
            i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK | android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(i); hideOverlay();
        });
        overlay.addView(open, op);

        WindowManager.LayoutParams p = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        p.gravity = Gravity.CENTER;
        try { wm.addView(overlay, p); } catch (Throwable ignored) { overlay = null; }
    }

    private TextView text(String s, int size, int color, boolean bold) {
        TextView v = new TextView(this); v.setText(s); v.setTextColor(color); v.setTextSize(size); v.setGravity(Gravity.CENTER);
        if (bold) v.setTypeface(null, android.graphics.Typeface.BOLD);
        return v;
    }

    private Button button(String s) {
        Button b = new Button(this); b.setText(s); b.setTextColor(Color.WHITE); b.setBackgroundColor(Color.rgb(190, 12, 35)); return b;
    }

    private void hideOverlay() {
        if (overlay != null && wm != null) {
            try { wm.removeView(overlay); } catch (Throwable ignored) { }
            overlay = null;
        }
    }

    private boolean isSupportedBrowser(String p) {
        if (p == null) return false;
        return p.equals("com.android.chrome") || p.equals("com.sec.android.app.sbrowser") ||
                p.equals("org.mozilla.firefox") || p.equals("com.microsoft.emmx") ||
                p.equals("com.brave.browser") || p.equals("com.opera.browser") ||
                p.equals("com.duckduckgo.mobile.android") || p.equals("com.vivaldi.browser");
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private static final class Risk {
        final int score; final String reason;
        Risk(int score, String reason) { this.score = score; this.reason = reason; }
    }
}
