"""属性相性表，解析自 Showdown data/typechart.ts。

damageTaken 编码（防御方类型视角，键为攻击招式属性）：
    0 = 普通 1×
    1 = 弱点 2×（防御方被该属性克制）
    2 = 抗性 0.5×
    3 = 免疫 0×
"""
from __future__ import annotations

import re
from pathlib import Path

_CODE_MULT = {0: 1.0, 1: 2.0, 2: 0.5, 3: 0.0}


def _parse_ts_chart(text: str) -> dict[str, dict[str, float]]:
    chart: dict[str, dict[str, float]] = {}
    # 顶层类型块：  bug: { ... damageTaken: { ... }, ... }
    for block in re.finditer(r"^\s*([a-zA-Z]+):\s*\{(.*?)\},?\s*(?=\n\t[a-zA-Z]+:|\n\})", text, re.S | re.M):
        def_type = block.group(1).lower()
        body = block.group(2)
        dt = re.search(r"damageTaken:\s*\{(.*?)\}", body, re.S)
        if not dt:
            continue
        row: dict[str, float] = {}
        for pair in re.finditer(r"([a-zA-Z]+):\s*([0-3])", dt.group(1)):
            atk_type = pair.group(1).lower()
            if atk_type == "prankster":  # 非属性条目
                continue
            row[atk_type] = _CODE_MULT[int(pair.group(2))]
        chart[def_type] = row
    return chart


class TypeChart:
    def __init__(self, chart: dict[str, dict[str, float]]):
        self._chart = chart

    @classmethod
    def from_file(cls, path: str | Path) -> "TypeChart":
        text = Path(path).read_text(encoding="utf-8")
        return cls(_parse_ts_chart(text))

    def effectiveness(self, move_type: str, defender_types: list[str]) -> float:
        """招式属性对防御方复合属性的总倍率（双属性相乘）。"""
        mult = 1.0
        mt = move_type.lower()
        for dt in defender_types:
            row = self._chart.get(dt.lower(), {})
            mult *= row.get(mt, 1.0)
        return mult
