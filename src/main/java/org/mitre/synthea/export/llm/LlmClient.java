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
 * <p>Responses are cached on disk (keyed by a hash of model + temperature + seed + prompts) so
 * re-running a simulation with the same seed does not re-incur API cost and produces stable
 * output. Requests use {@code temperature=0} and a deterministic {@code seed} by default to keep
 * generation as reproducible as the provider allows.
 */
public class LlmClient {

  private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
  private static final int MAX_RETRIES = 4;

  private final String apiKey;
  private final String baseUrl;
  private final String model;
  private final double temperature;
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
    this.temperature = Config.getAsDouble("exporter.llm.temperature", 0.0);
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
   * @param seed         deterministic seed for the request (provider best-effort)
   * @return the generated text, or null if the request failed after retries
   */
  public String complete(String systemPrompt, String userPrompt, int seed) {
    String cacheKey = hash(model + "\n" + temperature + "\n" + seed + "\n"
        + systemPrompt + "\n" + userPrompt);
    String cached = readCache(cacheKey);
    if (cached != null) {
      return cached;
    }

    JsonArray messages = new JsonArray();
    messages.add(message("system", systemPrompt));
    messages.add(message("user", userPrompt));

    JsonObject payload = new JsonObject();
    payload.addProperty("model", model);
    payload.addProperty("temperature", temperature);
    payload.addProperty("seed", seed);
    payload.add("messages", messages);

    String result = requestWithRetries(payload.toString());
    if (result != null) {
      writeCache(cacheKey, result);
    }
    return result;
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
          return parseContent(response.body().string());
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
