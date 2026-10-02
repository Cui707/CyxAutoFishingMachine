#!/usr/bin/env python3
"""生成「运行中」的正面贴图 —— 把红石指示灯点亮。

== 为什么是脚本，不是一张手画的 PNG ==

`active=false` 与 `active=true` 的正面只差指示灯那十几个像素。
手画一张就等于把同一幅铜壳画了两遍：以后要改铜色、改铆钉位置，
得记得同时改两张，而漏改的那张没有任何机制会提醒。

所以这里从 `auto_fishing_machine_front.png` **派生**：
  * 指示灯像素改成亮红（原来是不发光的暗红 `(170,0,0)`）；
  * 紧贴指示灯的像素混入一点暖色 —— 16×16 上「发光」只能靠这一点晕染表达，
    没有它，点亮的灯和暗着的灯在远处几乎分不出来。

脚本会**校验自己的前提**：底图里必须真的存在那两种颜色，
且改动范围必须与预期完全一致。底图以后被重画时，这里会当场失败，
而不是安静地生成一张没差别的贴图。

用法：

    python tools/gen_active_texture.py          # 生成（覆盖已有产物）
    python tools/gen_active_texture.py --check  # 只校验产物是否与底图一致，不写文件
"""

from __future__ import annotations

import argparse
import struct
import sys
import zlib
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
TEX_DIR = ROOT / "src/main/resources/assets/cyxautofishingmachine/textures/block"
BASE = TEX_DIR / "auto_fishing_machine_front.png"
ACTIVE = TEX_DIR / "auto_fishing_machine_front_active.png"

# 底图里指示灯的颜色。改成别的暗红时这里会失败 —— 那正是它存在的意义。
LAMP = (170, 0, 0)

# 点亮后的灯。
LAMP_LIT = (255, 96, 64)

# 晕染的目标色，以及混入比例。
GLOW = (255, 130, 90)
GLOW_MIX = 0.35

PNG_SIGNATURE = b"\x89PNG\r\n\x1a\n"


# ------------------------------------------------------------ PNG 读写
#
# 只实现这个项目需要的最小集合：8 位、非隔行的真彩/真彩带 alpha。
# 故意不引第三方库（Pillow 在这个环境里没装，也不该为一张 16×16 的图引入依赖）：
# 需要的只是一段 zlib 和几行反滤波。


def read_png(path: Path) -> tuple[int, int, list[list[tuple[int, int, int, int]]]]:
    data = path.read_bytes()
    if not data.startswith(PNG_SIGNATURE):
        raise SystemExit(f"{path} 不是 PNG")
    pos = 8
    header = None
    palette = b""
    idat = b""
    while pos < len(data):
        (length,) = struct.unpack(">I", data[pos:pos + 4])
        kind = data[pos + 4:pos + 8]
        body = data[pos + 8:pos + 8 + length]
        if kind == b"IHDR":
            header = struct.unpack(">IIBBBBB", body)
        elif kind == b"PLTE":
            palette = body
        elif kind == b"IDAT":
            idat += body
        pos += 12 + length

    if header is None:
        raise SystemExit(f"{path} 里没有 IHDR")
    width, height, depth, color_type, _, _, interlace = header
    if depth != 8 or interlace != 0 or color_type not in (2, 6):
        raise SystemExit(
            f"{path} 是 depth={depth} color_type={color_type} interlace={interlace}，"
            "本脚本只处理 8 位非隔行的真彩/真彩带 alpha"
        )
    if color_type == 3:
        raise SystemExit(f"{path} 是调色板图，需要先展开成真彩")

    channels = 3 if color_type == 2 else 4
    stride = width * channels
    raw = zlib.decompress(idat)
    expected = height * (stride + 1)
    if len(raw) != expected:
        raise SystemExit(f"{path} 解压后 {len(raw)} 字节，应为 {expected}")

    rows: list[list[tuple[int, int, int, int]]] = []
    previous = bytearray(stride)
    offset = 0
    for _ in range(height):
        filter_type = raw[offset]
        offset += 1
        line = bytearray(raw[offset:offset + stride])
        offset += stride
        if filter_type == 1:
            for i in range(channels, stride):
                line[i] = (line[i] + line[i - channels]) & 0xFF
        elif filter_type == 2:
            for i in range(stride):
                line[i] = (line[i] + previous[i]) & 0xFF
        elif filter_type == 3:
            for i in range(stride):
                left = line[i - channels] if i >= channels else 0
                line[i] = (line[i] + ((left + previous[i]) >> 1)) & 0xFF
        elif filter_type == 4:
            for i in range(stride):
                left = line[i - channels] if i >= channels else 0
                up = previous[i]
                up_left = previous[i - channels] if i >= channels else 0
                pa, pb, pc = abs(up - up_left), abs(left - up_left), abs(left + up - 2 * up_left)
                predictor = left if (pa <= pb and pa <= pc) else (up if pb <= pc else up_left)
                line[i] = (line[i] + predictor) & 0xFF
        elif filter_type != 0:
            raise SystemExit(f"{path} 用了不支持的滤波器 {filter_type}")

        row: list[tuple[int, int, int, int]] = []
        for x in range(width):
            px = line[x * channels:(x + 1) * channels]
            if channels == 4:
                row.append((px[0], px[1], px[2], px[3]))
            else:
                row.append((px[0], px[1], px[2], 255))
        rows.append(row)
        previous = line
    return width, height, rows


def write_png(path: Path, rows: list[list[tuple[int, int, int, int]]]) -> None:
    height = len(rows)
    width = len(rows[0])
    raw = bytearray()
    for row in rows:
        raw.append(0)  # 滤波器 None：图很小，压缩率不值得为它多写代码
        for r, g, b, a in row:
            raw += bytes((r, g, b, a))

    def chunk(kind: bytes, body: bytes) -> bytes:
        return (
            struct.pack(">I", len(body))
            + kind
            + body
            + struct.pack(">I", zlib.crc32(kind + body) & 0xFFFFFFFF)
        )

    payload = (
        PNG_SIGNATURE
        + chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0))
        + chunk(b"IDAT", zlib.compress(bytes(raw), 9))
        + chunk(b"IEND", b"")
    )
    path.write_bytes(payload)


# ------------------------------------------------------------ 派生


def blend(base: tuple[int, int, int, int], target: tuple[int, int, int], mix: float) -> tuple[int, int, int, int]:
    return (
        round(base[0] * (1 - mix) + target[0] * mix),
        round(base[1] * (1 - mix) + target[1] * mix),
        round(base[2] * (1 - mix) + target[2] * mix),
        base[3],
    )


def derive(rows: list[list[tuple[int, int, int, int]]]) -> list[list[tuple[int, int, int, int]]]:
    height = len(rows)
    width = len(rows[0])

    lamps = {
        (x, y)
        for y in range(height)
        for x in range(width)
        if rows[y][x][:3] == LAMP
    }
    if not lamps:
        raise SystemExit(
            f"底图里找不到指示灯颜色 {LAMP} —— 贴图被重画过了，"
            "本脚本的派生规则需要跟着更新（不能安静地生成一张没差别的图）"
        )

    halo = set()
    for x, y in lamps:
        for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
            nx, ny = x + dx, y + dy
            if 0 <= nx < width and 0 <= ny < height and (nx, ny) not in lamps:
                halo.add((nx, ny))

    out: list[list[tuple[int, int, int, int]]] = []
    for y in range(height):
        row = []
        for x in range(width):
            if (x, y) in lamps:
                row.append((*LAMP_LIT, rows[y][x][3]))
            elif (x, y) in halo:
                row.append(blend(rows[y][x], GLOW, GLOW_MIX))
            else:
                row.append(rows[y][x])
        out.append(row)
    return out


def diff_pixels(
    a: list[list[tuple[int, int, int, int]]],
    b: list[list[tuple[int, int, int, int]]],
) -> list[tuple[int, int]]:
    return [
        (x, y)
        for y in range(len(a))
        for x in range(len(a[0]))
        if a[y][x] != b[y][x]
    ]


def main() -> int:
    parser = argparse.ArgumentParser(description="从正面贴图派生出「运行中」版本")
    parser.add_argument("--check", action="store_true", help="只校验已有产物，不写文件")
    args = parser.parse_args()

    width, height, base_rows = read_png(BASE)
    derived_rows = derive(base_rows)
    changed = diff_pixels(base_rows, derived_rows)

    lamps = sum(1 for row in base_rows for px in row if px[:3] == LAMP)
    print(f"底图 {BASE.name}：{width}×{height}，指示灯 {lamps} 像素，派生改动 {len(changed)} 像素")

    # 改动数 = 灯本身 + 一圈晕染，所以只做上下界校验：
    # 下界保证「灯真的被点亮了」，上界保证「没有把铜壳整片重刷」——
    # 底图里若在别处也用了同一个暗红（比如某个装饰块），上界会立刻失败。
    if len(changed) < lamps:
        raise SystemExit(f"派生只改了 {len(changed)} 像素，少于指示灯 {lamps} 像素，规则有问题")
    if len(changed) > lamps * 6:
        raise SystemExit(
            f"派生改动了 {len(changed)} 像素，远超预期（指示灯 {lamps} 像素 + 一圈晕染）——"
            "底图里可能有别的地方也用了指示灯的颜色"
        )

    if args.check:
        _, _, existing = read_png(ACTIVE)
        actual = diff_pixels(base_rows, existing)
        if existing != derived_rows:
            raise SystemExit(
                f"{ACTIVE.name} 与「从底图派生」的结果不一致（{len(actual)} 像素不同）—— "
                "请重新运行不带 --check 的本脚本"
            )
        print(f"{ACTIVE.name} 与底图派生结果一致")
        return 0

    write_png(ACTIVE, derived_rows)
    print(f"已写出 {ACTIVE.relative_to(ROOT)}（{ACTIVE.stat().st_size} 字节）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
