package com.clippingalex.clipcaptions;

import com.clippingalex.clipcaptions.core.Phrases;
import com.clippingalex.clipcaptions.core.Word;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Transcripcion con Groq (Whisper) con tiempos por palabra. */
public final class GroqClient {
    public static final String DEFAULT_URL = "https://api.groq.com/openai/v1/audio/transcriptions";
    public static final String MODEL = "whisper-large-v3-turbo";
    public static final long MAX_BYTES = 24L * 1024 * 1024;

    public static final class GroqException extends IOException {
        public GroqException(String m) { super(m); }
    }

    private final String url;

    public GroqClient() { this(DEFAULT_URL); }

    public GroqClient(String url) { this.url = url; }

    /** Convierte la respuesta verbose_json en palabras limpias. */
    public static List<Word> parse(String json) throws GroqException {
        try {
            JSONObject o = new JSONObject(json);
            JSONArray arr = o.optJSONArray("words");
            if (arr == null || arr.length() == 0)
                throw new GroqException("No se detectaron palabras en el audio.");
            List<Word> words = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject w = arr.getJSONObject(i);
                String text = w.has("word") ? w.getString("word") : w.optString("text", "");
                words.add(new Word(text, w.getDouble("start"), w.getDouble("end")));
            }
            return Phrases.clean(words);
        } catch (org.json.JSONException e) {
            throw new GroqException("Respuesta inesperada de Groq.");
        }
    }

    public List<Word> transcribe(File audio, String apiKey, String language) throws IOException {
        if (apiKey == null || apiKey.trim().isEmpty())
            throw new GroqException("Falta tu clave de Groq. Escríbela en la app.");
        if (audio.length() > MAX_BYTES)
            throw new GroqException("El audio es demasiado grande para Groq (máx. 24 MB). Usa un clip más corto.");
        String last = "";
        for (int attempt = 0; attempt < 3; attempt++) {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            try {
                String boundary = "----cc" + System.nanoTime();
                c.setRequestMethod("POST");
                c.setDoOutput(true);
                c.setConnectTimeout(20000);
                c.setReadTimeout(180000);
                c.setChunkedStreamingMode(64 * 1024);
                c.setRequestProperty("Authorization", "Bearer " + apiKey.trim());
                c.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
                try (DataOutputStream out = new DataOutputStream(c.getOutputStream())) {
                    field(out, boundary, "model", MODEL);
                    field(out, boundary, "response_format", "verbose_json");
                    field(out, boundary, "timestamp_granularities[]", "word");
                    field(out, boundary, "temperature", "0");
                    if (language != null && !language.isEmpty()) field(out, boundary, "language", language);
                    out.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\""
                            + audio.getName() + "\"\r\nContent-Type: application/octet-stream\r\n\r\n")
                            .getBytes(StandardCharsets.UTF_8));
                    try (InputStream in = new FileInputStream(audio)) {
                        byte[] buf = new byte[64 * 1024];
                        int n;
                        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                    }
                    out.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
                }
                int code = c.getResponseCode();
                if (code == 200) return parse(read(c.getInputStream()));
                last = read(c.getErrorStream());
                if (code == 401) throw new GroqException("La clave de Groq no es válida. Revísala.");
                if (code == 429 || code >= 500) {
                    long wait = 1500L * (attempt + 1);
                    String ra = c.getHeaderField("retry-after");
                    if (ra != null) try { wait = Math.min(15000, (long) (Double.parseDouble(ra) * 1000)); } catch (Exception ignored) { }
                    try { Thread.sleep(wait); } catch (InterruptedException ie) { throw new GroqException("Cancelado"); }
                    last = "HTTP " + code;
                    continue;
                }
                throw new GroqException("Groq respondió " + code + ": " + shorten(last));
            } finally {
                c.disconnect();
            }
        }
        throw new GroqException("Groq no responde ahora (" + shorten(last) + "). Intenta de nuevo.");
    }

    private static String shorten(String s) { return s == null ? "" : (s.length() > 200 ? s.substring(0, 200) : s); }

    private static void field(DataOutputStream out, String b, String name, String value) throws IOException {
        out.write(("--" + b + "\r\nContent-Disposition: form-data; name=\"" + name + "\"\r\n\r\n" + value + "\r\n")
                .getBytes(StandardCharsets.UTF_8));
    }

    private static String read(InputStream in) throws IOException {
        if (in == null) return "";
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) sb.append(line).append('\n');
        }
        return sb.toString();
    }
}
