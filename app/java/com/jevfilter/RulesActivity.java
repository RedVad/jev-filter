package com.jevfilter;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/** Редактор правил, по которым Джев решает судьбу уведомления. */
public class RulesActivity extends Activity {

    private SharedPreferences p;
    private EditText instrBox, keepBox, muteBox, dismissBox;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        p = getSharedPreferences(Prefs.NAME, MODE_PRIVATE);

        LinearLayout col = Ui.column(this);
        col.setBackgroundColor(Ui.INK);
        int pad = Ui.dp(this, 20);
        col.setPadding(pad, Ui.dp(this, 14), pad, Ui.dp(this, 28));

        TextView back = Ui.label(this, "‹ Назад", 14, Ui.SIGNAL);
        back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        col.addView(back);

        TextView title = Ui.label(this, "Правила для Джева", 21, Ui.CHALK);
        title.setLetterSpacing(0.01f);
        title.setPadding(0, Ui.dp(this, 12), 0, Ui.dp(this, 6));
        col.addView(title);

        col.addView(Ui.label(this, "Эти тексты уходят Джеву вместе с каждым уведомлением. Меняй их как удобно: "
                + "чем точнее правила, тем меньше ошибок. Пустое поле вернёт текст по умолчанию.", 13, Ui.ASH));

        Prefs.Rule r = Prefs.rules(p);

        col.addView(section("Что сделать в целом"));
        instrBox = field(r.instructions, 4);
        col.addView(instrBox);

        col.addView(section("keep — оставить и озвучить"));
        keepBox = field(r.keep, 6);
        col.addView(keepBox);

        col.addView(section("mute — показать тихо"));
        muteBox = field(r.mute, 5);
        col.addView(muteBox);

        col.addView(section("dismiss — убрать"));
        dismissBox = field(r.dismiss, 5);
        col.addView(dismissBox);

        col.addView(Ui.hairline(this));

        Button save = Ui.primary(this, "Сохранить правила");
        LinearLayout.LayoutParams sp = Ui.wide(Ui.dp(this, 52));
        sp.topMargin = Ui.dp(this, 22);
        col.addView(save, sp);
        save.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                save();
            }
        });

        Button reset = Ui.quiet(this, "Вернуть тексты по умолчанию");
        LinearLayout.LayoutParams rp = Ui.wide(Ui.dp(this, 46));
        rp.topMargin = Ui.dp(this, 12);
        col.addView(reset, rp);
        reset.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                p.edit()
                        .remove(Prefs.R_INSTR)
                        .remove(Prefs.R_KEEP)
                        .remove(Prefs.R_MUTE)
                        .remove(Prefs.R_DISMISS)
                        .apply();
                load();
                toast("Вернул тексты по умолчанию");
            }
        });

        ScrollView root = new ScrollView(this);
        root.setBackgroundColor(Ui.INK);
        root.addView(col, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(root);
        root.setFocusableInTouchMode(true);
        root.requestFocus();
    }

    private TextView section(String text) {
        TextView t = Ui.label(this, text, 15, Ui.CHALK);
        t.setPadding(0, Ui.dp(this, 24), 0, Ui.dp(this, 8));
        return t;
    }

    private EditText field(String value, int lines) {
        EditText e = Ui.field(this, value, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        e.setMinLines(lines);
        e.setGravity(Gravity.TOP);
        e.setTextSize(12.5f);
        e.setTypeface(Typeface.MONOSPACE);
        return e;
    }

    private void load() {
        Prefs.Rule r = Prefs.rules(p);
        instrBox.setText(r.instructions);
        keepBox.setText(r.keep);
        muteBox.setText(r.mute);
        dismissBox.setText(r.dismiss);
    }

    private void save() {
        p.edit()
                .putString(Prefs.R_INSTR, instrBox.getText().toString().trim())
                .putString(Prefs.R_KEEP, keepBox.getText().toString().trim())
                .putString(Prefs.R_MUTE, muteBox.getText().toString().trim())
                .putString(Prefs.R_DISMISS, dismissBox.getText().toString().trim())
                .apply();
        toast("Правила сохранены");
        finish();
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
