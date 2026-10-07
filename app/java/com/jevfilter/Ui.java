package com.jevfilter;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Единый визуальный язык приложения: цвета, отступы, поля, кнопки. */
final class Ui {

    static final int INK = 0xFF14161B;
    static final int SLATE = 0xFF1D2027;
    static final int HAIR = 0xFF2A2E37;
    static final int CHALK = 0xFFE8EAEE;
    static final int ASH = 0xFF8B93A1;
    static final int SIGNAL = 0xFF5AA9E6;
    static final int CALM = 0xFF4CC38A;
    static final int ALERT = 0xFFE5646E;
    static final int WARN = 0xFFE0A458;
    static final int HILITE = 0x145AA9E6;

    private Ui() {
    }

    static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    static GradientDrawable rect(int fill, float radiusDp, Context c) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(dp(c, radiusDp));
        return d;
    }

    static GradientDrawable rect(int fill, float radiusDp, int stroke, float strokeDp, Context c) {
        GradientDrawable d = rect(fill, radiusDp, c);
        if (stroke != 0) d.setStroke(Math.max(1, dp(c, strokeDp)), stroke);
        return d;
    }

    static GradientDrawable ring(int color, float strokeDp, Context c) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setStroke(Math.max(1, dp(c, strokeDp)), color);
        return d;
    }

    static TextView label(Context c, String text, float sp, int color) {
        TextView t = new TextView(c);
        t.setText(text);
        t.setTextSize(sp);
        t.setTextColor(color);
        return t;
    }

    static TextView sectionTitle(Context c, String text) {
        TextView t = label(c, text, 15, CHALK);
        t.setPadding(0, dp(c, 26), 0, dp(c, 10));
        return t;
    }

    static TextView meta(Context c, String text) {
        TextView t = label(c, text, 11, ASH);
        t.setPadding(0, dp(c, 7), 0, 0);
        return t;
    }

    static TextView action(Context c, String text) {
        return label(c, text, 13, SIGNAL);
    }

    static View hairline(Context c) {
        View v = new View(c);
        v.setBackgroundColor(HAIR);
        v.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(c, 0.7f))));
        return v;
    }

    static EditText field(Context c, String value, int inputType) {
        EditText e = new EditText(c);
        e.setText(value);
        e.setInputType(inputType);
        e.setTextSize(14);
        e.setTextColor(CHALK);
        e.setHintTextColor(ASH);
        e.setBackground(rect(SLATE, 10, HAIR, 1, c));
        int p = dp(c, 12);
        e.setPadding(p, p, p, p);
        return e;
    }

    static Button primary(Context c, String text) {
        Button b = new Button(c);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(15);
        b.setTextColor(INK);
        b.setBackground(rect(SIGNAL, 12, c));
        b.setStateListAnimator(null);
        b.setPadding(dp(c, 16), 0, dp(c, 16), 0);
        return b;
    }

    static Button quiet(Context c, String text) {
        Button b = new Button(c);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(14);
        b.setTextColor(CHALK);
        b.setBackground(rect(0x00000000, 12, HAIR, 1, c));
        b.setStateListAnimator(null);
        b.setPadding(dp(c, 14), 0, dp(c, 14), 0);
        return b;
    }

    static LinearLayout column(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    static LinearLayout row(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(android.view.Gravity.CENTER_VERTICAL);
        return l;
    }

    static LinearLayout.LayoutParams wrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    static LinearLayout.LayoutParams wide(int heightPx) {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, heightPx);
    }

    static LinearLayout.LayoutParams weighted() {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
    }
}
