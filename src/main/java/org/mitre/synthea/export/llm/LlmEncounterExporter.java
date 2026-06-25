package org.mitre.synthea.export.llm;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

import org.mitre.synthea.export.ClinicalNoteExporter;
import org.mitre.synthea.export.Exporter;
import org.mitre.synthea.export.Exporter.ExporterRuntimeOptions;
import org.mitre.synthea.export.PatientExporter;
import org.mitre.synthea.helpers.Config;
import org.mitre.synthea.world.agents.Person;
import org.mitre.synthea.world.concepts.HealthRecord.Encounter;

/**
 * Base class for exporters that feed an encounter's structured clinical data to an LLM and write
 * the generated free text to disk. Subclasses supply the enable flag, system prompt, output
 * folder, and filename tag.
 *
 * <p>The structured input is the rendered output of {@link ClinicalNoteExporter}, which already
 * assembles demographics, active conditions/medications/allergies, and the encounter's findings.
 * This keeps both LLM exporters working from the same foundation as the built-in note exporter.
 *
 * <p>To bound cost, generation is limited to a sampled subset of patients
 * ({@code exporter.llm.sample_size}) and to the most recent encounters per patient
 * ({@code exporter.llm.max_encounters_per_patient}). The sample is a bounded count, not a fixed
 * set of patients, because patients are exported across multiple threads.
 */
public abstract class LlmEncounterExporter implements PatientExporter {

  /** Shared client; constructed lazily so configuration is read after startup. */
  private static volatile LlmClient sharedClient;

  /** Bounded count of patients sampled for this exporter instance. */
  private final AtomicInteger sampledPatients = new AtomicInteger(0);

  /** Whether we have already warned about a missing API key (warn once, not per patient). */
  private final AtomicInteger missingKeyWarned = new AtomicInteger(0);

  /**
   * Config property that enables this exporter (e.g. {@code exporter.clinical_note.llm.export}).
   *
   * @return the boolean config key
   */
  protected abstract String enableConfigKey();

  /**
   * System prompt describing the role and desired output for this exporter.
   *
   * @return the system prompt
   */
  protected abstract String systemPrompt();

  /**
   * Output subfolder name (e.g. {@code notes_llm} or {@code transcripts}).
   *
   * @return the folder name under the exporter base directory
   */
  protected abstract String outputFolderName();

  /**
   * Tag inserted into the filename before the extension to distinguish output types.
   *
   * @return the filename tag
   */
  protected abstract String fileTag();

  @Override
  public void export(Person person, long stopTime, ExporterRuntimeOptions options) {
    if (!Config.getAsBoolean(enableConfigKey(), false)) {
      return;
    }

    LlmClient client = client();
    if (!client.isConfigured()) {
      if (missingKeyWarned.compareAndSet(0, 1)) {
        System.err.println("LLM export is enabled (" + enableConfigKey() + ") but no API key is "
            + "configured. Set the OPENAI_API_KEY environment variable or exporter.llm.api_key. "
            + "Skipping LLM export.");
      }
      return;
    }

    int sampleSize = Config.getAsInteger("exporter.llm.sample_size", 10);
    if (sampleSize > 0 && sampledPatients.incrementAndGet() > sampleSize) {
      return;
    }

    int maxEncounters = Config.getAsInteger("exporter.llm.max_encounters_per_patient", 5);
    List<Encounter> encounters = person.record.encounters;
    int start = (maxEncounters > 0) ? Math.max(0, encounters.size() - maxEncounters) : 0;

    for (int i = start; i < encounters.size(); i++) {
      Encounter encounter = encounters.get(i);
      String structured = ClinicalNoteExporter.export(person, encounter);
      if (structured == null || structured.isBlank()) {
        continue;
      }
      int seed = Math.abs(Objects.hash(person.attributes.get(Person.ID), encounter.start));
      String generated = client.complete(systemPrompt(), structured, seed);
      if (generated != null) {
        write(person, i, generated);
      }
    }
  }

  private void write(Person person, int encounterIndex, String content) {
    File outDirectory = Exporter.getOutputFolder(outputFolderName(), person);
    String name = Exporter.filename(person, fileTag() + "_" + encounterIndex, "txt");
    Path outFilePath = outDirectory.toPath().resolve(name);
    try {
      Files.writeString(outFilePath, content, StandardCharsets.UTF_8);
    } catch (IOException e) {
      System.err.println("LLM export: failed to write " + outFilePath + ": " + e.getMessage());
    }
  }

  private static LlmClient client() {
    if (sharedClient == null) {
      synchronized (LlmEncounterExporter.class) {
        if (sharedClient == null) {
          sharedClient = new LlmClient();
        }
      }
    }
    return sharedClient;
  }
}
