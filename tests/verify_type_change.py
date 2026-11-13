"""特性改属性 / 免疫特性的管线端到端校验。"""
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT))

from src.models import Battle, Category, Field, Move, PokemonInput, Terrain, Weather  # noqa: E402
from src.typechart import TypeChart  # noqa: E402
from src.damage import calculate  # noqa: E402

CHART = TypeChart.from_file(ROOT / "data" / "raw" / "typechart.ts")
STATS = {"hp": 100, "atk": 100, "def": 100, "spa": 100, "spd": 100, "spe": 100}


def side(types, ability=None):
    return PokemonInput(species="x", types=list(types), base_stats=dict(STATS), ability=ability)


def run(atk, dfn, move):
    return calculate(Battle(attacker=atk, defender=dfn, move=move), CHART)


# 1. 湿润之声：精神噪音（超能·声音）打地面系 → 改写为水，水克地面 ×2
atk = side(["Water", "Fairy"], "Liquid Voice")
dfn = side(["Ground"])
move = Move(name="Psychic Noise", type="Psychic", category=Category.SPECIAL,
            base_power=65, flags={"sound": 1})
res = run(atk, dfn, move)
print("湿润之声 精神噪音 →", res.effective_type, "相性", res.effectiveness)
assert res.effective_type == "Water"
assert res.effectiveness == 2.0, "水应克制地面 ×2"

# 2. 飘浮免疫地面（伤害应为 0，不被下限抬成 1）
dfn2 = side(["Ground"], "Levitate")
ground = Move(name="Earthquake", type="Ground", category=Category.PHYSICAL, base_power=100)
res2 = run(side(["Ground"]), dfn2, ground)
print("飘浮 抗震慑 → 相性", res2.effectiveness, "伤害", res2.min_damage, "~", res2.max_damage)
assert res2.effectiveness == 0.0
assert res2.min_damage == 0 and res2.max_damage == 0
assert all(r == 0 for r in res2.rolls)

# 3. 妖精皮肤：一般招打龙系 → 改写为妖精，妖精克龙 ×2
atk3 = side(["Normal"], "Pixilate")
dfn3 = side(["Dragon"])
normal = Move(name="Hyper Voice", type="Normal", category=Category.SPECIAL,
              base_power=90, flags={"sound": 1})
res3 = run(atk3, dfn3, normal)
print("妖精皮肤 巨声 →", res3.effective_type, "相性", res3.effectiveness)
assert res3.effective_type == "Fairy"
assert res3.effectiveness == 2.0

# 4. 无特性时保持原属性
res4 = run(side(["Psychic"]), side(["Ground"]), move)
print("无特性 精神噪音 →", res4.effective_type, "相性", res4.effectiveness)
assert res4.effective_type == "Psychic"

# 5. 沙暴天气：岩石系防御方特防 ×1.5（仅特殊招式）
rock = side(["Rock"])
water_special = Move(name="Surf", type="Water", category=Category.SPECIAL, base_power=80)
none_w = Battle(attacker=side(["Water"]), defender=rock, move=water_special)
sand_w = Battle(attacker=side(["Water"]), defender=rock, move=water_special,
                field=Field(weather=Weather.SAND))
res_none = calculate(none_w, CHART)
res_sand = calculate(sand_w, CHART)
print("沙暴岩石特防：无天气", res_none.min_damage, "~", res_none.max_damage,
      " 沙暴", res_sand.min_damage, "~", res_sand.max_damage)
assert res_sand.max_damage < res_none.max_damage
assert any("沙暴 岩石特防" in b for b in res_sand.breakdown)

# 6. 厚脂肪：火 / 冰受到 ×0.5，其他属性不受影响
fat = side(["Normal"], "Thick Fat")
fire_shot = Move(name="Fire Blast", type="Fire", category=Category.SPECIAL, base_power=80)
water_shot = Move(name="Surf", type="Water", category=Category.SPECIAL, base_power=80)
res_fire = calculate(Battle(attacker=side(["Fire"]), defender=fat, move=fire_shot), CHART)
res_water = calculate(Battle(attacker=side(["Water"]), defender=fat, move=water_shot), CHART)
print("厚脂肪 火", res_fire.min_damage, "~", res_fire.max_damage,
      " 水", res_water.min_damage, "~", res_water.max_damage)
assert res_fire.max_damage < res_water.max_damage
assert any("Thick Fat" in b for b in res_fire.breakdown)

# 7. 雪天冰系物防 ×1.5（仅物理招式）
ice = side(["Ice"])
ice_punch = Move(name="Ice Hammer", type="Ice", category=Category.PHYSICAL, base_power=80)
none_snow = Battle(attacker=side(["Ice"]), defender=ice, move=ice_punch)
snow_w = Battle(attacker=side(["Ice"]), defender=ice, move=ice_punch,
                field=Field(weather=Weather.SNOW))
res_ns = calculate(none_snow, CHART)
res_sw = calculate(snow_w, CHART)
print("雪天冰系物防：无天气", res_ns.min_damage, "~", res_ns.max_damage,
      " 雪天", res_sw.min_damage, "~", res_sw.max_damage)
assert res_sw.max_damage < res_ns.max_damage
assert any("雪天 冰系物防" in b for b in res_sw.breakdown)

# 8. 气象球：晴天 → 火属性，且有天气时威力 ×2
wb = Move(name="Weather Ball", type="Normal", category=Category.SPECIAL, base_power=50)
wb_sun = Battle(attacker=side(["Normal"]), defender=side(["Ground"]), move=wb,
                 field=Field(weather=Weather.SUN))
wb_none = Battle(attacker=side(["Normal"]), defender=side(["Ground"]), move=wb)
res_wb_sun = calculate(wb_sun, CHART)
res_wb_none = calculate(wb_none, CHART)
print("气象球晴天 →", res_wb_sun.effective_type, "伤害", res_wb_sun.min_damage, "~", res_wb_sun.max_damage,
      " 无天气", res_wb_none.min_damage, "~", res_wb_none.max_damage)
assert res_wb_sun.effective_type == "Fire"
assert res_wb_sun.max_damage > res_wb_none.max_damage

# 9. 隔音免疫声音招 / 防弹免疫子弹招
sound_move = Move(name="Hyper Voice", type="Normal", category=Category.SPECIAL,
                  base_power=90, flags={"sound": 1})
res_sound = calculate(Battle(attacker=side(["Normal"]), defender=side(["Normal"], "Soundproof"),
                             move=sound_move), CHART)
print("隔音 抗巨声 → 相性", res_sound.effectiveness, "伤害", res_sound.max_damage)
assert res_sound.effectiveness == 0 and res_sound.max_damage == 0

bullet_move = Move(name="Bullet Seed", type="Grass", category=Category.PHYSICAL,
                   base_power=25, flags={"bullet": 1})
res_bullet = calculate(Battle(attacker=side(["Grass"]), defender=side(["Normal"], "Bulletproof"),
                              move=bullet_move), CHART)
print("防弹 抗种子机枪 → 相性", res_bullet.effectiveness, "伤害", res_bullet.max_damage)
assert res_bullet.effectiveness == 0 and res_bullet.max_damage == 0

# 10. 毛茸大衣物理受伤 ×0.5；朋克摇滚声音防御 ×0.5
fur = side(["Normal"], "Fur Coat")
phys = Move(name="Tackle", type="Normal", category=Category.PHYSICAL, base_power=80, flags={"contact": 1})
res_fur = calculate(Battle(attacker=side(["Normal"]), defender=fur, move=phys), CHART)
res_plain = calculate(Battle(attacker=side(["Normal"]), defender=side(["Normal"]), move=phys), CHART)
print("毛茸大衣 物理", res_fur.min_damage, "~", res_fur.max_damage,
      " 对照", res_plain.min_damage, "~", res_plain.max_damage)
assert res_fur.max_damage < res_plain.max_damage

punk = side(["Normal"], "Punk Rock")
res_punk = calculate(Battle(attacker=side(["Normal"]), defender=punk, move=sound_move), CHART)
print("朋克摇滚 声音防御", res_punk.min_damage, "~", res_punk.max_damage,
      " 对照", res_sound and res_plain.min_damage)
assert res_punk.max_damage < res_plain.max_damage

# 11. 场地脉冲：青草场地 → 草属性，且有场地时威力 ×2
tp = Move(name="Terrain Pulse", type="Normal", category=Category.SPECIAL, base_power=50)
tp_grass = Battle(attacker=side(["Normal"]), defender=side(["Ground"]), move=tp,
                  field=Field(terrain=Terrain.GRASSY))
tp_none = Battle(attacker=side(["Normal"]), defender=side(["Ground"]), move=tp)
res_tp = calculate(tp_grass, CHART)
res_tp0 = calculate(tp_none, CHART)
print("场地脉冲青草 →", res_tp.effective_type, "伤害", res_tp.min_damage, "~", res_tp.max_damage,
      " 无场地", res_tp0.min_damage, "~", res_tp0.max_damage)
assert res_tp.effective_type == "Grass"
assert res_tp.max_damage > res_tp0.max_damage

# 12. 蓬松受火 ×2；干燥皮肤受火 ×1.25
fire = Move(name="Flamethrower", type="Fire", category=Category.SPECIAL, base_power=90)
fluffy = side(["Normal"], "Fluffy")
res_fluffy = calculate(Battle(attacker=side(["Fire"]), defender=fluffy, move=fire), CHART)
res_base = calculate(Battle(attacker=side(["Fire"]), defender=side(["Normal"]), move=fire), CHART)
print("蓬松 受火", res_fluffy.min_damage, "~", res_fluffy.max_damage, " 对照", res_base.min_damage, "~", res_base.max_damage)
assert res_fluffy.min_damage > res_base.min_damage

dry = side(["Normal"], "Dry Skin")
res_dry = calculate(Battle(attacker=side(["Fire"]), defender=dry, move=fire), CHART)
print("干燥皮肤 受火", res_dry.min_damage, "~", res_dry.max_damage, " 对照", res_base.min_damage, "~", res_base.max_damage)
assert res_dry.min_damage > res_base.min_damage

print("\n属性改写 / 免疫 / 天气防御校验通过。")
