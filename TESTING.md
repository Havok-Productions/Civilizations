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
