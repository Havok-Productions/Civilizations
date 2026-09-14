"""Export factual action/outcome records for later curation; never treat proposals as facts."""
import argparse
import json
from pathlib import Path


def export(data: Path, destination: Path) -> int:
    data = data.resolve()
    destination = destination.resolve()
    sources = sorted(data.glob("outcomes*.jsonl")) + sorted(data.glob("experiments*.jsonl"))
    if destination in sources:
        raise ValueError("Output must not overwrite an input journal")
    seen = set()
    count = 0
    destination.parent.mkdir(parents=True, exist_ok=True)
    with destination.open("x", encoding="utf-8") as output:
        for source in sources:
            with source.open(encoding="utf-8") as stream:
                for line in stream:
                    try:
                        event = json.loads(line)
                        record = event["data"]
                        if event.get("schema") != 1 or record.get("basis") != "executor observation":
                            continue
                        if "trial" in event:
                            identifier = "skill:" + event["trial"]
                            if event.get("event") != "outcome" or type(record.get("success")) is not bool or identifier in seen:
                                continue
                            # A proposal alone, cancellation or rejected response is not an outcome.
                            if "program" not in record or "observation" not in record:
                                continue
                            example = {
                                "schema": 1, "id": identifier, "time": event["time"],
                                "village": event["village"], "worker": event["worker"],
                                "scope": "NAVIGATION_SKILL", "context": event["context"],
                                "observation": record["observation"], "program": record["program"],
                                "teacher": record.get("teacher", "unknown"),
                                "success": record["success"], "evidence": record["evidence"],
                                "label_basis": "executor observation; local recovery only; requires curation",
                            }
                            seen.add(identifier)
                            output.write(json.dumps(example, ensure_ascii=False) + "\n")
                            count += 1
                            continue
                        ticket = record["ticket"]
                        if type(record.get("success")) is not bool or ticket["id"] in seen:
                            continue
                        seen.add(ticket["id"])
                        example = {
                            "schema": 1, "id": ticket["id"], "time": event["time"],
                            "village": ticket["village"], "worker": ticket["worker"],
                            "scope": ticket["choice"]["scope"], "policy_version": ticket["choice"]["version"],
                            "observation": ticket["choice"]["options"], "selected": ticket["selected"],
                            "success": record["success"], "evidence": record["evidence"],
                            "label_basis": "executor observation; alternatives untested; requires curation",
                        }
                        output.write(json.dumps(example, ensure_ascii=False) + "\n")
                        count += 1
                    except (ValueError, KeyError, TypeError):
                        # A crash-truncated final line is not a training example.
                        continue
    return count


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("data", type=Path, help="CoreAI/data folder")
    parser.add_argument("output", type=Path, help="New JSONL file; existing files are never overwritten")
    args = parser.parse_args()
    print(f"Exported {export(args.data, args.output)} executor examples; teacher proposals excluded.")
