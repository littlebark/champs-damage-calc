"""数据加载层：Showdown 基础数据 + Champions 覆盖层合并。

基础数据来源（已下载到 data/raw）：
- pokedex.json   种族值、属性、特性（主系列 1~9 世代）
- moves.json     招式属性、分类、威力、命中、优先度
- items.ts / abilities.ts / learnsets.ts / typechart.ts  道具/特性/可学招式/相性表

Champions 与主系列有差异（部分招式威力/属性调整、可学招式受限、新增 Mega 等），
差异放在 data/champions/ 覆盖层 JSON 中，查询时优先取覆盖层。
"""
from __future__ import annotations

import json
from pathlib import Path
from typing import Optional

from .models import Category, Move

STAT_MAP = {"hp": "hp", "atk": "atk", "def": "def", "spa": "spa", "spd": "spd", "spe": "spe"}


class Dex:
    def __init__(self, raw_dir: Path, override_path: Optional[Path] = None):
        self.raw_dir = raw_dir
        self._pokedex = self._load_json(raw_dir / "pokedex.json")
        self._moves = self._load_json(raw_dir / "moves.json")
        self._overrides = self._load_json(override_path) if override_path and override_path.exists() else {}

    @staticmethod
    def _load_json(path: Path) -> dict:
        if not path.exists():
            return {}
        return json.loads(path.read_text(encoding="utf-8"))

    # ---- 宝可梦 ----
    def get_species(self, name: str) -> Optional[dict]:
        ov = self._overrides.get("species", {})
        if name in ov:
            return ov[name]
        # 按英文显示名或 slug 匹配
        key = name.lower().replace(" ", "").replace("-", "")
        for slug, entry in self._pokedex.items():
            if slug.lower() == key or entry.get("name", "").lower() == name.lower():
                return entry
        return None

    def base_stats(self, name: str) -> Optional[dict]:
        entry = self.get_species(name)
        if not entry:
            return None
        return dict(entry["baseStats"])

    # ---- 招式 ----
    def get_move(self, name: str) -> Optional[Move]:
        ov = self._overrides.get("moves", {})
        if name in ov:
            o = ov[name]
            return Move(
                name=name,
                type=o["type"],
                category=Category(o.get("category", "Special")),
                base_power=o.get("basePower", 0),
                accuracy=o.get("accuracy", True),
                priority=o.get("priority", 0),
                flags=o.get("flags", {}),
            )
        key = name.lower().replace(" ", "").replace("-", "")
        for slug, entry in self._moves.items():
            if slug.lower() == key or entry.get("name", "").lower() == name.lower():
                return Move(
                    name=entry["name"],
                    type=entry["type"],
                    category=Category(entry["category"]),
                    base_power=entry.get("basePower", 0),
                    accuracy=entry.get("accuracy", True),
                    priority=entry.get("priority", 0),
                    flags=entry.get("flags", {}),
                )
        return None
