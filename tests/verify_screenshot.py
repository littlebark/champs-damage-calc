"""用两张参考截图里的真实数值校验能力值公式与伤害管线。

样例1（截图 pmbattle.supe）：炽焰咆哮虎，慎重(+特防 -特攻)
    HP32 / 攻击0 / 防御14 / 特攻0 / 特防20 / 速度0
    截图能力值：HP202 攻击135 防御124 特攻90 特防143 速度80

样例2（截图 com.microsoft）：西狮海壬(水/妖) 月亮之力(妖精 特殊 95) 打 烈咬陆鲨(龙/地)
    海壬 特攻点32、性格×1.1 → 截图特攻195
    地龙 HP点31、特防0、性格×1.0 → 截图 HP214 / 特防105
"""
from __future__ import annotations

import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT))

from src.models import Battle, Field, Move, PokemonInput  # noqa: E402
from src.stats import calc_stats  # noqa: E402
from src.typechart import TypeChart  # noqa: E402
from src.damage import calculate  # noqa: E402


def check(name: str, actual: int, expected: int) -> None:
    ok = actual == expected
    print(f"  [{'OK' if ok else 'XX'}] {name}: {actual} (期望 {expected})")
    if not ok:
        raise AssertionError(f"{name} = {actual}, 期望 {expected}")


def main() -> None:
    # ---- 样例1：能力值 ----
    print("样例1：炽焰咆哮虎 能力值")
    老虎 = PokemonInput(
        species="Incineroar",
        types=["Fire", "Dark"],
        base_stats={"hp": 95, "atk": 115, "def": 90, "spa": 80, "spd": 90, "spe": 60},
        stat_points={"hp": 32, "atk": 0, "def": 14, "spa": 0, "spd": 20, "spe": 0},
        nature="慎重",
    )
    s = calc_stats(老虎)
    check("HP", s.hp, 202)
    check("攻击", s.atk, 135)
    check("防御", s.def_, 124)
    check("特攻", s.spa, 90)
    check("特防", s.spd, 143)
    check("速度", s.spe, 80)

    # ---- 样例2：双方能力值 + 伤害区间 ----
    print("\n样例2：西狮海壬 vs 烈咬陆鲨")
    海壬 = PokemonInput(
        species="Primarina",
        types=["Water", "Fairy"],
        base_stats={"hp": 80, "atk": 74, "def": 74, "spa": 126, "spd": 116, "spe": 60},
        stat_points={"hp": 0, "atk": 0, "def": 0, "spa": 32, "spd": 0, "spe": 0},
        nature="内敛",  # +特攻 -攻击，截图特攻修正 ×1.1
    )
    地龙 = PokemonInput(
        species="Garchomp",
        types=["Dragon", "Ground"],
        base_stats={"hp": 108, "atk": 130, "def": 95, "spa": 80, "spd": 85, "spe": 102},
        stat_points={"hp": 31, "atk": 0, "def": 0, "spa": 0, "spd": 0, "spe": 0},
        nature="坦率",
    )
    a, d = calc_stats(海壬), calc_stats(地龙)
    check("海壬 特攻", a.spa, 195)
    check("地龙 HP", d.hp, 214)
    check("地龙 特防", d.spd, 105)

    chart = TypeChart.from_file(ROOT / "data" / "raw" / "typechart.ts")
    battle = Battle(
        attacker=海壬,
        defender=地龙,
        move=Move(name="月亮之力", type="Fairy", category="Special", base_power=95),
        field=Field(),
    )
    res = calculate(battle, chart)
    print(f"\n  基础伤害: {res.base}")
    print(f"  相性倍率: {res.effectiveness}  STAB: {res.stab}")
    print(f"  伤害区间: {res.min_damage} ~ {res.max_damage}")
    print(f"  16 档: {res.rolls}")
    print(f"  修正: {', '.join(res.breakdown) if res.breakdown else '无额外修正'}")
    print(f"  OHKO 概率(对地龙 {d.hp} HP): {res.ko_chance(d.hp)}")

    print("\n全部校验通过。")


if __name__ == "__main__":
    main()
