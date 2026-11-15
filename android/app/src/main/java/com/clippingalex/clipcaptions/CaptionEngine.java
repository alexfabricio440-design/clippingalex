package com.clippingalex.clipcaptions;

import android.content.res.AssetManager;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;

import com.clippingalex.clipcaptions.core.Anchors;
import com.clippingalex.clipcaptions.core.Box;
import com.clippingalex.clipcaptions.core.FaceTrack;
import com.clippingalex.clipcaptions.core.Phrases;
import com.clippingalex.clipcaptions.core.StyleDef;
import com.clippingalex.clipcaptions.core.Word;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/**
 * Motor de subtitulos dinamicos: decide estilo, color, posicion (sin tapar la cara)
 * y dibuja cada cuadro con animaciones sobre un Canvas.
 */
public final class CaptionEngine {

    static final class WAttr {
        boolean italic;
        float scale = 1f;
        Integer color;
    }

    /** Una frase ya diseñada. Visible para pruebas. */
    public static final class PhraseLayout {
        public double start, end;
        public StyleDef style;
        public int color;
        public String anim, zone;
        public double overlap, bw, bh, nx, ny;   // nx, ny: centro normalizado
        public List<Box> heads;
        public List<Word> words;
        float size, rot, cx, cy, lineH;
        String[] texts;
        WAttr[] attrs;
        int[][] lines;
        float[] wordCx, lineBase;
        public String text() {
            StringBuilder sb = new StringBuilder();
            for (String t : texts) sb.append(sb.length() == 0 ? "" : " ").append(t);
            return sb.toString();
        }
    }

    public final int width, height;
    public final List<PhraseLayout> phrases = new ArrayList<>();
    private final AssetManager assets;
    private final Map<String, Typeface> faces = new HashMap<>();
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    private final Paint measure = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);

    private CaptionEngine(AssetManager assets, int w, int h) {
        this.assets = assets; this.width = w; this.height = h;
        fill.setStyle(Paint.Style.FILL); fill.setTextAlign(Paint.Align.CENTER);
        stroke.setStyle(Paint.Style.STROKE); stroke.setTextAlign(Paint.Align.CENTER);
        stroke.setStrokeJoin(Paint.Join.ROUND); stroke.setStrokeCap(Paint.Cap.ROUND);
    }

    private Typeface face(String file) {
        Typeface t = faces.get(file);
        if (t == null) {
            try { t = Typeface.createFromAsset(assets, file); }
            catch (Exception e) { t = Typeface.DEFAULT_BOLD; }
            faces.put(file, t);
        }
        return t;
    }

    // ------------------------------------------------------------------ diseño

    public static CaptionEngine build(AssetManager am, List<Word> words, int w, int h,
                                      FaceTrack track, long seed, String styleName) {
        CaptionEngine e = new CaptionEngine(am, w, h);
        e.layoutAll(words, track, seed, styleName);
        return e;
    }

    private static boolean isKey(String w) {
        int alnum = 0; boolean digit = false;
        for (int i = 0; i < w.length(); i++) {
            char c = w.charAt(i);
            if (Character.isLetterOrDigit(c)) alnum++;
            if (Character.isDigit(c)) digit = true;
        }
        return alnum >= 7 || digit || w.endsWith("!") || w.endsWith("?");
    }

    private static <T> T pick(List<T> l, Random r) { return l.get(r.nextInt(l.size())); }

    private void layoutAll(List<Word> words, FaceTrack track, long seed, String styleName) {
        boolean vertical = height >= width;
        Random rng = new Random(seed);
        List<List<Word>> groups = Phrases.group(words);
        List<String> names = new ArrayList<>(StyleDef.ALL.keySet());
        List<String> lastStyles = new ArrayList<>();
        List<Integer> lastColors = new ArrayList<>();
        List<String> lastZones = new ArrayList<>();
        double prevEnd = 0;
        double[] lastPos = null;
        for (int pi = 0; pi < groups.size(); pi++) {
            List<Word> ph = groups.get(pi);
            double nxt = pi + 1 < groups.size() ? groups.get(pi + 1).get(0).start - 0.04 : 1e9;
            double start = Math.max(ph.get(0).start - 0.04, prevEnd);
            double end = Math.min(ph.get(ph.size() - 1).end + 0.14, nxt);
            if (end - start < 0.2) end = Math.min(start + 0.2, Math.max(nxt, start + 0.05));
            prevEnd = end;

            StyleDef st;
            if (styleName != null && StyleDef.ALL.containsKey(styleName)) {
                st = StyleDef.ALL.get(styleName);
            } else {
                List<String> opts = new ArrayList<>();
                for (String n : names) {
                    int sz = lastStyles.size();
                    boolean recent = (sz >= 1 && lastStyles.get(sz - 1).equals(n))
                            || (sz >= 2 && lastStyles.get(sz - 2).equals(n));
                    if (!recent) opts.add(n);
                }
                if (opts.isEmpty()) opts = names;
                st = StyleDef.ALL.get(pick(opts, rng));
            }
            List<Integer> copts = new ArrayList<>();
            for (int c : StyleDef.PALETTE) {
                boolean recent = false;
                for (int i = Math.max(0, lastColors.size() - 3); i < lastColors.size(); i++)
                    if (lastColors.get(i) == c) recent = true;
                if (!recent) copts.add(c);
            }
            int color = pick(copts, rng);
            String anim = st.anims[rng.nextInt(st.anims.length)];

            PhraseLayout p = buildPhrase(ph, st, color, anim, vertical, rng);
            p.start = start; p.end = end;
            p.heads = track != null ? track.headsBetween(start, end, 0.25) : new ArrayList<Box>();
            List<Anchors.Anchor> anchors = Anchors.free(p.heads, p.bw, p.bh, vertical);
            Anchors.Anchor an = chooseAnchor(anchors, lastZones, rng, lastPos);
            p.nx = an.cx; p.ny = an.cy; p.zone = an.zone; p.overlap = an.overlap;
            p.cx = (float) (an.cx * width); p.cy = (float) (an.cy * height);
            p.rot = rng.nextDouble() < 0.6 ? (float) (-2.8 + rng.nextDouble() * 5.6) : 0f;
            lastPos = new double[]{an.cx, an.cy};
            lastStyles.add(st.name); lastColors.add(color); lastZones.add(an.zone);
            phrases.add(p);
        }
    }

    private Anchors.Anchor chooseAnchor(List<Anchors.Anchor> anchors, List<String> lastZones,
                                        Random rng, double[] lastPos) {
        Map<String, List<Anchors.Anchor>> zones = new java.util.LinkedHashMap<>();
        for (Anchors.Anchor a : anchors) {
            List<Anchors.Anchor> l = zones.get(a.zone);
            if (l == null) { l = new ArrayList<>(); zones.put(a.zone, l); }
            l.add(a);
        }
        List<String> cand = new ArrayList<>();
        for (String z : zones.keySet()) {
            int n = lastZones.size();
            boolean recent = (n >= 1 && lastZones.get(n - 1).equals(z)) || (n >= 2 && lastZones.get(n - 2).equals(z));
            if (!recent) cand.add(z);
        }
        if (cand.isEmpty()) cand.addAll(zones.keySet());
        double[] w = new double[cand.size()];
        double sum = 0;
        for (int i = 0; i < w.length; i++) {
            String z = cand.get(i);
            double wt = 1.0;
            if (z.endsWith("centro")) wt *= 1.6;
            if (z.startsWith("baja")) wt *= 1.25;
            if (z.startsWith("alta")) wt *= 0.8;
            w[i] = wt; sum += wt;
        }
        double r = rng.nextDouble() * sum;
        int zi = 0;
        for (; zi < w.length - 1; zi++) { if (r < w[zi]) break; r -= w[zi]; }
        List<Anchors.Anchor> pool = zones.get(cand.get(zi));
        if (lastPos != null) {
            List<Anchors.Anchor> far = new ArrayList<>();
            for (Anchors.Anchor a : pool)
                if (Math.abs(a.cx - lastPos[0]) + Math.abs(a.cy - lastPos[1]) >= 0.07) far.add(a);
            if (!far.isEmpty()) {
                pool = far;
            } else {
                Anchors.Anchor best = anchors.get(0);
                double bd = -1;
                for (Anchors.Anchor a : anchors) {
                    double d = Math.abs(a.cx - lastPos[0]) + Math.abs(a.cy - lastPos[1]);
                    if (d > bd) { bd = d; best = a; }
                }
                return best;
            }
        }
        return pool.get(rng.nextInt(pool.size()));
    }

    private float textW(String t, StyleDef st, WAttr a, float size) {
        measure.setTypeface(face((a.italic || st.italicAll) ? st.fileItalic : st.file));
        measure.setTextSize(size);
        return measure.measureText(t) * a.scale;
    }

    private PhraseLayout buildPhrase(List<Word> ph, StyleDef st, int color, String anim,
                                     boolean vertical, Random rng) {
        int n = ph.size();
        PhraseLayout p = new PhraseLayout();
        p.words = ph; p.style = st; p.color = color; p.anim = anim;
        p.texts = new String[n]; p.attrs = new WAttr[n];
        for (int i = 0; i < n; i++) {
            String t = ph.get(i).text;
            p.texts[i] = st.caps ? t.toUpperCase(Locale.ROOT) : t;
            p.attrs[i] = new WAttr();
        }
        if (st.mix) {
            for (int i = 0; i < n; i++) {
                if (isKey(ph.get(i).text)) {
                    p.attrs[i].italic = rng.nextDouble() < 0.5;
                    p.attrs[i].scale = 1.28f;
                    int c;
                    do { c = StyleDef.PALETTE[rng.nextInt(StyleDef.PALETTE.length)]; } while (c == color);
                    p.attrs[i].color = c;
                } else if (rng.nextDouble() < 0.3) {
                    p.attrs[i].italic = true;
                }
            }
        }
        float base = vertical ? width * 0.118f : height * 0.10f;
        boolean force2 = vertical && n >= 3 && rng.nextDouble() < 0.5;
        float maxw = width * (vertical ? 0.86f : 0.80f);
        float size = base * st.sizeMul;
        float[] ww = new float[n];
        float space;
        int[][] lines;
        float lw;
        while (true) {
            for (int i = 0; i < n; i++) ww[i] = textW(p.texts[i], st, p.attrs[i], size);
            space = textW(" ", st, new WAttr(), size) * 1.35f;
            lines = splitLines(ww, space, maxw, force2);
            lw = 0;
            for (int[] ln : lines) {
                float s = space * (ln.length - 1);
                for (int j : ln) s += ww[j];
                lw = Math.max(lw, s);
            }
            if (lw <= maxw || size < base * 0.5f) break;
            size *= 0.93f;
        }
        p.size = size; p.lines = lines;
        p.lineH = size * 1.18f;
        float bord = size * st.outline;
        float mx = 0;
        for (WAttr a : p.attrs) mx = Math.max(mx, a.scale);
        p.bw = (lw + 2 * bord) * 1.14 / width;
        p.bh = (lines.length * p.lineH * mx + 2 * bord) * 1.1 / height;
        // posicion de cada palabra respecto al centro del bloque
        p.wordCx = new float[n];
        p.lineBase = new float[lines.length];
        measure.setTypeface(face(st.file)); measure.setTextSize(size);
        Paint.FontMetrics fm = measure.getFontMetrics();
        for (int li = 0; li < lines.length; li++) {
            float total = space * (lines[li].length - 1);
            for (int j : lines[li]) total += ww[j];
            float x = -total / 2f;
            for (int j : lines[li]) {
                p.wordCx[j] = x + ww[j] / 2f;
                x += ww[j] + space;
            }
            float yc = (li - (lines.length - 1) / 2f) * p.lineH;
            p.lineBase[li] = yc - (fm.ascent + fm.descent) / 2f;
        }
        return p;
    }

    private static int[][] splitLines(float[] ww, float space, float maxw, boolean force2) {
        int n = ww.length;
        float total = space * (n - 1);
        for (float w : ww) total += w;
        if (n == 1 || (total <= maxw && !force2)) {
            int[] all = new int[n];
            for (int i = 0; i < n; i++) all[i] = i;
            return new int[][]{all};
        }
        int bestCut = 1; float bestW = Float.MAX_VALUE;
        for (int cut = 1; cut < n; cut++) {
            float a = space * (cut - 1), b = space * (n - cut - 1);
            for (int i = 0; i < cut; i++) a += ww[i];
            for (int i = cut; i < n; i++) b += ww[i];
            float m = Math.max(a, b);
            if (m < bestW) { bestW = m; bestCut = cut; }
        }
        int[] l1 = new int[bestCut], l2 = new int[n - bestCut];
        for (int i = 0; i < bestCut; i++) l1[i] = i;
        for (int i = bestCut; i < n; i++) l2[i - bestCut] = i;
        return new int[][]{l1, l2};
    }

    // ------------------------------------------------------------------ tiempo

    private int phraseIndex(double t) {
        int lo = 0, hi = phrases.size() - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            PhraseLayout p = phrases.get(mid);
            if (t < p.start) hi = mid - 1;
            else if (t >= p.end) lo = mid + 1;
            else return mid;
        }
        return -1;
    }

    private static int activeWord(PhraseLayout p, double t) {
        int k = 0;
        for (int j = 1; j < p.words.size(); j++) if (p.words.get(j).start <= t) k = j;
        return k;
    }

    private static double wordStart(PhraseLayout p, int j) {
        return j == 0 ? p.start : p.words.get(j).start;
    }

    /** Clave del estado visual: igual clave = mismo dibujo (permite reutilizar el cuadro). -1 = nada. */
    public long stateKey(double t) {
        int idx = phraseIndex(t);
        if (idx < 0) return -1;
        PhraseLayout p = phrases.get(idx);
        int k = activeWord(p, t);
        boolean animating = (t - p.start) < 0.25 || (p.end - t) < 0.1 || (t - wordStart(p, k)) < 0.15;
        long key = ((long) idx << 40) | ((long) k << 32);
        if (animating) key |= 0x80000000L | (long) (t * 60);
        return key;
    }

    // ------------------------------------------------------------------ dibujo

    private static float clamp01(double v) { return (float) Math.max(0, Math.min(1, v)); }

    private static float easeOut(float x) { float i = 1 - x; return 1 - i * i * i; }

    private static float lerp(float a, float b, float t) { return a + (b - a) * t; }

    private float entryScale(String anim, double tin) {
        if (anim.equals("zoom")) return lerp(1.6f, 1f, easeOut(clamp01(tin / 0.17)));
        if (tin >= 0.19) return 1f;
        if (tin < 0.11) return lerp(0.55f, 1.12f, clamp01(tin / 0.11));
        return lerp(1.12f, 1f, clamp01((tin - 0.11) / 0.08));
    }

    /** Dibuja el cuadro del instante t (segundos). El canvas debe venir limpio y transparente. */
    public void draw(Canvas c, double t) {
        int idx = phraseIndex(t);
        if (idx < 0) return;
        PhraseLayout p = phrases.get(idx);
        double tin = t - p.start, tout = p.end - t;
        float alpha = Math.min(clamp01(tin / 0.06), clamp01(tout / 0.09));
        int k = activeWord(p, t);
        int saved = alpha < 0.999f ? c.saveLayerAlpha(null, (int) (alpha * 255)) : c.save();
        float ox = 0, oy = 0;
        float prog = easeOut(clamp01(tin / 0.17));
        if (p.anim.equals("slide_up")) oy = (1 - prog) * 0.035f * height;
        else if (p.anim.equals("slide_left")) ox = -(1 - prog) * 0.09f * width;
        c.translate(p.cx + ox, p.cy + oy);
        c.rotate(p.rot);
        if (p.style.glow) drawLayer(c, p, k, t, tin, true);
        drawLayer(c, p, k, t, tin, false);
        c.restoreToCount(saved);
    }

    private static int darken(int col, float k) {
        return 0xFF000000 | ((int) (((col >> 16) & 255) * k) << 16)
                | ((int) (((col >> 8) & 255) * k) << 8) | (int) ((col & 255) * k);
    }

    private void drawLayer(Canvas c, PhraseLayout p, int k, double t, double tin, boolean glow) {
        StyleDef st = p.style;
        float bord = p.size * st.outline;
        for (int li = 0; li < p.lines.length; li++) {
            for (int j : p.lines[li]) {
                if (st.reveal && j > k) continue;               // aun no se dice
                WAttr a = p.attrs[j];
                boolean active = j == k;
                int hot = a.color != null ? a.color : p.color;
                int col;
                if (st.baseNeon) col = active ? 0xFFFFFFFF : (a.color != null ? a.color : p.color);
                else col = active ? hot : (a.color != null ? a.color : 0xFFFFFFFF);
                float s = a.scale;
                if (k == 0 && tin < 0.19) {
                    if (!(st.reveal && j != 0)) s *= entryScale(p.anim, tin);
                } else if (active) {
                    double tw = t - wordStart(p, j);
                    s *= 1f + 0.12f * (1f - clamp01(tw / 0.14));
                }
                Typeface tf = face((a.italic || st.italicAll) ? st.fileItalic : st.file);
                fill.setTypeface(tf); stroke.setTypeface(tf);
                fill.setTextSize(p.size); stroke.setTextSize(p.size);
                c.save();
                c.translate(p.wordCx[j], p.lineBase[li]);
                c.scale(s, s);
                String txt = p.texts[j];
                if (glow) {
                    stroke.setStrokeWidth(p.size * 0.30f);
                    stroke.setColor(st.baseNeon ? p.color : col);
                    stroke.setAlpha(190);
                    stroke.setMaskFilter(new BlurMaskFilter(p.size * 0.16f, BlurMaskFilter.Blur.NORMAL));
                    c.drawText(txt, 0, 0, stroke);
                    stroke.setMaskFilter(null);
                } else {
                    if (st.shadow > 0) {
                        float off = p.size * st.shadow;
                        stroke.setStrokeWidth(bord * 2); stroke.setColor(0x50000000);
                        fill.setColor(0x50000000);
                        c.drawText(txt, off, off, stroke);
                        c.drawText(txt, off, off, fill);
                    }
                    stroke.setStrokeWidth(bord * 2);
                    stroke.setColor(st.baseNeon ? darken(p.color, 0.22f) : 0xFF000000);
                    stroke.setAlpha(255);
                    c.drawText(txt, 0, 0, stroke);
                    fill.setColor(col);
                    c.drawText(txt, 0, 0, fill);
                }
                c.restore();
            }
        }
    }
}
