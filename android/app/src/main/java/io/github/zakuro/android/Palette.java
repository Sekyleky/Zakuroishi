package io.github.zakuro.android;

import android.app.*;
import android.content.*;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Typeface;
import android.graphics.drawable.*;
import android.view.*;
import android.widget.*;

/** Menu colors and focus drawables shared by Launcher, SettingsDialog and Game (dark/light from the system night mode). */
// ponytail: one class with plain token fields, rebuilt per Activity/Dialog. A theme XML is not needed while every view is built in code; switch to one if views ever move to XML.
final class Palette {
    static final int TEXT = 0, SECONDARY = 1, PRIMARY = 2;
    final boolean dark;
    final int background, surface, raised, ink, muted, accent, onAccent, soft, border, ring, danger;
    private final Context context;

    Palette(Context c) {
        context = c;
        dark = (c.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        background = dark ? 0xff15100d : 0xfff6efe9; surface = dark ? 0xff211915 : 0xfffffaf6; raised = dark ? 0xff2d221c : 0xffffffff;
        ink = dark ? 0xfff7efe9 : 0xff2b1d15; muted = dark ? 0xffc9b6a8 : 0xff6a5446; accent = dark ? 0xfffb7a05 : 0xffb84400;
        onAccent = dark ? 0xff1f0d02 : 0xffffffff; soft = dark ? 0xff4a2812 : 0xfffde3cf; border = dark ? 0xff4d3c31 : 0xffdcc8b9;
        ring = dark ? 0xffffc58a : 0xff2b1d15; danger = dark ? 0xffff7a66 : 0xffb3261e;
    }

    int dp(float v) { return Math.round(v * context.getResources().getDisplayMetrics().density); }

    int dialogTheme() { return dark ? android.R.style.Theme_Material_Dialog_Alert : android.R.style.Theme_Material_Light_Dialog_Alert; }

    GradientDrawable shape(int fill, int stroke, int width, int radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(radius));
        if (width > 0) g.setStroke(dp(width), stroke);
        return g;
    }

    /** Accent fill inside a 3dp ring with a 2dp gap; idle states use the same 5dp inset so nothing moves. */
    private Drawable lit(int r) {
        LayerDrawable d = new LayerDrawable(new Drawable[]{shape(0, ring, 3, r + 5), shape(accent, 0, 0, r)});
        d.setLayerInset(1, dp(5), dp(5), dp(5), dp(5));
        return d;
    }

    private Drawable idle(int fill, int stroke, int r) { return new InsetDrawable(shape(fill, stroke, stroke == 0 ? 0 : 1, r), dp(5)); }

    /** Pressed or focused: lit. Disabled: transparent with a border. Otherwise the fill for the kind. */
    Drawable button(int kind) {
        int fill = kind == PRIMARY ? soft : kind == SECONDARY ? raised : 0;
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{-android.R.attr.state_enabled}, idle(0, border, 12));
        s.addState(new int[]{android.R.attr.state_focused}, lit(12));
        s.addState(new int[]{android.R.attr.state_pressed}, lit(12));
        s.addState(new int[]{}, idle(fill, kind == SECONDARY ? border : 0, 12));
        return s;
    }

    ColorStateList label(int idle) {
        return new ColorStateList(new int[][]{{-android.R.attr.state_enabled}, {android.R.attr.state_focused}, {android.R.attr.state_pressed}, {}},
                new int[]{muted, onAccent, onAccent, idle});
    }

    /** List, switch and choice rows: accentSoft with a 3dp accent stroke when focused or pressed. */
    Drawable row() {
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_focused}, shape(soft, accent, 3, 10));
        s.addState(new int[]{android.R.attr.state_pressed}, shape(soft, accent, 3, 10));
        s.addState(new int[]{}, shape(surface, border, 1, 10));
        return s;
    }

    /** Styles a shown-or-about-to-show AlertDialog: window card, dim, buttons. Call from its OnShowListener. */
    void dialog(AlertDialog d, boolean destructive) {
        d.getWindow().setBackgroundDrawable(shape(raised, border, 1, 20));
        d.getWindow().setDimAmount(dark ? .6f : .45f);
        int[] which = {DialogInterface.BUTTON_POSITIVE, DialogInterface.BUTTON_NEGATIVE, DialogInterface.BUTTON_NEUTRAL};
        for (int w : which) {
            Button b = d.getButton(w);
            if (b == null) continue;
            b.setAllCaps(false);
            b.setBackground(button(TEXT));
            b.setTextColor(label(w == DialogInterface.BUTTON_POSITIVE && destructive ? danger : accent));
            b.setMinHeight(dp(58));
        }
    }

    /** "A Play · Y Settings" -> glyph chips. Splits on " · ", the first word of each part is the glyph. */
    LinearLayout legend(String spec) {
        LinearLayout chips = new LinearLayout(context);
        chips.setGravity(Gravity.CENTER_VERTICAL);
        for (String part : spec.split(" · ")) {
            String[] p = part.split(" ", 2);
            TextView glyph = new TextView(context);
            glyph.setText(p[0]); glyph.setTextSize(12); glyph.setTypeface(null, Typeface.BOLD); glyph.setGravity(Gravity.CENTER);
            glyph.setTextColor(surface); glyph.setBackground(shape(ink, 0, 0, 11)); glyph.setPadding(dp(6), 0, dp(6), 0);
            LinearLayout.LayoutParams gp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(22));
            gp.setMargins(0, 0, dp(6), 0);
            chips.addView(glyph, gp);
            TextView text = new TextView(context);
            text.setText(p.length > 1 ? p[1] : ""); text.setTextSize(14); text.setTextColor(muted);
            LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            tp.setMargins(0, 0, dp(14), 0);
            chips.addView(text, tp);
        }
        return chips;
    }
}
