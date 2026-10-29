package com.orbit.assistant;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Source-reading tests assert on the English wording that used to sit in the Java files. Since the
 * wording lives in {@code res/values} (so Russian can sit beside it), a test that reads a source
 * file sees {@code getString(R.string.name)} instead of the literal. {@link #expand} puts the
 * English literal back in place of each {@code R.string.name}, so those assertions keep checking
 * what Orbit says in English without caring which file it is stored in.
 */
final class LocalizedSource {
    private static final Pattern ITEM = Pattern.compile(
            "<string\\s+name=\"([^\"]+)\"[^>]*>(.*?)</string>", Pattern.DOTALL);
    private static final Pattern REF = Pattern.compile(
            "(?:com\\.orbit\\.assistant\\.)?R\\.string\\.([A-Za-z0-9_]+)");
    private static Map<String, String> english;

    private LocalizedSource() {}

    static String expand(String source) {
        if (source == null || !source.contains("R.string.")) return source;
        Map<String, String> strings = english();
        Matcher m = REF.matcher(source);
        StringBuffer out = new StringBuffer();
        while (m.find()) {
            String text = strings.get(m.group(1));
            m.appendReplacement(out, Matcher.quoteReplacement(
                    text == null ? m.group() : quote(text)));
        }
        m.appendTail(out);
        return out.toString();
    }

    private static String quote(String text) {
        return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
    }

    private static synchronized Map<String, String> english() {
        if (english != null) return english;
        Map<String, String> map = new HashMap<>();
        File dir = new File("src/main/res/values");
        if (!dir.isDirectory()) dir = new File("app/src/main/res/values");
        File[] files = dir.listFiles((d, name) -> name.endsWith(".xml"));
        if (files != null) {
            for (File file : files) {
                try {
                    String xml = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
                    Matcher m = ITEM.matcher(xml);
                    while (m.find()) map.put(m.group(1), unescape(m.group(2)));
                } catch (IOException e) {
                    throw new AssertionError("could not read " + file, e);
                }
            }
        }
        english = map;
        return map;
    }

    /** Resource text to the runtime string Android would produce (quotes, escapes, entities). */
    private static String unescape(String raw) {
        String s = raw.trim();
        if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) s = s.substring(1, s.length() - 1);
        s = s.replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&");
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char n = s.charAt(++i);
                out.append(n == 'n' ? '\n' : n == 't' ? '\t' : n);
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }
}
