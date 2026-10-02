# 架构

> 本文档随开发推进逐步补全。当前覆盖到 **Phase 7（界面细节与本地化完善）**。

## 1. 总体分层

本模组是 CrimsonCopperGrid 的**附属 Mod**：不自带电网、不自带能量 API，
机器通过继承上游的机器基类接入它的电力体系。

```
net.minecraft...BlockEntity
  └─ com.cyx.crimsoncoppergrid.common.blockentity.MachineBaseBlockEntity
     │  （上游：tick 调度 / FACING+ACTIVE / 20 刻合并同步 / onPlace+onBreak / Container 骨架）
     └─ com.cyx.crimsoncoppergrid.common.powerSystem.PowerAcceptorBlockEntity
        │  （上游：SimpleSidedEnergyContainer 能量缓冲 / 六向推流 / 电量存档 / ContainerData）
        └─ com.cyx.cyxautofishingmachine.blockentity.AutoFishingMachineBlockEntity
           （本模组：钓竿槽 + 9 缓存槽 + 电力基准值 + 存档）

net.minecraft...BaseEntityBlock
  └─ com.cyx.crimsoncoppergrid.common.blocks.BlockMachineBase
     │  （上游：放置朝向 / 旋转镜像 / 右键开界面 / 服务端 ticker 调度 / 比较器）
     └─ com.cyx.cyxautofishingmachine.blocks.AutoFishingMachineBlock
        （本模组：只做「方块 ↔ 方块实体」绑定）
```

**继承结构是跟着源码分析结果走的，不是照抄需求文档的建议类图。**
`MachineBaseBlockEntity` / `PowerAcceptorBlockEntity` / `BlockMachineBase` 这三个上游基类
已经覆盖了需求里「机器基础方块 / 机器基础 Block Entity / 电力缓冲 / Tick 处理」的全部职责，
重写一遍只会制造两套行为不一致的机器。详见 `docs/UPSTREAM_ANALYSIS.md`。

## 2. 类清单（Phase 2）

| 类 | 职责 |
| --- | --- |
| `CyxAutoFishingMachine` | 主入口。按 `方块 → 方块实体 → 菜单 → 创造栏` 的顺序触发注册 |
| `init.ModBlocks` | 注册方块 + 方块物品（写法对齐上游 `init.ModBlocks`） |
| `init.ModBlockEntities` | 注册 `BlockEntityType`（26.3 没有 `Builder`，直接 new） |
| `init.ModMenuTypes` | 注册 `MenuType`（26.3 构造器被 Fabric 拓宽为 protected，直接 new） |
| `init.ModCreativeTab` | 自建创造模式物品栏 |
| `init.ModTags` | `fishing_rods` 物品标签（扩展「兼容钓竿」用） |
| `config.MachineConfig` | 全部可调参数（容量 / 能耗 / 等待时间 / 槽位编号 / 检测间隔），**不允许在别处写魔法数字** |
| `power.GridLink` | 电网连接检测（只读、可在 tick 之外调用） |
| `fishing.FishingPhase` | 三态：`IDLE / WAITING / APPROACHING`，与原版两个计时器一一对应（`APPROACHING` 覆盖 `timeUntilHooked`，是咬钩**之前**的阶段，见 3.20） |
| `fishing.PauseReason` | 停机原因枚举：`NO_ROD / NO_WATER / CACHE_FULL / GRID_DISCONNECTED / NOT_ENOUGH_POWER / OUTPUT_FULL`（id 只增不改，见 3.14）。另有 `translationKey()`（是什么）与 `hintKey()`（该怎么办）两个键，见 3.17 |
| `fishing.WaterVerdict` | 开放水域结论枚举 `UNKNOWN / CLOSED / OPEN`。线上仍是 0/1/2，方块实体里的三个常量改为委托本枚举，**判定结果搬运到界面的唯一出口**（见 3.15） |
| `fishing.FishingSpot` | 下钩点 record：水柱顶端那一格 + 原版流体高度公式算出的水面高度 |
| `fishing.WaterFinder` | 决定浮标扔到哪儿：沿正面量连续水面段、取中点（见 3.10） |
| `fishing.HookProbe` | 「不进入世界」的浮标替身：借原版算附魔参数与开放水域判定（见 3.8）；饵钓的区间缩放也在这一层（`lureTicksScaledTo`，见 3.20） |
| `fishing.LootRoller` | 按原版 `retrieve` 的参数掷 `minecraft:gameplay/fishing` 战利品表 |
| `fishing.RodWear` | 钓竿磨损 + 修补：先扣耐久、再用本杆经验修，顺序与原版一致 |
| `inventory.AdjacentInventory` | 相邻容器查找 + 逐件塞入：方法与原版 `HopperBlockEntity` 逐句对应（见 3.14） |
| `mixin.FishingHookAccessor` | 借原版两个私有成员（`openWater` 字段、`calculateOpenWater` 方法），不照抄判定逻辑 |
| `blocks.AutoFishingMachineBlock` | 方块层，刻意做薄 |
| `blockentity.AutoFishingMachineBlockEntity` | 机器状态：物品栏 + 电力基准 + 电网连接缓存 + **钓鱼状态机** + 界面数据 + 存档 |
| `menu.AutoFishingMachineMenu` | 界面的服务端/客户端共用逻辑：槽位布局 + 快速移动规则 + 阶段/进度读取 + 水域结论读取 |
| `menu.MachineTooltips` | **工具提示的内容层**：状态 / 电量 / 进度 / 水域四组提示的文本与严重级。刻意不依赖任何客户端类，服务端 GameTest 能逐字段断言（见 3.16） |
| `client.screen.AutoFishingMachineScreen` | 界面绘制：电量条 + 钓鱼进度条 + 水域指示器 + 状态一句话 + 悬停命中测试 |
| `gametest.MenuGameTests` | 服务端自动化测试：界面与物品搬运（`runGameTest`） |
| `gametest.FishingGameTests` | 服务端自动化测试：钓鱼状态机 13 条（`runGameTest`） |
| `gametest.OutputGameTests` | 服务端自动化测试：产物输出 13 条（`runGameTest`） |
| `gametest.GuiGameTests` | 服务端自动化测试：提示内容与水域结论 8 条（`runGameTest`） |
| `client.gametest.AutoFishingMachineClientGameTest` | 客户端端到端测试（`runClientGameTest`，能打开界面并截图） |

## 3. 关键设计决策

### 3.1 电力：靠上游的 fallback 自动接入，不注册任何能力

上游 `ModPowerRegistration` 注册的是一条 `EnergyStorage.SIDED.registerFallback(...)`，
判定条件是 `blockEntity instanceof PowerAcceptorBlockEntity`。
本模组的方块实体继承它，于是**自动**成为电网里的合法储能节点：

- 不需要写一行能量能力注册代码；
- 「电网断开」由上游电缆的推流结算自动覆盖；
- 每面进电速率、事务回滚（不会出现半截转账）都沿用上游实现。

风险与对策（上游若把 fallback 改成逐个注册会静默断链）见 `docs/UPSTREAM_ANALYSIS.md`。

### 3.2 方块破坏掉物：用原版机制，不自写掉落代码

26.3 的 `BlockEntity.preRemoveSideEffects(pos, state)` 默认实现是：

```java
if (this instanceof Container container && this.level != null) {
    Containers.dropContents(this.level, pos, container);
}
```

它由 `LevelChunk.setBlockState` 在**任何**移除路径（玩家挖掘、爆炸、活塞、`/setblock`）上调用。
因此只要方块实体实现 `Container` 并返回真实物品，里面的东西就会自动掉出。

⇒ **在方块或方块实体里再写一遍 `dropContents` 会导致每件物品掉两份**，所以刻意不写。
（`BlockMachineBase.playerWillDestroy → onBreak` 钩子留给后续阶段做状态清理用。）

### 3.3 槽位准入：缓存槽不接受玩家放入

`canPlaceItem` 只放行「钓竿槽 + 是钓竿」。缓存槽是机器自己的产物暂存区：

- 放行会让玩家把它当免费箱子，绕过「缓存满 → 停机」的设计；
- 放行还会让玩家手动塞入的物品误触发停机判定；
- 取出不受限制，避免玩家取不出放进去的东西。

钓竿的判定是「`instanceof FishingRodItem` 或 打上 `cyxautofishingmachine:fishing_rods` 标签」，
两条分支分别覆盖继承原版类的模组钓竿和自研钓竿逻辑的模组钓竿（数据包即可扩展）。

### 3.4 同步节流

电量变化走上游的 `syncWithAll()` —— 它**不是立刻发包**，
而是把请求合并进「最长 20 刻一次」的同步窗口。
进电是每刻发生的事，若不节流，几十台机器就能把玩家带宽打满。

### 3.5 电网连接检测（`GridLink`）

推流模型下设备**收不到「电网断了」的通知**，只能自己去问。判定规则：

| 邻居 | 判据 | 理由 |
| --- | --- | --- |
| 导线 / 电闸 | `CableBlockEntity#conducts()` | 只读方块状态；不导通 = 电闸切断 = 断链 |
| 其它设备 | `EnergyStorage#SIDED` 查到且 `supportsExtraction()` | 「它能把电给我」——发电机、电池、第三方储能 |

**为什么导体那一支不能用 `supportsExtraction()`**（这一条是读源码才发现的）：
`SimpleSidedEnergyContainer` 把 `supportsExtraction()` 实现为 `getMaxExtract(side) > 0`，
而导线的 `getMaxExtract` 还乘了 `allowTransfer(side)`，后者检查 `blockedSides` ——
「这个方向刚搬过电，本轮不允许反向操作」的记账位。

问题在于这个记账位**在下一轮网络结算开头才清零**，不是本 tick 结尾。于是「刚给我们送过电」
的那个方向，在该 tick 之后的任何时刻去查都可能读到 0。拿它当判据，
机器会在「已连接 / 已断开」之间按双方 tick 的先后顺序随机横跳。

检测结果缓存在方块实体里，每 `MachineConfig.GRID_RECHECK_INTERVAL`（20 刻 = 1 秒）重算一次，
并写入 NBT —— 后者不是为了持久化（派生值 20 刻内必定重算），而是为了可排查：
服主遇到「机器不动」，一条 `/data get block <坐标> GridLinked` 就能区分
「没接电」和「接了电但没电」。

> 注意 `isGridLinked()` 只回答「接上了没」，不回答「有没有电」。
> 一张接通了但发电机停机的电网仍然是 `true`，那属于「电量不足」，由状态机另行判断。

### 3.6 界面：三层各管一件事，槽位准入只有一个真相源

```
方块层   BlockMachineBase（上游）   —— 空手右键 → 问方块实体要 MenuProvider（一行都不用写）
方块实体 MenuProvider + ContainerData —— 界面数据的服务端权威：get() 直接读真实字段
菜单层   AutoFishingMachineMenu       —— 槽位布局 + 快速移动；准入全部委托 canPlaceItem
界面层   AutoFishingMachineScreen     —— 只画本机特有的电量条与电网状态
```

三个刻意的设计：

- **方块类不写界面代码。** 上游 `BlockMachineBase#getMenuProvider` 已经把
  「方块实体实现了 `MenuProvider` 就能右键打开」做成通用行为，新机器只需要实现接口。
- **槽位准入只有一份规则。** 菜单的 `Slot#mayPlace` 委托给 `Container#canPlaceItem` ——
  漏斗和界面走同一个判定。两套规则迟早分叉，分叉就是复制漏洞。
- **`ContainerData.get()` 必须读真实字段。** 服务端每 tick 拿它和上次发的快照比对，
  变了才发包；返回快照副本会让比对永远相等，界面就再也不会更新。

### 3.7 快速移动的合并漏洞（GameTest 实测发现）

原版 `AbstractContainerMenu#moveItemStackTo` 的循环里，「放进空槽」那一支会调
`Slot#mayPlace`，而「**合并进已有同类物品堆**」那一支**不做任何准入检查**。
对原版容器无害，但对缓存槽（只出不进）是一条侧信道：玩家能把同类物品合并进去，
绕开 `canPlaceItem` —— 危害不是复制（总数守恒），而是把缓存当免费箱子、
让「缓存满 → 停机」的保护失效。

⇒ `AutoFishingMachineMenu` 覆写了 `moveItemStackTo`，与原版逐句对应，
唯一差别是合并分支多一次 `slot.mayPlace(stack)`。
这条漏洞是 GameTest `quickMoveRejectsNonRodIntoMachine` 第一次跑就抓到的，
不是读代码读出来的 —— 修复前后的对比就是这条测试的红/绿。

### 3.8 钓鱼：用「不进入世界的浮标替身」，不生成真实实体

看起来最省事的是把浮标真的扔进水里让原版自己 tick。但那条路有三处绕不过去：

| 坑 | 原因 |
| --- | --- |
| 浮标会自毁 | `FishingHook#shouldStopFishing` 要求所有者是**真实玩家且手上拿着钓竿**。机器没有玩家，浮标第一刻就没了 |
| 产物路径不同 | 原版 `retrieve` 生成的是**掉落物实体**并让它飞向玩家；需求要求产物进内部缓存、再自动输出到相邻容器 |
| 规模化浪费 | 浮标实体要进区块、参与实体 tick、还要被渲染。一台机器一个浮标，几十台机器就是几十个无意义实体 |

⇒ `HookProbe` 只 `new` 一个 `FishingHook` 对象（`fabric-transitive-access-wideners`
已把那个构造器拓宽为 public），**从不 `addFreshEntity`**。它借三样原版的东西：

1. **附魔参数** —— `luck`（海之眷顾）与 `lureSpeed`（饵钓）。原版在构造浮标时
   用 `EnchantmentHelper` 算好并钳到非负，这里用 Mixin 读回那两个字段，
   **不自己再算一遍**，避免两处算法漂移；
2. **开放水域判定** —— `@Invoker calculateOpenWater`。判定逻辑不照抄：
   借原版的方法，原版改了我们也跟着对；
3. **战利品表的 `THIS_ENTITY`** —— 原版 `fishing.json` 的宝藏条目挂着
   `type_specific/fishing_hook.in_open_water` 谓词，它把实体转成 `FishingHook`
   再读 `isOpenWaterFishing()`。把替身传进去，等价于把「这片水算不算开放」
   直接告诉原版战利品表 —— **「封闭水域拿不到宝藏」是原版把关的，本模组一行判定代码都没写**。

### 3.9 开放水域只在两处判定：下钩点变了、收杆那一刻

原版每刻做 `openWater = openWater && calculateOpenWater(...)`（取与），
因为它的浮标会被玩家拽着到处移动。本模组的下钩点是**静止**的，
同一片水每刻的结论必然相同，取与退化成单次判定。

而一次判定要扫 5×5×4 = 100 格方块。每刻做正是需求明令禁止的
「每 tick 大范围扫描」。两者结论一致，代价差 100 倍。

⇒ `refreshSpot()` 在「下钩点变了」时重算（界面上「这片水能不能出宝藏」
永远是当前地形的答案），`resolveCatch()` 在收杆那一刻重算（那一杆的产物要如实反映当时的水域）。

### 3.10 浮标落在水面段的中点，而不是紧贴机器的那格水

`WaterFinder` 第一版返回「紧贴机器的那格水」，被开放水域判定当场否掉：
机器摆在岸边时，紧贴它的那格水必然也贴着岸，±2 的范围一伸出去就撞上陆地，
**结论永远是「非开放」，这台机器永远钓不到宝藏**。

⇒ 改成：沿机器正面方向量出连续水面段（`MAX_CAST_DISTANCE = 16` 内），
把浮标扔到这段水面的**中点**。水面越宽中点离岸越远，±2 范围越可能全是水 ——
这正是原版「离岸越远越可能出宝藏」那条因果关系的几何答案。
正面没水时依次试两侧、背面，最后退到「机器自己悬在水面上」的竖直查找。

> 这是一条**设计决策**而非原版规则（原版由玩家瞄准）。
> 界面会如实显示判定结果，玩家看到「非开放」就知道该把机器往水面中间挪。

### 3.11 电是预扣的，中止才退还

开周期前先 `tryUseExact(ENERGY_PER_CATCH)` 预扣。**周期内不再检查电网与电量**：
已经开始的周期即使中途断线、被抽空也照样走完，不会出现
「钓到一半电没了，这次白等」这种反直觉结果。代价是周期**被中止**时（钓竿被拿走、
水没了、缓存满了）必须把那 500 FE 退回来 —— 那一杆没有产出，不退等于收钱不办事。

停机原因的检查顺序也是设计过的：`钓竿 → 水域 → 缓存 → 电网 → 电量`。
一台没放钓竿又没接电的机器，说「没钓竿」比说「没接电」有用得多。

### 3.12 修补（Mending）：先扣耐久、再用经验修

原版 `ExperienceOrb#repairPlayerItems` 的做法是
`EnchantmentHelper.has(stack, REPAIR_WITH_XP)` + `modifyDurabilityToRepairFromXp`。
机器把这一杆的 1~6 点经验「代收」喂给修补。

**顺序不能反**：若先修补再扣耐久，耐久永远不会降到 0 以下，
钓竿就永远不会断 —— 那不是原版行为，是把 Mending 写成了无敌。
`RodWear.apply` 与原版一致：`hurtAndBreak` 走在前、修补走在后，
所以耐久恰好剩 1 点时钓竿照样断（修补救不了这一杆）。

### 3.13 `ContainerData` 不能传负数

`ClientboundContainerSetDataPacket` 按无符号 16 位编解码，
传 `-1` 到客户端会读成一个很大的正数。于是开放水域状态用
`0 = 未知 / 1 = 非开放 / 2 = 开放` 三个非负值，而不是更直觉的 `-1/0/1`。

### 3.14 产物输出：独立于状态机、逐件搬运、大箱子合并

实现是 `inventory.AdjacentInventory`（查找 + 准入）加
`AutoFishingMachineBlockEntity#outputTick`（节拍与接线），
方法与原版 `HopperBlockEntity#getBlockContainer` / `addItem` 逐句对应。

**① 输出不挂在钓鱼状态机上。** `serverTick` 里 `outputTick(level)` 排在
`tickFishing(level)` 之前、无条件执行 —— 机器停着也在搬。
若把输出写进状态机，「缓存满 → 停机 → 不搬 → 永远满」是一条死锁。
输出与钓鱼唯一共享的是缓存槽本身，搬走一件就是给钓鱼腾位。

**② 逐件搬运，8 刻一件。** `OUTPUT_INTERVAL_TICKS = 8` 取原版漏斗的
`MOVE_ITEM_SPEED`，玩家可感知的承诺就是「和一根漏斗一样快」。
不搬整堆：堆肥桶这类容器把每件当独立物品处理（`getMaxStackSize() == 1`），
整堆塞入会凭空吞掉多余物品；逐件还能在任意一件后停下，永不超容量。
最快产出（饵钓 III + 下雨：等待被压到 6 刻、靠近 15 刻，速度 2 各折半）约 11 刻一杆，
8 刻一件即 1.37 倍余量；
就算全部堵住，9 格缓存（最多 576 件）也是十几秒级的缓冲。

**③ 大箱子必须自己合并。** 26.3 的 `ChestBlockEntity#getContainerSize()`
硬编码返回 27 —— `level.getBlockEntity(pos)` 拿到的永远只有一半。
要 54 格必须走 `ChestBlock.getContainer(ChestBlock, BlockState, Level, BlockPos, true)`，
它内部走 `DoubleBlockCombiner`，双箱时返回 `CompoundContainer`。
查找顺序照抄漏斗：先 `WorldlyContainerHolder`（堆肥桶等**没有方块实体**的容器，
容器对象是现造的），再查方块实体，最后换大箱子合并。

**④ 入料面语义：`face = direction.getOpposite()`。** 物品从机器往 `direction` 走，
进入的是目标容器**朝向机器的那个面**。原版里堆肥桶是少数严格区分入料面的容器
（只有顶面进料、只收 `COMPOSTABLE` 组件），两条 GameTest 拿它当探针把这个语义钉死：
`containerFaceIsUsedForInsertion`（堆肥桶在下方，顶面进得去）、
`containerFaceRejectsWrongDirection`（堆肥桶在上方，底面不收）。

**⑤ `OUTPUT_FULL` 与 `CACHE_FULL` 分开报。** 查找时用
`canReceive`（该面有可用槽位）先排除「塞不进任何东西的容器」——
上游机器基类实现的是 `Container` **占位骨架**（`getContainerSize()` 恒为 0），
电池就贴在机器上方，不排除它会把「没放容器」错报成「容器已满」。
之后：有容器且没满 → `CACHE_FULL`（正常塞、马上能恢复）；
有容器但全满 → `OUTPUT_FULL`（界面上是另一句话，玩家知道该清箱子了）；
连容器都没有 → `CACHE_FULL`。两条原因的 id 在 `PauseReason` 里只增不改。

**⑥ 相邻机器天然不是输出目标。** `insertOne` 走的
`Container#canPlaceItem` + `WorldlyContainer#canPlaceItemThroughFace`
与漏斗、界面是同一套准入（3.3/3.6 的单一真相源第二次发挥作用）：
本模组自己的 `canPlaceItem` 只放行钓竿槽 + 是钓竿。

**⑦ 有意不做「实体容器」分支。** 原版漏斗还会找箱子船 / 箱子矿车，
那条分支会 `level.getRandom().nextInt(entities.size())` 消耗世界随机数 ——
「这一杆钓上来什么」不该受旁边有没有箱子船影响，两条互不相干的逻辑被耦合。

### 3.15 水域判定：结论走枚举，界面如实显示

开放水域的判定本身在 3.9 就定下了（只在两处算、结果存 `DATA_OPEN_WATER`）。
Phase 7 解决的是**它怎么走到界面**。

原先方块实体里是三个并列的 `int` 常量 `OPEN_WATER_UNKNOWN/CLOSED/OPEN`，
界面上则是一串 `if (value == 1) ... else if (value == 2) ...`。
这种写法的问题不是难看，而是**没有强制力**：常量改序、下标写错一格、
界面忘了处理新加的第四种取值，编译器一句话都不会说。

现在收成 `fishing.WaterVerdict`：

```java
public enum WaterVerdict {
    UNKNOWN(0), CLOSED(1), OPEN(2);

    public int state()            { return state; }
    public boolean treasurePossible() { return this == OPEN; }
    public String valueKey()      { return "gui.cyxautofishingmachine.water." + name().toLowerCase(Locale.ROOT); }
    public String tooltipKey()    { return "gui.cyxautofishingmachine.tooltip.water_" + name().toLowerCase(Locale.ROOT); }
    public static WaterVerdict fromState(int state) { /* 越界退回 UNKNOWN，不抛异常 */ }
}
```

三个要点：

**① 常量改为委托，而不是抄一份。** 方块实体里仍然是
`public static final int OPEN_WATER_CLOSED = WaterVerdict.CLOSED.state();` ——
**不写字面量**。这样「线上格式是 0/1/2」这件事在全项目只有一个定义处，
Phase 2 存档里已经写下的值不可能因为这次重构而错位（有 GameTest 钉住字面量）。

**② `fromState` 越界不抛异常。** 它跑在渲染路径上：一个来自旧存档或被改坏的
`ContainerData` 值，不应该让玩家「打开界面就崩」。退回 `UNKNOWN` 是诚实的选择 ——
界面上显示「未知」正是当时的真实情况。

**③ 界面把判定结果**如实**显示，不美化。** 需求要求
「界面会如实显示判定结果，玩家看到『非开放』就知道该把机器往水面中间挪」。
所以 `CLOSED` 就写 `Closed`（中文「封闭」），不写成「需要更多水域」这种
把结论翻译成建议的措辞 —— 结论和建议是两件事，后者放在工具提示里（见 3.18）。

### 3.16 提示内容与提示渲染分层

这一条是本阶段最重要的结构决策：**`menu.MachineTooltips` 里只有文本，没有渲染。**

```java
public enum Severity { NORMAL, WARNING, CAUTION }
public record StatusLine(Component text, Severity severity) {}

public static StatusLine statusLine(AutoFishingMachineMenu menu);
public static List<Component> status(AutoFishingMachineMenu menu);
public static List<Component> energy(AutoFishingMachineMenu menu);
public static List<Component> progress(AutoFishingMachineMenu menu);
public static List<Component> water(AutoFishingMachineMenu menu);
```

这个类**一个客户端类都不 import**。它只用 `Component` 和菜单的 getter，于是：

* **服务端 GameTest 能逐字段断言**「提示里写了什么」——
  断言的是 `TranslatableContents#getKey()` 与 `#getArgs()`，
  也就是键和参数，**与玩家用什么语言无关**（中文环境下断言英文键同样成立）；
* **客户端只负责「弹不弹、弹在哪、看得清不清」**，
  由 `AutoFishingMachineClientGameTest` 驱动 + 截图肉眼确认。

好处是测试成本的不对称被拉平了：文案是最容易改、最容易改错的部分，
现在它有了最便宜、最精确的测试（服务端、毫秒级、可断言到参数）；
而渲染是最贵、最难自动判断的部分（要靠截图），
让它只承担「弹出来了」这一个问题。

**文案里的精确数值只放在这里。** 面板上电量条只有 66px 宽，
只能画压缩短格式（`4.3K/10.0K`）；完整数值与单位
（`Stored: 4321 / 10000 FE`）放进提示 —— 那里不受宽度限制。
这也是 Phase 5 写 `drawEnergy` 时就预留好的落点。

### 3.17 状态行只有一个判断

界面上的状态行（`No fishing rod` 那句话）和悬停它弹出来的提示，
**必须说同一件事**。两份独立的 `switch` 迟早会走岔：
有人在界面那侧加了一个新原因，忘了在提示那侧也加，于是
「面板说电量不足、提示说没有钓竿」——这种 bug 用户会看见，测试很难盯住。

所以把优先级判断抽成一个私有枚举，两边共用：

```java
private enum Situation { PAUSED, RUNNING_UNPOWERED, RUNNING }

private static Situation situationOf(AutoFishingMachineMenu menu) {
    if (menu.getPhase() == FishingPhase.IDLE) return Situation.PAUSED;
    return menu.isGridLinked() ? Situation.RUNNING : Situation.RUNNING_UNPOWERED;
}
```

`statusLine(menu)` 返回「是什么」（面板上画的那一行 + 严重级），
`status(menu)` 返回「是什么 + 该怎么办」（提示里的多行）。
两者都从 `situationOf` 出发，优先级**只写了一份**。

**「停机的具体原因」这一层不重复实现。** 面板上那一行就是
`PauseReason.translationKey()` 翻出来的，提示的第一行直接复用同一个 `Component`
——不是重新按 `Situation` 拼一遍。这样新增停机原因时，
只要 `PauseReason` 加了枚举项，两处同时就有了。

### 3.18 水域指示器：常驻，且拆两行

**① 常驻，不藏进悬停。** 它是**选址结论**，不是细节。
玩家放下机器后第一件想确认的事就是「我摆对地方了吗」。
把它做成悬停才能看到，等于把最重要的信息设成需要猜 ——
而且它只可能出现在「机器还没跑起来」的时候，恰恰是最需要即时反馈的时刻。

**② 拆两行。** 一行里同时放表头和结论，可用宽度只有 66px
（`WATER_MAX_WIDTH = CACHE_X - WATER_X - 2`）。英文 `Water: closed` 实测 66px
——正好贴边，任何一次措辞改动都会溢出到缓存槽上。拆成表头一行、结论一行后，
最长的 `Unknown` 只有 44px，余量翻倍：

```
Water            ← WATER_X, WATER_Y
Closed           ← WATER_X, WATER_VALUE_Y
```

**③ 「宽度够不够」是被断言的，不是靠目测。** `AutoFishingMachineClientGameTest`
里有一条 `assertWaterIndicatorFits`：它**遍历 `WaterVerdict.values()`**，
把每个 `valueKey()` 交给 `font.width(...)` 量一遍，超宽就当场失败。
将来加一种新结论、或把英文改成更长的词，失败会发生在测试里，
而不是发生在某个语言玩家的屏幕上。

**④ 「该怎么办」放在提示里。** 结论占 `water.valueKey()`，
建议占 `water.tooltipKey()`。封闭时提示第二行是「移向水面中间」——
面板上放不下（也放不下第二语言），提示里放得下。

### 3.19 刻意不做：指示灯的 `light_emission` 自发光

Phase 6 收尾时观察到 `active=true` 的正面指示灯在游戏里偏暗，
查清了原因也找到了做法，但**决定不做**：

* 26.3 的 `light_emission` 是**元素级**（`CuboidModelElement.FIELD_LIGHT_EMISSION`，
  取值 0–15），不是面级。指示灯那 10 个像素（右侧 `x=14, y=2..3` 竖条 +
  底部 `x=4..11, y=13` 横条，两块互不相连的矩形）要单独自发光，
  必须把它们从主立方体里拆出来，做成两块贴着正面的薄片元素，
  给它们加 `"light_emission": 15`；
* 代价是模型从「单立方体」变成「多元素 + 负 `z` 薄片」，
  要处理共面 z-fighting、`cullface` 语义（薄片也得跟随正面朝向被剔除，
  否则会悬空漂在相邻方块前）、以及一块新贴图或受限 UV；
* **收益只是观感**：机器能不能用、界面对不对、钓鱼正不正常，
  这一条都不影响。而 `ACTIVE` 的**功能**层面已经在可接受的成本下验完了 ——
  blockstate → 模型 → 贴图 这条链路有静态检查（`tools/check_resources.py`）
  与运行时 A/B 截图两层证据（见 `docs/TESTING.md` §7）。

结论：先把「能正常运行、核心功能完整」收口；自发光属于纯美化，
真要做得单独排一期，不应该挤在功能阶段里顺带做掉。

### 3.20 钓鱼的时间账：区间是主动调参的，机制是照抄原版的

界面上能看到的两个倒计时，对应原版 `FishingHook#catchingFish(BlockPos)` 的两个计时器。
**机制逐句照抄，两个区间的时长则是本项目主动调短过的**：

| 阶段 | 对应原版字段 | 本模组取值 | 原版取值 |
| --- | --- | --- | --- |
| `WAITING`「等待鱼上钩」 | `timeUntilLured` | 30~50 刻（1.5~2.5 秒），再减 `lureSpeed` | 100~600 刻（5~30 秒） |
| `APPROACHING`「鱼正在靠近」 | `timeUntilHooked` | 15~25 刻（0.75~1.25 秒） | 20~80 刻（1~4 秒） |

于是**一杆约 2.25~3.75 秒、均值 3 秒**（原版是 6~34 秒、均值 20 秒）。
四个上下限都在 `MachineConfig` 的「钓鱼节奏」一节里，改一行就生效 ——
状态机只在 `beginWaiting()` 与 `advanceCycle()` 各读一次，没有第二处硬编码。

两个倒计时都按原版规则逐刻递减：

```java
int fishingSpeed = 1;
if (random.nextFloat() < 0.25F && level.isRainingAt(above))  fishingSpeed++;
if (random.nextFloat() < 0.5F  && !level.canSeeSky(above))   fishingSpeed--;
```

**① 「比手动慢」的错觉曾来自一处文案 bug（已修）。**
`timeUntilHooked` 的倒计时覆盖的是鱼**朝浮标游过来、但还没咬**的那段时间，
咬钩（`FISHING_BOBBER_SPLASH` 水花音效 + `DATA_BITING = true`）
恰好发生在它归零那一刻。原版在这个阶段的粒子能直接看出方向：
`fishX = getX() + sin(fishAngle) * timeUntilHooked * 0.1F` —— 鱼的位置随倒计时减小而收敛到浮标上。

而这个阶段原先叫 `HOOKED`、界面写「**鱼咬钩了**」。玩家看到
「鱼咬钩了 · 剩余 2 秒」，自然会以为机器在咬钩之后又磨蹭了 2 秒 ——
那段时间其实**就是**同一段等待，只是手动钓鱼时没有人会在屏幕上写「已经咬钩了」。
现在改名为 `APPROACHING`、文案改为「鱼正在靠近」/ `Fish approaching`。

> 枚举是**按显式 `id` 存档**的（不是按 `name()`），所以这次改名不影响任何已有存档。

**② 机器用的是「反应时间 0 的完美玩家」。**
原版在咬钩那一刻紧接着 `nibble = Mth.nextInt(random, 20, 40)`，
把 20~40 刻留给玩家右键 —— 没在这个窗口内收杆，鱼就跑了，一切重来。
机器不需要反应时间：**计时器归零的同一 tick 就收杆，`nibble` 窗口一个 tick 都不用**。
再加上机器收杆后立刻开下一周期（人还要重新抛竿），机器恒快于任何人类玩家。
这一条与区间取值无关，就算把区间调回原版也仍然成立。

**③ 唯一会真正变慢的条件：水面看不到天空。**
`!canSeeSky(above)` 触发时 `fishingSpeed` 均值从 1 降到 0.5，也就是**整整慢一倍**。
在池塘上盖了顶、把机器摆到树冠下、或者在室内/洞穴水池里钓，就会命中这一条。
下雨相反（均值升到 1.25，更快）。**这两条递减规则是原版行为，不是本模组的设定** ——
本模组的 `fishingSpeed` 与原版逐句对应，两次 `nextFloat()` 都执行以保证随机序列不错位。

**④ 真正卡住「再快一点」的是电，不是这两个区间。**
每杆预扣 `ENERGY_PER_CATCH = 500 FE`，而进电只有 `MAX_INPUT = 32 FE/t`（CCG LOW 档），
稳态最快约 `500 / 32 ≈ 15.6` 刻一杆（≈0.78 秒）。10,000 FE 的缓冲区只够先爆发约 20 杆，
之后就会退化成「攒够 500 FE 才开下一杆」—— 界面上那个倒计时会先走完、然后机器空等，
不再反映真实节奏。要把一杆压到比这更短，必须连 `MAX_INPUT` 的档位一起提
（或降低 `ENERGY_PER_CATCH`）。

> **饵钓按区间等比缩放**：原版每级缩 100 刻，而整段「等鱼上钩」只剩 30~50 刻 ——
> 照原样相减，**任何等级都会压到下限 1 刻**，I 级与 III 级没有区别。
> 所以 `LURE_TICKS_PER_LEVEL = 8`：III 级共缩 24 刻，与区间宽度 20 刻同量级，
> 于是 III 级（6~26 刻）与无附魔（30~50 刻）**完全不重叠**，等级越高越快。
>
> 缩放只作用于「每级刻数」这一个标量：等级仍由 `HookProbe` 从原版
> `EnchantmentHelper` 读回（那次 `* 20.0F` 换算一步没改），
> 只是把系数从 100 换成 8 再交给状态机，见 `HookProbe#lureTicksScaledTo(int)`。
> 副作用是 `MIN_WAIT_TICKS` 保底在 3 级以内不再被触发（最小仍有 6 刻），
> 它保留为防呆，测试用**由常量推导**的等级覆盖那条分支。

**⑤ 这些语义都有测试守着。**
- `FishingGameTests#approachPhaseIsBeforeTheBite`：处于 `APPROACHING` 时倒计时必然 `> 0`、
  且鱼必然还没进缓存，并且这一轮里**确实观察到了**该阶段
  （否则阶段切换失效会让测试空转通过）。将来若有人把收杆挪到归零之后若干刻
  （或真去照抄 `nibble` 窗口），「计时器已归零却还停在 `APPROACHING`」会在那一刻当场失败。
- `FishingGameTests#bothWaitIntervalsMatchTheRequirement`：两个区间**各自**断言，
  任何一边被单方面改动都会当场失败。读数的时机是「阶段刚切换」那一 tick ——
  那一刻计时器刚被赋值、还没被 `fishingSpeed` 扣过，所以断言与下雨/遮天导致的速度浮动无关。
  **期望值锚在需求（2 秒 / 1 秒）上而不是读常量** —— 拿常量当期望值是同义反复，
  常量怎么改它都成立，这点被 M8/M9 两次突变检验当场揭穿过（见 TESTING §7.5）。
- `FishingGameTests#lureEnchantMapsLevelToTicks`：两侧换算各断言一遍 ——
  原版尺度 `HookProbe.lureTicks()`（0 / 100 / 300）与本模组尺度
  `lureTicksScaledTo(LURE_TICKS_PER_LEVEL)`（0 / 8 / 24），外加保底分支。
  它断言的是换算输出而不是「最终的等待刻数」—— 后者会被区间夹住，反推不出附魔是否生效。
- `FishingGameTests#lureEnchantActuallyShortensTheWait`：多轮实测采样，
  断言饵钓 III 的**最坏**样本仍严格优于无附魔的**最好**样本（两组区间不重叠）。
  这是「等级阶梯存在」的运行时证据 —— 只写「常量之间满足某个不等式」说明不了
  状态机真的用了它。期望值锚在需求字面量（30~50 刻、每级 8 刻）上，不读常量。

## 4. 资源结构

```
assets/cyxautofishingmachine/
  blockstates/auto_fishing_machine.json     facing × active = 8 变体
  models/block/auto_fishing_machine.json    6 面单立方体：front / side / top
  models/block/auto_fishing_machine_active.json  只换 front 贴图（见 3.19 的取舍）
  models/item/auto_fishing_machine.json     继承方块模型
  items/auto_fishing_machine.json           26.3 的物品定义（不是 models/item）
  textures/block/auto_fishing_machine_{front,side,top}.png
  textures/block/auto_fishing_machine_front_active.png   由 tools/gen_active_texture.py 派生
  lang/{en_us,zh_cn}.json
data/cyxautofishingmachine/
  loot_table/blocks/auto_fishing_machine.json   破坏掉落本体
  recipe/auto_fishing_machine.json              3 铜锭 + 3 铁锭 + 2 红石 + 1 钓竿
  tags/item/fishing_rods.json                   兼容钓竿标签
```

方块外观沿用上游的铜质工业语言：铜底色、铆钉、红石指示灯；
正面是水槽 + 鱼 + 底部供电母线的 16×16 占位贴图。

## 5. 尚未实现（按阶段推进，不预留空壳）

| 阶段 | 内容 |
| --- | --- |
| Phase 8 | 完整测试与最终构建（海之眷顾统计验证、断竿长周期、区块卸载、多机联测、多人操作，清单见 `docs/TESTING.md` §9） |

另有一条**刻意不做**的记录：指示灯的 `light_emission` 自发光（纯观感，取舍见 §3.19）。
