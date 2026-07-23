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
 * Feeds each encounter's structured clinical data to an LLM and writes the generated free text to
 * disk: a word-for-word doctor-patient <em>transcript</em> and/or a narrative clinical
 * <em>note</em>.
 *
 * <p>The structured input is the labeled-delta summary produced by {@link EncounterDeltaContext},
 * which partitions the record into the state active coming into the encounter and the
 * deterministic changes made at it (medications started/continued/stopped, problems new/resolved,
 * orders placed).
 *
 * <p><b>Transcript &rarr; note chaining.</b> When both outputs are enabled, the note is generated
 * <em>from the transcript</em> (plus the structured summary for factual grounding), not
 * independently. This makes the note's Subjective/HPI actually reflect what the patient said in the
 * conversation, so a transcript and its note form a faithful pair. When only the note is enabled
 * there is no conversation to derive from, so the note is written directly from the structured
 * summary as before. Hard clinical facts (diagnoses, medications, doses, results, procedures)
 * always come only from the structured summary, in both outputs.
 *
 * <p>When enabled, this generates output for every encounter of every exported patient. There is no
 * sampling cap: the volume of LLM calls is determined by the population size ({@code -p}) and each
 * patient's encounter count, so control cost via the number of patients generated.
 */
public class LlmEncounterExporter implements PatientExporter {

  /** Config key enabling the narrative clinical note. */
  private static final String NOTE_ENABLE_KEY = "exporter.clinical_note.llm.export";
  /** Config key enabling the encounter conversation transcript. */
  private static final String TRANSCRIPT_ENABLE_KEY = "exporter.transcript.llm.export";

  /** Output subfolder + filename tag for notes. */
  private static final String NOTE_FOLDER = "notes_llm";
  private static final String NOTE_TAG = "_note";
  /** Output subfolder + filename tag for transcripts. */
  private static final String TRANSCRIPT_FOLDER = "transcripts";
  private static final String TRANSCRIPT_TAG = "_transcript";

  private static final String TRANSCRIPT_SYSTEM_PROMPT =
      "You are generating a realistic, word-for-word transcript of a medical encounter "
      + "between a clinician and a patient (and, where appropriate, a nurse or family "
      + "member). You are given a STRUCTURED, LABELED summary of the encounter that "
      + "states exactly what changed: medications STARTED / CONTINUED / STOPPED, problems "
      + "that are NEW / RESOLVED / ONGOING, and the procedures, labs, and care plans "
      + "recorded.\n\n"
      + "Produce a long, natural spoken dialogue representing a full, unhurried visit of "
      + "several minutes of real back-and-forth (not a brief summary). Move through the "
      + "whole arc of the visit: greeting and rapport, an in-depth history-taking exchange "
      + "where the patient describes their story in their own words, the clinician's "
      + "follow-up and clarifying questions, a review of systems woven into the "
      + "conversation, discussion and explanation of examination and test findings, "
      + "shared decision-making, a clear explanation of the assessment and the plan, "
      + "counseling and safety-netting / return precautions, patient questions and "
      + "teach-back, and a wrap-up with next steps. Label each turn with the speaker "
      + "(e.g. 'DR:', 'PT:', 'RN:'). Use realistic spoken language with hesitations, "
      + "interruptions, self-corrections, tangents, and small talk, and let the exchange "
      + "breathe rather than rushing to the plan.\n\n"
      + "NARRATIVE RICHNESS:\n"
      + "- Let the patient tell a rich, specific presentation story: how symptoms started "
      + "and evolved over time, their character and severity, what makes them better or "
      + "worse, associated symptoms, and concrete detail about what they were doing and "
      + "how it affects daily life, work, sleep, and mood.\n"
      + "- Add plausible context such as recent life events, stressors, travel, diet, "
      + "exercise, occupation, medication adherence, and family or social circumstances "
      + "that could reasonably lead to this encounter's findings and plan, along with the "
      + "patient's own concerns, fears, and goals.\n"
      + "- This invented subjective and contextual detail is expected and encouraged, as "
      + "long as it is internally consistent and plausibly leads to the documented "
      + "assessment and plan.\n\n"
      + "STRICT GROUNDING RULES (hard clinical facts only):\n"
      + "- Any diagnosis, medication, dose, lab or test result, or procedure discussed "
      + "must come ONLY from the provided summary. Do NOT introduce, infer, or 'round "
      + "out' clinical facts that are not listed.\n"
      + "- The narrative latitude above applies to the conversation and the patient's "
      + "story, NOT to inventing new clinical findings, results, or treatments.\n"
      + "- Let the conversation reflect the labeled changes: initiating STARTED "
      + "medications, reviewing CONTINUED ones, and discontinuing STOPPED ones (with the "
      + "given reason when provided), and explaining NEW or RESOLVED diagnoses.\n\n"
      + "Output only the transcript.";

  private static final String NOTE_SYSTEM_PROMPT =
      "You are an experienced physician documenting a patient encounter in the "
      + "electronic health record.\n\n"
      + "You may be given up to two inputs: (1) a TRANSCRIPT of the encounter conversation, "
      + "and (2) a STRUCTURED, LABELED summary of the encounter that states exactly what "
      + "changed: medications STARTED / CONTINUED / STOPPED, problems that are NEW / "
      + "RESOLVED / ONGOING, and the procedures, labs, and care plans recorded.\n\n"
      + "Write a realistic, detailed clinical note in standard SOAP format (Subjective, "
      + "Objective, Assessment, Plan), using natural clinical language and appropriate "
      + "medical abbreviations. Aim for the depth of a real attending's note, not a "
      + "terse summary.\n\n"
      + "USING THE TRANSCRIPT (when one is provided):\n"
      + "- The Subjective / HPI MUST reflect what the patient actually said in the "
      + "transcript: the same symptom story (onset, duration, character, severity, timing, "
      + "aggravating and relieving factors, associated symptoms), the same psychosocial "
      + "context, concerns, and goals. Summarize and organize it into clinical prose; do "
      + "NOT contradict or invent a different story than the conversation.\n"
      + "- Capture the review of systems and pertinent positives/negatives that came up in "
      + "the dialogue, and let the Assessment and Plan match what the clinician explained "
      + "and decided in the conversation.\n\n"
      + "WHEN NO TRANSCRIPT IS PROVIDED:\n"
      + "- Develop a thorough, plausible HPI and psychosocial context yourself (onset, "
      + "duration, character, severity, aggravating/relieving factors, associated "
      + "symptoms, relevant ROS), consistent with the documented findings and plan. This "
      + "kind of invented narrative detail is expected and encouraged.\n\n"
      + "STRICT GROUNDING RULES (hard clinical facts only):\n"
      + "- The diagnoses, medications, doses, lab and test results, procedures, "
      + "immunizations, and care plans must come ONLY from the STRUCTURED SUMMARY. Do NOT "
      + "add, infer, or 'round out' any of these, and do NOT treat clinical facts merely "
      + "mentioned in the transcript as documented unless they also appear in the "
      + "summary. If it is not listed in the summary, it did not happen.\n"
      + "- The narrative latitude above applies to the subjective story and context, NOT "
      + "to inventing new clinical findings, results, or treatments.\n"
      + "- Reflect the labeled changes in the Assessment and Plan: STARTED medications as "
      + "newly initiated, CONTINUED as ongoing, STOPPED as discontinued (with the given "
      + "reason when provided); NEW diagnoses as new and RESOLVED problems as resolved.\n"
      + "- Refer to items by the names given (you may drop the bracketed codes).\n\n"
      + "Output only the note text.";

  /** Shared client; constructed lazily so configuration is read after startup. */
  private static volatile LlmClient sharedClient;

  /** Whether we have already warned about a missing API key (warn once, not per patient). */
  private final AtomicInteger missingKeyWarned = new AtomicInteger(0);

  @Override
  public void export(Person person, long stopTime, ExporterRuntimeOptions options) {
    boolean noteEnabled = Config.getAsBoolean(NOTE_ENABLE_KEY, false);
    boolean transcriptEnabled = Config.getAsBoolean(TRANSCRIPT_ENABLE_KEY, false);
    if (!noteEnabled && !transcriptEnabled) {
      return;
    }

    LlmClient client = client();
    if (!client.isConfigured()) {
      if (missingKeyWarned.compareAndSet(0, 1)) {
        System.err.println("LLM export is enabled but no API key is configured. Set the "
            + "OPENAI_API_KEY environment variable or exporter.llm.api_key. Skipping LLM export.");
      }
      return;
    }

    List<Encounter> encounters = person.record.encounters;

    // Each encounter is independent: EncounterDeltaContext.build reconstructs the state entering an
    // encounter from the (read-only during export) person.record, never from another encounter's
    // output, and each writes distinct files. So encounters of one patient can be generated
    // concurrently; patients already run in parallel on the generator's pool. Within a single
    // encounter the transcript and note are generated sequentially (the note is derived from the
    // transcript), so one pool task owns both calls for that encounter.
    if (!LlmExecutor.isParallel()) {
      for (int i = 0; i < encounters.size(); i++) {
        generateForEncounter(client, person, encounters.get(i), i, noteEnabled, transcriptEnabled);
      }
      return;
    }

    ExecutorService pool = LlmExecutor.pool();
    List<Future<?>> futures = new ArrayList<>(encounters.size());
    for (int i = 0; i < encounters.size(); i++) {
      final int index = i;
      final Encounter encounter = encounters.get(i);
      futures.add(pool.submit(() ->
          generateForEncounter(client, person, encounter, index, noteEnabled, transcriptEnabled)));
    }
    awaitAll(futures);
  }

  /**
   * Build the structured input for one encounter and, if non-empty, generate its transcript and/or
   * note. When both are enabled the note is generated from the transcript. Runs either on the
   * calling (patient) thread or on an {@link LlmExecutor} worker.
   */
  private void generateForEncounter(LlmClient client, Person person, Encounter encounter,
      int encounterIndex, boolean noteEnabled, boolean transcriptEnabled) {
    String structured = EncounterDeltaContext.build(person, encounter);
    if (structured == null || structured.isBlank()) {
      return;
    }

    String transcript = null;
    if (transcriptEnabled) {
      transcript = client.complete(TRANSCRIPT_SYSTEM_PROMPT, structured);
      if (transcript != null) {
        write(person, TRANSCRIPT_FOLDER, TRANSCRIPT_TAG, encounterIndex, transcript);
      }
    }

    if (noteEnabled) {
      // Derive the note from the conversation when we have one; otherwise from the summary alone.
      String noteInput = (transcript != null)
          ? composeNoteInput(transcript, structured)
          : structured;
      String note = client.complete(NOTE_SYSTEM_PROMPT, noteInput);
      if (note != null) {
        write(person, NOTE_FOLDER, NOTE_TAG, encounterIndex, note);
      }
    }
  }

  /** Label the transcript and structured summary as the two inputs to the note generation. */
  private static String composeNoteInput(String transcript, String structured) {
    return "=== ENCOUNTER CONVERSATION TRANSCRIPT ===\n"
        + "Base the Subjective/HPI on what the patient says here.\n\n"
        + transcript
        + "\n\n=== STRUCTURED CLINICAL SUMMARY (authoritative for all clinical facts) ===\n"
        + structured;
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

  private void write(Person person, String folder, String tag, int encounterIndex,
      String content) {
    File outDirectory = Exporter.getOutputFolder(folder, person);
    String name = Exporter.filename(person, tag + "_" + encounterIndex, "txt");
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
