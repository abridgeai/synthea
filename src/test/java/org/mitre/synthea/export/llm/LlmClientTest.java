package org.mitre.synthea.export.llm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mitre.synthea.helpers.Config;

public class LlmClientTest {

  private MockWebServer server;
  private Path cacheDir;

  private static final String SUCCESS_BODY =
      "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"Generated note.\"}}],"
      + "\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":5,\"total_tokens\":15}}";

  /** Start a mock server, point the LLM config at it, and reset usage counters before each test. */
  @Before
  public void setup() throws Exception {
    server = new MockWebServer();
    server.start();
    cacheDir = Files.createTempDirectory("llm-cache-test");
    Config.set("exporter.llm.api_key", "test-key");
    Config.set("exporter.llm.base_url", server.url("/v1").toString());
    Config.set("exporter.llm.model", "test-model");
    Config.set("exporter.llm.cache", "false");
    Config.set("exporter.llm.cache_dir", cacheDir.toString());
    LlmClient.resetStats();
  }

  @After
  public void tearDown() throws Exception {
    server.shutdown();
  }

  @Test
  public void completeParsesContentAndSendsRequest() throws Exception {
    server.enqueue(new MockResponse().setBody(SUCCESS_BODY));

    LlmClient client = new LlmClient();
    assertTrue(client.isConfigured());

    String result = client.complete("system prompt", "user prompt");
    assertEquals("Generated note.", result);

    RecordedRequest request = server.takeRequest();
    assertEquals("/v1/chat/completions", request.getPath());
    assertEquals("Bearer test-key", request.getHeader("Authorization"));
    String body = request.getBody().readUtf8();
    assertTrue(body.contains("\"model\":\"test-model\""));
    assertTrue(body.contains("system prompt"));
    assertTrue(body.contains("user prompt"));
    // temperature and seed are intentionally not sent.
    assertTrue(!body.contains("temperature"));
    assertTrue(!body.contains("seed"));

    // Usage counters reflect the single call.
    assertEquals(1, LlmClient.getApiCallCount());
    assertEquals(10, LlmClient.getPromptTokens());
    assertEquals(5, LlmClient.getCompletionTokens());
    assertEquals(15, LlmClient.getTotalTokens());
  }

  @Test
  public void cacheAvoidsSecondRequestAndIsNotCounted() throws Exception {
    Config.set("exporter.llm.cache", "true");
    server.enqueue(new MockResponse().setBody(SUCCESS_BODY));

    LlmClient client = new LlmClient();
    String first = client.complete("sys", "user");
    String second = client.complete("sys", "user");

    assertEquals("Generated note.", first);
    assertEquals("Generated note.", second);
    // Only one HTTP request was made; the cache hit is not counted as an API call.
    assertEquals(1, server.getRequestCount());
    assertEquals(1, LlmClient.getApiCallCount());
  }

  @Test
  public void notConfiguredWithoutKey() {
    Config.set("exporter.llm.api_key", "");
    // Env var may still provide a key; only assert behavior when none is present.
    if (System.getenv("OPENAI_API_KEY") == null) {
      LlmClient client = new LlmClient();
      assertTrue(!client.isConfigured());
    }
  }

  @Test
  public void returnsNullOnClientError() {
    server.enqueue(new MockResponse().setResponseCode(400).setBody("{\"error\":\"bad\"}"));

    LlmClient client = new LlmClient();
    String result = client.complete("sys", "user");
    assertNull(result);
    // A failed request is not counted as a successful API call.
    assertEquals(0, LlmClient.getApiCallCount());
  }
}
