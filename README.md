# Hearth

**AI-assisted villager collective for Minecraft (Folia / Paper, Java 25, Folia API 26.2 — latest).**

Hearth turns a group of villagers into a small self-sufficient collective:

- 🧱 **Builds a wall ring** around the village (configurable material/height, with a gated door) so mobs can't get in
- 🔧 **Repairs the wall** when blocks are missing (mobs, players, explosions)
- ⛏️ **Digs a dry underground mine** — staircase + room-and-pillar tunnel at a configurable depth. It *never* routes through water, lava, bedrock, or house blocks
- 💡 **Lights the village and the mine** (default: glowstone; torches work too — villagers gather sticks from logs and coal from coal ore and assemble them)
- 📦 **Places a community chest automatically** if the village doesn't have one, and villagers deposit everything they gather there
- 🌙 **Goes to bed at night** (sleep window matches vanilla: ~5:20 PM → 7:00 AM) and wakes in the morning
- 🏃 **Moves at normal villager speed** — small velocity nudges + occasional natural "step", never 100 mph teleport-zoomies
- 🤖 **Optional AI advisor** (ChatGPT **or** DeepSeek): each village sends the model a JSON report (wall integrity, chest stockpile, mine progress, nearby monsters, time of day) and the model picks the next priority task. Local safety rules always override the AI (villagers always sleep at night, always flee mobs).

Hearth is an **original implementation**. It borrows *ideas* from two well-known projects (Baritone's budgeted A* pathfinding and behavior concepts; Civilizations' villager task-management approach) but contains no copied code.

---

## How it works

```
┌────────────────────────────────────────────────────────────────────┐
│  VillageManager   detects villages (flood-fill villager clusters)  │
│       │                                                            │
│       ▼                                                            │
│  Village          state: wall jobs, mine plan, chest, beds, stats  │
│       │                                                            │
│       ▼                                                            │
│  PriorityPolicy   local heuristics + optional LLM advice           │
│       │            (safety rules always win)                       │
│       ▼                                                            │
│  VillagerBrain    per-villager state machine                       │
│       │      IDLE → TRAVEL → WORK → (carry → chest) → IDLE         │
│       │      + SLEEP (night) + FLEE (mob nearby)                   │
│       ▼                                                            │
│  PathStore        budgeted, resumable A* (Baritone-style)          │
│       │        split across ticks so the server never hitches      │
│       ▼                                                            │
│  MovementController  normal-speed walking, jump physics,           │
│                      stuck-detection with one small "step"         │
│                                                              │     │
│  Planners         WallPlanner · MinePlanner · LightPlanner         │
│  AIAdvisor        LLMAdvisor (OpenAI-compatible: OpenAI/DeepSeek)  │
└────────────────────────────────────────────────────────────────────┘
```

### Folia threading model (genuinely region-safe)

Hearth is written for Folia's region-threaded runtime, not just its API:

- **Per-villager brain** — each brain ticks on the *villager's own entity
  scheduler* (`Entity#getScheduler`). Folia keeps the task attached when the
  villager crosses region boundaries, and every entity operation (inventory,
  teleport, velocity, sleep) happens on the region that owns the villager.
- **Per-village coordination** — one region task bound to the village center
  owns village-level state (stats, wall/mine/light plans, task resolution).
- **Discovery** — village/villager rescan runs on the *global region* thread
  and only **reads** the world; it never writes blocks.
- **Cross-region writes are routed** — anything that mutates the world at a
  location that may live in a different region (chest placement, chest
  inventory transfers, block placement/breaking, item drops) is submitted
  through `HearthPlugin#runInRegion` onto the owning region's thread, where
  the world state is re-validated before the write. Chest→villager transfers
  are split: the chest write lands on the chest's region, the inventory write
  is queued onto the villager's own region.
- **Async AI** — the only off-thread work is the LLM HTTP call; its callback
  is marshalled back to the village center's region before it touches village
  state.

No world/entity access is ever scheduled on the legacy global
`BukkitScheduler` (the classic reason Folia plugins break at runtime), shared
mutable state uses `ConcurrentHashMap`/`volatile`/locks, and rescans **merge
into** existing villages instead of leaking duplicates.

### Pathfinding (original, Baritone-inspired)

- A* over block nodes with a **per-block cost model**: horizontal 1.0, jump 2.0,
  small drops cheap, big drops heavily penalized (villagers prefer stairs),
  water +2.0 (they swim), lava/fire never, leaves/cobwebs slow.
- **Budgeted & resumable**: each server tick the whole server gets a fixed
  number of node expansions (default 6 000), split across all active paths.
  Long paths continue on later ticks — no lag spike, Folia-friendly.
- **Path smoothing** is implicit: villagers snap to waypoints and the
  controller walks straight between them.
- Re-pathing: stuck detection (no progress for 3 s → one small step; 3 failed
  re-paths → give up that target), timed-out travels abort to IDLE.

### Movement (why they look like normal villagers)

- `settings.movement.max-speed` (default **0.22 blocks/tick ≈ 2.2 blocks/s**,
  a brisk but believable walk) is a hard cap.
- Movement is real velocity (gravity/jumps/friction stay vanilla).
- When stuck, they take **one** natural step (≤ 2.2 blocks) — not a map-wide teleport.
- They face their travel direction.

### Mining (why it's never underwater or in a house)

`MinePlanner` tries up to `mining.max-route-attempts` candidate routes. For each
candidate it verifies **every block of the 3-wide × 3-tall cross-section** is
diggable: not water, not lava, not bedrock, not a house block (planks, glass,
doors, chests, beds, furniture, …). Only a fully clean route is dug.
Room-and-pillar: one full pillar is left every `mining.pillar-every` columns so
the ceiling holds.

### AI advisor

- Enabled with `ai.enabled: true` + an API key.
- `provider` is just a label — what matters is `base-url` + `model`:
  - **DeepSeek**: `https://api.deepseek.com/v1` + `deepseek-chat` (default)
  - **OpenAI/ChatGPT**: `https://api.openai.com/v1` + `gpt-4o-mini` (or any model)
- The model receives a compact JSON report + the "village charter" (editable in
  `config.yml`) and must answer `{"task": "...", "reason": "..."}`.
- Advice is cached per village for `ai.interval-minutes`; if the model is down,
  local rules keep the village running.
- Test with `/hearth ai test`.

---

## Building

Requires **Java 25** and **Maven 3.9+** (the Folia 26.x API is Java-25 bytecode).

```bash
cd hearth
mvn package
```

Output: `target/Hearth-1.0.0.jar` → drop into your Folia/Paper `plugins/` folder.

> **Version note:** this project is built against the **latest Folia API**,
> `dev.folia:folia-api:26.2.build.7-beta` (the `<release>`/`<latest>` in
> PaperMC's maven metadata as of this writing), and ships
> `api-version: '26.2'`. It loads on a **Folia 26.2** server running **Java 25**.
> Because the Folia API extends the Paper API, the jar should also load on a
> Paper server of the same `api-version` (`folia-supported: true` is simply
> ignored by Paper).
>
> - Still running the latest **stable-suffixed** line (26.1.x)? Set the
>   dependency in `pom.xml` to `dev.folia:folia-api:26.1.2.build.8-stable`
>   and `api-version: '26.1'` in `plugin.yml`, then rebuild — the project
>   compiles cleanly against both.
> - Verified with `mvn clean package` (BUILD SUCCESS) on both 26.1.2 and
>   26.2, `release 25`.

## Setup

1. Spawn (or find) some villagers in one area.
2. Put `Hearth-1.0.0.jar` in `plugins/`, start the server.
3. Watch them:
   - place the community chest,
   - build the wall + gate,
   - dig the mine, light everything,
   - then go to bed when it gets dark.

### Commands

| Command | Description |
|---|---|
| `/hearth help` | Help |
| `/hearth list` | List villages |
| `/hearth status [villager]` | Village + per-villager brain status |
| `/hearth wall` / `mine` / `light` | Force the village to focus on that task (5 min) |
| `/hearth chest` | Show community chest info |
| `/hearth ai on\|off\|test` | Control/test the AI advisor |
| `/hearth reload` | Reload `config.yml` |
| `/hearth stop` | Pause the plugin |

Permissions: `hearth.admin` (default: op), `hearth.player` (default: everyone).

## Tuning (config.yml highlights)

| Key | Default | Meaning |
|---|---|---|
| `settings.movement.max-speed` | 0.22 | Walk speed cap (blocks/tick) — keep it low so they look normal |
| `settings.performance.pathfind-budget-per-tick` | 6000 | A* expansions per server tick (all villagers combined) |
| `villages.radius` | 48 | Village detection radius |
| `walls.material` | STONE | Wall material (mines straight off stone; try COBBLESTONE, DIRT, OAK_LOG) |
| `walls.height` | 4 | Wall height |
| `mining.depth` | -40 | Tunnel depth (deepslate ore zone) |
| `mining.length` | 64 | Tunnel length in columns |
| `lighting.material` | GLOWSTONE | Light source (TORCH works too — they gather sticks+coal) |
| `sleep.sleep-from` / `sleep-until` | 17000 / 7000 | Sleep window (Minecraft time) |
| `ai.enabled` | false | Turn on the ChatGPT/DeepSeek advisor |

## Honest limitations (v1)

- Villagers "mine" by block simulation (the plugin hands them the expected item) —
  no tools, no smelting, no crafting table. Torch assembly is the only crafting.
- One mine per village, one wall ring; no multi-floor builds yet.
- The AI advisor influences **task priority only** — it can't invent new behaviors.
- If a chunk is unloaded, villagers can't path there (they wait / re-route).

## License

MIT — do whatever you want, no warranty.
