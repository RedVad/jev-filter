package com.jevfilter;

import android.content.SharedPreferences;

final class Prefs {

    static final String NAME = "jev";
    static final String KEY = "api_key";
    static final String ENABLED = "enabled";
    static final String THRESH = "threshold";
    static final String SKIP_ONGOING = "skip_ongoing";
    static final String BLACKLIST = "blacklist";
    static final String NEVER = "never";
    static final String STALE = "stale_sec";
    static final String LOG_ALL = "log_all";
    static final String MODE = "mode";
    static final String R_INSTR = "rule_instructions";
    static final String R_KEEP = "rule_keep";
    static final String R_MUTE = "rule_mute";
    static final String R_DISMISS = "rule_dismiss";

    /**
     * Отложить до решения: уведомление прячется сразу (вместе со звуком), своей копии не показываем,
     * и возвращается оно только если Джев его оставил.
     */
    static final String MODE_DEFER = "defer";
    /** Сначала показываем тихую копию, звук даём только одобренному. */
    static final String MODE_SILENT = "silent";
    /** Просто убираем то, что Джев назвал спамом. */
    static final String MODE_REMOVE = "remove";

    static final String DEFAULT_RULE_INSTRUCTIONS =
            "A notification just arrived on the user's Android phone. Decide what to do with it. "
            + "Protect anything the user must see now. Remove advertising and mass mailings.";

    static final String DEFAULT_RULE_KEEP =
            "Time critical or personal: messages from real people, verification and OTP codes, bank and payment "
            + "operations, security alerts, delivery with a time window, alarms, reminders and calendar events the "
            + "user created, medical, government and legal notices, travel disruption, missed calls, account problems.";

    static final String DEFAULT_RULE_MUTE =
            "Useful but not urgent: order status changes without a deadline, social network activity, group chats "
            + "without a direct mention, app updates, non urgent reminders, newsletters from a service the user uses.";

    static final String DEFAULT_RULE_DISMISS =
            "Pure noise: advertising, promotions, discounts, sales, cashback offers, rate us requests, game energy or "
            + "lives, re engagement and we miss you messages, spam, mass mailings, repetitive digests, streaming "
            + "recommendations, content the user never asked for.";

    static final class Rule {
        final String instructions, keep, mute, dismiss;

        Rule(String instructions, String keep, String mute, String dismiss) {
            this.instructions = instructions;
            this.keep = keep;
            this.mute = mute;
            this.dismiss = dismiss;
        }
    }

    static Rule rules(SharedPreferences p) {
        return new Rule(
                p.getString(R_INSTR, DEFAULT_RULE_INSTRUCTIONS),
                p.getString(R_KEEP, DEFAULT_RULE_KEEP),
                p.getString(R_MUTE, DEFAULT_RULE_MUTE),
                p.getString(R_DISMISS, DEFAULT_RULE_DISMISS));
    }

    static String mode(SharedPreferences p) {
        String m = p.getString(MODE, MODE_DEFER);
        if (MODE_SILENT.equals(m) || MODE_REMOVE.equals(m)) return m;
        return MODE_DEFER;
    }

    /** Писать в журнал и те уведомления, которых нет в чёрном списке. */
    static boolean logAll(SharedPreferences p) {
        return p.getBoolean(LOG_ALL, true);
    }

    /** Фильтруем только эти приложения (чёрный список). */
    static final String DEFAULT_BLACKLIST =
            "com.wildberries.ru,ru.wildberries,ru.ozon.app.android,com.avito.android,"
            + "ru.yandex.market,com.yandex.go,ru.yandex.taxi,com.aliexpress.buyer,"
            + "ru.dns.shop.android,ru.mvideo.app,com.mvideo.android,ru.eldorado,"
            + "ru.sbermarket,com.deliveryclub,ru.foodfox.client";

    /**
     * Служебное: не трогаем никогда, даже если пакет окажется в чёрном списке.
     * Это страховка от будильника, звонков и SMS.
     */
    static final String DEFAULT_NEVER =
            "com.android.systemui,com.android.settings,com.android.packageinstaller,"
            + "com.android.dialer,com.android.phone,com.android.server.telecom,com.google.android.dialer,"
            + "com.android.mms,com.google.android.apps.messaging,com.android.messaging,"
            + "com.android.deskclock,com.android.BBKClock,com.vivo.deskclock,"
            + "com.android.calendar,com.google.android.calendar,com.android.providers.calendar,"
            + "com.google.android.gms,com.android.vending";

    private Prefs() {
    }

    static boolean inList(String raw, String pkg) {        if (pkg == null || pkg.length() == 0) return false;
        if (raw == null) return false;
        for (String s : raw.split(",")) {
            s = s.trim();
            if (s.length() == 0) continue;
            if (s.endsWith("*")) {
                if (pkg.startsWith(s.substring(0, s.length() - 1))) return true;
            } else if (s.equalsIgnoreCase(pkg)) {
                return true;
            }
        }
        return false;
    }

    static String blacklist(SharedPreferences p) {
        return p.getString(BLACKLIST, DEFAULT_BLACKLIST);
    }

    static String never(SharedPreferences p) {
        return p.getString(NEVER, DEFAULT_NEVER);
    }

    static boolean isBlacklisted(SharedPreferences p, String pkg) {
        return inList(blacklist(p), pkg);
    }

    static boolean isNever(SharedPreferences p, String pkg) {
        return inList(never(p), pkg);
    }

    static double threshold(SharedPreferences p) {
        try {
            double d = Double.parseDouble(p.getString(THRESH, "0.5").trim());
            if (d < 0d) return 0d;
            if (d > 1d) return 1d;
            return d;
        } catch (Throwable t) {
            return 0.5d;
        }
    }

    static int staleSec(SharedPreferences p) {
        try {
            int v = Integer.parseInt(p.getString(STALE, "20").trim());
            if (v < 0) return 0;
            if (v > 600) return 600;
            return v;
        } catch (Throwable t) {
            return 20;
        }
    }

    static java.util.List<String> tokens(String raw) {        java.util.List<String> out = new java.util.ArrayList<String>();
        if (raw == null) return out;
        for (String s : raw.split(",")) {
            s = s.trim();
            if (s.length() > 0) out.add(s);
        }
        return out;
    }

    static String join(java.util.Collection<String> list) {
        StringBuilder sb = new StringBuilder();
        for (String s : list) {
            if (s == null || s.trim().length() == 0) continue;
            if (sb.length() > 0) sb.append(',');
            sb.append(s.trim());
        }
        return sb.toString();
    }

    /**
     * Предупреждение для приложений, уведомления которых обычно фильтровать не стоит.
     * null - приложение безопасное.
     */
    static String riskNote(String pkg) {
        if (pkg == null) return null;
        String q = pkg.toLowerCase(java.util.Locale.US);
        // "bank" ловит и те банки, которых нет в списке ниже: Яндекс.Банк (com.yandex.bank)
        // так и проскочил однажды к модели, хотя банковские уведомления к ней не ходят.
        if (q.contains("bank") || (q.contains("sber") && !q.contains("sbermarket")) || q.contains("tinkoff")
                || q.contains("idamob")
                || q.contains("alfa") || q.contains("vtb") || q.contains("gazprom") || q.contains("rosbank")
                || q.contains("raiffeisen") || q.contains("openbank") || q.contains("psbank") || q.contains("sovcom")
                || q.contains("mkb") || q.contains("homecredit") || q.contains("otpbank")) {
            return "банк: под фильтр попадут коды подтверждения и списания";
        }
        if (q.contains("rostel") || q.contains("gosuslugi") || q.contains("gnivts") || q.contains("nalog")) {
            return "госуслуги или налоговая: их уведомления тоже будут проверяться";
        }
        if (q.contains("oneme") || q.contains("messenger") || q.contains("whatsapp") || q.contains("telegram")
                || q.contains("viber") || q.contains("signal") || q.contains("discord")) {
            return "мессенджер: под фильтр попадут сообщения от людей";
        }
        if (q.contains("dialer") || q.contains("telecom") || q.contains("mms") || q.contains("messaging")
                || q.contains("deskclock") || q.contains("bbkclock") || q.contains("systemui")) {
            return "системное: звонки, SMS или будильник";
        }
        return null;
    }
}
