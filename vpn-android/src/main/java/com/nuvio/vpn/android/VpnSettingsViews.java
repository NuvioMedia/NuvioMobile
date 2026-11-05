package com.nuvio.vpn.android;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Build;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.TextView;
import java.util.Locale;

/** Native settings controls styled with the host's current theme, without Compose dependencies. */
final class VpnSettingsViews {
    final Context context;
    final VpnAppearance theme;
    final Typeface font;

    VpnSettingsViews(Context context, VpnAppearance theme) {
        this.context = context;
        this.theme = theme;
        Typeface resolved = Typeface.create("sans-serif", Typeface.NORMAL);
        try {
            if (theme.fontAsset() != null) resolved = Typeface.createFromAsset(context.getAssets(), theme.fontAsset());
            else if (theme.fontResource() != 0 && Build.VERSION.SDK_INT >= 26)
                resolved = context.getResources().getFont(theme.fontResource());
        } catch (RuntimeException ignored) { /* A restored host theme can outlive a font resource. */ }
        font = resolved;
    }

    int dp(float value) { return Math.round(value * context.getResources().getDisplayMetrics().density); }
    LinearLayout column() {
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }
    LinearLayout card() {
        LinearLayout layout = column();
        layout.setBackground(shape(theme.color(1), theme.color(2), theme.cardRadius(), .5f));
        layout.setPadding(dp(4), dp(4), dp(4), dp(4));
        layout.setClipToOutline(true);
        return layout;
    }
    void section(LinearLayout parent, int title) {
        TextView label = text(context.getString(title).toUpperCase(Locale.getDefault()), theme.body() - 1, 4);
        label.setTypeface(font, Typeface.BOLD);
        label.setLetterSpacing(.08f);
        label.setPadding(dp(16), dp(24), dp(16), dp(10));
        if (Build.VERSION.SDK_INT >= 28) label.setAccessibilityHeading(true);
        parent.addView(label);
    }
    TextView text(String value, float size, int color) {
        TextView text = new TextView(context);
        text.setText(value);
        text.setTextColor(theme.color(color));
        text.setTextSize(size);
        text.setTypeface(font);
        text.setIncludeFontPadding(false);
        text.setLineSpacing(dp(2), 1f);
        return text;
    }
    TextView paragraph(String value) {
        TextView text = text(value, theme.body(), 4);
        text.setPadding(dp(16), dp(10), dp(16), dp(14));
        return text;
    }
    TextView title(String value) {
        TextView text = text(value, theme.title(), 3);
        text.setTypeface(font, Typeface.BOLD);
        text.setPadding(dp(16), dp(14), dp(16), dp(4));
        return text;
    }
    void divider(LinearLayout parent) {
        View divider = new View(context);
        divider.setBackgroundColor(theme.color(2));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, Math.max(1, dp(.5f)));
        params.setMargins(dp(16), 0, dp(16), 0);
        parent.addView(divider, params);
    }
    void gap(LinearLayout parent, int height) {
        parent.addView(new View(context), new LinearLayout.LayoutParams(1, dp(height)));
    }
    Button action(String title, String detail) {
        Button button = new Button(context);
        decorate(button, title, detail);

        return button;
    }
    RadioButton option(String title, String detail) {
        RadioButton button = new RadioButton(context);
        button.setButtonDrawable((Drawable) null);
        decorate(button, title, detail);
        StateListDrawable mark = new StateListDrawable();
        mark.addState(new int[] {android.R.attr.state_checked}, icon(R.drawable.vpn_radio_checked, 5));
        mark.addState(new int[0], icon(R.drawable.vpn_radio_unchecked, 4));
        mark.setBounds(0, 0, dp(22), dp(22));
        button.setCompoundDrawablesRelative(null, null, mark, null);
        button.setSaveEnabled(false);
        return button;
    }
    Drawable icon(int resource, int color) {
        Drawable drawable = context.getDrawable(resource).mutate();
        drawable.setTint(theme.color(color));
        drawable.setBounds(0, 0, dp(22), dp(22));
        return drawable;
    }
    Drawable controlBackground() {
        StateListDrawable background = new StateListDrawable();
        background.addState(new int[] {android.R.attr.state_focused},
                shape(theme.color(6), theme.color(8), theme.rowRadius(), 2));
        background.addState(new int[] {android.R.attr.state_checked},
                shape(blend(theme.color(1), theme.color(5), .09f), Color.TRANSPARENT, theme.rowRadius(), 0));
        background.addState(new int[0], shape(Color.TRANSPARENT, Color.TRANSPARENT, theme.rowRadius(), 0));
        return new RippleDrawable(ColorStateList.valueOf(blend(theme.color(1), theme.color(5), .15f)),
                background, shape(Color.WHITE, Color.TRANSPARENT, theme.rowRadius(), 0));
    }
    GradientDrawable shape(int fill, int stroke, float radius, float width) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fill);
        drawable.setCornerRadius(dp(radius));
        if (width > 0) drawable.setStroke(Math.max(1, dp(width)), stroke);
        return drawable;
    }
    private void decorate(Button button, String title, String detail) {
        button.setAllCaps(false);
        button.setTypeface(font);
        button.setTextSize(theme.title());
        button.setTextColor(new ColorStateList(new int[][] {{android.R.attr.state_focused}, new int[0]},
                new int[] {theme.color(7), theme.color(3)}));
        SpannableStringBuilder label = new SpannableStringBuilder(title);
        label.setSpan(new StyleSpan(Typeface.BOLD), 0, label.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        if (detail != null && !detail.isEmpty()) {
            label.append('\n');
            int start = label.length();
            label.append(detail);
            label.setSpan(new RelativeSizeSpan(theme.body() / theme.title()), start, label.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            label.setSpan(new ForegroundColorSpan(theme.color(4)), start, label.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        button.setText(label);
        button.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        button.setPadding(dp(16), dp(14), dp(16), dp(14));
        button.setCompoundDrawablePadding(dp(14));
        button.setMinHeight(dp(64));
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setIncludeFontPadding(false);
        button.setLineSpacing(dp(3), 1f);
        button.setStateListAnimator(null);
        button.setBackground(controlBackground());
        button.setLayoutParams(new LinearLayout.LayoutParams(-1, -2));
    }
    private static int blend(int background, int foreground, float amount) {
        return Color.rgb((int) (Color.red(background) * (1 - amount) + Color.red(foreground) * amount),
                (int) (Color.green(background) * (1 - amount) + Color.green(foreground) * amount),
                (int) (Color.blue(background) * (1 - amount) + Color.blue(foreground) * amount));
    }
}
