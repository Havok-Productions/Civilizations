# Player AI: first milestone

`/playerai` spawns a human-looking **mannequin NPC** near the command sender. It is a
server entity with a player appearance, not a logged-in Minecraft account. It uses
the existing Folia API and needs no Citizens, packet library, skin download or LLM.

Start on dry, open ground with natural trees nearby. The NPC looks for a flat,
empty 5x5 work area, gathers six ordinary logs, crafts 24 planks, and builds a
3x3 open-sided canopy (17 planks) and a workbench (four planks). Three planks remain
in its inventory. Different supported wood species can be mixed. The canopy is a
small bootstrap milestone, not an enclosed survival house.

Each block is approached on foot and checked live before modification. The actor
stops at this milestone rather than starting unlimited construction. Missing
resources, changed terrain, denied actions and inaccessible targets produce a
visible `BLOCKED` reason with a ten-second retry delay. Failed tree targets have
a separate one-minute cooldown so another tree can be considered.

## Commands and settings

All commands require `civilizations.playerai.admin` (operators by default).

| Command | Behavior |
|---|---|
| `/playerai` | Spawn a pioneer with a generated name |
| `/playerai spawn Alex` | Spawn with a chosen 1–16 character name |
| `/playerai list` | Show loaded NPC identities, stages, progress and inventory |
| `/playerai status [name/UUID]` | Inspect all loaded NPCs or one specific NPC |
| `/playerai pause <name/UUID>` | Stop work and persist the pause |
| `/playerai resume <name/UUID>` | Continue from existing resources and progress |
| `/playerai remove <name/UUID>` | Remove that NPC and drop its remaining resources |

Use a full profile UUID if multiple loaded NPCs have the same name. Spawning is
in-game only; the other commands also work from console. Removing an NPC leaves
its construction in the world.

```yaml
player-ai:
  enabled: true
  max-active: 8
  search-radius: 16
```

The active limit is for loaded NPCs (clamped to 1–32). Extra saved NPCs stay inactive
until capacity is available and discovery revisits their chunk. Search radius is
clamped to 12–24 blocks. No chunks are forced to load. Chunk unloading suspends work;
loading and periodic discovery attach to the existing entity without spawning a
replacement. Disable the feature to prevent spawning and attachment.

## Ownership and organization

| Class | Responsibility |
|---|---|
| `PlayerAi` | Per-player brain, staged progression, inventory transactions and durable state |
| `PlayerAiCoordinator` | Core lifecycle coordinator, commands, population budget, reservations and persistence |
| `PlayerAiProfile` | Stable UUID, generation seed and validated display name |
| `StarterCamp` | Ordered blueprint, resource budget and explicit standing positions |
| `MannequinActions` | World adapter for gathering and placement, live validation and action receipts |
| `PlayerAiNavigation` | Bounded snapshot-based route search and ordinary velocity-based walking |
| `PlayerAiWorkEvent` | Cancellable integration point for NPC-specific protection policies |

The brain knows no Bukkit types. `PlayerAi.Actions` is the boundary between deciding
and executing; only confirmed action receipts update its resources and progress.
Snapshots are immutable. All body, inventory-state and world mutations run on that
body's Folia entity scheduler. Region snapshots and route planning reuse the existing
terrain and A* code, with a separate two-thread planning pool. No native villager
worker is enrolled and no village model request is needed by this feature.

The existing plugin's model configuration still controls its villager systems.
Turning on player AI does not disable or change those existing model settings.

## Persistence, materials and protection

Versioned JSON lives in the mannequin's `playerai_state` persistent-data key. It
contains its profile, camp origin, completed placement count, inventory and pause
state. Every successful harvest, craft, placement and pause updates that key;
Minecraft saves it with the entity. This supports normal chunk unload/reload and
clean server restarts; it is not an atomic transaction across world and entity
files after a hard crash. Invalid or unsupported state is rejected and retained
for inspection, not reset to a new NPC.

Mannequins do not have a player inventory. This milestone owns a bounded logical
inventory of real harvested logs and crafted items. It does not grant starter
resources. Natural death and explicit removal drop remaining resources. It does
not collect arbitrary item entities, use community storage or wield tools yet.

Only supported ordinary natural logs are harvested, with soil-root and foliage
checks, visible reach, dry surroundings and matching live block state. Placement
requires air, visible reach and no living entity in the target volume. Campsites
are reserved between loaded player AIs. Recorded human placements and settlement
construction are protected. Human-placed logs observed while this feature is
enabled receive durable chunk provenance outside settlements too; old unrecorded
structures cannot be identified perfectly from their shape alone.

Both Bukkit `EntityChangeBlockEvent` and `PlayerAiWorkEvent` are fired before world
mutation. A protection plugin should cancel one of these for its claim policy.
This is not a claim that every third-party protection plugin already handles them.

## Incremental roadmap

1. **Current: controlled bootstrap.** Manual spawn, generated names and seeds,
   nearby wood, hand crafting, one small construction milestone, status/pause,
   receipts and clean-restart persistence.
2. **Survival and tools.** Add ordered goals for food, beds, wooden/stone tools,
   mining and an enclosed shelter. Use the existing crafting catalog and mining
   prerequisites through body-independent action adapters. Do not fork the full
   villager worker into a second giant brain.
3. **Reliable exploration and recovery.** Add larger staged surveys, doors,
   terrain preparation, swimming/escape, better interaction positions and repair
   of completed structures. Keep per-action budgets and failure reasons.
4. **Generated people.** Expand the profile with a generator version, appearance,
   personality, skill affinities and needs derived from the persisted seed. Keep
   identity independent of entity UUID. Add explicit schema migrations before
   changing saved fields or blueprint ordering.
5. **Settlement participation.** Introduce actor-neutral inventory, navigation,
   storage and work adapters, then connect job claims, cooperation, trading and
   relationships to existing village systems.
6. **Optional learned planning and natural spawning.** Add a goal-selector boundary
   for teacher/model advice, with local execution constraints unchanged. Automatic
   population generation needs persistent global caps, biome/site admission and
   a spawn budget; it is deliberately not enabled by this milestone.

No combat, hunger model, arbitrary terrain edits, automatic repairs, player login,
custom skin service or automatic population spawning is implemented yet.

## Verification

```powershell
python tools/test_sections.py playerai navigation settlements
```

The `playerai` section covers exact material conservation, mixed-wood crafting,
reload after every receipt, denied work, retry pacing, pause/resume, durable
inventory isolation, profile generation, invalid saves, campsite admission and
ordered construction sightlines. These are JVM/fake-world and geometry contracts,
not evidence of a mannequin physically walking or building in Minecraft.

One opt-in physical check should use a disposable Folia world with inference
disabled: provide flat dry ground and two ordinary trees within range, run
`/playerai spawn Pioneer`, and observe actual movement, six missing logs, 17 canopy
planks, one workbench and three retained planks. Pause and cleanly restart halfway
through, then resume; confirm the same profile and body with no duplicate items.
Use a protection listener to deny one harvest/placement and verify unchanged
blocks and inventory. Check death/removal drops and unload/reattach. Keep the live
server unchanged until this separate check is requested and passes.
