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
import android.webkit.DownloadListener;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

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
        handler.postDelayed(continuousScanner, 1800L);
    }

    private void buildUi() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(Color.rgb(8, 1, 2));
        setContentView(page);

        LinearLayout brandRow = new LinearLayout(this);
        brandRow.setGravity(Gravity.CENTER_VERTICAL);
        brandRow.setPadding(dp(12), dp(8), dp(12), dp(6));
        brandRow.setBackgroundColor(Color.rgb(18, 1, 3));
        page.addView(brandRow, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(58)));

        ImageView icon = new ImageView(this);
        icon.setImageResource(com.example_placeholder());
    }

    private void buildHeaderFallback(LinearLayout brandRow) {
        // Unused. Kept only to make layout construction explicit in one file.
    }

    private void configureWebView() {
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
