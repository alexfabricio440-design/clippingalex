package com.clippingalex.clipcaptions;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.Intent;
import android.os.ParcelFileDescriptor;
import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.util.Log;

import androidx.test.platform.app.InstrumentationRegistry;

import com.clippingalex.clipcaptions.core.Box;
import com.clippingalex.clipcaptions.core.FaceTrack;
import com.clippingalex.clipcaptions.core.Word;

import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/** Prueba el flujo real (sin Groq) en un emulador: rostros, audio, subtitulos y exportacion. */
@androidx.media3.common.util.UnstableApi
public class PipelineTest {
    static final String TXT = "Esto es lo que nadie te cuenta sobre ganar dinero en internet. Primero tienes que "
            + "entender algo muy importante: el 90% de la gente se rinde antes de empezar! Pero tu "
            + "no vas a ser uno de ellos, porque hoy vas a aprender el sistema completo.";

    private final Context app = InstrumentationRegistry.getInstrumentation().getTargetContext();
    private final Context test = InstrumentationRegistry.getInstrumentation().getContext();

    private static List<Word> words(double maxT) {
        List<Word> ws = new ArrayList<>();
        double t = 0.2;
        for (String w : TXT.split(" ")) {
            double d = 0.17 + 0.025 * w.length();
            if (t + d > maxT) break;
            ws.add(new Word(w, t, t + d));
            t += d + 0.03;
        }
        return ws;
    }

    private Uri asset(String name) throws Exception {
        File f = new File(app.getCacheDir(), name);
        try (InputStream in = test.getAssets().open(name); FileOutputStream out = new FileOutputStream(f)) {
            byte[] b = new byte[65536]; int n;
            while ((n = in.read(b)) > 0) out.write(b, 0, n);
        }
        return Uri.fromFile(f);
    }

    private File previews() {
        File d = new File(app.getExternalFilesDir(null), "previews");
        d.mkdirs();
        return d;
    }

    /** Gradle desinstala la app al terminar y borra sus archivos: copiamos las imagenes a /sdcard/Download. */
    private void exportPreviews() throws Exception {
        String cmd = "mkdir -p /sdcard/Download/ccprev && cp " + previews().getAbsolutePath() + "/*.png /sdcard/Download/ccprev/";
        ParcelFileDescriptor pfd = InstrumentationRegistry.getInstrumentation().getUiAutomation().executeShellCommand(cmd);
        try (InputStream in = new ParcelFileDescriptor.AutoCloseInputStream(pfd)) { while (in.read() >= 0) { /* espera */ } }
    }

    @Test public void uiScreenshot() throws Exception {
        Intent i = new Intent(app, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        InstrumentationRegistry.getInstrumentation().startActivitySync(i);
        Thread.sleep(2000);
        Bitmap shot = InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
        assertTrue(shot != null && shot.getWidth() > 100);
        savePng(shot, new File(previews(), "ui_home.png"));
        exportPreviews();
    }

    private static void savePng(Bitmap b, File f) throws Exception {
        try (FileOutputStream o = new FileOutputStream(f)) { b.compress(Bitmap.CompressFormat.PNG, 100, o); }
    }

    @Test public void faceTrackingFindsTheFace() throws Exception {
        Uri v = asset("test_face.mp4");
        Pipeline.VideoInfo info = Pipeline.probe(app, v);
        assertEquals(540, info.upW); assertEquals(960, info.upH);
        assertTrue(info.hasAudio);
        FaceTrack t = FaceTracker.track(app, v, info.duration, info.upW, info.upH, null);
        int with = 0;
        for (FaceTrack.Sample s : t.samples) if (!s.boxes.isEmpty()) with++;
        Log.i("CCTEST", "rostros en " + with + "/" + t.samples.size() + " muestras");
        assertTrue("debe detectar la cara en casi todas las muestras", with >= t.samples.size() * 0.8);
        Box b = t.samples.get(0).boxes.get(0);
        assertTrue(b.x + b.w / 2 > 0.2 && b.x + b.w / 2 < 0.8 && b.y + b.h / 2 < 0.5);
    }

    private void runFull(String asset, String tag, int expW, int expH) throws Exception {
        Uri v = asset(asset);
        Pipeline.VideoInfo info = Pipeline.probe(app, v);
        assertEquals(expW, info.upW); assertEquals(expH, info.upH);
        FaceTrack track = FaceTracker.track(app, v, info.duration, info.upW, info.upH, null);
        int[] os = Pipeline.outputSize(info.upW, info.upH);
        CaptionEngine engine = CaptionEngine.build(app.getAssets(), words(info.duration - 0.3), os[0], os[1], track, 7, null);
        assertTrue(engine.phrases.size() >= 4);

        Exporter ex = new Exporter(app);
        File audio = new File(app.getCacheDir(), tag + "_audio.m4a");
        ex.exportAudio(v, audio);
        assertTrue("audio extraido", audio.length() > 1000);

        File out = new File(app.getCacheDir(), tag + "_out.mp4");
        long t0 = System.currentTimeMillis();
        ex.exportVideo(v, out, engine, os[0], os[1], null);
        Log.i("CCTEST", tag + " exportado en " + (System.currentTimeMillis() - t0) + " ms");
        assertTrue(out.length() > 10000);

        MediaMetadataRetriever r = new MediaMetadataRetriever();
        r.setDataSource(out.getAbsolutePath());
        int cw = Integer.parseInt(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH));
        int ch = Integer.parseInt(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT));
        String rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION);
        int deg = rot == null ? 0 : Integer.parseInt(rot);
        Log.i("CCTEST", tag + " salida codificada " + cw + "x" + ch + " rotacion " + deg);
        boolean swap = deg == 90 || deg == 270;     // Media3 puede guardar vertical como horizontal + rotacion
        assertEquals(os[0], swap ? ch : cw);
        assertEquals(os[1], swap ? cw : ch);
        double dur = Long.parseLong(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)) / 1000.0;
        assertTrue("duracion " + dur, Math.abs(dur - info.duration) < 0.6);

        MediaMetadataRetriever src = new MediaMetadataRetriever();
        src.setDataSource(app, v);
        int drawn = 0;
        for (CaptionEngine.PhraseLayout p : engine.phrases) {
            double t = (p.start + p.end) / 2;
            if (t >= info.duration - 0.2) continue;
            Bitmap o = r.getFrameAtTime((long) (t * 1e6), MediaMetadataRetriever.OPTION_CLOSEST);
            Bitmap s = src.getFrameAtTime((long) (t * 1e6), MediaMetadataRetriever.OPTION_CLOSEST);
            if (o == null || s == null || o.getWidth() != s.getWidth()) continue;
            savePng(o, new File(previews(), tag + "_" + (drawn + 1) + "_" + p.style.name + ".png"));
            // los subtitulos nunca deben pintar la zona de la cabeza
            int strongInHead = 0, headPx = 0, strongAll = 0;
            for (int y = 0; y < o.getHeight(); y += 2) for (int x = 0; x < o.getWidth(); x += 2) {
                int a = o.getPixel(x, y), c = s.getPixel(x, y);
                int d = Math.abs(((a >> 16) & 255) - ((c >> 16) & 255)) + Math.abs(((a >> 8) & 255) - ((c >> 8) & 255)) + Math.abs((a & 255) - (c & 255));
                boolean strong = d > 90;
                if (strong) strongAll++;
                double nx = x / (double) o.getWidth(), ny = y / (double) o.getHeight();
                for (Box h : p.heads) if (nx >= h.x && nx <= h.x2() && ny >= h.y && ny <= h.y2()) { headPx++; if (strong) strongInHead++; break; }
            }
            Log.i("CCTEST", tag + " frase '" + p.text() + "' " + p.style.name + " " + p.zone + " pixelesFuertes=" + strongAll + " enCabeza=" + strongInHead + "/" + headPx);
            if (strongAll > 400) drawn++;                       // se ven subtitulos en este cuadro
            assertTrue("subtitulo sobre la cabeza en '" + p.text() + "' (" + strongInHead + ")", strongInHead <= Math.max(5, headPx * 0.002));
        }
        exportPreviews();
        assertTrue("deben verse subtitulos en el video (cuadros con texto: " + drawn + ")", drawn >= 3);
    }

    @Test public void fullPipelineVertical() throws Exception { runFull("test_face.mp4", "vert", 540, 960); }

    @Test public void fullPipelinePhoneRotatedVideo() throws Exception { runFull("test_rot.mp4", "rot", 540, 960); }
}
