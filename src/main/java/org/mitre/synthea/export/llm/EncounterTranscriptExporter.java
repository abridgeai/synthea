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
      + "a STRUCTURED, LABELED summary of the encounter that states exactly what changed: "
      + "medications STARTED / CONTINUED / STOPPED, problems that are NEW / RESOLVED / ONGOING, "
      + "and the procedures, labs, and care plans recorded.\n\n"
      + "Produce a natural spoken dialogue that would plausibly lead to this documentation: "
      + "greeting, history taking, discussion of symptoms and findings, explanation of the "
      + "assessment, and the plan. Label each turn with the speaker (e.g. 'DR:', 'PT:'). Keep it "
      + "conversational and realistic, including hesitations and clarifying questions.\n\n"
      + "STRICT GROUNDING RULES (closed world):\n"
      + "- The clinical content of the conversation must come ONLY from the provided summary. Any "
      + "medication, diagnosis, dose, procedure, or result discussed MUST appear in it.\n"
      + "- Do NOT introduce, infer, or 'round out' any clinical detail that is not listed.\n"
      + "- Let the conversation reflect the labeled changes: the clinician initiating STARTED "
      + "medications, reviewing CONTINUED ones, and discontinuing STOPPED ones (with the given "
      + "reason when provided), and explaining NEW or RESOLVED diagnoses.\n"
      + "- Conversational filler, rapport, and non-clinical small talk are encouraged for realism; "
      + "the grounding rules apply only to clinical facts.\n\n"
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
