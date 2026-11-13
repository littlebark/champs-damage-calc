import sys, io
from pathlib import Path
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8")
ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT))
from src import modifiers as M  # noqa: E402


def check(name, actual, expected):
    ok = actual == expected if isinstance(expected, str) else abs(actual - expected) < 1e-9
    print(f"[{'OK' if ok else 'FAIL'}] {name}: {actual} (期望 {expected})")
    if not ok:
        raise AssertionError(name)


# ---- 斗争心 ----
m, _ = M.rivalry_mult("M", "M"); check("斗争心 同性 ×1.25", m, 1.25)
m, _ = M.rivalry_mult("F", "F"); check("斗争心 同性(雌) ×1.25", m, 1.25)
m, _ = M.rivalry_mult("M", "F"); check("斗争心 异性 ×0.75", m, 0.75)
m, _ = M.rivalry_mult("M", "N"); check("斗争心 任一未知 ×1.0", m, 1.0)
m, _ = M.rivalry_mult(None, "F"); check("斗争心 空性别 ×1.0", m, 1.0)

# ---- 分析 ----
m, _ = M.analytic_mult(None, 50, 100); check("分析 慢速自动 ×1.3", m, 1.3)
m, _ = M.analytic_mult(None, 100, 50); check("分析 快速自动 ×1.0", m, 1.0)
m, _ = M.analytic_mult(None, 100, 100); check("分析 同速(后手) ×1.3", m, 1.3)
m, _ = M.analytic_mult(True, 100, 50); check("分析 强制发动 ×1.3", m, 1.3)
m, _ = M.analytic_mult(False, 50, 100); check("分析 强制不发动 ×1.0", m, 1.0)

# ---- 电球 ----
check("电球 速度比≥4 →150", M.electro_ball_bp(400, 100), 150)
check("电球 速度比=3 →120", M.electro_ball_bp(300, 100), 120)
check("电球 速度比=2 →80", M.electro_ball_bp(200, 100), 80)
check("电球 速度比=1 →60", M.electro_ball_bp(100, 100), 60)
check("电球 速度比<1 →40", M.electro_ball_bp(50, 100), 40)
check("电球 守方速度0 →40", M.electro_ball_bp(100, 0), 40)

# ---- 螺旋球 ----
check("螺旋球 守快攻慢 高威力封顶150", M.gyro_ball_bp(10, 200), 150)
check("螺旋球 攻方速度0 →1", M.gyro_ball_bp(0, 100), 1)
check("螺旋球 同速 26", M.gyro_ball_bp(100, 100), 26)

# ---- 抗性浆果 ----
m, n = M.defender_item_berry_mult("Chople Berry", "Fighting", 2.0)
check("浆果 克制格斗 ×0.5", m, 0.5)
m, _ = M.defender_item_berry_mult("Chople Berry", "Fighting", 1.0)
check("浆果 一般格斗不触发 ×1.0", m, 1.0)
m, _ = M.defender_item_berry_mult("Chilan Berry", "Normal", 1.0)
check("浆果 普通系招触发 ×0.5", m, 0.5)
m, _ = M.defender_item_berry_mult("Chople Berry", "Fire", 2.0)
check("浆果 非对应属性 ×1.0", m, 1.0)
m, _ = M.defender_item_berry_mult("Chople Berry", "Fighting", 2.0, ripen=True)
check("浆果 成熟 ×0.25", m, 0.25)

# ---- 强行 ----
m, _ = M.attacker_ability_mult("Sheer Force", "Special", 80, "Fire", False, 1.0,
                                has_secondary=True)
check("强行 有附加效果 ×1.3", m, 1.3)
m, _ = M.attacker_ability_mult("Sheer Force", "Special", 80, "Fire", False, 1.0,
                                has_secondary=False)
check("强行 无附加效果 ×1.0", m, 1.0)

print("\n新机制纯函数校验通过。")
