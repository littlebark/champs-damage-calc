"""能力值推算（Pokémon Champions 规则）。

公式（神奇宝贝百科 / Bulbapedia 核对，与截图数值逐一吻合）：
    HP 能力值   = 种族值 + 能力点数 + 75
    其他能力值  = ⌊(种族值 + 能力点数 + 20) × 能力调整⌋
    能力调整：性格（或薄荷）提升项 ×1.1，下降项 ×0.9，其余 ×1.0

- 能力点数单项 0~32，六维合计上限 66；
- 个体值在 Champions 中固定按 31 处理，且不进入上述公式；
- 所有对战按 50 级计算。
"""
from __future__ import annotations

import math
from typing import Dict

from .models import PokemonInput, StatBlock

STAT_KEYS = ("hp", "atk", "def", "spa", "spd", "spe")

# 性格 -> (提升项, 下降项)。平衡性格两侧均为 None。
# 依据 52poke 性格矩阵整理。
NATURES: Dict[str, tuple[str | None, str | None]] = {
    # 攻击↑
    "固执": ("atk", "spa"),
    "怕寂寞": ("atk", "def"),
    "顽皮": ("atk", "spd"),
    "勇敢": ("atk", "spe"),
    # 防御↑
    "大胆": ("def", "atk"),
    "淘气": ("def", "spa"),
    "乐天": ("def", "spd"),
    "悠闲": ("def", "spe"),
    # 特攻↑
    "内敛": ("spa", "atk"),
    "慢吞吞": ("spa", "def"),
    "马虎": ("spa", "spd"),
    "冷静": ("spa", "spe"),
    # 特防↑
    "温和": ("spd", "atk"),
    "温顺": ("spd", "def"),
    "慎重": ("spd", "spa"),
    "自大": ("spd", "spe"),
    # 速度↑
    "胆小": ("spe", "atk"),
    "急躁": ("spe", "def"),
    "爽朗": ("spe", "spa"),
    "天真": ("spe", "spd"),
    # 平衡（不修正任何项）
    "勤奋": (None, None),
    "坦率": (None, None),
    "害羞": (None, None),
    "浮躁": (None, None),
    "认真": (None, None),
}


def nature_modifiers(nature: str) -> Dict[str, float]:
    """返回六维性格系数：提升 1.1 / 下降 0.9 / 其余 1.0。"""
    mods = {k: 1.0 for k in STAT_KEYS}
    up, down = NATURES.get(nature, (None, None))
    if up:
        mods[up] = 1.1
    if down:
        mods[down] = 0.9
    return mods


def calc_stats(p: PokemonInput) -> StatBlock:
    """按 Champions 公式计算六项能力值（HP 不受性格修正）。"""
    mods = nature_modifiers(p.nature)
    sp = p.stat_points
    base = p.base_stats

    hp = base["hp"] + sp["hp"] + 75

    def other(key: str) -> int:
        return math.floor((base[key] + sp[key] + 20) * mods[key])

    return StatBlock(
        hp=hp,
        atk=other("atk"),
        def_=other("def"),
        spa=other("spa"),
        spd=other("spd"),
        spe=other("spe"),
    )


def stage_multiplier(stage: int) -> float:
    """能力等级倍率：stage ∈ [-6, +6]。

    正向：(2+stage)/2；负向：2/(2-stage)。
    例：+1 → 1.5，+2 → 2.0；-1 → 2/3，-2 → 0.5。
    暴击时攻击方负向阶段、防御方正向阶段被无视（在 damage 层处理）。
    """
    stage = max(-6, min(6, stage))
    if stage >= 0:
        return (2 + stage) / 2
    return 2 / (2 - stage)


def battle_stat(stat_value: int, stage: int, ignore_stage: bool = False) -> float:
    """对战中经过能力等级修正后的能力值。ignore_stage 用于暴击无视阶段。"""
    if ignore_stage:
        return float(stat_value)
    return stat_value * stage_multiplier(stage)
