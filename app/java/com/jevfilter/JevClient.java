package com.jevfilter;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;

/** Calls the TypeSafe "System One" decision endpoint and returns the verdict for one notification. */
final class JevClient {

    private static final String ENDPOINT = "https://openrouter.ai/api/alpha/decisions";
    private static final String MODEL = "typesafe/jev-1.13";
    private static final Charset UTF8 = Charset.forName("UTF-8");
    /** Ожидание ответа по попыткам: первая короткая, дальше терпеливее. */
    private static final int[] READ_TIMEOUTS = {4000, 8000, 8000, 8000};

    static final class Verdict {
        final String choice;
        final double confidence;
        final int attempts;
        final long elapsedMs;

        Verdict(String choice, double confidence, int attempts, long elapsedMs) {
            this.choice = choice;
            this.confidence = confidence;
            this.attempts = attempts;
            this.elapsedMs = elapsedMs;
        }

        boolean shouldRemove(double threshold) {
            if (!"dismiss".equalsIgnoreCase(choice) && !"mute".equalsIgnoreCase(choice)) return false;
            return confidence >= threshold;
        }
    }

    private JevClient() {
    }

    static Verdict ask(String apiKey, Prefs.Rule rule, String pkg, String app, String channel, String title, String text)
            throws IOException {
        if (apiKey == null || apiKey.trim().length() < 20) throw new IOException("API key не задан");
        if (rule == null) throw new IOException("правила не заданы");

        String payload;
        try {
            JSONObject state = new JSONObject();
            state.put("app", nz(app));
            state.put("package", nz(pkg));
            state.put("channel", nz(channel));
            state.put("title", mask(nz(title)));
            state.put("text", mask(nz(text)));

            JSONObject criteria = new JSONObject();
            criteria.put("keep", nz(rule.keep));
            criteria.put("mute", nz(rule.mute));
            criteria.put("dismiss", nz(rule.dismiss));

            JSONObject verdict = new JSONObject();
            verdict.put("type", "choice");
            verdict.put("instructions", nz(rule.instructions));
            verdict.put("criteria", criteria);

            JSONObject questions = new JSONObject();
            questions.put("verdict", verdict);

            JSONObject body = new JSONObject();
            body.put("model", MODEL);
            body.put("state", state);
            body.put("questions", questions);
            payload = body.toString();
        } catch (Throwable t) {
            throw new IOException("не собрался запрос: " + t);
        }

        // Первая попытка с коротким ожиданием: оборванный путь молчит и не отдаёт ни байта,
        // поэтому ждать его бессмысленно. Следующие попытки терпеливее - на случай медленного,
        // но живого ответа.
        IOException last = null;
        long started = System.currentTimeMillis();
        for (int attempt = 1; attempt <= READ_TIMEOUTS.length; attempt++) {
            try {
                Verdict v = once(apiKey.trim(), payload, READ_TIMEOUTS[attempt - 1]);
                return new Verdict(v.choice, v.confidence, attempt, System.currentTimeMillis() - started);
            } catch (IOException e) {
                last = e;
                if (attempt < READ_TIMEOUTS.length) {
                    try {
                        Thread.sleep(400L);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }
        throw last == null ? new IOException("нет ответа") : last;
    }

    private static Verdict once(String apiKey, String payload, int readTimeoutMs) throws IOException {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(ENDPOINT).openConnection();
            c.setRequestMethod("POST");
            c.setConnectTimeout(4000);
            c.setReadTimeout(readTimeoutMs);
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            c.setRequestProperty("Authorization", "Bearer " + apiKey);
            byte[] out = payload.getBytes(UTF8);
            c.setFixedLengthStreamingMode(out.length);
            OutputStream os = c.getOutputStream();
            try {
                os.write(out);
            } finally {
                os.close();
            }
            int code = c.getResponseCode();
            String resp = read(code >= 400 ? c.getErrorStream() : c.getInputStream());
            if (code >= 400) throw new IOException("HTTP " + code + ": " + cut(resp, 200));
            return parse(resp);
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static Verdict parse(String resp) throws IOException {
        JSONObject v;
        try {
            JSONObject root = new JSONObject(resp);
            JSONObject answers = root.optJSONObject("answers");
            if (answers == null) throw new IOException("нет answers: " + cut(resp, 200));
            v = answers.optJSONObject("verdict");
            if (v == null) throw new IOException("нет verdict: " + cut(resp, 200));
        } catch (IOException e) {
            throw e;
        } catch (Throwable t) {
            throw new IOException("не разобрался ответ: " + cut(resp, 200));
        }
        String choice = v.optString("choice", "");
        if (choice.length() == 0) throw new IOException("пустой choice: " + cut(resp, 200));
        return new Verdict(choice, v.optDouble("confidence", 0d), 1, 0L);
    }

    private static String read(InputStream in) throws IOException {
        if (in == null) return "";
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        try {
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        } finally {
            in.close();
        }
        return new String(bos.toByteArray(), UTF8);
    }

    private static String cut(String s, int n) {
        if (s == null) return "";
        return s.length() <= n ? s : s.substring(0, n) + "...";
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    /**
     * Цифровые последовательности длиной 4-8 знаков наружу не уходят: это коды, суммы и номера
     * заказов. Коды до сюда обычно не доходят (их перехватывает StopList), но сумма в чужой
     * модели нам не нужна, а длина последовательности для решения полезна и сохраняется.
     */
    private static String mask(String s) {
        if (s == null || s.length() == 0) return "";
        StringBuilder out = new StringBuilder(s.length());
        StringBuilder run = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch >= '0' && ch <= '9') {
                run.append(ch);
                continue;
            }
            flush(out, run);
            out.append(ch);
        }
        flush(out, run);
        return out.toString();
    }

    private static void flush(StringBuilder out, StringBuilder run) {
        int n = run.length();
        if (n >= 4 && n <= 8) {
            for (int i = 0; i < n; i++) out.append('\u2022');
        } else {
            out.append(run);
        }
        run.setLength(0);
    }
}
