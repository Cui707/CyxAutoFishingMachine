"""Phase 6 突变检验：往实现里注入已知错误，确认测试会红，再还原。

用法：在项目根目录执行 `python tools/mutate_phase6.py`。
每条突变都是「改一处 → 跑全套 GameTest → 记录失败清单 → 全部还原」，
失败清单就是这条测试的鉴别力证据。
"""
import io
import os
import re
import subprocess

ROOT = r"D:\codespace\CyxAutoFishingMachine"
PKG = os.path.join(ROOT, "src", "main", "java", "com", "cyx", "cyxautofishingmachine")
ADJ = os.path.join(PKG, "inventory", "AdjacentInventory.java")
BE = os.path.join(PKG, "blockentity", "AutoFishingMachineBlockEntity.java")

MUTATIONS = [
    ("M1 大箱子不合并（只看得到一半的 27 格）", ADJ,
     """		if (container instanceof ChestBlockEntity && block instanceof ChestBlock chest) {
			return ChestBlock.getContainer(chest, state, level, pos, true);
		}
""",
     ""),

    ("M2 不复制物品堆（源槽与目标容器指向同一个 ItemStack）", ADJ,
     "		ItemStack remaining = stack.copyWithCount(1);",
     "		ItemStack remaining = stack;"),

    ("M3 入料面方向传反（传 direction 而不是它的反面）", BE,
     "Direction face = direction.getOpposite();",
     "Direction face = direction;"),

    ("M4 不排除「塞不进东西的容器」（电池被当成满容器）", BE,
     """			if (target == null || !AdjacentInventory.canReceive(target, face)) {
				continue;
			}
""",
     """			if (target == null) {
				continue;
			}
"""),

    ("M5 输出时连钓竿槽一起遍历", BE,
     "\t\tfor (int index = 0; index < MachineConfig.SLOT_CACHE_COUNT; index++) {",
     "\t\tfor (int index = -1; index < MachineConfig.SLOT_CACHE_COUNT; index++) {"),
]


def read(path):
    with io.open(path, encoding="utf-8", newline="") as handle:
        return handle.read()


def write(path, text):
    with io.open(path, "w", encoding="utf-8", newline="") as handle:
        handle.write(text)


def run_tests():
    proc = subprocess.run([os.path.join(ROOT, "gradlew.bat"), "runGameTest", "--console=plain"],
                          cwd=ROOT, capture_output=True, text=True,
                          encoding="utf-8", errors="replace")
    out = proc.stdout + proc.stderr
    if "GAME TESTS COMPLETE" in out and "required tests failed" not in out \
            and "BUILD SUCCESSFUL" in out:
        return True, []
    return False, sorted(set(re.findall(r"- cyxautofishingmachine:(\S+?):", out)))


def main():
    backups = {path: read(path) for path in (ADJ, BE)}
    results = []
    try:
        for name, path, old, new in MUTATIONS:
            # 关键：每轮开始把**所有**文件还原。只还原被改的那个会让上一轮的突变残留，
            # 失败清单就不再是「这一处改动」的证据了（第一次跑就踩了这个坑）。
            for target, text in backups.items():
                write(target, text)
            text = read(path)
            if old not in text:
                results.append((name, "锚点没找到，跳过"))
                print(">>>", name, "=> 锚点没找到，跳过", flush=True)
                continue
            write(path, text.replace(old, new))
            ok, failures = run_tests()
            outcome = "没被抓到（测试有漏洞）" if ok else \
                "被抓到，" + str(len(failures)) + " 条失败：" + "、".join(failures)
            results.append((name, outcome))
            print(">>>", name, "=>", outcome, flush=True)
    finally:
        for target, text in backups.items():
            write(target, text)

    print("\n================ 突变检验汇总 ================")
    for name, outcome in results:
        print("-", name, "->", outcome)


if __name__ == "__main__":
    main()
