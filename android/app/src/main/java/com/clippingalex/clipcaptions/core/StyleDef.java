package com.clippingalex.clipcaptions.core;

import java.util.LinkedHashMap;
import java.util.Map;

/** Catalogo de estilos y paleta de colores brillantes. */
public final class StyleDef {
    public final String name, file, fileItalic;
    public final boolean caps, glow, reveal, baseNeon, italicAll, mix;
    public final float sizeMul, outline, shadow;
    public final String[] anims;

    private StyleDef(String name, String file, String fileItalic, boolean caps, float sizeMul,
                     float outline, float shadow, boolean glow, boolean reveal, boolean baseNeon,
                     boolean italicAll, boolean mix, String... anims) {
        this.name = name; this.file = file; this.fileItalic = fileItalic; this.caps = caps;
        this.sizeMul = sizeMul; this.outline = outline; this.shadow = shadow; this.glow = glow;
        this.reveal = reveal; this.baseNeon = baseNeon; this.italicAll = italicAll; this.mix = mix;
        this.anims = anims;
    }

    /** Colores vivos/neon (ARGB opaco). */
    public static final int[] PALETTE = {
            0xFFFF2D95, 0xFF00E5FF, 0xFFB6FF00, 0xFFFFE600,
            0xFFFF7A00, 0xFFB14DFF, 0xFF00FF9C, 0xFFFF3347};

    public static final Map<String, StyleDef> ALL = new LinkedHashMap<>();

    static {
        String ib = "fonts/Inter-Black.otf", ibi = "fonts/Inter-BlackItalic.otf";
        String ie = "fonts/Inter-ExtraBold.otf", iei = "fonts/Inter-ExtraBoldItalic.otf";
        String pb = "fonts/Poppins-Bold.ttf", pbi = "fonts/Poppins-BoldItalic.ttf";
        add(new StyleDef("impacto", ib, ibi, true, 1.0f, 0.085f, 0.05f, false, false, false, false, false,
                "pop", "zoom", "slide_up"));
        add(new StyleDef("flujo", pb, pbi, false, 0.95f, 0.07f, 0.03f, false, false, false, true, false,
                "slide_up", "slide_left", "pop"));
        add(new StyleDef("neon", ie, iei, true, 0.95f, 0.05f, 0f, true, false, true, false, false,
                "zoom", "pop"));
        add(new StyleDef("mixto", pb, pbi, false, 0.95f, 0.075f, 0.04f, false, false, false, false, true,
                "pop", "slide_up", "zoom"));
        add(new StyleDef("aparece", ib, ibi, true, 1.05f, 0.09f, 0.05f, false, true, false, false, false,
                "pop"));
        add(new StyleDef("grueso", pb, pbi, false, 1.0f, 0.10f, 0.06f, false, false, false, false, false,
                "pop", "slide_left"));
    }

    private static void add(StyleDef s) { ALL.put(s.name, s); }
}
