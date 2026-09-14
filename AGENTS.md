# Testing policy for Civilizations and CoreAI

Follow the user's preference for focused, relevant verification. Read `TESTING.md` when selecting checks.

- Use `python tools/test_sections.py <section...>` from this directory, or preview explicit changed files with `--changed <paths...> --dry-run`. Keep `tools/test_sections.json` mappings and JUnit section/interaction tags current.
- Run the relevant section and its included interaction contracts once. Broaden only for shared-code impact, uncovered dependencies, new failures, or an explicit release/broad-verification request.
- Use the Maven `test` phase for checks. Package with `-DskipTests` afterward only when a JAR is needed. Never deploy a new live-server JAR merely for test-suite or documentation edits.
- Physical Folia checks are opt-in, on disposable worlds, and limited to the changed executor behavior. Prefer one combined scenario over repeated baseline/extended runs. Navigation and station recovery are separate scenarios.
- No Folia startups, model probes, repeated smoke checks or full-village runs for test-selection/documentation-only changes. A mock/planner result is not proof of executed Minecraft actions. Report the evidence level honestly.
- Remove obsolete test expectations only after checking that the production path was replaced; preserve independent failure/protection/material-conservation regressions. Retarget old tests to the current implementation where their contract still matters.
