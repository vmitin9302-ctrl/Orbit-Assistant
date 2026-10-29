package com.orbit.assistant;

import android.content.Context;

import java.util.Locale;

/**
 * Orbit decides whether to retry a failed request by reading the wording of the error. Errors that
 * Orbit words itself are now in the language of the phone, so the same decision also looks for the
 * words those errors use. The list for each decision is a comma-separated resource; the English
 * checks in the callers stay exactly as they were.
 */
final class ErrorMarkers {
    private ErrorMarkers() {}

    static boolean matches(Context context, int markersRes, String message) {
        if (context == null || message == null) return false;
        String lower = message.toLowerCase(Locale.ROOT);
        for (String marker : context.getString(markersRes).split(",")) {
            String word = marker.trim().toLowerCase(Locale.ROOT);
            if (!word.isEmpty() && lower.contains(word)) return true;
        }
        return false;
    }
}
