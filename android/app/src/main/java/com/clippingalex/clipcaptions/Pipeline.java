package com.clippingalex.clipcaptions;

import android.content.Context;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import com.clippingalex.clipcaptions.core.FaceTrack;
import com.clippingalex.clipcaptions.core.Word;

import java.io.File;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Flujo completo en el telefono: audio -> Groq -> rostros -> subtitulos -> video. */
@androidx.media3.common.util.UnstableApi
public final class Pipeline {
    public interface Listener {
        void onProgress(int pct, String msg);
        void onDone(File out, int phrases, List<String> styles);
        void onError(String msg);
    }

    public static final class VideoInfo {
        public int upW, upH;
        public double duration;
        public boolean hasAudio;
        public boolean vertical() { return upH >= upW; }
    }

    public static final double MAX_SECONDS = 360;
    public static final int MAX_SIDE = 1920;

    private final Context ctx;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Exporter exporter;
    private volatile boolean cancelled;
    private volatile float faceFraction;

    public Pipeline(Context ctx, Listener listener) {
        this.ctx = ctx.getApplicationContext();
        this.listener = listener;
        this.exporter = new Exporter(this.ctx);
    }

    public void cancel() { cancelled = true; exporter.cancel(); }

    public static VideoInfo probe(Context ctx, Uri video) throws Exception {
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(ctx, video);
            VideoInfo i = new VideoInfo();
            int w = Integer.parseInt(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH));
            int h = Integer.parseInt(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT));
            String rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION);
            int deg = rot == null ? 0 : Integer.parseInt(rot);
            boolean swap = deg == 90 || deg == 270;
            i.upW = swap ? h : w;
            i.upH = swap ? w : h;
            String d = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            i.duration = d == null ? 0 : Long.parseLong(d) / 1000.0;
            i.hasAudio = "yes".equals(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO));
            return i;
        } finally {
            try { r.release(); } catch (Exception ignored) { }
        }
    }

    /** Tamaño de salida: lado mayor <= 1920 y medidas pares. */
    public static int[] outputSize(int w, int h) {
        double f = Math.min(1.0, (double) MAX_SIDE / Math.max(w, h));
        int ow = (int) Math.round(w * f / 2.0) * 2, oh = (int) Math.round(h * f / 2.0) * 2;
        return new int[]{Math.max(2, ow), Math.max(2, oh)};
    }

    public void start(final Uri video, final String style, final String lang, final String apiKey) {
        new Thread(new Runnable() {
            public void run() { runSafe(video, style, lang, apiKey); }
        }, "clipcaptions-pipeline").start();
    }

    private void progress(final int pct, final String msg) {
        main.post(new Runnable() { public void run() { listener.onProgress(pct, msg); } });
    }

    private void runSafe(Uri video, String style, String lang, String apiKey) {
        ExecutorService faces = Executors.newSingleThreadExecutor();
        try {
            final File dir = new File(ctx.getCacheDir(), "work");
            dir.mkdirs();
            File[] old = dir.listFiles();
            if (old != null) for (File f : old) f.delete();

            progress(3, "Leyendo el video");
            final VideoInfo info = probe(ctx, video);
            if (info.duration > MAX_SECONDS)
                throw new IllegalStateException("El video es demasiado largo (máximo " + (int) (MAX_SECONDS / 60) + " minutos).");
            if (!info.hasAudio)
                throw new IllegalStateException("El video no tiene audio para transcribir.");

            final Uri v = video;
            Future<FaceTrack> faceJob = faces.submit(new Callable<FaceTrack>() {
                public FaceTrack call() throws Exception {
                    return FaceTracker.track(ctx, v, info.duration, info.upW, info.upH,
                            new FaceTracker.Progress() { public void on(float f) { faceFraction = f; } });
                }
            });

            progress(8, "Preparando el audio");
            File audio = new File(dir, "audio.m4a");
            exporter.exportAudio(video, audio);
            check();

            progress(22, "Transcribiendo con Groq");
            List<Word> words = new GroqClient().transcribe(audio, apiKey, lang);
            check();

            progress(40, "Buscando tu cara");
            FaceTrack track;
            try {
                while (!faceJob.isDone()) {
                    check();
                    progress(40 + (int) (18 * faceFraction), "Buscando tu cara");
                    Thread.sleep(300);
                }
                track = faceJob.get();
            } catch (java.util.concurrent.ExecutionException e) {
                track = new FaceTrack(1.0);          // sin rostros: los subtitulos van en zona segura
            }

            progress(60, "Diseñando los subtítulos");
            int[] os = outputSize(info.upW, info.upH);
            StringBuilder sb = new StringBuilder();
            for (Word w : words) sb.append(w.text);
            CaptionEngine engine = CaptionEngine.build(ctx.getAssets(), words, os[0], os[1], track,
                    sb.toString().hashCode(), "auto".equals(style) ? null : style);

            progress(62, "Creando el video");
            File out = new File(dir, "resultado.mp4");
            exporter.exportVideo(video, out, engine, os[0], os[1], new Exporter.Progress() {
                public void on(float f) { progress(62 + (int) (36 * f), "Creando el video"); }
            });
            check();

            TreeSet<String> used = new TreeSet<>();
            for (CaptionEngine.PhraseLayout p : engine.phrases) used.add(p.style.name);
            final int n = engine.phrases.size();
            final List<String> styles = new ArrayList<>(used);
            final File outF = out;
            main.post(new Runnable() { public void run() { listener.onDone(outF, n, styles); } });
        } catch (Throwable e) {
            final String msg = cancelled ? "Cancelado" : friendly(e);
            main.post(new Runnable() { public void run() { listener.onError(msg); } });
        } finally {
            faces.shutdownNow();
        }
    }

    private void check() throws Exception { if (cancelled) throw new Exception("Cancelado"); }

    static String friendly(Throwable e) {
        if (e instanceof UnknownHostException || e instanceof SocketTimeoutException)
            return "No hay conexión a internet para transcribir con Groq.";
        if (e instanceof GroqClient.GroqException || e instanceof IllegalStateException) return e.getMessage();
        if (e instanceof androidx.media3.transformer.ExportException)
            return "Este teléfono no pudo procesar el video (" + ((androidx.media3.transformer.ExportException) e).getErrorCodeName() + ").";
        String m = e.getMessage();
        return "Error: " + (m == null ? e.getClass().getSimpleName() : m);
    }
}
