package org.mitre.synthea.export.llm;

/**
 * Generates a realistic, word-for-word transcript of the doctor-patient conversation for an
 * encounter, derived from the same structured clinical data used for notes. Enabled via
 * {@code exporter.transcript.llm.export} and requires
 * {@code exporter.enable_custom_exporters = true}.
 */
public class EncounterTranscriptExporter extends LlmEncounterExporter {

  private static final String SYSTEM_PROMPT =
      "You are generating a realistic, word-for-word transcript of a medical encounter "
      + "between a clinician and a patient (and, where appropriate, a nurse or family "
      + "member). You are given a STRUCTURED, LABELED summary of the encounter that "
      + "states exactly what changed: medications STARTED / CONTINUED / STOPPED, problems "
      + "that are NEW / RESOLVED / ONGOING, and the procedures, labs, and care plans "
      + "recorded.\n\n"
      + "Produce a lengthy, natural spoken dialogue representing several minutes of real "
      + "back-and-forth (not a brief summary). Include greeting and rapport, an unhurried "
      + "history-taking exchange where the patient describes their story in their own "
      + "words, the clinician's follow-up and clarifying questions, discussion of the "
      + "findings, shared decision-making, explanation of the assessment, the plan, and "
      + "patient questions before wrap-up. Label each turn with the speaker (e.g. 'DR:', "
      + "'PT:'). Use realistic spoken language with hesitations, interruptions, tangents, "
      + "and small talk.\n\n"
      + "NARRATIVE RICHNESS:\n"
      + "- Let the patient tell a rich presentation story: how symptoms started and "
      + "evolved, what they were doing, and how it affects daily life, plus plausible "
      + "context such as recent life events, stressors, travel, diet, work, and family "
      + "circumstances that could reasonably lead to this encounter's findings and "
      + "plan.\n"
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
