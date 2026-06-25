package org.mitre.synthea.export.llm;

/**
 * Generates a realistic, narrative clinical note for an encounter by rewriting Synthea's
 * structured note through an LLM. Enabled via {@code exporter.clinical_note.llm.export} and
 * requires {@code exporter.enable_custom_exporters = true}.
 */
public class LlmClinicalNoteExporter extends LlmEncounterExporter {

  private static final String SYSTEM_PROMPT =
      "You are an experienced physician documenting a patient encounter in the electronic health "
      + "record. You are given a structured summary of the encounter. Write a realistic, "
      + "professional clinical note in standard SOAP format (Subjective, Objective, Assessment, "
      + "Plan). Use natural clinical language and appropriate medical abbreviations. Do not invent "
      + "findings, diagnoses, medications, or results that are not supported by the provided data, "
      + "but you may phrase them naturally. Output only the note text.";

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
