"""道具 / 特性 / 能力等级修正的单元校验。

直接断言 `modifiers` 注册表与 `stats.stage_multiplier` 的倍率；
端到端伤害倍数由浏览器回归样例另行核对。
"""
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT))

from src import modifiers as M  # noqa: E402
from src.stats import stage_multiplier  # noqa: E402


def check(name, actual, expected):
    ok = actual == expected if isinstance(expected, str) else abs(actual - expected) < 1e-9
    print(f"[{'OK' if ok else 'FAIL'}] {name}: {actual} (期望 {expected})")
    if not ok:
        raise AssertionError(name)


# ---- 道具 ----
m, _ = M.attacker_item_mult("Life Orb", "Physical", "Fire", 1.0)
check("生命宝珠 ×1.3", m, 1.3)

m, _ = M.attacker_item_mult("Choice Band", "Physical", "Normal", 1.0)
check("讲究头带 物理 ×1.15", m, 1.15)
m, _ = M.attacker_item_mult("Choice Band", "Special", "Normal", 1.0)
check("讲究头带 特殊不加成", m, 1.0)

m, _ = M.attacker_item_mult("Choice Specs", "Special", "Water", 1.0)
check("讲究眼镜 特殊 ×1.15", m, 1.15)

m, _ = M.attacker_item_mult("Expert Belt", "Physical", "Fire", 2.0)
check("达人带 克制 ×1.2", m, 1.2)
m, _ = M.attacker_item_mult("Expert Belt", "Physical", "Fire", 1.0)
check("达人带 非克制不加成", m, 1.0)

m, _ = M.attacker_item_mult("Muscle Band", "Physical", "Normal", 1.0)
check("力量头带 物理 ×1.1", m, 1.1)
m, _ = M.attacker_item_mult("Muscle Band", "Special", "Normal", 1.0)
check("力量头带 特殊不加成", m, 1.0)

m, _ = M.attacker_item_mult("Charcoal", "Physical", "Fire", 1.0)
check("木炭 火 ×1.1", m, 1.1)
m, _ = M.attacker_item_mult("Charcoal", "Physical", "Water", 1.0)
check("木炭 非火不加成", m, 1.0)

# ---- 攻击特性 ----
m, _ = M.attacker_ability_mult("Huge Power", "Physical", 80, "Normal", False, 1.0)
check("大力士 攻击 ×2", m, 2.0)
m, _ = M.attacker_ability_mult("Hustle", "Physical", 80, "Normal", False, 1.0)
check("毅力 攻击 ×1.5", m, 1.5)

m, _ = M.attacker_ability_mult("Technician", "Physical", 60, "Normal", False, 1.0)
check("技师 威力60 ×1.5", m, 1.5)
m, _ = M.attacker_ability_mult("Technician", "Physical", 75, "Normal", False, 1.0)
check("技师 威力75 不加成", m, 1.0)

m, _ = M.attacker_ability_mult("Iron Fist", "Physical", 75, "Normal", True, 1.0)
check("铁拳 拳招 ×1.2", m, 1.2)
m, _ = M.attacker_ability_mult("Iron Fist", "Physical", 75, "Normal", False, 1.0)
check("铁拳 非拳招不加成", m, 1.0)

m, _ = M.attacker_ability_mult("Torrent", "Special", 90, "Water", False, 0.3)
check("激流 残水水招 ×1.5", m, 1.5)
m, _ = M.attacker_ability_mult("Torrent", "Special", 90, "Water", False, 0.8)
check("激流 满血不加成", m, 1.0)
m, _ = M.attacker_ability_mult("Torrent", "Special", 90, "Fire", False, 0.3)
check("激流 非水招不加成", m, 1.0)

# ---- STAB ----
check("适应力 STAB ×2", M.stab_multiplier("Adaptability", "Water", ["Water", "Fairy"]), 2.0)
check("普通 STAB ×1.5", M.stab_multiplier(None, "Water", ["Water", "Fairy"]), 1.5)
check("非本系 STAB ×1", M.stab_multiplier(None, "Fire", ["Water", "Fairy"]), 1.0)

# ---- 防御特性 ----
m, _ = M.defender_ability_mult("Solid Rock", 2.0, False)
check("坚硬岩石 克制 ×0.75", m, 0.75)
m, _ = M.defender_ability_mult("Filter", 1.0, False)
check("过滤 非克制不加成", m, 1.0)
m, _ = M.defender_ability_mult("Multiscale", 2.0, True)
check("多重鳞片 满血 ×0.5", m, 0.5)
m, _ = M.defender_ability_mult("Multiscale", 2.0, False)
check("多重鳞片 非满血不加成", m, 1.0)

# ---- 暴击特性 ----
check("狙击手 暴击 ×1.5", M.crit_ability_mult("Sniper", True), 1.5)
check("狙击手 非暴击不加成", M.crit_ability_mult("Sniper", False), 1.0)

# ---- 能力等级 ----
check("等级 +1", stage_multiplier(1), 1.5)
check("等级 +2", stage_multiplier(2), 2.0)
check("等级 -1", stage_multiplier(-1), 2 / 3)
check("等级 -2", stage_multiplier(-2), 0.5)
check("等级 0", stage_multiplier(0), 1.0)

# ---- 特性改属性 ----
t, _ = M.modified_move_type("Liquid Voice", "Psychic", {"sound": 1})
check("湿润之声 声音招→水", t, "Water")
t, _ = M.modified_move_type("Liquid Voice", "Psychic", {})
check("湿润之声 非声音不改写", t, "Psychic")
t, _ = M.modified_move_type("Pixilate", "Normal", {})
check("妖精皮肤 一般→妖精", t, "Fairy")
t, _ = M.modified_move_type("Dragonize", "Normal", {})
check("龙皮肤 一般→龙", t, "Dragon")
t, _ = M.modified_move_type("Pixilate", "Fire", {})
check("皮肤 非一般不改写", t, "Fire")
check("皮肤 ×1.2", M.skin_boost_mult("Pixilate"), 1.2)
check("非皮肤 ×1", M.skin_boost_mult("Levitate"), 1.0)

# ---- 免疫特性 ----
check("飘浮免疫地面", 1 if M.ability_immune("Levitate", "Ground") else 0, 1)
check("飘浮不免疫电", 1 if M.ability_immune("Levitate", "Electric") else 0, 0)
check("储水免疫水", 1 if M.ability_immune("Water Absorb", "Water") else 0, 1)

print("\n修正项校验全部通过。")
