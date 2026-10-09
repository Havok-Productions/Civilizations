# Alpha.14: mapped navigation and failure evidence

Villagers now request an immutable terrain map with a **20-block horizontal radius** (41 by 41 columns), with walkable positions within 10 blocks above/below their current height. Floor/head margins are also captured. Unknown or unloaded chunks remain unknown; navigation never force-loads them.

An original bounded A* search finds connected walking, one-block ascent/descent and door/gate transitions. It can initially move away from its goal to escape an enclosure. Long journeys use successive local maps. Native villager pathfinding executes short segments at the configured ordinary speed. A route proposal is distinct from observed arrival.

During active work the native movement adapter renews a ten-tick Folia activation lease on the entity's owning region. Otherwise, server entity-activation throttling can run the plugin scheduler while suppressing the villager's movement ticks. The lease stops renewing during rest, sleep, fleeing and shutdown, and does not modify server-wide activation settings.

## Reasonable salvage

Walking around obstacles is tried first. If there is no route, a second search may clear **at most four natural blocks per mapped route**, one action per configured work interval. It considers dry dirt/grass/rooted dirt/podzol/coarse dirt, nonpersistent leaves and logs with natural root-and-canopy evidence. It rechecks live material, region ownership, natural origin, nearby architecture, recorded player placements, reserved work, protection events and inventory capacity immediately before acting. Soil supporting a nonnatural overhead block is excluded. Actual drops enter the villager inventory; successful clearing has an explicit receipt.

This does not authorize demolition of buildings, arbitrary tunneling, clearing water or a whole forest. A complex terrain feature can still need a different project or manual intervention. The map is a conservative block approximation; native collision checks remain authoritative.

## Failure memory and recovery

Each village shares up to 256 rejected transitions. They are persisted in `debug/navigation/maps/failed-transitions.json`, expire after five minutes, and are reconsidered sooner when their local block signature changes. Village caches are capped at 128. Failed destination searches also use the existing task/route cooldowns. These are bounded retries, not permanent avoidance of an area after one failure.

Native movement has a ten-second step deadline, obstacle actions a fifteen-second wait limit, and requests a ninety-second no-progress deadline. Six unsuccessful execution recoveries return control to task recovery. Explicit errors distinguish missing paths, wrong endpoints, unknown regions, hazards, protection refusals, changing material, inventory limits and search exhaustion. Inaccessible shared crafting tables are skipped in favor of another observed/local station; if necessary the villager makes and places a nearby table using real ingredients. Existing alternate-community-chest selection uses the new routes.

## Reading the evidence

- `debug/failures/events.jsonl`: separate rotating failure journal, enabled even when ordinary debug is disabled. Includes task failures, navigation rejects, failure-bearing design/inference/transfer events, worker exceptions with stack frames, and controlled-villager deaths with cause and last navigation evidence. It cannot capture failures inside unrelated plugins or a process crash before a record is written.
- `debug/events.jsonl`: normal chronological work, movement plans and verified obstacle/transfer receipts, when `debug.enabled` is true.
- `debug/navigation/maps/map-N.json.gz`: 128 compressed rolling map slots containing block classifications, the proposed route, search rejection counts and examples. Match the embedded map ID to the failure event; slots are reused. Map capture for general task failures is limited to once per worker per ten seconds, with explicit throttling/queue errors.
- `CoreAI/data/`: existing observations and actual outcome receipts. These remain collected evidence, not an automatically trained neural model. Mere plans or claims are not successful demonstrations.

The failure journal rotates at 8 MiB with four older files. Archive admission is bounded; dropped map requests and writer errors are reported. Four searches can be in flight and each search expands at most 12,000 states. Diagnostics intentionally do not dump model keys or tokens.

The included standard-library Python tool displays a historical horizontal map slice:

```powershell
python tools/inspect_navigation.py "C:/path/to/server/plugins/Civilizations" --map map-0.json.gz
```

Use `--y 65` for another captured height. Omit `--map` to inspect the newest slot. The output identifies solids, natural salvage candidates, hazards, doors, unknown cells and proposed route positions.

## References and verification

The design draws on [Baritone's documented A*, segmented search and world cache](https://github.com/cabaletta/baritone/blob/master/FEATURES.md) and the [older Civilizations/Hearth path budget and snapshot approach](https://github.com/Havok-Productions/Civilizations/blob/master/src/main/java/dev/hearth/path/PathStore.java). Baritone's cached geometry is not a ready-made neural training dataset. Its source has its own license; no Baritone implementation code or binary was imported. This remains the separate `dev.civilizations` Folia plugin.

The activation fix was checked against Folia's own runtime bytecode and [Paper's entity activation implementation](https://github.com/PaperMC/Paper/blob/main/paper-server/patches/features/0003-Entity-Activation-Range-2.0.patch). It is a narrow version-sensitive native adapter; incompatibility produces an explicit failure event.

Alpha.14 passed 117 unit tests. Disposable Folia fixtures exercised a U-shaped solid obstacle, multiple local maps, a natural dirt barrier, retained drops and an actual repair. Folia 26.2 build 7 completed the route/repair in approximately 27 seconds after the activation fix, compared with approximately 85 seconds before it. The same workflow passed on the user's Folia 26.1.2 build 8 in approximately 25 seconds, followed by actual local-table and stone-pickaxe crafting after rejecting an inaccessible shared table. There were zero routine model calls and no navigation failures in that final compatibility run. See `verification/alpha14/` for logs and hashes. The final package adds death-event diagnostics after the physical run; all 117 tests were rerun afterward. The user's complete live village still requires observation after the normal restart.

## Alpha.16 learned rules

Maps distinguish `HAZARD`, `FLUID`, `OBSTACLE`, `UNCLASSIFIED` (observed material needing investigation), `UNKNOWN` (unobserved), and `CLEARABLE`. The inspector displays these separately. Clearance-only clutter never counts as a supporting floor. `CLASSIFY` and `SEARCH` programs may trial new classifications and loaded search radii up to 48 blocks; only verified trials publish the changes. Exact block-state rules and search radius are persisted in `CoreAI/rules/terrain.json`. See the CoreAI README for probes, action checks and rollback.
