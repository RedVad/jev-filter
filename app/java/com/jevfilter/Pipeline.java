package com.jevfilter;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Общая логика обработки уведомления.
 *
 * Режим «отложить до решения»: оригинал снимается сразу (отмена обрывает и звук), своей копии
 * не показываем, пока Джев думает; оставил - показываем копию со звуком, назвал спамом - не
 * показываем вовсе.
 * Режим «сначала тихо»: своя тихая копия видна сразу, оригинал снимается, одобренное
 * перевыкладывается со звуком.
 * Режим «убирать лишнее»: оригинал живёт до вердикта, снимается только спам.
 *
 * Любая ошибка трактуется в пользу человека: уведомление остаётся и звучит.
 * Код подтверждения, списание и вход в аккаунт решаются локально в StopList, до сети.
 */
final class Pipeline {

    static final String CH_SILENT = "jev_silent";
    static final String CH_AUDIBLE = "jev_audible";

    private static final int QUEUE = 12;
    /** Окно защиты от настоящего дубля: тот же ключ И тот же текст. */
    private static final long DEDUPE_MS = 3000L;
    /**
     * «Шумные» события — те, после которых ничего не произошло, — пишем в журнал не чаще раза
     * в 5 минут на одно и то же уведомление. VPN (com.github.metacubex.clash.meta) перевыкладывает
     * своё постоянное уведомление со счётчиком трафика примерно раз в секунду: это 89 % всех
     * записей журнала, из-за чего кольцо на 600 строк вытесняло настоящие решения за 10 минут.
     */
    private static final long CHATTER_MS = 5L * 60L * 1000L;
    private static final long NO_NET_LOG_MS = 30_000L;
    private static long lastNoNetLog = 0L;

    /**
     * Порог для уведомлений с цифрами (код, сумма, номер заказа). В рекламе тоже полно цифр,
     * поэтому цифры не запрещают фильтрацию, но требуют от Джева большей уверенности.
     */
    private static final double DIGIT_THRESHOLD = 0.75d;

    /** Несколько потоков: один медленный запрос не должен задерживать остальные уведомления. */
    private static final ThreadPoolExecutor POOL = new ThreadPoolExecutor(
            3, 3, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<Runnable>(QUEUE),
            new ThreadPoolExecutor.DiscardOldestPolicy());

    private static final Object SEEN_LOCK = new Object();
    private static String lastFingerprint = null;
    private static long lastFingerprintAt = 0L;

    /** Живость: сколько уведомлений вообще дошло до приложения и когда последнее. */
    static final java.util.concurrent.atomic.AtomicInteger RECV =
            new java.util.concurrent.atomic.AtomicInteger();
    static final java.util.concurrent.atomic.AtomicInteger DECIDED =
            new java.util.concurrent.atomic.AtomicInteger();
    static volatile long LAST_RECV = 0L;

    private Pipeline() {
    }

    static void channels(Context c) {
        try {
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            NotificationChannel silent = new NotificationChannel(CH_SILENT, "Ждут решения", NotificationManager.IMPORTANCE_LOW);
            silent.setDescription("Показаны без звука, пока Джев решает");
            silent.setSound(null, null);
            silent.enableVibration(false);
            silent.setShowBadge(false);
            NotificationChannel audible = new NotificationChannel(CH_AUDIBLE, "Одобренные Джевом", NotificationManager.IMPORTANCE_HIGH);
            audible.setDescription("Со звуком: Джев счёл их важными");
            nm.createNotificationChannel(silent);
            nm.createNotificationChannel(audible);
        } catch (Throwable t) {
            android.util.Log.e("JevFilter", "channels: " + t);
        }
    }

    static boolean canPost(Context c) {
        try {
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return false;
            if (Build.VERSION.SDK_INT >= 24) return nm.areNotificationsEnabled();
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    static boolean interesting(SharedPreferences p, String pkg) {
        if (pkg == null || pkg.length() == 0) return false;
        if (Prefs.isNever(p, pkg)) return false;
        return Prefs.isBlacklisted(p, pkg);
    }

    static void onPosted(final NotificationListenerService svc, final StatusBarNotification sbn) {
        if (svc == null || sbn == null) return;
        try {
            final Context c = svc.getApplicationContext();
            final SharedPreferences p = c.getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE);
            if (!p.getBoolean(Prefs.ENABLED, true)) return;

            final String pkg = sbn.getPackageName();
            if (pkg == null || c.getPackageName().equals(pkg)) return;

            RECV.incrementAndGet();
            LAST_RECV = System.currentTimeMillis();

            final String key = sbn.getKey();
            final int nid = sbn.getId();

            final Notification n = sbn.getNotification();
            if (n == null) {
                quiet(c, "empty", pkg, key, nid, "SKIP пустое уведомление [" + pkg + "]");
                return;
            }

            if ((n.flags & Notification.FLAG_GROUP_SUMMARY) != 0) {
                quiet(c, "group", pkg, key, nid, "SKIP групповое [" + pkg + "]");
                return;
            }
            if (key != null && key.contains("|g:")) {
                quiet(c, "groupsum", pkg, key, nid, "SKIP сводка группы [" + pkg + "]");
                return;
            }

            final Bundle ex = n.extras;
            final String title = str(ex == null ? null : ex.getCharSequence(Notification.EXTRA_TITLE));
            final String text = str(ex == null ? null : ex.getCharSequence(Notification.EXTRA_TEXT));

            final int flags = n.flags;
            boolean ongoing = (flags & Notification.FLAG_ONGOING_EVENT) != 0
                    || (flags & Notification.FLAG_FOREGROUND_SERVICE) != 0;
            if (ongoing && p.getBoolean(Prefs.SKIP_ONGOING, true)) {
                quiet(c, "ongoing", pkg, key, nid, "SKIP постоянное [" + pkg + "] " + one(title));
                return;
            }

            if (Prefs.isNever(p, pkg)) {
                quiet(c, "service", pkg, key, nid, "SKIP служебное [" + pkg + "] " + one(title));
                return;
            }
            if (!Prefs.isBlacklisted(p, pkg)) {
                // чтобы в журнале было видно всё, а не только отмеченные приложения
                if (Prefs.logAll(p)) {
                    quiet(c, "miss", pkg, key, nid,
                            "MISS   не в списке [" + pkg + "] " + one(title) + " :: " + one(text));
                }
                return;
            }
            android.util.Log.i("JevFilter", "recv " + pkg + " key=" + key);

            // Стоп-лист раньше сети и раньше любых действий: код подтверждения, списание, вход
            // в аккаунт не уходят в OpenRouter и не снимаются. Оригинал при этом не трогаем
            // совсем, поэтому такое уведомление и звучит как обычно.
            final String stop = StopList.reason(pkg, title, text);
            if (stop != null) {
                JeLog.add(c, "LEAVE  стоп-лист (" + stop + ") [" + pkg + "] "
                        + one(title) + " :: " + one(StopList.mask(text)));
                return;
            }
            final boolean sensitive = StopList.hasDigits(title, text);

            // устаревшее отсекаем сразу, а не после ожидания в очереди:
            // иначе медленный запрос «состаривает» следующие уведомления
            long age = System.currentTimeMillis() - sbn.getPostTime();
            int stale = Prefs.staleSec(p);
            if (stale > 0 && age > stale * 1000L) {
                quiet(c, "stale", pkg, key, nid, "SKIP устарело (" + (age / 1000) + " с) [" + pkg + "] " + one(title));
                return;
            }

            final String chan = n.getChannelId();
            final String app = appLabel(c, pkg);
            final long postTime = sbn.getPostTime();

            POOL.execute(new Runnable() {
                @Override
                public void run() {
                    handle(svc, c, p, n, key, postTime, pkg, app, chan, title, text, sensitive);
                }
            });
        } catch (Throwable t) {
            JeLog.add(svc, "ERROR callback " + t);
        }
    }

    private static void handle(NotificationListenerService svc, Context c, SharedPreferences p,
                               Notification original, String key, long postTime, String pkg,
                               String app, String chan, String title, String text, boolean sensitive) {
        String tag = key == null ? pkg : key;
        int id = Math.abs(tag.hashCode());
        String mode = Prefs.mode(p);
        boolean silentMode = Prefs.MODE_SILENT.equals(mode);
        boolean deferMode = Prefs.MODE_DEFER.equals(mode);
        boolean canPost = canPost(c);
        boolean useCopy = (silentMode || deferMode) && canPost;
        /** «Отложено»: копию не показываем, пока Джев думает. Вернём, только если он оставит. */
        boolean hidden = deferMode && canPost;
        final String token = key != null ? key : pkg + "#" + id;

        try {
            long now = System.currentTimeMillis();
            // Дедуплицировать по ключу нельзя: приложения вроде Ozon переиспользуют один и тот же
            // ключ для каждого нового уведомления, и тогда каждое следующее считалось «повтором»
            // и оставалось в шторке. Сравниваем содержимое и только в пределах пары секунд.
            final String fingerprint = pkg + "\u0001" + key + "\u0001" + title + "\u0001" + text;
            synchronized (SEEN_LOCK) {
                if (fingerprint.equals(lastFingerprint) && now - lastFingerprintAt < DEDUPE_MS) {
                    quiet(c, "dup", pkg, key, id, "SKIP повтор (то же содержимое, "
                            + (now - lastFingerprintAt) + " мс) [" + pkg + "] " + one(title));
                    return;
                }
                lastFingerprint = fingerprint;
                lastFingerprintAt = now;
            }

            if ((silentMode || deferMode) && !canPost) {
                JeLog.add(c, "режим «" + (deferMode ? "отложить" : "сначала тихо")
                        + "» не работает: приложению не разрешены свои уведомления");
            }

            if (useCopy) {
                if (hidden) {
                    // Защёлка ДО снятия оригинала: если процесс умрёт в эти секунды,
                    // уведомление вернётся при следующем запуске, а не пропадёт.
                    if (Pending.put(c, token, pkg, app, title, text)) {
                        svc.cancelNotification(key);
                    } else {
                        // Записать не смогли - значит и прятать нельзя: иначе при первом же
                        // сбое процесса уведомление исчезнет навсегда. Работаем как «убирать
                        // лишнее»: оригинал живёт до вердикта.
                        hidden = false;
                        useCopy = false;
                        JeLog.add(c, "защёлка не записалась, оригинал не трогаю [" + pkg + "] " + one(title));
                    }
                } else {
                    // Сначала показываем тихую копию и убеждаемся, что она встала, и только потом
                    // убираем оригинал: notify() молчит и при отказе, а потерять уведомление
                    // совсем - худшее из возможного.
                    if (showCopy(c, tag, id, app, pkg, title, text, original, false) && ownActive(c, tag, id)) {
                        svc.cancelNotification(key);
                    } else {
                        useCopy = false;
                        JeLog.add(c, "копия не показалась, оригинал не трогаю [" + pkg + "] " + one(title));
                    }
                }
            }

            if (!Net.allowed(c)) {
                boolean shown = true;
                if (useCopy) {
                    // без сети решить нельзя - значит не молчим
                    shown = showCopy(c, tag, id, app, pkg, title, text, original, true);
                }
                if (hidden && shown) Pending.done(c, token);
                JeLog.add(c, "LEAVE  нет сети, решения нет [" + pkg + "] " + one(title));
                return;
            }

            double threshold = Prefs.threshold(p);
            if (sensitive && threshold < DIGIT_THRESHOLD) threshold = DIGIT_THRESHOLD;
            JevClient.Verdict v = JevClient.ask(p.getString(Prefs.KEY, ""), Prefs.rules(p),
                    pkg, app, chan, title, text);
            Net.ok();

            boolean keep = !v.shouldRemove(threshold);
            DECIDED.incrementAndGet();
            JeLog.add(c, (keep ? "LEAVE  " : "REMOVE ") + v.choice + " " + conf(v.confidence)
                    + (hidden ? " (отложено)" : (useCopy ? " (тихо)" : ""))
                    + (sensitive ? " цифры, порог " + conf(threshold) : "")
                    + (v.attempts > 1 ? " с " + v.attempts + "-й попытки, " + (v.elapsedMs / 1000.0) + " с" : "")
                    + " [" + pkg + "] " + one(title) + " :: " + one(text));

            if (useCopy) {
                if (keep) {
                    // одобрено: тот же текст, но уже со звуком
                    if (hidden && active(svc, key)) svc.cancelNotification(key);
                    if (hidden && !showCopy(c, tag, id, app, pkg, title, text, original, true)) {
                        // Копию показать не удалось. Из защёлки не убираем: вернём её при
                        // следующем запуске (в пределах 15 минут), чем потеряем совсем.
                        JeLog.add(c, "копию показать не смог, верну при запуске [" + pkg + "] " + one(title));
                        return;
                    }
                } else {
                    cancelCopy(c, tag, id);
                    if (hidden && active(svc, key)) svc.cancelNotification(key);
                }
                if (hidden) Pending.done(c, token);
            } else if (!keep) {
                svc.cancelNotification(key);
            }
        } catch (Throwable t) {
            boolean tripped = Net.fail();
            JeLog.add(c, "LEAVE  (ошибка" + (tripped ? ", пауза 2 мин" : "") + ") "
                    + t.getClass().getSimpleName() + ": " + one(t.getMessage()) + " [" + pkg + "]");
            // Ошибка - лучше озвучить, чем потерять. Если и показать не вышло, уведомление
            // остаётся в защёлке и вернётся при следующем запуске.
            boolean shown = true;
            if (useCopy) shown = showCopy(c, tag, id, app, pkg, title, text, original, true);
            if (hidden) {
                if (shown) {
                    Pending.done(c, token);
                } else {
                    JeLog.add(c, "ошибка: копию показать не смог, верну при запуске [" + pkg + "]");
                }
            }
        }
    }

    /**
     * Висит ли уведомление в системе прямо сейчас.
     *
     * cancelNotification ничего не возвращает: отказ (например, для постоянного уведомления)
     * выглядит как успех. В режиме «отложить» это важно - иначе на «оставить» мы покажем копию
     * рядом с неснятым оригиналом, а на «убрать» оставим спам в шторке.
     */
    private static boolean active(NotificationListenerService svc, String key) {
        if (key == null || Build.VERSION.SDK_INT < 23) return false;
        try {
            StatusBarNotification[] a = svc.getActiveNotifications(new String[]{key});
            return a != null && a.length > 0;
        } catch (Throwable t) {
            return false;
        }
    }

    private static void cancelCopy(Context c, String tag, int id) {
        try {
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.cancel(tag, id);
        } catch (Throwable t) {
            android.util.Log.e("JevFilter", "cancel copy: " + t);
        }
    }

    /** Запускали ли уже возврат потерянных уведомлений в этом процессе. */
    private static volatile boolean pendingRestored = false;

    /**
     * Возврат отложенных уведомлений, потерянных из-за смерти процесса между снятием оригинала
     * и вердиктом. Делается один раз за запуск процесса: при повторной привязке живого слушателя
     * в защёлке лежат ещё не решённые уведомления, и возвращать их рано.
     *
     * Возвращаем со звуком: неизвестно, важное это было или спам, а потерять важное хуже,
     * чем показать лишнее. Кнопки и переход в приложение при этом теряются - PendingIntent
     * через файл не передать.
     */
    static void restorePending(Context c) {
        if (pendingRestored || c == null) return;
        pendingRestored = true;
        try {
            java.util.List<Pending.Item> lost = Pending.take(c);
            if (lost.isEmpty()) return;
            for (Pending.Item it : lost) {
                showCopy(c, it.token, Math.abs(it.token.hashCode()), it.app, it.pkg, it.title, it.text, null, true);
            }            JeLog.add(c, "вернул отложенные после перезапуска: " + lost.size());
        } catch (Throwable t) {
            JeLog.add(c, "не смог вернуть отложенные: " + t);
        }
    }

    /**
     * Показывает нашу копию уведомления. Возвращает true, если notify() не отказал.
     *
     * Отказ возможен молча (запрет своих уведомлений, лимит на число постов), поэтому там, где
     * копия - единственный способ не потерять уведомление, её наличие ещё проверяется в системе
     * через {@link #ownActive}.
     */
    private static boolean showCopy(Context c, String tag, int id, String app, String pkg, String title,
                                    String text, Notification original, boolean audible) {
        // Значок исходного приложения: копию постим мы, и система по умолчанию нарисует наш
        // колокольчик, хотя уведомление чужое. Оформление - не повод рисковать содержимым,
        // поэтому если система значок не приняла, показываем копию со своим.
        Icon source = sourceIcon(c, pkg);
        if (source != null) {
            try {
                post(c, tag, id, app, title, text, original, audible, source);
                return true;
            } catch (Throwable t) {
                JeLog.add(c, "значок " + pkg + " не принят, показываю свой: " + t);
            }
        }
        try {
            post(c, tag, id, app, title, text, original, audible, null);
            return true;
        } catch (Throwable t) {
            JeLog.add(c, "не смог показать копию: " + t);
            return false;
        }
    }

    private static void post(Context c, String tag, int id, String app, String title, String text,
                             Notification original, boolean audible, Icon small) throws Exception {
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) throw new IllegalStateException("нет NotificationManager");
        channels(c);
        Notification.Builder b = new Notification.Builder(c, audible ? CH_AUDIBLE : CH_SILENT)
                .setSmallIcon(small != null ? small : Icon.createWithResource(c, R.drawable.ic_small))
                .setContentTitle(title.length() == 0 ? app : title)
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setSubText(app)
                .setAutoCancel(true)
                .setShowWhen(true)
                .setWhen(System.currentTimeMillis());
        if (original != null) {
            if (original.contentIntent != null) b.setContentIntent(original.contentIntent);
            Notification.Action[] acts = original.actions;
            if (acts != null) {
                for (Notification.Action a : acts) {
                    if (a != null) b.addAction(a);
                }
            }
        }
        nm.notify(tag, id, b.build());
    }

    /**
     * Значок приложения, чьё уведомление мы перевыкладываем.
     *
     * Ссылка на чужой ресурс здесь работает: значок грузит SystemUI, а он системный процесс и
     * ресурсы любого пакета читать умеет. Свой значок был бы честнее по букве правил, но в шторке
     * он врёт - уведомление выглядит нашим, хотя пришло из другого приложения.
     */
    private static Icon sourceIcon(Context c, String pkg) {
        if (pkg == null || pkg.length() == 0) return null;
        if (c.getPackageName().equals(pkg)) return null;
        try {
            android.content.pm.ApplicationInfo ai =
                    c.getPackageManager().getApplicationInfo(pkg, 0);
            if (ai == null || ai.icon == 0) return null;
            return Icon.createWithResource(pkg, ai.icon);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Встала ли наша копия на самом деле. notify() не сообщает об отказе, а отличить «показали»
     * от «не показали» нужно до того, как снимем оригинал.
     */
    private static boolean ownActive(Context c, String tag, int id) {
        if (Build.VERSION.SDK_INT < 23) return true;
        try {
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return true;
            String me = c.getPackageName();
            for (StatusBarNotification s : nm.getActiveNotifications()) {
                if (s.getId() != id || !me.equals(s.getPackageName())) continue;
                String st = s.getTag();
                if (tag == null ? st == null : tag.equals(st)) return true;
            }
            return false;
        } catch (Throwable t) {
            // проверить не смогли - верим notify()
            return true;
        }
    }

    private static String appLabel(Context c, String pkg) {
        try {
            return String.valueOf(c.getPackageManager().getApplicationLabel(
                    c.getPackageManager().getApplicationInfo(pkg, 0)));
        } catch (Throwable t) {
            return pkg;
        }
    }

    /**
     * Журнальная запись о событии, после которого ничего не произошло.
     *
     * Ключ троттлинга — конкретное уведомление (пакет + ключ), а не приложение: иначе схлопнулись
     * бы разные сообщения одного мессенджера. Перевыкладывание одного и того же уведомления
     * (VPN со счётчиком трафика) схлопывается, новые уведомления видны все.
     */
    private static void quiet(Context c, String kind, String pkg, String key, int id, String line) {
        String k = kind + '\u0001' + pkg + '\u0001' + (key != null ? key : "#" + id);
        JeLog.addThrottled(c, k, CHATTER_MS, line);
    }

    private static String str(CharSequence cs) {
        return cs == null ? "" : cs.toString();
    }

    private static String one(String s) {
        if (s == null) return "";
        s = s.replace('\n', ' ').replace('\r', ' ').trim();
        return s.length() > 220 ? s.substring(0, 220) + "..." : s;
    }

    private static String conf(double d) {
        return String.format(Locale.US, "%.2f", d);
    }
}
