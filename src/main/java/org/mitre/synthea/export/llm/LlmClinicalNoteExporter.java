package org.mitre.synthea.export.llm;

/**
 * Generates a realistic, narrative clinical note for an encounter by rewriting Synthea's
 * structured note through an LLM. Enabled via {@code exporter.clinical_note.llm.export} and
 * requires {@code exporter.enable_custom_exporters = true}.
 */
public class LlmClinicalNoteExporter extends LlmEncounterExporter {

  private static final String SYSTEM_PROMPT =
      "You are an experienced physician documenting a patient encounter in the electronic health "
      + "record. You are given a STRUCTURED, LABELED summary of the encounter that already states "
      + "exactly what changed: medications STARTED / CONTINUED / STOPPED, problems that are "
      + "NEW / RESOLVED / ONGOING, and the procedures, labs, and care plans recorded.\n\n"
      + "Write a realistic, professional clinical note in standard SOAP format (Subjective, "
      + "Objective, Assessment, Plan). Use natural clinical language and appropriate medical "
      + "abbreviations.\n\n"
      + "STRICT GROUNDING RULES (closed world):\n"
      + "- Use ONLY the facts in the provided summary. Every medication, diagnosis, dose, "
      + "procedure, and result you mention MUST appear in it.\n"
      + "- Do NOT introduce, infer, or 'round out' any diagnosis, medication, lab value, or "
      + "finding that is not listed. If something is not present, it did not happen.\n"
      + "- Reflect the labeled changes faithfully in the Assessment and Plan: describe STARTED "
      + "medications as newly initiated, CONTINUED medications as ongoing, and STOPPED "
      + "medications as discontinued (with the given reason when provided). Frame NEW diagnoses "
      + "as new and RESOLVED problems as resolved.\n"
      + "- Refer to items by the names given (you may use the medical display names without the "
      + "bracketed codes).\n\n"
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
