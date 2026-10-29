package com.orbit.assistant;

import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.File;
import java.io.PrintWriter;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Opens each screen with the phone set to Russian and lists the visible text that still contains
 * English words. It is a coverage scan, not a behaviour test: set the environment variable {@code ORBIT_RUSCAN_REPORT} to a file path
 * to get the full list; without it the test only checks that the main screens come up in Russian.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {35}, qualifiers = "ru")
public final class RussianInterfaceScanTest {
    private static final Pattern LATIN_WORD = Pattern.compile("[A-Za-z]{3,}");
    private static final Set<String> BRAND = new LinkedHashSet<>(java.util.Arrays.asList(
            "orbit", "chatgpt", "gpt", "codex", "openai", "anthropic", "claude", "xai", "grok", "openrouter",
            "samsung", "android", "wifi", "bluetooth", "https", "http", "api", "url", "json", "mcp", "ok",
            "amoled", "gmt", "utc", "pdf", "sol", "luna", "terra", "astra", "local", "sonnet", "opus", "haiku",
            "fable", "qwen", "llama", "gemini", "mistral", "deepseek", "meta", "google", "pro", "max", "mini",
            "nano", "flash", "lite", "turbo", "high", "low", "medium", "minimal", "xhigh", "mb", "gb", "kb",
            "ms", "sdk", "ocr", "ai", "tts", "pin", "gps", "sms", "nfc", "dnd", "usb", "app", "apk", "sha",
            "lelo", "times", "new", "roman", "vpn", "dns", "gif", "png", "jpg", "jpeg", "csv", "txt", "md"));

    private static final Class<?>[] SCREENS = {
            MainActivity.class, SettingsActivity.class, OnboardingActivity.class, ChatActivity.class,
            RoutinesActivity.class, RoutineTemplatesActivity.class, RoutineEditorActivity.class,
            RoutineBuilderActivity.class, RoutineRunHistoryActivity.class, RoutineTriggersActivity.class,
            TimeTriggerEditorActivity.class, LocationTriggerEditorActivity.class, DiagnosticsActivity.class,
            ThemeStudioActivity.class, DeckActivity.class, OrbitVaultActivity.class, SmartVaultActivity.class,
            ExtensionsActivity.class, LocalAiActivity.class, MemoryActivity.class, NotificationsActivity.class,
            AppsActivity.class, UpdateActivity.class, RoadmapActivity.class, WhatsNewActivity.class,
            CapabilitiesActivity.class, AiProvidersActivity.class, RemindersActivity.class,
            SavedPlacesActivity.class, CustomCommandsActivity.class, CustomCommandEditorActivity.class,
            AttachmentPickerActivity.class, OrbitVaultPickerActivity.class, OrbitWidgetConfigureActivity.class,
            ShareToOrbitActivity.class, ScreenSelectionActivity.class, OrbitLaunchSequenceActivity.class,
    };

    @Test public void screensComeUpInRussian() throws Exception {
        String report = System.getenv("ORBIT_RUSCAN_REPORT");
        PrintWriter out = report == null ? null : new PrintWriter(new File(report), "UTF-8");
        TestWorkManager.ensureInitialized(org.robolectric.RuntimeEnvironment.getApplication());
        int russianScreens = 0;
        for (Class<?> screen : SCREENS) {
            Set<String> texts = new LinkedHashSet<>();
            try {
                Activity activity = (Activity) Robolectric.buildActivity(screen.asSubclass(Activity.class))
                        .setup().get();
                collect(activity.getWindow().getDecorView(), texts);
            } catch (Throwable t) {
                if (out != null) out.println("## " + screen.getSimpleName() + " FAILED TO OPEN: " + t);
                continue;
            }
            boolean cyrillic = false;
            Set<String> english = new LinkedHashSet<>();
            for (String text : texts) {
                if (text.matches("(?s).*[А-Яа-я].*")) cyrillic = true;
                if (hasEnglish(text)) english.add(text);
            }
            if (cyrillic) russianScreens++;
            if (out != null) {
                out.println("## " + screen.getSimpleName() + " texts=" + texts.size() + " cyrillic=" + cyrillic
                        + " english=" + english.size());
                for (String text : english) out.println("   " + text.replace('\n', '/'));
            }
        }
        if (out != null) out.close();
        assertTrue("at least the main screens render Russian text", russianScreens >= 5);
    }

    static boolean hasEnglish(String text) {
        Matcher m = LATIN_WORD.matcher(text);
        while (m.find()) if (!BRAND.contains(m.group().toLowerCase(java.util.Locale.ROOT))) return true;
        return false;
    }

    private static void collect(View view, Set<String> out) {
        CharSequence cd = view.getContentDescription();
        if (cd != null && cd.length() > 0) out.add(cd.toString());
        if (view instanceof TextView) {
            TextView tv = (TextView) view;
            if (tv.getText() != null && tv.getText().length() > 0) out.add(tv.getText().toString());
            if (tv.getHint() != null && tv.getHint().length() > 0) out.add(tv.getHint().toString());
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) collect(group.getChildAt(i), out);
        }
    }
}
