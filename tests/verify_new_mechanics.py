"""新机制端到端管线校验：强行 / 斗争心 / 分析 / 抗性浆果 / 速度威力招。"""
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT))

from src.models import Battle, Category, Move, PokemonInput  # noqa: E402
from src.typechart import TypeChart  # noqa: E402
from src.damage import calculate  # noqa: E402

CHART = TypeChart.from_file(ROOT / "data" / "raw" / "typechart.ts")
STATS = {"hp": 100, "atk": 100, "def": 100, "spa": 100, "spd": 100, "spe": 100}


def side(types, ability=None, gender=None, ability_on=None, item=None, spe=100):
    stats = dict(STATS)
    stats["spe"] = spe
    return PokemonInput(
        species="x", types=list(types), base_stats=stats, ability=ability,
        gender=gender, ability_on=ability_on, item=item,
    )


def run(atk, dfn, move):
    return calculate(Battle(attacker=atk, defender=dfn, move=move), CHART)


def bp_move(name, mtype, cat=Category.SPECIAL, power=80, secondary=False, flags=None):
    return Move(name=name, type=mtype, category=cat, base_power=power,
                flags=flags or {}, english_name=name, has_secondary=secondary)


# 1. 强行：带附加效果的招 ×1.3；无附加效果不加成
sf = run(side(["Fire"], "Sheer Force"), side(["Normal"]),
         bp_move("Fire Fang", "Fire", secondary=True))
plain = run(side(["Fire"]), side(["Normal"]),
            bp_move("Fire Fang", "Fire", secondary=True))
print("强行 有附加效果:", sf.min_damage, "~", sf.max_damage, sf.breakdown)
assert any("附加效果×1.3" in n for n in sf.breakdown)
ratio = sf.max_damage / plain.max_damage
assert abs(ratio - 1.3) < 0.05, f"强行倍率应≈1.3，实际 {ratio}"

sf_no = run(side(["Fire"], "Sheer Force"), side(["Normal"]),
            bp_move("Flame Charge", "Fire", secondary=False))
assert not any("附加效果" in n for n in sf_no.breakdown)

# 2. 斗争心：同性 ×1.25 / 异性 ×0.75 / 未知 ×1.0
same = run(side(["Normal"], "Rivalry", gender="M"), side(["Normal"], gender="M"),
           bp_move("Tackle", "Normal", Category.PHYSICAL))
print("斗争心 同性:", same.min_damage, "~", same.max_damage, same.breakdown)
assert any("同性×1.25" in n for n in same.breakdown)

opp = run(side(["Normal"], "Rivalry", gender="M"), side(["Normal"], gender="F"),
          bp_move("Tackle", "Normal", Category.PHYSICAL))
print("斗争心 异性:", opp.min_damage, "~", opp.max_damage, opp.breakdown)
assert any("异性×0.75" in n for n in opp.breakdown)

unk = run(side(["Normal"], "Rivalry", gender="M"), side(["Normal"], gender="N"),
          bp_move("Tackle", "Normal", Category.PHYSICAL))
assert not any("斗争心" in n for n in unk.breakdown)

# 3. 分析：慢速自动发动 ×1.3；快速不发动；手动覆盖
slow = run(side(["Psychic"], "Analytic", spe=40), side(["Psychic"], spe=100),
           bp_move("Psychic", "Psychic"))
print("分析 慢速:", slow.min_damage, "~", slow.max_damage, slow.breakdown)
assert any("后手×1.3" in n for n in slow.breakdown)

fast = run(side(["Psychic"], "Analytic", spe=100), side(["Psychic"], spe=40),
           bp_move("Psychic", "Psychic"))
assert not any("后手×1.3" in n for n in fast.breakdown)

forced = run(side(["Psychic"], "Analytic", spe=100, ability_on=True),
             side(["Psychic"], spe=40), bp_move("Psychic", "Psychic"))
assert any("后手×1.3" in n for n in forced.breakdown)

forced_off = run(side(["Psychic"], "Analytic", spe=40, ability_on=False),
                 side(["Psychic"], spe=100), bp_move("Psychic", "Psychic"))
assert not any("后手×1.3" in n for n in forced_off.breakdown)

# 4. 抗性浆果：克制属性 ×0.5；非对应属性不触发
berry = run(side(["Fighting"]), side(["Steel"], item="Chople Berry"),
            bp_move("Low Kick", "Fighting", Category.PHYSICAL))
print("浆果 抗格斗:", berry.min_damage, "~", berry.max_damage, berry.breakdown)
assert any("×0.5" in n and "Chople" in n for n in berry.breakdown)

berry_no = run(side(["Fire"]), side(["Steel"], item="Chople Berry"),
               bp_move("Fire Fang", "Fire", Category.PHYSICAL))
assert not any("Chople" in n for n in berry_no.breakdown)

# 5. 电球：威力随攻/守速度比变化（回调 BP）
eb_fast = run(side(["Electric"], spe=400), side(["Electric"], spe=100),
              bp_move("Electro Ball", "Electric", power=0))
eb_slow = run(side(["Electric"], spe=50), side(["Electric"], spe=100),
              bp_move("Electro Ball", "Electric", power=0))
print("电球 快:", eb_fast.min_damage, "~", eb_fast.max_damage,
      " 慢:", eb_slow.min_damage, "~", eb_slow.max_damage)
assert eb_fast.max_damage > eb_slow.max_damage

print("\n新机制端到端校验通过。")
