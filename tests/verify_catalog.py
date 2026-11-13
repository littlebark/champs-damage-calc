"""Catalog 接入层回归校验。"""
import sys
from pathlib import Path

ROOT = Path(r"C:\Users\bark\Doubao\chats\2026-11-13\new-chat\champs-damage-calc")
sys.path.insert(0, str(ROOT))

from src.catalog import Catalog  # noqa: E402

cat = Catalog(ROOT)

species = cat.species_list()
print(f"物种形态: {len(species)}")
print(f"道具: {len(cat.item_list())}  特性: {len(cat.ability_list())}")

# 1. 中文/英文搜索
for q in ["暴雪王", "Abomasnow", "西狮海壬", "Primarina", "炽焰咆哮虎", "Incineroar", "烈咬陆鲨"]:
    e = cat.get_species(q)
    print(f"  搜索 {q!r:14} -> {e.display_name() if e else '未命中'} ({e.showdown_id if e else '-'})")

# 2. 种族值抽查（Incineroar 95/115/90/80/90/60）
inc = cat.get_species("Incineroar")
assert inc, "Incineroar 未命中"
print("\n炽焰咆哮虎 种族值:", inc.base_stats)
assert inc.base_stats == {"hp": 95, "atk": 115, "def": 90, "spa": 80, "spd": 90, "spe": 60}, inc.base_stats

# 3. 招式信息：月亮之力 Moonblast 妖精 特殊 95
mb = cat.get_move("Moonblast")
print("月亮之力:", mb.english_name, mb.type, mb.category, mb.base_power)
assert mb and mb.type == "Fairy" and mb.category == "Special" and mb.base_power == 95

# 4. Primarina 可学池含 Moonblast
prim = cat.get_species("Primarina")
if prim:
    moves = [m.english_name for m in cat.species_moves("Primarina")]
    print(f"\n西狮海壬 可学招式 {len(moves)} 个，含 Moonblast: {'Moonblast' in moves}")
    assert "Moonblast" in moves, moves[:10]
else:
    print("\nPrimarina 未在 v18/index 命中（检查对齐）")

# 5. 招式中文名抽查
for q in ["Moonblast", "Surf", "Flare Blitz"]:
    m = cat.get_move(q)
    if m:
        print(f"  招式 {q} -> {m.display_name()} ({m.type}/{m.category}/{m.base_power})")

# 6. 可学池空值统计
empty = [e.showdown_id for e in species if not e.learnset]
print(f"\n无可学池条目: {len(empty)}")

# 7. 端到端闭环：Catalog 取双方 → 构造内核输入 → 跑伤害（对照截图 月亮之力 200~236）
from src.models import Battle, Category, Field, Move  # noqa: E402
from src.typechart import TypeChart  # noqa: E402
from src.damage import calculate  # noqa: E402

prim = cat.get_species("Primarina")
garch = cat.get_species("Garchomp")
print("\n海壬种族值:", prim.base_stats, " 地龙种族值:", garch.base_stats)

atk = cat.to_pokemon_input(prim, nature="内敛", stat_points={"spa": 32})
dfn = cat.to_pokemon_input(garch, nature="坦率", stat_points={"hp": 31})
mi = cat.get_move("Moonblast")
move = Move(name=mi.display_name(), type=mi.type, category=Category(mi.category), base_power=mi.base_power)

chart = TypeChart.from_file(ROOT / "data/raw/typechart.ts")
res = calculate(Battle(attacker=atk, defender=dfn, move=move, field=Field()), chart)
print(f"伤害区间: {res.min_damage} ~ {res.max_damage}  OHKO(HP214): {res.ko_chance(214)}")
assert (res.min_damage, res.max_damage) == (200, 236), (res.min_damage, res.max_damage)
assert res.ko_chance(214) == {"ohko_rolls": 9, "ohko_pct": 56.2}

print("\nCatalog 校验完成。")
