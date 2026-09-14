"""Curated local JVM checks. Never starts Folia, downloads a model, or deploys a plugin."""
import argparse
import fnmatch
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
CONFIG = json.loads(Path(__file__).with_suffix(".json").read_text())
SECTIONS = set(CONFIG["sections"])


def catalog():
    cases = []
    for module in ("CoreAI", "Civilizations"):
        for path in (ROOT / module / "src/test/java").rglob("*.java"):
            text = path.read_text(encoding="utf-8")
            found = 0
            for match in re.finditer(r"((?:^[ \t]*@[^;\n]*\n)+)[ \t]*(?:public )?void (\w+)\(", text, re.M):
                annotations, method = match.groups()
                if not re.search(r"@(?:[\w.]+\.)?(?:Test|ParameterizedTest)\b", annotations):
                    continue
                found += 1
                tags = set(re.findall(r'@(?:[\w.]+\.)?Tag\("([\w-]+)"\)', annotations))
                if not tags & SECTIONS or tags - SECTIONS - {"interaction"}:
                    raise ValueError(f"Missing/unknown section tag: {path.name}#{method}")
                cases.append({"file": str(path.relative_to(ROOT)).replace("\\", "/"), "class": path.stem, "method": method, "tags": sorted(tags)})
            declared = len(re.findall(r"@(?:[\w.]+\.)?(?:Test|ParameterizedTest|RepeatedTest|TestFactory|TestTemplate)\b", text))
            if found != declared:
                raise ValueError(f"Unsupported test declaration in {path}; update catalog parser before proceeding")
    return cases


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("sections", nargs="*", help="One or more section names, or all")
    parser.add_argument("--changed", nargs="+", default=[], help="Changed file paths; unmapped source selects all and explains why")
    parser.add_argument("--list", action="store_true")
    parser.add_argument("--dry-run", action="store_true")
    parser.add_argument("--interactions", action="store_true", help="Only cross-section contracts among selected sections")
    parser.add_argument("--maven", default=os.environ.get("MAVEN_CMD", "mvn"))
    parser.add_argument("--repo", default=os.environ.get("MAVEN_REPO"))
    args = parser.parse_args()
    cases = catalog()
    if args.list:
        for section, description in CONFIG["sections"].items():
            print(f"{section:13} {sum(section in c['tags'] for c in cases):3} methods  {description}")
        print("\nPhysical scenarios (manual, separate from this command):")
        for name, description in CONFIG["physical_scenarios"].items():
            print(f"  {name}: {description}")
        return 0
    selected = set(args.sections)
    if selected - SECTIONS - {"all"}:
        parser.error("Unknown section: " + ", ".join(sorted(selected - SECTIONS - {"all"})))
    for raw in args.changed:
        name = raw.replace("\\", "/")
        if "/outputs/" in name:
            name = name.split("/outputs/", 1)[1]
        name = name.removeprefix("outputs/").removeprefix("./")
        if Path(name).suffix.lower() in {".md", ".txt", ".png", ".jpg", ".svg"}:
            print(f"Documentation/artifact-only change: {raw}; no automated checks selected for it.")
            continue
        related = [c for c in cases if c["file"] == name or c["file"].endswith("/" + name)]
        if related:
            selected.update(t for c in related for t in c["tags"] if t in SECTIONS)
            continue
        matched = False
        for rule in CONFIG["source_rules"]:
            if any(fnmatch.fnmatchcase(name, pattern) for pattern in rule["patterns"]):
                selected.update(rule["sections"])
                matched = True
                break
        if not matched:
            print(f"Unmapped/shared change: {raw}; selecting all fast checks (no Folia run).")
            selected.add("all")
    if not selected:
        if args.changed:
            print("No executable changes selected; no tests run.")
            return 0
        parser.error("Choose a section, --changed paths, --list, or explicitly all")
    if "all" in selected:
        selected = set(SECTIONS)
    chosen = [c for c in cases if selected.intersection(c["tags"]) and (not args.interactions or "interaction" in c["tags"])]
    if not chosen:
        parser.error("Selection contains no tests")
    # Method selectors also exclude stale compiled test classes after source removal/renaming.
    classes = {}
    for case in chosen:
        classes.setdefault(case["class"], []).append(case["method"])
    selector = ",".join(name if len(methods) == sum(c["class"] == name for c in cases) else name + "#" + "+".join(methods) for name, methods in sorted(classes.items()))
    print(f"Sections: {', '.join(sorted(selected))}; {len(chosen)} methods (parameterized methods may have multiple cases).", flush=True)
    print("Includes cross-section tests tagged with the selected areas; no Folia/model runs.", flush=True)
    for name, methods in sorted(classes.items()):
        print(f"  {name}: {len(methods)} methods")
    if args.dry_run:
        return 0
    maven = shutil.which(args.maven)
    if not maven:
        parser.error("Maven not found; set MAVEN_CMD or pass --maven C:/path/to/mvn.cmd")
    argv = [maven, "-B", "-ntp", "-f", str(ROOT / "pom.xml"), "test", f"-Dtest={selector}", "-Dsurefire.failIfNoSpecifiedTests=false"]
    if args.repo:
        argv.append(f"-Dmaven.repo.local={args.repo}")
    reports = {p: (p.stat().st_mtime_ns, p.stat().st_size) for module in ("CoreAI", "Civilizations") for p in (ROOT / module / "target/surefire-reports").glob("TEST-*.xml")}
    started = time.monotonic()
    result = subprocess.run(argv, cwd=ROOT)
    totals = {"tests": 0, "failures": 0, "errors": 0, "skipped": 0}
    for module in ("CoreAI", "Civilizations"):
        for path in (ROOT / module / "target/surefire-reports").glob("TEST-*.xml"):
            if reports.get(path) == (path.stat().st_mtime_ns, path.stat().st_size):
                continue
            suite = ET.parse(path).getroot()
            for key in totals:
                totals[key] += int(suite.attrib.get(key, 0))
    summary = {"sections": sorted(selected), "selected_methods": len(chosen), "results": totals, "elapsed_seconds": round(time.monotonic() - started, 2), "exit_code": result.returncode, "evidence": "JVM contracts; not proof of villager movement or world changes"}
    output = ROOT / "test-results/last-run.json"
    output.parent.mkdir(exist_ok=True)
    output.write_text(json.dumps(summary, indent=2) + "\n")
    print(json.dumps(summary, indent=2))
    if result.returncode == 0 and totals["tests"] == 0:
        print("ERROR: Maven reported success without fresh test results", file=sys.stderr)
        return 1
    return result.returncode


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except ValueError as error:
        print(str(error), file=sys.stderr)
        raise SystemExit(2)
