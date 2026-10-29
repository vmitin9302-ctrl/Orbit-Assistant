package com.orbit.assistant;

import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioManager;
import android.os.BatteryManager;
import android.provider.Settings;

/**
 * What Orbit can honestly say about the phone's current state.
 *
 * <p>Orbit has been able to <em>change</em> brightness, media volume and Do Not Disturb for
 * several releases and has never been able to say what any of them are. This is the reading half,
 * and its whole discipline is that every sentence it produces comes from a value Android actually
 * returned. Nothing here estimates, predicts, or fills a gap with something plausible: a value the
 * platform will not give up is reported as unavailable, with the reason, rather than guessed.
 *
 * <p>Brightness in particular is read through the same interpretation Orbit writes with, so
 * "what's my brightness" and "set brightness to 40%" can never disagree about what 40% means.
 *
 * <p><b>Voice, since v0.7.8.0 Beta 2.</b> The values were right and the sentences read like a debug
 * dump: "Media volume is 47%." These are answers to a question somebody asked out loud, so they are
 * written the way an assistant would say them, in the second person, and a failure is a plain "I
 * couldn't read …" rather than a formal report about Orbit. Nothing about how a value is obtained
 * changed, and nothing here is ever sent to a model to be rewritten.
 */
public final class DeviceStatusReader {
    private DeviceStatusReader() {}

    /** One reading: a value, or an honest reason there is none. */
    public static final class Reading {
        public final boolean available;
        /** The sentence Orbit says. Always present, whether or not a value was readable. */
        public final String text;
        /** True when the reason for no value is an access the user has not granted. */
        public final boolean needsAccess;

        private Reading(boolean available, String text, boolean needsAccess) {
            this.available = available;
            this.text = text == null ? "" : text;
            this.needsAccess = needsAccess;
        }

        static Reading of(String text) { return new Reading(true, text, false); }
        static Reading unavailable(String text) { return new Reading(false, text, false); }
        static Reading blocked(String text) { return new Reading(false, text, true); }
    }

    // ---- battery ---------------------------------------------------------------------------------

    /**
     * The battery, from Android's own sticky broadcast.
     *
     * <p>Level and scale rather than a hardcoded hundred, because a device is allowed to report on
     * a different scale. Remaining runtime is deliberately absent: Android exposes no figure Orbit
     * could stand behind, and inventing "about four hours" would be the exact kind of confident
     * guess this class exists to avoid.
     */
    public static Reading battery(Context c) {
        if (c == null) return Reading.unavailable("I couldn't read your battery level.");
        Intent status;
        try {
            status = c.getApplicationContext().registerReceiver(null,
                    new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        } catch (Exception e) {
            return Reading.unavailable(c.getString(R.string.devstat_battery_unreadable));
        }
        if (status == null) return Reading.unavailable(c.getString(R.string.devstat_battery_unreadable));

        int level = status.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = status.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
        if (level < 0 || scale <= 0) {
            return Reading.unavailable(c.getString(R.string.devstat_battery_unreported));
        }
        int percent = Math.max(0, Math.min(100, Math.round(level * 100f / scale)));

        int state = status.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
        int plugged = status.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
        boolean charging = state == BatteryManager.BATTERY_STATUS_CHARGING
                || state == BatteryManager.BATTERY_STATUS_FULL
                || plugged != 0;
        boolean full = state == BatteryManager.BATTERY_STATUS_FULL;

        if (!charging) return Reading.of(c.getString(R.string.devstat_battery_not_charging, percent));
        String how = chargingPhrase(c, plugged);
        if (full) return Reading.of(c.getString(R.string.devstat_battery_full, percent, how));
        return Reading.of(c.getString(R.string.devstat_battery_charging, percent, how));
    }

    /**
     * How it is charging, as a phrase that reads as part of the sentence.
     *
     * <p>A plug Android names something Orbit has no word for produces no phrase at all, rather
     * than a bracketed code the reader has to interpret.
     */
    static String chargingPhrase(int plugged) {
        String source = plugSource(plugged);
        if (source.isEmpty()) return "";
        if ("wireless".equals(source)) return " wirelessly";
        return " over " + source;
    }

    /** As {@link #chargingPhrase(int)}, in the app language. */
    static String chargingPhrase(Context c, int plugged) {
        String source = plugSource(plugged);
        if (source.isEmpty()) return "";
        if ("wireless".equals(source)) return " " + c.getString(R.string.devstat_charge_wireless);
        if ("USB".equals(source)) return " " + c.getString(R.string.devstat_charge_usb);
        return " " + c.getString(R.string.devstat_charge_mains);
    }

    /** The plug Android named, or "" when it named one Orbit has no word for. */
    static String plugSource(int plugged) {
        if ((plugged & BatteryManager.BATTERY_PLUGGED_AC) != 0) return "mains";
        if ((plugged & BatteryManager.BATTERY_PLUGGED_USB) != 0) return "USB";
        if ((plugged & BatteryManager.BATTERY_PLUGGED_WIRELESS) != 0) return "wireless";
        return "";
    }

    // ---- brightness -------------------------------------------------------------------------------

    /** The screen brightness, in the same percentage Orbit sets it with. */
    public static Reading brightness(Context c) {
        if (c == null) return Reading.unavailable("I couldn't read your screen brightness.");
        int percent = DeviceActionExecutor.currentBrightnessPercent(c, -1);
        if (percent < 0) {
            return Reading.unavailable(c.getString(R.string.devstat_bright_unreported));
        }
        String line = c.getString(R.string.devstat_bright_at, percent);
        if (adaptiveBrightness(c)) {
            // A single reading of an adaptive screen is true right now and will not stay true.
            line += " " + c.getString(R.string.devstat_bright_adaptive);
        }
        return Reading.of(line);
    }

    static boolean adaptiveBrightness(Context c) {
        try {
            return Settings.System.getInt(c.getContentResolver(),
                    Settings.System.SCREEN_BRIGHTNESS_MODE, -1)
                    == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC;
        } catch (Exception e) {
            return false;
        }
    }

    // ---- media volume -----------------------------------------------------------------------------

    /** The media stream, as the percentage Orbit's own volume commands work in. */
    public static Reading mediaVolume(Context c) {
        int percent = mediaVolumePercent(c);
        if (percent < 0) return Reading.unavailable(c == null ? "I couldn't read your media volume." : c.getString(R.string.devstat_vol_unreadable));
        if (percent == 0) return Reading.of(c.getString(R.string.devstat_vol_zero));
        return Reading.of(c.getString(R.string.devstat_vol_at, percent));
    }

    /** The media stream as a percentage, or -1 when it cannot be read. */
    public static int mediaVolumePercent(Context c) {
        if (c == null) return -1;
        try {
            AudioManager am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
            if (am == null) return -1;
            int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            if (max <= 0) return -1;
            return Math.max(0, Math.min(100,
                    Math.round(am.getStreamVolume(AudioManager.STREAM_MUSIC) * 100f / max)));
        } catch (Exception e) {
            return -1;
        }
    }

    // ---- ringer -----------------------------------------------------------------------------------

    /** The ringer mode, which Android reports without any special access. */
    public static Reading ringer(Context c) {
        String mode = ringerModeName(c);
        if (mode.isEmpty()) return Reading.unavailable(c == null ? "I couldn't read your ringer mode." : c.getString(R.string.devstat_ringer_unreadable));
        switch (mode) {
            case "Vibrate": return Reading.of(c.getString(R.string.devstat_ringer_vibrate));
            case "Silent": return Reading.of(c.getString(R.string.devstat_ringer_silent));
            default: return Reading.of(c.getString(R.string.devstat_ringer_on));
        }
    }

    /** "Normal", "Vibrate", "Silent", or "" when the mode could not be read. */
    public static String ringerModeName(Context c) {
        if (c == null) return "";
        try {
            AudioManager am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
            if (am == null) return "";
            switch (am.getRingerMode()) {
                case AudioManager.RINGER_MODE_NORMAL: return "Normal";
                case AudioManager.RINGER_MODE_VIBRATE: return "Vibrate";
                case AudioManager.RINGER_MODE_SILENT: return "Silent";
                default: return "";
            }
        } catch (Exception e) {
            return "";
        }
    }

    // ---- Do Not Disturb ----------------------------------------------------------------------------

    /**
     * Do Not Disturb, when Orbit is allowed to see it.
     *
     * <p>Reading the interruption filter needs the same Do Not Disturb access that changing it
     * needs. Without it Orbit says so rather than reporting a state it cannot see, and does not
     * prompt for a permission simply to make a sentence available.
     */
    public static Reading doNotDisturb(Context c) {
        if (c == null) return Reading.unavailable("I couldn't read Do Not Disturb.");
        NotificationManager nm =
                (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return Reading.unavailable(c.getString(R.string.devstat_dnd_unreadable));
        if (!nm.isNotificationPolicyAccessGranted()) {
            // The one reading that genuinely needs an access, so the sentence keeps naming it.
            return Reading.blocked(c.getString(R.string.devstat_dnd_blocked));
        }
        try {
            int filter = nm.getCurrentInterruptionFilter();
            switch (filter) {
                case NotificationManager.INTERRUPTION_FILTER_ALL:
                    return Reading.of(c.getString(R.string.devstat_dnd_off));
                case NotificationManager.INTERRUPTION_FILTER_NONE:
                    return Reading.of(c.getString(R.string.devstat_dnd_none));
                case NotificationManager.INTERRUPTION_FILTER_PRIORITY:
                    return Reading.of(c.getString(R.string.devstat_dnd_priority));
                case NotificationManager.INTERRUPTION_FILTER_ALARMS:
                    return Reading.of(c.getString(R.string.devstat_dnd_alarms));
                default:
                    return Reading.unavailable(c.getString(R.string.devstat_dnd_unreported));
            }
        } catch (Exception e) {
            return Reading.unavailable(c.getString(R.string.devstat_dnd_unreadable));
        }
    }
}
