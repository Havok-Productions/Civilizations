"""Read archived terrain maps without touching the running server. Python 3.10+."""
import argparse
import gzip
import json
from pathlib import Path

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("data", type=Path, help="plugins/Civilizations directory")
parser.add_argument("--map", help="Map filename, e.g. map-3.json.gz; default is newest")
parser.add_argument("--y", type=int, help="Block Y slice; default is villager's mapped feet height")
args = parser.parse_args()
directory = args.data / "debug/navigation/maps"
maps = list(directory.glob("map-*.json.gz"))
if not maps:
    parser.error("No archived maps yet. Check debug/failures/events.jsonl for capture errors.")
if args.map and Path(args.map).name != args.map:
    parser.error("--map must be a filename within the map archive")
path = directory / args.map if args.map else max(maps, key=lambda p: p.stat().st_mtime_ns)
with gzip.open(path, "rt", encoding="utf-8") as stream:
    saved = json.load(stream)
volume = saved["map"]
center = volume["center"]
radius, vertical = volume["radius"], volume["vertical"]
y = center["y"] if args.y is None else args.y
low_y, high_y = center["y"] - vertical - 1, center["y"] + vertical + 2
if not low_y <= y <= high_y:
    parser.error(f"Y must be within this map's captured range {low_y}..{high_y}")
flat = []
runs = volume["runs"]
for index in range(0, len(runs), 2):
    flat.extend([volume["palette"][runs[index]]] * runs[index + 1])
width, height = radius * 2 + 1, high_y - low_y + 1
route = {(s["feet"]["x"], s["feet"]["z"]) for s in saved["search"]["steps"] if s["feet"]["y"] == y}
symbols = {"AIR": ".", "SOLID": "#", "SOFT": "s", "OPENABLE": "D", "HAZARD": "!", "UNKNOWN": "?", "UNCLASSIFIED": "u", "FLUID": "~", "OBSTACLE": "X", "CLEARABLE": "c"}
print(f"{path.name}: id={saved['id']} worker={saved['worker']}")
print(f"Center={center}, Y={y}, radius={radius}; x increases right, z increases down")
print(f"Search={saved['search']['reason']}; expanded={saved['search']['expanded']}")
print("Legend: @ villager, * planned route, # solid/protected, s natural salvage candidate, D door/gate, ! damaging/explosive, ~ fluid, X obstacle, c clearable, u needs classification, ? unobserved, . air")
for z in range(center["z"] - radius, center["z"] + radius + 1):
    row = []
    for x in range(center["x"] - radius, center["x"] + radius + 1):
        index = ((x - center["x"] + radius) * width + z - center["z"] + radius) * height + y - low_y
        kind = flat[index].rsplit(":", 1)[-1]
        row.append("@" if x == center["x"] and z == center["z"] else "*" if (x, z) in route else symbols[kind])
    print(f"{z:6} " + "".join(row))
print("Rejections:", json.dumps(saved["search"]["rejected"], sort_keys=True))
print("This is a historical snapshot and proposed route, not proof of completed movement. Match its ID to the event log; archive slots rotate.")
