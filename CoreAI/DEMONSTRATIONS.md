# Recovery demonstrations

Alpha.48 connects teacher proposals to executed recovery evidence. This is a data pipeline for a future local action/skill learner; it does not merge DeepSeek/Qwen weights or train a general planner. The separate movement learner still estimates movement outcomes in shadow mode.

## Automatic recording

`RecoveryExperiments` keeps using `CoreAI/data/experiments*.jsonl`. Receipt schema 1 remains readable by older tools; `data.receipt_version=2` adds:

- A unique `proposal_id` for each admitted program, including revisions and remembered programs.
- Structured `teacher_provenance` with recorded model/artifact/hash/license and the source of that identity. Historical plain labels remain unresolved rather than acquiring an invented hash.
- Original and current program context with map fingerprint, origin, goal and reach.
- A `program_outcome` receipt before replacing a physically failed program. Its scope is a program segment, not the whole village project.

When an observation request replaces instructions, it receives no goal-success label. No accepted program is attributed while its replacement is pending. Measured intermediate steps remain attached to the program that issued them. A failure after an unavailable revision cannot relabel an earlier program.

Teacher observations now include material, block state and removal information beside natural-clearance candidate coordinates. These are map observations and still require fresh host checks before a block is removed. The context does not supply a scripted answer to the model.

## Offline dataset assembly

Use the Python standard-library tool on a stopped-server snapshot or a completed lesson directory. It neither starts a model nor modifies the source data:

```text
python CoreAI/tools/export_recovery.py <CoreAI/data-or-lesson-root> <new-dataset-directory>
python CoreAI/tools/export_recovery.py <first-root> <new-directory> --additional-data <second-root>
```

The destination must be new and outside the input roots. Input hashes, receipt references and counts are retained in `manifest.json`. The existing `export_outcomes.py` interface is unchanged.

| Output | Meaning |
| --- | --- |
| `demonstrations.jsonl` | Joined, versioned program/context/evidence records |
| `success.jsonl` / `negative.jsonl` | Host-reported terminal or segment labels, with separate eligibility flags |
| `interrupted.jsonl` / `unverified.jsonl` | Cancellations and insufficient evidence, not failures or positive demonstrations |
| `quarantined.jsonl` | Conflicting labels, programs, provenance or ambiguous legacy attribution |
| `steps.jsonl` | Individual host receipts; verified steps are not whole-program successes |
| `positive-sft.jsonl` | Eligible training successes only; exact recorded teacher provenance required |
| `positive-sft-validation.jsonl` / `positive-sft-test.jsonl` | Corresponding withheld examples; not part of training |
| `lesson-manifests.jsonl` | Physical lesson recipes and independently measured case results |

Whole trials, repeated geometry and linked map/task contexts stay in one split. A declared lesson holdout forces its connected group into test, even if another source declared that group training. Unassigned groups use a stable 70/15/15 hash split. This prevents obvious leakage; it does not establish generalization to unseen Minecraft worlds.

Missing-program outcomes cannot supply positive training examples even if they claim success or retain a stale proposal ID. Historical missing provenance remains explicit. Failures are useful negative examples, not programs to imitate. Deterministic fixture demonstrations are labeled distinctly from model-generated behavior. A positive export is still input for curation, not proof that a student model learned anything.

## Focused physical lesson

The disposable `live-skill` fixture with `civilizations.test.demonstration=true` runs oak, birch and spruce/soil corridors. It retains chunk tickets, uses actual worker/navigation/block executors, checks the original goal and obstacle state, verifies item-drop accounting, drains evidence and releases tickets before shutdown. The third layout is declared held out. Case libraries are isolated to prevent remembering one case during another.

The current spruce/soil layout places stone immediately above the upper dirt block. The terrain classifier consequently requires a mining task for that block, which the recovery `CLEAR` instruction does not provide. Treat this layout as a capability-gap diagnostic; failure there cannot establish a model's general reasoning quality. A future loose-soil lesson must use separately identified geometry and verify executor feasibility before using it as a model comparison. Retain the existing results rather than rewriting their layout or labels.

`civilizations.test.real-teacher=true` uses an already-running local teacher and requires fixture-only environment credentials and the verified artifact hash. It has no scripted fallback. Default mode is an explicitly labeled deterministic executor control, not a model benchmark. Both modes preserve failed attempts. Do not run this fixture in a player's world.

## Readiness

The manifest reports whether successful and negative eligible examples exist in each split. This is a minimum coverage check, not a sufficient training budget. Do not train or promote a new action selector merely because journals are large. Obtain diverse executed successes, preserve failures, reserve independent scenarios, and evaluate against the existing controller. The current simple movement predictor cannot accept text plans; an action selector would be a separate learner consuming these observations and supported skill choices.

Focused checks: `python tools/test_sections.py coreai diagnostics inference` and `python -m unittest discover -s CoreAI/tools -p 'test_export*.py'`. Physical lesson results and model quality must be reported separately from those contracts.
