"""baseline 构建前：核对 build.gradle 硬校验所需资源的数量与键结构。"""
import json
from pathlib import Path

ref = Path(r"C:\Users\bark\Doubao\chats\2026-11-13\new-chat\champs-damage-calc\reference\Pokemon-Champions-dmg_cal")

em = ref / "tools/team-code-resolver/data/champions-entity-map.v18.json"
data = json.loads(em.read_text(encoding="utf-8"))
print("entity-map keys:", list(data.keys()))
print("schemaVersion:", data.get("schemaVersion"), "masterDataVersion:", data.get("masterDataVersion"))
for k in ("species", "moves", "abilities", "items"):
    v = data.get(k)
    print(f"  {k}: {len(v) if isinstance(v, dict) else v}")

for rel in [
    "config/android-release-variant.txt",
    "src/data/recognition/android/team-preview-templates-v2.bin",
    "src/data/recognition/android/team-preview-templates-v2.json",
    "src/data/recognition/team-preview.safe-zone-roi.zh-Hans.v2.json",
    "src/data/damage/champions-presets.json",
    "src/data/localization/zh-Hans.json",
    "THIRD_PARTY_NOTICES.md",
]:
    p = ref / rel
    print(("OK  " if p.exists() else "MISS"), rel)

variant = (ref / "config/android-release-variant.txt").read_text(encoding="utf-8").strip()
print("release variant:", variant)
