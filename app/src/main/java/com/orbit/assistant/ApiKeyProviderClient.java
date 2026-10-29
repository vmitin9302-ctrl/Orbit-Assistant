package com.orbit.assistant;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BooleanSupplier;

/**
 * Official API-key transports for Anthropic Messages, xAI Chat Completions and, since
 * 0.8.3.0-beta.6, OpenRouter's OpenAI-compatible Chat Completions.
 *
 * <p>OpenRouter specifics, checked against its documentation on 2026-10-02: the stream may carry
 * {@code :} keep-alive comments (ignored), an error can arrive mid-stream with HTTP 200 as a chunk
 * carrying an {@code error} object and {@code finish_reason: "error"} (reported, never shown as an
 * answer), and closing the connection cancels the request and its billing on providers that
 * support it, so Stop really stops. Each chunk names the model that produced it; for OpenRouter
 * Auto that is the downstream model, which Response Details records. Orbit never invents one.
 */
final class ApiKeyProviderClient {
    private static final String ANTHROPIC_MESSAGES = "https://api.anthropic.com/v1/messages";
    private static final String XAI_CHAT = "https://api.x.ai/v1/chat/completions";
    static final String OPENROUTER_CHAT = "https://openrouter.ai/api/v1/chat/completions";
    /** OpenRouter's optional app attribution. Identifies Orbit, never the user. */
    static final String OPENROUTER_REFERER = "https://github.com/lpnovi/Orbit-Assistant";
    static final String OPENROUTER_TITLE = "Orbit Assistant";
    private static final ExecutorService EXEC = Executors.newCachedThreadPool();

    private ApiKeyProviderClient() {}

    static void send(Context c, String provider, String key, AiRequest request,
                     AssistantClient.Callback callback) {
        EXEC.execute(() -> doSend(c, provider, key, request, callback));
    }

    static void complete(Context c, String provider, String key, AiSelection selection,
                         String instructions, String prompt, AssistantClient.PlanCallback callback) {
        EXEC.execute(() -> {
            HttpURLConnection conn = null;
            try {
                JSONObject body = ProviderRequestMapper.simple(provider, selection, instructions, prompt);
                conn = open(provider, key, false);
                write(conn, body);
                int code = conn.getResponseCode();
                String raw = read(code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream());
                if (code < 200 || code >= 300) {
                    callback.onError(error(c, provider, code, raw, selection.model));
                    return;
                }
                String text = Prefs.PROVIDER_ANTHROPIC.equals(provider)
                        ? anthropicText(new JSONObject(raw)) : xaiText(new JSONObject(raw));
                if (text.trim().isEmpty()) callback.onError(c.getString(R.string.d_api_no_text, name(provider)));
                else callback.onText(text, name(provider) + " · " + selection.modelName());
            } catch (Exception e) {
                callback.onError(c.getString(R.string.d_api_unreachable, name(provider)));
            } finally {
                if (conn != null) conn.disconnect();
            }
        });
    }

    private static void doSend(Context c, String provider, String key, AiRequest request,
                               AssistantClient.Callback callback) {
        HttpURLConnection conn = null;
        boolean openRouter = Prefs.PROVIDER_OPENROUTER.equals(provider);
        try {
            AiModelSpec spec = OrbitModelCatalog.spec(provider, request.selection.model);
            if (spec == null || !spec.selectable()) {
                callback.onError(OrbitModelCatalog.unavailableMessage(request.selection.model));
                return;
            }
            JSONObject body = Prefs.PROVIDER_ANTHROPIC.equals(provider)
                    ? ProviderRequestMapper.anthropic(c, request, true)
                    : openRouter ? ProviderRequestMapper.openRouter(c, request, true)
                    : ProviderRequestMapper.xai(c, request, true);
            conn = open(provider, key, true);
            write(conn, body);
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                String raw = read(conn.getErrorStream());
                String friendly = error(c, provider, code, raw, request.selection.model);
                if (modelUnavailable(provider, code, raw, request.selection.model)) {
                    ModelAvailability.markUnavailable(c, provider, request.selection.model);
                }
                callback.onError(friendly);
                return;
            }
            ModelAvailability.markAvailable(c, provider, request.selection.model);
            if (request.thinkingUpdates) callback.onThinking(ThinkingUpdate.modelReasoning(request.selection.model));
            String[] servedBy = new String[]{""};
            String text;
            if (Prefs.PROVIDER_ANTHROPIC.equals(provider)) {
                text = readAnthropicStream(conn.getInputStream(), callback);
            } else if (openRouter) {
                text = readOpenRouterStream(conn.getInputStream(), callback, request.cancelled,
                        servedBy);
            } else {
                text = readXaiStream(conn.getInputStream(), callback);
            }
            if (openRouter && request.cancelled.getAsBoolean()) {
                callback.onError(c.getString(R.string.d_api_stopped));
                return;
            }
            if (text.trim().isEmpty()) {
                callback.onError(c.getString(R.string.d_api_no_assistant_text, name(provider)));
                return;
            }
            ResponseDetails details = ResponseDetails.sentWith(request.selection);
            if (openRouter && OrbitModelCatalog.OPENROUTER_AUTO.equals(request.selection.model)) {
                details = details.withServedBy(servedBy[0]);
            }
            callback.onSuccess(new AssistantReply(text.replace("—", "-"), new ArrayList<>())
                    .withDetails(details));
        } catch (StreamError e) {
            callback.onError(error(c, provider, e.code, e.getMessage(), request.selection.model));
        } catch (Exception e) {
            callback.onError(c.getString(R.string.d_api_unreachable, name(provider)));
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static HttpURLConnection open(String provider, String key, boolean stream) throws Exception {
        boolean anthropic = Prefs.PROVIDER_ANTHROPIC.equals(provider);
        boolean openRouter = Prefs.PROVIDER_OPENROUTER.equals(provider);
        HttpURLConnection conn = (HttpURLConnection) new URL(anthropic ? ANTHROPIC_MESSAGES
                : openRouter ? OPENROUTER_CHAT : XAI_CHAT).openConnection();
        conn.setRequestMethod("POST");
        conn.setInstanceFollowRedirects(false);
        conn.setConnectTimeout(15_000);
        conn.setReadTimeout(240_000);
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        conn.setRequestProperty("Accept", stream ? "text/event-stream" : "application/json");
        if (anthropic) {
            conn.setRequestProperty("x-api-key", key);
            conn.setRequestProperty("anthropic-version", "2023-06-01");
        } else {
            conn.setRequestProperty("Authorization", "Bearer " + key);
        }
        if (openRouter) {
            conn.setRequestProperty("HTTP-Referer", OPENROUTER_REFERER);
            conn.setRequestProperty("X-Title", OPENROUTER_TITLE);
        }
        return conn;
    }

    private static void write(HttpURLConnection conn, JSONObject body) throws Exception {
        byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
        conn.setFixedLengthStreamingMode(bytes.length);
        try (OutputStream out = conn.getOutputStream()) { out.write(bytes); }
    }

    static String readAnthropicStream(InputStream input, AssistantClient.Callback callback) throws Exception {
        StringBuilder full = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.startsWith("data:")) continue;
                String data = line.substring(5).trim();
                if (data.isEmpty()) continue;
                JSONObject event;
                try { event = new JSONObject(data); } catch (Exception ignored) { continue; }
                if ("error".equals(event.optString("type"))) {
                    JSONObject error = event.optJSONObject("error");
                    throw new IllegalStateException(error == null ? "stream error" : error.optString("type"));
                }
                JSONObject delta = event.optJSONObject("delta");
                if (delta != null && "text_delta".equals(delta.optString("type"))) {
                    full.append(delta.optString("text", ""));
                    callback.onDelta(full.toString().replace("—", "-"));
                }
            }
        }
        return full.toString();
    }

    static String readXaiStream(InputStream input, AssistantClient.Callback callback) throws Exception {
        StringBuilder full = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.startsWith("data:")) continue;
                String data = line.substring(5).trim();
                if (data.isEmpty() || "[DONE]".equals(data)) continue;
                JSONObject event;
                try { event = new JSONObject(data); } catch (Exception ignored) { continue; }
                JSONArray choices = event.optJSONArray("choices");
                JSONObject choice = choices == null ? null : choices.optJSONObject(0);
                JSONObject delta = choice == null ? null : choice.optJSONObject("delta");
                if (delta != null && delta.has("content")) {
                    full.append(delta.optString("content", ""));
                    callback.onDelta(full.toString().replace("—", "-"));
                }
            }
        }
        return full.toString();
    }

    /** A failure OpenRouter reported inside an HTTP 200 stream. The message is bounded. */
    static final class StreamError extends Exception {
        final int code;

        StreamError(int code, String message) {
            super(message == null ? "" : message.length() > 300 ? message.substring(0, 300) : message);
            this.code = code;
        }
    }

    /**
     * Reads OpenRouter's stream. Comment lines (keep-alives) and {@code [DONE]} are skipped, only
     * {@code delta.content} becomes answer text (reasoning deltas are never shown or stored), the
     * first reported {@code model} is kept in {@code servedBy[0]}, and a chunk carrying an
     * {@code error} ends the read with a {@link StreamError}. Returns early, with what has arrived,
     * once {@code cancelled} reports true; the caller then closes the connection.
     */
    static String readOpenRouterStream(InputStream input, AssistantClient.Callback callback,
                                       BooleanSupplier cancelled, String[] servedBy)
            throws Exception {
        StringBuilder full = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (cancelled != null && cancelled.getAsBoolean()) break;
                if (!line.startsWith("data:")) continue;
                String data = line.substring(5).trim();
                if (data.isEmpty() || "[DONE]".equals(data)) continue;
                JSONObject event;
                try { event = new JSONObject(data); } catch (Exception ignored) { continue; }
                JSONObject error = event.optJSONObject("error");
                if (error != null) {
                    throw new StreamError(error.optInt("code", 0), error.optString("message", ""));
                }
                String model = event.optString("model", "").trim();
                if (servedBy != null && servedBy[0].isEmpty() && !model.isEmpty()
                        && model.length() <= 120) {
                    servedBy[0] = model;
                }
                JSONArray choices = event.optJSONArray("choices");
                JSONObject choice = choices == null ? null : choices.optJSONObject(0);
                JSONObject delta = choice == null ? null : choice.optJSONObject("delta");
                if (delta != null && delta.has("content") && !delta.isNull("content")) {
                    String piece = delta.optString("content", "");
                    if (!piece.isEmpty()) {
                        full.append(piece);
                        callback.onDelta(full.toString().replace("—", "-"));
                    }
                }
            }
        }
        return full.toString();
    }

    /** Whether an error means this account cannot reach this model right now. */
    static boolean modelUnavailable(String provider, int code, String raw, String model) {
        if (Prefs.PROVIDER_OPENROUTER.equals(provider)) {
            String lower = raw == null ? "" : raw.toLowerCase(java.util.Locale.US);
            if (lower.contains("no endpoints found that support")) return false;
            return code == 404 || OrbitModelCatalog.looksUnavailable(model, raw);
        }
        return code == 404 || OrbitModelCatalog.looksUnavailable(model, raw);
    }

    /** The message shown for a failed provider request, saying how OpenRouter was connected. */
    static String error(Context c, String provider, int code, String raw, String model) {
        if (Prefs.PROVIDER_OPENROUTER.equals(provider) && (code == 401)) {
            boolean oauth = c != null && SecureStore.OPENROUTER_SOURCE_OAUTH.equals(
                    SecureStore.openRouterKeySource(c));
            return oauth
                    ? c.getString(R.string.d_api_openrouter_revoked)
                    : c.getString(R.string.d_api_openrouter_key_invalid);
        }
        String lower = raw == null ? "" : raw.toLowerCase(java.util.Locale.US);
        if (Prefs.PROVIDER_OPENROUTER.equals(provider)) {
            String openRouter = openRouterError(c, code, lower, model);
            if (openRouter != null) return openRouter;
        }
        if (code == 401 || code == 403) return c.getString(R.string.d_api_key_invalid, name(provider));
        if (code == 404 || OrbitModelCatalog.looksUnavailable(model, raw))
            return OrbitModelCatalog.unavailableMessage(model);
        if (code == 413 || lower.contains("context") && (lower.contains("long") || lower.contains("limit")))
            return c.getString(R.string.d_api_too_large, modelLabel(c, model));
        if (code == 429) return c.getString(R.string.d_api_rate_limit, name(provider));
        if (code == 529 || lower.contains("overloaded")) return c.getString(R.string.d_api_overloaded, name(provider));
        if (code >= 500) return c.getString(R.string.d_api_server_problem, name(provider));
        if (lower.contains("image") || lower.contains("unsupported"))
            return c.getString(R.string.d_api_unsupported_attachment);
        return c.getString(R.string.d_api_rejected, name(provider));
    }

    /**
     * OpenRouter's documented status codes, in plain words. Null hands over to the shared rules.
     * Never quotes the server's body back to the user.
     */
    private static String openRouterError(Context c, int code, String lower, String model) {
        if (code == 401) {
            return c.getString(R.string.d_api_or_rejected);
        }
        if (code == 402 || lower.contains("insufficient credits") || lower.contains("more credits")) {
            return c.getString(R.string.d_api_or_credits);
        }
        if (lower.contains("no endpoints found that support")) {
            return c.getString(R.string.d_api_or_attachment, modelLabel(c, model));
        }
        if (code == 403) {
            return c.getString(R.string.d_api_or_declined);
        }
        if (code == 408) return c.getString(R.string.d_api_or_timeout);
        if (code == 413 || lower.contains("context length") || lower.contains("maximum context")
                || lower.contains("too many tokens") || lower.contains("context window")) {
            return c.getString(R.string.d_api_or_too_large, modelLabel(c, model));
        }
        if (code == 429) return c.getString(R.string.d_api_or_rate_limit);
        if (code == 502) {
            return c.getString(R.string.d_api_or_down, modelLabel(c, model));
        }
        if (code == 503) {
            return c.getString(R.string.d_api_or_no_provider, modelLabel(c, model));
        }
        return null;
    }

    private static String modelLabel(Context c, String model) {
        String name = OrbitModelCatalog.displayName(model);
        return name.isEmpty() ? (model == null || model.isEmpty()
                ? c.getString(R.string.d_api_this_model) : model) : name;
    }

    private static String anthropicText(JSONObject root) {
        JSONArray content = root.optJSONArray("content");
        StringBuilder out = new StringBuilder();
        if (content != null) for (int i = 0; i < content.length(); i++) {
            JSONObject block = content.optJSONObject(i);
            if (block != null && "text".equals(block.optString("type"))) out.append(block.optString("text"));
        }
        return out.toString();
    }

    private static String xaiText(JSONObject root) {
        JSONArray choices = root.optJSONArray("choices");
        JSONObject choice = choices == null ? null : choices.optJSONObject(0);
        JSONObject message = choice == null ? null : choice.optJSONObject("message");
        return message == null ? "" : message.optString("content", "");
    }

    private static String read(InputStream input) throws Exception {
        if (input == null) return "";
        StringBuilder out = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null && out.length() < 200_000) out.append(line);
        }
        return out.toString();
    }

    static String name(String provider) {
        if (Prefs.PROVIDER_ANTHROPIC.equals(provider)) return "Anthropic";
        if (Prefs.PROVIDER_OPENROUTER.equals(provider)) return "OpenRouter";
        return "xAI";
    }
}
