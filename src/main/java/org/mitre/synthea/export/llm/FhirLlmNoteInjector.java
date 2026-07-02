package org.mitre.synthea.export.llm;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.mitre.synthea.engine.Generator;
import org.mitre.synthea.export.Exporter.ExporterRuntimeOptions;
import org.mitre.synthea.export.PostCompletionExporter;
import org.mitre.synthea.helpers.Config;

/**
 * Post-processes generated FHIR R4 bundles to embed the LLM-generated notes and transcripts,
 * keeping the structured bundle and the free text in sync.
 *
 * <p>By default {@code FhirR4} embeds Synthea's deterministic template note in each encounter's
 * {@code DiagnosticReport}/{@code DocumentReference}. When this injector is enabled
 * ({@code exporter.fhir.llm.inject = true}) it re-reads each bundle and, for every encounter that
 * has an LLM note in {@code output/notes_llm}, replaces that embedded text with the LLM note. Each
 * LLM transcript in {@code output/transcripts} is added as a new {@code DocumentReference}.
 *
 * <p>Notes/transcripts are matched to encounters by chronological index. This is safe because both
 * the FHIR export and the LLM {@link LlmEncounterExporter} run on the same record after
 * {@code years_of_history} filtering, so they see an identical, identically-ordered encounter list.
 * Only the standard per-patient R4 transaction bundle layout is handled (not bulk/ndjson or split
 * records).
 */
public class FhirLlmNoteInjector implements PostCompletionExporter {

  private static final String NOTE_LOINC = "34117-2";
  private static final String TRANSCRIPT_SYSTEM = "http://synthetichealth.github.io/synthea";
  private static final String TRANSCRIPT_CODE = "transcript";
  private static final Pattern NOTE_IDX = Pattern.compile("_note_(\\d+)\\.txt$");
  private static final Pattern TRANS_IDX = Pattern.compile("_transcript_(\\d+)\\.txt$");

  @Override
  public void export(Generator generator, ExporterRuntimeOptions options) {
    if (!Config.getAsBoolean("exporter.fhir.llm.inject", false)
        || !Config.getAsBoolean("exporter.fhir.export", false)) {
      return;
    }
    String base = Config.get("exporter.baseDirectory", "./output/");
    Path fhirDir = Paths.get(base, "fhir");
    Path notesDir = Paths.get(base, "notes_llm");
    Path transDir = Paths.get(base, "transcripts");
    if (!Files.isDirectory(fhirDir)) {
      return;
    }
    List<Path> noteFiles = walk(notesDir, ".txt");
    List<Path> transFiles = walk(transDir, ".txt");
    if (noteFiles.isEmpty() && transFiles.isEmpty()) {
      return;
    }
    Gson gson = Config.getAsBoolean("exporter.pretty_print", true)
        ? new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()
        : new GsonBuilder().disableHtmlEscaping().create();

    int updated = 0;
    for (Path bundleFile : walk(fhirDir, ".json")) {
      String name = bundleFile.getFileName().toString();
      if (name.startsWith("hospitalInformation") || name.startsWith("practitionerInformation")) {
        continue;
      }
      try {
        if (processBundleFile(bundleFile, noteFiles, transFiles, gson)) {
          updated++;
        }
      } catch (Exception e) {
        System.err.println("FhirLlmNoteInjector: failed to process " + bundleFile + ": "
            + e.getMessage());
      }
    }
    if (updated > 0) {
      System.out.println("FhirLlmNoteInjector: embedded LLM notes/transcripts into "
          + updated + " FHIR bundle(s).");
    }
  }

  private boolean processBundleFile(Path bundleFile, List<Path> noteFiles, List<Path> transFiles,
      Gson gson) throws IOException {
    JsonObject root = JsonParser.parseString(
        Files.readString(bundleFile, StandardCharsets.UTF_8)).getAsJsonObject();
    String patientId = patientId(root);
    if (patientId == null) {
      return false;
    }
    Map<Integer, String> notes = readMatching(noteFiles, patientId, NOTE_IDX);
    Map<Integer, String> transcripts = readMatching(transFiles, patientId, TRANS_IDX);
    if (notes.isEmpty() && transcripts.isEmpty()) {
      return false;
    }
    int injected = injectIntoBundle(root, notes, transcripts);
    if (injected > 0) {
      Files.writeString(bundleFile, gson.toJson(root), StandardCharsets.UTF_8);
      return true;
    }
    return false;
  }

  /**
   * Embed the given notes/transcripts (keyed by chronological encounter index) into a parsed FHIR
   * bundle. Notes replace the text in the matching {@code DiagnosticReport}/{@code
   * DocumentReference}; transcripts are added (or, if already present from a prior run, updated) as
   * a {@code DocumentReference}.
   *
   * @param root        the parsed FHIR transaction bundle
   * @param notes       encounter index to note text
   * @param transcripts encounter index to transcript text
   * @return the number of encounters whose note or transcript was injected
   */
  static int injectIntoBundle(JsonObject root, Map<Integer, String> notes,
      Map<Integer, String> transcripts) {
    JsonArray entries = root.getAsJsonArray("entry");
    if (entries == null) {
      return 0;
    }

    String patientFullUrl = null;
    List<JsonObject> encounterEntries = new ArrayList<>();
    for (JsonElement el : entries) {
      JsonObject entry = el.getAsJsonObject();
      JsonObject res = entry.getAsJsonObject("resource");
      if (res == null) {
        continue;
      }
      String type = str(res, "resourceType");
      if ("Patient".equals(type)) {
        patientFullUrl = str(entry, "fullUrl");
      } else if ("Encounter".equals(type)) {
        encounterEntries.add(entry);
      }
    }
    encounterEntries.sort(Comparator.comparing(FhirLlmNoteInjector::encounterStart)
        .thenComparing(e -> str(e, "fullUrl")));
    List<String> encUrls = new ArrayList<>();
    for (JsonObject e : encounterEntries) {
      encUrls.add(str(e, "fullUrl"));
    }

    int injected = 0;
    for (Map.Entry<Integer, String> ne : notes.entrySet()) {
      int idx = ne.getKey();
      if (idx < 0 || idx >= encUrls.size()) {
        System.err.println("FhirLlmNoteInjector: note index " + idx + " out of range ("
            + encUrls.size() + " encounters); skipping.");
        continue;
      }
      if (replaceNote(entries, encUrls.get(idx), b64(ne.getValue()))) {
        injected++;
      }
    }
    for (Map.Entry<Integer, String> te : transcripts.entrySet()) {
      int idx = te.getKey();
      if (idx < 0 || idx >= encounterEntries.size()) {
        continue;
      }
      upsertTranscript(entries, encounterEntries.get(idx), encUrls.get(idx),
          patientFullUrl, te.getValue());
      injected++;
    }
    return injected;
  }

  /** Replace the note text in the DiagnosticReport and DocumentReference for an encounter. */
  private static boolean replaceNote(JsonArray entries, String encUrl, String base64) {
    boolean replaced = false;
    for (JsonElement el : entries) {
      JsonObject res = el.getAsJsonObject().getAsJsonObject("resource");
      if (res == null) {
        continue;
      }
      String type = str(res, "resourceType");
      if ("DiagnosticReport".equals(type) && isNoteReport(res)
          && encUrl.equals(refOf(res.getAsJsonObject("encounter")))) {
        JsonObject att = first(res.getAsJsonArray("presentedForm"));
        if (att != null) {
          att.addProperty("data", base64);
          replaced = true;
        }
      } else if ("DocumentReference".equals(type) && docRefReferences(res, encUrl)
          && !isTranscript(res)) {
        JsonObject content = first(res.getAsJsonArray("content"));
        if (content != null && content.has("attachment")) {
          content.getAsJsonObject("attachment").addProperty("data", base64);
          replaced = true;
        }
      }
    }
    return replaced;
  }

  /** Add a transcript DocumentReference for the encounter, or update one from a prior run. */
  private static void upsertTranscript(JsonArray entries, JsonObject encounterEntry, String encUrl,
      String patientFullUrl, String text) {
    String base64 = b64(text);
    for (JsonElement el : entries) {
      JsonObject res = el.getAsJsonObject().getAsJsonObject("resource");
      if (res != null && "DocumentReference".equals(str(res, "resourceType"))
          && isTranscript(res) && docRefReferences(res, encUrl)) {
        JsonObject content = first(res.getAsJsonArray("content"));
        if (content != null && content.has("attachment")) {
          content.getAsJsonObject("attachment").addProperty("data", base64);
          return;
        }
      }
    }

    String uuid = UUID.nameUUIDFromBytes(("transcript:" + encUrl).getBytes(StandardCharsets.UTF_8))
        .toString();
    JsonObject att = new JsonObject();
    att.addProperty("contentType", "text/plain; charset=utf-8");
    att.addProperty("data", base64);
    JsonObject content = new JsonObject();
    content.add("attachment", att);
    JsonArray contentArr = new JsonArray();
    contentArr.add(content);

    JsonObject context = new JsonObject();
    JsonArray encArr = new JsonArray();
    encArr.add(reference(encUrl));
    context.add("encounter", encArr);
    JsonObject period = (encounterEntry.getAsJsonObject("resource")).getAsJsonObject("period");
    if (period != null) {
      context.add("period", period.deepCopy());
    }

    JsonObject docref = new JsonObject();
    docref.addProperty("resourceType", "DocumentReference");
    docref.addProperty("id", uuid);
    docref.addProperty("status", "current");
    docref.add("type", codeableConcept(TRANSCRIPT_SYSTEM, TRANSCRIPT_CODE,
        "Encounter conversation transcript"));
    JsonArray category = new JsonArray();
    category.add(codeableConcept(
        "http://hl7.org/fhir/us/core/CodeSystem/us-core-documentreference-category",
        "clinical-note", "Clinical Note"));
    docref.add("category", category);
    if (patientFullUrl != null) {
      docref.add("subject", reference(patientFullUrl));
    }
    String start = str(encounterEntry.getAsJsonObject("resource").getAsJsonObject("period"),
        "start");
    if (start != null) {
      docref.addProperty("date", start);
    }
    docref.add("content", contentArr);
    docref.add("context", context);

    JsonObject request = new JsonObject();
    request.addProperty("method", "POST");
    request.addProperty("url", "DocumentReference");
    JsonObject entry = new JsonObject();
    entry.addProperty("fullUrl", "urn:uuid:" + uuid);
    entry.add("resource", docref);
    entry.add("request", request);
    entries.add(entry);
  }

  // ---- small JSON helpers ----

  private static boolean isNoteReport(JsonObject report) {
    JsonArray categories = report.getAsJsonArray("category");
    if (categories == null) {
      return false;
    }
    for (JsonElement cat : categories) {
      JsonArray coding = cat.getAsJsonObject().getAsJsonArray("coding");
      if (coding != null) {
        for (JsonElement c : coding) {
          if (NOTE_LOINC.equals(str(c.getAsJsonObject(), "code"))) {
            return true;
          }
        }
      }
    }
    return false;
  }

  private static boolean isTranscript(JsonObject docref) {
    JsonObject type = docref.getAsJsonObject("type");
    if (type == null) {
      return false;
    }
    JsonArray coding = type.getAsJsonArray("coding");
    if (coding == null) {
      return false;
    }
    for (JsonElement c : coding) {
      if (TRANSCRIPT_CODE.equals(str(c.getAsJsonObject(), "code"))) {
        return true;
      }
    }
    return false;
  }

  private static boolean docRefReferences(JsonObject docref, String encUrl) {
    JsonObject context = docref.getAsJsonObject("context");
    if (context == null) {
      return false;
    }
    JsonArray encArr = context.getAsJsonArray("encounter");
    if (encArr == null) {
      return false;
    }
    for (JsonElement e : encArr) {
      if (encUrl.equals(refOf(e.getAsJsonObject()))) {
        return true;
      }
    }
    return false;
  }

  private static String refOf(JsonObject ref) {
    return ref == null ? null : str(ref, "reference");
  }

  private static JsonObject reference(String url) {
    JsonObject o = new JsonObject();
    o.addProperty("reference", url);
    return o;
  }

  private static JsonObject codeableConcept(String system, String code, String display) {
    JsonObject coding = new JsonObject();
    coding.addProperty("system", system);
    coding.addProperty("code", code);
    coding.addProperty("display", display);
    JsonArray arr = new JsonArray();
    arr.add(coding);
    JsonObject cc = new JsonObject();
    cc.add("coding", arr);
    cc.addProperty("text", display);
    return cc;
  }

  private static String encounterStart(JsonObject encounterEntry) {
    JsonObject res = encounterEntry.getAsJsonObject("resource");
    JsonObject period = res == null ? null : res.getAsJsonObject("period");
    String start = str(period, "start");
    return start == null ? "" : start;
  }

  private static JsonObject first(JsonArray arr) {
    return (arr == null || arr.size() == 0) ? null : arr.get(0).getAsJsonObject();
  }

  private static String str(JsonObject obj, String key) {
    return (obj != null && obj.has(key) && !obj.get(key).isJsonNull())
        ? obj.get(key).getAsString() : null;
  }

  private static String b64(String text) {
    return Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8));
  }

  // ---- file discovery ----

  private static String patientId(JsonObject root) {
    JsonArray entries = root.getAsJsonArray("entry");
    if (entries == null) {
      return null;
    }
    for (JsonElement el : entries) {
      JsonObject res = el.getAsJsonObject().getAsJsonObject("resource");
      if (res != null && "Patient".equals(str(res, "resourceType"))) {
        return str(res, "id");
      }
    }
    return null;
  }

  private static Map<Integer, String> readMatching(List<Path> files, String patientId,
      Pattern idxPattern) {
    Map<Integer, String> out = new HashMap<>();
    for (Path f : files) {
      String name = f.getFileName().toString();
      if (!name.contains(patientId)) {
        continue;
      }
      Matcher m = idxPattern.matcher(name);
      if (m.find()) {
        try {
          out.put(Integer.parseInt(m.group(1)), Files.readString(f, StandardCharsets.UTF_8));
        } catch (IOException e) {
          System.err.println("FhirLlmNoteInjector: could not read " + f + ": " + e.getMessage());
        }
      }
    }
    return out;
  }

  private static List<Path> walk(Path dir, String suffix) {
    if (!Files.isDirectory(dir)) {
      return new ArrayList<>();
    }
    try (Stream<Path> s = Files.walk(dir)) {
      List<Path> out = new ArrayList<>();
      s.filter(Files::isRegularFile)
          .filter(p -> p.getFileName().toString().endsWith(suffix))
          .forEach(out::add);
      return out;
    } catch (IOException e) {
      return new ArrayList<>();
    }
  }
}
