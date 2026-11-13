import json
from pathlib import Path

D = Path(r"C:\Users\bark\Doubao\chats\2026-11-13\new-chat\champs-damage-calc\data\raw\community")
def load(n): return json.loads((D / n).read_text(encoding="utf-8"))

for name in ["natures__natures.json", "items__items.json", "abilities__abilities.json", "type-chart__effectiveness.json"]:
    d = load(name)
    print("==", name, "type:", type(d).__name__, "len:", len(d))
    if isinstance(d, list):
        print("  sample:", json.dumps(d[0], ensure_ascii=False)[:300])
    elif isinstance(d, dict):
        k = next(iter(d))
        print("  key:", k, "sample:", json.dumps(d[k], ensure_ascii=False)[:300])
    print()
