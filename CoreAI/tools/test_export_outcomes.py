"""Focused dataset contract: only executed outcomes, including failures, are exported."""
import json
import tempfile
import unittest
from pathlib import Path
from export_outcomes import export


class ExportTest(unittest.TestCase):
    def test_skill_proposals_and_cancellations_are_not_training_successes(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            receipt = {"schema": 1, "time": 1, "trial": "one", "worker": "w",
                       "village": "v", "context": "map", "event": "outcome",
                       "data": {"basis": "executor observation", "success": False,
                                "observation": {"inventory": {}}, "program_observation": {"inventory": {"DIRT": 2}, "failure": "fresh failed walk"}, "program": {"steps": []},
                                "evidence": {"reason": "material missing"}, "teacher": "fixture"}}
            rows = [receipt, receipt, dict(receipt, event="proposed", trial="two"),
                    dict(receipt, event="cancelled", trial="three")]
            (root / "experiments.jsonl").write_text("\n".join(json.dumps(r) for r in rows) + "\n{truncated", encoding="utf-8")
            destination = root / "dataset.jsonl"
            self.assertEqual(1, export(root, destination))
            saved = json.loads(destination.read_text(encoding="utf-8"))
            self.assertFalse(saved["success"])
            self.assertEqual("NAVIGATION_SKILL", saved["scope"])
            self.assertEqual({"DIRT": 2}, saved["observation"]["inventory"])
            self.assertEqual({}, saved["original_goal_observation"]["inventory"])
            with self.assertRaises(FileExistsError):
                export(root, destination)


if __name__ == "__main__":
    unittest.main()
