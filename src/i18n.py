"""界面静态枚举的中文映射（天气 / 场地）。

属性（type）中文名来自 `zh-Hans.json`，由 `Catalog.type_zh` 提供；
本表只放不依赖数据文件的固定枚举。
"""
from __future__ import annotations

WEATHER_ZH: dict[str, str] = {
    "None": "无",
    "Sun": "晴天",
    "Rain": "雨天",
    "Sand": "沙暴",
    "Snow": "雪天",
}

TERRAIN_ZH: dict[str, str] = {
    "None": "无",
    "Electric": "电气场地",
    "Grassy": "青草场地",
    "Psychic": "精神场地",
    "Misty": "薄雾场地",
}

CATEGORY_ZH: dict[str, str] = {
    "Physical": "物理",
    "Special": "特殊",
    "Status": "变化",
}


def weather_zh(value: str) -> str:
    return WEATHER_ZH.get(value, value)


def terrain_zh(value: str) -> str:
    return TERRAIN_ZH.get(value, value)


def category_zh(value: str) -> str:
    return CATEGORY_ZH.get(value, value)
