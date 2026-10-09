# Movement learning

Alpha.48 trains with deterministic replay balanced by source, movement family and outcome, so frequent successful walks cannot crowd out rare failures, swimming or earlier virtual lessons. Each candidate is compared to the current model using validation data only. A worse per-domain classification accuracy, balanced Brier score or count of confident failure predictions retains the existing weights. Promotion also requires an improvement. Reports retain the candidate and comparison even when rejected; the evaluated signature persists so rejected candidates do not cause endless automatic retraining. Test data is used for reporting only. These balanced outputs are uncalibrated shadow scores, not calibrated success probabilities.

Alpha.46 starts movement diagnostics and evaluation automatically when the plugin enables CoreAI. No commands are required. Startup evaluates once if no matching evaluation exists; every minute a background review writes `diagnostics.json` with actual arrivals, stalls, interrupted/unfinished attempts, failure reasons and data-collection errors. New labeled geometry triggers training after a ten-minute cooldown. Duplicate outcomes and cancellations do not trigger training, and the evaluated-data signature survives restart. Failed reviews write an error summary and retry on the next review. Diagnostics continue even when no training is needed. Predictions remain in shadow mode; this does not start a destructive construction trial or take over villagers' current tasks.

Alpha.45 adds an actual local neural predictor with 9 inputs, 12 tanh units and a sigmoid output (133 learned parameters). It estimates arrival probability for an adult villager's movement attempt. It does not generate paths, train Qwen/DeepSeek, or replace the existing movement executors. Training and journal writes run on a background CPU thread; no inference server, downloads or GPU are needed.

## Collect, probe, train

With CoreAI enabled, accepted native paths and issued surface-swimming movements automatically record pre-action features and a prediction. Measured waypoint arrival is positive; a timed-out executed movement is negative. Cancellation, remapping, sleep and controller interruption remain unlabeled. A rejected or unexecuted path is not a physical failure. Attempts have IDs; start and finish records are correlated, and each attempt finishes at most once. A crash may leave a start without a finish; this cannot become a successful training example.

The optional `/civ debug probe start` targets an existing task near a controlled villager. The optional `/civ debug probe learn` forces an immediate training/evaluation run; normal operation already does this automatically when new measured data is available. It does not move villagers or modify blocks. `/civ debug coreai` includes movement status and dropped receipt counts. Existing nearest-villager selection and task/proposal probes remain available.

Files are beneath `plugins/Civilizations/CoreAI/movement`:

- `attempts.jsonl` and three rotated files: pre-action and terminal receipts, model version, prediction, features, outcome and reason. Rotation begins at 8 MiB per file. Archive these before rotation if collecting a long-lived dataset.
- `model.json`: atomically saved weights, feature schema and evaluation report. Restart reloads these weights. With no saved model the bundled simulation-trained bootstrap is used. A corrupt saved model is reported and never used for predictions; a successful automatic rebuild can replace it from the retained examples. Invalid durable experience stops training with a diagnostic error and is left intact.
- `models/<version>.json`: trained snapshots identified by a weight hash.
- `experience.json`: deduplicated, labeled physical examples retained across subsequent training runs, even after diagnostic rotation. Each successful training run updates this memory.
- `datasets/<hash>.json`: exact numeric training/evaluation snapshot saved by each training run, so journal rotation cannot erase that run's inputs. Physical receipts also retain observed block states and route coordinates for inspection. Model and dataset archives are retained; their disk usage grows with training runs.
- `report.json`: active snapshot, candidate snapshot, validation-only promotion decision, split counts, per-source/family/outcome results, baselines, Brier error, confident false-success predictions, up to five held-out mistakes per source/family, dataset hash and collection health. Zero physical cases means no physical evaluation. Candidate metrics must not be mistaken for active-model metrics when promotion is rejected.

Offline equivalent, using the shaded plugin JAR and a separate output/data directory:

```text
java -cp Civilizations-2.0.0-alpha.48.jar dev.coreai.movement.MovementLearning <movement-folder>
```

## What the probes establish

The virtual curriculum covers flat ground, stairs, drops, low passages, gaps, water exits, fences, unknown cells, hazards and changed terrain with repaired support. These are an approximate corridor simulator, not Minecraft's collision/velocity engine. A fixed seed generates 2,100 cases. Groups are split 70/15/15 by stable hash; before/after terrain pairs and repeated physical route endpoints stay together. Interrupted records are excluded and identical group/features/outcome repeats are deduplicated. Fixed training duration never uses held-out labels to choose weights. Repeated reports reuse this holdout and are not independent experiments.

Training replays all retained training strata instead of adapting only to recent physical examples. Reports keep virtual and physical results separate. Old diagnostic logs without the required pre-action feature schema are not silently relabeled as training data. Physical coverage depends on routes actually attempted; safe native path selection naturally underrepresents failed paths. The physical sampler currently covers adult villagers and paths up to 64 native points, reads only owned regions, and uses point collision observations rather than a full physics replay. Nearby routes can share terrain; endpoint grouping is not proof of generalization to a new world.

The initial model is **shadow only**: its predictions are logged, not allowed to veto or steer routes. Promoting it into route selection needs comparison against the existing controller on real, held-out scenarios. The alpha.45 bootstrap reached 98.3% on 293 held-out simulated cases; all five errors were low passages, including one confident false success. This is a concrete weakness to investigate with fresh physical courses, not evidence that villagers have mastered movement.

## Implementation boundaries

`MovementSample` owns features and labels; `MovementCurriculum` owns simulation; `MovementNetwork` owns learned weights and gradient updates; `MovementTraining` owns grouped evaluation; `MovementLearning` owns persistence and asynchronous lifecycle. The plugin's `MovementObservation` reads live collisions, and the existing native/surface controllers own execution and outcome attribution. Simulated outcomes never enter the physical receipt journal.

Focused JVM checks: `python tools/test_sections.py coreai navigation diagnostics`. The existing disposable emergency-navigation fixture additionally checks actual arrivals, original pre-action features and physical provenance while retaining its escape, clearance, repair and item-conservation checks.
