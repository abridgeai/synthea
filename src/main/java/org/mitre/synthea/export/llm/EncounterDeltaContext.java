package org.mitre.synthea.export.llm;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import org.mitre.synthea.world.agents.Person;
import org.mitre.synthea.world.concepts.HealthRecord.Allergy;
import org.mitre.synthea.world.concepts.HealthRecord.CarePlan;
import org.mitre.synthea.world.concepts.HealthRecord.Code;
import org.mitre.synthea.world.concepts.HealthRecord.Encounter;
import org.mitre.synthea.world.concepts.HealthRecord.Entry;
import org.mitre.synthea.world.concepts.HealthRecord.Medication;
import org.mitre.synthea.world.concepts.HealthRecord.Observation;
import org.mitre.synthea.world.concepts.HealthRecord.Report;

/**
 * Builds an explicit, labeled summary of the deterministic clinical state and the state
 * <em>transition</em> that occurs at a single encounter, for use as the prompt input to the
 * LLM-backed exporters.
 *
 * <p>Unlike the rendered FreeMarker note, this representation preserves the structure the model
 * needs to phrase an assessment and plan correctly: it partitions the record into what was active
 * coming into the encounter and what deterministically changed at it &mdash; medications
 * STARTED / CONTINUED / STOPPED, problems NEW / RESOLVED / ONGOING, and the orders placed. These
 * deltas are derived purely from the {@code start}/{@code stop}/{@code stopReason} fields on each
 * record entry, so they exactly mirror Synthea's engine output. Codes (RxNorm/SNOMED/etc.) are
 * included so the model anchors to specific concepts rather than paraphrasing.
 */
public class EncounterDeltaContext {

  private static final DateTimeFormatter DATE =
      DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC);

  /**
   * Build the labeled-delta context for a single encounter.
   *
   * @param person    the patient
   * @param encounter the encounter to summarize
   * @return a structured plain-text block describing the encounter and its deterministic changes
   */
  public static String build(Person person, Encounter encounter) {
    StringBuilder sb = new StringBuilder();
    long t = encounter.start;

    // --- Header / demographics ---
    sb.append("PATIENT: ");
    Integer age = ageInYears(person, t);
    if (age != null) {
      sb.append(age).append(" year-old ");
    }
    Object gender = person.attributes.get("gender");
    sb.append("F".equals(gender) ? "female" : ("M".equals(gender) ? "male" : "patient"));
    appendIfPresent(sb, ", race: ", person.attributes.get("race"));
    appendIfPresent(sb, ", ethnicity: ", person.attributes.get("ethnicity"));
    sb.append("\n");

    sb.append("ENCOUNTER DATE: ").append(DATE.format(Instant.ofEpochMilli(t))).append("\n");
    if (encounter.type != null) {
      sb.append("ENCOUNTER TYPE: ").append(encounter.type).append("\n");
    }
    if (encounter.reason != null) {
      sb.append("REASON FOR VISIT: ").append(codeDisplay(encounter.reason)).append("\n");
    }
    List<String> symptoms = person.getSymptoms();
    sb.append("CHIEF COMPLAINT / REPORTED SYMPTOMS: ")
        .append(symptoms == null || symptoms.isEmpty() ? "none reported"
            : String.join(", ", symptoms))
        .append("\n");

    // --- Problem list: ongoing vs new vs resolved at this encounter ---
    List<Entry> newProblems = encounter.conditions;
    List<Entry> ongoingProblems = new ArrayList<>();
    List<Entry> resolvedProblems = new ArrayList<>();
    for (Encounter enc : person.record.encounters) {
      for (Entry c : enc.conditions) {
        if (newProblems.contains(c) || c.start >= t) {
          continue;
        }
        if (stoppedDuring(c, encounter)) {
          resolvedProblems.add(c);
        } else if (activeAtStart(c, t)) {
          ongoingProblems.add(c);
        }
      }
    }
    appendList(sb, "PROBLEM LIST (ongoing, active coming into this encounter)", ongoingProblems);
    appendList(sb, "NEW DIAGNOSES (recorded at this encounter)", newProblems);
    appendList(sb, "RESOLVED (at this encounter)", resolvedProblems);

    // --- Medications: started vs continued vs stopped at this encounter ---
    List<Medication> started = encounter.medications;
    List<Medication> continued = new ArrayList<>();
    List<Medication> stopped = new ArrayList<>();
    for (Encounter enc : person.record.encounters) {
      for (Medication m : enc.medications) {
        if (started.contains(m) || m.start >= t) {
          continue;
        }
        if (stoppedDuring(m, encounter)) {
          stopped.add(m);
        } else if (activeAtStart(m, t)) {
          continued.add(m);
        }
      }
    }
    sb.append("MEDICATIONS:\n");
    appendSubList(sb, "STARTED at this encounter", started, false);
    appendSubList(sb, "CONTINUED (active, unchanged)", continued, false);
    appendSubList(sb, "STOPPED at this encounter", stopped, true);

    // --- Allergies ---
    List<Allergy> newAllergies = encounter.allergies;
    List<Allergy> activeAllergies = new ArrayList<>();
    for (Encounter enc : person.record.encounters) {
      for (Allergy a : enc.allergies) {
        if (!newAllergies.contains(a) && a.start < t && activeAtStart(a, t)) {
          activeAllergies.add(a);
        }
      }
    }
    appendList(sb, "ALLERGIES (active)", new ArrayList<>(activeAllergies));
    appendList(sb, "NEW ALLERGIES (this encounter)", new ArrayList<>(newAllergies));

    // --- Orders / events performed at this encounter ---
    appendList(sb, "PROCEDURES performed at this encounter",
        new ArrayList<>(encounter.procedures));
    appendList(sb, "IMMUNIZATIONS given at this encounter",
        new ArrayList<>(encounter.immunizations));
    appendList(sb, "IMAGING STUDIES at this encounter",
        new ArrayList<>(encounter.imagingStudies));
    appendReports(sb, encounter);
    appendObservations(sb, "VITALS / OBSERVATIONS at this encounter", encounter.observations);
    appendList(sb, "DEVICES/EQUIPMENT at this encounter", new ArrayList<>(encounter.devices));

    // --- Care plans (start/stop, like medications) ---
    List<CarePlan> startedPlans = encounter.careplans;
    List<CarePlan> stoppedPlans = new ArrayList<>();
    for (Encounter enc : person.record.encounters) {
      for (CarePlan cp : enc.careplans) {
        if (!startedPlans.contains(cp) && cp.start < t && stoppedDuring(cp, encounter)) {
          stoppedPlans.add(cp);
        }
      }
    }
    if (!startedPlans.isEmpty() || !stoppedPlans.isEmpty()) {
      sb.append("CARE PLANS:\n");
      appendSubList(sb, "STARTED at this encounter", new ArrayList<>(startedPlans), false);
      appendSubList(sb, "STOPPED at this encounter", new ArrayList<>(stoppedPlans), true);
    }

    // --- Any engine-provided free-text note attached to the encounter ---
    if (encounter.note != null && !encounter.note.isBlank()) {
      sb.append("ENGINE-PROVIDED NOTE: ").append(encounter.note).append("\n");
    }

    return sb.toString();
  }

  /** An entry is active at the encounter start if it began earlier and has not yet stopped. */
  private static boolean activeAtStart(Entry e, long t) {
    return e.start < t && (e.stop == 0L || e.stop > t);
  }

  /** An entry stops "at" this encounter if its stop time falls within the encounter window. */
  private static boolean stoppedDuring(Entry e, Encounter enc) {
    if (e.stop == 0L) {
      return false;
    }
    long end = Math.max(enc.stop, enc.start);
    return e.stop >= enc.start && e.stop <= end;
  }

  private static void appendList(StringBuilder sb, String heading, List<? extends Entry> items) {
    sb.append(heading).append(":");
    if (items == null || items.isEmpty()) {
      sb.append(" (none)\n");
      return;
    }
    sb.append("\n");
    for (Entry e : items) {
      sb.append("  - ").append(label(e)).append("\n");
    }
  }

  private static void appendSubList(StringBuilder sb, String heading,
      List<? extends Entry> items, boolean withStopReason) {
    sb.append("  ").append(heading).append(":");
    if (items == null || items.isEmpty()) {
      sb.append(" (none)\n");
      return;
    }
    sb.append("\n");
    for (Entry e : items) {
      sb.append("    - ").append(label(e));
      if (withStopReason) {
        Code reason = stopReason(e);
        if (reason != null) {
          sb.append(" (reason: ").append(codeDisplay(reason)).append(")");
        }
      }
      sb.append("\n");
    }
  }

  private static void appendReports(StringBuilder sb, Encounter encounter) {
    if (encounter.reports == null || encounter.reports.isEmpty()) {
      sb.append("LABS / DIAGNOSTIC REPORTS at this encounter: (none)\n");
      return;
    }
    sb.append("LABS / DIAGNOSTIC REPORTS at this encounter:\n");
    for (Report report : encounter.reports) {
      sb.append("  - ").append(label(report)).append("\n");
      if (report.observations != null) {
        for (Observation obs : report.observations) {
          sb.append("      - ").append(label(obs)).append(": ").append(obsValue(obs)).append("\n");
        }
      }
    }
  }

  private static void appendObservations(StringBuilder sb, String heading,
      List<Observation> observations) {
    if (observations == null || observations.isEmpty()) {
      return;
    }
    sb.append(heading).append(":\n");
    for (Observation obs : observations) {
      sb.append("  - ").append(label(obs)).append(": ").append(obsValue(obs)).append("\n");
    }
  }

  private static String obsValue(Observation obs) {
    if (obs.value == null) {
      return "(panel)";
    }
    String unit = (obs.unit == null || obs.unit.isBlank()) ? "" : " " + obs.unit;
    return obs.value + unit;
  }

  private static String label(Entry e) {
    if (e.codes != null && !e.codes.isEmpty()) {
      Code c = e.codes.get(0);
      String display = (c.display != null && !c.display.isBlank()) ? c.display : e.name;
      return (display == null ? "unspecified" : display) + " [" + c.code + "]";
    }
    return e.name != null ? e.name : "unspecified";
  }

  private static String codeDisplay(Code c) {
    if (c == null) {
      return "unspecified";
    }
    return (c.display != null && !c.display.isBlank()) ? c.display : c.code;
  }

  private static Code stopReason(Entry e) {
    if (e instanceof Medication) {
      return ((Medication) e).stopReason;
    }
    if (e instanceof CarePlan) {
      return ((CarePlan) e).stopReason;
    }
    return null;
  }

  private static Integer ageInYears(Person person, long time) {
    try {
      return person.ageInYears(time);
    } catch (Exception e) {
      return null;
    }
  }

  private static void appendIfPresent(StringBuilder sb, String prefix, Object value) {
    if (value != null && !String.valueOf(value).isBlank()) {
      sb.append(prefix).append(value);
    }
  }
}
