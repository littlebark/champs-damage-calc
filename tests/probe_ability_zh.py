import sys, io
from pathlib import Path
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8")
ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT))
from src.catalog import Catalog

c = Catalog(ROOT)
ids = ["Punk Rock", "Motor Drive", "Strong Jaw", "Sharpness", "Fur Coat", "Fluffy",
       "Aura Guard", "Grass Pelt", "Purifying Salt", "Fire Mane", "Technician",
       "Pure Power", "Eelevate", "Storm Drain", "Galvanize", "Iron Fist",
       "Tough Claws", "Multiscale", "Huge Power", "Hustle", "Guts", "Solar Power"]
for a in ids:
    print(f"{a:18} -> {c._zh_name('ability', a)}")
print("Air Balloon ->", c._zh_name("item", "Air Balloon"))
