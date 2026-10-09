"""Contracts for evidence attribution, leakage protection and honest distillation inputs."""
import copy
import json
import tempfile
import unittest
from pathlib import Path

from export_recovery import export


TEACHER = {"model": "local-teacher", "artifact": "local:model.gguf", "sha256": "a" * 64,
           "license": "test-license", "knowledgeBasis": "recorded generated program"}
OBS = {"goal_relative": {"x": 2, "y": 0, "z": 0}, "inventory": {"DIRT": 1},
       "levels": [{"relative_y": 0, "rows": ["..s."]}]}
WALK = {"explanation": "This will definitely work", "steps": [{"op": "WALK", "x": 2}]}
CLEAR = {"steps": [{"op": "CLEAR", "x": 1}, {"op": "WALK", "x": 2}]}


def event(kind, time, data, trial="trial-one", context="terrain:alpha:world"):
    return {"schema": 1, "trial": trial, "event": kind, "time": time, "worker": "worker",
            "village": "village", "context": context, "data": data}


def outcome(success, program=WALK, **fields):
    return {"success": success, "basis": "executor observation", "program": program,
            "observation": OBS, "program_observation": OBS, "teacher": json.dumps(TEACHER),
            "evidence": {"position": {"x": 2}, "reason": "arrived" if success else "blocked"},
            **fields}


class RecoveryExportTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        self.data = self.root / "data"
        self.data.mkdir()

    def tearDown(self):
        self.temporary.cleanup()

    def journal(self, rows, name="experiments.jsonl"):
        (self.data / name).write_text("\n".join(json.dumps(row) for row in rows) + "\n", encoding="utf-8")

    def run_export(self, name="dataset"):
        destination = self.root / name
        manifest = export(self.data, destination)
        records = [json.loads(line) for line in (destination / "demonstrations.jsonl").read_text().splitlines()]
        return manifest, records

    def test_revision_failure_is_negative_and_final_success_uses_fresh_observation(self):
        fresh = dict(OBS, inventory={"DIRT": 2})
        self.journal([
            event("requested", 1, {"observation": OBS}),
            event("proposed", 2, {"program": WALK, "teacher": json.dumps(TEACHER)}),
            event("step", 3, {"evidence": {"verified": True, "instruction": {"op": "CLASSIFY"}}}),
            event("revision_requested", 4, dict(fresh, previous_program=WALK,
                                                 failed_execution={"reason": "tree blocked"})),
            event("revision_proposed", 5, {"program": CLEAR, "observation": fresh, "teacher": TEACHER}),
            event("outcome", 6, outcome(True, CLEAR, program_observation=fresh)),
        ])
        manifest, records = self.run_export()
        negative = next(r for r in records if r["status"] == "negative")
        success = next(r for r in records if r["status"] == "success")
        self.assertEqual("program_segment", negative["label"]["scope"])
        self.assertFalse(negative["eligibility"]["positive_sft"])
        self.assertEqual({"DIRT": 2}, success["current_observation"]["inventory"])
        self.assertEqual({"DIRT": 1}, success["initial_observation"]["inventory"])
        self.assertEqual(success["group"], negative["group"])
        self.assertEqual(1, manifest["verified_intermediate_step_receipts"])
        self.assertEqual(1, manifest["eligibility_counts"]["positive_sft"])
        self.assertEqual(TEACHER["sha256"], success["teacher_provenance"]["sha256"])

    def test_proposals_claims_rejections_and_interruptions_never_become_successes(self):
        self.journal([
            event("requested", 1, {"observation": OBS}),
            event("proposed", 2, {"program": WALK, "teacher": TEACHER, "success": True}),
            event("outcome", 3, outcome(True, basis="teacher claim")),
            event("cancelled", 4, {"reason": "night_sleep"}),
            event("rejected", 5, {"reason": "invalid_program"}, trial="rejected"),
            event("outcome", 6, outcome("true"), trial="string_bool"),
        ])
        manifest, records = self.run_export()
        self.assertEqual(0, manifest["status_counts"]["success"])
        self.assertEqual(1, manifest["status_counts"]["interrupted"])
        self.assertEqual(2, manifest["status_counts"]["unverified"])
        self.assertFalse(any(r["eligibility"]["positive_sft"] for r in records))

    def test_observation_request_does_not_label_replaced_program_a_failure(self):
        self.journal([
            event("requested", 1, {"observation": OBS}),
            event("proposed", 2, {"program": WALK, "teacher": TEACHER}),
            event("observation_requested", 3, dict(OBS, previous_program=WALK,
                                                    observation_request={"reason": "read complete"})),
            event("outcome", 4, {"success": False, "basis": "executor observation",
                                  "observation": OBS, "evidence": {"reason": "revision unavailable"}}),
        ])
        _, records = self.run_export()
        previous = next(r for r in records if r["program"] == WALK)
        self.assertEqual("unverified", previous["status"])
        self.assertIsNone(previous["label"])

    def test_explicit_segment_failure_is_not_duplicated_by_revision_request(self):
        context = {"map_fingerprint": "recordedmap", "origin": {"x": 1}, "goal": {"x": 3}}
        self.journal([
            event("requested", 1, {"observation": OBS}),
            event("proposed", 2, {"program": WALK, "proposal_id": "trial-one:1",
                                    "teacher_provenance": TEACHER, "program_context": context}),
            event("program_outcome", 3, outcome(False, proposal_id="trial-one:1")),
            event("revision_requested", 4, dict(OBS, previous_program=WALK,
                                                 previous_proposal_id="trial-one:1",
                                                 failed_execution={"reason": "blocked"})),
        ])
        _, records = self.run_export()
        self.assertEqual(1, len(records))
        self.assertEqual(1, len(records[0]["label_history"]))
        self.assertEqual("recorded", records[0]["proposal_id_basis"])
        self.assertEqual(context, records[0]["program_context"])

    def test_duplicate_rotated_receipts_and_conflicting_labels_are_handled_deterministically(self):
        successful = event("outcome", 3, outcome(True, proposal_id="one"))
        failure = event("outcome", 4, outcome(False, proposal_id="one"))
        self.journal([successful, failure])
        self.journal([failure, successful], "experiments.1.jsonl")
        with (self.data / "experiments.jsonl").open("a") as stream:
            stream.write("{truncated\n")
        manifest, records = self.run_export()
        self.assertEqual(2, manifest["receipt_counts"]["duplicate_receipts"])
        self.assertEqual(1, manifest["receipt_counts"]["malformed_lines"])
        self.assertEqual(1, len(records))
        self.assertEqual("quarantined", records[0]["status"])
        self.assertIn("conflicting_host_labels", records[0]["eligibility"]["issues"])
        self.assertFalse(records[0]["eligibility"]["selector_label"])
        second_manifest, second = self.run_export("second")
        self.assertEqual(records, second)
        self.assertEqual(manifest, second_manifest)

    def test_geometry_and_trial_groups_prevent_cross_split_leakage(self):
        different_geometry = copy.deepcopy(OBS)
        different_geometry["levels"][0]["rows"] = ["ssss"]
        self.journal([
            event("outcome", 1, outcome(False), trial="first", context="terrainA:alpha:world"),
            event("outcome", 2, outcome(True), trial="second", context="terrainB:alpha:world"),
            event("outcome", 3, outcome(False, CLEAR, program_observation=different_geometry,
                                        observation=different_geometry),
                  trial="second", context="terrainC:alpha:world"),
            event("outcome", 4, outcome(False, program_observation=different_geometry,
                                        observation=different_geometry),
                  trial="third", context="terrainD:alpha:world"),
        ])
        manifest, records = self.run_export()
        self.assertEqual(1, len({r["group"] for r in records}))
        self.assertEqual(1, len({r["split"] for r in records}))
        self.assertFalse(manifest["readiness"]["has_labeled_classes_in_all_splits"])

    def test_unknown_historical_teacher_has_no_invented_hash_or_sft_eligibility(self):
        self.journal([event("outcome", 1, outcome(True, teacher="old teacher"))])
        _, records = self.run_export()
        self.assertIsNone(records[0]["teacher_provenance"]["sha256"])
        self.assertEqual("unknown", records[0]["teacher_kind"])
        self.assertTrue(records[0]["eligibility"]["selector_label"])
        self.assertFalse(records[0]["eligibility"]["positive_sft"])

    def test_fixture_teacher_is_explicit_and_program_id_conflicts_are_quarantined(self):
        fixture = dict(TEACHER, model="fixture:clear-and-walk")
        self.journal([
            event("proposed", 1, {"program": WALK, "proposal_id": "same", "observation": OBS,
                                    "teacher_provenance": fixture}),
            event("outcome", 2, outcome(True, CLEAR, proposal_id="same", teacher=fixture)),
        ])
        _, records = self.run_export()
        self.assertEqual("deterministic_fixture", records[0]["teacher_kind"])
        self.assertEqual("quarantined", records[0]["status"])
        self.assertIn("conflicting_program_for_proposal_id", records[0]["eligibility"]["issues"])

    def test_existing_outputs_and_source_subdirectories_are_refused(self):
        self.journal([event("outcome", 1, outcome(False))])
        self.run_export()
        with self.assertRaises(FileExistsError):
            self.run_export()
        with self.assertRaises(ValueError):
            export(self.data, self.data / "dataset")
        self.assertEqual(1, len(list(self.data.iterdir())))

    def test_declared_holdout_is_never_sft_training_even_when_geometry_matches_train(self):
        first = self.root / "lessons" / "first"
        second = self.root / "lessons" / "second"
        for folder, split in ((first, "train"), (second, "holdout")):
            (folder / "data").mkdir(parents=True)
            (folder / "lesson.json").write_text(json.dumps({
                "schema": 1, "lesson": "foliage", "layout": {"name": folder.name, "split": split},
                "verified": False}), encoding="utf-8")
            (folder / "data" / "experiments.jsonl").write_text(json.dumps(
                event("outcome", 1, outcome(True), trial=folder.name,
                      context=folder.name + ":alpha:world")) + "\n", encoding="utf-8")
        manifest = export(first, self.root / "combined", [second])
        records = [json.loads(line) for line in
                   (self.root / "combined" / "demonstrations.jsonl").read_text().splitlines()]
        self.assertTrue(all(r["split"] == "test" for r in records))
        self.assertEqual(["test", "train"], records[0]["split_declarations"])
        self.assertEqual("", (self.root / "combined" / "positive-sft.jsonl").read_text())
        self.assertEqual(2, len((self.root / "combined" / "positive-sft-test.jsonl").read_text().splitlines()))
        self.assertEqual(2, manifest["lesson_manifest_count"])
        # The manifest's claimed result never supplies a label: only executor receipts do.
        self.assertEqual(2, manifest["status_counts"]["success"])

    def test_terminal_without_program_never_labels_stale_explicit_proposal(self):
        for success in (False, True):
            with self.subTest(success=success):
                terminal = outcome(success, proposal_id="old-id")
                terminal.pop("program")
                self.journal([
                    event("requested", 1, {"observation": OBS}),
                    event("proposed", 2, {"program": WALK, "proposal_id": "old-id", "teacher": TEACHER}),
                    event("observation_requested", 3, dict(OBS, previous_program=WALK,
                                                            previous_proposal_id="old-id")),
                    event("outcome", 4, terminal),
                ])
                manifest, records = self.run_export("terminal-" + str(success))
                previous = next(r for r in records if r["program"] == WALK)
                terminal_record = next(r for r in records if r["program"] is None)
                self.assertEqual("unverified", previous["status"])
                self.assertIsNone(previous["label"])
                self.assertEqual("old-id", terminal_record["reported_proposal_id"])
                self.assertEqual("unverified" if success else "negative", terminal_record["status"])
                self.assertFalse(terminal_record["eligibility"]["selector_label"])
                self.assertEqual(0, manifest["status_counts"]["success"])
                self.assertFalse(any(r["eligibility"]["positive_sft"] for r in records))

    def test_legacy_equal_timestamp_attribution_is_quarantined(self):
        self.journal([
            event("requested", 1, {"observation": OBS}),
            event("proposed", 2, {"program": WALK, "teacher": TEACHER}),
            event("outcome", 2, outcome(True)),
        ])
        _, records = self.run_export()
        terminal_record = next(r for r in records if r["label"])
        self.assertEqual("quarantined", terminal_record["status"])
        self.assertIn("ambiguous_legacy_same_timestamp", terminal_record["eligibility"]["issues"])
        self.assertFalse(terminal_record["eligibility"]["positive_sft"])

    def test_conflicting_teacher_hashes_do_not_silently_replace_provenance(self):
        self.journal([
            event("proposed", 1, {"program": WALK, "proposal_id": "same", "observation": OBS,
                                    "teacher_provenance": TEACHER}),
            event("outcome", 2, outcome(True, proposal_id="same", teacher=dict(TEACHER, sha256="b" * 64))),
        ])
        _, records = self.run_export()
        self.assertEqual("quarantined", records[0]["status"])
        self.assertEqual(TEACHER["sha256"], records[0]["teacher_provenance"]["sha256"])
        self.assertIn("conflicting_teacher_provenance", records[0]["eligibility"]["issues"])

    def test_prerequisite_progress_does_not_complete_mine_or_acquire_instruction(self):
        program = {"steps": [{"op": "ACQUIRE", "material": "WOODEN_PICKAXE"},
                             {"op": "MINE", "material": "STONE"}]}
        rows = [event("proposed", 1, {"program": program, "proposal_id": "one", "observation": OBS,
                                      "teacher_provenance": TEACHER})]
        time = 2
        for operation in ("ACQUIRE", "MINE"):
            for flag in (False, True, None):
                evidence = {"instruction": {"op": operation}, "verified": True,
                            "inventory": {"OAK_LOG": 1}}
                if flag is not None:
                    evidence["instruction_completed"] = flag
                rows.append(event("step", time, {"proposal_id": "one", "evidence": evidence}))
                time += 1
        self.journal(rows)
        manifest, records = self.run_export()
        steps = [json.loads(line) for line in (self.root / "dataset" / "steps.jsonl").read_text().splitlines()]
        self.assertEqual(6, manifest["verified_host_progress_receipts"])
        self.assertEqual(2, manifest["verified_intermediate_step_receipts"])
        for step in steps:
            self.assertTrue(step["verified_host_progress"])
            expected = step["instruction_completed"] is True
            self.assertEqual(expected, step["verified_intermediate_step"])
            self.assertEqual(expected, step["completed_instruction_example"])
            self.assertFalse(step["whole_program_success"])
        self.assertFalse(records[0]["eligibility"]["positive_sft"])

    def test_portable_reuse_keeps_translated_context_and_original_fixture_identity(self):
        context = {"origin": {"x": 128, "y": 4, "z": 0}, "goal": {"x": 132, "y": 4, "z": 0},
                   "map_fingerprint": "translated-map"}
        observation = dict(OBS, goal_relative={"x": 4, "y": 0, "z": 0})
        fixture = "deterministic-fixture:acquire-mine-portable-control"
        self.journal([
            event("reuse", 1, {"proposal_id": "replay", "program": WALK, "teacher": fixture,
                               "portable_skill_id": "original-skill", "program_context": context,
                               "basis": "verified translated terrain and prerequisites"}),
            event("outcome", 2, outcome(True, proposal_id="replay", teacher=fixture,
                                        observation=observation, program_observation=observation)),
        ])
        _, records = self.run_export()
        replay = records[0]
        self.assertEqual("success", replay["status"])
        self.assertEqual("deterministic_fixture", replay["teacher_kind"])
        self.assertEqual(fixture, replay["teacher_provenance"]["model"])
        self.assertEqual(context, replay["program_context"])
        self.assertEqual(observation, replay["current_observation"])
        self.assertEqual("original-skill", replay["host_evidence"][0]["data"]["portable_skill_id"])
        self.assertFalse(replay["eligibility"]["positive_sft"])


if __name__ == "__main__":
    unittest.main()
