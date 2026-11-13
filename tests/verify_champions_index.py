"""验证 champions index 加载：形态、可学招式、反推种族值与底座对照。"""
import json
import sys
from pathlib import Path

ROOT = Path(r"C:\Users\bark\Doubao\chats\2026-11-13\new-chat\champs-damage-calc")
sys.path.insert(0, str(ROOT))

from src.champions_data import ChampionsIndex  # noqa: E402
idx = ChampionsIndex(ROOT / "data/raw/champions-pokemon-index.json")
dex = json.loads((ROOT / "data/raw/pokedex.json").read_text(encoding="utf-8"))

print("赛季:", idx.seasons)
print("宝可梦总数:", len(idx.list_names()))

for label in ["Primarina", "Garchomp", "Incineroar"]:
    cp = idx.get(label)
    assert cp, label
    print(f"\n== {cp.name} (slug={cp.slug}, showdown={cp.showdown_id}) ==")
    print(f"可学招式 {len(cp.learnset)} 条")
    for f in cp.forms:
        print(f"  形态 {f.name} [{f.kind}] types={f.types} 特性={f.abilities}")
        print(f"    反推种族={f.base_stats}  默认能力值={f.default_stats}")

# Base 形态反推种族 vs Showdown 底座
print("\n== Base 形态种族值对照（底座 vs 冠军反推）==")
mismatch = 0
for cp in idx._by_name.values():
    base = next((f for f in cp.forms if f.kind == "Base"), None)
    if not base or not cp.showdown_id:
        continue
    sd = dex.get(cp.showdown_id)
    if not sd:
        continue
    bb = sd["baseStats"]
    for k, ch_v in base.base_stats.items():
        sd_v = bb.get(k[1:] if k == "spa" else k)
        if sd_v is not None and sd_v != ch_v:
            mismatch += 1
            if mismatch <= 10:
                print(f"  {cp.name} {k}: 底座 {sd_v} / 冠军反推 {ch_v}")
print("Base 种族值不一致项数:", mismatch)
