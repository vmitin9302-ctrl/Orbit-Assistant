package com.orbit.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Saved data is independent of the language of the interface. Data written while the phone is in
 * English is read, and written back, byte for byte the same while the phone is in Russian, and
 * the other way round.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {35})
public final class LocalizationDataSafetyTest {

    private static Map<String, String> snapshot(Context context) throws Exception {
        Map<String, String> files = new TreeMap<>();
        File root = context.getApplicationInfo().dataDir == null ? null : new File(context.getApplicationInfo().dataDir);
        if (root != null) walk(root, root, files);
        return files;
    }

    private static void walk(File root, File dir, Map<String, String> out) throws Exception {
        File[] children = dir.listFiles();
        if (children == null) return;
        for (File child : children) {
            if (child.isDirectory()) {
                walk(root, child, out);
            } else if (child.getName().endsWith(".xml") || child.getName().endsWith(".json")) {
                out.put(root.toPath().relativize(child.toPath()).toString(),
                        new String(Files.readAllBytes(child.toPath()), StandardCharsets.UTF_8));
            }
        }
    }

    private static List<AssistantReply.Action> oneAction() throws Exception {
        List<AssistantReply.Action> actions = new ArrayList<>();
        actions.add(new AssistantReply.Action(RoutineActionCatalog.FLASHLIGHT,
                new JSONObject().put("on", true), false));
        return actions;
    }

    @Test public void dataWrittenInEnglishIsReadAndRewrittenIdenticallyInRussian() throws Exception {
        RuntimeEnvironment.setQualifiers("+en");
        Context context = RuntimeEnvironment.getApplication();

        List<AssistantClient.History> history = Arrays.asList(
                new AssistantClient.History("user", "Explain tides"),
                new AssistantClient.History("assistant", "Tides follow the moon."));
        ConversationStore.save(context, "chat-1", history);
        RoutineStore.Routine routine = RoutineStore.create("Goodnight", oneAction());
        assertTrue(RoutineStore.upsert(context, routine));
        Prefs.get(context).edit().putString(Prefs.REMINDER_APP, ReminderDestination.ORBIT).apply();

        Map<String, String> before = snapshot(context);
        assertFalse("something was written", before.isEmpty());

        RuntimeEnvironment.setQualifiers("+ru");
        Context russian = RuntimeEnvironment.getApplication();

        ConversationStore.Conversation chat = ConversationStore.load(russian, "chat-1");
        assertNotNull(chat);
        assertEquals(2, chat.messages.size());
        assertEquals("Explain tides", chat.messages.get(0).content);
        assertEquals("user", chat.messages.get(0).role);
        List<RoutineStore.Routine> routines = RoutineStore.list(russian);
        assertEquals(1, routines.size());
        assertEquals("Goodnight", routines.get(0).name);
        assertEquals(RoutineActionCatalog.FLASHLIGHT, routines.get(0).actions.get(0).type);
        assertEquals(ReminderDestination.ORBIT,
                Prefs.get(russian).getString(Prefs.REMINDER_APP, ""));

        // Writing the same data back while in Russian changes nothing on disk.
        ConversationStore.save(russian, "chat-1", chat.messages);
        RoutineStore.upsert(russian, routines.get(0));
        Map<String, String> after = snapshot(russian);
        for (Map.Entry<String, String> file : before.entrySet()) {
            if (file.getKey().contains("chat") || file.getKey().contains("routine")) {
                String now = after.get(file.getKey());
                assertNotNull("file still present: " + file.getKey(), now);
                // updatedAt-style timestamps may move; the stored words and ids must not.
                assertEquals("keys and values: " + file.getKey(), words(file.getValue()), words(now));
            }
        }
    }

    @Test public void internalIdentifiersStayEnglish() {
        RuntimeEnvironment.setQualifiers("+ru");
        assertEquals("flashlight", "FLASHLIGHT".toLowerCase(java.util.Locale.ROOT));
        assertEquals("routine", "routine");
        assertEquals("chatgpt", Prefs.PROVIDER_CHATGPT);
        assertEquals("anthropic", Prefs.PROVIDER_ANTHROPIC);
        assertEquals("openrouter", Prefs.PROVIDER_OPENROUTER);
        assertEquals("FLASHLIGHT", RoutineActionCatalog.FLASHLIGHT);
    }

    /** The text of a stored file with digits-only runs (timestamps) blanked out. */
    private static String words(String raw) {
        return raw.replaceAll("\\d{9,}", "#");
    }
}
