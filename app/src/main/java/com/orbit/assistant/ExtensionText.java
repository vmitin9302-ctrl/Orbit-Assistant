package com.orbit.assistant;

import android.content.Context;
import android.content.res.Configuration;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Display text for the extensions that ship inside Orbit. Their manifests are English data files
 * that also define the extension; this only changes the words drawn on screen, and only while the
 * installed manifest still says exactly what the bundled one says. Anything else, including an
 * edited copy or a third-party extension that reuses an id, is shown as written.
 */
final class ExtensionText {
    private static final Map<String, int[]> EXTENSIONS = new HashMap<>();
    private static final Map<String, int[]> ACTIONS = new HashMap<>();

    static {
        put("com.orbit.extensions.developer-tools", R.string.extb_developer_tools_name, R.string.extb_developer_tools_desc);
        putAction("com.orbit.extensions.developer-tools", "open-android-developers", R.string.extb_developer_tools_open_android_developers_name, R.string.extb_developer_tools_open_android_developers_desc);
        putAction("com.orbit.extensions.developer-tools", "open-github-actions-docs", R.string.extb_developer_tools_open_github_actions_docs_name, R.string.extb_developer_tools_open_github_actions_docs_desc);
        putAction("com.orbit.extensions.developer-tools", "test-public-https-get", R.string.extb_developer_tools_test_public_https_get_name, R.string.extb_developer_tools_test_public_https_get_desc);
        put("com.orbit.extensions.discord-webhook", R.string.extb_discord_webhook_name, R.string.extb_discord_webhook_desc);
        putAction("com.orbit.extensions.discord-webhook", "send-message", R.string.extb_discord_webhook_send_message_name, R.string.extb_discord_webhook_send_message_desc);
        put("com.orbit.extensions.ntfy", R.string.extb_ntfy_name, R.string.extb_ntfy_desc);
        putAction("com.orbit.extensions.ntfy", "send-notification", R.string.extb_ntfy_send_notification_name, R.string.extb_ntfy_send_notification_desc);
        put("com.orbit.extensions.web-tools", R.string.extb_web_tools_name, R.string.extb_web_tools_desc);
        putAction("com.orbit.extensions.web-tools", "open-orbit-releases", R.string.extb_web_tools_open_orbit_releases_name, R.string.extb_web_tools_open_orbit_releases_desc);
        putAction("com.orbit.extensions.web-tools", "open-orbit-repository", R.string.extb_web_tools_open_orbit_repository_name, R.string.extb_web_tools_open_orbit_repository_desc);
        putAction("com.orbit.extensions.web-tools", "open-orbit-issues", R.string.extb_web_tools_open_orbit_issues_name, R.string.extb_web_tools_open_orbit_issues_desc);
        put("com.orbit.extensions.quick-links", R.string.extb_quick_links_name, R.string.extb_quick_links_desc);
        putAction("com.orbit.extensions.quick-links", "open-web-search", R.string.extb_quick_links_open_web_search_name, R.string.extb_quick_links_open_web_search_desc);
        putAction("com.orbit.extensions.quick-links", "open-maps", R.string.extb_quick_links_open_maps_name, R.string.extb_quick_links_open_maps_desc);
        putAction("com.orbit.extensions.quick-links", "open-reference", R.string.extb_quick_links_open_reference_name, R.string.extb_quick_links_open_reference_desc);
    }

    private ExtensionText() {}

    private static void put(String id, int name, int description) {
        EXTENSIONS.put(id, new int[]{name, description});
    }

    private static void putAction(String id, String action, int name, int description) {
        ACTIONS.put(id + "#" + action, new int[]{name, description});
    }

    static String name(Context context, OrbitExtension extension) {
        return pick(context, EXTENSIONS.get(extension.id), 0, extension.name);
    }

    static String description(Context context, OrbitExtension extension) {
        return pick(context, EXTENSIONS.get(extension.id), 1, extension.description);
    }

    static String actionName(Context context, OrbitExtension extension, OrbitExtension.Action action) {
        return pick(context, ACTIONS.get(extension.id + "#" + action.id), 0, action.name);
    }

    static String actionDescription(Context context, OrbitExtension extension, OrbitExtension.Action action) {
        return pick(context, ACTIONS.get(extension.id + "#" + action.id), 1, action.description);
    }

    private static String pick(Context context, int[] ids, int which, String written) {
        if (ids == null || context == null) return written;
        Configuration english = new Configuration(context.getResources().getConfiguration());
        english.setLocale(Locale.ENGLISH);
        String original = context.createConfigurationContext(english).getString(ids[which]);
        return original.equals(written) ? context.getString(ids[which]) : written;
    }
}
