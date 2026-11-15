package com.clippingalex.clipcaptions.core;

import java.util.ArrayList;
import java.util.List;

/** Rostros muestreados en el tiempo. */
public final class FaceTrack {
    public static final class Sample {
        public final double t;
        public final List<Box> boxes;
        public Sample(double t, List<Box> boxes) { this.t = t; this.boxes = boxes; }
    }

    public final List<Sample> samples = new ArrayList<>();
    /** Segundos que se mantiene la ultima cara si deja de detectarse. */
    public double hold;

    public FaceTrack(double hold) { this.hold = hold; }

    public void add(double t, List<Box> boxes) { samples.add(new Sample(t, boxes)); }

    public boolean hasFaces() {
        for (Sample s : samples) if (!s.boxes.isEmpty()) return true;
        return false;
    }

    public List<Box> facesAt(double t) {
        List<Box> best = null;
        double dist = Double.MAX_VALUE;
        for (Sample s : samples) {
            if (s.boxes.isEmpty()) continue;
            double d = Math.abs(s.t - t);
            if (d < dist) { dist = d; best = s.boxes; }
        }
        return (best != null && dist <= hold) ? best : new ArrayList<Box>();
    }

    /** Zonas de cabeza que ocupa el rostro en toda la ventana [t0, t1]. */
    public List<Box> headsBetween(double t0, double t1, double margin) {
        List<Box> raw = new ArrayList<>();
        List<Double> times = new ArrayList<>();
        times.add(t0 - margin); times.add(t0); times.add((t0 + t1) / 2); times.add(t1); times.add(t1 + margin);
        for (Sample s : samples) if (s.t >= t0 - margin && s.t <= t1 + margin) times.add(s.t);
        for (double t : times) for (Box f : facesAt(t)) raw.add(f.head());
        List<Box> regions = raw;
        boolean changed = true;
        while (changed) {
            changed = false;
            List<Box> merged = new ArrayList<>();
            for (Box b : regions) {
                boolean joined = false;
                for (int i = 0; i < merged.size(); i++) {
                    if (merged.get(i).intersects(b)) {
                        merged.set(i, merged.get(i).union(b));
                        joined = true;
                        changed = true;
                        break;
                    }
                }
                if (!joined) merged.add(b);
            }
            regions = merged;
        }
        return regions;
    }
}
