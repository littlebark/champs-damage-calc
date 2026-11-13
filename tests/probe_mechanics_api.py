"""新机制 API 冒烟：斗争心 / 分析 / 浆果 / 电球 / 强行 的后端透传与倍率。"""
import json
import urllib.request

BASE = "http://127.0.0.1:8000"


def call(attacker, defender, move="Tackle", extra_atk=None, extra_def=None):
    payload = {
        "attacker": {
            "species": attacker, "nature": "坦率",
            "stat_points": {"hp": 0, "atk": 0, "def": 0, "spa": 0, "spd": 0, "spe": 0},
            "stat_stage": {"hp": 0, "atk": 0, "def": 0, "spa": 0, "spd": 0, "spe": 0},
            "ability": None, "item": None, "status": None, "gender": "N", "ability_on": None,
        },
        "defender": {
            "species": defender, "nature": "坦率",
            "stat_points": {"hp": 31, "atk": 0, "def": 0, "spa": 0, "spd": 0, "spe": 0},
            "stat_stage": {"hp": 0, "atk": 0, "def": 0, "spa": 0, "spd": 0, "spe": 0},
            "ability": None, "item": None, "status": None, "gender": "N", "ability_on": None,
        },
        "move": {"name": move},
        "field": {},
        "crit": False,
    }
    if extra_atk:
        payload["attacker"].update(extra_atk)
    if extra_def:
        payload["defender"].update(extra_def)
    req = urllib.request.Request(BASE + "/api/calculate",
                                 data=json.dumps(payload).encode("utf-8"),
                                 headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req) as r:
        return json.loads(r.read().decode("utf-8"))


def show(tag, d):
    r = d["result"]
    print(f"{tag:26} {r['min']}~{r['max']}  速度 {d['attacker'].get('battleSpe')}/{d['defender'].get('battleSpe')}  修正: {r['breakdown']}")


# 斗争心：同性 / 异性
show("斗争心 同性", call("Garchomp", "Hippowdon",
     extra_atk={"ability": "Rivalry", "gender": "M"}, extra_def={"gender": "M"}))
show("斗争心 异性", call("Garchomp", "Hippowdon",
     extra_atk={"ability": "Rivalry", "gender": "M"}, extra_def={"gender": "F"}))

# 分析：自动(慢速) / 强制发动 / 强制不发动
show("分析 慢速自动", call("Garchomp", "Hippowdon", move="Psychic",
     extra_atk={"ability": "Analytic", "stat_points": {"hp": 0, "atk": 0, "def": 0, "spa": 0, "spd": 0, "spe": 0}},
     extra_def={"stat_points": {"hp": 31, "atk": 0, "def": 0, "spa": 0, "spd": 0, "spe": 32}}))
show("分析 强制发动", call("Garchomp", "Hippowdon", move="Psychic",
     extra_atk={"ability": "Analytic", "ability_on": "on"}))
show("分析 强制不发动", call("Garchomp", "Hippowdon", move="Psychic",
     extra_atk={"ability": "Analytic", "ability_on": "off",
                "stat_points": {"hp": 0, "atk": 0, "def": 0, "spa": 0, "spd": 0, "spe": 0}},
     extra_def={"stat_points": {"hp": 31, "atk": 0, "def": 0, "spa": 0, "spd": 0, "spe": 32}}))

# 浆果：普通系招 + 普通抗性浆果
show("浆果 普通系减伤", call("Garchomp", "Hippowdon", move="Tackle",
     extra_def={"item": "Chilan Berry"}))

# 电球：速度比决定威力（防守方选非地面系，避免电系免疫）
show("电球 皮卡丘", call("Pikachu", "Starmie", move="Electro Ball"))

# 强行：带附加效果的招
show("强行 火牙", call("Garchomp", "Hippowdon", move="Fire Fang",
     extra_atk={"ability": "Sheer Force"}))
