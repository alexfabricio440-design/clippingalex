package com.clippingalex.clipcaptions;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.clippingalex.clipcaptions.core.Anchors;
import com.clippingalex.clipcaptions.core.Box;
import com.clippingalex.clipcaptions.core.FaceTrack;
import com.clippingalex.clipcaptions.core.Phrases;
import com.clippingalex.clipcaptions.core.Word;
import com.sun.net.httpserver.HttpServer;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

public class CoreTest {
    private static List<Word> sample() {
        return new ArrayList<>(Arrays.asList(
                new Word("Hola", 0.1, 0.5), new Word("mundo.", 0.5, 1.0),
                new Word(" ", 1.0, 1.1), new Word("otra", 2.5, 2.9)));
    }

    @Test public void cleanDropsEmptyAndFixesOverlap() {
        List<Word> w = Phrases.clean(Arrays.asList(new Word("a", 0, 1.0), new Word("b", 0.8, 1.5), new Word("  ", 2, 3)));
        assertEquals(2, w.size());
        assertTrue(w.get(0).end <= w.get(1).start);
    }

    @Test public void groupRules() {
        List<List<Word>> g = Phrases.group(Phrases.clean(sample()));
        assertEquals(2, g.size());
        assertEquals("Hola", g.get(0).get(0).text);
        assertEquals("otra", g.get(1).get(0).text);
        List<Word> longList = new ArrayList<>();
        for (int i = 0; i < 10; i++) longList.add(new Word("p" + i, i * 0.3, i * 0.3 + 0.25));
        for (List<Word> p : Phrases.group(longList)) assertTrue(p.size() <= 3);
    }

    @Test public void boxGeometry() {
        Box a = new Box(0.1, 0.1, 0.2, 0.2), b = new Box(0.25, 0.25, 0.2, 0.2);
        assertTrue(a.intersects(b));
        assertEquals(0.0025, a.overlapArea(b), 1e-9);
        assertFalse(a.intersects(new Box(0.5, 0.5, 0.1, 0.1)));
        Box h = new Box(0.4, 0.4, 0.2, 0.2).head();
        assertTrue(h.x < 0.4 && h.y < 0.4 && h.y2() > 0.6);
    }

    @Test public void faceHoldAndUnion() {
        FaceTrack t = new FaceTrack(1.0);
        t.add(0.0, Arrays.asList(new Box(.4, .3, .2, .1)));
        t.add(0.25, new ArrayList<Box>());
        t.add(3.0, new ArrayList<Box>());
        assertFalse(t.facesAt(0.5).isEmpty());
        assertTrue(t.facesAt(2.5).isEmpty());
        FaceTrack m = new FaceTrack(1.0);
        m.add(0.0, Arrays.asList(new Box(.2, .3, .2, .1)));
        m.add(1.0, Arrays.asList(new Box(.6, .3, .2, .1)));
        List<Box> r = m.headsBetween(0, 1, 0.25);
        for (double cx : new double[]{0.3, 0.7}) {
            boolean covered = false;
            for (Box z : r) if (z.x < cx && cx < z.x2()) covered = true;
            assertTrue(covered);
        }
    }

    @Test public void anchorsNeverTouchHead() {
        List<Box> heads = Arrays.asList(new Box(0.30, 0.20, 0.35, 0.30));
        for (boolean vertical : new boolean[]{true, false}) {
            for (double[] sz : new double[][]{{0.5, 0.07}, {0.8, 0.12}, {0.3, 0.05}}) {
                List<Anchors.Anchor> an = Anchors.free(heads, sz[0], sz[1], vertical);
                assertFalse(an.isEmpty());
                Box safe = vertical ? Anchors.SAFE_VERTICAL : Anchors.SAFE_HORIZONTAL;
                for (Anchors.Anchor a : an) {
                    Box blk = new Box(a.cx - sz[0] / 2, a.cy - sz[1] / 2, sz[0], sz[1]);
                    if (a.overlap == 0) assertFalse(blk.intersects(heads.get(0)));
                    assertTrue(blk.x >= safe.x - 1e-6 && blk.x2() <= safe.x2() + 1e-6);
                }
            }
        }
    }

    @Test public void anchorsHaveVarietyAndHugeFaceFallback() {
        Set<String> zones = new HashSet<>();
        for (Anchors.Anchor a : Anchors.free(Arrays.asList(new Box(0.30, 0.35, 0.40, 0.25)), 0.5, 0.07, true)) zones.add(a.zone);
        assertTrue(zones.size() >= 3);
        List<Anchors.Anchor> big = Anchors.free(Arrays.asList(new Box(0, 0, 1, 1)), 0.5, 0.1, true);
        assertFalse(big.isEmpty());
        assertTrue(big.get(0).overlap > 0);
    }

    @Test public void outputSizeIsEvenAndCapped() {
        assertEquals(1080, Pipeline.outputSize(1080, 1920)[0]);
        int[] s = Pipeline.outputSize(2160, 3840);
        assertEquals(1080, s[0]); assertEquals(1920, s[1]);
        int[] odd = Pipeline.outputSize(1079, 1919);
        assertTrue(odd[0] % 2 == 0 && odd[1] % 2 == 0);
        int[] h = Pipeline.outputSize(3840, 2160);
        assertEquals(1920, h[0]); assertEquals(1080, h[1]);
    }

    // ---------------- Groq contra un servidor falso local ----------------

    private static String readAll(InputStream in) throws Exception {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        byte[] b = new byte[8192]; int n;
        while ((n = in.read(b)) > 0) o.write(b, 0, n);
        return o.toString("ISO-8859-1");
    }

    @Test public void parseWords() throws Exception {
        List<Word> w = GroqClient.parse("{\"text\":\"x\",\"words\":[{\"word\":\"Hola\",\"start\":0.1,\"end\":0.5},{\"word\":\" \",\"start\":0.5,\"end\":0.6}]}");
        assertEquals(1, w.size());
        try { GroqClient.parse("{\"text\":\"x\"}"); fail(); } catch (GroqClient.GroqException e) { assertTrue(e.getMessage().contains("palabras")); }
    }

    @Test public void groqUploadRetryAndErrors() throws Exception {
        final AtomicInteger calls = new AtomicInteger();
        final String[] seen = new String[2];
        HttpServer srv = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        srv.createContext("/ok", ex -> {
            seen[0] = ex.getRequestHeaders().getFirst("Authorization");
            seen[1] = readAll(ex.getRequestBody());
            byte[] resp;
            int code;
            if (calls.incrementAndGet() == 1) { ex.getResponseHeaders().add("retry-after", "0"); code = 429; resp = "{}".getBytes(); }
            else { code = 200; resp = "{\"words\":[{\"word\":\"Hola\",\"start\":0.1,\"end\":0.5}]}".getBytes(); }
            ex.sendResponseHeaders(code, resp.length);
            ex.getResponseBody().write(resp); ex.close();
        });
        srv.createContext("/bad", ex -> { byte[] r = "{}".getBytes(); ex.sendResponseHeaders(401, r.length); ex.getResponseBody().write(r); ex.close(); });
        srv.start();
        try {
            File audio = File.createTempFile("a", ".m4a");
            Files.write(audio.toPath(), new byte[2000]);
            String base = "http://127.0.0.1:" + srv.getAddress().getPort();
            List<Word> w = new GroqClient(base + "/ok").transcribe(audio, "gsk_test", "es");
            assertEquals(1, w.size());
            assertEquals(2, calls.get());                                 // reintento tras 429
            assertEquals("Bearer gsk_test", seen[0]);
            assertTrue(seen[1].contains("name=\"model\"") && seen[1].contains("whisper-large-v3-turbo"));
            assertTrue(seen[1].contains("timestamp_granularities[]") && seen[1].contains("name=\"language\""));
            assertTrue(seen[1].contains("filename=\"" + audio.getName() + "\""));
            try { new GroqClient(base + "/bad").transcribe(audio, "x", null); fail(); }
            catch (GroqClient.GroqException e) { assertTrue(e.getMessage().contains("clave")); }
            try { new GroqClient(base + "/ok").transcribe(audio, "", null); fail(); }
            catch (GroqClient.GroqException e) { assertTrue(e.getMessage().contains("clave")); }
        } finally { srv.stop(0); }
    }
}
