import sys, io, json
from pathlib import Path
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8")
ROOT = Path(r"C:\Users\bark\Doubao\chats\2026-11-13\new-chat\champs-damage-calc")
sys.path.insert(0, str(ROOT))
from src.catalog import Catalog

CAT = Catalog(ROOT)
for q in ["河马兽", "Hippowdon", "Primarina", "西狮海壬"]:
    e = CAT.get_species(q)
    if e:
        print("物种", q, "->", e.showdown_id, e.zh_name, e.types, "特性", e.abilities)
    else:
        print("物种未找到:", q)

prim = CAT.get_species("Primarina")
if prim:
    ms = CAT.species_moves(prim.showdown_id)
    hit = [m for m in ms if "Noise" in m.english_name or "Noise" in (m.zh_name or "")]
    print("精神噪音类招式:", [(m.english_name, m.zh_name, m.type, m.category, m.base_power, m.flags) for m in hit])
