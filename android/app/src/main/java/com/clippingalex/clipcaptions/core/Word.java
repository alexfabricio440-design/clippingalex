package com.clippingalex.clipcaptions.core;

/** Palabra transcrita con su tiempo (segundos). */
public final class Word {
    public String text;
    public double start;
    public double end;

    public Word(String text, double start, double end) {
        this.text = text;
        this.start = start;
        this.end = end;
    }
}
