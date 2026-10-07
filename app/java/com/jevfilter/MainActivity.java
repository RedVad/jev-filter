package com.jevfilter;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.service.notification.NotificationListenerService;
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

import java.util.List;

public class MainActivity extends Activity {

    private SharedPreferences p;
    private EditText keyBox, thrBox, staleBox, neverBox;
    private TextView ring, stateWord, hintWord, accessValue, battValue, postValue, seenValue, modeValue, modeNote,
            blSummary, warnView, logMeta, logAllValue, counter;
    private Button blBtn;
    private boolean enabled;
    private float dp;
    private final android.os.Handler ui = new android.os.Handler(android.os.Looper.getMainLooper());

    /** На главном экране только счётчики: сам журнал живёт на отдельной странице. */
    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            if (logMeta != null) {
                long last = JeLog.lastDecisionAt();
                logMeta.setText(JeLog.size() + " записей, из них решений " + JeLog.decisions()
                        + (last == 0L ? ". Решений ещё не было" : ". Последнее — " + ago(last)));
            }
            if (seenValue != null) {
                long last = Pipeline.LAST_RECV;
                seenValue.setText(last == 0L
                        ? "ни одного"
                        : Pipeline.RECV.get() + ", последнее " + ago(last));
                seenValue.setTextColor(last == 0L || System.currentTimeMillis() - last > 30 * 60 * 1000L
                        ? Ui.WARN : Ui.CHALK);
            }
            ui.postDelayed(this, 2000);
        }
    };

    private String ago(long t) {
        long d = System.currentTimeMillis() - t;
        if (d < 60000L) return "только что";
        if (d < 3600000L) return (d / 60000L) + " мин назад";
        if (d < 86400000L) return (d / 3600000L) + " ч назад";
        return (d / 86400000L) + " дн назад";
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        p = getSharedPreferences(Prefs.NAME, MODE_PRIVATE);
        dp = getResources().getDisplayMetrics().density;
        enabled = p.getBoolean(Prefs.ENABLED, true);
        JeLog.prime(this);
        Pipeline.channels(this);

        LinearLayout col = Ui.column(this);
        int pad = Ui.dp(this, 20);
        col.setPadding(pad, Ui.dp(this, 18), pad, Ui.dp(this, 28));
        col.setBackgroundColor(Ui.INK);

        // ── кольцо-состояние: сколько приложений под наблюдением, и оно же выключатель
        LinearLayout head = Ui.row(this);
        ring = Ui.label(this, "0", 22, Ui.CHALK);
        ring.setGravity(Gravity.CENTER);
        head.addView(ring, new LinearLayout.LayoutParams(Ui.dp(this, 78), Ui.dp(this, 78)));

        LinearLayout headText = Ui.column(this);
        headText.setPadding(Ui.dp(this, 18), 0, 0, 0);
        TextView appName = Ui.label(this, "Jev Filter", 21, Ui.CHALK);
        appName.setLetterSpacing(0.01f);
        stateWord = Ui.label(this, "", 14, Ui.CHALK);
        stateWord.setPadding(0, Ui.dp(this, 5), 0, 0);
        hintWord = Ui.label(this, "", 12, Ui.ASH);
        hintWord.setPadding(0, Ui.dp(this, 2), 0, 0);
        headText.addView(appName);
        headText.addView(stateWord);
        headText.addView(hintWord);
        head.addView(headText);
        head.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                enabled = !enabled;
                p.edit().putBoolean(Prefs.ENABLED, enabled).apply();
                paintState();
            }
        });
        col.addView(head);

        // ── состояние доступа
        col.addView(Ui.hairline(this));
        accessValue = Ui.label(this, "…", 13, Ui.CHALK);
        col.addView(statusLine("Доступ к уведомлениям", accessValue));
        postValue = Ui.label(this, "…", 13, Ui.CHALK);
        col.addView(statusLine("Свои уведомления", postValue));
        battValue = Ui.label(this, "…", 13, Ui.CHALK);
        col.addView(statusLine("Работа в фоне", battValue));
        seenValue = Ui.label(this, "…", 13, Ui.CHALK);
        col.addView(statusLine("Дошло за запуск", seenValue));

        // ── режим работы
        LinearLayout modeRow = Ui.row(this);
        modeRow.setPadding(0, Ui.dp(this, 16), 0, 0);
        modeRow.addView(Ui.label(this, "Режим", 13, Ui.ASH), Ui.weighted());
        modeValue = Ui.label(this, "", 13, Ui.SIGNAL);
        modeRow.addView(modeValue);
        modeRow.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String cur = Prefs.mode(p);
                String next = Prefs.MODE_DEFER.equals(cur) ? Prefs.MODE_SILENT
                        : Prefs.MODE_SILENT.equals(cur) ? Prefs.MODE_REMOVE : Prefs.MODE_DEFER;
                p.edit().putString(Prefs.MODE, next).apply();
                paintMode();
            }
        });
        col.addView(modeRow);
        modeNote = Ui.label(this, "", 11, Ui.ASH);
        modeNote.setPadding(0, Ui.dp(this, 4), 0, 0);
        col.addView(modeNote);

        // ── ключ
        col.addView(Ui.sectionTitle(this, "Ключ OpenRouter"));
        keyBox = Ui.field(this, p.getString(Prefs.KEY, ""), InputType.TYPE_CLASS_TEXT);
        keyBox.setSingleLine(true);
        col.addView(keyBox);
        col.addView(Ui.meta(this, "хранится только на телефоне"));

        // ── порог
        col.addView(Ui.sectionTitle(this, "Порог уверенности"));
        thrBox = Ui.field(this, p.getString(Prefs.THRESH, "0.5"),
                InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        col.addView(thrBox, new LinearLayout.LayoutParams(Ui.dp(this, 130), ViewGroup.LayoutParams.WRAP_CONTENT));
        col.addView(Ui.meta(this, "от 0 до 1: ниже этого значения уведомление остаётся"));

        // ── задержка
        col.addView(Ui.sectionTitle(this, "Не трогать уведомления старше"));
        LinearLayout staleRow = Ui.row(this);
        staleBox = Ui.field(this, p.getString(Prefs.STALE, "20"), InputType.TYPE_CLASS_NUMBER);
        staleRow.addView(staleBox, new LinearLayout.LayoutParams(Ui.dp(this, 130), ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView sec = Ui.label(this, "сек", 13, Ui.ASH);
        sec.setPadding(Ui.dp(this, 12), 0, 0, 0);
        staleRow.addView(sec);
        col.addView(staleRow);
        col.addView(Ui.meta(this, "то, что ты уже видел, Джев не трогает"));

        // ── что фильтруем
        col.addView(Ui.hairline(this));
        col.addView(Ui.sectionTitle(this, "Что фильтруем"));
        blSummary = Ui.label(this, "", 12, Ui.ASH);
        col.addView(blSummary);
        warnView = Ui.label(this, "", 12, Ui.WARN);
        warnView.setPadding(0, Ui.dp(this, 8), 0, 0);
        warnView.setVisibility(View.GONE);
        col.addView(warnView);
        blBtn = Ui.quiet(this, "Выбрать приложения");
        blBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    startActivity(new Intent(MainActivity.this, AppPickerActivity.class));
                } catch (Throwable t) {
                    toast("Не открылось: " + t);
                }
            }
        });
        LinearLayout.LayoutParams bp = Ui.wide(Ui.dp(this, 46));
        bp.topMargin = Ui.dp(this, 14);
        col.addView(blBtn, bp);

        // ── неприкосновенное
        col.addView(Ui.sectionTitle(this, "Не трогать никогда"));
        neverBox = Ui.field(this, Prefs.never(p),
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        neverBox.setMinLines(3);
        neverBox.setGravity(Gravity.TOP);
        neverBox.setTextSize(11.5f);
        col.addView(neverBox);
        col.addView(Ui.meta(this, "проверяется раньше чёрного списка: будильник, звонки, SMS, система"));

        // ── сохранить
        col.addView(Ui.hairline(this));
        Button save = Ui.primary(this, "Сохранить настройки");
        LinearLayout.LayoutParams sp = Ui.wide(Ui.dp(this, 52));
        sp.topMargin = Ui.dp(this, 22);
        col.addView(save, sp);
        save.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                save();
            }
        });

        LinearLayout acts = Ui.row(this);
        LinearLayout.LayoutParams ap = Ui.wide(ViewGroup.LayoutParams.WRAP_CONTENT);
        ap.topMargin = Ui.dp(this, 16);
        acts.setLayoutParams(ap);
        TextView openAccess = Ui.action(this, "Открыть доступ к уведомлениям");
        openAccess.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
                } catch (Throwable t) {
                    toast("Не получилось открыть настройки");
                }
            }
        });
        TextView openBattery = Ui.action(this, "Разрешить работу в фоне");
        openBattery.setGravity(Gravity.END);
        openBattery.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                    i.setData(Uri.parse("package:" + getPackageName()));
                    startActivity(i);
                } catch (Throwable t) {
                    toast("Не получилось открыть настройки");
                }
            }
        });
        acts.addView(openAccess, Ui.weighted());
        acts.addView(openBattery, Ui.weighted());
        col.addView(acts);

        // ── ещё
        col.addView(Ui.hairline(this));
        col.addView(Ui.sectionTitle(this, "Ещё"));
        LinearLayout more = Ui.row(this);
        Button how = Ui.quiet(this, "Инструкция");
        Button rules = Ui.quiet(this, "Правила Джева");
        LinearLayout.LayoutParams r2 = Ui.weighted();
        r2.leftMargin = Ui.dp(this, 12);
        more.addView(how, Ui.weighted());
        more.addView(rules, r2);
        col.addView(more, Ui.wide(Ui.dp(this, 46)));
        how.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    startActivity(new Intent(MainActivity.this, InstructionsActivity.class));
                } catch (Throwable t) {
                    toast("Не открылось");
                }
            }
        });
        rules.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    startActivity(new Intent(MainActivity.this, RulesActivity.class));
                } catch (Throwable t) {
                    toast("Не открылось");
                }
            }
        });

        // ── проверка Джева
        col.addView(Ui.sectionTitle(this, "Проверить Джева"));
        LinearLayout tests = Ui.row(this);
        Button spam = Ui.quiet(this, "На спаме");
        Button important = Ui.quiet(this, "На важном");
        LinearLayout.LayoutParams t2 = Ui.weighted();
        t2.leftMargin = Ui.dp(this, 12);
        tests.addView(spam, Ui.weighted());
        tests.addView(important, t2);
        col.addView(tests, Ui.wide(Ui.dp(this, 46)));
        spam.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                askJev("ru.wildberries", "Wildberries", "promo",
                        "Скидка 50% только сегодня!", "Успейте купить, скидки до 70% на всё, кэшбэк 20%!");
            }
        });
        important.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                askJev("ru.rostel", "Госуслуги", "service",
                        "Заявление принято", "Ваше заявление №12345 зарегистрировано, срок рассмотрения 10 дней.");
            }
        });

        // ── журнал: отдельная страница со списком и иконками приложений
        col.addView(Ui.hairline(this));
        counter = Ui.sectionTitle(this, "Журнал решений");
        col.addView(counter);

        LinearLayout logRow = Ui.row(this);
        logRow.setPadding(0, 0, 0, Ui.dp(this, 10));
        logRow.addView(Ui.label(this, "Показывать все уведомления", 13, Ui.ASH), Ui.weighted());
        logAllValue = Ui.label(this, "", 13, Ui.SIGNAL);
        logRow.addView(logAllValue);
        logRow.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                p.edit().putBoolean(Prefs.LOG_ALL, !Prefs.logAll(p)).apply();
                paintLogAll();
            }
        });
        col.addView(logRow);
        col.addView(Ui.meta(this, "с выключенным попадают только решения Джева и служебные пометки"));

        logMeta = Ui.label(this, "", 12, Ui.ASH);
        logMeta.setPadding(0, Ui.dp(this, 10), 0, 0);
        col.addView(logMeta);

        Button openLog = Ui.primary(this, "Открыть журнал");
        openLog.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, JournalActivity.class));
            }
        });
        LinearLayout.LayoutParams olp = Ui.wide(Ui.dp(this, 50));
        olp.topMargin = Ui.dp(this, 14);
        col.addView(openLog, olp);

        ScrollView root = new ScrollView(this);
        root.setBackgroundColor(Ui.INK);
        root.addView(col, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(root);
        // клавиатура не должна выезжать при открытии
        root.setFocusableInTouchMode(true);
        root.requestFocus();

        if (Build.VERSION.SDK_INT >= 33 && !Pipeline.canPost(this)) {
            try {
                requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 7);
            } catch (Throwable ignored) {
            }
        }
    }

    private void paintMode() {
        String m = Prefs.mode(p);
        if (Prefs.MODE_DEFER.equals(m)) {
            modeValue.setText("отложить до решения");
            modeNote.setText("уведомление прячется сразу, вместе со звуком. оставим - вернём со звуком, "
                    + "признаем спамом - не покажем вовсе. нажми, чтобы переключить");
        } else if (Prefs.MODE_SILENT.equals(m)) {
            modeValue.setText("сначала тихо");
            modeNote.setText("тихая копия видна сразу, звук даётся только одобренному. "
                    + "нажми, чтобы переключить");
        } else {
            modeValue.setText("убирать лишнее");
            modeNote.setText("уведомление живёт до вердикта, спам исчезает через пару секунд. "
                    + "нажми, чтобы переключить");
        }
    }

    private void paintLogAll() {
        boolean on = Prefs.logAll(p);
        logAllValue.setText(on ? "да" : "нет");
        logAllValue.setTextColor(on ? Ui.SIGNAL : Ui.ASH);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
        refreshBlacklist();
        paintMode();
        paintLogAll();
        ui.removeCallbacks(ticker);
        ui.post(ticker);
        // если систему почему-то отвязала слушателя, просим привязать заново
        try {
            NotificationListenerService.requestRebind(new ComponentName(this, JevListenerService.class));
        } catch (Throwable ignored) {
        }
        // служба подключается не мгновенно: перечитываем статус через секунду
        accessValue.postDelayed(new Runnable() {
            @Override
            public void run() {
                refreshStatus();
            }
        }, 1500);
    }

    @Override
    protected void onPause() {
        super.onPause();
        ui.removeCallbacks(ticker);
    }

    private LinearLayout statusLine(String left, TextView value) {
        LinearLayout row = Ui.row(this);
        row.setPadding(0, Ui.dp(this, 9), 0, 0);
        row.addView(Ui.label(this, left, 13, Ui.ASH), Ui.weighted());
        row.addView(value);
        return row;
    }

    private void paintState() {
        int n = Prefs.tokens(Prefs.blacklist(p)).size();
        ring.setText(String.valueOf(n));
        int color = enabled ? Ui.CALM : Ui.ALERT;
        ring.setBackground(Ui.ring(color, 3, this));
        ring.setTextColor(enabled ? Ui.CHALK : Ui.ASH);
        stateWord.setText(enabled ? "Фильтр включён" : "Фильтр выключен");
        stateWord.setTextColor(color);
        hintWord.setText(enabled ? "нажми, чтобы выключить" : "нажми, чтобы включить");
    }

    private void refreshStatus() {
        String raw = null;
        try {
            raw = Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners");
        } catch (Throwable t) {
            raw = "ошибка чтения: " + t.getClass().getSimpleName();
        }
        boolean bySetting = raw != null && raw.contains(getPackageName());
        boolean granted = bySetting || JevListenerService.CONNECTED;
        android.util.Log.i("JevFilter", "status granted=" + granted + " bySetting=" + bySetting
                + " connected=" + JevListenerService.CONNECTED + " raw=" + raw);
        accessValue.setText(granted ? "выдан" : "не выдан");
        accessValue.setTextColor(granted ? Ui.CALM : Ui.ALERT);

        boolean batt = false;
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            batt = pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
        } catch (Throwable ignored) {
        }
        battValue.setText(batt ? "без ограничений" : "ограничена");
        battValue.setTextColor(batt ? Ui.CALM : Ui.ALERT);

        boolean canPost = Pipeline.canPost(this);
        postValue.setText(canPost ? "разрешены" : "запрещены");
        postValue.setTextColor(canPost ? Ui.CALM : Ui.ALERT);
    }

    private void refreshBlacklist() {
        List<String> toks = Prefs.tokens(Prefs.blacklist(p));
        blBtn.setText(toks.isEmpty() ? "Выбрать приложения" : "Выбрать приложения (" + toks.size() + ")");

        StringBuilder warn = new StringBuilder();
        for (String t : toks) {
            String note = Prefs.riskNote(t);
            if (note == null) continue;
            if (warn.length() > 0) warn.append('\n');
            warn.append(labelOf(t)).append(": ").append(note);
        }
        warnView.setText(warn.toString());
        warnView.setVisibility(warn.length() == 0 ? View.GONE : View.VISIBLE);

        if (toks.isEmpty()) {
            blSummary.setText("Список пуст, фильтр ничего не делает.");
            paintState();
            return;
        }
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (String t : toks) {
            if (n >= 10) {
                sb.append(" и ещё ").append(toks.size() - n);
                break;
            }
            if (n > 0) sb.append(", ");
            sb.append(labelOf(t));
            n++;
        }
        blSummary.setText(sb.toString());
        paintState();
    }

    private String labelOf(String pkg) {
        try {
            PackageManager pm = getPackageManager();
            return String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)));
        } catch (Throwable t) {
            return pkg;
        }
    }

    private void save() {
        p.edit()
                .putString(Prefs.KEY, keyBox.getText().toString().trim())
                .putString(Prefs.THRESH, thrBox.getText().toString().trim())
                .putString(Prefs.STALE, staleBox.getText().toString().trim())
                .putString(Prefs.NEVER, neverBox.getText().toString().trim())
                .putBoolean(Prefs.ENABLED, enabled)
                .apply();
        refreshStatus();
        toast("Настройки сохранены");
    }

    private void askJev(final String pkg, final String app, final String chan, final String title, final String text) {
        save();
        final String key = p.getString(Prefs.KEY, "");
        JeLog.add(this, "Проверка Джева: " + app + " :: " + title);
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    JevClient.Verdict v = JevClient.ask(key, Prefs.rules(p), pkg, app, chan, title, text);
                    append("Джев ответил: " + v.choice + " " + v.confidence
                            + (v.shouldRemove(Prefs.threshold(p)) ? ", снял бы" : ", оставил бы"));
                } catch (Throwable t) {
                    append("Джев не ответил: " + t.getClass().getSimpleName() + ": " + t.getMessage());
                }
            }
        }).start();
    }

    /** Итог ручной проверки: в журнал (видно на отдельной странице) и всплывающим сообщением. */
    private void append(final String s) {
        JeLog.add(this, s);
        toast(s);
    }

    private void toast(final String s) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(MainActivity.this, s, Toast.LENGTH_SHORT).show();
            }
        });
    }
}
