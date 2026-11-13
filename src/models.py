"""数据模型：宝可梦输入、招式、场地条件、对战组合。

《Pokémon Champions》规则要点（与主系列不同）：
- 所有对战按 50 级计算；
- 个体值固定 31，不可改；
- 没有努力值(EV)，取而代之的是「能力点数 Stat Points」：单项 0~32，六维合计上限 66；
- 能力值公式见 stats.py。
"""
from __future__ import annotations

from dataclasses import dataclass, field
from enum import Enum
from typing import Optional


class Category(str, Enum):
    PHYSICAL = "Physical"
    SPECIAL = "Special"
    STATUS = "Status"


class Weather(str, Enum):
    NONE = "None"
    SUN = "Sun"
    RAIN = "Rain"
    SAND = "Sand"
    SNOW = "Snow"


class Terrain(str, Enum):
    NONE = "None"
    ELECTRIC = "Electric"
    GRASSY = "Grassy"
    PSYCHIC = "Psychic"
    MISTY = "Misty"


@dataclass
class StatBlock:
    hp: int = 0
    atk: int = 0
    def_: int = 0
    spa: int = 0
    spd: int = 0
    spe: int = 0

    def as_dict(self) -> dict:
        return {
            "hp": self.hp,
            "atk": self.atk,
            "def": self.def_,
            "spa": self.spa,
            "spd": self.spd,
            "spe": self.spe,
        }


@dataclass
class PokemonInput:
    """一方宝可梦的全部可配置输入。

    base_stats: 种族值 {"hp","atk","def","spa","spd","spe"}
    stat_points: 能力点数 0~32，六维合计 ≤66
    nature: 性格中文名（见 stats.NATURES），决定 ±10% 修正
    stat_stage: 对战中能力等级 -6~+6，默认 0
    ability / item: 特性名 / 道具名（第一轮以字符串占位，效果在 modifiers 里挂接）
    status: 状态，目前支持 "burn"（烧伤，物理伤害×0.5）
    is_grounded: 是否站在地面上（场地加成判定），默认 True
    current_hp: 当前 HP（计算 KO 概率时用，默认等于最大 HP）
    """

    species: str
    types: list[str]
    base_stats: dict
    stat_points: dict = field(default_factory=lambda: {k: 0 for k in ("hp", "atk", "def", "spa", "spd", "spe")})
    nature: str = "坦率"
    stat_stage: dict = field(default_factory=lambda: {k: 0 for k in ("hp", "atk", "def", "spa", "spd", "spe")})
    ability: Optional[str] = None
    item: Optional[str] = None
    status: Optional[str] = None
    is_grounded: bool = True
    current_hp: Optional[int] = None
    # 性别："M" 雄 / "F" 雌 / "N" 未知（斗争心判定）
    gender: Optional[str] = None
    # 特性手动触发覆盖：None=按规则自动判定，True=强制发动，False=强制不发动
    ability_on: Optional[bool] = None
    # 冠军覆盖层：种族值/属性若与主系列不同，由 dataset 合并后写入这里
    note: Optional[str] = None

    def __post_init__(self) -> None:
        defaults = {k: 0 for k in ("hp", "atk", "def", "spa", "spd", "spe")}
        self.stat_points = {**defaults, **(self.stat_points or {})}
        self.stat_stage = {**defaults, **(self.stat_stage or {})}


@dataclass
class Move:
    name: str
    type: str
    category: Category
    base_power: int = 0
    accuracy: object = True  # True = 必中
    priority: int = 0
    flags: dict = field(default_factory=dict)
    english_name: Optional[str] = None  # 稳定英文名，供内部规则匹配；name 为展示名
    has_secondary: bool = False  # 是否带附加效果（强行特性判定）


@dataclass
class Field:
    weather: Weather = Weather.NONE
    terrain: Terrain = Terrain.NONE
    doubles: bool = False
    spread: bool = False  # 双打击中多个目标 → ×0.75
    reflect: bool = False  # 反射壁（物理减伤）
    light_screen: bool = False  # 光墙（特殊减伤）
    aurora_veil: bool = False
    friend_guard: bool = False  # 友谊防守 ×0.75
    helping_hand: bool = False  # 帮助 ×1.5（攻击方同伴施加）
    gravity: bool = False


@dataclass
class Battle:
    attacker: PokemonInput
    defender: PokemonInput
    move: Move
    field: Field = field(default_factory=Field)
    level: int = 50
