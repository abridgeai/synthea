---
name: synthea-patient-generation
description: Explains how Synthea generates synthetic patients and the parameters for tweaking output — population, seed, age/gender/location, history depth, which exporters run (FHIR/CSV/notes), bundle-size trimming, and the LLM-backed clinical note + transcript feature in org.mitre.synthea.export.llm. Use when asked to generate patients, tune patient complexity, choose CLI/config options, control output formats/size, or work with the LLM notes/transcripts.
---

# Synthea patient generation

## How generation works (mental model)

Synthea simulates each patient's whole life from birth to the present (or death),
advancing in fixed timesteps (`generate.timestep`, default 1 week = 604800000 ms).
**Modules** — JSON state machines in `src/main/resources/modules/` — drive disease
onset, encounters, medications, procedures, and care plans over that lifetime.
Demographics are sampled from `src/main/resources/geography/demographics.csv` (every US
city by default; constrain with a positional `state`/`city` argument).

Key consequence for tuning: **you do not set the number of encounters directly.**
Encounters emerge from the modules over the simulated lifespan, so **age is the primary
driver of chart complexity** — an older patient accrues more conditions, polypharmacy,
and encounters than a young, healthy one.

At export time the flow is:
1. `Exporter.export` calls `filterForExport(person, years_of_history, stopTime)` — trims the
   record to the configured history window (and after-death filtering). **This filtered
   record is shared by all exporters**, so FHIR, CSV, notes, and custom `PatientExporter`s
   all see the identical, identically-ordered encounter list.

   **`years_of_history` is NOT a clean N-year cut** (`Exporter.filterForExport`, `Exporter.java`
   ~716-768). It keeps the last N years **plus every older encounter that introduced a
   still-active condition, allergy, medication, or careplan** (`entryIsActive`: `stop==0 ||
   stop>cutoff` → the encounter stays "non-empty" and survives). Since social-history findings
   (e.g. *"Received higher education (finding)"*, employment status) **never get a stop date**,
   they pin otherwise-trivial old encounters forever. Net effect for elderly/multimorbid
   patients: the exported record — and thus the LLM notes/transcripts — spans **most of the
   patient's life, not just the last 10 years** (empirically, notes for 65–75yo patients ranged
   age 0–79; ~78% fell in the last ~15 years). Lowering `years_of_history` shrinks the recent
   window but does **not** remove these pinned older encounters. This is expected, not a bug.
2. Built-in exporters run (FHIR/CSV/JSON/text/notes), then any `PatientExporter`s
   (`exporter.enable_custom_exporters=true`), then `PostCompletionExporter`s once at the end.

## CLI options (`./run_synthea [options] [state [city]]`)

`run_synthea` just forwards args to `gradle run -Params=[...]`. Defined in `src/main/java/App.java`:

| Flag | Meaning |
|------|---------|
| `-p <n>` | population size |
| `-s <seed>` | population seed (**fixed seed → reproducible patient(s)**) |
| `-ps <seed>` | single-person seed (generate one specific person) |
| `-cs <seed>` | clinician seed |
| `-a <min>-<max>` | age range, e.g. `-a 65-85` |
| `-g M\|F` | gender |
| `-r <YYYYMMDD>` / `-e <YYYYMMDD>` | reference / end date |
| `-o <n>` | overflow population (extra patients to satisfy `-k`/keep filters) |
| `-m <module>` | restrict to specific module(s) (`File.pathSeparator`-separated) |
| `-c <file>` | load an extra properties file that **merges over** the packaged defaults |
| `-d <dir>` | local modules directory |
| `-k <file>` | keep only patients matching the given criteria |
| `--<config.key>=<value>` | override any `synthea.properties` setting inline |
| positional | `state` then `city`, e.g. `Massachusetts Chicopee` |

Examples: `./run_synthea -s 12345 -p 1 -a 65-85` · `./run_synthea -p 10 --exporter.csv.export=true Utah "Salt Lake City"`

## Key `synthea.properties` settings (`src/main/resources/synthea.properties`)

- **Generation:** `generate.default_population`, `generate.thread_pool_size` (-1 = all cores),
  `generate.timestep`, `generate.demographics.default_file`, `generate.geography.*`.
- **Output location/format:** `exporter.baseDirectory` (default `./output/`),
  `exporter.pretty_print`, `exporter.years_of_history` (default 10; **0 = keep entire history**),
  `exporter.use_uuid_filenames`, `exporter.subfolders_by_id_substring`.
- **Which exporters run:** `exporter.fhir.export`, `exporter.fhir_stu3.export`,
  `exporter.fhir_dstu2.export`, `exporter.csv.export`, `exporter.json.export`,
  `exporter.text.export`, `exporter.clinical_note.export` (built-in **template** note),
  `exporter.cpcds.export`, `exporter.bfd.export`, `exporter.cdw.export`.
- **FHIR tuning:** `exporter.fhir.use_us_core_ig`, `exporter.fhir.transaction_bundle`,
  `exporter.fhir.bulk_data` (ndjson), and **`exporter.fhir.included_resources` /
  `exporter.fhir.excluded_resources`** (comma-separated FHIR R4 type names; only one may be set).

## Tuning patient complexity

- **Age** (`-a`) is the biggest lever — older = richer charts, more encounters.
- **Fixed seed** (`-s`) → same patient every run (reproducible; pairs with the LLM cache below).
- **Location** narrows demographics; **`-m`** narrows disease modules.
- There is no direct "number of encounters" knob.

## Trimming FHIR bundle size

A single elderly patient's bundle can be several MB. Biggest contributors (by content):
**ExplanationOfBenefit ~39% + Claim ~10% (≈half is billing)**, then Observation, then notes.
About 40% of the file is pretty-print whitespace. Cheap wins (config only):
- `exporter.pretty_print = false` → roughly halves file size, no data loss.
- `exporter.fhir.excluded_resources = ExplanationOfBenefit,Claim` → drops ~49% of content
  (add `,Provenance,SupplyDelivery` for a bit more). Lowers US Core conformance but keeps all
  clinical content.
- Lower `exporter.years_of_history` → fewer encounters/observations across the board.

## LLM-backed clinical notes & transcripts (custom feature)

Lives in `src/main/java/org/mitre/synthea/export/llm/`. Generates realistic free-text notes and
encounter transcripts via an OpenAI-compatible Chat Completions API, grounded in the structured
record.

**Architecture:**
- `EncounterDeltaContext` — builds a labeled summary per encounter (meds STARTED/CONTINUED/STOPPED,
  problems NEW/RESOLVED/ONGOING, procedures/labs/care plans) from `start`/`stop`/`stopReason`. This
  is the prompt input — so notes use correct verbs (start vs continue vs discontinue).
- `LlmClient` — OkHttp+Gson; key from `OPENAI_API_KEY` env or `exporter.llm.api_key`; retries on
  429/5xx; **disk cache** keyed by `model + prompts`; sends **no** temperature/seed (natural
  variation); tracks API-call and token counts.
- `LlmEncounterExporter` — single `PatientExporter` that, per encounter, generates the
  **transcript first**, then the **note derived from that transcript** (+ the delta for factual
  grounding), writing one file each to `output/transcripts/` and `output/notes_llm/`. This
  transcript→note chaining makes the note's Subjective/HPI reflect what the patient said in the
  conversation, so a transcript and its note are a faithful pair (they were previously two
  independent generations from the same delta — factually consistent but narratively divergent).
  If only the note is enabled (no transcript), the note is written from the delta alone. Hard
  clinical facts always come only from the delta, never invented from the conversation.
- `LlmStatsExporter` — `PostCompletionExporter` printing API-call/token totals after a run.
- `FhirLlmNoteInjector` — `PostCompletionExporter` that re-reads each FHIR bundle and replaces the
  template note in the `DiagnosticReport`/`DocumentReference` with the LLM note (matched by
  chronological encounter index), and adds a `DocumentReference` per transcript.

**Config (`exporter.llm.*` and friends):**

| Key | Purpose |
|-----|---------|
| `exporter.clinical_note.llm.export` | enable LLM notes (derived from the transcript when it is also enabled) |
| `exporter.transcript.llm.export` | enable LLM transcripts (generated first; the note is chained off it) |
| `exporter.fhir.llm.inject` | embed LLM notes/transcripts into the FHIR bundle (needs `exporter.fhir.export`) |
| `exporter.llm.api_key` | API key (prefer the `OPENAI_API_KEY` env var; **never commit a key**) |
| `exporter.llm.base_url` | API base; **region-locked keys need `https://us.api.openai.com/v1`** |
| `exporter.llm.model` | model id |
| `exporter.llm.cache` / `exporter.llm.cache_dir` | response cache (default `./output/llm_cache`) |
| `exporter.llm.max_concurrent_requests` | global cap on in-flight LLM requests across all patients and encounters (default 50; `1` = serial). Each encounter makes up to 2 sequential calls (transcript, then note). LLM calls are I/O-bound, so this is decoupled from `generate.thread_pool_size` — see the parallelism note below |

There is **no sampling cap**: when enabled, a note/transcript is generated for **every encounter
of the filtered record**, for every exported patient. Because the LLM exporters run on the *same*
filtered record as FHIR/CSV, generation is strictly **1:1 with exported encounters** — nothing is
generated that isn't exported (verified: encounters == notes == transcripts, and each becomes a
`DocumentReference`). But per the `filterForExport` note above, "encounters of the filtered record"
means **~full-life for complex patients**, not a 10-year window — so budget total LLM volume ≈
patients (`-p`) × (their lifetime encounters that seeded still-active problems/meds + last-N-years
encounters) × 2, **not** `-p` × 10-years-of-encounters × 2. Empirically ~120 calls/patient for
elderly multimorbid patients. Control cost primarily via the number of patients generated.

**Parallelism:** encounters within a patient are generated concurrently via a shared bounded pool
(`LlmExecutor`) sized by `exporter.llm.max_concurrent_requests`; patients already run in parallel
on the generator's pool. The requests multiplex over one HTTP/2 connection, so raising concurrency
costs streams, not TCP connections. At 50 concurrent (~2.5k tokens/call, ~10s latency) you use ~1%
of a 30k-RPM / 180M-TPM OpenAI tier — the rate limit is not the binding constraint at any sane value.

**Cost & cache notes:** the cache makes re-runs with identical prompts free. Changing the model
**or the prompt** invalidates the cache → fresh API calls. Same seed + same data = cache hits.

## Viewing a patient: HTML report generator

`tools/build_patient_report.py` renders a single generated patient into a physician-friendly,
self-contained HTML chart: patient banner, active problem list / meds / allergies, **collapsible
encounters** (each with vitals, diagnoses/procedures/orders, the LLM clinical note rendered from
markdown, and the encounter transcript), then Labs and Imaging sections.

```
python3 tools/build_patient_report.py [BUNDLE.json] [OUT.html]
```
With no args it picks the single patient bundle in `output/fhir/` (ignoring the hospital/
practitioner `*Information*` files) and writes `output/patient_report.html`. It reads the LLM
notes/transcripts **from the bundle**, so generate with `exporter.fhir.llm.inject=true` first (else
notes/transcripts are absent). Pure stdlib; no external deps.

## Build / run environment (this machine)

- No system JDK; use the Homebrew openjdk 17:
  `export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home`
- Compile/test/lint: `./gradlew compileJava test checkstyleMain checkstyleTest`
- The repo enforces checkstyle (100-char lines; multi-line Javadoc when it has tags).
- Secrets: keep the API key in a git-ignored `synthea.local.properties` (matched by
  `*.local.properties`) and run with `-c synthea.local.properties`. Verify no `sk-` key is in any
  tracked file before committing.
- `output/` is git-ignored and safe to delete — **except** `output/llm_cache/` (deleting it forces
  paid API calls on the next run).

**Typical run** (one reproducible elderly patient with LLM notes + transcripts, from cache when warm):
```
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
./run_synthea -c synthea.local.properties -p 1 -a 65-85 -s 12345
```
