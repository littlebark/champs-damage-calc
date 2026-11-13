"""统一数据接入层（Catalog）。

把多套来源合并成一个面向界面/计算内核的查询门面：

- **v18 客户端挖掘快照**（`data/ref/v18/`）：静态事实首选——形态种族值、属性、体重、
  特性编号、每形态可学招式编号、道具白名单、推荐配置、中英文名映射。
- **championsbattledata index**（`data/raw/champions-pokemon-index.json`）：赛季、使用率、
  v18 未覆盖条目时的可学池与反推种族值兜底。
- **Showdown 底座**（`data/raw/moves.json` / `pokedex.json`）：招式静态信息（属性/分类/威力/
  命中/优先度/PP）、主系列骨架兜底。

来源优先级与对账结论见 `DATA_SEASONS.md` 第 5 节、`reference/REUSE_NOTES.md`。
本层只做读取与合并，不计算能力值/伤害；那部分在 `stats.py` / `damage.py`。
"""
from __future__ import annotations

import json
import re
from dataclasses import dataclass, field
from pathlib import Path
from typing import Optional

from .models import PokemonInput

# 六维种族值键
STAT_KEYS = ("hp", "atk", "def", "spa", "spd", "spe")


def norm(s: Optional[str]) -> str:
    """归一化 ID：小写，去掉空格、连字符、逗号、撇号等分隔符，用于跨源匹配。"""
    if not s:
        return ""
    return re.sub(r"[\s\-_,.'’:]", "", s.lower())


# 对照表（zh-Hans.json）未收录、取主系列通用官方译名的兜底条目。
# 仅在对照表查不到时使用；新增条目应优先回补 zh-Hans.json，不要在此堆叠自译。
FALLBACK_ZH: dict[str, dict[str, str]] = {
    "ability": {
        "Storm Drain": "引水",
        "Galvanize": "电皮肤",
    },
    "item": {
        "Air Balloon": "气球",
    },
}


def _load_json(path: Path) -> dict | list:
    if not path.exists():
        return {}
    return json.loads(path.read_text(encoding="utf-8"))


@dataclass
class SpeciesEntry:
    """一只冠军形态的合并后静态信息。"""

    showdown_id: str  # 英文 showdownId（显示名，如 "Venusaur" / "Abomasnow-Mega"）
    key: str  # 归一化键
    zh_name: Optional[str]
    english_name: str
    types: list[str]
    base_stats: dict[str, int]
    abilities: list[str]
    weight: Optional[float]
    learnset: list[str]  # 可学招式英文显示名
    forms: list[str] = field(default_factory=list)
    source: str = "v18"  # v18 / index / base
    aliases: list[str] = field(default_factory=list)

    def display_name(self) -> str:
        return self.zh_name or self.english_name


@dataclass
class MoveInfo:
    english_name: str
    zh_name: Optional[str]
    type: str
    category: str
    base_power: int
    accuracy: object
    priority: int
    pp: Optional[int]
    flags: dict = field(default_factory=dict)
    has_secondary: bool = False

    def display_name(self) -> str:
        return self.zh_name or self.english_name


class Catalog:
    def __init__(self, root: Path):
        self.root = Path(root)
        self.raw_dir = self.root / "data/raw"
        self.ref_dir = self.root / "data/ref/v18"

        # 中文映射：entityType -> norm(showdownId) -> entry
        self._zh: dict[str, dict[str, dict]] = {}
        # 反查：中文/别名 norm -> (entityType, showdownId)
        self._zh_lookup: dict[tuple[str, str], tuple[str, str]] = {}

        self._species: dict[str, SpeciesEntry] = {}
        self._moves_base: dict[str, dict] = {}  # norm(显示名) -> 底座条目
        self._moves_meta: dict[str, dict] = {}  # norm(显示名) -> v18 moveMetadata
        self._zh_move: dict[str, dict] = {}
        self._items: list[str] = []
        self._abilities: list[str] = []
        self._index_by_key: dict[str, dict] = {}

        self._load_zh()
        self._load_base_moves()
        self._load_index()
        self._load_v18()

    # ---------- 加载 ----------

    def _load_zh(self) -> None:
        entries = _load_json(self.ref_dir / "zh-Hans.json")
        if isinstance(entries, dict):
            entries = entries.get("entries", [])
        for e in entries:
            et = e.get("entityType")
            sid = e.get("showdownId") or ""
            k = norm(sid)
            self._zh.setdefault(et, {})[k] = e
            zh_list = (e.get("localizedNames") or {}).get("zh-Hans") or []
            for alias in [sid, *zh_list, *(e.get("aliases") or [])]:
                if alias:
                    self._zh_lookup[(et, norm(alias))] = (et, sid)

    def _load_base_moves(self) -> None:
        moves = _load_json(self.raw_dir / "moves.json")
        if isinstance(moves, dict):
            for slug, entry in moves.items():
                name = entry.get("name", slug)
                self._moves_base[norm(name)] = entry
                self._moves_base.setdefault(norm(slug), entry)

        presets = _load_json(self.ref_dir / "champions-presets.json")
        meta = presets.get("moveMetadata", {}) if isinstance(presets, dict) else {}
        for slug, m in meta.items():
            self._moves_meta[norm(slug)] = m

        for k, e in self._zh.get("move", {}).items():
            self._zh_move[k] = e

    def _load_index(self) -> None:
        data = _load_json(self.raw_dir / "champions-pokemon-index.json")
        for rec in data.get("pokemon", []):
            sid = rec.get("showdownId") or rec.get("slug") or rec["name"]
            self._index_by_key[norm(sid)] = rec

    def _load_v18(self) -> None:
        roster = _load_json(self.ref_dir / "champions-roster.v18.json")
        moves = _load_json(self.ref_dir / "champions-moves.v18.json")
        items = _load_json(self.ref_dir / "champions-items.v18.json")

        ability_by_num = {a["number"]: a["showdownId"] for a in roster.get("abilities", [])}
        move_by_num = {m["number"]: m["showdownId"] for m in moves.get("entries", [])}
        moves_form_by_key = {
            norm(f["showdownId"]): f for f in moves.get("forms", [])
        }

        if isinstance(items.get("entries"), list):
            self._items = [i["showdownId"] for i in items["entries"]]
        self._abilities = sorted(ability_by_num.values())

        for form in roster.get("forms", []):
            sid = form["showdownId"]
            key = norm(sid)
            learnset = [
                move_by_num[n] for n in moves_form_by_key.get(key, {}).get("moveNumbers", [])
                if n in move_by_num
            ]
            abilities = list(dict.fromkeys(
                ability_by_num[n] for n in form.get("abilityNumbers", []) if n in ability_by_num
            ))
            entry = SpeciesEntry(
                showdown_id=sid,
                key=key,
                zh_name=self._zh_species_name(sid),
                english_name=sid,
                types=form.get("types", []),
                base_stats={k: form["baseStats"][k] for k in STAT_KEYS},
                abilities=abilities,
                weight=form.get("weightkg"),
                learnset=learnset,
                forms=[form.get("clientFormId", sid)],
                source="v18",
                aliases=self._aliases("species", sid),
            )
            self._species[key] = entry

        # index 兜底：补 v18 未覆盖的条目，并用 index 信息补 v18 缺的字段
        for key, rec in self._index_by_key.items():
            if key in self._species:
                self._augment_from_index(self._species[key], rec)
                continue
            self._species[key] = self._species_from_index(rec)

    def _species_from_index(self, rec: dict) -> SpeciesEntry:
        forms = rec.get("summary", {}).get("forms", [])
        base = next((f for f in forms if f.get("form_kind") == "Base"), forms[0] if forms else {})
        sid = rec.get("showdownId") or rec.get("slug") or rec["name"]
        base_stats = {
            "hp": base.get("hp", 0) - 75,
            "atk": base.get("attack", 0) - 20,
            "def": base.get("defense", 0) - 20,
            "spa": base.get("sp_attack", 0) - 20,
            "spd": base.get("sp_defense", 0) - 20,
            "spe": base.get("speed", 0) - 20,
        }
        abilities = list(dict.fromkeys(a.strip() for a in (base.get("abilities") or "").split("|") if a.strip()))
        return SpeciesEntry(
            showdown_id=sid,
            key=norm(sid),
            zh_name=self._zh_species_name(sid),
            english_name=rec["name"],
            types=base.get("types", []),
            base_stats=base_stats,
            abilities=abilities,
            weight=base.get("weight"),
            learnset=rec.get("learnableMoveNames", []),
            forms=[f.get("form_name", f.get("showdown_name", sid)) for f in forms],
            source="index",
            aliases=self._aliases("species", sid),
        )

    def _augment_from_index(self, entry: SpeciesEntry, rec: dict) -> None:
        if not entry.learnset:
            entry.learnset = rec.get("learnableMoveNames", [])
        if not entry.abilities:
            forms = rec.get("summary", {}).get("forms", [])
            base = next((f for f in forms if f.get("form_kind") == "Base"), forms[0] if forms else {})
            entry.abilities = [a.strip() for a in (base.get("abilities") or "").split("|") if a.strip()]
        if not entry.types and forms:
            entry.types = base.get("types", [])
        entry.forms = entry.forms or [f.get("form_name", sid) for f in forms]

    # ---------- 中文映射辅助 ----------

    def _zh_entry(self, entity_type: str, showdown_id: str) -> Optional[dict]:
        return self._zh.get(entity_type, {}).get(norm(showdown_id))

    def _zh_species_name(self, showdown_id: str) -> Optional[str]:
        e = self._zh_entry("species", showdown_id)
        if e:
            names = (e.get("localizedNames") or {}).get("zh-Hans") or []
            if names:
                return names[0]
        return None

    def _aliases(self, entity_type: str, showdown_id: str) -> list[str]:
        e = self._zh_entry(entity_type, showdown_id)
        return list(e.get("aliases", [])) if e else []

    def _resolve_showdown_id(self, entity_type: str, query: str) -> Optional[str]:
        k = norm(query)
        if (entity_type, k) in self._zh_lookup:
            return self._zh_lookup[(entity_type, k)][1]
        # 直接按 showdownId 归一化
        entries = self._zh.get(entity_type, {})
        if k in entries:
            return entries[k]["showdownId"]
        return None

    # ---------- 物种查询 ----------

    def species_list(self) -> list[SpeciesEntry]:
        return sorted(self._species.values(), key=lambda e: e.display_name())

    def get_species(self, query: str) -> Optional[SpeciesEntry]:
        if not query:
            return None
        k = norm(query)
        if k in self._species:
            return self._species[k]
        sid = self._resolve_showdown_id("species", query)
        if sid and norm(sid) in self._species:
            return self._species[norm(sid)]
        return None

    def search_species(self, query: str, limit: int = 20) -> list[SpeciesEntry]:
        q = norm(query)
        if not q:
            return self.species_list()[:limit]
        out: list[SpeciesEntry] = []
        for e in self._species.values():
            hay = [e.key, norm(e.english_name), norm(e.zh_name or ""), *[norm(a) for a in e.aliases]]
            if any(q in h for h in hay):
                out.append(e)
        out.sort(key=lambda e: (e.key != q, e.display_name()))
        return out[:limit]

    # ---------- 招式查询 ----------

    def get_move(self, query: str) -> Optional[MoveInfo]:
        if not query:
            return None
        k = norm(query)
        base = self._moves_base.get(k)
        meta = self._moves_meta.get(k)
        zh = self._zh_move.get(k)
        if not (base or meta or zh):
            sid = self._resolve_showdown_id("move", query)
            if sid:
                k = norm(sid)
                base = self._moves_base.get(k)
                meta = self._moves_meta.get(k)
                zh = self._zh_move.get(k)
        if not (base or meta or zh):
            return None

        english = (base or meta or zh).get("name") or (zh or {}).get("showdownId") or query
        zh_name = None
        if zh:
            names = (zh.get("localizedNames") or {}).get("zh-Hans") or []
            zh_name = names[0] if names else None
        return MoveInfo(
            english_name=english,
            zh_name=zh_name,
            type=(base or {}).get("type", "???"),
            category=(base or meta or {}).get("category", "Status"),
            base_power=(base or meta or {}).get("basePower") or 0,
            accuracy=(base or meta or {}).get("accuracy", True),
            priority=(base or meta or {}).get("priority", 0),
            pp=(base or meta or {}).get("pp") or (base or meta or {}).get("basePP"),
            flags=(base or {}).get("flags", {}) or {},
            has_secondary=bool((base or {}).get("secondaries") or (base or {}).get("secondary")
                                or (base or {}).get("hasSheerForceBoost")),
        )

    def species_moves(self, query: str) -> list[MoveInfo]:
        entry = self.get_species(query)
        if not entry:
            return []
        out: list[MoveInfo] = []
        for name in entry.learnset:
            mi = self.get_move(name)
            if mi:
                out.append(mi)
        return out

    # ---------- 道具 / 特性 / 性格 ----------

    def item_list(self) -> list[dict]:
        return [{"english": i, "zh": self._zh_name("item", i)} for i in self._items]

    def ability_list(self) -> list[dict]:
        return [{"english": a, "zh": self._zh_name("ability", a)} for a in self._abilities]

    def _zh_name(self, entity_type: str, showdown_id: str) -> Optional[str]:
        e = self._zh_entry(entity_type, showdown_id)
        if e:
            names = (e.get("localizedNames") or {}).get("zh-Hans") or []
            if names:
                return names[0]
        return FALLBACK_ZH.get(entity_type, {}).get(showdown_id)

    def type_zh(self, type_name: str) -> str:
        """属性中文名；未收录时返回原名。"""
        return self._zh_name("type", type_name) or type_name

    # ---------- 构造内核输入 ----------

    def to_pokemon_input(
        self,
        entry: SpeciesEntry,
        nature: str = "坦率",
        stat_points: Optional[dict] = None,
        ability: Optional[str] = None,
        item: Optional[str] = None,
        status: Optional[str] = None,
        stat_stage: Optional[dict] = None,
        current_hp: Optional[int] = None,
        gender: Optional[str] = None,
        ability_on: Optional[bool] = None,
    ) -> PokemonInput:
        """把 Catalog 物种条目 + 用户输入组装成内核 `PokemonInput`。

        ability/item 传英文 showdownId（内部标识）；界面展示用 `_zh_name` 转中文。
        """
        return PokemonInput(
            species=entry.showdown_id,
            types=list(entry.types),
            base_stats=dict(entry.base_stats),
            stat_points=stat_points,
            nature=nature,
            stat_stage=stat_stage,
            ability=ability,
            item=item,
            status=status,
            current_hp=current_hp,
            gender=gender,
            ability_on=ability_on,
        )
