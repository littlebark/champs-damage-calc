"""App 骨架冒烟：全量列表接口字段 + 详情接口字段完整性。"""
import json
import urllib.request

BASE = "http://127.0.0.1:8000"


def get(path):
    with urllib.request.urlopen(BASE + path) as r:
        return json.loads(r.read().decode("utf-8"))


all_data = get("/api/species/all")
results = all_data["results"]
print(f"全量物种数: {len(results)}")
first = results[0]
print("列表字段:", sorted(first.keys()))
print("首条:", first.get("zh"), "/", first.get("en"), first.get("typesZh"),
      "种族值", first.get("baseStats"), "特性", [a["zh"] for a in first.get("abilities", [])])

# 挑一只已知物种核对详情
prim = next(e for e in results if e["en"] == "Primarina")
detail = get("/api/species/" + prim["key"])
print("\n详情字段:", sorted(detail.keys()))
print("名称:", detail["zh"], "/", detail["en"], "属性:", detail["typesZh"])
print("种族值:", detail["baseStats"])
print("特性:", [a["zh"] for a in detail["abilities"]])
moves = detail["learnset"]
print("招式数:", len(moves))
for m in moves[:5]:
    print("  ", m.get("zh"), m.get("typeZh"), m.get("categoryZh"), "威力", m.get("power"), "优先", m.get("priority"))

# 断言关键结构齐全
assert detail["baseStats"] and all(k in detail["baseStats"] for k in ("hp", "atk", "def", "spa", "spd", "spe"))
assert detail["abilities"] and isinstance(detail["learnset"], list)
if moves:
    m = moves[0]
    assert all(k in m for k in ("en", "type", "category", "power")), m
print("\n结构校验通过。")
