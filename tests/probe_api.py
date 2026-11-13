import json
import urllib.request
import urllib.parse

BASE = "http://127.0.0.1:8000"

def get(path):
    with urllib.request.urlopen(BASE + path, timeout=5) as r:
        return r.read().decode("utf-8"), r.status

def post(path, payload):
    req = urllib.request.Request(BASE + path, data=json.dumps(payload).encode("utf-8"),
                                 headers={"Content-Type": "application/json"}, method="POST")
    with urllib.request.urlopen(req, timeout=5) as r:
        return json.loads(r.read().decode("utf-8"))

html, status = get("/")
print("首页状态:", status, " 含标题:", "伤害计算器" in html, " 字节:", len(html))

meta = json.loads(get("/api/meta")[0])
print("meta natures:", len(meta["natures"]))

srch = json.loads(get("/api/species?q=" + urllib.parse.quote("西狮海壬"))[0])
prim = json.loads(get("/api/species/" + srch["results"][0]["key"])[0])
print("detail:", prim["zh"], "可学", len(prim["learnset"]),
      " 招式样例:", [(m["zh"], m["power"]) for m in prim["learnset"][:3]])

calc = post("/api/calculate", {
    "attacker": {"species": "Primarina", "nature": "内敛", "stat_points": {"spa": 32}},
    "defender": {"species": "Garchomp", "nature": "坦率", "stat_points": {"hp": 31}},
    "move": {"name": "Moonblast"},
    "field": {},
    "crit": False,
})
r = calc["result"]
print("result:", r["min"], "~", r["max"], "ko:", r["ko"], "pct:", r["pct_min"], r["pct_max"])
assert (r["min"], r["max"]) == (200, 236)
assert r["ko"] == {"ohko_rolls": 9, "ohko_pct": 56.2}
print("API 校验通过")
