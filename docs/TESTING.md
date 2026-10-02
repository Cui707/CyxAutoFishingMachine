# 测试

> 原则：**不得只靠静态阅读代码就声称测试通过**。这里只记录实际跑过、拿到过结果的测试。
> 每一项都标注了验证日期与当时的项目版本。

## 1. 测试方法：无人值守的服务器实测

客户端交互类行为（放置、破坏、掉落、重启恢复、供电）不依赖「有人在场」——
开发服务器开着 RCON，用脚本把命令发进去，再读回响应即可。

```
run/server.properties
  enable-rcon=true
  rcon.port=25585
  rcon.password=<自定>
```

```bash
./gradlew runServer                                   # 另开一个终端跑着
python tools/rcon_test.py phase3 --password <自定>    # 跑某个场景
python tools/rcon_test.py --help                      # 列出全部场景
```

`tools/rcon_test.py` 里的每条断言都是「发命令 → 解析返回值 → 判断」，
失败会打印实际拿到的 NBT，不会只报一句「失败」。
需要重启才能验证的场景拆成 `-prepare` / `-check` 两步，中间由人停服、起服。

**RCON 够不着的部分由 GameTest 补上**：RCON 只能发服务器命令，
点不到界面里的槽位，更模拟不了玩家点击。菜单与界面的行为用 Fabric 的
GameTest 框架验证 —— 服务端侧跑 `./gradlew runGameTest`（真实服务端 + 真实玩家对象），
客户端侧跑 `./gradlew runClientGameTest`（真实客户端，能打开界面并截图）。
两者都是无人值守、可进 CI 的。

## 2. Phase 2 实测记录

**版本 `0.1.0`（Phase 2）· 2026-10-02 · 开发服务器（46 个模组，含 CCG 1.1.0）**

测试位置 `(0, 100, 0)`，上方 `(0, 101, 0)` 放漏斗，先用 `forceload add 0 0` 固定区块。

| # | 验证点 | 命令 | 结果 |
| --- | --- | --- | --- |
| 1 | 方块 + 方块实体注册 | `setblock 0 100 0 cyxautofishingmachine:auto_fishing_machine` | `Changed the block at 0, 100, 0` |
| 2 | BE 初始状态 | `data get block 0 100 0` | `id: "cyxautofishingmachine:auto_fishing_machine"`, `PowerAcceptor: {energy: 0L}`, `Items: []` ✅ |
| 3 | 钓竿能进钓竿槽 | 漏斗装 `minecraft:fishing_rod` 放在机器正上方 | 机器 `Items: [{Slot: 0b, id: "minecraft:fishing_rod"}]`，漏斗里钓竿消失 ✅ |
| 4 | **无效物品被拒**（条件 C） | 漏斗装 64 个 `minecraft:cod` | 64 个鳕鱼**留在漏斗里**，机器里没有 ✅ |
| 5 | 缓存槽不接受外部放入 | 同上（漏斗会依次尝试所有槽位） | 同上，9 个缓存槽全部拒绝 ✅ |
| 6 | 战利品表有效 | `loot spawn 0 100 0 loot cyxautofishingmachine:blocks/auto_fishing_machine` | `Dropped 1 [Auto Fishing Machine]` ✅ |
| 7 | 破坏掉落本体 + 内部物品 | `setblock 0 100 0 air destroy` | 同时掉出 `Auto Fishing Machine` 与内部 `Fishing Rod` ✅ |
| 8 | 重启恢复（物品） | 放入钓竿 + 3 个鳕鱼后 `stop` 再启动 | 钓竿与鳕鱼原样恢复 ✅ |
| 9 | 重启恢复（电量） | `data merge block 0 100 0 {PowerAcceptor:{energy:1234}}` 后重启 | `energy: 1234L` ✅ |
| 10 | 日志无异常 | — | 两轮运行均无 `ERROR` / `Exception`，46 模组全部加载 ✅ |

第 3、4、5 项合起来说明的是同一件事：**漏斗对 `Container.canPlaceItem` 的尊重是原版行为**，
所以「钓竿槽只收钓竿、缓存槽不收任何东西」这条规则是被原版机制强制执行的，
不依赖我们自己的代码去拦截。

## 3. Phase 3 实测记录：电网接入

**版本 `0.1.0`（Phase 3）· 2026-10-02 · 开发服务器**

场景布局（一条直线，机器与电池之间隔着一段导线，确保走的确实是电网）：

```
(10,100,0) crimsoncoppergrid:battery  ─  (11,100,0) crimsoncoppergrid:cable  ─  (12,100,0) cyxautofishingmachine:auto_fishing_machine
```

`python tools/rcon_test.py phase3` —— **11/11 通过**：

| # | 断言 | 实测结果 |
| --- | --- | --- |
| 1 | 电缆与电池、钓鱼机**双向连接** | `execute if block ... cable[west=true,east=true]` → `Test passed` |
| 2 | 机器 `GridLinked = 1` | `{GridLinked: 1b, ..., PowerAcceptor: {energy: 0L}}` |
| 3 | 电池空载时机器电量为 0 | `energy=0`（不会凭空产生能量） |
| 4 | 电池充电后机器开始进电 | `0 → 336`（20 刻） |
| 5 | 进电速率不超过 32 FE/t | `+336 / 20 刻`（= 16.8 FE/t，在上限内） |
| 6 | 充满到 10000 FE | `energy=10000` |
| 7 | 充满后不再接收、不溢出 | 1 秒后仍为 `energy=10000` |
| 8 | **剪断电缆 → `GridLinked = 0`** | `{GridLinked: 0b, energy: 10000L}` |
| 9 | 断线后不再进电 | 清零后 1.5 秒仍为 `energy=0` |
| 10 | 接回电缆 → `GridLinked` 恢复 | `{GridLinked: 1b, energy: 496L}` |
| 11 | 复线后重新进电 | `energy=496` |

补充观察（顺手做的守恒核对）：电池 −2288 FE / 机器 +2224 FE / 导线缓冲 +64 FE，**总量守恒**。

**重启持久化**（`phase3-persist-prepare` → 停服 → 起服 → `phase3-persist-check`）—— **3/3 通过**：

| # | 断言 | 实测结果 |
| --- | --- | --- |
| 1 | 重启后方块实体仍存在 | `id: "cyxautofishingmachine:auto_fishing_machine"` |
| 2 | 重启后电量原样恢复 | `energy=7777`（与存盘值一致） |
| 3 | 重启后重算电网状态 | `GridLinked: 0b`（机器是孤立的，确实是重算出来的而不是照抄存档） |

> 第 2 条的实验设计要点：**先把电缆剪掉再存盘**。否则重启后电网会立刻把机器充满，
> 「电量还在」就变成了「电量是刚充的」，什么也没证明。剪线后剩下的 7777 FE 只可能来自 NBT。

## 4. Phase 4 实测记录：界面与物品搬运（自动化 GameTest）

**版本 `0.1.0`（Phase 4）· 2026-10-02**

「shift 点击会不会复制物品」这类问题，RCON 点不到界面、JUnit 起不了 Minecraft，
只有 GameTest 能在真实服务端里造一个玩家、真的走一遍菜单交互路径。两层测试：

### 4.1 服务端 GameTest —— `./gradlew runGameTest`，**8/8 通过**

| 测试 | 验证点 |
| --- | --- |
| `rodSlotAcceptsOnlyFishingRods` | 钓竿槽收钓竿、拒鱼和木棍 |
| `cacheSlotsRejectExternalItems` | 9 个缓存槽对外一律拒收 |
| `menuSlotRulesMatchContainerRules` | **界面槽位与容器的判定逐项一致**（漏斗走 `canPlaceItem`、界面走 `Slot#mayPlace`，两者分叉就是复制漏洞的来源） |
| `quickMoveIntoMachineKeepsItemCount` | shift 点击把钓竿搬进机器，总数恒为 1 |
| `quickMoveOutOfMachineKeepsItemCount` | shift 点击把钓竿搬回背包，总数恒为 1 |
| `quickMoveRejectsNonRodIntoMachine` | 非钓竿 shift 点击时机器一个槽都不收 |
| `containerDataExposesEnergyAndGridState` | 界面读到的电量/电网状态直接来自方块实体，`ContainerData` 索引布局正确（改格数忘改 `getCount()` 会在这里越界） |

报告落在 `build/junit.xml`（CI 可直接消费）。

### 4.2 GameTest 抓到并修掉的真实漏洞

`quickMoveRejectsNonRodIntoMachine` **第一次跑就是失败的**：玩家背包 64 条鱼 shift 点击后
变成「缓存槽 64 条 + 背包剩 1 条」。

根因在**原版** `AbstractContainerMenu#moveItemStackTo`：它的循环有两个分支，
只有「放进空槽」那一支会调 `Slot#mayPlace`，**「合并进已有同类物品堆」那一支没有任何准入检查**。
对原版容器无害（槽位本就能放），但缓存槽是「只出不进」的，于是出现一条侧信道——
只要缓存里已有同类物品，玩家就能把背包里的同类物品合并进去，绕开 `canPlaceItem`。

危害不是复制（总数守恒），而是绕过设计：缓存会被当免费箱子，Phase 6 依赖的
「缓存满 → 停机」保护跟着失效。

修复：`AutoFishingMachineMenu` 覆写 `moveItemStackTo`，与原版逐句对应、唯一差别是
合并分支多一次 `slot.mayPlace(stack)`。修复后 8/8 通过。

### 4.3 客户端端到端 —— `./gradlew runClientGameTest`，**通过**

真实客户端自动完成：建世界 → 放机器 → 传送 → 看向方块 → 触发「使用方块」→
等界面出现 → 校验槽位坐标 → **截图**（`docs/screenshots/auto_fishing_machine_gui.png`）。

截图证实：标题居中、钓竿槽、3×3 缓存、电量条「0 / 10.0K」、
未接电网时状态文字呈告警红色 —— 布局无重叠、无溢出，资源（贴图/语言键）全部加载。
客户端侧日志 54 模组、无 ERROR、无资源缺失警告。

两点实现说明（都写在测试类的注释里）：

| 取舍 | 原因 |
| --- | --- |
| 用 `gameMode.useItemOn(...)` 而不是模拟鼠标右键 | 它是客户端交互的**真实入口**（原版按键最终也走到这里）。Fabric 的注入式按键在这个环境里不被原版按键轮询读到，而那层是纯原版逻辑，与本模组无关 |
| 玩家位置靠垫基岩稳住 | 26.3 不允许 `/data merge` 改玩家实体数据（实测 `Unable to modify player data`） |

## 5. Phase 5 实测记录：钓鱼状态机（自动化 GameTest）

**版本 `0.1.0`（Phase 5）· 2026-10-02**

钓鱼周期当时是 120~680 刻（6~34 秒），靠真实时间推进十几条测试要跑十几分钟
（节奏后来调快到约 46~76 刻，见 §7.5；下面这条理由在调快后仍然成立）。
`FishingGameTests` 的办法是**手动调 `MachineBaseBlockEntity#tick`** ——
原版给方块实体调度 tick 走的正是这个入口，一次调用等于推进一刻。
全套测试在毫秒级完成、完全确定（没有真实时间、没有并发、没有 tick 顺序抖动）。
代价是「状态机被正确调度」没有覆盖到，那由客户端端到端测试与 Phase 8 实机验证补上。

场景全部逐格手搭（原版开放水域判定要看浮标周围 ±2 格、上下 4 层，
「一片水算不算开放」必须用真实地形试，不能打桩）：

```
封闭水域：5×5×3 石头，只在 (2,1,2) 挖一格水（上方还是石头，第一层就判 INVALID）
开放水域：5×5×2 的水体 + 上方 5×5×2 空气 + 石头外框，机器在东岸 (7,2,4) 朝西
```

### 5.1 服务端 GameTest —— `./gradlew runGameTest`，**21/21 通过**（8 条 Phase 4 + 13 条 Phase 5）

| 测试 | 验证点 |
| --- | --- |
| `noRodReportsNoRod` | 没钓竿 → `NO_ROD`，且**一分电都不扣** |
| `noWaterReportsNoWater` | 周围没水 → `NO_WATER`，不扣电 |
| `cacheFullReportsCacheFull` | 9 格缓存全满 → `CACHE_FULL`（停机，而不是把产物丢地上） |
| `disconnectedGridReportsDisconnected` | 旁边没电源 → `GRID_DISCONNECTED`，不扣电 |
| `insufficientPowerReportsNotEnoughPower` | 接了电但不够预扣 → `NOT_ENOUGH_POWER`，电量一动不动 |
| `singleCatchConsumesEnergyAndDamagesRod` | 一杆的完整账：**正好预扣 500 FE、正好产出一件原版钓鱼战利品、钓竿耐久正好 −1** |
| `abortingCycleRefundsPreChargedEnergy` | 周期内拿走钓竿 → 中止、原因 `NO_ROD`、**预扣的电退回来** |
| `closedWaterNeverYieldsTreasure` | 一格水坑跑满 300 杆：**一件宝藏都不出**（确定性断言，见 5.2） |
| `openWaterYieldsTreasure` | 5×5×2 水池跑满 300 杆：**至少出一件宝藏**（单杆约 7%，300 杆全落空概率 ≈ 3e-10） |
| `bobberLandsOnMiddleOfWaterRun` | 浮标落在水面段**中点**（x=4 而不是贴岸的 x=6）；把中点填成石头后判定跟随变化 |
| `lureEnchantShrinksInitialWait`（§7.5 起改为 `lureEnchantMapsLevelToTicks`） | 饵钓 III：等待落在 1~300 刻（原版 `nextInt(100,600) − 3×100`），60 轮全部命中区间 |
| `mendingRepairsRod` | 磨损 30 的钓竿 + 修补：一杆之后耐久**上升** |
| `unbreakingSlowsDurabilityLoss` | 同样 40 杆：普通钓竿**正好**磨损 40，耐久 III 明显更少 |

报告落在 `build/junit.xml`。

### 5.2 突变检验：这些测试真的会红

「测试全绿」本身不证明什么 —— 得先证明它们**抓得到错**。做法是故意注入三类
典型错误，逐条确认对应测试变红，再还原：

| 突变 | 实测结果 |
| --- | --- |
| **A**：`probeOpenWater` 恒返回 `true` | `closedWaterNeverYieldsTreasure` 红：**「300 杆里出现了 15 件宝藏」**（≈5%，正是宝藏权重 5/100）；`bobberLandsOnMiddleOfWaterRun` 红：状态误报为开放 |
| **B**：`probeOpenWater` 恒返回 `false` | `openWaterYieldsTreasure` 红：**「300 杆里一件宝藏都没出」**；状态误报为非开放 |
| **C**：丢掉饵钓的 `* 20.0F` 秒→刻换算 | 当时 `lureEnchantShrinksInitialWait` 红：**「应落在 1~300 之间，实际 527」**（600 − 15 秒被当成 15 刻）。同一突变现在由 `lureEnchantMapsLevelToTicks` 抓 —— 它直接断言换算链的输出 0/100/300，会差 20 倍 |

突变 A/B 证明的是同一条最重要的结论：**「封闭水域拿不到宝藏」确实是原版战利品表
的 `in_open_water` 谓词在把关** —— 我们的标志位一错，宝藏立刻漏出来/被筛掉，
而测试在**产物层面**（不是只在显示层面）抓到了它。

> 突变过程中还改掉了测试自身的一个弱点：`closedWaterNeverYieldsTreasure` /
> `openWaterYieldsTreasure` 原本先断言「状态」再断言「产物」。状态断言会抢先失败，
> 把「产物混进/漏掉宝藏」这条更严重的证据藏起来。已改成**先产物、后状态**。

### 5.3 客户端端到端 —— `./gradlew runClientGameTest`，**通过**

截图（`docs/screenshots/auto_fishing_machine_gui.png`，Phase 5 版）证实：
电量条「0 / 10.0K」、其下新增的**钓鱼进度条**、红色状态文字
「No fishing rod」（无钓竿 → `NO_ROD`，告警色）三者在面板内各就各位，
无重叠、无溢出，中英文语言键齐全。

## 6. Phase 6 实测记录：相邻容器输出（自动化 GameTest）

**版本 `0.1.0`（Phase 6）· 2026-10-02**

输出逻辑的最小场景不需要真实钓鱼周期（产物直接预置进缓存槽），
但「相邻容器」的语义必须用真实方块试：大箱子是两半、堆肥桶没有方块实体、
电池是 `Container` 占位骨架 —— 这些全是运行时事实，不能打桩。

### 6.1 服务端 GameTest —— `./gradlew runGameTest`，**34/34 通过**（8 条 Phase 4 + 13 条 Phase 5 + 13 条 Phase 6）

13 条 `OutputGameTests`，按验证目标分四组：

| 测试 | 验证点 |
| --- | --- |
| `oneItemMovesIntoAdjacentChest` | 下方箱子收到恰好 1 条鳕鱼，缓存相应减 1 |
| `outputRateFollowsInterval` | 输出节拍遵守 `OUTPUT_INTERVAL_TICKS`（8 刻一件） |
| `outputPrefersDownwards` | 下方与上方都有容器时**先走下方**（`OUTPUT_ORDER` 的方向顺序） |
| `rodSlotIsNeverOutput` | 钓竿槽里的钓竿永远不进输出 |
| `hopperBelowReceivesItems` | 下方是漏斗（有方块实体、非大箱子分支）也照常进 |
| `doubleChestIsTreatedAsOneInventory` | **大箱子当一个库存用**：贴机器的那半塞满、另一半留空，输出照常进到另一半（不合并的实现会认为「满了」一件都搬不动） |
| `neighbourMachineIsNotAnOutputTarget` | 旁边的自动钓鱼机不收产物（`canPlaceItem` 只放行钓竿槽） |
| `containerFaceIsUsedForInsertion` | 堆肥桶在下方：物品从它的**顶面**进（可堆肥物品进得去） |
| `containerFaceRejectsWrongDirection` | 堆肥桶在上方：物品得从它的底面进，**不收**（入料面语义被钉死） |
| `fullContainerReportsOutputFull` | 所有相邻容器都满 → 停机原因 `OUTPUT_FULL`（不是 `CACHE_FULL`） |
| `nothingIsLostOrDroppedWhileBlocked` | 输出被堵期间物品不丢失、不掉地上（服务端权威） |
| `fishingResumesAfterContainerIsEmptied` | 箱子清空后机器**自动恢复**钓鱼（输出独立于状态机，不存在死锁） |
| `outputRunsOnTheNaturalTickLoop` | **不手动推刻**，等真实 tick 循环跑过输出间隔后箱子收到物品（方块实体的 ticker 接线被覆盖到，Phase 5 手动推刻模式的盲区） |

报告落在 `build/junit.xml`。

### 6.2 突变检验：6/6 被抓到

用 `tools/mutate_phase6.py` 自动注入 → 跑 `runGameTest` → 记录失败清单 → **全部还原**再跑下一轮
（第一版只还原「本轮要改的那个文件」，残留突变叠加后失败条数被夸大，已修正）：

| 突变 | 实测结果 |
| --- | --- |
| **M1** 大箱子不合并（直接返回半边方块实体） | `doubleChestIsTreatedAsOneInventory` 红 —— 正是这条测试存在的理由 |
| **M2** 不复制物品堆（源槽与目标容器指向同一个 `ItemStack`） | 7 条红 —— 引用共享是物品复制的经典源头，断言在数量守恒上 |
| **M3** 入料面方向传反（不取 `getOpposite()`） | 恰好 2 条红：`containerFaceIsUsedForInsertion` + `containerFaceRejectsWrongDirection` |
| **M4** 不排除「塞不进东西的容器」（去掉 `canReceive`） | 2 条红：`cacheFullReportsCacheFull`（Phase 5 的）+ `nothingIsLostOrDroppedWhileBlocked` —— 「没放容器」被误报成「容器已满」 |
| **M5** 输出时连钓竿槽一起遍历 | 2 条红：`rodSlotIsNeverOutput` + `fishingResumesAfterContainerIsEmptied` |
| **M6**（手动注入）`serverTick` 里不调 `outputTick` | 9 条红，含 `outputRunsOnTheNaturalTickLoop`：「自然 tick 跑了 10 刻，箱子还是空的」 |

M6 是额外验证（未进脚本）：它证明的不是某条逻辑写对了，而是**输出真的挂在
方块实体的 ticker 上** —— 这正是 Phase 5 手动推刻测试覆盖不到的那一层。

突变后 `grep -c MUTATION src/` 为 0，源码无残留。

### 6.3 客户端端到端 —— `./gradlew runClientGameTest`，**通过**

截图（`docs/screenshots/auto_fishing_machine_gui.png`，Phase 6 版）证实：
电量条「0 / 10.0K」、钓鱼进度条、红色「No fishing rod」、钓竿槽与 3×3 缓存格
布局无重叠、无溢出。

## 7. Phase 7 实测记录：界面细节与本地化（自动化 GameTest）

本阶段新增 `fishing.WaterVerdict`、`menu.MachineTooltips`、
`gametest.GuiGameTests`（8 条）、`tools/check_localization.py`、
`tools/check_resources.py`，并给正面加了「运行中」贴图。

### 7.1 服务端 GameTest —— `./gradlew runGameTest`，**47/47 通过**

```
========= 47 GAME TESTS COMPLETE IN 2.498 s ======================
All 47 required tests passed :)
```

47 = 8（菜单/搬运，Phase 4）+ 16（钓鱼状态机，Phase 5/7）+ 13（产物输出，Phase 6）
+ 8（提示内容与水域结论，Phase 7）+ 2（更早的其他项）。

> Phase 7 收档时是 45 条；§7.5 的节奏调参换掉 1 条、新增 1 条变成 46 条；
> §7.6 的饵钓缩放再新增 1 条，成为 47 条。

新增的界面层测试都跑在**服务端**上 —— 这是 3.16 分层带来的直接好处：
`MachineTooltips` 不依赖客户端类，所以「提示里写了什么」能逐字段断言
（`TranslatableContents#getKey()` + `#getArgs()`，与语言无关），例如：

* `energyTooltipUsesExactNumbers`：缓冲塞 4321 FE（刻意不取整数，
  否则「电量 ÷ 500」和「写死的数」会得出同样的干净商），断言提示三行的
  键与参数恰为 `4321 / 10000`、`500`、`8`；
* `waterVerdictMapsEveryWireValue`：线上字面量 `0/1/2` 逐个映射回
  `UNKNOWN/CLOSED/OPEN`，越界值退回 `UNKNOWN` 而不是抛异常；
* `menuShowsTheWaterVerdictTheMachineComputed`：机器自己算出的
  「封闭」结论，菜单读到的是同一个值 —— 判定与显示不会走岔；
* `gridCutWhileRunningStaysCautionary`：运行中剪断电网，
  状态行升级为 `CAUTION`（有措辞建议）而不是 `WARNING`（只是陈述）。

**`approachPhaseIsBeforeTheBite`（Phase 7 补，见 3.20）** —— 守的是「阶段名必须描述
真正在跑的那个计时器」。这个阶段曾叫 `HOOKED`、文案写「鱼咬钩了」，
而它实际覆盖的是咬钩**之前**的 `timeUntilHooked` 倒计时。
测试断言整轮周期内：处于 `APPROACHING` 时倒计时必然 `> 0`、且鱼必然还没进缓存，
并且这一轮里**确实观察到过**该阶段（否则阶段切换失效会让测试空转通过）。

它的突变验证（M7）：把 `if (timeUntilHooked <= 0)` 改成 `<= -5`，
即「计时器归零后再磨蹭 5 刻才收杆」—— 正是「比手动钓鱼还慢」的形态。
结果该测试当场失败，报出预定信息：

```
第 590 刻：处于 APPROACHING 但倒计时已归零（0）—— 说明计时器归零后机器还在磨蹭，
这一段正是「比手动钓鱼还慢」的来源
```

同时另 5 条计时相关测试也红了（该突变会拉长整个周期），符合预期。还原后 45/45 复绿。

> 写这条测试时踩的坑值得记一笔：第一版忘了插钓竿。
> `placeMachine` 只放方块与电池，不插竿，于是机器一直停在 `IDLE / NO_ROD`，
> 循环空转 2000 刻 —— 失败信息是「从未观察到 APPROACHING」。
> **那条 `observed > 0` 断言正是为这种情况留的**：没有它，循环体一次都不进，
> 测试会「通过」而什么都没验证。

### 7.2 本地化与资源的静态检查

两条检查脚本都已接入日常验证，突变自测证明它们**真的会抓错**：

**`tools/check_localization.py`** —— 四类检查：
两份语言文件键集合一致；枚举推导键齐全（**解析 Java 枚举常量名**，
不手抄清单，`PauseReason`/`FishingPhase`/`WaterVerdict` 各自成组）；
源码字面量键存在（是某个真实键的严格前缀也算过 —— 覆盖
`"gui.…status." + name()` 这类拼键）；占位符个数对齐且**只允许 `%s`**。

```
语言文件：en_us, zh_cn，en_us 共 34 个键
枚举推导键 23 个，源码字面量键 21 个（其中 5 个是拼键前缀）
本地化检查通过
```

突变自测 5/5 被抓到：删推导键 ×2、占位符 2→1、用 `%d` 代替 `%s`、
前缀拼错（`status`→`stauts`）。还原后 exit=0。

**`tools/check_resources.py`** —— 四类检查：blockstate 变体指向的模型存在；
模型 `parent`/`textures` 引用存在；`items/*.json` 嵌套模型存在；
**变体完整性 + 布尔属性两侧必须映射到不同模型**（后者正是 `ACTIVE` 属性存在的理由）。

```
检查了 7 个 JSON，共 16 条资源引用
资源检查通过
```

突变自测 4/4 被抓到：`active=true` 退回基础模型（4 条「属性在视觉上没有任何效果」）、
引用不存在的贴图、缺一个变体组合、指向不存在的模型。还原后 exit=0。

**`tools/gen_active_texture.py --check`** —— 「运行中」贴图是**派生**出来的
（指示灯 10 像素点亮 + 四邻 35% 晕染），`--check` 断言产物与「从底图重新派生」
逐像素一致，底图被重画而忘记重新生成时会当场失败。

### 7.3 客户端端到端 —— `./gradlew runClientGameTest`，**通过**

```
BUILD SUCCESSFUL in 39s
[ClientGameTest] 运行中方块截图：.../0000_auto_fishing_machine_block_active.png
[ClientGameTest] 界面截图：.../0001_auto_fishing_machine_gui.png
[ClientGameTest] 电量提示截图：.../0002_auto_fishing_machine_tooltip_energy.png
```

三张截图已归档到 `docs/screenshots/`，目视确认：

* `auto_fishing_machine_gui.png`：两行水域指示器 `Water / Unknown` 正确渲染，
  与电量条 `0 / 10.0K`、进度条、红色 `No fishing rod` 互不重叠；
* `auto_fishing_machine_tooltip_energy.png`：悬停电量条弹出三行
  `Stored: 0 / 10000 FE` / `Uses 500 FE per catch` / `Enough for 0 more catches`
  —— **精确数值**（非压缩短格式）与悬停机制同时得到验证；
* `auto_fishing_machine_block.png`：方块正面朝向正确，`ACTIVE` 贴图生效。

本轮还把原先一句只打印不判断的调试输出**升级成了真断言**
`assertActiveFlagReachedTheClient`：`/setblock` 在服务端执行，
客户端世界的方块状态要靠网络同步才看得到 —— 先证明
「此刻客户端认为 `ACTIVE` 确实是期望值」再看图，两张 A/B 图才说明得了
「贴图跟着状态变了」。开周期与恢复停机各断言一次，均通过。

### 7.4 刻意不做：指示灯自发光

`active=true` 的指示灯在游戏里偏暗。查清了：26.3 的 `light_emission`
是**元素级**（0–15，`CuboidModelElement`），不是面级，
要让灯自发光必须把它拆成独立 cuboid 元素。**决定不做** ——
代价是模型重构成多元素 + 负 `z` 薄片（z-fighting、`cullface`、新贴图三件事要处理），
收益却只是观感，对「能不能用、钓得对不对」零影响。
理由与取舍完整记录在 `docs/ARCHITECTURE.md` §3.19。

### 7.5 后续调整：把两个等待区间调快到「一杆约 3 秒」

**2026-10-03 · 用户实机测试后的显式要求**

用户实机测试（普通未附魔钓竿，未发现恶性 bug）后提出：等待鱼上钩约 2 秒、
鱼正在靠近约 1 秒，也就是一杆约 3~4 秒完成一次结算。

| | 调前 | 调后 |
| --- | --- | --- |
| 等待鱼上钩 | 100~600 刻（5~30 秒） | 30~50 刻（1.5~2.5 秒） |
| 鱼正在靠近 | 20~80 刻（1~4 秒） | 15~25 刻（0.75~1.25 秒） |
| 一杆合计 | 6~34 秒，均值 20 秒 | 2.25~3.75 秒，均值 3 秒 |

调前那组是**原版数值** —— 这次改动把「忠实照抄原版」换成了「主动调参」。
连带必须处理三件事：

1. **常量改名**：`VANILLA_MIN/MAX_LURE_WAIT` → `MIN/MAX_LURE_WAIT`（BITE 同理）。
   值一改，名字里的 `VANILLA_` 就是谎话。现在这个前缀被严格保留给
   「数值确实取自原版且未改动」的常量（两个速度概率 0.25F / 0.5F），
   于是「有没有前缀」本身就是一条可读的语义。
2. **一条测试必然失效**：`lureEnchantShrinksInitialWait` 用
   `MAX_LURE_WAIT - 3 × LURE_TICKS_PER_LEVEL` 当上限，新值下算出来是 **-250**。
   换成 `lureEnchantMapsLevelToTicks`，直接断言换算链的输出 `HookProbe.lureTicks()`
   （0 / 100 / 300）—— 那才是它原本要守的东西（`*20` 那次换算没被漏掉或重复），
   而且与等待区间无关。保底那一段的附魔等级改为**由常量推导**，不再写死 3 级，
   这样把区间放宽到超过 300 刻时断言仍然成立。
3. **新增** `bothWaitIntervalsMatchTheRequirement`：两个区间分别断言。

#### 突变检验（M8–M10）

第一版的新测试断言的是「掷出的值 ∈ `[MIN_LURE_WAIT, MAX_LURE_WAIT]`」——
**这是同义反复**：掷出的值本来就来自这两个常量，常量怎么改它都成立。
突变检验当场揭穿了它：

| 突变 | 第一版 | 修正后 |
| --- | --- | --- |
| M8 只把「等待鱼上钩」退回原版 100~600 | ❌ 这条新测试**照样通过**（当次唯一的红是 `lureEnchantMapsLevelToTicks`，因为它的「300 不低于区间上限」前提被破坏） | ✅ 红：`第 0 轮「等鱼上钩」掷出 221 刻，超出要求的 30~50 刻（约 2 秒 ±0.5 秒）` |
| M9 只把「鱼正在靠近」上限退回原版 80 | ❌ **46 条全绿，什么都没抓到** | ✅ 红：`第 0 轮「鱼正在靠近」掷出 45 刻，超出要求的 15~25 刻（约 1 秒 ±0.25 秒）` |
| M10 去掉 `Math.max(MIN_WAIT_TICKS, …)` 保底 | 未测 | ✅ 红：`饵钓 1 级缩短 100 刻，不低于区间上限 50 刻，等待应被压到下限 1，实际 0` |

修正是把期望值**锚在需求上**而不是读常量：等待 2 秒（40 刻）、靠近 1 秒（20 刻），
各留 ±0.5 秒 / ±0.25 秒的区间宽度。三次突变后还原，46/46 复绿。

> M10 的失败信息里「实际 0」值得留意：去掉保底后 `timeUntilLured` 变成负数，
> 状态机会每刻都落进「两个计时器都归零」的兜底分支重新开周期 ——
> 表现是机器**永远钓不上任何东西，而且不报任何错误**。

#### 由这次改动暴露的结论

* **真正的天花板是电**：500 FE/杆 ÷ 32 FE/t（CCG LOW 档）= 15.6 刻/杆 ≈ 0.78 秒。
  想让一杆比这更短，必须连 `MAX_INPUT` 的档位一起提，否则缓冲区耗尽后会退化成
  「攒够 500 FE 才开下一杆」，界面上的倒计时不再反映真实节奏。
* **饵钓的等级阶梯被压平**：每级缩短 100 刻，而整段等待只剩 30~50 刻，
  于是任何等级都压到下限 1 刻，I 级与 III 级无区别。
  当时的取舍是「有意保留原版换算」—— **这个取舍在 §7.6 被推翻并修掉了**。

### 7.6 饵钓等级阶梯恢复：`LURE_TICKS_PER_LEVEL` 100 → 8

§7.5 的「一杆 2 秒」把饵钓阶梯压平了：每级缩短 100 刻，而整段等待只剩 30~50 刻，
于是任何等级都压到下限 1 刻，I 级与 III 级跑出来一模一样。
这一节把它按区间等比缩到 **8 刻/级**，并让缩放**真正进入运算**。

#### 改之前的一个隐患：常量与实现是脱节的

`LURE_TICKS_PER_LEVEL` 在此之前**只出现在测试与注释里**，状态机读的是
`HookProbe.lureTicks()`（原版值，每级 100 刻）。也就是说这个常量名叫
「饵钓每级缩短的等待刻数」，却对运行时没有任何影响 —— 名实不符本身就是隐患：
改它不会改变行为，只会让测试的期望值跟着漂移，而这正是 M8/M9 那类错误的温床。
所以这次改的不只是把 100 换成 8，而是**把缩放接进运算**。

#### 改动

| | 改前 | 改后 |
| --- | --- | --- |
| `MachineConfig.LURE_TICKS_PER_LEVEL` | 100（= 原版换算，但没被使用） | **8**（真正生效） |
| `HookProbe` | 只有 `lureTicks()`（原版尺度） | 新增 `VANILLA_TICKS_PER_LURE_LEVEL = 100` 与 `lureTicksScaledTo(int)` |
| `beginWaiting` | `probe.lureTicks()` | `probe.lureTicksScaledTo(MachineConfig.LURE_TICKS_PER_LEVEL)` |

* `lureTicksScaledTo` 的语义是「先由原版值还原等级（原版恒为 `等级 × 100`，
  这次除法是精确的），再乘本模组的每级刻数」—— 换算链
  （`EnchantmentHelper` → 秒 → 刻）一步没改，换掉的只是那个标量系数。
* 效果：III 级共缩 24 刻，与区间宽度 20 刻同量级。于是 III 级（6~26 刻）
  与无附魔（30~50 刻）**完全不重叠**，等级越高越快；中间两级也各自区分
  （I 级 22~42、II 级 14~34）。
* 副作用：`MIN_WAIT_TICKS` 保底在 3 级以内不再被触发（最小仍有 6 刻）。
  它保留为防呆 —— 区间收窄或每级刻数调大时轮到它兜底。
  测试因此改用**由常量推导**的等级（当前算出来是 7 级）来覆盖这条分支。

#### 新增测试

`lureEnchantActuallyShortensTheWait`：多轮实测采样无附魔与饵钓 III 的掷出值，
断言 **III 级的最坏样本仍严格优于无附魔的最好样本**。
只推「常量之间满足某个不等式」说明不了状态机真的用了它 —— 那正是同义反复的老路，
所以这里断言的是一个与常量无关的不变量（两组区间不重叠），
期望区间写成需求字面量（30~50 刻、每级 8 刻）。

`lureEnchantMapsLevelToTicks` 相应扩成两侧断言：原版尺度 `0 / 100 / 300`
（守 `*20` 换算）与本模组尺度 `0 / 8 / 24`（守阶梯标量）。

#### 突变检验（M11–M13）

| 突变 | 结果 |
| --- | --- |
| M11 `LURE_TICKS_PER_LEVEL` 退回 100（阶梯重新压平） | ✅ 红：`lureEnchantActuallyShortensTheWait` —— **「饵钓III 的等待应落在 6~26 刻（每级缩 8 刻），实测 1~1 刻」** |
| M12 `lureTicksScaledTo` 直接返回原版值（缩放失效） | ✅ 红两条：`lureEnchantMapsLevelToTicks` —— **「缩放后：饵钓 1 级应当缩短 8 刻，实际 100」**；以及上面那条实测 |
| M13 去掉 `Math.max(MIN_WAIT_TICKS, …)` 保底 | ✅ 红：`lureEnchantMapsLevelToTicks` —— **「饵钓 7 级缩短 56 刻，不低于区间上限 50 刻，等待应被压到下限 1，实际 0」** |

三条全中，还原后 **47/47 复绿**。M11/M12 都靠 `lureEnchantActuallyShortensTheWait`
的实测区间抓到 —— 若只有常量推导式的断言，它们都会漏网（M11 尤其明显：
「常量与实现本来就脱节」这种改动，纯推导的断言根本无从察觉）。

## 8. 已知非问题

| 现象 | 为什么不算 bug |
| --- | --- |
| `/item replace block ... container.5` 能直接把鳕鱼塞进缓存槽 | 这是管理员命令，**按设计绕过一切准入规则**（对箱子同样如此）。玩家正常途径（漏斗 / 界面 / 快速移动）都走 `canPlaceItem`，已被 Phase 2 第 4、5 项与 Phase 4 的 GameTest 拦住 |
| `active=false/true` 两种方块状态映射到**不同**模型（Phase 6 起拆开，`_active` 只换正面贴图） | 指示灯在游戏里偏暗是已知的观感问题：26.3 的 `light_emission` 是元素级，做自发光需要重构模型。收益只是观感，**决定不做**（见 §7.4 与 `docs/ARCHITECTURE.md` §3.19） |
| 翻译参数只支持 `%s`，不支持 `%d`/`%f` | 26.3 的 `Component.translatable(key, Object... args)` 参数全是对象，`%d` 会直接抛异常。所有数值参数在 Java 侧先 `String.valueOf` 成字符串，翻译键里只写 `%s`（`tools/check_localization.py` 会拦住误用的 `%d`） |
| 进电实测是 16.8 FE/t 而不是满额的 32 FE/t | 电网的 `dispatchTransfer` 会把额度按「剩余目标数」均摊，且两端都受 32 FE/t 限制；瞬时速率低于名义上限是正常调度结果，不会丢电（已用守恒核对确认） |

## 9. Phase 8 待测清单

来自需求文档「十四、开发流程」的第 8 阶段，完成一项勾一项：

- [x] 普通钓竿（Phase 5 `singleCatchConsumesEnergyAndDamagesRod`：产出属于原版钓鱼战利品表、耐久 −1）
- [x] 饵钓附魔（`lureEnchantMapsLevelToTicks` + `lureEnchantActuallyShortensTheWait`，见 §7.5 / §7.6）
- [ ] 海之眷顾附魔（`withLuck` 链路已接通，产物偏移的统计验证留在 Phase 8 加大样本）
- [x] 耐久附魔（Phase 5 `unbreakingSlowsDurabilityLoss`）
- [x] 钓竿损坏（`RodWear` 走原版 `hurtAndBreak`；断竿音效路径已实现，长周期实测留 Phase 8）
- [x] 电力不足（Phase 5 `insufficientPowerReportsNotEnoughPower`）
- [x] 电网断开（Phase 3 电力层 + Phase 5 `disconnectedGridReportsDisconnected` / `abortingCycleRefundsPreChargedEnergy`）
- [x] 无开放水域（Phase 5 `noWaterReportsNoWater` + `closedWaterNeverYieldsTreasure`）
- [x] 无相邻箱子（Phase 6 `neighbourMachineIsNotAnOutputTarget` + `fullContainerReportsOutputFull` / `nothingIsLostOrDroppedWhileBlocked`：「没容器」报 `CACHE_FULL`、不丢物品）
- [x] 箱子已满（Phase 6 `fullContainerReportsOutputFull` + `fishingResumesAfterContainerIsEmptied`：报 `OUTPUT_FULL`、清箱自动恢复）
- [x] 大箱子（Phase 6 `doubleChestIsTreatedAsOneInventory`：`ChestBlock.getContainer` 合并 54 格）
- [x] 机器破坏（Phase 2 第 7 项）
- [x] 服务器重启（Phase 2 第 8、9 项；Phase 3 持久化 3 项）
- [ ] 区块卸载和重新加载（Phase 5 已把状态机完整写入 NBT 且**不重置阶段**，避免区块卸载吞掉预扣的电；实测留 Phase 8）
- [ ] 多台机器同时运行
- [x] 玩家退出 GUI（Phase 4 `stillValid` + Phase 5 状态机与 GUI 解耦：周期由方块实体推进，界面开关不影响它）
- [ ] 多人同时操作
