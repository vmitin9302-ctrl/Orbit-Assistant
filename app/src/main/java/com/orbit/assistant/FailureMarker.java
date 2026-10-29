package com.orbit.assistant;

import android.content.Context;

/**
 * Tells a saved failure notice from an ordinary reply. The notice is plain text in the chat, so
 * the check reads its opening words: the English ones every older chat contains, and the opening
 * words of the notice in the current language.
 */
final class FailureMarker {
    private static final String ENGLISH_PREFIX = "Orbit could not finish";

    private FailureMarker() {}

    static boolean isFailure(Context context, String text) {
        if (text == null) return false;
        return text.startsWith(ENGLISH_PREFIX)
                || text.startsWith(context.getString(R.string.ui_orbit_could_not_finish_5bb52db0));
    }
}
