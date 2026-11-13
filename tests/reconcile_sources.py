"""三方对账：community 数据集 vs champions index vs Showdown 底座。"""
import json
import sys
from pathlib import Path

ROOT = Path(r"C:\Users\bark\Doubao\chats\2026-11-13\new-chat\champs-damage-calc")
sys.path.insert(0, str(ROOT))
from src.champions_data import ChampionsIndex  # noqa: E402

D = ROOT / "data/raw/community"
def load(n): return json.loads((D / n).read_text(encoding="utf-8"))

def norm(s): return (s or "").lower().replace(" ", "-").replace("_", "-")

comm_stats = {norm(e["name"]): e for e in load("pokemon__base-stats.json")}
comm_learn = {norm(k): v for k, v in load("learnsets__learnsets.json").items()}
comm_moves = {norm(m["name"]): m for m in load("moves__moves.json")}

idx = ChampionsIndex(ROOT / "data/raw/champions-pokemon-index.json")
dex = json.loads((ROOT / "data/raw/pokedex.json").read_text(encoding="utf-8"))

# 1) 名单覆盖
idx_names = {norm(cp.name) for cp in idx._by_name.values()}
comm_names = set(comm_stats)
print("index 宝可梦:", len(idx_names), " community 条目:", len(comm_names))
print("仅 index 有:", sorted(idx_names - comm_names)[:20])
print("仅 community 有:", sorted(comm_names - idx_names)[:20])

# 2) Base 种族值对账：community vs index 反推 vs Showdown
print("\n== 种族值三方对账（Base，仅列 community 与 index 反推不一致）==")
stat_keys = ["hp", "atk", "def", "spa", "spd", "spe"]
mismatch = []
for cp in idx._by_name.values():
    base = next((f for f in cp.forms if f.kind == "Base"), None)
    if not base:
        continue
    cs = comm_stats.get(norm(cp.name))
    if not cs:
        continue
    for k in stat_keys:
        cv = base.base_stats[k]
        mv = cs[k]
        if cv != mv:
            mismatch.append((cp.name, k, mv, cv))
print("community vs index反推 不一致项:", len(mismatch))
for m in mismatch[:25]:
    print("  ", m)

# 3) learnset 重合度（基础名）
print("\n== learnset 对账 ==")
common = 0
learn_mismatch = []
for cp in idx._by_name.values():
    cl = comm_learn.get(norm(cp.name))
    if not cl:
        continue
    idx_set = {norm(m) for m in cp.learnset}
    comm_set = {norm(m["name"]) for m in cl["moves"]}
    common += 1
    if idx_set != comm_set:
        learn_mismatch.append((cp.name, len(idx_set), len(comm_set),
                               sorted(idx_set - comm_set)[:5], sorted(comm_set - idx_set)[:5]))
print("可配对宝可梦:", common, " 招式集合不一致:", len(learn_mismatch))
for name, li, lc, only_idx, only_comm in learn_mismatch[:10]:
    print(f"  {name}: index {li} / comm {lc}; 仅index {only_idx}; 仅comm {only_comm}")

# 4) 招式池：index 可学去重 vs community inChampions
idx_move_pool = {norm(m) for cp in idx._by_name.values() for m in cp.learnset}
comm_inc = {k for k, m in comm_moves.items() if m.get("inChampions")}
print("\nindex 可学招式去重:", len(idx_move_pool), " community inChampions:", len(comm_inc))
print("index 可学但 community 标不可用:", sorted(idx_move_pool - comm_inc)[:20])
print("community 可用但不在任何 index 可学池:", len(comm_inc - idx_move_pool))
