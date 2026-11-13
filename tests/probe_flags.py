import sys, io, json, collections
from pathlib import Path
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8")
ROOT = Path(r"C:\Users\bark\Doubao\chats\2026-11-13\new-chat\champs-damage-calc")
data = json.loads((ROOT / "data" / "raw" / "moves.json").read_text(encoding="utf-8"))
flags = collections.Counter()
sample = {}
for k, m in data.items():
    f = m.get("flags") or {}
    for key in f:
        flags[key] += 1
        sample.setdefault(key, (k, m.get("name", "")))
print("招式总数:", len(data))
for key, cnt in flags.most_common():
    print(f"{key:14} {cnt:4}  例: {sample[key][0]}")
# 找气象球
for k, m in data.items():
    if "weather" in k.lower() or m.get("name", "").lower() == "weather ball":
        print("气象球条目:", k, json.dumps(m, ensure_ascii=False)[:300])
