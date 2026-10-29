package com.orbit.assistant;

import android.content.Context;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.Locale;

/**
 * Small, deterministic action catalog for saved Orbit routines.
 *
 * Routines deliberately expose actions that are predictable when replayed later.
 * Communication/composer actions remain outside the routine builder until Orbit has
 * a dedicated safety/confirmation model for reusable outbound actions.
 */
public final class RoutineActionCatalog {
    public static final int MAX_STEPS = 20;

    public static final String IF_CONDITION = "IF_CONDITION";
    public static final String OPEN_APP = "OPEN_APP";
    public static final String SET_BRIGHTNESS = "SET_BRIGHTNESS";
    public static final String SET_DND = "SET_DND";
    public static final String SET_VOLUME = "SET_VOLUME";
    public static final String FLASHLIGHT = "FLASHLIGHT";
    public static final String SET_TIMER = "SET_TIMER";
    public static final String SET_ALARM = "SET_ALARM";
    public static final String OPEN_SETTINGS = "OPEN_SETTINGS";
    public static final String OPEN_INTERNET_PANEL = "OPEN_INTERNET_PANEL";
    public static final String OPEN_BLUETOOTH_SETTINGS = "OPEN_BLUETOOTH_SETTINGS";
    public static final String EXTENSION_ACTION = "EXTENSION_ACTION";

    public static final String[] TYPES = {
            IF_CONDITION,
            OPEN_APP,
            SET_BRIGHTNESS,
            SET_DND,
            SET_VOLUME,
            FLASHLIGHT,
            SET_TIMER,
            SET_ALARM,
            OPEN_SETTINGS,
            OPEN_INTERNET_PANEL,
            OPEN_BLUETOOTH_SETTINGS
    };

    public static final String[] LABELS = {
            "If condition",
            "Open app",
            "Brightness",
            "Do Not Disturb",
            "Media volume",
            "Flashlight",
            "Timer",
            "Alarm",
            "Open Settings",
            "Internet panel",
            "Bluetooth settings"
    };

    private RoutineActionCatalog() {}

    public static boolean isSupported(String type) {
        if (type == null) return false;
        if (EXTENSION_ACTION.equalsIgnoreCase(type)) return true;
        for (String candidate : TYPES) if (candidate.equalsIgnoreCase(type)) return true;
        return false;
    }

    public static boolean isConfigurable(String type) {
        if (type == null) return false;
        return !(OPEN_SETTINGS.equals(type) || OPEN_INTERNET_PANEL.equals(type) ||
                OPEN_BLUETOOTH_SETTINGS.equals(type));
    }

    /** Which string a label comes from; parallel to {@link #LABELS}. */
    private static final int[] LABEL_RES = {
            R.string.rcat_label_if_condition, R.string.rcat_label_open_app,
            R.string.rcat_label_brightness, R.string.rcat_label_dnd,
            R.string.rcat_label_media_volume, R.string.rcat_label_flashlight,
            R.string.rcat_label_timer, R.string.rcat_label_alarm,
            R.string.rcat_label_open_settings, R.string.rcat_label_internet_panel,
            R.string.rcat_label_bluetooth_settings
    };

    /**
     * One piece of wording: from resources when a Context is at hand, otherwise the English text
     * (the {@code fallback} equals the English resource, so both routes read the same).
     */
    private static String t(Context c, int res, String fallback, Object... args) {
        if (c != null) return args.length == 0 ? c.getString(res) : c.getString(res, args);
        return args.length == 0 ? fallback : String.format(Locale.US, fallback, args);
    }

    private static String plural(Context c, int res, int count, String one, String other) {
        if (c != null) return c.getResources().getQuantityString(res, count, count);
        return String.format(Locale.US, count == 1 ? one : other, count);
    }

    @Deprecated

    public static String labelForType(String type) {
        return labelForType(null, type);
    }

    /** The label in the app language (English when there is no Context). */
    public static String labelForType(Context c, String type) {
        if (type == null) return t(c, R.string.rcat_action, "Action");
        for (int i = 0; i < TYPES.length; i++) {
            if (TYPES[i].equalsIgnoreCase(type)) return t(c, LABEL_RES[i], LABELS[i]);
        }
        return titleCase(c, type.replace('_', ' '));
    }

    @Deprecated

    public static String title(AssistantReply.Action action) {
        return title(null, action);
    }

    /** The step title in the app language (English when there is no Context). */
    public static String title(Context c, AssistantReply.Action action) {
        if (action == null) return t(c, R.string.rcat_action, "Action");
        JSONObject p = action.params == null ? new JSONObject() : action.params;
        String type = action.type == null ? "" : action.type.toUpperCase(Locale.US);
        switch (type) {
            case IF_CONDITION: {
                String mode = RoutineConditionEvaluator.mode(action);
                String branch = RoutineBranch.hasElse(action)
                        ? t(c, R.string.rcat_with_else, " · with ELSE") : "";
                if (RoutineConditionEvaluator.MODE_LOCATION.equals(mode)) {
                    String name = clean(p.optString("locationName", ""));
                    return t(c, R.string.rcat_if_location, "If location")
                            + (name.isEmpty() ? "" : " · " + name) + branch;
                }
                String window = minuteLabel(c, p.optInt("startMinute", 0)) + "–"
                        + minuteLabel(c, p.optInt("endMinute", 0));
                if (RoutineConditionEvaluator.MODE_TIME_AND_LOCATION.equals(mode)) {
                    String name = clean(p.optString("locationName", ""));
                    return t(c, R.string.rcat_if_time_location, "If time + location") + " · "
                            + window + (name.isEmpty() ? "" : " · " + name) + branch;
                }
                return t(c, R.string.rcat_if_time, "If time") + " · " + window + branch;
            }
            case OPEN_APP:
                String app = clean(p.optString("app", p.optString("package", "")));
                return app.isEmpty() ? t(c, R.string.rcat_label_open_app, "Open app")
                        : t(c, R.string.rcat_open_named, "Open %1$s", app);
            case SET_BRIGHTNESS:
                return t(c, R.string.rcat_title_brightness, "Brightness · %1$d%%",
                        clampPercent(p.optInt("percent", 50)));
            case SET_DND:
                return t(c, R.string.rcat_title_dnd, "Do Not Disturb · %1$s",
                        onOff(c, p.optBoolean("enabled", true)));
            case SET_VOLUME:
                return t(c, R.string.rcat_title_volume, "Media volume · %1$d%%",
                        clampPercent(p.optInt("percent", 50)));
            case FLASHLIGHT:
                return t(c, R.string.rcat_title_flashlight, "Flashlight · %1$s",
                        onOff(c, p.optBoolean("on", true)));
            case SET_TIMER:
                return t(c, R.string.rcat_title_timer, "Timer · %1$s",
                        durationLabel(c, Math.max(1, p.optInt("seconds", 60))));
            case SET_ALARM:
                return t(c, R.string.rcat_title_alarm, "Alarm · %1$s",
                        timeLabel(c, p.optInt("hour", 8), p.optInt("minute", 0)));
            case OPEN_SETTINGS:
                return t(c, R.string.rcat_label_open_settings, "Open Settings");
            case OPEN_INTERNET_PANEL:
                return t(c, R.string.rcat_title_open_internet, "Open Internet panel");
            case OPEN_BLUETOOTH_SETTINGS:
                return t(c, R.string.rcat_title_open_bluetooth, "Open Bluetooth settings");
            case EXTENSION_ACTION: {
                String actionName = clean(p.optString("actionName", ""));
                return actionName.isEmpty() ? t(c, R.string.rcat_extension_action, "Extension action")
                        : actionName;
            }
            default:
                return labelForType(c, type);
        }
    }

    private static String onOff(Context c, boolean on) {
        return on ? t(c, R.string.rcat_on, "On") : t(c, R.string.rcat_off, "Off");
    }

    @Deprecated

    public static String summary(AssistantReply.Action action) {
        return summary(null, action);
    }

    /** The step summary in the app language (English when there is no Context). */
    public static String summary(Context c, AssistantReply.Action action) {
        if (action == null) return "";
        JSONObject p = action.params == null ? new JSONObject() : action.params;
        String type = action.type == null ? "" : action.type.toUpperCase(Locale.US);
        switch (type) {
            case IF_CONDITION: {
                int next = RoutineConditionEvaluator.gatedSteps(action);
                int otherwise = RoutineBranch.elseSteps(action);
                String target = next == 1 ? t(c, R.string.rcat_next_one, "the next step")
                        : plural(c, R.plurals.rcat_next_steps, next,
                                "the next %d step", "the next %d steps");
                if (otherwise > 0) {
                    // With an ELSE the interesting fact is that exactly one path runs, so the
                    // summary describes the split rather than repeating the condition mode.
                    String elseTarget = otherwise == 1
                            ? t(c, R.string.rcat_after_one, "the step after")
                            : plural(c, R.plurals.rcat_steps_after, otherwise,
                                    "the %d step after", "the %d steps after");
                    return t(c, R.string.rcat_sum_if_else,
                            "Runs %1$s when true, otherwise %2$s, then continues the routine",
                            target, elseTarget);
                }
                String mode = RoutineConditionEvaluator.mode(action);
                if (RoutineConditionEvaluator.MODE_LOCATION.equals(mode)) {
                    int radius = Math.round((float) p.optDouble("radiusMeters", 200d));
                    return t(c, R.string.rcat_sum_if_location,
                            "Runs %1$s only while inside the saved %2$s area",
                            target, radiusLabel(c, radius));
                }
                if (RoutineConditionEvaluator.MODE_TIME_AND_LOCATION.equals(mode)) {
                    return t(c, R.string.rcat_sum_if_both,
                            "Runs %1$s only when both the time and location match", target);
                }
                return t(c, R.string.rcat_sum_if_time,
                        "Runs %1$s only during the saved time window", target);
            }
            case OPEN_APP:
                return t(c, R.string.rcat_sum_open_app, "Launches the selected app");
            case SET_BRIGHTNESS:
                return t(c, R.string.rcat_sum_brightness, "Sets screen brightness to %1$d%%",
                        clampPercent(p.optInt("percent", 50)));
            case SET_DND:
                return p.optBoolean("enabled", true)
                        ? t(c, R.string.rcat_sum_dnd_on, "Enables Do Not Disturb")
                        : t(c, R.string.rcat_sum_dnd_off, "Disables Do Not Disturb");
            case SET_VOLUME:
                return t(c, R.string.rcat_sum_volume, "Sets media volume to %1$d%%",
                        clampPercent(p.optInt("percent", 50)));
            case FLASHLIGHT:
                return p.optBoolean("on", true)
                        ? t(c, R.string.rcat_sum_flash_on, "Turns the flashlight on")
                        : t(c, R.string.rcat_sum_flash_off, "Turns the flashlight off");
            case SET_TIMER: {
                String label = clean(p.optString("label", ""));
                String base = t(c, R.string.rcat_sum_timer, "Starts a %1$s timer",
                        durationModifierLabel(c, Math.max(1, p.optInt("seconds", 60))));
                return label.isEmpty() || "Orbit timer".equals(label) ? base : base + " · " + label;
            }
            case SET_ALARM: {
                String label = clean(p.optString("label", ""));
                String base = t(c, R.string.rcat_sum_alarm, "Sets an alarm for %1$s",
                        timeLabel(c, p.optInt("hour", 8), p.optInt("minute", 0)));
                return label.isEmpty() || "Orbit alarm".equals(label) ? base : base + " · " + label;
            }
            case OPEN_SETTINGS:
                return t(c, R.string.rcat_sum_open_settings, "Opens Android Settings");
            case OPEN_INTERNET_PANEL:
                return t(c, R.string.rcat_sum_open_internet, "Opens Android's Internet controls");
            case OPEN_BLUETOOTH_SETTINGS:
                return t(c, R.string.rcat_sum_open_bluetooth, "Opens Bluetooth settings");
            case EXTENSION_ACTION: {
                String extensionName = clean(p.optString("extensionName", ""));
                return extensionName.isEmpty()
                        ? t(c, R.string.rcat_sum_extension, "Runs a saved declarative extension action")
                        : t(c, R.string.rcat_sum_extension_named, "Extension · %1$s", extensionName);
            }
            default:
                return t(c, R.string.rcat_sum_default, "Saved Orbit action");
        }
    }

    public static AssistantReply.Action copy(AssistantReply.Action action) {
        if (action == null) return null;
        JSONObject params;
        try { params = new JSONObject(action.params == null ? "{}" : action.params.toString()); }
        catch (Exception ignored) { params = new JSONObject(); }
        return new AssistantReply.Action(action.type, params, action.requiresConfirmation);
    }

    public static boolean isValid(AssistantReply.Action action) {
        if (action == null || !isSupported(action.type)) return false;
        JSONObject p = action.params == null ? new JSONObject() : action.params;
        String type = action.type.toUpperCase(Locale.US);
        switch (type) {
            case IF_CONDITION: {
                String mode = RoutineConditionEvaluator.mode(action);
                int next = p.optInt("nextSteps", 1);
                if (next < 1 || next > 5) return false;
                // Absent means no ELSE path, which is every condition written before v0.7.5.0.
                int otherwise = p.optInt(RoutineBranch.KEY_ELSE_STEPS, 0);
                if (otherwise < 0 || otherwise > RoutineBranch.MAX_BRANCH_STEPS) return false;
                if (RoutineConditionEvaluator.MODE_TIME.equals(mode) ||
                        RoutineConditionEvaluator.MODE_TIME_AND_LOCATION.equals(mode)) {
                    int start = p.optInt("startMinute", -1);
                    int end = p.optInt("endMinute", -1);
                    if (start < 0 || start > 1439 || end < 0 || end > 1439) return false;
                }
                if (RoutineConditionEvaluator.MODE_LOCATION.equals(mode) ||
                        RoutineConditionEvaluator.MODE_TIME_AND_LOCATION.equals(mode)) {
                    double lat = p.optDouble("latitude", Double.NaN);
                    double lon = p.optDouble("longitude", Double.NaN);
                    double radius = p.optDouble("radiusMeters", -1d);
                    if (Double.isNaN(lat) || lat < -90d || lat > 90d ||
                            Double.isNaN(lon) || lon < -180d || lon > 180d ||
                            radius < RoutineTriggerStore.MIN_LOCATION_RADIUS_METERS ||
                            radius > RoutineTriggerStore.MAX_LOCATION_RADIUS_METERS) return false;
                }
                return true;
            }
            case OPEN_APP:
                return !clean(p.optString("app", p.optString("package", ""))).isEmpty();
            case SET_BRIGHTNESS:
            case SET_VOLUME:
                return p.has("percent") && p.optInt("percent", -1) >= 0 && p.optInt("percent", 101) <= 100;
            case SET_TIMER:
                return p.optInt("seconds", 0) > 0;
            case SET_ALARM:
                return p.optInt("hour", -1) >= 0 && p.optInt("hour", 24) <= 23 &&
                        p.optInt("minute", -1) >= 0 && p.optInt("minute", 60) <= 59;
            case EXTENSION_ACTION:
                return hasOnlyExtensionKeys(p) &&
                        validReference(p.optString("extensionId", ""), 80) &&
                        validReference(p.optString("actionId", ""), 64) &&
                        clean(p.optString("extensionName", "")).length() <= 60 &&
                        clean(p.optString("actionName", "")).length() <= 60 &&
                        validStoredActionParameters(p.optJSONObject("actionParameters"));
            default:
                return true;
        }
    }

    public static AssistantReply.Action extensionAction(OrbitExtension extension,
                                                        OrbitExtension.Action action) {
        return extensionAction(extension, action, new JSONObject());
    }

    public static AssistantReply.Action extensionAction(OrbitExtension extension,
            OrbitExtension.Action action, JSONObject actionParameters) {
        if (extension == null || action == null) return null;
        JSONObject params = new JSONObject();
        try {
            params.put("extensionId", extension.id);
            params.put("actionId", action.id);
            // Display-only snapshots keep a removed action understandable. Execution
            // always resolves the stable IDs against the current validated manifest.
            params.put("extensionName", extension.name);
            params.put("actionName", action.name);
            if (actionParameters != null && actionParameters.length() > 0) {
                params.put("actionParameters", new JSONObject(actionParameters.toString()));
            }
        } catch (Exception ignored) {}
        return new AssistantReply.Action(EXTENSION_ACTION, params, false);
    }

    private static boolean hasOnlyExtensionKeys(JSONObject params) {
        Iterator<String> keys = params.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if (!("extensionId".equals(key) || "actionId".equals(key) ||
                    "extensionName".equals(key) || "actionName".equals(key) ||
                    "actionParameters".equals(key))) return false;
        }
        return true;
    }

    private static boolean validStoredActionParameters(JSONObject parameters) {
        if (parameters == null) return true;
        if (parameters.length() > OrbitExtensionV2.MAX_PARAMETERS ||
                parameters.toString().getBytes(StandardCharsets.UTF_8).length >
                        OrbitExtension.MAX_POST_BODY_BYTES) return false;
        Iterator<String> keys = parameters.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if (!key.matches("[a-z0-9][a-z0-9_-]{1,63}") || sensitiveParameterId(key))
                return false;
            Object value = parameters.opt(key);
            if (!(value instanceof String) || ((String) value).length() > OrbitExtensionV2.MAX_TEXT_LENGTH)
                return false;
        }
        return true;
    }

    private static boolean sensitiveParameterId(String id) {
        String lower = id.toLowerCase(Locale.US);
        return lower.contains("secret") || lower.contains("password") ||
                lower.contains("credential") || lower.contains("token") ||
                lower.contains("api_key") || lower.contains("apikey");
    }

    public static int clampPercent(int percent) {
        return Math.max(0, Math.min(100, percent));
    }

    @Deprecated

    public static String timeLabel(int hour24, int minute) {
        return timeLabel(null, hour24, minute);
    }

    /** Clock time in the app language (12-hour with AM/PM in English, 24-hour in Russian). */
    public static String timeLabel(Context c, int hour24, int minute) {
        int h = Math.max(0, Math.min(23, hour24));
        int m = Math.max(0, Math.min(59, minute));
        if (c != null && c.getResources().getBoolean(R.bool.rcat_clock_24h)) {
            return c.getString(R.string.rcat_time_24h, h, m);
        }
        String ap = h >= 12 ? t(c, R.string.rcat_pm, "PM") : t(c, R.string.rcat_am, "AM");
        int display = h % 12;
        if (display == 0) display = 12;
        return String.format(Locale.US, "%d:%02d %s", display, m, ap);
    }

    @Deprecated

    public static String minuteLabel(int minuteOfDay) {
        return minuteLabel(null, minuteOfDay);
    }

    public static String minuteLabel(Context c, int minuteOfDay) {
        int safe = Math.max(0, Math.min(1439, minuteOfDay));
        return timeLabel(c, safe / 60, safe % 60);
    }

    private static String radiusLabel(Context c, int radiusMeters) {
        if (radiusMeters >= 1000 && radiusMeters % 1000 == 0) {
            return t(c, R.string.rcat_radius_km, "%1$d km", radiusMeters / 1000);
        }
        return t(c, R.string.rcat_radius_m, "%1$d m", radiusMeters);
    }

    /**
     * A duration standing on its own: "20 minutes", "4 minutes 30 seconds".
     *
     * <p>A duration that does not sit naturally in one unit is written in the units it actually
     * spans. Falling back to "270 seconds" was technically true and unreadable, and it is the same
     * category of mistake as showing a 4-minute-30 timer as "4m". Which durations count as
     * single-unit is decided once, by {@link DurationParser}, so this and the modifier form beneath
     * it can never disagree about whether 90 seconds is a minute and a half.
     */
    @Deprecated
    public static String durationLabel(int seconds) {
        return durationLabel(null, seconds);
    }

    /** {@link #durationLabel(int)} in the app language (English when there is no Context). */
    public static String durationLabel(Context c, int seconds) {
        int safe = Math.max(1, seconds);
        int single = DurationParser.naturalSingleUnit(safe);
        StringBuilder out = new StringBuilder();
        if (single > 0) {
            appendPart(c, out, safe / single,
                    single == 3600 ? "hour" : single == 60 ? "minute" : "second");
            return out.toString();
        }
        appendPart(c, out, safe / 3600, "hour");
        appendPart(c, out, (safe % 3600) / 60, "minute");
        appendPart(c, out, safe % 60, "second");
        return out.toString();
    }

    /** One part of a spanning duration, pluralized, skipped entirely when it is zero. */
    private static void appendPart(Context c, StringBuilder out, int count, String singularUnit) {
        if (count <= 0) return;
        if (out.length() > 0) out.append(' ');
        if (c != null) {
            int res = "hour".equals(singularUnit) ? R.plurals.rcat_dur_hours
                    : "minute".equals(singularUnit) ? R.plurals.rcat_dur_minutes
                    : R.plurals.rcat_dur_seconds;
            out.append(c.getResources().getQuantityString(res, count, count));
            return;
        }
        out.append(count).append(' ').append(singularUnit);
        if (count != 1) out.append('s');
    }

    /**
     * The same duration written as a modifier in front of a noun: "20-minute timer", "1-hour
     * timer". English hyphenates a counted unit in that position and keeps it singular, which is
     * the opposite of {@link #durationLabel(int)}: a duration standing on its own is still "20
     * minutes". Composing the standalone form with a noun is what produced "20 minutes timer".
     *
     * <p>A duration spanning units is delegated to {@link DurationParser#spokenModifier(long)},
     * which owns the one rule for saying a mixed duration out loud.
     */
    @Deprecated
    public static String durationModifierLabel(int seconds) {
        return DurationParser.spokenModifier(Math.max(1, seconds));
    }

    /** {@link #durationModifierLabel(int)} in the app language (English when no Context). */
    public static String durationModifierLabel(Context c, int seconds) {
        int safe = Math.max(1, seconds);
        if (c == null) return durationModifierLabel(safe);
        int single = DurationParser.naturalSingleUnit(safe);
        if (single > 0) {
            int count = safe / single;
            int res = single == 3600 ? R.plurals.rcat_mod_hours
                    : single == 60 ? R.plurals.rcat_mod_minutes : R.plurals.rcat_mod_seconds;
            return c.getResources().getQuantityString(res, count, count);
        }
        StringBuilder out = new StringBuilder();
        modPart(c, out, safe / 3600, R.plurals.rcat_modpart_hours);
        modPart(c, out, (safe % 3600) / 60, R.plurals.rcat_modpart_minutes);
        modPart(c, out, safe % 60, R.plurals.rcat_modpart_seconds);
        return c.getString(R.string.rcat_mod_span, out.toString());
    }

    private static void modPart(Context c, StringBuilder out, int count, int res) {
        if (count <= 0) return;
        if (out.length() > 0) out.append(' ');
        out.append(c.getResources().getQuantityString(res, count, count));
    }

    /**
     * The grammar rule itself, for callers that already know the count and unit the user said and
     * must not restate it in different terms. Kept beside {@link #durationLabel(int)} so the two
     * forms cannot drift apart again in separate files.
     *
     * @param count        how many of the unit
     * @param singularUnit the unit in its singular form: "second", "minute", "hour"
     */
    public static String durationModifier(long count, String singularUnit) {
        return count + "-" + singularUnit;
    }

    private static String clean(String s) {
        return s == null ? "" : s.trim().replaceAll("\\s+", " ");
    }

    private static boolean validReference(String value, int maxLength) {
        String clean = value == null ? "" : value.trim();
        return !clean.isEmpty() && clean.length() <= maxLength &&
                clean.matches("[a-z0-9][a-z0-9._-]+");
    }

    private static String titleCase(Context c, String s) {
        if (s == null || s.trim().isEmpty()) return t(c, R.string.rcat_action, "Action");
        String[] parts = s.toLowerCase(Locale.US).trim().split("\\s+");
        StringBuilder out = new StringBuilder();
        for (String part : parts) {
            if (part.isEmpty()) continue;
            if (out.length() > 0) out.append(' ');
            out.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return out.toString();
    }
}
