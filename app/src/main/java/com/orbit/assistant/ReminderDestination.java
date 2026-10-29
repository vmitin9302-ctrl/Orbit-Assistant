package com.orbit.assistant;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.util.Locale;

/**
 * Where a new reminder goes: Orbit's own reminders, or Samsung Reminder.
 *
 * <p>Samsung Reminder is reached only through documented, public entry points, and only the ones
 * the installed Samsung Reminder actually declares on this phone:
 * <ul>
 *   <li>{@link #FULL}: Android's {@code ACTION_CREATE_REMINDER}, with the title and time filled in.
 *   <li>{@link #SHARE}: Android's plain-text share, so only the text arrives and the user sets the
 *       time in Samsung Reminder.
 * </ul>
 * Either way Samsung Reminder shows its own screen and the user saves there. Android reports
 * nothing back from that screen, so Orbit can say it opened Samsung Reminder and never that a
 * reminder was created. No private API, permission or Accessibility automation is involved.
 */
public final class ReminderDestination {
    public static final String ORBIT = "orbit";
    public static final String SAMSUNG = "samsung";

    /** Samsung Reminder declares nothing Orbit can use. */
    public static final int NONE = 0;
    /** Only plain-text share: the time has to be set in Samsung Reminder. */
    public static final int SHARE = 1;
    /** {@code ACTION_CREATE_REMINDER}: title and time arrive filled in. */
    public static final int FULL = 2;

    static final String SAMSUNG_PACKAGE = "com.samsung.android.app.reminder";
    /** {@code Intent.ACTION_CREATE_REMINDER}, spelled out because minSdk predates the constant. */
    static final String ACTION_CREATE_REMINDER = "android.intent.action.CREATE_REMINDER";
    static final String EXTRA_TIME = "android.intent.extra.TIME";

    /** Set once per install; records what this install was updated from, so never backed up. */
    static final String SETTLED = "reminder_app_settled";

    private ReminderDestination() {}

    /** The user's default destination, as stored. Unknown or missing values mean Orbit. */
    public static String current(Context c) {
        return SAMSUNG.equals(Prefs.get(c).getString(Prefs.REMINDER_APP, ORBIT)) ? SAMSUNG : ORBIT;
    }

    public static void set(Context c, String destination) {
        Prefs.get(c).edit().putString(Prefs.REMINDER_APP,
                SAMSUNG.equals(destination) ? SAMSUNG : ORBIT).apply();
    }

    /**
     * Picks the default exactly once per install. Someone updating keeps Orbit Reminders, which is
     * all they have ever had. A fresh install starts on Samsung Reminder only when the full
     * integration is present, since the text-only path leaves the time to the user. Never
     * overwrites a stored choice, including one restored from a backup.
     */
    static void settleForInstall(Context c, boolean packageWasUpdated) {
        SharedPreferences p = Prefs.get(c);
        if (p.getBoolean(SETTLED, false)) return;
        SharedPreferences.Editor e = p.edit().putBoolean(SETTLED, true);
        if (!p.contains(Prefs.REMINDER_APP)) {
            e.putString(Prefs.REMINDER_APP,
                    !packageWasUpdated && samsungSupport(c) == FULL ? SAMSUNG : ORBIT);
        }
        e.apply();
    }

    /**
     * The destination for one request: an explicit "samsung" or "orbit" in the action wins,
     * anything else falls back to the user's default.
     */
    static String forAction(Context c, JSONObject params) {
        String asked = params == null ? "" : params.optString("destination", "")
                .trim().toLowerCase(Locale.US);
        if (asked.contains(SAMSUNG)) return SAMSUNG;
        if (asked.contains(ORBIT)) return ORBIT;
        return current(c);
    }

    public static int samsungSupport(Context c) {
        if (c == null) return NONE;
        try {
            if (fullIntent("", 0L).resolveActivity(c.getPackageManager()) != null) return FULL;
            if (shareIntent("").resolveActivity(c.getPackageManager()) != null) return SHARE;
        } catch (Exception ignored) {}
        return NONE;
    }

    public static boolean samsungInstalled(Context c) {
        try {
            c.getPackageManager().getPackageInfo(SAMSUNG_PACKAGE, 0);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    static Intent fullIntent(String title, long triggerAt) {
        Intent i = new Intent(ACTION_CREATE_REMINDER).setPackage(SAMSUNG_PACKAGE)
                .putExtra(Intent.EXTRA_TITLE, title);
        if (triggerAt > 0L) i.putExtra(EXTRA_TIME, triggerAt);
        return i;
    }

    static Intent shareIntent(String text) {
        return new Intent(Intent.ACTION_SEND).setPackage(SAMSUNG_PACKAGE)
                .setType("text/plain").putExtra(Intent.EXTRA_TEXT, text);
    }

    static boolean isSamsungReminder(AssistantReply.Action action) {
        return action != null && "SET_REMINDER".equalsIgnoreCase(action.type)
                && SAMSUNG.equals(action.params.optString("destination", ""));
    }

    /** A Samsung request that could not be delivered, which a card offers to send to Orbit. */
    static boolean offersOrbitFallback(AssistantReply.Action action, String status) {
        return isSamsungReminder(action) && DeviceActionExecutor.STATUS_UNAVAILABLE.equals(status);
    }

    /**
     * The same reminder pinned to Orbit. Only ever built from a user's tap on a card whose Samsung
     * request created nothing, so it can never produce a second copy.
     */
    static AssistantReply.Action orbitFallback(AssistantReply.Action action) {
        try {
            JSONObject p = new JSONObject(action.params.toString()).put("destination", ORBIT);
            p.remove("reminderId");
            return new AssistantReply.Action(action.type, p, false);
        } catch (Exception e) {
            return null;
        }
    }

    /** One line for Settings that says what choosing Samsung Reminder will really do. */
    public static String samsungExplanation(Context c) {
        switch (samsungSupport(c)) {
            case FULL:
                return c.getString(R.string.remdest_samsung_full);
            case SHARE:
                return c.getString(R.string.remdest_samsung_share);
            default:
                return samsungInstalled(c)
                        ? c.getString(R.string.remdest_samsung_no_accept)
                        : c.getString(R.string.remdest_samsung_missing);
        }
    }
}
