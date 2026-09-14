# CoreAI framework 0.2

CoreAI 0.2.2 accompanies Civilizations alpha.21. `SkillProgram.parse` supplies the host's final VERIFY when an otherwise usable program omits it. Explicit verification still must be last, and success still requires executor evidence. This avoids discarding actions solely because a model forgot the terminator; it does not mark the program successful.

CoreAI 0.2.1 accompanies Civilizations alpha.20. It adds the editable `navigation.clearance_blocks` parameter; the host's shared navigation executor consumes it during salvage searches. Agent lifecycle and backend interfaces remain compatible with 0.2.0. See Civilizations/ADAPTATION.md for the Minecraft recovery and material-processing changes.

CoreAI provides a reusable agent lifecycle and a shared inference scheduler. It requires Java 25 and Gson; it has no Bukkit, Folia, Minecraft server, model weights or network-process dependency. Civilizations alpha.19 supplies the Minecraft adapters and the existing local model runtimes.

## Boundaries

| Component | Responsibility | Civilizations integration |
|---|---|---|
| `Observation`, `Facts` | Freeze observed state with agent/environment identity and capture time | Position, inventory, shared stock, observation age and threats copied on the worker's region |
| `Goal`, `Action`, `AgentFrame` | Describe the desired state and supported actions for that observation | A claimed job becomes a specific step in its village project |
| `AgentPorts.Perception`, `Planning` | Let a host supply observations and feasible work | `VillagerMind` translates Minecraft work without accessing Bukkit objects |
| `AgentPorts.ActionExecutor` | Dispatch work on the host's calling thread and return an asynchronous receipt | Existing worker gathering, crafting, navigation and block executors continue on Folia's owning region |
| `AgentSession` | Correlate observation, selected action and eventual verified result | One session per worker; reset, retirement and shutdown cancel pending attribution |
| `AgentPorts.Memory`, `RecentMemory` | Store completed experiences and optionally forward them to persistence | Recent experiences inform recovery reports; the existing CoreAI IO queue writes the journal |
| `ReasoningBackend`, `InferenceScheduler` | Swap inference transports while preserving one shared request queue | The Minecraft `ModelBackend` and `InferenceQueue` are compatibility adapters for existing Qwen/DeepSeek routing |
| Existing policy/skill libraries | Replay, trial, verify, retain and roll back adaptations | Existing policy ranking, terrain classification, search and tuning consumers remain connected |

Routine work does not automatically request model inference. Existing host planning/ranking chooses work; the shared scheduler is used when a caller explicitly submits planning or recovery. A host can use a different model implementation without rewriting the agent lifecycle. Hosts without a language model can still use observations, actions and memory.

The framework adds no numeric proposal ranges. Executable action identities must belong to the current host frame. A model proposal remains a hypothesis until the host implements/validates it and an executor supplies evidence. CPU, memory, actual terrain, supported operations and inventory still determine whether execution is possible.

## Lifecycle

1. Capture an observation on the application's owning thread. `Facts` serializes nested data immediately; later mutations cannot change the recorded observation. JSON access returns a fresh tree.
2. Supply a frame containing goals and currently feasible actions. Domain-specific rules and policy ranking belong to the host/adapters.
3. Start a selected action. The executor's `start` method must dispatch quickly, without waiting for its long-running work. The framework creates an attempt identity and consumes that frame.
4. Complete the returned future only when the executor has observed success or failure. Success requires evidence. A goal step completing does not mean its whole village project is finished.
5. Store one experience for that attempt. Cancellation has its own status; a late reply cannot overwrite a newer action or become a learning success.

The host owns scheduling, action deadlines, stopping physical work and world rollback. `AgentSession.cancel` cancels attribution and rejects late receipts; it does not undo a world edit or stop arbitrary host machinery. Civilizations calls it when its worker actually resets/stops. No new worker thread or per-villager model process is created.

## Reuse outside Minecraft

`AgentFrameworkTest.independentHostPerceivesPlansExecutesAndRemembersWithoutAWorldOrModel` is an executable warehouse example: an application observes a box, offers delivery, starts a host action, verifies storage and records the experience. It runs with only CoreAI's dependencies. The other framework checks cover mutable inputs, cancellation/late replies, wrong-agent frames, stale observations, executor failures and a replacement inference backend.

To integrate another application, implement the perception/planning/action ports with that application's capabilities and owning-thread rules, then supply a memory sink. Reuse `InferenceScheduler` only if model reasoning is useful. Keep application-specific actions, credentials and IO in its adapter.

## Evidence and inspection

With CoreAI enabled, Civilizations writes agent experiences to `plugins/Civilizations/CoreAI/data/agents.jsonl`, using the existing rotating journal and IO queue. Each entry contains the immutable pre-action observation, goal, selected action, attempt identity, timestamps, outcome status and executor evidence. The debug journal also records `agent_experience`; `/civ inspect` includes active framework action and recent verified experience count. Recovery prompts receive compact experience summaries, not an extra inference call.

The 16-entry working memory lasts for the current worker session. The experience journal is durable diagnostic/training input; it is not automatically loaded as a neural model. Existing village memories, learned policies and terrain rules retain their existing persistence/reuse behavior. A verified goal state can already exist when the worker checks it; experience success alone must not be counted as a newly placed block. Check item/world-change evidence when exporting training labels.

## Validation

Run `python tools/test_sections.py coreai inference tasks diagnostics` from the repository root. The alpha.19 selection passed 87 JVM cases, including the existing queue/model-routing contracts. Package separately with `mvn -f pom.xml package -DskipTests` when an artifact is needed.

The opt-in physical integration check uses the existing disposable `rule-learning` scenario with `-Dcivilizations.test.framework=true`. It verifies two real cluttered repairs, inventory consumption, retained drops, learned-rule reuse and an observed-block receipt in each worker's CoreAI experience. It uses a deterministic teacher, not a model-quality benchmark or a new full-village trial.

The alpha.19 physical run passed. Both repair experiences were verified in working memory and the persisted `agents.jsonl`: two planks consumed, two string retained, observed OAK_PLANKS at both targets, and one deterministic inference request with learned-rule reuse.
