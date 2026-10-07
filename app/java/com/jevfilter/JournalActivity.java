package com.jevfilter;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Журнал решений отдельной страницей: список во всю высоту, с иконками приложений.
 *
 * Читается как выписка по счёту: слева узкая цветная черта (красная — сняли, зелёная —
 * оставили, серая — ничего не делали), поэтому даже при быстрой прокрутке видно, где
 * приложение работало, а где пропускало.
 */
public class JournalActivity extends Activity {

    private static final String FILTER_KEY = "journal_decisions_only";
    private static final Pattern CONF = Pattern.compile("([01][.,]\\d\\d)");

    private SharedPreferences p;
    private ListView list;
    private TextView meta, empty, tabAll, tabDec;
    private Rows rows;
    private boolean onlyDecisions;
    private boolean seenOnly;
    private long seenVersion = -1L;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Map<String, Drawable> icons = new HashMap<String, Drawable>();
    private final Map<String, String> labels = new HashMap<String, String>();
    private final Map<Integer, String> days = new HashMap<Integer, String>();
    private final SimpleDateFormat TIME = new SimpleDateFormat("HH:mm", Locale.US);
    private final SimpleDateFormat DAY = new SimpleDateFormat("d MMMM", new Locale("ru", "RU"));
    private final Calendar cal = Calendar.getInstance();

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            reload();
            ui.postDelayed(this, 2000);
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        p = getSharedPreferences(Prefs.NAME, MODE_PRIVATE);
        onlyDecisions = p.getBoolean(FILTER_KEY, false);
        JeLog.prime(this);

        int pad = Ui.dp(this, 20);
        LinearLayout col = Ui.column(this);
        col.setBackgroundColor(Ui.INK);
        col.setPadding(pad, Ui.dp(this, 18), pad, 0);

        TextView back = Ui.action(this, "← Назад");
        back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        col.addView(back);

        TextView title = Ui.label(this, "Журнал решений", 21, Ui.CHALK);
        title.setPadding(0, Ui.dp(this, 12), 0, 0);
        col.addView(title);

        meta = Ui.label(this, "", 12, Ui.ASH);
        meta.setPadding(0, Ui.dp(this, 6), 0, 0);
        col.addView(meta);

        LinearLayout seg = Ui.row(this);
        seg.setBackground(Ui.rect(Ui.SLATE, 10, Ui.HAIR, 1, this));
        int sp2 = Ui.dp(this, 3);
        seg.setPadding(sp2, sp2, sp2, sp2);
        tabAll = tab("Всё");
        tabDec = tab("Только решения");
        tabAll.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setFilter(false);
            }
        });
        tabDec.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setFilter(true);
            }
        });
        seg.addView(tabAll, new LinearLayout.LayoutParams(0, Ui.dp(this, 34), 1f));
        seg.addView(tabDec, new LinearLayout.LayoutParams(0, Ui.dp(this, 34), 1f));
        LinearLayout.LayoutParams segLp = Ui.wide(Ui.dp(this, 40));
        segLp.topMargin = Ui.dp(this, 16);
        col.addView(seg, segLp);

        LinearLayout acts = Ui.row(this);
        acts.setPadding(0, Ui.dp(this, 14), 0, Ui.dp(this, 10));
        TextView copy = Ui.action(this, "Скопировать");
        copy.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                copyAll();
            }
        });
        TextView wipe = Ui.action(this, "Очистить");
        wipe.setTextColor(Ui.ASH);
        wipe.setGravity(Gravity.END);
        wipe.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                JeLog.clear();
                reload();
                toast("Журнал очищен");
            }
        });
        acts.addView(copy, Ui.weighted());
        acts.addView(wipe, Ui.weighted());
        col.addView(acts);

        rows = new Rows();
        list = new ListView(this);
        list.setBackgroundColor(Ui.INK);
        list.setDivider(new ColorDrawable(Ui.HAIR));
        list.setDividerHeight(1);
        list.setCacheColorHint(0);
        list.setVerticalScrollBarEnabled(true);
        list.setClipToPadding(false);
        list.setPadding(0, 0, 0, Ui.dp(this, 28));
        list.setAdapter(rows);

        empty = Ui.label(this, "Пока пусто.\nЗдесь появятся решения по уведомлениям\nиз чёрного списка.", 13, Ui.ASH);
        empty.setGravity(Gravity.CENTER);
        empty.setVisibility(View.GONE);

        FrameLayout box = new FrameLayout(this);
        box.addView(list, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        box.addView(empty, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        col.addView(box, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(col);
        paintFilter();
        reload();
    }

    private TextView tab(String text) {
        TextView t = Ui.label(this, text, 13, Ui.ASH);
        t.setGravity(Gravity.CENTER);
        return t;
    }

    private void setFilter(boolean decisionsOnly) {
        if (onlyDecisions == decisionsOnly) return;
        onlyDecisions = decisionsOnly;
        p.edit().putBoolean(FILTER_KEY, decisionsOnly).apply();
        paintFilter();
        reload();
        list.setSelection(0);
    }

    private void paintFilter() {
        tabAll.setTextColor(onlyDecisions ? Ui.ASH : Ui.CHALK);
        tabDec.setTextColor(onlyDecisions ? Ui.CHALK : Ui.ASH);
        tabAll.setBackground(onlyDecisions ? null : Ui.rect(Ui.HILITE, 8, this));
        tabDec.setBackground(onlyDecisions ? Ui.rect(Ui.HILITE, 8, this) : null);
    }

    /** Перечитывает журнал. Позицию прокрутки сохраняем: список растёт сверху. */
    private void reload() {
        long v = JeLog.version();
        // фильтр меняет содержимое списка, но не версию журнала — проверяем и его
        if (v == seenVersion && onlyDecisions == seenOnly) return;
        seenVersion = v;
        seenOnly = onlyDecisions;
        List<JeLog.Entry> data = JeLog.entries(onlyDecisions);
        int old = rows.getCount();
        int first = list.getFirstVisiblePosition();
        View top = list.getChildAt(0);
        final int off = top == null ? 0 : top.getTop() - list.getPaddingTop();
        rows.setData(data);
        rows.notifyDataSetChanged();
        if (first > 0) {
            final int pos = first + Math.max(0, data.size() - old);
            list.post(new Runnable() {
                @Override
                public void run() {
                    list.setSelectionFromTop(pos, off);
                }
            });
        }
        boolean none = data.isEmpty();
        empty.setVisibility(none ? View.VISIBLE : View.GONE);
        list.setVisibility(none ? View.GONE : View.VISIBLE);
        int all = JeLog.size();
        int dec = JeLog.decisions();
        meta.setText(plural(all, "запись", "записи", "записей")
                + ", из них " + plural(dec, "решение", "решения", "решений"));
    }

    private void copyAll() {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("Журнал Jev Filter", JeLog.dump()));
            toast("Журнал скопирован");
        } catch (Throwable t) {
            toast("Не получилось скопировать");
        }
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    private static String plural(int n, String one, String few, String many) {
        int m10 = n % 10;
        int m100 = n % 100;
        String word;
        if (m10 == 1 && m100 != 11) word = one;
        else if (m10 >= 2 && m10 <= 4 && (m100 < 12 || m100 > 14)) word = few;
        else word = many;
        return n + " " + word;
    }

    private String time(long at) {
        return TIME.format(new Date(at));
    }

    /** «сегодня», «вчера» или «2 октября»: без этого длинный журнал не читается. */
    private String day(long at) {
        cal.setTimeInMillis(at);
        int key = cal.get(Calendar.YEAR) * 1000 + cal.get(Calendar.DAY_OF_YEAR);
        String cached = days.get(key);
        if (cached != null) return cached;

        Calendar now = Calendar.getInstance();
        String label;
        if (sameDay(now, cal)) {
            label = "сегодня";
        } else {
            now.add(Calendar.DAY_OF_YEAR, -1);
            label = sameDay(now, cal) ? "вчера" : null;
        }
        if (label == null) {
            String d = DAY.format(new Date(at));
            Calendar cur = Calendar.getInstance();
            label = cal.get(Calendar.YEAR) == cur.get(Calendar.YEAR) ? d : d + " " + cal.get(Calendar.YEAR);
        }
        days.put(key, label);
        return label;
    }

    private static boolean sameDay(Calendar a, Calendar b) {
        return a.get(Calendar.YEAR) == b.get(Calendar.YEAR)
                && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR);
    }

    private static int color(int kind) {
        switch (kind) {
            case JeLog.REMOVE: return Ui.ALERT;
            case JeLog.LEAVE: return Ui.CALM;
            case JeLog.MISS: return Ui.SIGNAL;
            default: return Ui.HAIR;
        }
    }

    private static String verdict(int kind) {
        switch (kind) {
            case JeLog.REMOVE: return "снято";
            case JeLog.LEAVE: return "оставлено";
            case JeLog.SKIP: return "пропущено";
            case JeLog.MISS: return "не в списке";
            default: return "служебное";
        }
    }

    /** Правая мелочь в строке вердикта: уверенность, попытки, причина пропуска. */
    private static String tech(JeLog.Entry e) {
        String note = e.note == null ? "" : e.note;
        if (e.kind == JeLog.REMOVE || e.kind == JeLog.LEAVE) {
            StringBuilder sb = new StringBuilder();
            Matcher m = CONF.matcher(note);
            if (m.find()) sb.append("уверенность ").append(m.group(1).replace(',', '.'));
            Matcher a = Pattern.compile("с (\\d+)-й попытки").matcher(note);
            if (a.find()) sb.append(sb.length() > 0 ? ", " : "").append("с ").append(a.group(1)).append("-й попытки");
            if (note.contains("тихо")) sb.append(sb.length() > 0 ? ", " : "").append("тихий режим");
            if (note.contains("нет сети")) sb.append(sb.length() > 0 ? ", " : "").append("сети не было");
            return sb.toString();
        }
        if (e.kind == JeLog.SKIP) {
            String reason = note.startsWith("SKIP") ? note.substring(4).trim() : note;
            return reason;
        }
        return "";
    }

    /** Часть «(+N таких же за 5 мин)» показываем отдельно, а не внутри текста уведомления. */
    private static String tail(String body) {
        int i = body.indexOf("(+");
        return i > 0 ? body.substring(i).trim() : "";
    }

    private static String bodyOnly(String body) {
        int i = body.indexOf("(+");
        return i > 0 ? body.substring(0, i).trim() : body;
    }

    private Drawable icon(String pkg) {
        if (pkg == null) return getDrawable(R.mipmap.ic_launcher);
        Drawable d = icons.get(pkg);
        if (d == null) {
            try {
                d = getPackageManager().getApplicationIcon(pkg);
            } catch (Throwable t) {
                d = getDrawable(android.R.drawable.sym_def_app_icon);
            }
            icons.put(pkg, d);
        }
        return d;
    }

    private String appName(String pkg) {
        if (pkg == null) return "Jev Filter";
        String s = labels.get(pkg);
        if (s == null) {
            try {
                PackageManager pm = getPackageManager();
                s = String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)));
            } catch (Throwable t) {
                s = pkg;
            }
            labels.put(pkg, s);
        }
        return s;
    }

    @Override
    protected void onResume() {
        super.onResume();
        ui.removeCallbacks(ticker);
        ui.post(ticker);
    }

    @Override
    protected void onPause() {
        super.onPause();
        ui.removeCallbacks(ticker);
    }

    private final class Rows extends BaseAdapter {

        private final List<JeLog.Entry> data = new ArrayList<JeLog.Entry>();

        void setData(List<JeLog.Entry> d) {
            data.clear();
            data.addAll(d);
        }

        @Override
        public int getCount() {
            return data.size();
        }

        @Override
        public Object getItem(int i) {
            return data.get(i);
        }

        @Override
        public long getItemId(int i) {
            return i;
        }

        @Override
        public View getView(int i, View v, ViewGroup parent) {
            if (v == null) v = row();
            Holder h = (Holder) v.getTag();
            JeLog.Entry e = data.get(i);

            String day = day(e.at);
            boolean fresh = i == 0 || !day.equals(day(data.get(i - 1).at));
            h.day.setVisibility(fresh ? View.VISIBLE : View.GONE);
            h.day.setText(day);

            h.rule.setBackgroundColor(color(e.kind));
            h.icon.setImageDrawable(icon(e.pkg));
            h.app.setText(appName(e.pkg));
            h.time.setText(time(e.at));

            String body = bodyOnly(e.body == null || e.body.length() == 0 ? e.note : e.body);
            h.body.setText(body);

            String t = tech(e);
            String extra = tail(e.body);
            if (extra.length() > 0) t = t.length() > 0 ? t + "  " + extra : extra;
            h.tech.setText(t);

            // у служебных строк вердикта нет: сообщение и есть весь смысл строки
            String word = e.kind == JeLog.INFO ? "" : verdict(e.kind);
            h.verdict.setText(word);
            h.verdict.setTextColor(color(e.kind));
            h.line3.setVisibility(word.length() == 0 && t.length() == 0 ? View.GONE : View.VISIBLE);
            return v;
        }
    }

    private final class Holder {
        TextView day, app, time, body, verdict, tech;
        View rule, line3;
        ImageView icon;
    }

    private View row() {
        LinearLayout outer = Ui.column(this);

        TextView day = Ui.label(this, "", 11.5f, Ui.ASH);
        day.setPadding(0, Ui.dp(this, 20), 0, Ui.dp(this, 7));
        outer.addView(day);

        LinearLayout line = Ui.row(this);

        View rule = new View(this);
        line.addView(rule, new LinearLayout.LayoutParams(Ui.dp(this, 3),
                ViewGroup.LayoutParams.MATCH_PARENT));

        ImageView icon = new ImageView(this);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(Ui.dp(this, 34), Ui.dp(this, 34));
        ilp.leftMargin = Ui.dp(this, 13);
        line.addView(icon, ilp);

        LinearLayout content = Ui.column(this);
        content.setPadding(Ui.dp(this, 13), Ui.dp(this, 11), 0, Ui.dp(this, 11));

        LinearLayout l1 = Ui.row(this);
        TextView app = Ui.label(this, "", 15, Ui.CHALK);
        TextView time = Ui.label(this, "", 11, Ui.ASH);
        time.setTypeface(Typeface.MONOSPACE);
        l1.addView(app, Ui.weighted());
        l1.addView(time);
        content.addView(l1);

        TextView body = Ui.label(this, "", 13.5f, Ui.ASH);
        body.setMaxLines(2);
        body.setEllipsize(TextUtils.TruncateAt.END);
        body.setPadding(0, Ui.dp(this, 3), 0, 0);
        content.addView(body);

        LinearLayout l3 = Ui.row(this);
        l3.setPadding(0, Ui.dp(this, 6), 0, 0);
        TextView verdict = Ui.label(this, "", 12, Ui.ASH);
        verdict.setSingleLine(true);
        TextView tech = Ui.label(this, "", 11, Ui.ASH);
        tech.setTypeface(Typeface.MONOSPACE);
        tech.setSingleLine(true);
        tech.setEllipsize(TextUtils.TruncateAt.END);
        tech.setGravity(Gravity.END);
        l3.addView(verdict);
        l3.addView(tech, Ui.weighted());
        content.addView(l3);

        line.addView(content, Ui.weighted());
        outer.addView(line);

        Holder h = new Holder();
        h.day = day;
        h.rule = rule;
        h.icon = icon;
        h.app = app;
        h.time = time;
        h.body = body;
        h.verdict = verdict;
        h.tech = tech;
        h.line3 = l3;
        outer.setTag(h);
        return outer;
    }
}
