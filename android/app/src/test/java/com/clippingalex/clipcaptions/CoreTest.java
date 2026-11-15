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

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
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

    /** Servidor HTTP minimo para probar el cliente (Android no trae com.sun.net.httpserver). */
    static final class FakeServer implements Runnable {
        final ServerSocket ss;
        volatile String auth = "", body = "";
        final AtomicInteger okCalls = new AtomicInteger();

        FakeServer() throws Exception { ss = new ServerSocket(0, 5, java.net.InetAddress.getByName("127.0.0.1")); }

        int port() { return ss.getLocalPort(); }

        static String line(InputStream in) throws Exception {
            StringBuilder sb = new StringBuilder();
            int c;
            while ((c = in.read()) >= 0) { if (c == '\n') break; if (c != '\r') sb.append((char) c); }
            return sb.toString();
        }

        public void run() {
            while (!ss.isClosed()) {
                try (Socket s = ss.accept()) {
                    InputStream in = s.getInputStream();
                    String req = line(in);
                    boolean chunked = false;
                    int len = 0;
                    String h;
                    while (!(h = line(in)).isEmpty()) {
                        String l = h.toLowerCase();
                        if (l.startsWith("authorization:")) auth = h.substring(14).trim();
                        if (l.startsWith("transfer-encoding:") && l.contains("chunked")) chunked = true;
                        if (l.startsWith("content-length:")) len = Integer.parseInt(h.substring(15).trim());
                    }
                    ByteArrayOutputStream bo = new ByteArrayOutputStream();
                    if (chunked) {
                        while (true) {
                            int n = Integer.parseInt(line(in).trim(), 16);
                            if (n == 0) { line(in); break; }
                            byte[] b = new byte[n]; int got = 0;
                            while (got < n) got += in.read(b, got, n - got);
                            bo.write(b); line(in);
                        }
                    } else { byte[] b = new byte[len]; int got = 0; while (got < len) got += in.read(b, got, len - got); bo.write(b); }
                    String path = req.split(" ")[1];
                    String resp;
                    if (path.equals("/bad")) resp = "HTTP/1.1 401 Unauthorized\r\nContent-Length: 2\r\nConnection: close\r\n\r\n{}";
                    else {
                        body = bo.toString("ISO-8859-1");
                        if (okCalls.incrementAndGet() == 1) resp = "HTTP/1.1 429 Too Many\r\nretry-after: 0\r\nContent-Length: 2\r\nConnection: close\r\n\r\n{}";
                        else {
                            String j = "{\"words\":[{\"word\":\"Hola\",\"start\":0.1,\"end\":0.5}]}";
                            resp = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: " + j.length() + "\r\nConnection: close\r\n\r\n" + j;
                        }
                    }
                    OutputStream out = s.getOutputStream();
                    out.write(resp.getBytes("ISO-8859-1")); out.flush();
                } catch (Exception e) { /* socket cerrado al terminar */ }
            }
        }
    }

    @Test public void groqUploadRetryAndErrors() throws Exception {
        FakeServer srv = new FakeServer();
        new Thread(srv).start();
        try {
            File audio = File.createTempFile("a", ".m4a");
            Files.write(audio.toPath(), new byte[2000]);
            String base = "http://127.0.0.1:" + srv.port();
            List<Word> w = new GroqClient(base + "/ok").transcribe(audio, "gsk_test", "es");
            assertEquals(1, w.size());
            assertEquals(2, srv.okCalls.get());                           // reintento tras 429
            assertEquals("Bearer gsk_test", srv.auth);
            assertTrue(srv.body.contains("name=\"model\"") && srv.body.contains("whisper-large-v3-turbo"));
            assertTrue(srv.body.contains("timestamp_granularities[]") && srv.body.contains("name=\"language\""));
            assertTrue(srv.body.contains("filename=\"" + audio.getName() + "\""));
            try { new GroqClient(base + "/bad").transcribe(audio, "x", null); fail(); }
            catch (GroqClient.GroqException e) { assertTrue(e.getMessage().contains("clave")); }
            try { new GroqClient(base + "/ok").transcribe(audio, "", null); fail(); }
            catch (GroqClient.GroqException e) { assertTrue(e.getMessage().contains("clave")); }
        } finally { srv.ss.close(); }
    }
}
