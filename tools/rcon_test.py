"""RCON 驱动的运行时测试工具。

用途：**不启动客户端**就能验证方块 / 方块实体的真实行为。
Minecraft 服务器的 RCON 会把命令的真实执行结果回传，比翻日志更能说明问题。

用法：

    # 1. run/server.properties 里打开 RCON
    #    enable-rcon=true / rcon.port=25585 / rcon.password=<自定>
    # 2. 启动服务器（另开一个终端）
    ./gradlew runServer
    # 3. 跑场景
    python tools/rcon_test.py phase3 --password <自定>

为什么要有这个文件：需求里写着「不得只通过静态阅读代码就声称所有测试通过」。
把测试固定成脚本，任何人都能重跑一遍，也能看出哪条断言在哪个版本上失效了。
"""
import argparse
import re
import socket
import struct
import sys
import time

SERVERDATA_AUTH = 3
SERVERDATA_EXECCOMMAND = 2


# ---------------------------------------------------------------- 协议

def _build(req_id, ptype, payload):
    body = struct.pack("<ii", req_id, ptype) + payload.encode("utf-8") + b"\x00\x00"
    return struct.pack("<i", len(body)) + body


def _read_packet(sock):
    raw = b""
    while len(raw) < 4:
        chunk = sock.recv(4 - len(raw))
        if not chunk:
            raise ConnectionError("RCON 连接被关闭")
        raw += chunk
    (length,) = struct.unpack("<i", raw)
    data = b""
    while len(data) < length:
        chunk = sock.recv(length - len(data))
        if not chunk:
            raise ConnectionError("RCON 连接被关闭")
        data += chunk
    req_id, ptype = struct.unpack("<ii", data[:8])
    return req_id, ptype, data[8:-2].decode("utf-8", "replace")


class Rcon:
    def __init__(self, port, password, retries=180, delay=1.0):
        self.sock = self._connect(port, password, retries, delay)

    @staticmethod
    def _connect(port, password, retries, delay):
        for _ in range(retries):
            try:
                sock = socket.create_connection(("127.0.0.1", port), timeout=5)
            except OSError:
                time.sleep(delay)
                continue
            sock.sendall(_build(1, SERVERDATA_AUTH, password))
            deadline = time.time() + 5
            while time.time() < deadline:
                req_id, _, _ = _read_packet(sock)
                if req_id == 1:
                    return sock
                if req_id == -1:
                    raise SystemExit("RCON 密码错误")
            sock.close()
        raise SystemExit("连不上 RCON（端口 %s）——服务器起来了吗？" % port)

    def cmd(self, text, quiet=False):
        self.sock.sendall(_build(2, SERVERDATA_EXECCOMMAND, text))
        result = _read_packet(self.sock)[2]
        if not quiet:
            print("    > %s\n      %s" % (text, result))
        return result

    def block_data(self, x, y, z):
        """取方块实体 NBT；目标不是方块实体时返回空串。"""
        return self.cmd("data get block %d %d %d" % (x, y, z), quiet=True)

    def close(self):
        self.sock.close()


# ---------------------------------------------------------------- 断言

class Report:
    def __init__(self):
        self.rows = []

    def check(self, name, ok, detail=""):
        self.rows.append((name, bool(ok), detail))
        print("  [%s] %s%s" % ("PASS" if ok else "FAIL", name, ("  | " + detail) if detail else ""))
        return bool(ok)

    def summary(self):
        failed = [r for r in self.rows if not r[1]]
        print("\n" + "=" * 64)
        print("结果：%d/%d 通过" % (len(self.rows) - len(failed), len(self.rows)))
        for name, _, detail in failed:
            print("  FAIL  %s  %s" % (name, detail))
        print("=" * 64)
        return 0 if not failed else 1


def energy_of(nbt):
    """从 /data get block 的文本里取出 PowerAcceptor.energy。取不到返回 None。"""
    match = re.search(r"PowerAcceptor:\s*\{energy:\s*(\d+)L?\}", nbt)
    return int(match.group(1)) if match else None


def flag_of(nbt, key):
    """取一个布尔字段（NBT 里写成 0b / 1b）。取不到返回 None。"""
    match = re.search(re.escape(key) + r":\s*(\d)b", nbt)
    return bool(int(match.group(1))) if match else None


def item_ids(nbt):
    """列出 Items 里出现的物品 id。"""
    section = re.search(r"Items:\s*\[(.*?)\](,|$)", nbt, re.S)
    return re.findall(r'id:\s*"([^"]+)"', section.group(1)) if section else []


# ---------------------------------------------------------------- 场景

BATTERY = (10, 100, 0)
CABLE = (11, 100, 0)
MACHINE = (12, 100, 0)

MOD = "cyxautofishingmachine"
CCG = "crimsoncoppergrid"


def setup_area(rcon):
    rcon.cmd("forceload add 0 0")


def teardown_area(rcon):
    for pos in (BATTERY, CABLE, MACHINE):
        rcon.cmd("setblock %d %d %d air" % pos)
    rcon.cmd("forceload remove 0 0")


def scenario_phase3(rcon):
    """Phase 3 验收：机器能正常连接电网并接收能量。"""
    rep = Report()
    mx, my, mz = MACHINE
    print("\n--- 搭建：电池(10,100,0) - 电缆(11,100,0) - 钓鱼机(12,100,0) ---")
    setup_area(rcon)
    rcon.cmd("setblock %d %d %d %s:battery" % (BATTERY + (CCG,)))
    rcon.cmd("setblock %d %d %d %s:cable" % (CABLE + (CCG,)))
    rcon.cmd("setblock %d %d %d %s:auto_fishing_machine" % (MACHINE + (MOD,)))
    time.sleep(1.0)

    # 1. 电缆应当朝电池和机器两个方向都伸出连接臂。
    #    CableBlockEntity.shouldConnect 用的是 EnergyStorage.SIDED.find，
    #    所以这条断言同时证明「机器的能量能力确实被查阅表找到了」。
    out = rcon.cmd("execute if block %d %d %d %s:cable[west=true,east=true]" % (CABLE + (CCG,)))
    rep.check("电缆与电池、钓鱼机双向连接（energy 能力可被 SIDED 查到）", "passed" in out, out.strip())

    # 2. 机器认为自己接在电网上
    nbt = rcon.block_data(mx, my, mz)
    rep.check("机器 GridLinked = 1", flag_of(nbt, "GridLinked") is True, nbt)

    # 3. 电池没电时，机器不应该凭空有电
    rep.check("电池空载时机器电量为 0", energy_of(nbt) == 0, "energy=%s" % energy_of(nbt))

    # 4. 给电池充电，观察机器是否进电
    print("\n--- 给电池充 100000 FE，观察机器进电 ---")
    rcon.cmd("data merge block %d %d %d {PowerAcceptor:{energy:100000}}" % BATTERY)
    before = energy_of(rcon.block_data(mx, my, mz))
    time.sleep(1.0)  # 20 刻
    after = energy_of(rcon.block_data(mx, my, mz))
    gained = (after or 0) - (before or 0)
    # 导线传输速率 32 FE/t、机器输入上限 32 FE/t → 20 刻最多 640，留一点调度余量
    rep.check("机器从电网获得能量", gained > 0, "%s -> %s (+%d)" % (before, after, gained))
    rep.check("进电速率不超过 32 FE/t", 0 < gained <= 32 * 20, "+%d / 20 刻" % gained)

    # 5. 充满后必须停止接收（canAcceptEnergy 生效），且不允许溢出
    print("\n--- 等待充满（容量 10000 FE，约 313 刻） ---")
    deadline = time.time() + 40
    full = 0
    while time.time() < deadline:
        full = energy_of(rcon.block_data(mx, my, mz)) or 0
        if full >= 10000:
            break
        time.sleep(1.0)
    rep.check("机器充满到 10000 FE", full == 10000, "energy=%d" % full)
    time.sleep(1.0)
    still = energy_of(rcon.block_data(mx, my, mz))
    rep.check("充满后不再接收（不溢出）", still == 10000, "energy=%d" % still)

    # 6. 剪断电缆 → 立刻掉线，并且不再进电
    print("\n--- 剪断电缆 ---")
    rcon.cmd("setblock %d %d %d air" % CABLE)
    time.sleep(1.5)
    nbt = rcon.block_data(mx, my, mz)
    rep.check("断线后 GridLinked = 0", flag_of(nbt, "GridLinked") is False, nbt)
    rcon.cmd("data merge block %d %d %d {PowerAcceptor:{energy:0}}" % MACHINE)
    time.sleep(1.5)
    drained = energy_of(rcon.block_data(mx, my, mz))
    rep.check("断线后不再进电", drained == 0, "energy=%s" % drained)

    # 7. 接回电缆 → 恢复供电
    print("\n--- 接回电缆 ---")
    rcon.cmd("setblock %d %d %d %s:cable" % (CABLE + (CCG,)))
    time.sleep(1.5)
    nbt = rcon.block_data(mx, my, mz)
    rep.check("复线后 GridLinked 恢复为 1", flag_of(nbt, "GridLinked") is True, nbt)
    rep.check("复线后重新进电", (energy_of(nbt) or 0) > 0, "energy=%s" % energy_of(nbt))

    print("\n--- 清理 ---")
    teardown_area(rcon)
    return rep


PERSIST_ENERGY = 7777


def scenario_phase3_persist_prepare(rcon):
    """Phase 3 收尾第一步：制造一个「只能靠存档恢复」的局面。

    关键点：必须**先把电缆剪掉**。否则重启后电网立刻又把机器充满，
    「重启后电量还在」这条断言就变成了「重启后重新充上了电」，什么也没证明。
    剪掉线之后还剩下的电量，只可能来自 NBT。
    """
    rep = Report()
    mx, my, mz = MACHINE
    print("\n--- 剪断电缆，把电量写成一个可辨认的值 ---")
    setup_area(rcon)
    rcon.cmd("setblock %d %d %d air" % CABLE)
    time.sleep(1.5)
    rcon.cmd("setblock %d %d %d %s:auto_fishing_machine" % (MACHINE + (MOD,)))
    time.sleep(0.5)
    rcon.cmd("data merge block %d %d %d {PowerAcceptor:{energy:%d}}" % (MACHINE + (PERSIST_ENERGY,)))
    time.sleep(1.5)

    nbt = rcon.block_data(mx, my, mz)
    rep.check("准备完成：GridLinked = 0（孤立机器，重启后只能靠存档）",
              flag_of(nbt, "GridLinked") is False, nbt)
    rep.check("准备完成：电量 = %d" % PERSIST_ENERGY, energy_of(nbt) == PERSIST_ENERGY,
              "energy=%s" % energy_of(nbt))
    rcon.cmd("save-all flush")
    print("\n  现在停掉服务器、重新启动，再跑 phase3-persist-check。")
    return rep


def scenario_phase3_persist_check(rcon):
    """Phase 3 收尾第二步：重启后复查（承接 phase3-persist-prepare）。"""
    rep = Report()
    mx, my, mz = MACHINE
    rcon.cmd("forceload add 0 0")
    time.sleep(1.0)
    nbt = rcon.block_data(mx, my, mz)
    rep.check("重启后方块实体仍存在", "auto_fishing_machine" in nbt, nbt)
    rep.check("重启后电量原样恢复（%d）" % PERSIST_ENERGY, energy_of(nbt) == PERSIST_ENERGY,
              "energy=%s" % energy_of(nbt))
    rep.check("重启后重算电网状态：孤立机器 GridLinked = 0",
              flag_of(nbt, "GridLinked") is False, nbt)
    return rep


def scenario_phase3_persist_cleanup(rcon):
    """Phase 3 收尾第三步：拆掉测试现场。"""
    rep = Report()
    rcon.cmd("setblock %d %d %d air" % MACHINE)
    rcon.cmd("forceload remove 0 0")
    rep.check("测试现场已清理", True)
    return rep


SCENARIOS = {
    "phase3": scenario_phase3,
    "phase3-persist-prepare": scenario_phase3_persist_prepare,
    "phase3-persist-check": scenario_phase3_persist_check,
    "phase3-persist-cleanup": scenario_phase3_persist_cleanup,
}


def main():
    parser = argparse.ArgumentParser(description="RCON 运行时测试")
    parser.add_argument("scenario", choices=sorted(SCENARIOS))
    parser.add_argument("--port", type=int, default=25585)
    parser.add_argument("--password", required=True)
    args = parser.parse_args()

    rcon = Rcon(args.port, args.password)
    try:
        rep = SCENARIOS[args.scenario](rcon)
    finally:
        rcon.close()
    sys.exit(rep.summary())


if __name__ == "__main__":
    main()
