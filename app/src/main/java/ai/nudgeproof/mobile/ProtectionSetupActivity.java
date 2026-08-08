package ai.nudgeproof.mobile;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

public final class ProtectionSetupActivity extends Activity {
    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Color.rgb(38, 2, 7));
        getWindow().setNavigationBarColor(Color.BLACK);

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setGravity(Gravity.CENTER_HORIZONTAL);
        page.setPadding(dp(28), dp(40), dp(28), dp(28));
        page.setBackgroundColor(Color.rgb(10, 1, 2));
        setContentView(page);

        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.nudgeproof_icon);
        page.addView(logo, new LinearLayout.LayoutParams(dp(132), dp(132)));

        TextView title = text("PHONE-WIDE PROTECTION", 25, Color.WHITE, true);
        LinearLayout.LayoutParams t = new LinearLayout.LayoutParams(-1, -2); t.setMargins(0, dp(22), 0, dp(10));
        page.addView(title, t);

        TextView body = text("Enable NudgeProof Phone-wide Protection in Android Accessibility settings. NudgeProof then watches supported browser screens locally, rescans them continuously, calculates a risk percentage, and shows the red warning overlay when dangerous scam or fraud signals are detected.\n\nSupported: Chrome, Samsung Internet, Firefox, Edge, Brave, Opera, DuckDuckGo and Vivaldi.", 15, Color.rgb(224, 203, 207), false);
        page.addView(body, new LinearLayout.LayoutParams(-1, -2));

        Button enable = new Button(this);
        enable.setText("ENABLE PHONE-WIDE PROTECTION");
        enable.setTextColor(Color.WHITE);
        enable.setBackgroundColor(Color.rgb(203, 16, 42));
        enable.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        LinearLayout.LayoutParams ep = new LinearLayout.LayoutParams(-1, dp(58)); ep.setMargins(0, dp(28), 0, 0);
        page.addView(enable, ep);

        Button browser = new Button(this);
        browser.setText("OPEN SECURE BROWSER");
        browser.setTextColor(Color.WHITE);
        browser.setBackgroundColor(Color.rgb(77, 7, 16));
        browser.setOnClickListener(v -> startActivity(new Intent(this, SecureBrowserActivity.class)));
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-1, dp(54)); bp.setMargins(0, dp(12), 0, 0);
        page.addView(browser, bp);

        TextView note = text("Privacy: browser text used by this phone-wide scanner is analyzed locally on the device. Android requires you to enable the Accessibility Service yourself.", 12, Color.rgb(170, 141, 147), false);
        LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(-1, -2); np.setMargins(0, dp(20), 0, 0);
        page.addView(note, np);
    }

    private TextView text(String value, int size, int color, boolean bold) {
        TextView v = new TextView(this); v.setText(value); v.setTextColor(color); v.setTextSize(size); v.setGravity(Gravity.CENTER);
        if (bold) v.setTypeface(null, android.graphics.Typeface.BOLD);
        return v;
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
}
