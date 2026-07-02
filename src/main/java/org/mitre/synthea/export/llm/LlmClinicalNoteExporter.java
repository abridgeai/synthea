package org.mitre.synthea.export.llm;

/**
 * Generates a realistic, narrative clinical note for an encounter by rewriting Synthea's
 * structured note through an LLM. Enabled via {@code exporter.clinical_note.llm.export} and
 * requires {@code exporter.enable_custom_exporters = true}.
 */
public class LlmClinicalNoteExporter extends LlmEncounterExporter {

  private static final String SYSTEM_PROMPT =
      "You are an experienced physician documenting a patient encounter in the "
      + "electronic health record. You are given a STRUCTURED, LABELED summary of the "
      + "encounter that already states exactly what changed: medications STARTED / "
      + "CONTINUED / STOPPED, problems that are NEW / RESOLVED / ONGOING, and the "
      + "procedures, labs, and care plans recorded.\n\n"
      + "Write a realistic, detailed clinical note in standard SOAP format (Subjective, "
      + "Objective, Assessment, Plan), using natural clinical language and appropriate "
      + "medical abbreviations. Aim for the depth of a real attending's note, not a "
      + "terse summary.\n\n"
      + "NARRATIVE RICHNESS (especially the Subjective / HPI):\n"
      + "- Develop a thorough History of Present Illness: a plausible symptom story "
      + "(onset, duration, character, severity, timing, aggravating and relieving "
      + "factors, associated symptoms), a relevant review of systems, and pertinent "
      + "positives and negatives.\n"
      + "- Add plausible psychosocial and contextual color that could reasonably lead to "
      + "this encounter's findings and plan: relevant life events, stressors, recent "
      + "travel, diet and exercise, occupation, medication adherence, and family or "
      + "social circumstances, plus the patient's own concerns and goals.\n"
      + "- This kind of invented narrative detail is expected and encouraged, as long as "
      + "it is internally consistent and plausibly leads to the documented assessment "
      + "and plan.\n\n"
      + "STRICT GROUNDING RULES (hard clinical facts only):\n"
      + "- The diagnoses, medications, doses, lab and test results, procedures, "
      + "immunizations, and care plans must come ONLY from the provided summary. Do NOT "
      + "add, infer, or 'round out' any of these. If it is not listed, it did not "
      + "happen.\n"
      + "- The narrative latitude above applies to the subjective story and context, NOT "
      + "to inventing new clinical findings, results, or treatments.\n"
      + "- Reflect the labeled changes in the Assessment and Plan: STARTED medications as "
      + "newly initiated, CONTINUED as ongoing, STOPPED as discontinued (with the given "
      + "reason when provided); NEW diagnoses as new and RESOLVED problems as resolved.\n"
      + "- Refer to items by the names given (you may drop the bracketed codes).\n\n"
      + "Output only the note text.";

  @Override
  protected String enableConfigKey() {
    return "exporter.clinical_note.llm.export";
  }

  @Override
  protected String systemPrompt() {
    return SYSTEM_PROMPT;
  }

  @Override
  protected String outputFolderName() {
    return "notes_llm";
  }

  @Override
  protected String fileTag() {
    return "_note";
  }
}
