# CLAUDE.md

Guidance for AI agents (and humans) working in this repository.

## What this is

This is **Abridge's fork of [Synthea](https://github.com/synthetichealth/synthea)**, MITRE's
synthetic patient generator. Upstream generates whole-life synthetic patient records and exports
them to FHIR/CSV/etc. We track upstream and layer Abridge-specific enhancements on top.

### Branch layout

- **`master`** mirrors upstream MITRE; do not put fork work here.
- **`abridge-main`** is the fork's integration trunk. Feature branches are squash-merged into it.
- Prefix new feature branches with `carl/`.

## Abridge enhancements

Everything above the upstream merge commit `2b0a55bab` is ours. Four areas:

1. **LLM-generated clinical notes & encounter transcripts** — `src/main/java/org/mitre/synthea/export/llm/`.
   Replaces the template (FreeMarker) note with LLM-generated notes and adds encounter transcripts,
   grounded in a labeled-delta encounter context (`EncounterDeltaContext`). OpenAI-compatible client
   with disk cache and retry (`LlmClient`), FHIR bundle injection (`FhirLlmNoteInjector`), usage
   stats (`LlmStatsExporter`). **This is the deepest feature — see the `synthea-patient-generation`
   skill (`.claude/skills/synthea-patient-generation/SKILL.md`) for the full picture, config keys,
   cost model, and run recipes.**

2. **Increased patient complexity** — ~18 new disease modules in `src/main/resources/modules/`
   (acute_pancreatitis, anxiety, appendicitis, bowel_obstruction, bph, cellulitis, depression,
   diverticulitis, dvt_pe, gerd, hyperlipidemia, influenza, low_back_pain, migraine,
   nephrolithiasis, parkinsons, peripheral_arterial_disease, pneumonia) plus imaging on
   stroke/osteoarthritis/injuries. Imaging is emitted as `ImagingStudy` states with grounded
   free-text impressions exported as FHIR `DiagnosticReport`. Realism fixes: penicillin-allergy
   guards before beta-lactams (Active Allergy RxNorm 7984 pattern), enoxaparin→warfarin bridge in
   dvt_pe, statin up-titration, cholecystectomy dedup via a shared marker.

3. **Parallelized LLM generation** — `LlmExecutor`, a shared bounded thread pool capping total
   in-flight LLM requests across all patients and both exporters (`exporter.llm.max_concurrent_requests`,
   default 50; `1` = serial). Decoupled from the CPU-sized generation pool because LLM calls are
   I/O-bound. Cache hardened with write-temp-then-atomic-rename for concurrency safety.

4. **US Core problem-list-item Conditions** — `FhirR4.java`. Each Condition additionally emits a
   `category=problem-list-item` Condition (active if no stop; resolved with `abatementDateTime` if
   stopped) so the US Core Problems view is populated. The encounter-diagnosis resource is left
   unchanged; the problem-list twin uses a deterministic id and is emitted after it so
   `reasonReference` lookups are unaffected.

## Important constraints

- **Physiology generators are disabled** (`physiology.generators.enabled = false`,
  `physiology.state.enabled = false` in `synthea.properties`) — the physiology simulator fails to
  load on the current build and crashes patient generation. Keep them off.
- **LLM export is off by default** (`exporter.clinical_note.llm.export`, `exporter.transcript.llm.export`,
  `exporter.fhir.llm.inject` all `false`). Enabling it makes paid API calls per encounter, per
  patient. Do not enable it or kick off generation runs that consume LLM tokens without explicit
  confirmation.
- **Validate module/logic changes with tests, not full generation runs.** `GeneratorTest` /
  `ModuleTest` exercise the modules without burning tokens or wall-clock.
- Use a **random seed by default**; only pin `-s` for reproducible A/B comparisons.

## Build & test environment

- Requires **JDK 17** (`build.gradle` `sourceCompatibility = '17'`). If no system JDK:
  `export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home`
- Compile / test / lint: `./gradlew compileJava test checkstyleMain checkstyleTest`
- Checkstyle is enforced (100-char lines; multi-line Javadoc when it has tags).
- Full run entrypoint: `./run_synthea [options] [state [city]]` (see README).

## Secrets & output hygiene

- Keep the LLM API key in a git-ignored `synthea.local.properties` (matched by `*.local.properties`)
  and run with `-c synthea.local.properties`. Never commit an `sk-` key — check tracked files before
  committing.
- `output/` is git-ignored and safe to delete — **except `output/llm_cache/`**, which caches LLM
  responses; deleting it forces paid API calls on the next run.

## Commit convention

End commit messages with the `Co-Authored-By` trailer for the model that assisted.
