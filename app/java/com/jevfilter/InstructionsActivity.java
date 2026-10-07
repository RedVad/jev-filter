package com.jevfilter;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Пошаговая инструкция: как довести приложение до рабочего состояния на новом телефоне. */
public class InstructionsActivity extends Activity {

    private static final String[][] STEPS = {
            {"1. Установи приложение",
                    "Скопируй jevfilter.apk на телефон и открой его. Android спросит разрешение на установку "
                            + "из неизвестного источника — разреши для файлового менеджера или браузера."},
            {"2. Вставь ключ OpenRouter",
                    "Ключ начинается с sk-or-v1- и лежит в файле .env на компьютере. Вставь его в поле "
                            + "«Ключ OpenRouter» и нажми «Сохранить настройки». Без ключа приложение не сможет "
                            + "спрашивать Джева и будет просто ничего не делать."},
            {"3. Открой доступ к уведомлениям",
                    "Нажми «Открыть доступ к уведомлениям», найди в списке Jev Filter и включи переключатель. "
                            + "Если экран открылся, а переключателя нет — телефон нестандартный: Настройки → "
                            + "Приложения → Специальный доступ → Доступ к уведомлениям → Jev Filter."},
            {"4. Разреши свои уведомления",
                    "В строке «Свои уведомления» должно быть «разрешены». Это нужно для режима «сначала тихо»: "
                            + "приложение показывает уведомление от себя. Если стоит «запрещены» — Настройки → "
                            + "Приложения → Jev Filter → Уведомления → включить."},
            {"5. Разреши работу в фоне",
                    "Нажми «Разрешить работу в фоне» и подтверди. На vivo и iQOO дополнительно: Настройки → "
                            + "Приложения → Jev Filter → Автозапуск → разрешить. Там же поставь батарею "
                            + "«Без ограничений». Без этого система отвяжет слушателя и фильтр замолчит."},
            {"6. Выбери приложения",
                    "Нажми «Выбрать приложения», отметь те, чьи уведомления нужно проверять, и нажми "
                            + "«Сохранить выбор». Уведомления остальных приложений не трогаются вообще."},
            {"7. Выбери режим",
                    "«Отложить до решения» — уведомление прячется сразу, вместе со звуком, и возвращается "
                            + "со звуком только если Джев его оставил; спам не показывается вовсе. "
                            + "«Сначала тихо» — тихая копия видна сразу, звук даётся только одобренному. "
                            + "«Убирать лишнее» — уведомление живёт до вердикта, спам исчезает через пару секунд. "
                            + "Режим переключается нажатием на строку «Режим»."},
            {"8. Проверь",
                    "Кнопки «На спаме» и «На важном» отправляют Джеву тестовые уведомления. В журнале "
                            + "снизу видно каждый вердикт: строка «REMOVE dismiss» значит спам, «LEAVE keep» — "
                            + "уведомление осталось."},
            {"Что вообще не уходит в сеть",
                    "Код подтверждения, списание, перевод, вход в аккаунт, банк и госуслуги решаются "
                            + "на телефоне, без Джева: такие уведомления не снимаются никогда, а их текст "
                            + "никуда не отправляется. В запрос к Джеву цифровые последовательности "
                            + "из 4-8 знаков уходят заменёнными на точки."},
            {"Если хочется абсолютной тишины",
                    "Отмена уведомления обрывает звук, но первые доли секунды он может успеть начаться. "
                            + "Чтобы этого не было совсем, выключи звук у каналов самих приложений: Настройки → "
                            + "Приложения → нужное приложение → Уведомления → нужный канал → «Тихие». "
                            + "Тогда звук будет идти только от одобренных уведомлений."},
            {"Если перестало работать",
                    "Открой Jev Filter: при открытии слушатель подключается заново. Проверь строку «Доступ к "
                            + "уведомлениям» — там должно быть «выдан». Если в журнале пусто, значит система "
                            + "отвязала слушателя: выключи и включи доступ к уведомлениям."},
            {"Чего приложение не умеет",
                    "Оно не может перехватить уведомление до звука: Android не даёт этого сторонним "
                            + "приложениям. Оно также не трогает уведомления из списка «Не трогать никогда» "
                            + "(будильник, звонки, SMS, система) и уведомления от постоянных процессов, "
                            + "например от музыкального плеера."},
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
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

        TextView title = Ui.label(this, "Как довести до рабочего состояния", 21, Ui.CHALK);
        title.setLetterSpacing(0.01f);
        title.setPadding(0, Ui.dp(this, 12), 0, Ui.dp(this, 6));
        col.addView(title);

        TextView intro = Ui.label(this, "Порядок важен: без доступа к уведомлениям и без разрешения на фоновую "
                + "работу фильтр не заработает, даже если всё остальное настроено.", 13, Ui.ASH);
        col.addView(intro);

        for (String[] step : STEPS) {
            TextView head = Ui.label(this, step[0], 15, Ui.CHALK);
            head.setPadding(0, Ui.dp(this, 24), 0, Ui.dp(this, 8));
            col.addView(head);
            TextView body = Ui.label(this, step[1], 13, Ui.ASH);
            body.setLineSpacing(Ui.dp(this, 4), 1f);
            col.addView(body);
        }

        col.addView(Ui.hairline(this));

        Button access = Ui.quiet(this, "Открыть доступ к уведомлениям");
        LinearLayout.LayoutParams p1 = Ui.wide(Ui.dp(this, 46));
        p1.topMargin = Ui.dp(this, 22);
        col.addView(access, p1);
        access.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
                } catch (Throwable ignored) {
                }
            }
        });

        Button battery = Ui.quiet(this, "Разрешить работу в фоне");
        LinearLayout.LayoutParams p2 = Ui.wide(Ui.dp(this, 46));
        p2.topMargin = Ui.dp(this, 12);
        col.addView(battery, p2);
        battery.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                    i.setData(Uri.parse("package:" + getPackageName()));
                    startActivity(i);
                } catch (Throwable ignored) {
                }
            }
        });

        if (Build.VERSION.SDK_INT >= 33) {
            Button post = Ui.quiet(this, "Разрешить свои уведомления");
            LinearLayout.LayoutParams p3 = Ui.wide(Ui.dp(this, 46));
            p3.topMargin = Ui.dp(this, 12);
            col.addView(post, p3);
            post.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    try {
                        requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 7);
                    } catch (Throwable ignored) {
                    }
                }
            });
        }

        ScrollView root = new ScrollView(this);
        root.setBackgroundColor(Ui.INK);
        root.addView(col, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(root);
    }
}
