"""把旧参考工程复制为本项目 android 工作副本。

排除：.git / .android-tools / node_modules / 构建产物 / gradle 缓存 / 空 external。
保留源码、资源、tools、config、third_party 与根配置，保持旧仓库根布局
（build.gradle 用 ../src ../tools ../config ../third_party 相对引用）。
"""
import shutil
from pathlib import Path

REF = Path(r"C:\Users\bark\Doubao\chats\2026-11-13\new-chat\champs-damage-calc\reference\Pokemon-Champions-dmg_cal")
DST = Path(r"C:\Users\bark\Doubao\chats\2026-11-13\new-chat\champs-damage-calc\android")

IGNORE_DIRS = {".git", ".android-tools", "node_modules", "build", ".gradle", ".kotlin", "external", "captures"}
IGNORE_FILES = {"local.properties"}


def ignore(directory: str, names: list[str]):
    out = set()
    for n in names:
        p = Path(directory) / n
        if n in IGNORE_DIRS and p.is_dir():
            out.add(n)
        elif n in IGNORE_FILES:
            out.add(n)
    return out


def main():
    if DST.exists():
        raise SystemExit(f"target already exists: {DST}")
    DST.mkdir(parents=True)

    for child in REF.iterdir():
        if child.name in IGNORE_DIRS:
            continue
        if child.is_dir():
            shutil.copytree(child, DST / child.name, ignore=ignore)
        else:
            if child.name in IGNORE_FILES:
                continue
            shutil.copy2(child, DST / child.name)

    copied = sum(1 for _ in DST.rglob("*") if _.is_file())
    mb = sum(f.stat().st_size for f in DST.rglob("*") if f.is_file()) / 1024 / 1024
    print(f"copied files: {copied}, size: {mb:.1f} MB")


if __name__ == "__main__":
    main()
