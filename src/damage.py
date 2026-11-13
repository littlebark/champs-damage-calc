"""伤害计算管线（Gen 9 / Champions 同款流程）。

基础伤害：
    base = ⌊ ⌊ ⌊(2·L/5 + 2) · Power · Atk / Def⌋ / 50 ⌋ + 2

修正链顺序（参照 @smogon/calc 与 52poke 伤害页）：
    多目标 → 帮助 → 天气 → 暴击 → 随机档(16) → STAB → 相性 →
    烧伤 → 墙(反射壁/光墙/极光幕) → 友谊防守 → 场地 → 道具/特性钩子

取整：pokeRound 为五舍六入（.5 向下）；随机档与相性后向下取整。
伤害下限 1。
"""
from __future__ import annotations

import math
from dataclasses import dataclass, field

from .models import Battle, Category, Terrain, Weather
from .stats import battle_stat, calc_stats
from .typechart import TypeChart
from . import modifiers


def poke_round(x: float) -> int:
    """五舍六入：恰好 .5 时向下取整（引擎的 half-down）。"""
    return int(math.floor(x + 0.5 - 1e-9)) if abs(x - math.floor(x) - 0.5) > 1e-9 else int(math.floor(x))


@dataclass
class DamageResult:
    base: int
    min_damage: int
    max_damage: int
    rolls: list[int]
    effectiveness: float
    stab: float
    effective_type: str = ""
    breakdown: list[str] = field(default_factory=list)

    def ko_chance(self, defender_hp: int) -> dict:
        """粗略 KO 统计：16 档中能击倒的档数占比。"""
        ohko = sum(1 for r in self.rolls if r >= defender_hp)
        return {"ohko_rolls": ohko, "ohko_pct": round(ohko / 16 * 100, 1)}


# ---- 各修正项 ----

def _weather_mult(weather: Weather, move_type: str) -> tuple[float, str]:
    if weather == Weather.SUN:
        if move_type == "Fire":
            return 1.5, "晴天 火×1.5"
        if move_type == "Water":
            return 0.5, "晴天 水×0.5"
    if weather == Weather.RAIN:
        if move_type == "Water":
            return 1.5, "雨天 水×1.5"
        if move_type == "Fire":
            return 0.5, "雨天 火×0.5"
    return 1.0, ""


def _weather_defense_mult(weather: Weather, defender_types: list[str],
                          category: Category) -> tuple[float, str]:
    """天气对防御能力的加成（对照 champions.ts）：
    沙暴岩石系特防 ×1.5（仅特殊招式）；雪天冰系物防 ×1.5（仅物理招式）。"""
    if weather == Weather.SAND and "Rock" in defender_types and category != Category.PHYSICAL:
        return 1.5, "沙暴 岩石特防×1.5"
    if weather == Weather.SNOW and "Ice" in defender_types and category == Category.PHYSICAL:
        return 1.5, "雪天 冰系物防×1.5"
    return 1.0, ""


def _terrain_mult(terrain: Terrain, move_type: str, attacker_grounded: bool, defender_grounded: bool) -> tuple[float, str]:
    if terrain == Terrain.ELECTRIC and attacker_grounded and move_type == "Electric":
        return 1.3, "电气场地 电×1.3"
    if terrain == Terrain.GRASSY and attacker_grounded and move_type == "Grass":
        return 1.3, "青草场地 草×1.3"
    if terrain == Terrain.PSYCHIC and attacker_grounded and move_type == "Psychic":
        return 1.3, "精神场地 超能×1.3"
    if terrain == Terrain.MISTY and defender_grounded and move_type == "Dragon":
        return 0.5, "薄雾场地 龙×0.5"
    return 1.0, ""


def _screen_mult(field, category: Category) -> tuple[float, str]:
    if field.aurora_veil:
        return (0.5, "极光幕×0.5") if not field.doubles else (2 / 3, "极光幕×2/3")
    if category == Category.PHYSICAL and field.reflect:
        return (0.5, "反射壁×0.5") if not field.doubles else (2 / 3, "反射壁×2/3")
    if category == Category.SPECIAL and field.light_screen:
        return (0.5, "光墙×0.5") if not field.doubles else (2 / 3, "光墙×2/3")
    return 1.0, ""


def calculate(battle: Battle, chart: TypeChart, crit: bool = False) -> DamageResult:
    f = battle.field
    atk_p, def_p = battle.attacker, battle.defender
    move = battle.move
    move_key = move.english_name or move.name

    atk_stats = calc_stats(atk_p)
    def_stats = calc_stats(def_p)
    atk_spe = battle_stat(atk_stats.spe, atk_p.stat_stage["spe"])
    def_spe = battle_stat(def_stats.spe, def_p.stat_stage["spe"])

    # 选取攻击/防御能力值；暴击无视攻击方负向阶段与防御方正向阶段
    if move.category == Category.PHYSICAL:
        atk_val = battle_stat(atk_stats.atk, atk_p.stat_stage["atk"], ignore_stage=crit and atk_p.stat_stage["atk"] < 0)
        def_val = battle_stat(def_stats.def_, def_p.stat_stage["def"], ignore_stage=crit and def_p.stat_stage["def"] > 0)
    else:
        atk_val = battle_stat(atk_stats.spa, atk_p.stat_stage["spa"], ignore_stage=crit and atk_p.stat_stage["spa"] < 0)
        def_val = battle_stat(def_stats.spd, def_p.stat_stage["spd"], ignore_stage=crit and def_p.stat_stage["spd"] > 0)

    # 天气防御加成（沙暴岩石 / 雪天冰系特防 ×1.5）
    def_mult, def_note = _weather_defense_mult(f.weather, def_p.types, move.category)
    if def_mult != 1.0:
        def_val *= def_mult

    weather_str = f.weather.value if f.weather else None
    terrain_str = f.terrain.value if f.terrain else None
    bp = move.base_power
    if move_key == "Weather Ball":
        bp = poke_round(bp * modifiers.weather_ball_mult(weather_str))
    if move_key == "Terrain Pulse":
        bp = poke_round(bp * modifiers.terrain_pulse_mult(terrain_str))
    if move_key == "Electro Ball":
        bp = modifiers.electro_ball_bp(atk_spe, def_spe)
    if move_key == "Gyro Ball":
        bp = modifiers.gyro_ball_bp(atk_spe, def_spe)
    level_term = math.floor(2 * battle.level / 5 + 2)
    base = math.floor(math.floor(math.floor(level_term * bp * atk_val / def_val) / 50)) + 2

    # 特性改写招式属性（湿润之声 / 皮肤类），再判定免疫与相性
    move_type, type_note = modifiers.modified_move_type(
        atk_p.ability, move.type, move.flags)
    # 气象球：按天气改写属性
    if move_key == "Weather Ball":
        move_type = modifiers.weather_ball_type(weather_str)
        type_note = f"气象球→{move_type}"
    # 场地脉冲：按场地改写属性
    if move_key == "Terrain Pulse":
        move_type = modifiers.terrain_pulse_type(terrain_str)
        type_note = f"场地脉冲→{move_type}"
    if modifiers.ability_immune(def_p.ability, move_type):
        eff = 0.0
    else:
        flag_immune, _flag = modifiers.ability_flag_immune(def_p.ability, move.flags)
        eff = 0.0 if flag_immune else chart.effectiveness(move_type, def_p.types)
    # STAB（适应力 ×2，否则本系 ×1.5），按改写后的属性判定
    stab = modifiers.stab_multiplier(atk_p.ability, move_type, atk_p.types)

    # 道具 / 特性修正所需上下文
    flags = move.flags or {}
    is_punch = "punch" in flags
    is_contact = "contact" in flags
    is_bite = "bite" in flags
    is_pulse = "pulse" in flags
    is_slicing = "slicing" in flags
    is_sound = "sound" in flags
    is_physical = move.category == Category.PHYSICAL
    cat_value = move.category.value if hasattr(move.category, "value") else str(move.category)
    atk_hp_ratio = (atk_p.current_hp or atk_stats.hp) / atk_stats.hp if atk_stats.hp else 1.0
    def_full_hp = def_p.current_hp is None or def_p.current_hp >= def_stats.hp

    item_mult, item_note = modifiers.attacker_item_mult(
        atk_p.item, cat_value, move.type, eff)
    atk_ab_mult, atk_ab_note = modifiers.attacker_ability_mult(
        atk_p.ability, cat_value, bp, move_type, is_punch, atk_hp_ratio,
        is_contact, is_bite, is_pulse, is_slicing, is_sound, weather_str,
        atk_p.status, has_secondary=move.has_secondary)
    def_ab_mult, def_ab_note = modifiers.defender_ability_mult(
        def_p.ability, eff, def_full_hp, move_type, is_contact, is_sound,
        is_physical, terrain_str, def_p.status)

    # 斗争心（性别）/ 分析（先后手）/ 防御方抗性浆果
    if atk_p.ability == "Rivalry":
        rivalry_m, rivalry_note = modifiers.rivalry_mult(atk_p.gender, def_p.gender)
    else:
        rivalry_m, rivalry_note = 1.0, ""
    if atk_p.ability == "Analytic":
        analytic_m, analytic_note = modifiers.analytic_mult(atk_p.ability_on, atk_spe, def_spe)
    else:
        analytic_m, analytic_note = 1.0, ""
    berry_m, berry_note = modifiers.defender_item_berry_mult(
        def_p.item, move_type, eff, ripen=def_p.ability == "Ripen")
    crit_ab_mult = modifiers.crit_ability_mult(atk_p.ability, crit)

    breakdown: list[str] = []

    # 1. 多目标
    mult = 1.0
    if f.spread and f.doubles:
        mult *= 0.75
        breakdown.append("双打多目标×0.75")
    # 2. 帮助
    if f.helping_hand:
        mult *= 1.5
        breakdown.append("帮助×1.5")
    # 3. 天气（按改写后的属性判定）
    w_mult, w_note = _weather_mult(f.weather, move_type)
    if w_mult != 1.0:
        mult *= w_mult
        breakdown.append(w_note)
    if def_note:
        breakdown.append(def_note)
    # 4. 暴击（含狙击手额外暴击倍率）
    crit_mult = 1.5 if crit else 1.0
    crit_mult *= crit_ab_mult
    if crit:
        breakdown.append("暴击×1.5" + ("，狙击手×1.5" if crit_ab_mult != 1.0 else ""))
    # 5. 场地（攻击/防御两侧）
    t_mult, t_note = _terrain_mult(f.terrain, move_type, atk_p.is_grounded, def_p.is_grounded)
    if t_mult != 1.0:
        mult *= t_mult
        breakdown.append(t_note)
    # 6. 烧伤（物理）
    burn_mult = 1.0
    guts = atk_p.ability == "Guts"
    if atk_p.status == "burn" and move.category == Category.PHYSICAL and not guts:
        burn_mult = 0.5
        breakdown.append("烧伤 物理×0.5")
    # 7. 墙
    screen_mult, s_note = _screen_mult(f, move.category)
    if screen_mult != 1.0:
        mult *= screen_mult
        breakdown.append(s_note)
    # 8. 友谊防守
    if f.friend_guard:
        mult *= 0.75
        breakdown.append("友谊防守×0.75")
    # 特性改属性 / 免疫说明
    if type_note:
        breakdown.append(type_note)
    if eff == 0.0:
        if modifiers.ability_immune(def_p.ability, move_type):
            breakdown.append(f"{def_p.ability} 免疫{move_type}")
        else:
            flag_immune, flag = modifiers.ability_flag_immune(def_p.ability, flags)
            if flag_immune:
                breakdown.append(f"{def_p.ability} 免疫{modifiers.FLAG_ZH.get(flag, flag)}")
    # 9. 攻击方道具 / 特性、防御方特性
    if item_mult != 1.0:
        mult *= item_mult
        breakdown.append(item_note)
    if atk_ab_mult != 1.0:
        mult *= atk_ab_mult
        breakdown.append(atk_ab_note)
    if def_ab_mult != 1.0:
        mult *= def_ab_mult
        breakdown.append(def_ab_note)
    if rivalry_m != 1.0:
        mult *= rivalry_m
        breakdown.append(rivalry_note)
    if analytic_m != 1.0:
        mult *= analytic_m
        breakdown.append(analytic_note)
    if berry_m != 1.0:
        mult *= berry_m
        breakdown.append(berry_note)

    # 随机 16 档：固定修正部分保留浮点倍率，逐档滚随机后再取整
    fixed = mult * crit_mult
    rolls: list[int] = []
    for n in range(16):
        if eff == 0.0:
            rolls.append(0)
            continue
        rand = (85 + n) / 100
        d = math.floor(base * fixed * rand)
        d = poke_round(d * stab)
        d = math.floor(d * eff)
        d = math.floor(d * burn_mult)
        rolls.append(max(1, d))

    return DamageResult(
        base=base,
        min_damage=min(rolls),
        max_damage=max(rolls),
        rolls=rolls,
        effectiveness=eff,
        stab=stab,
        effective_type=move_type,
        breakdown=breakdown,
    )
