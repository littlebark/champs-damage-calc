"""本地 Web 计算器原型后端（Python 标准库，无第三方依赖）。

桥接统一接入层 `Catalog` 与伤害内核 `damage.calculate`，向前端提供：
  GET  /api/meta                 性格 / 天气 / 场地等选项
  GET  /api/species?q=           按中文/英文/别名搜索宝可梦
  GET  /api/species/<key>        物种详情：种族值/属性/特性/可学池
  POST /api/calculate            双方 + 招式 + 条件 → 伤害区间 / KO 概率

运行：
  python web/server.py
默认 http://127.0.0.1:8000
"""
from __future__ import annotations

import json
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Optional
from urllib.parse import urlparse, parse_qs

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT))

from src.catalog import Catalog  # noqa: E402
from src.models import Battle, Category, Field, Move, Weather, Terrain  # noqa: E402
from src.stats import calc_stats, battle_stat, NATURES  # noqa: E402
from src.typechart import TypeChart  # noqa: E402
from src.damage import calculate as calc_damage  # noqa: E402
from src import i18n  # noqa: E402

CAT = Catalog(ROOT)
CHART = TypeChart.from_file(ROOT / "data" / "raw" / "typechart.ts")
WEB_DIR = ROOT / "web"

WEATHERS = [w.value for w in Weather]
TERRAINS = [t.value for t in Terrain]


def _species_brief(entry) -> dict:
    return {
        "key": entry.key,
        "showdownId": entry.showdown_id,
        "zh": entry.zh_name,
        "en": entry.english_name,
        "types": entry.types,
        "typesZh": [CAT.type_zh(t) for t in entry.types],
    }


def species_summary(entry) -> dict:
    """列表卡片摘要：名 / 属性 / 种族值 / 特性（不含完整招式池）。"""
    return {
        **_species_brief(entry),
        "baseStats": entry.base_stats,
        "abilities": [
            {"en": a, "zh": CAT._zh_name("ability", a)} for a in entry.abilities
        ],
    }


def species_detail(entry) -> dict:
    return {
        **_species_brief(entry),
        "baseStats": entry.base_stats,
        "abilities": [
            {"en": a, "zh": CAT._zh_name("ability", a)} for a in entry.abilities
        ],
        "learnset": [
            {
                "en": m.english_name,
                "zh": m.zh_name,
                "type": m.type,
                "typeZh": CAT.type_zh(m.type),
                "category": m.category,
                "categoryZh": i18n.category_zh(m.category),
                "power": m.base_power,
                "priority": m.priority,
                "flags": m.flags,
            }
            for m in CAT.species_moves(entry.showdown_id)
        ],
    }


def _parse_ability_on(raw) -> Optional[bool]:
    """特性发动开关：None/缺省=自动，True/'on'=强制发动，False/'off'=强制不发动。"""
    if raw is None or raw == "":
        return None
    if isinstance(raw, bool):
        return raw
    if str(raw).lower() in ("on", "true", "1"):
        return True
    if str(raw).lower() in ("off", "false", "0"):
        return False
    return None


def _build_pokemon(side: dict):
    entry = CAT.get_species(side.get("species", ""))
    if not entry:
        raise ValueError(f"未找到宝可梦：{side.get('species')}")
    pk = CAT.to_pokemon_input(
        entry,
        nature=side.get("nature", "坦率"),
        stat_points=side.get("stat_points"),
        ability=side.get("ability"),
        item=side.get("item"),
        status=side.get("status"),
        stat_stage=side.get("stat_stage"),
        gender=side.get("gender") or None,
        ability_on=_parse_ability_on(side.get("ability_on")),
    )
    return pk, entry


def _field_from(data: dict) -> Field:
    return Field(
        weather=Weather(data.get("weather", "None")),
        terrain=Terrain(data.get("terrain", "None")),
        doubles=bool(data.get("doubles", False)),
        spread=bool(data.get("spread", False)),
        reflect=bool(data.get("reflect", False)),
        light_screen=bool(data.get("light_screen", False)),
        aurora_veil=bool(data.get("aurora_veil", False)),
        friend_guard=bool(data.get("friend_guard", False)),
        helping_hand=bool(data.get("helping_hand", False)),
        gravity=bool(data.get("gravity", False)),
    )


_TYPE_EN = ["Bug", "Dark", "Dragon", "Electric", "Fairy", "Fighting", "Fire",
            "Flying", "Ghost", "Grass", "Ground", "Ice", "Normal", "Poison",
            "Psychic", "Rock", "Steel", "Stellar", "Water"]


def _localize_notes(notes: list[str], atk_ab, def_ab, atk_item, def_item=None) -> list[str]:
    """把修正说明里的特性 / 道具 / 属性英文 id 替换为中文名。"""
    repl: dict[str, str] = {}
    for aid in (atk_ab, def_ab):
        if aid:
            zh = CAT._zh_name("ability", aid)
            if zh:
                repl[aid] = zh
    for item in (atk_item, def_item):
        if item:
            zh = CAT._zh_name("item", item)
            if zh:
                repl[item] = zh
    for t in _TYPE_EN:
        repl[t] = CAT.type_zh(t)
    out: list[str] = []
    for note in notes:
        for en in sorted(repl, key=len, reverse=True):
            zh = repl[en]
            if zh and zh != en:
                note = note.replace(en, zh)
        out.append(note)
    return out


def compute_damage(payload: dict) -> dict:
    atk_pk, atk_entry = _build_pokemon(payload["attacker"])
    dfn_pk, dfn_entry = _build_pokemon(payload["defender"])

    mi = CAT.get_move(payload["move"]["name"])
    if not mi:
        raise ValueError(f"未找到招式：{payload['move']['name']}")
    move = Move(
        name=mi.display_name(),
        type=mi.type,
        category=Category(mi.category),
        base_power=mi.base_power,
        accuracy=mi.accuracy,
        priority=mi.priority,
        flags=mi.flags,
        english_name=mi.english_name,
        has_secondary=mi.has_secondary,
    )

    field = _field_from(payload.get("field", {}))
    crit = bool(payload.get("crit", False))
    battle = Battle(attacker=atk_pk, defender=dfn_pk, move=move, field=field)
    res = calc_damage(battle, CHART, crit=crit)

    atk_stats = calc_stats(atk_pk).as_dict()
    dfn_stats = calc_stats(dfn_pk).as_dict()
    if move.category == Category.PHYSICAL:
        atk_battle = battle_stat(atk_stats["atk"], atk_pk.stat_stage["atk"])
    else:
        atk_battle = battle_stat(atk_stats["spa"], atk_pk.stat_stage["spa"])
    atk_battle_spe = battle_stat(atk_stats["spe"], atk_pk.stat_stage["spe"])
    dfn_battle_spe = battle_stat(dfn_stats["spe"], dfn_pk.stat_stage["spe"])
    ko = res.ko_chance(dfn_stats["hp"])
    return {
        "attacker": {"entry": _species_brief(atk_entry), "stats": atk_stats,
                     "battleAtk": round(atk_battle), "battleSpe": round(atk_battle_spe)},
        "defender": {"entry": _species_brief(dfn_entry), "stats": dfn_stats,
                     "battleSpe": round(dfn_battle_spe)},
        "move": {"zh": mi.zh_name, "en": mi.english_name, "type": mi.type,
                 "typeZh": CAT.type_zh(mi.type), "category": mi.category,
                 "categoryZh": i18n.category_zh(mi.category), "power": mi.base_power},
        "result": {
            "min": res.min_damage,
            "max": res.max_damage,
            "rolls": res.rolls,
            "effectiveness": res.effectiveness,
            "stab": res.stab,
            "effectiveType": res.effective_type,
            "effectiveTypeZh": CAT.type_zh(res.effective_type) if res.effective_type else "",
            "breakdown": _localize_notes(res.breakdown, atk_pk.ability, dfn_pk.ability,
                                         atk_pk.item, dfn_pk.item),
            "ko": ko,
            "hp": dfn_stats["hp"],
            "pct_min": round(res.min_damage / dfn_stats["hp"] * 100, 1) if dfn_stats["hp"] else 0,
            "pct_max": round(res.max_damage / dfn_stats["hp"] * 100, 1) if dfn_stats["hp"] else 0,
        },
    }


class Handler(BaseHTTPRequestHandler):
    def _send_json(self, obj: dict, status: int = 200) -> None:
        body = json.dumps(obj, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def _send_static(self, name: str) -> None:
        path = WEB_DIR / name
        if not path.exists():
            self._send_json({"error": "not found"}, 404)
            return
        body = path.read_bytes()
        self.send_response(200)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self) -> None:
        parsed = urlparse(self.path)
        if parsed.path in ("/", "/index.html"):
            self._send_static("index.html")
            return
        if parsed.path == "/api/meta":
            self._send_json({
                "natures": list(NATURES.keys()),
                "weathers": [{"value": w, "zh": i18n.weather_zh(w)} for w in WEATHERS],
                "terrains": [{"value": t, "zh": i18n.terrain_zh(t)} for t in TERRAINS],
            })
            return
        if parsed.path == "/api/items":
            self._send_json({"results": CAT.item_list()})
            return
        if parsed.path == "/api/species/all":
            self._send_json({"results": [species_summary(e) for e in CAT.species_list()]})
            return
        if parsed.path == "/api/species":
            q = parse_qs(parsed.query).get("q", [""])[0]
            results = [_species_brief(e) for e in CAT.search_species(q, limit=30)]
            self._send_json({"results": results})
            return
        if parsed.path.startswith("/api/species/"):
            key = parsed.path[len("/api/species/"):]
            entry = CAT.get_species(key)
            if entry:
                self._send_json(species_detail(entry))
            else:
                self._send_json({"error": "not found"}, 404)
            return
        self._send_json({"error": "not found"}, 404)

    def do_POST(self) -> None:
        if urlparse(self.path).path != "/api/calculate":
            self._send_json({"error": "not found"}, 404)
            return
        try:
            length = int(self.headers.get("Content-Length", 0))
            payload = json.loads(self.rfile.read(length).decode("utf-8"))
            self._send_json(compute_damage(payload))
        except ValueError as e:
            self._send_json({"error": str(e)}, 400)
        except Exception as e:  # noqa: BLE001
            self._send_json({"error": f"内部错误：{e}"}, 500)

    def log_message(self, fmt: str, *args) -> None:  # 静默默认访问日志
        pass


def main() -> None:
    port = 8000
    server = ThreadingHTTPServer(("127.0.0.1", port), Handler)
    print(f"伤害计算器原型：http://127.0.0.1:{port}")
    server.serve_forever()


if __name__ == "__main__":
    main()
