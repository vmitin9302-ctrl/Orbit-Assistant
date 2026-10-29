package com.orbit.assistant;

import android.content.Context;
import androidx.annotation.StringRes;

import java.util.Locale;

/**
 * How much reasoning a request asks its model to spend.
 *
 * <p>One ordered list for every model, so "nearest supported strength" means the same thing on every
 * screen. Which of these a given model accepts is a fact about the model and lives in
 * {@link AiModelSpec}; nothing outside the catalog decides it.
 */
public enum AiStrength {
    NONE("none", "None", R.string.aistrength_none),
    LOW("low", "Low", R.string.aistrength_low),
    MEDIUM("medium", "Medium", R.string.aistrength_medium),
    HIGH("high", "High", R.string.aistrength_high),
    XHIGH("xhigh", "Extra High", R.string.aistrength_xhigh),
    MAX("max", "Max", R.string.aistrength_max);

    /** The value sent to the backend as {@code reasoning.effort}. Never renamed. */
    public final String id;
    /** What the user reads. */
    public final String label;
    /** The localized display name of {@link #label}; {@link #label} stays the stable English key. */
    @StringRes public final int labelRes;

    AiStrength(String id, String label, @StringRes int labelRes) {
        this.id = id;
        this.label = label;
        this.labelRes = labelRes;
    }

    /** What the user reads, in the app language. */
    public String label(Context context) {
        return context == null ? label : context.getString(labelRes);
    }

    /** The strength with this backend id or label, or null for anything else. */
    public static AiStrength fromId(String value) {
        if (value == null) return null;
        String v = value.trim().toLowerCase(Locale.US).replace("-", "").replace(" ", "");
        if (v.isEmpty()) return null;
        for (AiStrength s : values()) {
            if (s.id.equals(v) || s.label.toLowerCase(Locale.US).replace(" ", "").equals(v)) return s;
        }
        return null;
    }
}
