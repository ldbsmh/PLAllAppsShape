package com.plallappsshape.settings;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import com.plallappsshape.R;
import com.plallappsshape.launcher.Probe;

/**
 * Settings UI. It can live in either process (module app, or inside the launcher when Xposed loads
 * it); the launcher side reads the same {@code plallappsshape} preference file.
 */
public class SettingsActivity extends Activity {

    static final String PREFS = "plallappsshape";

    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        setContentView(buildUi());
    }

    private View buildUi() {
        int pad = dp(20);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad + dp(24), pad, pad);

        TextView title = new TextView(this);
        title.setText(getString(R.string.app_name));
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
        title.setTextColor(Color.parseColor("#1B1B1F"));
        root.addView(title);

        TextView desc = new TextView(this);
        desc.setText(getString(R.string.desc));
        desc.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        desc.setTextColor(Color.parseColor("#46464F"));
        desc.setPadding(0, dp(10), 0, dp(16));
        root.addView(desc);

        root.addView(switchRow(getString(R.string.pref_enabled), "enabled", true));
        root.addView(switchRow(getString(R.string.pref_log), "verbose_log", false));

        TextView probe = statusRow();
        root.addView(probe);

        TextView hint = new TextView(this);
        hint.setText(getString(R.string.hint));
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        hint.setTextColor(Color.parseColor("#78767D"));
        hint.setPadding(0, dp(20), 0, 0);
        root.addView(hint);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        return scroll;
    }

    /** Shows what the module last saw in the launcher - the fastest way to tell whether it hooked. */
    private TextView statusRow() {
        SharedPreferences qa = getSharedPreferences("plallappsshape_qa", MODE_PRIVATE);
        String state = qa.getString(Probe.KEY_STATE, "");
        String last = qa.getString(Probe.KEY_LAST, "");
        TextView tv = new TextView(this);
        tv.setText(getString(R.string.status) + "\n" + (state.isEmpty() ? "-" : state)
                + (last.isEmpty() ? "" : "\n" + last));
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        tv.setTextColor(Color.parseColor("#46464F"));
        tv.setPadding(dp(4), dp(16), dp(4), 0);
        return tv;
    }

    private View switchRow(String label, final String key, boolean def) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(16), dp(12), dp(16), dp(12));

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.WHITE);
        bg.setCornerRadius(dp(20));
        card.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(10);
        card.setLayoutParams(lp);

        TextView text = new TextView(this);
        text.setText(label);
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        text.setTextColor(Color.parseColor("#1B1B1F"));
        card.addView(text, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Switch sw = new Switch(this);
        sw.setChecked(prefs.getBoolean(key, def));
        sw.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                prefs.edit().putBoolean(key, isChecked).commit();
            }
        });
        card.addView(sw);
        return card;
    }

    private int dp(int v) {
        return Math.round(getResources().getDisplayMetrics().density * v);
    }
}
