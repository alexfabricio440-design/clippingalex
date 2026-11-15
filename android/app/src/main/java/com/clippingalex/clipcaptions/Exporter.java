package com.clippingalex.clipcaptions;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import androidx.media3.common.Effect;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.VideoFrameProcessingException;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.effect.BitmapOverlay;
import androidx.media3.effect.OverlayEffect;
import androidx.media3.effect.Presentation;
import androidx.media3.transformer.Composition;
import androidx.media3.transformer.EditedMediaItem;
import androidx.media3.transformer.Effects;
import androidx.media3.transformer.ExportException;
import androidx.media3.transformer.ExportResult;
import androidx.media3.transformer.ProgressHolder;
import androidx.media3.transformer.Transformer;

import com.google.common.collect.ImmutableList;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;

/** Usa Media3 Transformer: extrae el audio y exporta el video con subtitulos dibujados encima. */
@androidx.media3.common.util.UnstableApi
public final class Exporter {
    public interface Progress { void on(float fraction); }

    private final Context ctx;
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile Transformer current;
    private volatile boolean cancelled;

    public Exporter(Context ctx) { this.ctx = ctx.getApplicationContext(); }

    public void cancel() {
        cancelled = true;
        main.post(new Runnable() {
            public void run() { if (current != null) current.cancel(); }
        });
    }

    /** Extrae solo el audio (AAC en .m4a) para mandarlo a Groq. Bloquea el hilo que llama. */
    public void exportAudio(Uri in, File out) throws Exception {
        EditedMediaItem item = new EditedMediaItem.Builder(MediaItem.fromUri(in)).setRemoveVideo(true).build();
        run(item, out, MimeTypes.AUDIO_AAC, null, null);
    }

    /** Exporta el video final con el motor de subtitulos como capa superpuesta. */
    public void exportVideo(Uri in, File out, final CaptionEngine engine, int outW, int outH,
                            Progress progress) throws Exception {
        List<Effect> vfx = new ArrayList<>();
        vfx.add(Presentation.createForWidthAndHeight(outW, outH, Presentation.LAYOUT_SCALE_TO_FIT));
        vfx.add(new OverlayEffect(ImmutableList.of(new CaptionOverlay(engine, outW, outH))));
        EditedMediaItem item = new EditedMediaItem.Builder(MediaItem.fromUri(in))
                .setEffects(new Effects(ImmutableList.<AudioProcessor>of(), ImmutableList.copyOf(vfx)))
                .build();
        run(item, out, null, MimeTypes.VIDEO_H264, progress);
    }

    private void run(final EditedMediaItem item, final File out, final String audioMime,
                     final String videoMime, final Progress progress) throws Exception {
        if (out.exists()) out.delete();
        final CountDownLatch done = new CountDownLatch(1);
        final Exception[] error = new Exception[1];
        main.post(new Runnable() {
            public void run() {
                try {
                    Transformer.Builder b = new Transformer.Builder(ctx);
                    if (audioMime != null) b.setAudioMimeType(audioMime);
                    if (videoMime != null) b.setVideoMimeType(videoMime);
                    b.addListener(new Transformer.Listener() {
                        @Override
                        public void onCompleted(Composition c, ExportResult r) { done.countDown(); }

                        @Override
                        public void onError(Composition c, ExportResult r, ExportException e) {
                            error[0] = e;
                            done.countDown();
                        }
                    });
                    final Transformer t = b.build();
                    current = t;
                    if (cancelled) { error[0] = new Exception("Cancelado"); done.countDown(); return; }
                    t.start(item, out.getAbsolutePath());
                    if (progress != null) poll(t, progress, done);
                } catch (Exception e) {
                    error[0] = e;
                    done.countDown();
                }
            }
        });
        done.await();
        current = null;
        if (error[0] != null) throw error[0];
        if (cancelled) throw new Exception("Cancelado");
    }

    private void poll(final Transformer t, final Progress progress, final CountDownLatch done) {
        main.postDelayed(new Runnable() {
            public void run() {
                if (done.getCount() == 0) return;
                ProgressHolder h = new ProgressHolder();
                if (t.getProgress(h) == Transformer.PROGRESS_STATE_AVAILABLE) progress.on(h.progress / 100f);
                poll(t, progress, done);
            }
        }, 400);
    }

    /** Capa de imagen del tamaño del video: se redibuja solo cuando cambia el estado visual. */
    static final class CaptionOverlay extends BitmapOverlay {
        private final CaptionEngine engine;
        private final Bitmap empty, a, b;
        private final Canvas ca, cb;
        private boolean useA = true;
        private long lastKey = Long.MIN_VALUE;
        private Bitmap last;

        CaptionOverlay(CaptionEngine engine, int w, int h) {
            this.engine = engine;
            empty = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            a = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            ca = new Canvas(a);
            cb = new Canvas(b);
            last = empty;
        }

        @Override
        public Bitmap getBitmap(long presentationTimeUs) throws VideoFrameProcessingException {
            double t = presentationTimeUs / 1e6;
            long key = engine.stateKey(t);
            if (key == lastKey) return last;
            lastKey = key;
            if (key < 0) { last = empty; return last; }
            // Media3 solo vuelve a subir la textura si cambia el objeto Bitmap: alternamos dos.
            Bitmap bmp = useA ? a : b;
            Canvas cv = useA ? ca : cb;
            useA = !useA;
            bmp.eraseColor(Color.TRANSPARENT);
            engine.draw(cv, t);
            last = bmp;
            return bmp;
        }
    }
}
