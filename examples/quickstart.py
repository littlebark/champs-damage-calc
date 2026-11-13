"""最小可跑示例：Catalog 取冠军数据 → 构造攻防双方 → 输出伤害区间。

运行： python examples/quickstart.py
"""
from __future__ import annotations

import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT))

from src.catalog import Catalog  # noqa: E402
from src.models import Battle, Category, Field, Move  # noqa: E402
from src.stats import calc_stats  # noqa: E402
from src.typechart import TypeChart  # noqa: E402
from src.damage import calculate  # noqa: E402


def main() -> None:
    cat = Catalog(ROOT)
    chart = TypeChart.from_file(ROOT / "data" / "raw" / "typechart.ts")

    # 中文/英文/别名均可搜索
    prim = cat.get_species("西狮海壬")
    garch = cat.get_species("烈咬陆鲨")
    print(f"攻击方: {prim.display_name()}  属性 {prim.types}  种族值 {prim.base_stats}")
    print(f"防御方: {garch.display_name()}  属性 {garch.types}  种族值 {garch.base_stats}")

    attacker = cat.to_pokemon_input(prim, nature="内敛", stat_points={"spa": 32})
    defender = cat.to_pokemon_input(garch, nature="坦率", stat_points={"hp": 31})

    mi = cat.get_move("Moonblast")  # 月亮之力
    move = Move(name=mi.display_name(), type=mi.type, category=Category(mi.category),
                base_power=mi.base_power)

    battle = Battle(attacker=attacker, defender=defender, move=move, field=Field())
    res = calculate(battle, chart)

    print("\n攻击方能力值:", calc_stats(attacker).as_dict())
    print("防御方能力值:", calc_stats(defender).as_dict())
    print(f"招式: {mi.display_name()} ({mi.type}/{mi.category}/{mi.base_power})")
    print(f"伤害区间: {res.min_damage} ~ {res.max_damage}  相性×{res.effectiveness}  STAB×{res.stab}")
    print("16 档:", res.rolls)
    print("KO 概率:", res.ko_chance(calc_stats(defender).hp))


if __name__ == "__main__":
    main()
