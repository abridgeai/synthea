package org.mitre.synthea.export.llm;

/**
 * Generates a realistic, word-for-word transcript of the doctor-patient conversation for an
 * encounter, derived from the same structured clinical data used for notes. Enabled via
 * {@code exporter.transcript.llm.export} and requires
 * {@code exporter.enable_custom_exporters = true}.
 */
public class EncounterTranscriptExporter extends LlmEncounterExporter {

  private static final String SYSTEM_PROMPT =
      "You are generating a realistic, word-for-word transcript of a medical encounter between a "
      + "clinician and a patient (and, where appropriate, a nurse or family member). You are given "
      + "a structured summary of the encounter. Produce a natural spoken dialogue that would "
      + "plausibly lead to this documentation: greeting, history taking, discussion of symptoms "
      + "and findings, explanation of the assessment, and the plan. Label each turn with the "
      + "speaker (e.g. 'DR:', 'PT:'). Keep it conversational and realistic, including hesitations "
      + "and clarifying questions. Do not introduce diagnoses, medications, or results that are "
      + "not supported by the provided data. Output only the transcript.";

  @Override
  protected String enableConfigKey() {
    return "exporter.transcript.llm.export";
  }

  @Override
  protected String systemPrompt() {
    return SYSTEM_PROMPT;
  }

  @Override
  protected String outputFolderName() {
    return "transcripts";
  }

  @Override
  protected String fileTag() {
    return "_transcript";
  }
}
