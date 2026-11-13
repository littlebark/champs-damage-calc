import sys, io, json, re
from pathlib import Path
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8")
ROOT = Path(r"C:\Users\bark\Doubao\chats\2026-11-13\new-chat\champs-damage-calc")

moves = json.loads((ROOT / "data/raw/moves.json").read_text(encoding="utf-8"))
# secondaries / hasSheerForceBoost 样例
sec = [(k, m["name"]) for k, m in moves.items() if m.get("secondaries") or m.get("secondary")]
print("带 secondaries 的招数:", len(sec), " 例:", sec[:5])
hsf = [(k, m["name"]) for k, m in moves.items() if m.get("hasSheerForceBoost")]
print("hasSheerForceBoost:", hsf[:8])
# 看一条
for k, m in moves.items():
    if m.get("secondaries"):
        print("样例", k, json.dumps({"secondaries": m["secondaries"]}, ensure_ascii=False)[:300])
        break

items_ts = (ROOT / "data/raw/items.ts").read_text(encoding="utf-8")
# 切出每条道具块
blocks = re.split(r'\n  (?=\w+: \{)', items_ts)
for name in ["Chople Berry", "Babiri Berry", "Coba Berry", "Aguav Berry"]:
    for b in blocks:
        if f'name: "{name}"' in b:
            print("\n===", name, "===")
            print(b[:500])
            break
