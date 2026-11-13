"""道具 / 特性伤害修正注册表（显式规则，按 Gen 9 / Champions 常用条目）。

底座 `items.ts` / `abilities.ts` 的效果是事件钩子函数，不便直接解析；这里把计算器
需要的伤害修正逐条写成显式规则。未收录的道具 / 特性返回 1.0 并标注「待接入」，
不臆造倍率。

修正分类：
- 攻击侧道具倍率（乘到最终伤害）；
- 攻击侧特性倍率（攻击能力倍率 / 威力条件 / 属性条件）；
- 防御侧特性减伤倍率；
- STAB 倍率（适应力改为 ×2）；
- 暴击伤害倍率（狙击手在暴击时额外 ×1.5）。
"""
from __future__ import annotations

import math
from typing import Optional

# ---- 攻击道具：直接伤害倍率 ----
# 通用道具（不看招式分类）
ITEM_DIRECT_MULT: dict[str, float] = {
    "Life Orb": 1.3,       # 生命宝珠
    "Choice Band": 1.15,  # 讲究头带（物理，在分类判定里处理）
    "Choice Specs": 1.15,  # 讲究眼镜（特殊）
    "Muscle Band": 1.1,    # 力量头带（物理）
}

# 分类限定道具：{道具: (适用分类, 倍率)}
ITEM_CATEGORY_MULT: dict[str, tuple[str, float]] = {
    "Choice Band": ("Physical", 1.15),
    "Choice Specs": ("Special", 1.15),
    "Muscle Band": ("Physical", 1.1),
}

# 抗性浆果：{道具: 被减伤属性}。守方被该属性击中且克制/一般时 ×0.5（Ripen ×0.25）
# 对照 smogon damage-calc items.ts getBerryResistType
BERRY_RESIST_TYPE: dict[str, str] = {
    "Chilan Berry": "Normal",
    "Occa Berry": "Fire",
    "Passho Berry": "Water",
    "Wacan Berry": "Electric",
    "Rindo Berry": "Grass",
    "Yache Berry": "Ice",
    "Chople Berry": "Fighting",
    "Kebia Berry": "Poison",
    "Shuca Berry": "Ground",
    "Coba Berry": "Flying",
    "Payapa Berry": "Psychic",
    "Tanga Berry": "Bug",
    "Charti Berry": "Rock",
    "Kasib Berry": "Ghost",
    "Haban Berry": "Dragon",
    "Colbur Berry": "Dark",
    "Babiri Berry": "Steel",
    "Roseli Berry": "Fairy",
}

# 属性增强道具：{道具: 属性}，对应属性招式 ×1.1
ITEM_TYPE_BOOST: dict[str, str] = {
    "Charcoal": "Fire",
    "Miracle Seed": "Grass",
    "Mystic Water": "Water",
    "Silver Powder": "Bug",
    "Black Belt": "Fighting",
    "Hard Stone": "Rock",
    "Sharp Beak": "Flying",
    "Poison Barb": "Poison",
    "Soft Sand": "Ground",
    "Twisted Spoon": "Psychic",
    "Black Glasses": "Dark",
    "Metal Coat": "Steel",
    "Dragon Fang": "Dragon",
    "Never-Melt Ice": "Ice",
    "Magnet": "Electric",
}

# 特性：攻击能力 ×固定倍率（等价于攻击能力值乘算）
ABILITY_ATK_MULT: dict[str, float] = {
    "Huge Power": 2.0,   # 大力士
    "Pure Power": 2.0,   # 瑜伽之力
    "Hustle": 1.5,       # 活力（攻击↑，命中↓，伤害侧只取攻击倍率）
}

# 特性：威力 ≤60 的招式 ×1.5
TECHNICIAN_ABILITIES = {"Technician"}

# 特性：拳类招式 ×1.2
IRON_FIST_ABILITIES = {"Iron Fist"}

# 特性：半血（HP ≤1/3）对应属性招式 ×1.5
ABILITY_HP_BOOST_TYPE: dict[str, str] = {
    "Torrent": "Water",
    "Blaze": "Fire",
    "Overgrow": "Grass",
    "Swarm": "Bug",
}

# 特性：防御侧，受到克制伤害 ×0.75
FILTER_ABILITIES = {"Solid Rock", "Filter"}

# 特性：防御侧，指定属性受到 ×0.5（厚脂肪：火 / 冰）
ABILITY_RESIST_TYPE: dict[str, list[str]] = {
    "Thick Fat": ["Fire", "Ice"],
    "Heatproof": ["Fire"],          # 耐热
    "Purifying Salt": ["Ghost"],    # 洁净之盐
    "Water Bubble": ["Fire"],       # 水泡（防火）
}

# 特性：接触类招式 ×1.3（硬爪）
TOUGH_CLAWS_ABILITIES = {"Tough Claws"}

# 特性：指定标记招式威力 ×1.5 {特性: (标记, 倍率)}
ABILITY_FLAG_BP: dict[str, tuple[str, float]] = {
    "Mega Launcher": ("pulse", 1.5),   # 超级发射器（波招）
    "Strong Jaw": ("bite", 1.5),       # 强壮之颚（咬招）
    "Sharpness": ("slicing", 1.5),      # 锋锐（切招）
}

# 特性：指定属性招式常驻 ×1.5
ABILITY_TYPE_BP: dict[str, list[str]] = {
    "Steely Spirit": ["Steel"],
    "Fire Mane": ["Fire"],
}

# 特性：沙暴天气下岩 / 地 / 钢招式 ×1.3（沙之力）
SAND_FORCE_ABILITIES = {"Sand Force"}
SAND_FORCE_TYPES = {"Rock", "Ground", "Steel"}

# 特性：声音招式攻击 ×1.3 / 防御 ×0.5（ punk rock）
PUNK_ROCK_ABILITIES = {"Punk Rock"}

# 招式标记 → 中文说明词
FLAG_ZH: dict[str, str] = {
    "punch": "拳招", "contact": "接触招", "bite": "咬招", "pulse": "波招",
    "slicing": "切招", "sound": "声音招", "bullet": "子弹招",
}

# 防御特性：物理受伤 ×0.5（物防翻倍，毛皮大衣）
DEFENDER_PHYS_HALF = {"Fur Coat"}

# 防御特性：接触招受伤 ×0.5（毛茸茸 / 波导防护）
DEFENDER_CONTACT_HALF = {"Fluffy", "Aura Guard"}

# 特性：满血时受伤 ×0.5
MULTISCALE_ABILITIES = {"Multiscale", "Multiscale Shield"}

# 特性：暴击时伤害再 ×1.5
SNIPER_ABILITIES = {"Sniper"}

# 特性：声音类招式改写为指定属性（湿润之声：声音招 → 水）
ABILITY_VOICE_TYPE: dict[str, str] = {
    "Liquid Voice": "Water",
}

# 特性：一般属性招式改写为指定属性（皮肤类），并附带 ×1.2 属性加成
ABILITY_SKIN_TYPE: dict[str, str] = {
    "Pixilate": "Fairy",       # 妖精皮肤
    "Refrigerate": "Ice",      # 冰冻皮肤
    "Aerilate": "Flying",      # 飞行皮肤
    "Galvanize": "Electric",   # 电皮肤
    "Dragonize": "Dragon",     # 龙皮肤
}

# 防御方特性：免疫指定属性（招式伤害按 0 处理）
ABILITY_IMMUNE: dict[str, str] = {
    "Levitate": "Ground",       # 飘浮
    "Eelevate": "Ground",        # 鳗鳗高升
    "Volt Absorb": "Electric",   # 蓄电
    "Motor Drive": "Electric",  # 电气引擎
    "Lightning Rod": "Electric", # 避雷针
    "Water Absorb": "Water",    # 储水
    "Storm Drain": "Water",      # 引水（对照表未收录，主系列通用译名）
    "Dry Skin": "Water",         # 干燥皮肤
    "Flash Fire": "Fire",       # 引火
    "Earth Eater": "Ground",     # 食土
    "Sap Sipper": "Grass",       # 食草
}

# 防御方特性：免疫带指定标记的招式
ABILITY_FLAG_IMMUNE: dict[str, str] = {
    "Bulletproof": "bullet",     # 防弹
    "Soundproof": "sound",       # 隔音
}


# 气象球：按天气改变属性
WEATHER_BALL_TYPE: dict[str, str] = {
    "Sun": "Fire",
    "Rain": "Water",
    "Sand": "Rock",
    "Snow": "Ice",
}


def weather_ball_type(weather: Optional[str]) -> str:
    """气象球在对应天气下的属性；无天气返回一般。"""
    return WEATHER_BALL_TYPE.get(weather or "", "Normal")


def weather_ball_mult(weather: Optional[str]) -> float:
    """气象球有天气时威力 ×2。"""
    return 2.0 if weather in WEATHER_BALL_TYPE else 1.0


# 场地脉冲：按场地改变属性
TERRAIN_PULSE_TYPE: dict[str, str] = {
    "Electric": "Electric",
    "Grassy": "Grass",
    "Misty": "Fairy",
    "Psychic": "Psychic",
}


def terrain_pulse_type(terrain: Optional[str]) -> str:
    """场地脉冲在对应场地的属性；无场地返回一般。"""
    return TERRAIN_PULSE_TYPE.get(terrain or "", "Normal")


def terrain_pulse_mult(terrain: Optional[str]) -> float:
    """场地脉冲有场地时威力 ×2。"""
    return 2.0 if terrain in TERRAIN_PULSE_TYPE else 1.0


def modified_move_type(ability: Optional[str], move_type: str,
                       flags: dict) -> tuple[str, str]:
    """攻击方特性改写招式属性。返回 (属性, 说明)；不改写则原样返回。"""
    if not ability:
        return move_type, ""
    if ability in ABILITY_VOICE_TYPE and "sound" in (flags or {}):
        to = ABILITY_VOICE_TYPE[ability]
        return to, f"{ability} 声音招→{to}"
    if ability in ABILITY_SKIN_TYPE and move_type == "Normal":
        to = ABILITY_SKIN_TYPE[ability]
        return to, f"{ability} 一般招→{to}"
    return move_type, ""


def skin_boost_mult(ability: Optional[str]) -> float:
    """皮肤类特性对改写后招式的 ×1.2 加成。"""
    return 1.2 if ability in ABILITY_SKIN_TYPE else 1.0


def ability_immune(ability: Optional[str], move_type: str) -> bool:
    """防御方特性是否免疫该属性。"""
    return ABILITY_IMMUNE.get(ability) == move_type


def ability_flag_immune(ability: Optional[str], flags: dict) -> tuple[bool, str]:
    """防御方特性是否免疫带指定标记的招式。返回 (是否免疫, 标记名)。"""
    if not ability:
        return False, ""
    flag = ABILITY_FLAG_IMMUNE.get(ability)
    if flag and flag in (flags or {}):
        return True, flag
    return False, ""


def attacker_item_mult(item: Optional[str], category: str, move_type: str,
                       effectiveness: float) -> tuple[float, str]:
    """攻击方道具伤害倍率。返回 (倍率, 说明)。"""
    if not item:
        return 1.0, ""
    if item in ITEM_CATEGORY_MULT:
        cat, mult = ITEM_CATEGORY_MULT[item]
        if category == cat:
            return mult, f"{item} ×{mult}"
        return 1.0, ""
    if item == "Expert Belt":  # 达人带：克制招式 ×1.2
        if effectiveness > 1:
            return 1.2, f"{item} 克制×1.2"
        return 1.0, ""
    boosted_type = ITEM_TYPE_BOOST.get(item)
    if boosted_type and move_type == boosted_type:
        return 1.1, f"{item} {boosted_type}×1.1"
    if item in ITEM_DIRECT_MULT:
        return ITEM_DIRECT_MULT[item], f"{item} ×{ITEM_DIRECT_MULT[item]}"
    return 1.0, ""


def attacker_ability_mult(ability: Optional[str], category: str, base_power: int,
                          move_type: str, is_punch: bool, hp_ratio: float,
                          is_contact: bool = False, is_bite: bool = False,
                          is_pulse: bool = False, is_slicing: bool = False,
                          is_sound: bool = False, weather: Optional[str] = None,
                          atk_status: Optional[str] = None,
                          has_secondary: bool = False,
                          ) -> tuple[float, str]:
    """攻击方特性伤害倍率。hp_ratio = 当前 HP / 最大 HP。返回 (倍率, 说明)。"""
    if not ability:
        return 1.0, ""
    notes: list[str] = []
    mult = 1.0

    if ability in ABILITY_ATK_MULT:
        m = ABILITY_ATK_MULT[ability]
        mult *= m
        notes.append(f"{ability} 攻击×{m}")

    if ability in TECHNICIAN_ABILITIES and base_power > 0 and base_power <= 60:
        mult *= 1.5
        notes.append(f"{ability} 低威力×1.5")

    if ability in IRON_FIST_ABILITIES and is_punch:
        mult *= 1.2
        notes.append(f"{ability} 拳招×1.2")

    if ability in TOUGH_CLAWS_ABILITIES and is_contact:
        mult *= 1.3
        notes.append(f"{ability} 接触招×1.3")

    flag_key, flag_mult = ABILITY_FLAG_BP.get(ability, ("", 1.0))
    flag_hit = (is_bite if flag_key == "bite" else
                is_pulse if flag_key == "pulse" else
                is_slicing if flag_key == "slicing" else False)
    if flag_key and flag_hit:
        mult *= flag_mult
        notes.append(f"{ability} {FLAG_ZH.get(flag_key, flag_key)}×{flag_mult}")

    if ability in ABILITY_TYPE_BP and move_type in ABILITY_TYPE_BP[ability]:
        mult *= 1.5
        notes.append(f"{ability} {move_type}×1.5")

    if ability == "Water Bubble" and move_type == "Water":
        mult *= 1.5
        notes.append(f"{ability} 水×1.5")

    if ability == "Guts" and atk_status and category == "Physical":
        mult *= 1.5
        notes.append(f"{ability} 异常物理×1.5")

    if ability == "Solar Power" and weather == "Sun" and category == "Special":
        mult *= 1.5
        notes.append(f"{ability} 晴天特殊×1.5")

    if ability in SAND_FORCE_ABILITIES and weather == "Sand" and move_type in SAND_FORCE_TYPES:
        mult *= 1.3
        notes.append(f"{ability} 沙暴{move_type}×1.3")

    if ability in PUNK_ROCK_ABILITIES and is_sound:
        mult *= 1.3
        notes.append(f"{ability} 声音招×1.3")

    if ability == "Sheer Force" and has_secondary:
        mult *= 1.3
        notes.append(f"{ability} 附加效果×1.3")

    boosted = ABILITY_HP_BOOST_TYPE.get(ability)
    if boosted and move_type == boosted and hp_ratio <= 1 / 3:
        mult *= 1.5
        notes.append(f"{ability} 残血{boosted}×1.5")

    if ability in ABILITY_SKIN_TYPE and move_type == "Normal":
        mult *= 1.2
        notes.append("皮肤 ×1.2")

    return mult, "、".join(notes)


def defender_ability_mult(ability: Optional[str], effectiveness: float,
                         full_hp: bool, move_type: Optional[str] = None,
                         is_contact: bool = False, is_sound: bool = False,
                         is_physical: bool = False, terrain: Optional[str] = None,
                         status: Optional[str] = None) -> tuple[float, str]:
    """防御方特性减伤倍率。返回 (倍率, 说明)。"""
    if not ability:
        return 1.0, ""
    notes: list[str] = []
    mult = 1.0

    if ability in FILTER_ABILITIES and effectiveness > 1:
        mult *= 0.75
        notes.append(f"{ability} 克制×0.75")

    resist = ABILITY_RESIST_TYPE.get(ability)
    if resist and move_type in resist:
        mult *= 0.5
        notes.append(f"{ability} {move_type}×0.5")

    if ability in DEFENDER_PHYS_HALF and is_physical:
        mult *= 0.5
        notes.append(f"{ability} 物理×0.5")

    if ability in DEFENDER_CONTACT_HALF and is_contact:
        mult *= 0.5
        notes.append(f"{ability} 接触招×0.5")

    if ability in PUNK_ROCK_ABILITIES and is_sound:
        mult *= 0.5
        notes.append(f"{ability} 声音招×0.5")

    if ability == "Dry Skin" and move_type == "Fire":
        mult *= 1.25
        notes.append(f"{ability} 受火×1.25")

    if ability == "Fluffy" and move_type == "Fire":
        mult *= 2.0
        notes.append(f"{ability} 受火×2")

    if ability == "Grass Pelt" and terrain == "Grassy" and is_physical:
        mult *= 2 / 3
        notes.append(f"{ability} 青草物理×2/3")

    if ability == "Marvel Scale" and status and is_physical:
        mult *= 2 / 3
        notes.append(f"{ability} 异常物理×2/3")

    if ability in MULTISCALE_ABILITIES and full_hp:
        mult *= 0.5
        notes.append(f"{ability} 满血×0.5")

    return mult, "、".join(notes)


def stab_multiplier(ability: Optional[str], move_type: str, attacker_types: list[str]) -> float:
    """本系一致倍率：适应力 ×2，否则 ×1.5。"""
    if move_type not in attacker_types:
        return 1.0
    if ability == "Adaptability":
        return 2.0
    return 1.5


def crit_ability_mult(ability: Optional[str], crit: bool) -> float:
    """暴击相关特性倍率（狙击手）。"""
    if crit and ability in SNIPER_ABILITIES:
        return 1.5
    return 1.0


def berry_resist_type(item: Optional[str]) -> Optional[str]:
    """抗性浆果对应的减伤属性；非抗性浆果返回 None。"""
    return BERRY_RESIST_TYPE.get(item or "")


def defender_item_berry_mult(item: Optional[str], move_type: str,
                            effectiveness: float, ripen: bool = False
                            ) -> tuple[float, str]:
    """防御方抗性浆果减伤：命中对应属性且（克制或一般系）时 ×0.5（成熟 ×0.25）。

    对照 champions.ts 1177-1187：招类型 == 浆果抗性类型，且 eff>1 或招为普通系。
    """
    resisted = berry_resist_type(item)
    if not resisted or move_type != resisted:
        return 1.0, ""
    if effectiveness > 1 or move_type == "Normal":
        mult = 0.25 if ripen else 0.5
        return mult, f"{item} {resisted}×{mult}"
    return 1.0, ""


def rivalry_mult(atk_gender: Optional[str], def_gender: Optional[str]
                 ) -> tuple[float, str]:
    """斗争心：同性 ×1.25，异性 ×0.75；任一方性别未知则 ×1.0。"""
    a = (atk_gender or "N").upper()
    d = (def_gender or "N").upper()
    if a == "N" or d == "N":
        return 1.0, ""
    if a == d:
        return 1.25, "斗争心 同性×1.25"
    return 0.75, "斗争心 异性×0.75"


def analytic_mult(ability_on: Optional[bool], atk_spe: float, def_spe: float
                   ) -> tuple[float, str]:
    """分析：后手（速度不高于守方）时 ×1.3。

    ability_on=None 按速度自动判定；True 强制发动；False 强制不发动。
    对照 champions.ts 532 / 775-776：turnOrder = 攻方速度 > 守方速度 ? 'first' : 'last'。
    """
    if ability_on is False:
        return 1.0, ""
    if ability_on is True:
        return 1.3, "分析 后手×1.3"
    if atk_spe <= def_spe:
        return 1.3, "分析 后手×1.3"
    return 1.0, ""


def electro_ball_bp(atk_spe: float, def_spe: float) -> int:
    """电球：r = 攻/守速度比；守方速度 0 时 40。"""
    if def_spe == 0:
        return 40
    r = math.floor(atk_spe / def_spe)
    return 150 if r >= 4 else 120 if r >= 3 else 80 if r >= 2 else 60 if r >= 1 else 40


def gyro_ball_bp(atk_spe: float, def_spe: float) -> int:
    """螺旋球：min(150, ⌊25×守/攻⌋+1)；攻方速度 0 时 1。"""
    if atk_spe == 0:
        return 1
    return min(150, math.floor(25 * def_spe / atk_spe) + 1)
