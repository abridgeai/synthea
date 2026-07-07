package org.mitre.synthea.export.llm;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

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
 * <p>The structured input is the labeled-delta summary produced by {@link EncounterDeltaContext},
 * which partitions the record into the state active coming into the encounter and the
 * deterministic changes made at it (medications started/continued/stopped, problems new/resolved,
 * orders placed). Both LLM exporters work from this same foundation.
 *
 * <p>When enabled, this generates a note/transcript for every encounter of every exported
 * patient. There is no sampling cap: the volume of LLM calls is determined by the population size
 * ({@code -p}) and each patient's encounter count, so control cost via the number of patients
 * generated.
 */
public abstract class LlmEncounterExporter implements PatientExporter {

  /** Shared client; constructed lazily so configuration is read after startup. */
  private static volatile LlmClient sharedClient;

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

    List<Encounter> encounters = person.record.encounters;

    // Each encounter's note/transcript is independent: EncounterDeltaContext.build reconstructs
    // the state entering an encounter from the (read-only during export) person.record, never from
    // another encounter's output, and each writes a distinct file. So the encounters of one patient
    // can be generated concurrently. When parallelism is enabled we fan them out to the shared
    // LlmExecutor pool, which bounds total in-flight requests across all patients and both
    // exporters; when disabled (concurrency == 1) we generate serially, as before.
    if (!LlmExecutor.isParallel()) {
      for (int i = 0; i < encounters.size(); i++) {
        generateForEncounter(client, person, encounters.get(i), i);
      }
      return;
    }

    ExecutorService pool = LlmExecutor.pool();
    List<Future<?>> futures = new ArrayList<>(encounters.size());
    for (int i = 0; i < encounters.size(); i++) {
      final int index = i;
      final Encounter encounter = encounters.get(i);
      futures.add(pool.submit(() -> generateForEncounter(client, person, encounter, index)));
    }
    awaitAll(futures);
  }

  /**
   * Build the structured input for one encounter and, if non-empty, generate and write its output.
   * Runs either on the calling (patient) thread or on an {@link LlmExecutor} worker.
   */
  private void generateForEncounter(LlmClient client, Person person, Encounter encounter,
      int encounterIndex) {
    String structured = EncounterDeltaContext.build(person, encounter);
    if (structured == null || structured.isBlank()) {
      return;
    }
    String generated = client.complete(systemPrompt(), structured);
    if (generated != null) {
      write(person, encounterIndex, generated);
    }
  }

  /**
   * Wait for all submitted encounter tasks to finish. A single encounter's failure is logged and
   * does not abort the rest of the patient's export.
   */
  private static void awaitAll(List<Future<?>> futures) {
    for (Future<?> future : futures) {
      try {
        future.get();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      } catch (ExecutionException e) {
        System.err.println("LLM export: encounter task failed: " + e.getCause());
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
