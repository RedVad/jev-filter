package com.jevfilter;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Выбор приложений, уведомления которых проверяет Джев. */
public class AppPickerActivity extends Activity {

    private static final class Row {
        final String pkg;
        final String label;
        final boolean installed;
        boolean checked;
        Drawable icon;

        Row(String pkg, String label, boolean installed) {
            this.pkg = pkg;
            this.label = label;
            this.installed = installed;
        }
    }

    private final List<Row> all = new ArrayList<Row>();
    private final List<Row> shown = new ArrayList<Row>();

    private SharedPreferences p;
    private Adapter adapter;
    private EditText search;
    private TextView counter;
    private Button saveBtn;
    private float dp;
    private volatile boolean loaded = false;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        p = getSharedPreferences(Prefs.NAME, MODE_PRIVATE);
        dp = getResources().getDisplayMetrics().density;

        LinearLayout col = Ui.column(this);
        col.setBackgroundColor(Ui.INK);
        int pad = Ui.dp(this, 20);
        col.setPadding(pad, Ui.dp(this, 14), pad, Ui.dp(this, 20));

        TextView back = Ui.label(this, "‹ Назад", 14, Ui.SIGNAL);
        back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        col.addView(back);

        TextView title = Ui.label(this, "Выбор приложений", 21, Ui.CHALK);
        title.setLetterSpacing(0.01f);
        title.setPadding(0, Ui.dp(this, 12), 0, 0);
        col.addView(title);

        counter = Ui.label(this, "Загрузка списка приложений…", 13, Ui.ASH);
        counter.setPadding(0, Ui.dp(this, 6), 0, 0);
        col.addView(counter);

        search = Ui.field(this, "", android.text.InputType.TYPE_CLASS_TEXT);
        search.setHint("Поиск по названию или пакету");
        search.setSingleLine(true);
        LinearLayout.LayoutParams fp = Ui.wide(Ui.dp(this, 46));
        fp.topMargin = Ui.dp(this, 16);
        col.addView(search, fp);
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b2, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b2, int c) { }
            @Override public void afterTextChanged(Editable s) { refilter(); }
        });

        LinearLayout acts = Ui.row(this);
        acts.setPadding(0, Ui.dp(this, 14), 0, Ui.dp(this, 10));
        TextView checkShown = Ui.action(this, "Проверять все показанные");
        checkShown.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                for (Row r : shown) r.checked = true;
                adapter.notifyDataSetChanged();
                updateCounter();
            }
        });
        TextView clearAll = Ui.action(this, "Снять все");
        clearAll.setGravity(Gravity.END);
        clearAll.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                for (Row r : all) r.checked = false;
                adapter.notifyDataSetChanged();
                updateCounter();
            }
        });
        acts.addView(checkShown, Ui.weighted());
        acts.addView(clearAll, Ui.weighted());
        col.addView(acts);

        col.addView(Ui.hairline(this));

        ListView list = new ListView(this);
        list.setDivider(new ColorDrawable(Ui.HAIR));
        list.setDividerHeight(Math.max(1, Ui.dp(this, 0.7f)));
        list.setCacheColorHint(0);
        list.setSelector(new ColorDrawable(0x00000000));
        adapter = new Adapter();
        list.setAdapter(adapter);
        col.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        saveBtn = Ui.primary(this, "Сохранить выбор");
        saveBtn.setEnabled(false);
        saveBtn.setAlpha(0.4f);
        LinearLayout.LayoutParams sp = Ui.wide(Ui.dp(this, 52));
        sp.topMargin = Ui.dp(this, 16);
        col.addView(saveBtn, sp);
        saveBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                saveNow();
            }
        });

        setContentView(col);
        col.setFocusableInTouchMode(true);
        col.requestFocus();
        load();
    }

    private void load() {
        final Set<String> selected = new HashSet<String>(Prefs.tokens(Prefs.blacklist(p)));
        new Thread(new Runnable() {
            @Override
            public void run() {
                final List<Row> rows = new ArrayList<Row>();
                String problem = null;
                try {
                    PackageManager pm = getPackageManager();
                    Set<String> seen = new HashSet<String>();

                    Intent main = new Intent(Intent.ACTION_MAIN, null);
                    main.addCategory(Intent.CATEGORY_LAUNCHER);
                    List<ResolveInfo> found = pm.queryIntentActivities(main, 0);
                    for (ResolveInfo ri : found) {
                        if (ri == null || ri.activityInfo == null || ri.activityInfo.applicationInfo == null) continue;
                        ApplicationInfo ai = ri.activityInfo.applicationInfo;
                        String pkg = ai.packageName;
                        if (pkg == null || getPackageName().equals(pkg)) continue;
                        if (!seen.add(pkg)) continue;
                        rows.add(new Row(pkg, String.valueOf(ai.loadLabel(pm)), true));
                    }

                    // записи из списка, которых нет на телефоне - показываем ниже, чтобы их было видно
                    for (String tok : Prefs.tokens(Prefs.blacklist(p))) {
                        if (tok.endsWith("*")) {
                            rows.add(new Row(tok, tok, false));
                            continue;
                        }
                        boolean already = false;
                        for (Row r : rows) {
                            if (r.pkg.equalsIgnoreCase(tok)) { already = true; break; }
                        }
                        if (already) continue;
                        String label = tok;
                        try {
                            label = String.valueOf(pm.getApplicationInfo(tok, 0).loadLabel(pm));
                        } catch (Throwable ignored) {
                        }
                        rows.add(new Row(tok, label, false));
                    }

                    for (Row r : rows) r.checked = selected.contains(r.pkg);

                    final Collator col = Collator.getInstance(new Locale("ru", "RU"));
                    col.setStrength(Collator.PRIMARY);
                    Collections.sort(rows, new Comparator<Row>() {
                        @Override public int compare(Row a, Row b) {
                            if (a.installed != b.installed) return a.installed ? -1 : 1;
                            return col.compare(a.label, b.label);
                        }
                    });
                } catch (Throwable t) {
                    problem = t.getClass().getSimpleName() + ": " + t.getMessage();
                }

                final String err = problem;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        loaded = true;
                        saveBtn.setEnabled(true);
                        saveBtn.setAlpha(1f);
                        all.clear();
                        all.addAll(rows);
                        refilter();
                        int checked = 0;
                        for (Row r : all) if (r.checked) checked++;
                        android.util.Log.i("JevFilter", "picker apps=" + all.size() + " checked=" + checked + " err=" + err);
                        if (err != null) {
                            toast("Список не загрузился: " + err);
                        }
                    }
                });
            }
        }).start();
    }

    private void refilter() {
        String q = search.getText().toString().trim().toLowerCase(Locale.US);
        shown.clear();
        for (Row r : all) {
            if (q.length() == 0
                    || r.label.toLowerCase(Locale.US).contains(q)
                    || r.pkg.toLowerCase(Locale.US).contains(q)) {
                shown.add(r);
            }
        }
        adapter.notifyDataSetChanged();
        updateCounter();
    }

    private void updateCounter() {
        if (!loaded) {
            counter.setText("Загрузка списка приложений…");
            return;
        }
        int checked = 0;
        for (Row r : all) if (r.checked) checked++;
        counter.setText("Отмечено " + checked + " из " + all.size());
    }

    private void saveNow() {
        if (!loaded) return;
        List<String> out = new ArrayList<String>();
        for (Row r : all) if (r.checked) out.add(r.pkg);
        String joined = Prefs.join(out);
        p.edit().putString(Prefs.BLACKLIST, joined).apply();
        // копия на случай, если настройки перезапишут извне
        try {
            java.io.FileWriter w = new java.io.FileWriter(new java.io.File(getFilesDir(), "blacklist.backup"));
            try {
                w.write(joined);
            } finally {
                w.close();
            }
        } catch (Throwable ignored) {
        }
        toast("Выбор сохранён");
        finish();
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    private final class Adapter extends BaseAdapter {
        @Override public int getCount() { return shown.size(); }
        @Override public Object getItem(int i) { return shown.get(i); }
        @Override public long getItemId(int i) { return i; }

        @Override
        public View getView(int pos, View convert, ViewGroup parent) {
            LinearLayout row;
            CheckBox cb;
            TextView label;
            TextView sub;
            if (convert == null) {
                row = Ui.row(parent.getContext());
                row.setPadding(Ui.dp(AppPickerActivity.this, 12), Ui.dp(AppPickerActivity.this, 12),
                        Ui.dp(AppPickerActivity.this, 12), Ui.dp(AppPickerActivity.this, 12));

                cb = new CheckBox(AppPickerActivity.this);
                cb.setClickable(false);
                cb.setFocusable(false);
                cb.setButtonTintList(ColorStateList.valueOf(Ui.SIGNAL));
                row.addView(cb);

                LinearLayout col = Ui.column(AppPickerActivity.this);
                label = Ui.label(AppPickerActivity.this, "", 15, Ui.CHALK);
                label.setGravity(Gravity.CENTER_VERTICAL);
                label.setCompoundDrawablePadding(Ui.dp(AppPickerActivity.this, 12));
                sub = Ui.label(AppPickerActivity.this, "", 11, Ui.ASH);
                sub.setPadding(Ui.dp(AppPickerActivity.this, 2), Ui.dp(AppPickerActivity.this, 2), 0, 0);
                col.addView(label);
                col.addView(sub);
                row.addView(col, Ui.weighted());
            } else {
                row = (LinearLayout) convert;
                cb = (CheckBox) row.getChildAt(0);
                LinearLayout col = (LinearLayout) row.getChildAt(1);
                label = (TextView) col.getChildAt(0);
                sub = (TextView) col.getChildAt(1);
            }

            final Row r = shown.get(pos);

            int iconSize = Ui.dp(AppPickerActivity.this, 40);
            if (r.icon == null) {
                try {
                    r.icon = getPackageManager().getApplicationIcon(r.pkg);
                } catch (Throwable ignored) {
                }
            }
            if (r.icon != null) {
                r.icon.setBounds(0, 0, iconSize, iconSize);
                label.setCompoundDrawables(r.icon, null, null, null);
            } else {
                label.setCompoundDrawables(null, null, null, null);
            }

            label.setText(r.label);
            label.setTextColor(r.installed ? Ui.CHALK : Ui.ASH);
            sub.setText(r.installed ? r.pkg : r.pkg + "  не установлено");
            sub.setTypeface(Typeface.DEFAULT);
            cb.setChecked(r.checked);
            row.setBackground(r.checked ? Ui.rect(Ui.HILITE, 12, AppPickerActivity.this) : null);

            row.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (!r.checked) {
                        String note = Prefs.riskNote(r.pkg);
                        if (note != null) {
                            confirmRisky(r, note);
                            return;
                        }
                    }
                    toggle(r);
                }
            });
            return row;
        }
    }

    private void toggle(Row r) {
        r.checked = !r.checked;
        adapter.notifyDataSetChanged();
        updateCounter();
    }

    /** Страховка: банк, госуслуги, мессенджер или системное приложение - спрашиваем отдельно. */
    private void confirmRisky(final Row r, String note) {
        AlertDialog d = new AlertDialog.Builder(this)
                .setTitle(r.label)
                .setMessage(note + ". Проверять его уведомления Джевом?")
                .setPositiveButton("Проверять", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dlg, int w) {
                        toggle(r);
                    }
                })
                .setNegativeButton("Не трогать", null)
                .show();
        android.widget.Button yes = d.getButton(DialogInterface.BUTTON_POSITIVE);
        android.widget.Button no = d.getButton(DialogInterface.BUTTON_NEGATIVE);
        if (yes != null) {
            yes.setAllCaps(false);
            yes.setTextColor(Ui.SIGNAL);
        }
        if (no != null) {
            no.setAllCaps(false);
            no.setTextColor(Ui.ASH);
        }
    }
}
