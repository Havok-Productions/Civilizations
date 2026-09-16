# Focused testing

Run the smallest relevant set once. Tests of a planner, inventory calculation or mock backend establish those contracts; they do **not** prove that a villager moved, mined or built in Minecraft.

## Routine changes

From this directory, with Java 25 and Maven available:

```powershell
python tools/test_sections.py navigation
python tools/test_sections.py crafting storage
python tools/test_sections.py --changed Civilizations/src/main/java/dev/civilizations/world/WorkerNavigation.java --dry-run
python tools/test_sections.py --changed Civilizations/src/main/java/dev/civilizations/world/WorkerNavigation.java
python tools/test_sections.py navigation --interactions --dry-run
python tools/test_sections.py --list
```

If Maven is not on PATH, set `MAVEN_CMD` to its executable or use `--maven`. `JAVA_HOME` selects Java; optional `MAVEN_REPO` / `--repo` selects an existing Maven cache. This runner uses the reactor's **test** phase, avoiding JAR shading, packaging, installation and deployment. It never starts a server or a model. First use may need to obtain missing build dependencies.

Tests are tagged by section and, where appropriate, by interacting sections. Selecting `crafting`, for example, includes its storage/worker-plan contracts; it does not automatically run every storage, village or model test. Multiple sections are a deduplicated union. `--interactions` narrows that union to cross-section checks; it is for diagnosing a boundary and is not a replacement for all checks relevant to a change.

| Section | Scope | Physical follow-up when execution changes |
|---|---|---|
| navigation | Local maps, detours, clearance safety, failed routes, route adapter | `navigation` |
| crafting | Current recipe engine, materials and worker tool prerequisites | `crafting` |
| storage | Chest selection, donations, transfers and surplus commitments | `observations` or `workflow` |
| design | Layout validation, terrain surveys, reservations and repair plans | `repair` or `workflow` |
| tasks | Claims, work order, recovery budgets and completed-job receipts | `autonomy` when the work loop changes |
| settlements | Membership, merging and persistence contracts | `connections` or `discovery` |
| inference | Fake-backend queue, response validation and model routing | `inference-busy` only for work/model scheduling |
| runtime | Local runtime ownership, ports, paths and restart policy | A specific runtime reproduction if needed |
| coreai | Interpreter, replay, promotion, rollback and host adapters | Only the affected worker interaction |
| diagnostics | Failure classification, journal and evidence persistence | Inspect the affected event from an already-needed physical check |

`tools/test_sections.json` maps source files to sections. Shared entry points, build/config changes and unknown paths select all fast tests with an explicit explanation, rather than silently choosing zero coverage. Every test declaration must have a valid section tag; new unsupported declaration types fail the catalog check until their selection is implemented.

The runner prints chosen classes and method counts. `test-results/last-run.json` contains results from **fresh** Surefire reports only, plus elapsed wall time and the evidence limitation. Old reports cannot inflate a focused run's count. Parameterized tests can produce more cases than selected methods.

## Slow checks are separate

Alpha.35 uses `navigation` with `-Dcivilizations.test.adaptive-recovery=true`. In one disposable world, a worker escapes a deep pool around a solid obstruction, then mines and repairs its assigned block with exact tool/material accounting. Another builds every block of a two-layer shifted wall beside water, including the gate. The wall's geometry comes from the production local-alternative search and passes normal admission. Supplied materials isolate the changed executors; inference is disabled. This proves these physical cases, not general village productivity or autonomous model design. The fixture stops observing each assigned project at completion, without waiting for newly generated village work. JVM checks in `navigation design tasks settlements storage coreai inference diagnostics` cover changed boundaries; subsequent fixes should rerun only affected sections. Coverage includes exact probe refusal reasons, two adjacent stone preparation blocks, duplicate-program correction feedback, changed-inventory reconsideration, and persistent per-program learning counters.

Alpha.34 uses `observations` with `-Dcivilizations.test.source-recovery=true` for the repeated wet-sand failure found in live logs. One gathering executor must reject the nearer wet source, harvest the dry alternative, survive repeated local scans without repeatedly rejecting unchanged terrain, and harvest the original source after water removal with exact item conservation. Check its journal for exactly one `resource_rejected` event. JVM coverage in `navigation crafting diagnostics` checks worker-local memory and invalidation by changed observations. This does not establish a solution to the separately observed submerged native paths or invalid wall proposal.

Alpha.33 adds the `autonomy` selector `-Dcivilizations.test.task-probe=true`. Console commands assign two ordinary workers explicit queued tasks: one must find a natural tree from an empty inventory, harvest, craft and place the specified plank while leaving a nearer unrelated job unfinished; the other must retain a protected dirt block and report the precise protection failure followed by `TIMEOUT`. The probe's outcome must agree with actual blocks and item conservation. Models remain disabled. Run `tasks diagnostics storage crafting coreai` for worker/receipt interactions; the shared plugin entry-point change is only command routing and journal lifecycle wiring. See `Civilizations/PROBES.md` for live admin use; the disposable fixture itself must never be installed on the live server.

Alpha.32 uses `crafting` with `-Dcivilizations.test.blocker-recovery=true`. One disposable-world case checks a full inventory with a partially consumed log stack, exact plank/cache conservation, an abandoned sand batch refueled from community coal, and a second furnace chosen while the nearest one holds another recipe. It also checks a higher trunk block after an exhausted approach history and a normal navigation reset: the actor must approach a visible ground stand and face the log before harvesting. Models remain disabled. Focused JVM contracts cover shared ore/tool prerequisites, cache metadata persistence and old-save compatibility, failed candidate/control comparisons, and executor-attributed automatic rollback. A cross-region cache transfer is not physically covered by this scenario; the implementation approaches the saved position before accessing the item on its owning region.

Alpha.31 uses `autonomy` with `-Dcivilizations.test.progress-learning=true`. One disposable-world scenario starts a worker with every inventory slot full, clears dirt, retrieves reserved project wood, crafts and places planks, and checks exact item conservation with no community deposit. An enrolled blocking villager must move aside and resume its own job; an existing bed and gate must be reoriented without new items. The gathering executor must return actual iron and clay drops and retain the base of harvested cane. Focused `design navigation tasks storage crafting coreai settlements` contracts separately check measured control/candidate comparisons, inconclusive outcomes, previous-policy restoration across restart, 3D design access and cache persistence/merging. Inference is disabled in the physical case: it verifies the executor, not autonomous model reasoning or neural training.

Alpha.30 uses `autonomy` with `-Dcivilizations.test.construction-guards=true`. One disposable-world case checks already-satisfied placement, mining without a pickaxe, and an interrupted gathering step through the actual worker tick with empty inventories. A builder must physically step out before placing its block. A second builder must retain its job and bed while another villager occupies the bed's head space, then place both halves after the obstruction leaves. The check covers the final placement barrier, a non-colliding torch, exact plank/bed conservation and no injuries or task failures. Focused `design navigation tasks storage crafting coreai` checks include the large-open-field access regression, rejection of a disconnected second stand, and reachable clearing stages. Models remain disabled; these checks establish executor behavior, not general village productivity.

Alpha.29 uses `navigation` with `-Dcivilizations.test.movement-recovery=true`. One disposable-world case submits twelve simultaneous map requests and runs two ordinary workers through an airborne approach, surface-water traversal, drowning escape, and a dirt obstruction around a worker's head. Both original construction jobs must finish with exact plank/dirt conservation and no task failure. Escape choices at equal distance prefer the retained task's side. This tests actual movement and the emergency executor with inference disabled; it does not benchmark model reasoning or general village productivity. Focused `navigation tasks crafting storage coreai` checks also cover queued-request supersession/cancellation, capacity failures, and pending/unobserved resource surveys. A pending local search cannot impose a village-wide material shortage.

Alpha.28 checks proposal surveys and salvage in `design settlements inference`. The recorded mislocated-wall geometry is replayed against synthetic observed terrain: it must fail the existing defense-purpose check before a huge capture, survey known bed/chest neighborhoods, and admit a freshly validated local alternative under the same proposal identity. Other checks retain full-footprint/access coverage, negative-direction mine bounds, resource-failure fallback, unknown/wet-terrain rejection and merge continuity. These are planning checks, not proof that a wall was built in the live world; no Folia or model startup is needed for this change.

Alpha.27 uses the existing `cooperation` scenario with `-Dcivilizations.test.courier-failure=true`. It injects one courier navigation failure into the actual worker callback, checks that the donor retains its unrelated construction claim without a failure penalty, and verifies that the handoff releases its recipient without immediately repeating the unchanged failed route. The ordinary physical delivery, completed wall, resumed donor task and inventory conservation must then pass. The injected failure tests callback attribution, not detection of a real blocked route. Focused `tasks storage settlements crafting coreai` checks cover route retry memory, changed positions/tasks, alternative couriers and the retained work contracts; no model startup is required.

Alpha.26 combines recovery and construction with `-Dcivilizations.test.scenario=rule-learning -Dcivilizations.test.wall-work=true -Dcivilizations.test.recovery-revision=true`. It uses a fresh per-world learning folder and a deterministic teacher. One initially impossible walk must produce a fresh-observation revision, finish the original repairs, reuse the measured clutter rule, clear tree/soil prerequisites, withdraw wall materials and finish every wall block. The default rule-learning checks are unchanged. This replaces separate recovery/soil/wall startups for this work-loop change. JVM checks cover the full footprint survey and resource-failure fallback; `python CoreAI/tools/test_export_outcomes.py` checks revised-program observation attribution. Navigation archive tests drain asynchronous writes before temporary-directory cleanup.

Alpha.25 adds the `navigation` selector `-Dcivilizations.test.dynamic-route=true`: one short disposable-world case for a failed proposal with no instruction, opening a real door, tree/grass growth after the initial map, remapping, physical clearance and retained wood. It uses a disabled inference backend and the actual navigation/recovery executors. This replaces the longer detour/repair setup when checking changing-route behavior. Run `navigation diagnostics coreai inference` for the affected JVM contracts; no model startup is involved.

Alpha.24 adds targeted proposal-salvage checks in `design settlements inference`. The production coordinator runs against an injectable immutable terrain survey and a fake or offline model. Checks cover a model revision arriving during a village merge, preparation before paid construction, an offline local fallback for invalid geometry, reuse after an obstruction disappears, no feasible site remaining queued, and revision history surviving restart and older saves. These are planning/admission checks; no live-server or model startup is part of the focused selection.

Alpha.23 adds focused task/proposal continuity contracts to `design tasks settlements`: exact supply-parent resumption after release/restart, atomic claims, merge migration, persistent retry evidence, normalization of closed wall notation and gate orientation, durable deferred proposals, and shared work access with exclusive construction/interior reservations. These tests exercise the production persistence, admission and claim code with synthetic terrain. They do not establish live-model design quality or physical construction speed; no Folia startup is needed for these persistence and planning contracts.

Alpha.22 adds `cooperation`: one disposable-world scenario covering multi-layer soil/tree preparation, a paid floor placement, a complete three-block-high wall with its gate, courier travel while the donor retains unfinished work, resumption of that work, and a crafted/placed/registered storage expansion. It checks actual blocks, inventory conservation and existing chest contents. It uses specified projects and supplies to isolate the executor; it does not claim that a local language model designed those projects autonomously.

Alpha.21 extends the existing `progress` case to omit model VERIFY deliberately, then verifies the host checks the real recovery result. After sand-to-glass it runs charcoal-to-torch preparation with actual fuel, intermediate output collection, final crafting/placement and exact leftovers. This single workflow targets the intermediate-batch regression; it does not benchmark live-model reasoning or complete wall construction.

Alpha.20 adds `progress`: a deterministic recovery beyond the original map followed by actual sand gathering, furnace crafting/placement, native smelting and glass repair. For changes confined to farm clutter, run that scenario with `-Dcivilizations.test.farm-preparation=true`; it skips the recovery/glass setup and checks the dandelion-to-wheat action with real drops and a consumed seed. These selectors never start automatically during section checks.

Folia scenarios must use a disposable world, never the live server. Build the fixture only when a relevant physical check is needed. Select exactly one scenario:

```powershell
java -Xmx2G '-Dcivilizations.test.scenario=navigation' -jar folia.jar nogui
```

The fixture refuses to run without an explicit scenario. See `Civilizations/integration-test/README.md` for setup and the full scenario list. In particular:

- `navigation` combines detouring, bounded dirt clearance and repair into one setup. It replaces repeatedly running both simple repair and terrain repair for the same navigation change. It no longer runs the crafting-station test afterward.
- `crafting` starts directly at the station recovery case, loading nine fixture chunks instead of the repair route's seventy-seven. It has no preceding wall/repair journey.
- `autonomy` is for the gather/craft/work loop. `inference-busy` uses a fake backend to check that thinking does not freeze that loop.
- `manual-village` is exploratory observation, not a pass/fail gate. Model probes in the test source tree are manual tools, outside routine selection.

Do not repeat server startups for documentation, logging-format-only or test-selection changes. For a movement or block-action change, use one relevant physical case that checks position, actual block/item changes and retained resources. If it fails, fix the failure and rerun that case. A server version change warrants compatibility checking on the target version; there is no automatic matrix of old versions.

## Broad verification and cleanup record

```powershell
python tools/test_sections.py all
```

Use this for shared infrastructure changes, intentional broad verification or a release check. After it passes, package with `mvn -f pom.xml package -DskipTests` only if an artifact is needed; do not run the same tests again merely to produce the JAR. Plain Maven test/package retains its conventional full-suite behavior; use the runner for curated selection.

The September 14 cleanup removed the obsolete straight-line `RouteProgress` test and two tests of `ToolRecipes.next`, which no longer drives villager crafting. Oak and birch bootstrap checks now share one parameterized test of **CraftingBook**. Stone-upgrade coverage now exercises **WorkerPlan + CraftingBook**, and still checks real ingredient costs. Active tool-tier and surplus contracts were retained. Journal/receipt checks moved into `DiagnosticsTest` and `StorageExchangeTest`, separate from `CraftingTest`.

The previous suite had 117 cases. The curated suite has **116 relevant cases**; the aim is relevance and selecting fewer cases per change, not reducing the count artificially. The all-section validation passed in 5.25 seconds including Maven overhead; navigation alone ran **13 cases in 2.95 seconds** on this machine. These are observed runs, not a controlled performance benchmark. The separated Folia harness compiled; no server/model run was started for this test-only cleanup. Existing alpha.14 physical evidence remains historical and is not presented as a new run of the reorganized fixture.

Alpha.15 adds `live-skill` for the generated-program executor and a focused exporter contract: `python CoreAI/tools/test_export_outcomes.py`. The skill scenario uses a deterministic proposal and actual Folia block/inventory/movement checks. Run it for changed executor behavior, not routine data-format edits.

Alpha.16 adds `rule-learning`: two repairs through the actual worker loop, physical classification/clearance, ingredient costs and rule reuse. By default the teacher is deterministic; setting the fixture-only `CIV_TEST_MODEL_TOKEN` uses the already-running loopback DeepSeek at port 8652. Never put that credential in source or logs. This is an explicit physical/model check, outside routine section runs.
