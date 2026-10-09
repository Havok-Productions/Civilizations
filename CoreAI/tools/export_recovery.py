"""Join recovery journals into auditable demonstrations, never teacher-claimed successes.

Exports into a NEW directory. No model is called or trained. Historical proposal IDs
are inferred from receipt order and executable steps; their attribution stays explicit.
"""
import argparse
import collections
import hashlib
import json
from pathlib import Path

SCHEMA = 2
OBSERVATION_KEYS = (
    "goal_relative", "reach_squared", "levels", "level_x_coordinates", "route_height",
    "nearby_stands", "nearby_obstacle_examples", "natural_clear_candidates",
    "natural_clear_candidates_details",
)


def canonical(value):
    return json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=False,
                      allow_nan=False)


def digest(value):
    return hashlib.sha256(canonical(value).encode("utf-8")).hexdigest()


def program_key(program):
    return digest(program.get("steps", [])) if isinstance(program, dict) else None


def provenance(data):
    raw = data.get("teacher_provenance", data.get("teacher"))
    if isinstance(raw, str):
        try:
            decoded = json.loads(raw)
        except (ValueError, TypeError):
            decoded = None
        if isinstance(decoded, dict):
            raw = decoded
    if not isinstance(raw, dict):
        return {"model": raw, "artifact": None, "sha256": None, "license": None,
                "knowledge_basis": None, "source": "historical_unknown"}
    return {"model": raw.get("model"), "artifact": raw.get("artifact"),
            "sha256": raw.get("sha256"), "license": raw.get("license"),
            "knowledge_basis": raw.get("knowledge_basis", raw.get("knowledgeBasis", raw.get("use"))),
            "identity_basis": raw.get("identity_basis", "historical_recorded_provenance"),
            "source": "recorded_provenance"}


def exact_provenance(value):
    sha = value.get("sha256")
    return (all(value.get(k) for k in ("model", "artifact", "license"))
            and isinstance(sha, str) and len(sha) == 64
            and all(c in "0123456789abcdefABCDEF" for c in sha))


def load_receipts(roots):
    receipts, seen, inputs = [], {}, []
    lesson_manifests = {}
    stats = collections.Counter()
    paths = [(index, root, path) for index, root in enumerate(roots)
             for path in sorted(root.rglob("experiments*.jsonl"))]
    for index, root, path in paths:
        relative = path.relative_to(root).as_posix()
        name = relative if len(roots) == 1 else f"source-{index}/{relative}"
        cases = []
        lesson_path = path.parent.parent / "lesson.json"
        if lesson_path.is_file():
            lesson_bytes = lesson_path.read_bytes()
            lesson_hash = hashlib.sha256(lesson_bytes).hexdigest()
            lesson = json.loads(lesson_bytes)
            layout = lesson.get("layout", {})
            declared = layout.get("split")
            if declared not in (None, "train", "validation", "test", "holdout"):
                raise ValueError(f"Unknown evaluation split in {lesson_path.name}: {declared}")
            lesson_manifests[lesson_hash] = {"sha256": lesson_hash, "content": lesson}
            cases = [{"name": layout.get("name"), "lesson": lesson.get("lesson"),
                      "declared_split": "test" if declared == "holdout" else declared,
                      "manifest_sha256": lesson_hash}]
        content = path.read_bytes()
        inputs.append({"file": name, "bytes": len(content),
                       "sha256": hashlib.sha256(content).hexdigest()})
        for number, line in enumerate(content.splitlines(), 1):
            stats["input_lines"] += 1
            try:
                event = json.loads(line)
                if (not isinstance(event, dict) or event.get("schema") != 1
                        or not isinstance(event.get("data"), dict)
                        or not isinstance(event.get("trial"), str)
                        or not isinstance(event.get("time"), (int, float))):
                    stats["unsupported_receipts"] += 1
                    continue
                key = digest(event)
            except (ValueError, TypeError, UnicodeError):
                stats["malformed_lines"] += 1
                continue
            reference = {"file": name, "line": number, "sha256": key}
            if key in seen:
                seen[key]["references"].append(reference)
                for case in cases:
                    if case not in seen[key]["cases"]:
                        seen[key]["cases"].append(case)
                stats["duplicate_receipts"] += 1
                continue
            receipt = {"event": event, "references": [reference], "sha256": key, "cases": cases}
            seen[key] = receipt
            receipts.append(receipt)
    priority = {"requested": 0, "proposed": 1, "reuse": 1, "revision_proposed": 1,
                "step": 2, "program_outcome": 3, "revision_requested": 4,
                "observation_requested": 4, "outcome": 5, "cancelled": 6}
    receipts.sort(key=lambda r: (r["event"]["time"],
                                priority.get(r["event"].get("event"), 7), r["sha256"]))
    stats["unique_receipts"] = len(receipts)
    return receipts, inputs, stats, list(lesson_manifests.values())


def evidence_item(receipt):
    event = receipt["event"]
    return {"event": event.get("event"), "time": event["time"], "data": event["data"],
            "references": receipt["references"], "cases": receipt["cases"]}


def assemble(trial, receipts):
    """Retain each admitted program version, with pre-revision failures on that version."""
    records, active, initial, pending = [], None, None, None
    explicit = {}
    cases = list({canonical(case): case for receipt in receipts for case in receipt["cases"]}.values())
    proposal_times = {r["event"]["time"] for r in receipts
                      if r["event"].get("event") in ("proposed", "revision_proposed", "reuse")}

    def new_record(receipt, program=None, proposal_id=None, observation=None):
        e, d = receipt["event"], receipt["event"]["data"]
        identifier = proposal_id or "historical-" + receipt["sha256"][:20]
        record = {
            "schema": SCHEMA, "id": trial + ":" + identifier, "trial": trial,
            "village": e.get("village"), "worker": e.get("worker"),
            "context": e.get("context"), "first_time": e["time"],
            "cases": cases,
            "program_context": d.get("program_context"), "original_context": d.get("original_context"),
            "proposal_id": identifier, "proposal_id_basis": "recorded" if proposal_id else "inferred_receipt",
            "attribution": "proposal_version",
            "program": program, "program_sha256": program_key(program),
            "initial_observation": initial, "current_observation": observation,
            "outcome_observation": None, "teacher_provenance": provenance(d),
            "host_evidence": [], "_labels": [], "_interrupted": False, "_issues": [],
        }
        records.append(record)
        if proposal_id:
            explicit[proposal_id] = record
        return record

    def select(receipt, program=None):
        e, d = receipt["event"], receipt["event"]["data"]
        proposal_id = d.get("proposal_id", e.get("proposal_id"))
        if proposal_id:
            target = explicit.get(proposal_id)
            if target is None:
                target = new_record(receipt, program, proposal_id,
                                    d.get("program_observation", d.get("observation")))
            elif program is not None and target["program_sha256"] not in (None, program_key(program)):
                target["_issues"].append("conflicting_program_for_proposal_id")
            return target
        if (e["time"] in proposal_times
                and e.get("event") not in ("proposed", "revision_proposed", "reuse")):
            # Millisecond timestamps cannot order legacy writes around a revision.
            target = new_record(receipt, program,
                                observation=d.get("program_observation", d.get("observation")))
            target["_issues"].append("ambiguous_legacy_same_timestamp")
            target["attribution"] = "ambiguous_legacy_timing"
            return target
        if active is not None and (program is None or active["program_sha256"] == program_key(program)):
            return active
        # Only a unique historical match is safe once rotated files lost the proposal.
        matches = [r for r in records if program is not None and r["program_sha256"] == program_key(program)]
        if len(matches) == 1:
            return matches[0]
        return new_record(receipt, program, observation=d.get("program_observation", d.get("observation")))

    for receipt in receipts:
        e, d = receipt["event"], receipt["event"]["data"]
        kind = e.get("event")
        if kind == "requested":
            initial = initial or d.get("observation")
            pending = d.get("observation")
            continue
        if kind in ("proposed", "revision_proposed", "reuse"):
            proposal_id = d.get("proposal_id", e.get("proposal_id"))
            if proposal_id and proposal_id in explicit:
                active = select(receipt, d.get("program"))
            else:
                active = new_record(receipt, d.get("program"), proposal_id,
                                    d.get("observation", pending or initial))
            active["host_evidence"].append(evidence_item(receipt))
            continue
        if kind in ("revision_requested", "observation_requested"):
            previous = d.get("previous_program")
            previous_id = d.get("previous_proposal_id")
            target = explicit.get(previous_id) if previous_id else None
            target = target or (select(receipt, previous) if previous is not None else active)
            if target is not None:
                target["host_evidence"].append(evidence_item(receipt))
                failure = d.get("failed_execution")
                if (kind == "revision_requested" and isinstance(failure, dict)
                        and failure.get("reason") and previous is not None
                        and not any(label["scope"] == "program_segment" and label["success"] is False
                                    for label in target["_labels"])):
                    target["_labels"].append({"success": False, "scope": "program_segment",
                                               "basis": "host_revision_failure"})
                target["outcome_observation"] = d
            pending = d
            active = None
            continue
        if kind in ("step", "outcome", "program_outcome", "cancelled", "rejected", "revision_rejected"):
            if kind in ("outcome", "program_outcome") and not isinstance(d.get("program"), dict):
                # Older production receipts retained a stale proposal ID while a replacement
                # was pending. Never attach that terminal result to the replaced program.
                target = new_record(receipt, observation=d.get("program_observation", d.get("observation")))
                target["attribution"] = "unattributed_terminal"
                target["reported_proposal_id"] = d.get("proposal_id", e.get("proposal_id"))
            else:
                target = select(receipt, d.get("program"))
            target["host_evidence"].append(evidence_item(receipt))
            if target["program"] is None and isinstance(d.get("program"), dict):
                target["program"] = d["program"]
                target["program_sha256"] = program_key(d["program"])
            incoming = provenance(d)
            previous = target["teacher_provenance"]
            if exact_provenance(incoming):
                if (exact_provenance(previous)
                        and (previous["sha256"].lower(), previous["artifact"])
                        != (incoming["sha256"].lower(), incoming["artifact"])):
                    target["_issues"].append("conflicting_teacher_provenance")
                else:
                    target["teacher_provenance"] = incoming
            if kind in ("outcome", "program_outcome"):
                initial = initial or d.get("observation")
                target["current_observation"] = d.get("program_observation") or target["current_observation"]
                if kind == "program_outcome":
                    target["current_observation"] = target["current_observation"] or d.get("observation")
                target["outcome_observation"] = d.get("observation")
                if (d.get("basis") == "executor observation" and type(d.get("success")) is bool
                        and isinstance(d.get("evidence"), dict) and d["evidence"]):
                    target["_labels"].append({"success": d["success"],
                                               "scope": "original_goal" if kind == "outcome" else "program_segment",
                                               "basis": "executor observation"})
            elif kind == "cancelled":
                target["_interrupted"] = True
            continue
        # Keep correction/rejection diagnostics without turning them into action labels.
        if active is not None:
            active["host_evidence"].append(evidence_item(receipt))
    if not records:
        record = new_record(receipts[-1], observation=pending or initial)
        record["host_evidence"] = [evidence_item(r) for r in receipts]
    for record in records:
        record["initial_observation"] = record["initial_observation"] or initial
        labels = record.pop("_labels")
        interrupted = record.pop("_interrupted")
        issues = record.pop("_issues")
        if len({label["success"] for label in labels}) > 1:
            issues.append("conflicting_host_labels")
        terminal = next((label for label in reversed(labels) if label["scope"] == "original_goal"), None)
        label = terminal or (labels[-1] if labels else None)
        record["label"] = label
        record["label_history"] = labels
        model = record["teacher_provenance"].get("model")
        record["teacher_kind"] = ("deterministic_fixture" if isinstance(model, str) and model.startswith(("fixture", "deterministic-fixture"))
                                  else "recorded_model" if exact_provenance(record["teacher_provenance"])
                                  else "unknown")
        record["status"] = ("quarantined" if issues else
                            "success" if terminal and terminal["success"] and record["program"] is not None else
                            "negative" if label and not label["success"] else
                            "interrupted" if interrupted else "unverified")
        complete = (isinstance(record["program"], dict)
                    and isinstance(record["program"].get("steps"), list)
                    and bool(record["program"]["steps"])
                    and isinstance(record["current_observation"], dict)
                    and bool(record["current_observation"]))
        if not complete:
            issues.append("missing_program_or_pre_execution_observation")
        if record["attribution"] == "unattributed_terminal":
            issues.append("terminal_without_executable_program")
        if not exact_provenance(record["teacher_provenance"]):
            issues.append("missing_exact_recorded_teacher_provenance")
        eligible = complete and record["status"] in ("success", "negative")
        record["eligibility"] = {
            "selector_label": eligible,
            "positive_sft": eligible and record["status"] == "success" and not issues,
            "negative_example": eligible and record["status"] == "negative",
            "issues": sorted(set(issues)),
        }
    return records


def assign_splits(records):
    """Connected groups retain trials, map fingerprints and repeated relative geometry."""
    parents = {}

    def root(key):
        parents.setdefault(key, key)
        if parents[key] != key:
            parents[key] = root(parents[key])
        return parents[key]

    def union(a, b):
        x, y = root(a), root(b)
        parents[max(x, y)] = min(x, y)

    for record in records:
        trial = "trial:" + record["trial"]
        root(trial)
        context = record.get("context")
        if context:
            # Production context starts with a map hash; ignore the plugin version.
            pieces = context.split(":")
            union(trial, "terrain:" + pieces[0] + ":" + (pieces[-1] if len(pieces) > 2 else ""))
        for context in (record.get("program_context"), record.get("original_context")):
            if isinstance(context, dict) and context.get("map_fingerprint"):
                union(trial, "map:" + str(context["map_fingerprint"]))
        for observation in (record["initial_observation"], record["current_observation"]):
            if isinstance(observation, dict):
                geometry = {key: observation[key] for key in OBSERVATION_KEYS if key in observation}
                if any(key in geometry for key in ("levels", "route_height", "nearby_stands")):
                    union(trial, "geometry:" + digest(geometry))
                if observation.get("terrain_case_id"):
                    union(trial, "case:" + str(observation["terrain_case_id"]))
    declared_splits = collections.defaultdict(set)
    for record in records:
        group = root("trial:" + record["trial"])
        declared_splits[group].update(case["declared_split"] for case in record["cases"] if case["declared_split"])
    for record in records:
        connected = root("trial:" + record["trial"])
        group = digest(connected)
        bucket = int(group[:8], 16) % 100
        record["group"] = group
        declarations = declared_splits[connected]
        record["split"] = ("test" if "test" in declarations else "validation" if "validation" in declarations
                           else "train" if "train" in declarations
                           else "train" if bucket < 70 else "validation" if bucket < 85 else "test")
        record["split_basis"] = ("declared_evaluation_group" if declarations else "group_hash")
        record["split_declarations"] = sorted(declarations)


def write_jsonl(path, rows):
    with path.open("x", encoding="utf-8", newline="\n") as stream:
        for row in rows:
            stream.write(canonical(row) + "\n")


def export(data: Path, destination: Path, additional_data=()):
    data, destination = data.resolve(), destination.resolve()
    roots = [data] + [Path(root).resolve() for root in additional_data]
    for root in roots:
        if not root.is_dir():
            raise ValueError("Data must be an existing journal directory")
        if destination == root or root in destination.parents:
            raise ValueError("Dataset must be outside the source journals directory")
    if destination.exists():
        raise FileExistsError("Use a new dataset directory; existing exports are immutable")
    receipts, inputs, stats, lesson_manifests = load_receipts(roots)
    if not inputs:
        raise ValueError("No experiments*.jsonl journals found")
    trials = collections.defaultdict(list)
    for receipt in receipts:
        trials[receipt["event"]["trial"]].append(receipt)
    records = [record for trial in sorted(trials) for record in assemble(trial, trials[trial])]
    assign_splits(records)
    records.sort(key=lambda record: record["id"])
    destination.mkdir(parents=True)
    for status in ("success", "negative", "interrupted", "unverified", "quarantined"):
        write_jsonl(destination / (status + ".jsonl"), (r for r in records if r["status"] == status))
    write_jsonl(destination / "demonstrations.jsonl", records)
    # The conveniently named SFT file is training-only; held-out demonstrations stay separate.
    write_jsonl(destination / "positive-sft.jsonl", (r for r in records
                                                    if r["eligibility"]["positive_sft"] and r["split"] == "train"))
    for split in ("validation", "test"):
        write_jsonl(destination / ("positive-sft-" + split + ".jsonl"),
                    (r for r in records if r["eligibility"]["positive_sft"] and r["split"] == split))
    write_jsonl(destination / "lesson-manifests.jsonl", lesson_manifests)
    steps = []
    for record in records:
        for item in record["host_evidence"]:
            if item["event"] != "step":
                continue
            evidence = item["data"].get("evidence", {})
            verified = evidence.get("verified") is True or (
                evidence.get("status", "").startswith("classification_verified_by_live_facts")
                and evidence.get("physical_probe", {}).get("observed") is True)
            completion = evidence.get("instruction_completed")
            instruction = evidence.get("instruction", {})
            operation = instruction.get("op") if isinstance(instruction, dict) else None
            # A harvested ingredient is real progress, but does not complete an ACQUIRE
            # request for a tool or a MINE request for its target block. New operations
            # never inherit the historical advance-receipt completion assumption.
            if type(completion) is bool:
                completed = verified and completion
                completion_basis = "explicit_completed" if completion else "explicit_incomplete"
            elif operation in ("MINE", "ACQUIRE"):
                completed = False
                completion_basis = "missing_required_completion_flag"
            else:
                completed = verified
                completion_basis = "legacy_step_receipt"
            attributed = record["status"] != "quarantined" and record["program"] is not None
            steps.append({"schema": SCHEMA, "proposal": record["id"], "trial": record["trial"],
                          "group": record["group"], "split": record["split"], "host_evidence": item,
                          "verified_host_progress": verified,
                          "instruction_completed": completion if type(completion) is bool else None,
                          "completion_basis": completion_basis,
                          "verified_intermediate_step": completed,
                          "completed_instruction_example": completed and attributed,
                          "proposal_attribution_verified": attributed,
                          "whole_program_success": False,
                          "label_basis": "Host progress and instruction completion are separate; neither is a positive whole-plan demonstration"})
    write_jsonl(destination / "steps.jsonl", steps)
    counts = collections.Counter(record["status"] for record in records)
    eligible = collections.Counter(key for r in records for key, value in r["eligibility"].items()
                                   if value is True)
    split_counts = {split: dict(collections.Counter(r["status"] for r in records if r["split"] == split))
                    for split in ("train", "validation", "test")}
    ready = all(any(r["split"] == split and r["status"] == status
                    and r["eligibility"]["selector_label"] for r in records)
                for split in ("train", "validation", "test") for status in ("success", "negative"))
    manifest = {
        "schema": SCHEMA, "kind": "recovery_demonstrations", "inputs": inputs,
        "input_set_sha256": digest({"journals": inputs,
                                    "lesson_manifests": sorted(m["sha256"] for m in lesson_manifests)}),
        "receipt_counts": dict(stats),
        "trial_count": len(trials), "demonstration_count": len(records),
        "status_counts": {s: counts[s] for s in ("success", "negative", "interrupted", "unverified", "quarantined")},
        "eligibility_counts": dict(eligible), "step_receipt_count": len(steps),
        "verified_host_progress_receipts": sum(s["verified_host_progress"] for s in steps),
        "verified_intermediate_step_receipts": sum(s["verified_intermediate_step"] for s in steps),
        "split_counts": split_counts, "group_count": len({r["group"] for r in records}),
        "split_policy": "Connected trial/terrain/task geometry groups; declared holdout/test stays test for the entire group, validation outranks train; otherwise 70/15/15 SHA256 buckets, not stratified",
        "lesson_manifest_count": len(lesson_manifests),
        "evidence_policy": "Host outcome or explicit failed-execution receipt required. Model claims, rejected proposals and interruptions are not successes. Recorded artifact hashes are provenance, not a new artifact verification.",
        "readiness": {"has_labeled_classes_in_all_splits": ready,
                      "training_performed": False, "physical_execution_performed": False,
                      "note": "Class coverage is a minimum check, not proof of sufficient data or generalization."},
    }
    manifest["outputs"] = [{"file": path.name, "bytes": path.stat().st_size,
                             "sha256": hashlib.sha256(path.read_bytes()).hexdigest()}
                            for path in sorted(destination.glob("*.jsonl"))]
    (destination / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    return manifest


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("data", type=Path, help="CoreAI/data or lesson root; journals are discovered recursively")
    parser.add_argument("output", type=Path, help="NEW dataset directory, outside the source journals")
    parser.add_argument("--additional-data", type=Path, action="append", default=[],
                        help="Additional journal/lesson root; repeat for multiple sources")
    args = parser.parse_args()
    result = export(args.data, args.output, args.additional_data)
    print(json.dumps({"status_counts": result["status_counts"], "eligibility_counts": result["eligibility_counts"],
                      "readiness": result["readiness"]}, indent=2))
