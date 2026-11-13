"""Champions 专属数据层（来自 championsbattledata.com 公开 index）。

index.json 每条宝可梦包含：
- showdownId/showdownName：关联 Showdown 主系列底座；
- learnableMoveNames：冠军可学招式全集（选宝可梦后招式下拉的来源）；
- summary.forms：形态列表（Base / Mega / Mega Z / 地区形态 / 性别 / 其他形态），
  每形态给出属性、特性、体重、默认能力值（0 能力点、平衡性格、50 级）；
- summary.battleCurrent / battleSummary：按赛季、单双打的使用率配招推荐。

注意：forms 里的能力值是「默认能力值」，不是种族值。
- HP 种族值 = 默认 HP - 75
- 其他种族值 = 默认能力值 - 20（平衡性格修正 1.0）
底座 pokedex 缺失的冠军原创形态（新 Mega / Mega Z），用反推种族值补齐。
"""
from __future__ import annotations

import json
from dataclasses import dataclass, field
from pathlib import Path
from typing import Optional


@dataclass
class ChampionForm:
    name: str
    kind: str
    types: list[str]
    abilities: list[str]
    weight: Optional[float]
    base_stats: dict  # 反推后的种族值
    default_stats: dict  # 默认能力值（0 点、平衡性格）


@dataclass
class ChampionPokemon:
    slug: str
    name: str
    battle_name: Optional[str]
    showdown_id: Optional[str]
    showdown_name: Optional[str]
    forms: list[ChampionForm]
    learnset: list[str]
    raw: dict = field(default_factory=dict)

    @property
    def primary(self) -> ChampionForm:
        return self.forms[0]


def _reverse_base_stats(form: dict) -> dict:
    """从默认能力值反推种族值（冠军公式的逆运算，平衡性格）。"""
    return {
        "hp": form["hp"] - 75,
        "atk": form["attack"] - 20,
        "def": form["defense"] - 20,
        "spa": form["sp_attack"] - 20,
        "spd": form["sp_defense"] - 20,
        "spe": form["speed"] - 20,
    }


def _parse_form(form: dict) -> ChampionForm:
    abilities = [a.strip() for a in (form.get("abilities") or "").split("|") if a.strip()]
    return ChampionForm(
        name=form["form_name"],
        kind=form.get("form_kind", "Base"),
        types=form.get("types", []),
        abilities=abilities,
        weight=form.get("weight"),
        base_stats=_reverse_base_stats(form),
        default_stats={
            "hp": form["hp"], "atk": form["attack"], "def": form["defense"],
            "spa": form["sp_attack"], "spd": form["sp_defense"], "spe": form["speed"],
        },
    )


class ChampionsIndex:
    def __init__(self, index_path: Path):
        data = json.loads(Path(index_path).read_text(encoding="utf-8"))
        self.raw = data
        self.seasons: list[str] = data.get("seasons", [])
        self.data_version: Optional[str] = data.get("dataVersion")
        self.generated_at: Optional[str] = data.get("generatedAt")
        self._by_slug: dict[str, ChampionPokemon] = {}
        self._by_name: dict[str, ChampionPokemon] = {}
        for rec in data.get("pokemon", []):
            forms = [_parse_form(f) for f in rec.get("summary", {}).get("forms", [])]
            cp = ChampionPokemon(
                slug=rec["slug"],
                name=rec["name"],
                battle_name=rec.get("battleName"),
                showdown_id=rec.get("showdownId"),
                showdown_name=rec.get("showdownName"),
                forms=forms,
                learnset=rec.get("learnableMoveNames") or [],
                raw=rec,
            )
            self._by_slug[rec["slug"].lower()] = cp
            self._by_name[rec["name"].lower()] = cp

    def get(self, name_or_slug: str) -> Optional[ChampionPokemon]:
        key = name_or_slug.lower().replace(" ", "-")
        return self._by_slug.get(key) or self._by_name.get(name_or_slug.lower())

    def list_names(self) -> list[str]:
        return [cp.name for cp in self._by_name.values()]

    def learnset(self, name_or_slug: str) -> list[str]:
        cp = self.get(name_or_slug)
        return cp.learnset if cp else []

    def usage(self, name_or_slug: str, season: str = "Current", fmt: str = "Doubles") -> Optional[dict]:
        """取某赛季/格式的使用率配招推荐（Top 招式/道具/队友/性格/努力/特性）。"""
        cp = self.get(name_or_slug)
        if not cp:
            return None
        bc = cp.raw.get("summary", {}).get("battleCurrent", {})
        return bc.get(season, {}).get(fmt)
