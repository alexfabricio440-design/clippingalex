package com.clippingalex.clipcaptions.core;

import java.util.ArrayList;
import java.util.List;

/** Limpieza de palabras y agrupacion en frases cortas estilo TikTok. */
public final class Phrases {
    private Phrases() { }

    private static boolean endsWithAny(String s, String chars) {
        if (s.isEmpty()) return false;
        return chars.indexOf(s.charAt(s.length() - 1)) >= 0;
    }

    /** Quita vacios, arregla tiempos invertidos y solapes. */
    public static List<Word> clean(List<Word> in) {
        List<Word> out = new ArrayList<>();
        for (Word w : in) {
            String t = w.text == null ? "" : w.text.trim();
            if (t.isEmpty()) continue;
            double start = w.start, end = w.end;
            if (end < start) end = start;
            if (!out.isEmpty() && start < out.get(out.size() - 1).end) {
                Word prev = out.get(out.size() - 1);
                prev.end = Math.max(prev.start, start);
            }
            out.add(new Word(t, start, end));
        }
        return out;
    }

    public static List<List<Word>> group(List<Word> words, int maxWords, int maxChars, double gap) {
        List<List<Word>> phrases = new ArrayList<>();
        List<Word> cur = new ArrayList<>();
        for (Word w : words) {
            if (!cur.isEmpty()) {
                Word last = cur.get(cur.size() - 1);
                double pause = w.start - last.end;
                int chars = cur.size() + w.text.length();
                for (Word x : cur) chars += x.text.length();
                if (pause > gap || cur.size() >= maxWords || chars > maxChars
                        || endsWithAny(last.text, ".?!…")) {
                    phrases.add(cur);
                    cur = new ArrayList<>();
                } else if (endsWithAny(last.text, ",;:—") && cur.size() >= 2) {
                    phrases.add(cur);
                    cur = new ArrayList<>();
                }
            }
            cur.add(w);
        }
        if (!cur.isEmpty()) phrases.add(cur);
        return phrases;
    }

    public static List<List<Word>> group(List<Word> words) {
        return group(words, 3, 22, 0.55);
    }
}
