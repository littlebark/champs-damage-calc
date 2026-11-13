"""从 champions index 派生赛季 manifest.json。

用法： python scripts/build_manifest.py
读取 data/raw/champions-pokemon-index.json 的顶层 seasons / dataVersion / generatedAt，
生成 data/champions/manifest.json。赛季按 index 顺序登记，相邻赛季互为继承关系。
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT))

RAW = ROOT / "data/raw/champions-pokemon-index.json"
OUT = ROOT / "data/champions/manifest.json"


def main() -> None:
    index = json.loads(RAW.read_text(encoding="utf-8"))
    seasons = index.get("seasons", [])

    # index seasons 顺序为 新→旧（Current, M7..M1）；新赛季的规则基干继承更早一赛季。
    entries = []
    for i, sid in enumerate(seasons):
        live = sid == index.get("defaultSeason", "Current")
        parent = seasons[i + 1] if i + 1 < len(seasons) else None
        entries.append({
            "id": sid,
            "label": sid,
            "regulation": None,  # 公告确认后回填 M-A / M-B / M-C
            "live": live,
            "inheritsFrom": parent,
            "sourceDataVersion": index.get("dataVersion"),
            "fetchedAt": None,
            "roster": f"species/{sid}.json",
            "moves": f"moves-overrides/{sid}.json",
            "items": f"items/{sid}.json",
            "abilities": f"abilities/{sid}.json",
            "usageDir": f"usage/{sid}/",
        })

    manifest = {
        "defaultSeason": index.get("defaultSeason", "Current"),
        "base": {
            "showdownSnapshot": None,
            "championsIndexDataVersion": index.get("dataVersion"),
            "championsIndexGeneratedAt": index.get("generatedAt"),
        },
        "seasons": entries,
    }

    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"已生成 {OUT}，赛季 {len(entries)} 个：{[e['id'] for e in entries]}")


if __name__ == "__main__":
    main()
