"""临时冒烟：API 层面对比 无道具 / 生命宝珠 / 能力等级 的伤害区间。"""
import json
import urllib.request

BASE = "http://127.0.0.1:8000"


def call(attacker, defender, move="Moonblast", extra_atk=None, extra_def=None):
    payload = {
        "attacker": {
            "species": attacker, "nature": "内敛",
            "stat_points": {"hp": 0, "atk": 0, "def": 0, "spa": 32, "spd": 0, "spe": 0},
            "stat_stage": {"hp": 0, "atk": 0, "def": 0, "spa": 0, "spd": 0, "spe": 0},
            "ability": None, "item": None, "status": None,
        },
        "defender": {
            "species": defender, "nature": "坦率",
            "stat_points": {"hp": 31, "atk": 0, "def": 0, "spa": 0, "spd": 0, "spe": 0},
            "stat_stage": {"hp": 0, "atk": 0, "def": 0, "spa": 0, "spd": 0, "spe": 0},
            "ability": None, "item": None, "status": None,
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
    print(f"{tag:28} {r['min']}~{r['max']}  能力{d['attacker'].get('battleAtk')}  OHKO {r['ko']['ohko_rolls']}/16  修正: {r['breakdown']}")


show("基础", call("Primarina", "Garchomp"))
show("生命宝珠", call("Primarina", "Garchomp", extra_atk={"item": "Life Orb"}))
show("特攻 +1 级", call("Primarina", "Garchomp",
      extra_atk={"stat_stage": {"hp": 0, "atk": 0, "def": 0, "spa": 1, "spd": 0, "spe": 0}}))
show("防御 +1 级", call("Primarina", "Garchomp",
      extra_def={"stat_stage": {"hp": 0, "atk": 0, "def": 0, "spa": 0, "spd": 1, "spe": 0}}))
show("特性 适应力", call("Primarina", "Garchomp", extra_atk={"ability": "Adaptability"}))
show("湿润之声 精神噪音→河马兽", call("Primarina", "Hippowdon", move="Psychic Noise",
      extra_atk={"ability": "Liquid Voice"}))
show("激流 精神噪音→河马兽(对照)", call("Primarina", "Hippowdon", move="Psychic Noise",
      extra_atk={"ability": "Torrent"}))
show("飘浮 免疫地震", call("Garchomp", "Hippowdon", move="Earthquake",
      extra_def={"ability": "Levitate"}))
