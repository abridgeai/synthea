package org.mitre.synthea.export.llm;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import org.mitre.synthea.helpers.Config;

/**
 * Minimal client for an OpenAI-compatible Chat Completions API, used by the LLM-backed
 * exporters ({@link LlmClinicalNoteExporter} and {@link EncounterTranscriptExporter}).
 *
 * <p>The API key is read from the {@code OPENAI_API_KEY} environment variable, falling back to
 * the {@code exporter.llm.api_key} config property. The key is never read from the committed
 * {@code synthea.properties}; keep it in the environment or a local, git-ignored properties file.
 *
 * <p>Responses are cached on disk (keyed by a hash of model + prompts) so re-running a simulation
 * with identical prompts does not re-incur API cost. Requests send no {@code temperature} or
 * {@code seed}, so the provider's natural (non-deterministic) variation is preserved.
 *
 * <p>API call and token-usage counts are tracked in static counters across all instances for the
 * duration of a run, and reported by {@link LlmStatsExporter} after generation completes.
 */
public class LlmClient {

  private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
  private static final int MAX_RETRIES = 4;

  private static final AtomicLong API_CALLS = new AtomicLong();
  private static final AtomicLong PROMPT_TOKENS = new AtomicLong();
  private static final AtomicLong COMPLETION_TOKENS = new AtomicLong();
  private static final AtomicLong TOTAL_TOKENS = new AtomicLong();

  private final String apiKey;
  private final String baseUrl;
  private final String model;
  private final boolean cacheEnabled;
  private final Path cacheDir;
  private final OkHttpClient http;

  /**
   * Construct a client configured from {@code exporter.llm.*} properties and the environment.
   */
  public LlmClient() {
    String envKey = System.getenv("OPENAI_API_KEY");
    this.apiKey = (envKey != null && !envKey.isBlank())
        ? envKey
        : Config.get("exporter.llm.api_key", null);
    this.baseUrl = stripTrailingSlash(
        Config.get("exporter.llm.base_url", "https://api.openai.com/v1"));
    this.model = Config.get("exporter.llm.model", "gpt-4o");
    this.cacheEnabled = Config.getAsBoolean("exporter.llm.cache", true);
    this.cacheDir = Paths.get(Config.get("exporter.llm.cache_dir", "./output/llm_cache"));
    long timeout = Config.getAsLong("exporter.llm.timeout_seconds", 120);
    this.http = new OkHttpClient.Builder()
        .connectTimeout(timeout, TimeUnit.SECONDS)
        .readTimeout(timeout, TimeUnit.SECONDS)
        .writeTimeout(timeout, TimeUnit.SECONDS)
        .build();
  }

  /**
   * Whether the client has an API key and can make requests. When false, the exporters log a
   * warning once and skip generation rather than failing the simulation.
   *
   * @return true if an API key is configured
   */
  public boolean isConfigured() {
    return apiKey != null && !apiKey.isBlank();
  }

  /**
   * Generate a completion for the given system and user prompts.
   *
   * @param systemPrompt instructions describing the role/output
   * @param userPrompt   the structured encounter content
   * @return the generated text, or null if the request failed after retries
   */
  public String complete(String systemPrompt, String userPrompt) {
    String cacheKey = hash(model + "\n" + systemPrompt + "\n" + userPrompt);
    String cached = readCache(cacheKey);
    if (cached != null) {
      return cached;
    }

    JsonArray messages = new JsonArray();
    messages.add(message("system", systemPrompt));
    messages.add(message("user", userPrompt));

    JsonObject payload = new JsonObject();
    payload.addProperty("model", model);
    payload.add("messages", messages);

    String result = requestWithRetries(payload.toString());
    if (result != null) {
      writeCache(cacheKey, result);
    }
    return result;
  }

  /** Total successful API calls made this run (cache hits are not counted). */
  public static long getApiCallCount() {
    return API_CALLS.get();
  }

  /** Total prompt (input) tokens reported by the API this run. */
  public static long getPromptTokens() {
    return PROMPT_TOKENS.get();
  }

  /** Total completion (output) tokens reported by the API this run. */
  public static long getCompletionTokens() {
    return COMPLETION_TOKENS.get();
  }

  /** Total tokens reported by the API this run. */
  public static long getTotalTokens() {
    return TOTAL_TOKENS.get();
  }

  /** Reset the run-level API call and token counters. */
  public static void resetStats() {
    API_CALLS.set(0);
    PROMPT_TOKENS.set(0);
    COMPLETION_TOKENS.set(0);
    TOTAL_TOKENS.set(0);
  }

  private String requestWithRetries(String body) {
    IOException lastError = null;
    for (int attempt = 0; attempt < MAX_RETRIES; attempt++) {
      Request request = new Request.Builder()
          .url(baseUrl + "/chat/completions")
          .header("Authorization", "Bearer " + apiKey)
          .post(RequestBody.create(body, JSON))
          .build();
      try (Response response = http.newCall(request).execute()) {
        if (response.isSuccessful() && response.body() != null) {
          String responseBody = response.body().string();
          API_CALLS.incrementAndGet();
          recordUsage(responseBody);
          return parseContent(responseBody);
        }
        // Retry on rate limiting and transient server errors; fail fast otherwise.
        if (response.code() != 429 && response.code() < 500) {
          System.err.println("LlmClient: request failed (HTTP " + response.code() + "): "
              + (response.body() != null ? response.body().string() : ""));
          return null;
        }
      } catch (IOException e) {
        lastError = e;
      }
      backoff(attempt);
    }
    System.err.println("LlmClient: giving up after " + MAX_RETRIES + " attempts"
        + (lastError != null ? " (" + lastError.getMessage() + ")" : ""));
    return null;
  }

  private static JsonObject message(String role, String content) {
    JsonObject msg = new JsonObject();
    msg.addProperty("role", role);
    msg.addProperty("content", content);
    return msg;
  }

  private static String parseContent(String responseBody) {
    JsonObject root = JsonParser.parseString(responseBody).getAsJsonObject();
    JsonArray choices = root.getAsJsonArray("choices");
    if (choices == null || choices.size() == 0) {
      return null;
    }
    return choices.get(0).getAsJsonObject()
        .getAsJsonObject("message")
        .get("content").getAsString();
  }

  /**
   * Parse the {@code usage} object (if present) and add its token counts to the run totals.
   */
  private static void recordUsage(String responseBody) {
    try {
      JsonObject root = JsonParser.parseString(responseBody).getAsJsonObject();
      if (!root.has("usage") || root.get("usage").isJsonNull()) {
        return;
      }
      JsonObject usage = root.getAsJsonObject("usage");
      PROMPT_TOKENS.addAndGet(longField(usage, "prompt_tokens"));
      COMPLETION_TOKENS.addAndGet(longField(usage, "completion_tokens"));
      TOTAL_TOKENS.addAndGet(longField(usage, "total_tokens"));
    } catch (RuntimeException e) {
      // Usage accounting is best-effort and must never disrupt generation.
    }
  }

  private static long longField(JsonObject obj, String field) {
    return (obj.has(field) && !obj.get(field).isJsonNull()) ? obj.get(field).getAsLong() : 0L;
  }

  private static void backoff(int attempt) {
    try {
      Thread.sleep((long) (Math.pow(2, attempt) * 1000));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private String readCache(String key) {
    if (!cacheEnabled) {
      return null;
    }
    Path file = cacheDir.resolve(key + ".txt");
    if (Files.exists(file)) {
      try {
        return Files.readString(file, StandardCharsets.UTF_8);
      } catch (IOException e) {
        return null;
      }
    }
    return null;
  }

  private void writeCache(String key, String value) {
    if (!cacheEnabled) {
      return;
    }
    try {
      Files.createDirectories(cacheDir);
      Files.writeString(cacheDir.resolve(key + ".txt"), value, StandardCharsets.UTF_8);
    } catch (IOException e) {
      // Caching is best-effort; a write failure should not stop generation.
    }
  }

  private static String hash(String input) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] bytes = digest.digest(input.getBytes(StandardCharsets.UTF_8));
      StringBuilder sb = new StringBuilder();
      for (byte b : bytes) {
        sb.append(String.format("%02x", b));
      }
      return sb.toString();
    } catch (Exception e) {
      return Integer.toHexString(input.hashCode());
    }
  }

  private static String stripTrailingSlash(String url) {
    return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
  }
}
