package com.orbit.assistant;

import android.content.Context;
import android.graphics.Color;

import java.util.Locale;

/**
 * A plain-English name for a colour.
 *
 * <p>Theme Studio is a screen made almost entirely of colour, which is exactly the kind of screen
 * that stops working when colour is the only thing carrying the information. A swatch labelled
 * {@code #3A2E63} tells a sighted user very little and a screen-reader user nothing at all, so
 * every swatch Orbit draws is described as "Deep violet" first and by its hex value second.
 *
 * <p>Deterministic and offline: hue, saturation and value buckets, nothing looked up and nothing
 * learned. The names are approximate by design — this is a label, not a colour-science claim.
 */
public final class OrbitColorName {
    private OrbitColorName() {}

    /**
     * Hue boundaries, in degrees, and the name that starts at each.
     *
     * <p>Uneven on purpose. Even buckets put Orbit's own violet accent — which sits at 247° — on
     * the wrong side of a boundary and called it indigo, and green occupies far more of the circle
     * than red does. These follow how the families are actually named rather than how the numbers
     * divide.
     */
    private static final float[] HUE_STARTS = {
            10f, 40f, 52f, 66f, 90f, 150f, 175f, 195f, 215f, 240f, 260f, 285f, 320f, 345f
    };
    private static final String[] HUE_NAMES = {
            "orange", "amber", "yellow", "lime", "green", "teal", "cyan", "sky blue",
            "blue", "violet", "purple", "magenta", "pink", "red"
    };

    private interface Names {
        String grey(int index);
        String hue(int index);
        String styled(int style, String base);
    }

    private static final String[] GREYS = {
            "Black", "Near black", "Dark grey", "Grey", "Light grey", "White"
    };
    private static final String[] STYLES = {"very dark ", "deep ", "pale ", "light ", "vivid "};

    private static final Names ENGLISH = new Names() {
        @Override public String grey(int index) { return GREYS[index]; }
        @Override public String hue(int index) { return HUE_NAMES[index]; }
        @Override public String styled(int style, String base) {
            return style < 0 ? capitalize(base) : capitalize(STYLES[style] + base);
        }
    };

    private static final int[] HUE_RES = {
            R.string.colorname_orange, R.string.colorname_amber, R.string.colorname_yellow,
            R.string.colorname_lime, R.string.colorname_green, R.string.colorname_teal,
            R.string.colorname_cyan, R.string.colorname_sky_blue, R.string.colorname_blue,
            R.string.colorname_violet, R.string.colorname_purple, R.string.colorname_magenta,
            R.string.colorname_pink, R.string.colorname_red
    };
    private static final int[] GREY_RES = {
            R.string.colorname_black, R.string.colorname_near_black, R.string.colorname_dark_grey,
            R.string.colorname_grey, R.string.colorname_light_grey, R.string.colorname_white
    };
    private static final int[] STYLE_RES = {
            R.string.colorname_very_dark, R.string.colorname_deep, R.string.colorname_pale,
            R.string.colorname_light, R.string.colorname_vivid
    };

    private static Names names(final Context context) {
        return new Names() {
            @Override public String grey(int index) { return context.getString(GREY_RES[index]); }
            @Override public String hue(int index) { return context.getString(HUE_RES[index]); }
            @Override public String styled(int style, String base) {
                return style < 0 ? capitalize(base)
                        : capitalize(context.getString(STYLE_RES[style], base));
            }
        };
    }

    /** "Deep violet", "Pale sky blue", "Light grey". Always capitalised, never empty. */
    @Deprecated
    public static String of(int color) {
        return of(ENGLISH, color);
    }

    /** Same name in the app language. */
    public static String of(Context context, int color) {
        return of(names(context), color);
    }

    private static String of(Names n, int color) {
        float[] hsv = new float[3];
        Color.colorToHSV(color, hsv);
        float hue = hsv[0];
        float sat = hsv[1];
        float val = hsv[2];

        if (sat < 0.10f) {
            if (val < 0.06f) return n.grey(0);
            if (val < 0.28f) return n.grey(1);
            if (val < 0.55f) return n.grey(2);
            if (val < 0.80f) return n.grey(3);
            if (val < 0.96f) return n.grey(4);
            return n.grey(5);
        }

        String base = n.hue(hueIndex(hue));
        if (val < 0.22f) return n.styled(0, base);
        if (val < 0.45f) return n.styled(1, base);
        if (sat < 0.35f && val > 0.80f) return n.styled(2, base);
        if (val > 0.86f && sat < 0.62f) return n.styled(3, base);
        if (sat > 0.85f && val > 0.75f) return n.styled(4, base);
        return n.styled(-1, base);
    }

    /** What a swatch announces: the name, then the value, so both are available. */
    @Deprecated
    public static String describe(String role, int color) {
        String prefix = role == null || role.trim().isEmpty() ? "" : role.trim() + ", ";
        return prefix + of(color) + ", " + OrbitTheme.colorToken(color);
    }

    /** Same announcement in the app language; {@code role} is already localised by the caller. */
    public static String describe(Context context, String role, int color) {
        String prefix = role == null || role.trim().isEmpty() ? "" : role.trim() + ", ";
        return prefix + of(context, color) + ", " + OrbitTheme.colorToken(color);
    }

    /** Red wraps the origin, so it is both the first family and the last. */
    private static int hueIndex(float hue) {
        float value = ((hue % 360f) + 360f) % 360f;
        for (int i = 0; i < HUE_STARTS.length; i++) {
            if (value < HUE_STARTS[i]) return i == 0 ? HUE_NAMES.length - 1 : i - 1;
        }
        return HUE_NAMES.length - 1;
    }

    private static String capitalize(String value) {
        if (value.isEmpty()) return value;
        return value.substring(0, 1).toUpperCase(Locale.US) + value.substring(1);
    }
}
