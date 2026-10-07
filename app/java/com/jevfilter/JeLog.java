package com.jevfilter;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Журнал решений: кольцо в памяти (для экрана) плюс текстовый файл (для adb).
 *
 * Каждая запись хранится и сырой строкой, и разобранной на части: экран журнала показывает
 * иконку приложения, вердикт и текст уведомления, а не сырую техническую строку.
 */
final class JeLog {

    /** Джев сказал снять. */
    static final int REMOVE = 1;
    /** Джев сказал оставить. */
    static final int LEAVE = 2;
    /** Ничего не делали: постоянное, групповое, устаревшее, повтор. */
    static final int SKIP = 3;
    /** Приложения нет в чёрном списке (пишется, когда включён показ всех уведомлений). */
    static final int MISS = 4;
    /** Служебная строка самого приложения. */
    static final int INFO = 0;

    /** Одна запись журнала, разобранная на части. */
    static final class Entry {
        final long at;
        final int kind;
        final String pkg;   // может быть null
        final String note;  // "REMOVE dismiss 1.00 (тихо)" — то, что до [пакета]
        final String body;  // "Wildberries :: Скидка 50%" — то, что после [пакета]
        final String raw;   // строка как она лежит в файле, без отметки времени

        Entry(long at, int kind, String pkg, String note, String body, String raw) {
            this.at = at;
            this.kind = kind;
            this.pkg = pkg;
            this.note = note;
            this.body = body;
            this.raw = raw;
        }
    }

    private static final int MAX = 1500;
    private static final ArrayDeque<Entry> BUF = new ArrayDeque<Entry>();
    private static final SimpleDateFormat FMT = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.US);
    private static final SimpleDateFormat FULL = new SimpleDateFormat("yyyy MM-dd HH:mm:ss", Locale.US);
    private static String year = null;

    /** Растёт на каждой записи: экран по нему понимает, что список надо перечитать. */
    private static long version = 0L;

    /** Состояние троттлинга: ключ события -> {когда писали в последний раз, сколько подавлено}. */
    private static final Map<String, long[]> THROTTLE = new HashMap<String, long[]>();
    private static final int THROTTLE_MAX = 256;
    private static final long THROTTLE_TTL = 30L * 60L * 1000L;

    private JeLog() {
    }

    static synchronized void add(Context c, String line) {
        long now = System.currentTimeMillis();
        Entry e = parse(now, line);
        BUF.addFirst(e);
        while (BUF.size() > MAX) BUF.removeLast();
        version++;
        android.util.Log.i("JevFilter", line);
        if (c != null) write(c, FMT.format(new Date(now)) + "  " + line);
    }

    /** Записи, новые сверху. onlyDecisions — только то, что Джев реально решил. */
    static synchronized List<Entry> entries(boolean onlyDecisions) {
        ArrayList<Entry> out = new ArrayList<Entry>();
        for (Entry e : BUF) {
            if (!onlyDecisions || e.kind == REMOVE || e.kind == LEAVE) out.add(e);
        }
        return out;
    }

    static synchronized int size() {
        return BUF.size();
    }

    /** Сколько записей Джева всего (для строки «N записей, из них решений M»). */
    static synchronized int decisions() {
        int n = 0;
        for (Entry e : BUF) if (e.kind == REMOVE || e.kind == LEAVE) n++;
        return n;
    }

    static synchronized long version() {
        return version;
    }

    /** Когда Джев в последний раз что-то решил — для строки на главном экране. */
    static synchronized long lastDecisionAt() {
        for (Entry e : BUF) if (e.kind == REMOVE || e.kind == LEAVE) return e.at;
        return 0L;
    }

    static synchronized String dump() {
        StringBuilder sb = new StringBuilder();
        for (Entry e : BUF) sb.append(FMT.format(new Date(e.at))).append("  ").append(e.raw).append('\n');
        return sb.toString();
    }

    static synchronized void clear() {
        BUF.clear();
        THROTTLE.clear();
        version++;
    }

    /**
     * Пишет строку не чаще одной на throttleKey за windowMs.
     *
     * Нужно для событий, после которых ничего не произошло: службы вроде VPN перевыкладывают
     * своё постоянное уведомление раз в секунду, и без этого кольцо вытесняло настоящие решения
     * за считанные минуты. Первое событие пишется сразу, повторы считаются молча, а в следующей
     * записи видно их число - факт не теряется, шум уходит.
     * Решения (REMOVE/LEAVE) через это не проходят никогда.
     */
    static synchronized void addThrottled(Context c, String throttleKey, long windowMs, String line) {
        long now = System.currentTimeMillis();
        long[] st = THROTTLE.get(throttleKey);
        if (st == null) {
            if (THROTTLE.size() > THROTTLE_MAX) prune(now);
            THROTTLE.put(throttleKey, new long[]{now, 0L});
            add(c, line);
            return;
        }
        if (now - st[0] >= windowMs) {
            long n = st[1];
            st[0] = now;
            st[1] = 0L;
            add(c, n > 0L ? line + "  (+" + n + " таких же за " + span(windowMs) + ")" : line);
            return;
        }
        st[1]++;
    }

    /** Чтобы карта не росла на телефоне, который живёт неделями без перезапуска. */
    private static void prune(long now) {
        Iterator<Map.Entry<String, long[]>> it = THROTTLE.entrySet().iterator();
        while (it.hasNext()) {
            if (now - it.next().getValue()[0] > THROTTLE_TTL) it.remove();
        }
    }

    private static String span(long ms) {
        long min = ms / 60_000L;
        return min >= 1L ? min + " мин" : (ms / 1000L) + " с";
    }

    /** После перезапуска процесса подтягиваем хвост журнала из файла, иначе экран выглядит пустым. */
    static synchronized void prime(Context c) {
        if (!BUF.isEmpty() || c == null) return;
        BufferedReader r = null;
        try {
            File f = new File(c.getFilesDir(), "jev.log");
            if (!f.exists()) return;
            year = new SimpleDateFormat("yyyy", Locale.US).format(new Date());
            r = new BufferedReader(new FileReader(f));
            ArrayDeque<Entry> tmp = new ArrayDeque<Entry>();
            String line;
            while ((line = r.readLine()) != null) {
                // формат файла: "MM-dd HH:mm:ss" (14 знаков) + два пробела + строка журнала
                int sep = line.indexOf("  ");
                int cut = sep > 0 ? sep : 14;
                if (line.length() < cut + 2) continue;
                long at = parseStamp(line.substring(0, cut));
                Entry e = parse(at, line.substring(cut + 2));
                tmp.addFirst(e);
                while (tmp.size() > MAX) tmp.removeLast();
            }
            BUF.addAll(tmp);
            version++;
        } catch (Throwable ignored) {
        } finally {
            try {
                if (r != null) r.close();
            } catch (Throwable ignored) {
            }
        }
    }

    /** "MM-dd HH:mm:ss" без года: подставляем текущий, а хвост прошлого года уводим назад. */
    private static long parseStamp(String stamp) {
        try {
            String y = year != null ? year
                    : new SimpleDateFormat("yyyy", Locale.US).format(new Date());
            long t = FULL.parse(y + " " + stamp).getTime();
            if (t - System.currentTimeMillis() > 86400000L) t -= 31536000000L;
            return t;
        } catch (Throwable ignored) {
            return System.currentTimeMillis();
        }
    }

    /** Разбирает нашу же строку журнала: "REMOVE dismiss 1.00 [pkg] заголовок :: текст". */
    private static Entry parse(long at, String line) {
        String msg = line == null ? "" : line;
        int kind = INFO;
        if (msg.startsWith("REMOVE")) kind = REMOVE;
        else if (msg.startsWith("LEAVE")) kind = LEAVE;
        else if (msg.startsWith("SKIP")) kind = SKIP;
        else if (msg.startsWith("MISS")) kind = MISS;

        String pkg = null;
        String note = msg.trim();
        String body = "";
        int a = msg.indexOf('[');
        int b = a < 0 ? -1 : msg.indexOf(']', a + 1);
        if (b > a + 1) {
            pkg = msg.substring(a + 1, b);
            note = msg.substring(0, a).trim();
            body = msg.substring(b + 1).trim();
        }
        return new Entry(at, kind, pkg, note, body, msg);
    }

    private static void write(Context c, String s) {
        try {
            File dir = c.getFilesDir();
            if (dir == null) return;
            if (!dir.exists()) dir.mkdirs();
            File f = new File(dir, "jev.log");
            if (f.exists() && f.length() > 2L * 1024L * 1024L) {
                File old = new File(dir, "jev.old.log");
                if (old.exists()) old.delete();
                f.renameTo(old);
            }
            FileWriter w = new FileWriter(f, true);
            try {
                w.write(s + "\n");
            } finally {
                w.close();
            }
        } catch (Throwable t) {
            android.util.Log.e("JevFilter", "log write: " + t);
        }
    }
}
