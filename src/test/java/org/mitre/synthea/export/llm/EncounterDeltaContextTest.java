package org.mitre.synthea.export.llm;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.mitre.synthea.world.agents.PayerManager;
import org.mitre.synthea.world.agents.Person;
import org.mitre.synthea.world.concepts.HealthRecord;
import org.mitre.synthea.world.concepts.HealthRecord.Code;
import org.mitre.synthea.world.concepts.HealthRecord.Encounter;
import org.mitre.synthea.world.concepts.HealthRecord.EncounterType;
import org.mitre.synthea.world.concepts.HealthRecord.Medication;

public class EncounterDeltaContextTest {

  private static final long T1 = 1_000_000_000L;
  private static final long T2 = 2_000_000_000L;

  /**
   * Across two encounters, a medication started at the first and ended at the second, plus a new
   * medication and condition at the second, should be partitioned into STARTED / STOPPED with the
   * problem list carried forward as ONGOING.
   */
  @Test
  public void partitionsStartedContinuedStoppedAndProblemList() {
    Person person = new Person(0L);
    person.attributes.put(Person.BIRTHDATE, 0L);
    person.attributes.put("gender", "M");
    PayerManager.loadNoInsurance();
    person.coverage.setPlanToNoInsurance(0L);
    HealthRecord record = person.record;

    // Encounter 1: start lisinopril, diagnose hypertension.
    record.encounterStart(T1, EncounterType.OUTPATIENT);
    record.conditionStart(T1, "59621000"); // hypertension
    Medication lisinopril = record.medicationStart(T1, "lisinopril", true);
    lisinopril.codes.add(new Code("RxNorm", "314076", "lisinopril 10 MG Oral Tablet"));

    // Encounter 2: start metformin (new dx diabetes), stop lisinopril.
    Encounter e2 = record.encounterStart(T2, EncounterType.OUTPATIENT);
    record.conditionStart(T2, "44054006"); // type 2 diabetes
    Medication metformin = record.medicationStart(T2, "metformin", true);
    metformin.codes.add(new Code("RxNorm", "860975", "metformin 500 MG Oral Tablet"));
    record.medicationEnd(T2, "lisinopril",
        new Code("SNOMED", "182840001", "Drug treatment stopped"));

    String context = EncounterDeltaContext.build(person, e2);

    // metformin is STARTED, lisinopril is STOPPED at this encounter.
    assertTrue(context.contains("STARTED at this encounter"));
    assertTrue(context.contains("metformin 500 MG Oral Tablet"));
    assertTrue(context.contains("STOPPED at this encounter"));

    // The stopped lisinopril and its stop reason should appear under the stopped section,
    // not the started section.
    int startedIdx = context.indexOf("STARTED at this encounter");
    int continuedIdx = context.indexOf("CONTINUED (active, unchanged)");
    int stoppedIdx = context.indexOf("STOPPED at this encounter");
    int lisinoprilIdx = context.indexOf("lisinopril 10 MG Oral Tablet");
    assertTrue(lisinoprilIdx > stoppedIdx);
    assertTrue("lisinopril must be after CONTINUED header (i.e. in STOPPED section)",
        lisinoprilIdx > continuedIdx);
    assertTrue(context.contains("Drug treatment stopped"));

    // hypertension carried forward as ongoing; diabetes is a new diagnosis this encounter.
    assertTrue(context.contains("PROBLEM LIST (ongoing, active coming into this encounter)"));
    assertTrue(context.contains("NEW DIAGNOSES (recorded at this encounter)"));

    // Sanity: lisinopril is not double-listed as started.
    String startedSection = context.substring(startedIdx, continuedIdx);
    assertFalse(startedSection.contains("lisinopril"));
  }
}
