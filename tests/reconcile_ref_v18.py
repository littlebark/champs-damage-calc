"""对账：参考仓库 v18 客户端挖掘数据 vs championsbattledata index。"""
import json
import sys
from pathlib import Path

ROOT = Path(r"C:\Users\bark\Doubao\chats\2026-11-13\new-chat\champs-damage-calc")
sys.path.insert(0, str(ROOT))
from src.champions_data import ChampionsIndex  # noqa: E402

REF = ROOT / "data/ref/v18"
def load(n): return json.loads((REF / n).read_text(encoding="utf-8"))

roster = load("champions-roster.v18.json")
moves = load("champions-moves.v18.json")
items = load("champions-items.v18.json")
presets = load("champions-presets.json")
zh = load("zh-Hans.json")

print("roster forms:", len(roster["forms"]), " abilities:", len(roster["abilities"]))
print("moves entries:", len(moves["entries"]), " forms:", len(moves["forms"]))
print("items entries:", len(items["entries"]))
print("presets species:", presets["speciesCount"], " profiles:", presets["profileCount"])
print("zh entries:", len(zh), " 类型:", {t: sum(1 for e in zh if e["entityType"] == t) for t in {e["entityType"] for e in zh}})

def norm(s): return (s or "").lower().replace(" ", "-")

# 编号 -> showdownId
move_by_num = {m["number"]: m["showdownId"] for m in moves["entries"]}
ability_by_num = {a["number"]: a["showdownId"] for a in roster["abilities"]}
item_by_num = {i["number"]: i["showdownId"] for i in items["entries"]}

# 形态索引：showdownId 归一化 -> form
ref_forms = {norm(f["showdownId"]): f for f in roster["forms"]}

idx = ChampionsIndex(ROOT / "data/raw/champions-pokemon-index.json")

# 种族值对照（Base，按 showdownId）
stat_keys = ["hp", "atk", "def", "spa", "spd", "spe"]
match = mismatch = 0
mis_examples = []
for cp in idx._by_name.values():
    base = next((f for f in cp.forms if f.kind == "Base"), None)
    if not base or not cp.showdown_id:
        continue
    rf = ref_forms.get(norm(cp.showdown_id))
    if not rf:
        continue
    match += 1
    for k in stat_keys:
        if rf["baseStats"][k] != base.base_stats[k]:
            mismatch += 1
            if len(mis_examples) < 12:
                mis_examples.append((cp.name, k, rf["baseStats"][k], base.base_stats[k]))

print(f"\n种族值可配对 Base: {match}  不一致项: {mismatch}")
for e in mis_examples:
    print("  ", e)

# 可学池对照：v18 moves form.moveNumbers -> showdownId 集合 vs index learnset
moves_forms = {norm(f["showdownId"]): f for f in moves["forms"]}
pool_mismatch = []
for cp in idx._by_name.values():
    rf = moves_forms.get(norm(cp.showdown_id)) if cp.showdown_id else None
    if not rf:
        continue
    rf_moves = {norm(move_by_num[n]) for n in rf["moveNumbers"] if n in move_by_num}
    idx_moves = {norm(m) for m in cp.learnset}
    if rf_moves != idx_moves:
        pool_mismatch.append((cp.name, len(idx_moves), len(rf_moves),
                              sorted(idx_moves - rf_moves)[:4], sorted(rf_moves - idx_moves)[:4]))
print(f"\n可学池不一致: {len(pool_mismatch)} / {len(ref_forms)}")
for e in pool_mismatch[:10]:
    print("  ", e)
