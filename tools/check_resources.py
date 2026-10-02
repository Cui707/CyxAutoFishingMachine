#!/usr/bin/env python3
"""资源静态接线检查 —— 不需要启动游戏。

== 为什么必须有这个脚本 ==

资源层的接线错误（blockstate 指到一个不存在的模型、模型引用一张没生成的贴图）
在游戏里的表现是**紫黑棋盘格**，日志里有一条 WARNING —— 但它不会让测试失败。
如果测试只验证「方块放得下、界面开得了」，一张贴图路径打错就能一路绿着发布出去。

静态检查在这里反而比游戏内检查更可靠：它直接回答
「blockstate 写的每个模型文件在不在」「模型引用的每张贴图在不在」，
不需要先搭出能让方块进入某个状态的场景。

覆盖四类问题：

1. `blockstates/*.json` 里每个变体指向的模型文件是否存在；
2. `models/**/*.json` 的 `parent`（仅本模组命名空间）与 `textures` 里的贴图是否存在；
3. `items/*.json`（26.3 的物品定义）指向的模型是否存在；
4. **变体完整性**：变体集合必须是它提到的属性值的完整组合，
   且布尔属性的两侧必须映射到不同的模型 ——
   后者是本项目的既定约定：`ACTIVE` 这个属性就是为「运行中」贴图加的，
   两侧指向同一个模型等于这个属性没起作用（Phase 6 之前正是这个状态）。
   将来若有属性确实不需要视觉区分，把那一段检查放宽即可。

贴图的**内容**是否正确（比如「运行中」版本灯是不是亮的）由
`tools/gen_active_texture.py` 的 `--check` 保证 —— 那里能拿到像素，
这里只管「文件存在、接线闭合」。

用法：

    python tools/check_resources.py
"""

from __future__ import annotations

import itertools
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
ASSETS = ROOT / "src/main/resources/assets"
NAMESPACE = "cyxautofishingmachine"

# 变体键里 "facing=north,active=true" 的拆分模式
VARIANT_PART_PATTERN = r"([a-z_]+)=([a-z0-9_\-]+)"


class Report:
    def __init__(self) -> None:
        self.errors: list[str] = []
        self.checked = 0

    def error(self, message: str) -> None:
        self.errors.append(message)


def parse_location(value, source: Path) -> str | None:
    """`namespace:path` → path（仅本模组命名空间）。不是资源位置就返回 None。"""
    if not isinstance(value, str) or ":" not in value:
        return None
    namespace, _, path = value.partition(":")
    if namespace != NAMESPACE or not path:
        return None
    if any(part in ("", ".", "..") for part in path.split("/")):
        report.error(f"{source.relative_to(ROOT)} 里的资源位置 {value} 含有非法路径段")
        return None
    return path


def check_model_reference(source: Path, value, report: Report) -> None:
    path = parse_location(value, source)
    if path is None:
        return
    target = ASSETS / NAMESPACE / "models" / f"{path}.json"
    report.checked += 1
    if not target.is_file():
        report.error(f"{source.relative_to(ROOT)} 指向的模型 {value} 不存在（缺 {target.relative_to(ROOT)}）")


def check_texture_reference(source: Path, value, report: Report) -> None:
    path = parse_location(value, source)
    if path is None:
        return
    target = ASSETS / NAMESPACE / "textures" / f"{path}.png"
    report.checked += 1
    if not target.is_file():
        report.error(f"{source.relative_to(ROOT)} 引用的贴图 {value} 不存在（缺 {target.relative_to(ROOT)}）")


def walk_model_references(node, key, source: Path, report: Report) -> None:
    """在任意 JSON 里递归找 `model` / `parent` 键。

    26.3 的物品定义是嵌套的：`{"model": {"type": "...", "model": "ns:path"}}` ——
    外层键也叫 `model`，但它的值是对象而不是字符串。所以这里
    **先递归、到了叶子再判断**，外层对象里的内层 `model` 键才不会漏掉。
    """
    if isinstance(node, dict):
        for child_key, value in node.items():
            walk_model_references(value, child_key, source, report)
    elif isinstance(node, list):
        for item in node:
            walk_model_references(item, key, source, report)
    elif key in ("model", "parent"):
        check_model_reference(source, node, report)


def parse_variant_key(key: str) -> dict[str, str]:
    properties: dict[str, str] = {}
    for part in key.split(","):
        name, _, value = part.partition("=")
        properties[name.strip()] = value.strip()
    return properties


def check_variant_completeness(path: Path, variants: dict, report: Report) -> None:
    """变体必须是「提到过的属性值」的完整组合，且布尔属性两侧不能共用同一个模型。"""
    parsed = {key: parse_variant_key(key) for key in variants}
    value_sets: dict[str, set[str]] = {}
    for properties in parsed.values():
        for name, value in properties.items():
            value_sets.setdefault(name, set()).add(value)

    # 两侧必须用同一种属性顺序：dict 的插入序取决于变体键的书写顺序，
    # 直接 product 会得到与 actual 不同的元组顺序，于是永远「缺少变体」。
    names = sorted(value_sets)
    expected = set(itertools.product(*(value_sets[n] for n in names)))
    actual = {tuple(properties[n] for n in names) for properties in parsed.values()}
    missing = expected - actual
    if missing:
        pretty = [dict(zip(names, combo)) for combo in sorted(missing)]
        report.error(f"{path.relative_to(ROOT)} 缺少变体组合：{pretty}")

    for name, values in value_sets.items():
        if len(values) != 2 or set(values) != {"false", "true"}:
            continue
        others = [n for n in value_sets if n != name]
        for combo in itertools.product(*(value_sets[n] for n in others)):
            models = {
                variants[key].get("model")
                for key, properties in parsed.items()
                if all(properties[n] == value for n, value in zip(others, combo))
            }
            if len(models) == 1:
                report.error(
                    f"{path.relative_to(ROOT)}：属性 {name} 在 {dict(zip(others, combo))} 下"
                    f"的两种取值都映射到 {models} —— 这个属性在视觉上没有任何效果，"
                    "要么补上模型，要么别在 blockstate 里声明它"
                )


def main() -> int:
    report = Report()

    json_files = sorted(p for p in ASSETS.rglob("*.json") if p.is_file())
    if not json_files:
        raise SystemExit(f"{ASSETS} 下没有任何 JSON")

    for path in json_files:
        try:
            data = json.loads(path.read_text(encoding="utf-8"))
        except json.JSONDecodeError as exc:
            report.error(f"{path.relative_to(ROOT)} 不是合法 JSON：{exc}")
            continue

        parent_dir = path.relative_to(ASSETS).parts[1] if len(path.relative_to(ASSETS).parts) > 1 else ""
        if parent_dir == "blockstates":
            variants = data.get("variants", {})
            if not isinstance(variants, dict):
                report.error(f"{path.relative_to(ROOT)} 的 variants 不是对象")
                continue
            for key, variant in variants.items():
                entries = variant if isinstance(variant, list) else [variant]
                for entry in entries:
                    check_model_reference(path, entry.get("model"), report)
            check_variant_completeness(path, variants, report)
        elif parent_dir == "models":
            parent = data.get("parent")
            if isinstance(parent, str):
                check_model_reference(path, parent, report)
            textures = data.get("textures")
            if isinstance(textures, dict):
                for value in textures.values():
                    check_texture_reference(path, value, report)
        elif parent_dir == "items":
            walk_model_references(data, None, path, report)

    print(f"检查了 {len(json_files)} 个 JSON，共 {report.checked} 条资源引用")

    for message in report.errors:
        print(f"[FAIL] {message}")

    if report.errors:
        print(f"\n资源检查失败：{len(report.errors)} 项")
        return 1
    print("资源检查通过")
    return 0


if __name__ == "__main__":
    sys.exit(main())
