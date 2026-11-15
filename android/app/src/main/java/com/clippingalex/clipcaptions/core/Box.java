package com.clippingalex.clipcaptions.core;

/** Caja normalizada (0-1). x,y = esquina superior izquierda. */
public final class Box {
    public final double x, y, w, h;

    public Box(double x, double y, double w, double h) {
        this.x = x; this.y = y; this.w = w; this.h = h;
    }

    public double x2() { return x + w; }
    public double y2() { return y + h; }

    public boolean intersects(Box o) {
        return x < o.x2() && o.x < x2() && y < o.y2() && o.y < y2();
    }

    public double overlapArea(Box o) {
        double ow = Math.min(x2(), o.x2()) - Math.max(x, o.x);
        double oh = Math.min(y2(), o.y2()) - Math.max(y, o.y);
        return Math.max(0, ow) * Math.max(0, oh);
    }

    public Box union(Box o) {
        double nx = Math.min(x, o.x), ny = Math.min(y, o.y);
        return new Box(nx, ny, Math.max(x2(), o.x2()) - nx, Math.max(y2(), o.y2()) - ny);
    }

    /** Cara -> cabeza completa (pelo arriba, cuello abajo, aire a los lados). */
    public Box head() {
        double nx = x - 0.25 * w, ny = y - 0.45 * h, nw = 1.5 * w, nh = 1.95 * h;
        double x1 = Math.max(0, nx), y1 = Math.max(0, ny);
        double x2 = Math.min(1, nx + nw), y2 = Math.min(1, ny + nh);
        return new Box(x1, y1, x2 - x1, y2 - y1);
    }
}
