package org.mitre.synthea.export.llm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

import org.junit.Test;

public class FhirLlmNoteInjectorTest {

  /** A minimal transaction bundle: patient, two encounters, and the note resources for e1. */
  private static final String BUNDLE =
      "{\"resourceType\":\"Bundle\",\"type\":\"transaction\",\"entry\":["
      + "{\"fullUrl\":\"urn:uuid:pat\",\"resource\":{\"resourceType\":\"Patient\",\"id\":\"pat\"}},"
      + "{\"fullUrl\":\"urn:uuid:e0\",\"resource\":{\"resourceType\":\"Encounter\",\"id\":\"e0\","
      + "\"period\":{\"start\":\"2000-01-01T00:00:00Z\"}}},"
      + "{\"fullUrl\":\"urn:uuid:e1\",\"resource\":{\"resourceType\":\"Encounter\",\"id\":\"e1\","
      + "\"period\":{\"start\":\"2010-01-01T00:00:00Z\"}}},"
      + "{\"fullUrl\":\"urn:uuid:dr1\",\"resource\":{\"resourceType\":\"DiagnosticReport\","
      + "\"category\":[{\"coding\":[{\"system\":\"http://loinc.org\",\"code\":\"34117-2\"}]}],"
      + "\"encounter\":{\"reference\":\"urn:uuid:e1\"},"
      + "\"presentedForm\":[{\"contentType\":\"text/plain\",\"data\":\""
      + b64("OLD NOTE") + "\"}]}},"
      + "{\"fullUrl\":\"urn:uuid:doc1\",\"resource\":{\"resourceType\":\"DocumentReference\","
      + "\"type\":{\"coding\":[{\"system\":\"http://loinc.org\",\"code\":\"34117-2\"}]},"
      + "\"context\":{\"encounter\":[{\"reference\":\"urn:uuid:e1\"}]},"
      + "\"content\":[{\"attachment\":{\"contentType\":\"text/plain\",\"data\":\""
      + b64("OLD NOTE") + "\"}}]}}"
      + "]}";

  private static String b64(String s) {
    return Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_8));
  }

  private static String decode(String b64) {
    return new String(Base64.getDecoder().decode(b64), StandardCharsets.UTF_8);
  }

  @Test
  public void replacesNoteAndAddsTranscript() {
    JsonObject root = JsonParser.parseString(BUNDLE).getAsJsonObject();
    Map<Integer, String> notes = new HashMap<>();
    notes.put(1, "NEW LLM NOTE");          // index 1 == encounter e1 (later start)
    Map<Integer, String> transcripts = new HashMap<>();
    transcripts.put(1, "DR: Hello. PT: Hi.");

    int injected = FhirLlmNoteInjector.injectIntoBundle(root, notes, transcripts);
    assertEquals(2, injected); // one note replacement + one transcript

    JsonArray entries = root.getAsJsonArray("entry");
    String drData = null;
    String docData = null;
    JsonObject transcript = null;
    for (JsonElement el : entries) {
      JsonObject res = el.getAsJsonObject().getAsJsonObject("resource");
      String type = res.get("resourceType").getAsString();
      if ("DiagnosticReport".equals(type)) {
        drData = res.getAsJsonArray("presentedForm").get(0).getAsJsonObject()
            .get("data").getAsString();
      } else if ("DocumentReference".equals(type)) {
        JsonObject typeObj = res.getAsJsonObject("type");
        boolean isTranscript = typeObj.getAsJsonArray("coding").get(0).getAsJsonObject()
            .get("code").getAsString().equals("transcript");
        String data = res.getAsJsonArray("content").get(0).getAsJsonObject()
            .getAsJsonObject("attachment").get("data").getAsString();
        if (isTranscript) {
          transcript = res;
        } else {
          docData = data;
        }
      }
    }
    assertEquals("NEW LLM NOTE", decode(drData));
    assertEquals("NEW LLM NOTE", decode(docData));
    assertTrue("transcript DocumentReference should be added", transcript != null);
    assertEquals("DR: Hello. PT: Hi.",
        decode(transcript.getAsJsonArray("content").get(0).getAsJsonObject()
            .getAsJsonObject("attachment").get("data").getAsString()));
    // transcript references the correct encounter
    assertEquals("urn:uuid:e1", transcript.getAsJsonObject("context").getAsJsonArray("encounter")
        .get(0).getAsJsonObject().get("reference").getAsString());
  }

  @Test
  public void rerunIsIdempotentForTranscripts() {
    JsonObject root = JsonParser.parseString(BUNDLE).getAsJsonObject();
    Map<Integer, String> notes = new HashMap<>();
    Map<Integer, String> transcripts = new HashMap<>();
    transcripts.put(1, "first version");

    FhirLlmNoteInjector.injectIntoBundle(root, notes, transcripts);
    transcripts.put(1, "second version");
    FhirLlmNoteInjector.injectIntoBundle(root, notes, transcripts);

    int transcriptCount = 0;
    String data = null;
    for (JsonElement el : root.getAsJsonArray("entry")) {
      JsonObject res = el.getAsJsonObject().getAsJsonObject("resource");
      if (!"DocumentReference".equals(res.get("resourceType").getAsString())) {
        continue;
      }
      JsonObject typeObj = res.getAsJsonObject("type");
      if (typeObj != null && typeObj.getAsJsonArray("coding").get(0).getAsJsonObject()
          .get("code").getAsString().equals("transcript")) {
        transcriptCount++;
        data = res.getAsJsonArray("content").get(0).getAsJsonObject()
            .getAsJsonObject("attachment").get("data").getAsString();
      }
    }
    assertEquals("re-running must not duplicate the transcript DocumentReference",
        1, transcriptCount);
    assertEquals("second version", decode(data));
  }
}
