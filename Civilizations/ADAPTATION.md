# Controller adaptation

Civilizations alpha.17 builds with Java 25 and Folia 26.1.2. Run `mvn -f pom.xml package` from the repository root to build the CoreAI library and shaded Civilizations plugin. Install only the Civilizations JAR. For focused checks, use `python tools/test_sections.py coreai navigation design tasks`; package afterward with `-DskipTests` when a JAR is needed.

## Editable sections

`CoreAI/.../ParameterCatalog.java` defines named settings, purpose, default and trial range. `WorkerTuning` supplies each worker's effective values. The recovery model receives the full catalog and current values in its observation. A `TUNE` instruction uses `material` for the key, `x` for the integer value and `y=z=0`.

| Section | Parameter | Default | Trial range |
|---|---|---|---|
| Construction | `construction.reach_squared` | 12 | 4–21 squared blocks |
| Construction | `construction.interval_ms` | Existing configured work interval | 250–10000 ms |
| Construction | `construction.face_ms` | 250 | 250–1000 ms |
| Navigation | `navigation.transition_ms` | 10000 | 3000–20000 ms |
| Navigation | `navigation.recovery_attempts` | 6 | 2–12 attempts |
| Recovery | `recovery.instruction_ms` | 12000 | 3000–30000 ms |
| Observation | `observation.probe_limit` | 12 | 4–32 distinct states |

`SEARCH` separately changes loaded-map radius within 8–48 blocks and the configured ceiling. `CLASSIFY` edits terrain rules using observed collision, fluid and removal facts. Those learned rules also feed construction-site clearance. Ranking expressions and finite walking/clearing/support-placement programs remain editable.

Programs can include up to three tuning instructions. A proposed setting applies to one worker during its trial, then persists and becomes shared only after verified completion. Failure, cancellation or failed storage discards the pending edit. A later successful proposal can revise the setting. Old terrain-rule files load without requiring migration. Skill contexts include effective parameter values, so remembered failures with different settings are distinguished.

The observations, proposal, before/after values, physical steps and outcome are recorded under the plugin's `CoreAI/` data folder. `/civ coreai` reports learned settings; `/civ inspect` reports work status. Completing a trial establishes that it worked in that case, not that it was faster or generally superior. This is controller learning, without neural weight training or arbitrary Java rewriting.

## Visible work and pauses

Workers approach a reachable target, check visibility, turn toward it and wait briefly before changing the block. Facing is checked after applying the current pose because native look control can adjust rotation between worker ticks. Construction and recovery clearance use the same pose helper. Actual work still consumes carried items, retains drops and animates a hand swing.

Work status distinguishes approaching, facing, preparing supplies, waiting for the work interval, waiting for region ownership, observing clutter and waiting for a recovery model. `work_wait` records changed wait reasons; `work_pose` records measured distance and facing at the action gate. These are observable execution facts rather than private model reasoning.

## Extending adaptation

For another adjustable parameter, add a catalog entry with units and purpose, wire the actual host consumer through `WorkerTuning`, and cover its affected action/outcome contract. A catalog entry with no consumer is not implemented adaptation. Keep physical validity, inventory accounting, player protection and Folia region ownership as host checks. Changing a knob cannot make unavailable materials or unsupported actions exist.

The existing architect can propose houses when shelter is needed. These tuning changes do not independently prove autonomous completion of an entire house or village. Useful model proposals, appropriate terrain, resources and the actual executor remain necessary.

## Focused physical check

The opt-in `rule-learning` fixture uses a disposable Folia world with normal discovery/model downloads disabled. It supplies two cluttered repair targets and a deterministic proposal containing TUNE, CLASSIFY, SEARCH, CLEAR and VERIFY. It checks facing at work events, actual plank placement, exact ingredient costs, retained drops, persisted settings and rule reuse without another backend request. The scenario stops its test server when done. A deterministic proposal tests the controller, not teacher-model quality.

Historical real-model program replay is optional through `civilizations.test.rule-program`. The optional local teacher adapter accepts an explicitly supplied environment credential; no credentials, model files, server data or private logs belong in Git.

Alpha.17 validation: 96 focused JVM cases passed. The physical rule-learning scenario then passed on Folia 26.1.2 build 8: two repairs, exact plank consumption, both string drops retained, facing verified at action events, and the proposed 500 ms facing delay persisted alongside classification and search radius. The second repair reused learned behavior without another backend request. The first physical attempt exposed a pose gate checking prior rotation and timed out; checking the applied rotation fixed that case. This was a deterministic controller test, not a fresh model-capability evaluation.
