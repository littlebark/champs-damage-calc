import json
from pathlib import Path

ROOT = Path(r"C:\Users\bark\Doubao\chats\2026-11-13\new-chat\champs-damage-calc")
idx = json.loads((ROOT / "data/raw/champions-pokemon-index.json").read_text(encoding="utf-8"))
dex = json.loads((ROOT / "data/raw/pokedex.json").read_text(encoding="utf-8"))
dex_names = {v["name"].lower(): k for k, v in dex.items()}

missing, forms = [], {}
for p in idx["pokemon"]:
    sid = (p.get("showdownId") or "").lower()
    sname = (p.get("showdownName") or "").lower()
    hit = sid in dex or sname in dex_names
    if not hit:
        missing.append((p["name"], sid))
    for f in p["summary"].get("forms", []):
        forms[f.get("form_kind", "?")] = forms.get(f.get("form_kind", "?"), 0) + 1

print("冠军宝可梦数:", len(idx["pokemon"]))
print("Showdown 底座未命中:", len(missing))
for m in missing:
    print("  MISS", m)
print("形态分布:", forms)

# 检查形态里非 Base 的条目
for p in idx["pokemon"]:
    for f in p["summary"].get("forms", []):
        if f.get("form_kind") != "Base":
            print("  形态:", p["name"], "->", f["form_name"], f["types"], f["abilities"], f["hp"], f["attack"], f["defense"], f["sp_attack"], f["sp_defense"], f["speed"])
