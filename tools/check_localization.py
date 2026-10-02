#!/usr/bin/env python3
"""本地化完整性检查 —— 不需要启动游戏。

== 为什么必须有这个脚本 ==

语言键打错的表现是**玩家界面上出现一串原始键名**（`gui.cyxautofishingmachine.tooltip.xxx`），
它不崩溃、不进日志、不影响任何测试，只是很难看 —— 属于「上线之后才被玩家截图发现」的那一类。
逐个人肉核对两份 JSON 也靠不住：键有几十个，而且有两类键是**推导出来的**，根本不在源码里以字面量出现：

  * `PauseReason` → `status.<枚举名小写>` 与 `hint.<枚举名小写>`
  * `FishingPhase` → `phase.<枚举名小写>`
  * `WaterVerdict` → `water.<枚举名小写>` 与 `tooltip.water_<枚举名小写>`

所以这里做四件事：

1. **两份语言文件的键集合必须完全相同** —— 缺一边就会出现「中文有、英文没有」的单侧缺口；
2. **枚举推导出来的键必须齐全**（直接解析 Java 枚举的常量名，不是手抄一份清单）；
3. **源码里出现的每一个字面量键都必须存在** —— 覆盖上面推导不到的那些；
4. **占位符必须对齐**：中英两边的 `%s` 个数要一致，且不允许出现 `%d` / `%f`
   （`Component.translatable` 的参数全是对象，`%d` 会直接抛异常）。

用法：

    python tools/check_localization.py            # 检查，失败返回非 0
    python tools/check_localization.py --verbose  # 另外列出未被源码引用的键
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
LANG_DIR = ROOT / "src/main/resources/assets/cyxautofishingmachine/lang"
MAIN_JAVA = ROOT / "src/main/java"
CLIENT_JAVA = ROOT / "src/client/java"

# 由游戏引擎自己查表、不会以字面量出现在 Java 源码里的键前缀。
# 反向检查（「定义了但没人用」）会跳过这些。
ENGINE_PREFIXES = (
    "itemGroup.",
    "block.",
    "item.",
    "tag.item.",
    "tag.block.",
    "entity.",
    "container.",
)

# 需要被枚举常量名推导出键的枚举：(文件, 键前缀模板)
# {name} 会被替换成常量名的小写形式。
ENUM_KEY_TEMPLATES = {
    "PauseReason.java": [
        "gui.cyxautofishingmachine.status.{name}",
        "gui.cyxautofishingmachine.hint.{name}",
    ],
    "FishingPhase.java": [
        "gui.cyxautofishingmachine.phase.{name}",
    ],
    "WaterVerdict.java": [
        "gui.cyxautofishingmachine.water.{name}",
        "gui.cyxautofishingmachine.tooltip.water_{name}",
    ],
}

# 源码里可能有意义的字面量键。只有带命名空间的键才算，避免误抓示例文本。
LITERAL_KEY_PATTERN = re.compile(
    r'"(gui\.cyxautofishingmachine\.[a-z0-9_.]+'
    r'|tag\.(?:item|block)\.cyxautofishingmachine\.[a-z0-9_.]+'
    r'|block\.cyxautofishingmachine\.[a-z0-9_.]+'
    r'|itemGroup\.cyxautofishingmachine\.[a-z0-9_.]+)"'
)

# 枚举常量声明：行首缩进 + 全大写常量名 + 紧跟左括号（区分开 `LABEL_KEY = "..."`）。
ENUM_CONSTANT_PATTERN = re.compile(r"^\s*([A-Z][A-Z0-9_]{2,})\s*\(", re.MULTILINE)

BLOCK_COMMENT_PATTERN = re.compile(r"/\*.*?\*/", re.DOTALL)
LINE_COMMENT_PATTERN = re.compile(r"//[^\n]*")


class Report:
    def __init__(self) -> None:
        self.errors: list[str] = []
        self.warnings: list[str] = []

    def error(self, message: str) -> None:
        self.errors.append(message)

    def warn(self, message: str) -> None:
        self.warnings.append(message)


def load_lang(path: Path) -> dict[str, str]:
    with path.open(encoding="utf-8") as handle:
        data = json.load(handle)
    if not isinstance(data, dict):
        raise SystemExit(f"{path} 的顶层不是对象")
    for key, value in data.items():
        if not isinstance(value, str):
            raise SystemExit(f"{path} 里 {key} 的值不是字符串")
    return data


def strip_comments(source: str) -> str:
    return LINE_COMMENT_PATTERN.sub("", BLOCK_COMMENT_PATTERN.sub("", source))


def enum_constants(path: Path) -> list[str]:
    """解析枚举的常量名。只认 `NAME(` 这种带参数的写法 —— 本项目的枚举常量全带显式 id。"""
    source = strip_comments(path.read_text(encoding="utf-8"))
    return [match.group(1) for match in ENUM_CONSTANT_PATTERN.finditer(source)]


def placeholders(text: str) -> list[str]:
    return re.findall(r"%(?:\d+\$)?[a-zA-Z]", text)


def check_placeholder_shapes(report: Report, lang: dict[str, dict[str, str]]) -> None:
    reference = "en_us"
    for name, table in lang.items():
        for key, value in table.items():
            bad = [token for token in placeholders(value) if token != "%s"]
            if bad:
                report.error(
                    f"{name}:{key} 用了非 %s 的占位符 {sorted(set(bad))} —— "
                    "Component.translatable 的参数都是对象，%d/%f 会在运行时抛异常"
                )
    for key, english in lang[reference].items():
        expected = placeholders(english)
        for name, table in lang.items():
            if name == reference or key not in table:
                continue
            actual = placeholders(table[key])
            if len(actual) != len(expected):
                report.error(
                    f"{key} 的占位符个数对不上：{reference} 是 {len(expected)} 个 {expected}，"
                    f"{name} 是 {len(actual)} 个 {actual} —— 界面会出现拿不到参数的文本"
                )


def main() -> int:
    parser = argparse.ArgumentParser(description="检查本地化文件的完整性")
    parser.add_argument("--verbose", action="store_true", help="列出未被源码引用的键")
    args = parser.parse_args()

    report = Report()

    lang_files = sorted(LANG_DIR.glob("*.json"))
    if not lang_files:
        raise SystemExit(f"{LANG_DIR} 下没有任何语言文件")
    lang = {path.stem: load_lang(path) for path in lang_files}

    # 1. 两份文件的键集合必须一致
    names = sorted(lang)
    baseline = names[0]
    for name in names[1:]:
        missing_here = sorted(set(lang[baseline]) - set(lang[name]))
        missing_there = sorted(set(lang[name]) - set(lang[baseline]))
        if missing_here:
            report.error(f"{name} 缺少 {len(missing_here)} 个键（{baseline} 有）：{missing_here}")
        if missing_there:
            report.error(f"{baseline} 缺少 {len(missing_there)} 个键（{name} 有）：{missing_there}")

    # 2. 枚举推导键
    derived: set[str] = set()
    for filename, templates in ENUM_KEY_TEMPLATES.items():
        matches = list(MAIN_JAVA.rglob(filename))
        if not matches:
            report.error(f"找不到枚举源文件 {filename} —— 检查脚本的模板需要同步更新")
            continue
        constants = enum_constants(matches[0])
        if not constants:
            report.error(f"{filename} 里没解析出任何枚举常量 —— 解析规则可能已经失效")
            continue
        for constant in constants:
            lowered = constant.lower()
            for template in templates:
                derived.add(template.format(name=lowered))

    for key in sorted(derived):
        for name, table in lang.items():
            if key not in table:
                report.error(f"{name} 缺少枚举推导出来的键：{key}")

    # 3. 源码字面量键
    #
    # 有一类字面量是**前缀**：`translationKey()` 之类的方法用
    # `"gui.…status." + name().toLowerCase()` 拼键，源码里出现的只是前半截，
    # 它本身当然不是一个键。
    #
    # 判定规则因此是「是某个真实键的**严格前缀**也算通过」：
    # 前缀拼错（status → stauts）时，没有任何键以它开头，照样会被抓出来。
    # 不需要去静态求值那段字符串拼接 —— 拼接的结果是不是合法键，
    # 已经由上面「枚举推导键必须齐全」那一步独立保证了。
    literals: set[str] = set()
    java_files = sorted(MAIN_JAVA.rglob("*.java")) + sorted(CLIENT_JAVA.rglob("*.java"))
    for path in java_files:
        for match in LITERAL_KEY_PATTERN.finditer(path.read_text(encoding="utf-8")):
            literals.add(match.group(1))

    all_keys = set(lang[baseline])
    prefixes: set[str] = set()

    for key in sorted(literals):
        if key in all_keys:
            continue
        if any(existing.startswith(key) for existing in all_keys):
            prefixes.add(key)
            continue
        report.error(
            f"源码里引用的键 {key} 在 {baseline} 里不存在，也不是任何键的前缀 —— "
            "界面上会直接显示这串原始键名"
        )

    # 4. 占位符
    check_placeholder_shapes(report, lang)

    # 反向：定义了却没人引用（只提示，不失败 —— 引擎侧的键本来就不出现在 Java 里）
    if args.verbose:
        for name in names:
            for key in sorted(lang[name]):
                if key in literals or key in derived or key.startswith(ENGINE_PREFIXES):
                    continue
                if any(key.startswith(prefix) for prefix in prefixes):
                    continue
                report.warn(f"{name} 里定义了但没有源码引用的键：{key}")

    # ------------------------------------------------------------ 输出
    total_keys = len(lang[baseline])
    print(f"语言文件：{', '.join(names)}，{baseline} 共 {total_keys} 个键")
    print(f"枚举推导键 {len(derived)} 个，源码字面量键 {len(literals)} 个（其中 {len(prefixes)} 个是拼键前缀）")

    for message in report.warnings:
        print(f"[warn] {message}")
    for message in report.errors:
        print(f"[FAIL] {message}")

    if report.errors:
        print(f"\n本地化检查失败：{len(report.errors)} 项")
        return 1
    print("\n本地化检查通过")
    return 0


if __name__ == "__main__":
    sys.exit(main())
