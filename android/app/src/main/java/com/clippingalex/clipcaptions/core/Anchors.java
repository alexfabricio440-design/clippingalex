package com.clippingalex.clipcaptions.core;

import java.util.ArrayList;
import java.util.List;

/** Zonas libres para subtitulos: fuera de la cabeza y dentro del area segura. */
public final class Anchors {
    private Anchors() { }

    public static final Box SAFE_VERTICAL = new Box(0.06, 0.09, 0.88, 0.70);
    public static final Box SAFE_HORIZONTAL = new Box(0.05, 0.06, 0.90, 0.86);

    public static final class Anchor {
        public final double cx, cy, overlap;
        public final String zone;
        Anchor(double cx, double cy, String zone, double overlap) {
            this.cx = cx; this.cy = cy; this.zone = zone; this.overlap = overlap;
        }
    }

    static String zoneName(double cx, double cy, Box safe) {
        double ry = (cy - safe.y) / safe.h, rx = (cx - safe.x) / safe.w;
        String v = ry < 0.34 ? "alta" : ry < 0.67 ? "media" : "baja";
        String h = rx < 0.34 ? "izq" : rx < 0.67 ? "centro" : "der";
        return v + "-" + h;
    }

    public static List<Anchor> free(List<Box> heads, double bw, double bh, boolean vertical) {
        final int cols = 9, rows = 9;
        final double gap = 0.015;
        Box safe = vertical ? SAFE_VERTICAL : SAFE_HORIZONTAL;
        double minCx = safe.x + bw / 2, maxCx = safe.x2() - bw / 2;
        double minCy = safe.y + bh / 2, maxCy = safe.y2() - bh / 2;
        if (maxCx < minCx) { minCx = 0.5; maxCx = 0.5; }
        if (maxCy < minCy) { minCy = (safe.y + safe.y2()) / 2; maxCy = minCy; }
        List<Anchor> all = new ArrayList<>();
        for (int r = 0; r < rows; r++) {
            double cy = minCy + (maxCy - minCy) * r / (rows - 1);
            for (int c = 0; c < cols; c++) {
                double cx = minCx + (maxCx - minCx) * c / (cols - 1);
                Box blk = new Box(cx - bw / 2 - gap, cy - bh / 2 - gap, bw + 2 * gap, bh + 2 * gap);
                double ov = 0;
                for (Box h : heads) ov += blk.overlapArea(h);
                all.add(new Anchor(cx, cy, zoneName(cx, cy, safe), ov));
            }
        }
        List<Anchor> free = new ArrayList<>();
        for (Anchor a : all) if (a.overlap == 0) free.add(a);
        if (!free.isEmpty()) return free;
        double best = Double.MAX_VALUE;
        for (Anchor a : all) best = Math.min(best, a.overlap);
        List<Anchor> least = new ArrayList<>();
        for (Anchor a : all) if (a.overlap <= best * 1.05) least.add(a);
        return least;
    }
}
