package com.jevfilter;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.List;

/**
 * Защёлка отложенных уведомлений для режима «отложить до решения».
 *
 * В этом режиме оригинал снимается сразу (это обрывает и звук), а своей копии мы не показываем,
 * пока Джев думает. Значит, между снятием и вердиктом уведомления нет нигде - и если процесс
 * в этот момент убьют (нехватка памяти, обновление, падение), оно пропадёт навсегда.
 *
 * Поэтому содержимое кладётся в файл ДО снятия оригинала, а удаляется после вердикта.
 * Что осталось в файле при старте процесса - то и есть потерянные уведомления, их возвращаем.
 *
 * Копии возвращаются упрощёнными: PendingIntent и кнопки действий через файл не передать,
 * так что восстановленное уведомление не откроет приложение-источник. Это осознанная плата
 * за то, что уведомление вообще не теряется.
 */
final class Pending {

    private static final String FILE = "jev.pending";
    /** Дольше этого срока запись считается мусором и не возвращается. */
    private static final long MAX_AGE = 15L * 60L * 1000L;
    private static final int MAX = 40;

    static final class Item {
        final long at;
        final String token, pkg, app, title, text;

        Item(long at, String token, String pkg, String app, String title, String text) {
            this.at = at;
            this.token = token;
            this.pkg = pkg;
            this.app = app;
            this.title = title;
            this.text = text;
        }
    }

    private Pending() {
    }

    /**
     * Записывает уведомление в защёлку. Возвращает false, если записать не удалось: тогда
     * прятать оригинал нельзя - при сбое процесса уведомление пропало бы навсегда.
     */
    static synchronized boolean put(Context c, String token, String pkg, String app, String title, String text) {
        try {
            List<Item> all = read(c);
            drop(all, token);
            all.add(new Item(System.currentTimeMillis(), token, pkg, app, title, text));
            while (all.size() > MAX) all.remove(0);
            return write(c, all);
        } catch (Throwable t) {
            android.util.Log.e("JevFilter", "pending put: " + t);
            return false;
        }
    }

    /** Уведомление решено: из защёлки его убираем. */
    static synchronized void done(Context c, String token) {
        try {
            List<Item> all = read(c);
            if (drop(all, token)) write(c, all);
        } catch (Throwable t) {
            android.util.Log.e("JevFilter", "pending done: " + t);
        }
    }

    /** Забирает всё, что осталось, и очищает файл. Возвращает только не слишком старое. */
    static synchronized List<Item> take(Context c) {
        List<Item> out = new ArrayList<Item>();
        try {
            List<Item> all = read(c);
            if (all.isEmpty()) return out;
            write(c, new ArrayList<Item>());
            long now = System.currentTimeMillis();
            for (Item it : all) {
                if (now - it.at <= MAX_AGE) out.add(it);
            }
        } catch (Throwable t) {
            android.util.Log.e("JevFilter", "pending take: " + t);
        }
        return out;
    }

    private static boolean drop(List<Item> all, String token) {
        boolean changed = false;
        for (int i = all.size() - 1; i >= 0; i--) {
            if (all.get(i).token.equals(token)) {
                all.remove(i);
                changed = true;
            }
        }
        return changed;
    }

    private static List<Item> read(Context c) {
        List<Item> out = new ArrayList<Item>();
        File f = file(c);
        if (f == null || !f.exists()) return out;
        BufferedReader r = null;
        try {
            r = new BufferedReader(new FileReader(f));
            String line;
            while ((line = r.readLine()) != null) {
                String[] p = line.split("\t", -1);
                if (p.length < 7) continue;
                long at;
                try {
                    at = Long.parseLong(p[0]);
                } catch (Throwable t) {
                    continue;
                }
                out.add(new Item(at, unesc(p[1]), unesc(p[2]), unesc(p[3]), unesc(p[4]),
                        unesc(p[5])));
            }
        } catch (Throwable t) {
            android.util.Log.e("JevFilter", "pending read: " + t);
        } finally {
            try {
                if (r != null) r.close();
            } catch (Throwable ignored) {
            }
        }
        return out;
    }

    private static boolean write(Context c, List<Item> all) {
        File f = file(c);
        if (f == null) return false;
        File tmp = new File(f.getAbsolutePath() + ".tmp");
        FileWriter w = null;
        try {
            w = new FileWriter(tmp, false);
            for (Item it : all) {
                w.write(it.at + "\t" + esc(it.token) + "\t" + esc(it.pkg) + "\t" + esc(it.app)
                        + "\t" + esc(it.title) + "\t" + esc(it.text) + "\t\n");
            }
            w.close();
            w = null;
            if (f.exists()) f.delete();
            return tmp.renameTo(f);
        } catch (Throwable t) {
            android.util.Log.e("JevFilter", "pending write: " + t);
            return false;
        } finally {
            try {
                if (w != null) w.close();
            } catch (Throwable ignored) {
            }
        }
    }

    private static File file(Context c) {
        try {
            File dir = c.getFilesDir();
            if (dir == null) return null;
            if (!dir.exists()) dir.mkdirs();
            return new File(dir, FILE);
        } catch (Throwable t) {
            return null;
        }
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n").replace("\r", "");
    }

    private static String unesc(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '\\' && i + 1 < s.length()) {
                char nx = s.charAt(++i);
                if (nx == 't') sb.append('\t');
                else if (nx == 'n') sb.append('\n');
                else if (nx == '\\') sb.append('\\');
                else sb.append(nx);
            } else {
                sb.append(ch);
            }
        }
        return sb.toString();
    }
}
