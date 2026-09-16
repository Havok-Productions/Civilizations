# Commanded task probes

Stand near a controlled villager and run `/civ debug probe`. This selects its current task, or the nearest available queued task, and commands the ordinary worker to attempt it for a 120-second observation window. It uses actual inventory, community supplies, gathering, crafting, smelting, navigation and construction as required by that task.

- `/civ debug probe status` shows the nearest worker's latest result.
- `/civ debug probe jobs` lists its nearby unfinished tasks and IDs.
- `/civ debug probe start nearest <job-id> 180` attempts that specific queued task for a three-minute window.
- `/civ debug probe cancel` ends the diagnostic assignment. Normal village work continues.

Console commands replace `nearest` with a worker UUID from `/civ debug details`. For example: `civ debug probe start <worker-uuid> <job-id> 120`. All entity/world access is scheduled on that villager's owning Folia region. The existing `civilizations.admin` permission applies.

The chosen task remains the probe's goal while ordinary prerequisite gathering and recovery run. Existing interrupted work is checkpointed. Task claims, predecessor order, retry cooldowns, bedtime, village pause and physical/protection checks still apply and appear in the report. A rejected layout without an admitted construction job cannot be executed by this command; use `/civ debug design` to inspect that planning failure.

Results are written to `plugins/Civilizations/debug/probes/events.jsonl`, independently of general debug logging, with five-second observations, failure evidence and a final outcome. Final results also appear in the server log. Evidence includes task/target, elapsed time, position, maximum observed displacement from the starting position, inventory delta, observed target block, navigation evidence and the last failure. Displacement can include vanilla wandering; it is not proof of successful task navigation.

`PASS` requires the selected task's actual executor to commit a verified world change. Movement, a model response, inventory changes, a supply subtask or another worker completing the task cannot independently pass the probe. `ALREADY_SATISFIED` means no new action was demonstrated. `TIMEOUT` means the observation window ended without verified completion, not that the task is impossible. `CANCELLED` and `INTERRUPTED` retain their reasons. Probe expiry releases the diagnostic preference; it does not delete jobs, undo work, restore items or stop ordinary village activity. Results are diagnostic execution evidence, not a benchmark of autonomous model planning.
