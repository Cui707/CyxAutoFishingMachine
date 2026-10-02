# 上游源码分析：CrimsonCopperGrid

> 本文档的全部结论都来自对 `D:\codespace\CrimsonCopperGrid` 真实源码的阅读，
> 以及对其构建产物 `build/libs/crimsoncoppergrid-1.1.0.jar` 的字节码核对。
> 凡是「无法确认」的地方都写明了未确认，没有凭类名猜测 API。
>
> 分析对象：CrimsonCopperGrid `1.1.0`（HEAD = `dddad93`）
> 分析时间：2026-10-02

---

## 一、版本与依赖矩阵

| 项目 | 取值 | 来源 |
| --- | --- | --- |
| Minecraft | `26.3` | `gradle.properties: minecraft_version` |
| Fabric Loader | `0.19.5` | `gradle.properties: loader_version` |
| Fabric Loom | `1.18-SNAPSHOT` | `gradle.properties: loom_version` |
| Fabric API | `0.161.0+26.3` | `gradle.properties: fabric_api_version` |
| Team Reborn Energy | `5.0.0` | `gradle.properties: energy_version` |
| Gradle | `9.8.0` | `gradle/wrapper/gradle-wrapper.properties` |
| Java 编译目标 | `25`（`options.release = 25`） | `build.gradle` |
| 实际 JDK（本机） | `27` | `java -version` |

### 关于映射（重要）

`gradle.properties` 里有一行注释说明了这件事：

> Yarn 目前没有 26.3 的映射（`meta.fabricmc.net/v2/versions/yarn/26.3` 返回空），
> 因此 Loom 1.18 默认使用 **Mojang 官方映射**，模板中也不再声明 `mappings` 行。

我用 `javap` 核对了 `build/libs/crimsoncoppergrid-1.1.0.jar` 里的类常量池，
确认其中的 Minecraft 类引用是**官方名**（`net/minecraft/core/BlockPos`），
而**不是** intermediary（`net/minecraft/class_2338`）。

结论：**本环境里「开发期命名空间」与「产物命名空间」是同一套官方名**，
所以本模组引用上游时不需要担心跨命名空间重映射。
26.3 的 API 全部使用 Mojang 命名风格：`Identifier`（不是 `ResourceLocation`）、
`Level`、`BlockPos`、`ItemStack#setDamageValue` 等。

---

## 二、上游项目架构

### 2.1 目录与包结构

```
com.cyx.crimsoncoppergrid
├── CrimsonCopperGrid                  ← 主入口（ModInitializer）
├── init/
│   ├── ModBlocks                      ← 方块 + 对应 BlockItem 注册
│   ├── ModBlockEntities               ← BlockEntityType 注册
│   ├── ModCreativeTab                 ← 创造模式物品栏
│   └── ModPowerRegistration           ← Team Reborn Energy 能力注册（关键）
├── blocks/
│   ├── FuelGeneratorBlock / SolarGeneratorBlock / WindGeneratorBlock / ...
│   └── cable/{CableBlock, SwitchBlock, CableShapeUtil}
├── blockentity/
│   ├── BatteryBlockEntity / ElectricFurnaceBlockEntity / ...
│   └── cable/{CableBlockEntity, CableTickManager, OfferedEnergyStorage, SwitchBlockEntity}
└── common/
    ├── blocks/BlockMachineBase        ← 所有机器方块的公共父类（关键）
    ├── blockentity/MachineBaseBlockEntity  ← 所有机器方块实体的公共父类（关键）
    ├── menu/{MachineMenu, ModMenuTypes, ContainerDataCodec, *Menu}
    └── powerSystem/{PowerAcceptorBlockEntity, CcgEnergyTier, PowerSystem, GridStats}

com.cyx.crimsoncoppergrid.client      ← 独立 source set（splitEnvironmentSourceSets）
├── CrimsonCopperGridClient            ← 客户端入口
├── screen/{MachineScreen, *Screen}
└── render/WindTurbineRenderer
```

分层明显在模仿 TechReborn / RebornCore：`common/` 放跨环境的基类，具体机器各自成类。

### 2.2 主入口的初始化顺序

`CrimsonCopperGrid#onInitialize()`：

```java
ModBlocks.init();            // 方块
ModBlockEntities.init();     // 方块实体（要引用方块）
ModCreativeTab.init();       // 创造栏
ModMenuTypes.init();         // 菜单类型
ModPowerRegistration.init(); // ← 电力能力注册
CableTickManager.init();     // 导线网络的 tick 钩子
```

顺序对外部模组**有语义**：`ModPowerRegistration.init()` 必须在方块实体之后执行，
因为 `registerForBlockEntity` 需要已注册的 `BlockEntityType` 对象。

---

## 三、电力 API 接入方式（本项目最关键的一节）

### 3.1 `PowerAcceptorBlockEntity` 的契约

`com.cyx.crimsoncoppergrid.common.powerSystem.PowerAcceptorBlockEntity`
继承 `MachineBaseBlockEntity`，实现 `ContainerData`。它对外提供的扩展点只有三个抽象方法：

```java
public abstract long getBaseMaxPower();   // 内部缓冲容量
public abstract long getBaseMaxOutput();  // 基础输出速率，纯用电设备返回 0
public abstract long getBaseMaxInput();   // 基础输入速率
```

内部持有的是一个官方的 `SimpleSidedEnergyContainer`（Team Reborn Energy 自带），
它在三个 `long` 上转发到上面三个方法，外加两个可覆写的方向判定：

```java
protected boolean canAcceptEnergy(@Nullable Direction side) { return true; }  // 默认都可以进电
protected boolean canProvideEnergy(@Nullable Direction side) { return true; } // 纯用电设备覆写为 false
```

它还提供了这些**可直接复用**的公开方法：

| 方法 | 语义 |
| --- | --- |
| `long getStored()` | 当前存量 |
| `void setStored(long)` | 写入存量（内部 clamp 到 `[0, capacity]` 并 `setChanged()`） |
| `long getMaxStoredPower()` | = `getBaseMaxPower()` |
| `long getFreeSpace()` | `capacity - stored` |
| `void addEnergy(long)` | 加电（走 clamp） |
| `void useEnergy(long)` | 扣电，不够就扣到 0 |
| `boolean tryUseExact(long)` | 精确扣电：够就扣并返回 `true`，不够一动不动 |
| `EnergyStorage getSideEnergyStorage(@Nullable Direction)` | 供能力查阅表使用 |
| `CcgEnergyTier getTier()` | 按输入速率反查档位（描述性，不限制任何东西） |
| `static int calculateComparatorOutputFromEnergy(BlockEntity)` | 比较器输出 |

`tick()` 里做的唯一一件事是**推流**：对六个方向各 `EnergyStorageUtil.move(out, target, Long.MAX_VALUE, null)`。
因为纯用电设备的 `getBaseMaxOutput()` 是 0，它这一侧的推流天然是空操作。

**存档**：`loadAdditional` / `saveAdditional` 用独立子节点 `"PowerAcceptor"` 存 `energy`
（`output.child("PowerAcceptor").putLong("energy", ...)`），
所以**新 Mod 不需要也不应该自己再存一份能量**，只要不覆写这两个方法就自动继承。

### 3.2 能力是怎么挂上去的 —— `ModPowerRegistration`

```java
public static void init() {
    EnergyStorage.SIDED.registerFallback((level, pos, state, blockEntity, direction) ->
            blockEntity instanceof PowerAcceptorBlockEntity powerAcceptor
                    ? powerAcceptor.getSideEnergyStorage(direction)
                    : null);

    EnergyStorage.SIDED.registerForBlockEntity(CableBlockEntity::getSideEnergyStorage, ModBlockEntities.CABLE);
    EnergyStorage.SIDED.registerForBlockEntity(SwitchBlockEntity::getSideEnergyStorage, ModBlockEntities.SWITCH);
}
```

这是本次分析里**最重要的发现**：

> 上游用的是一条 **fallback**，而 fallback 是对**所有方块实体**生效的兜底查询。
> 判定条件是 `blockEntity instanceof PowerAcceptorBlockEntity`，
> **不限定注册表项、不限定模组**。

**因此本模组完全不需要自己调用 `EnergyStorage.SIDED.register*`**：
只要自动钓鱼机的方块实体继承 `PowerAcceptorBlockEntity`，
它就会自动被这条 fallback 命中，成为电网里一个合法的储能节点。
不需要重复注册，也不会和上游产生冲突（重复注册同一 `BlockEntityType` 会抛异常，
而这里走的是不同路径，没有这个风险）。

**唯一的前提**：CrimsonCopperGrid 必须先完成初始化。
这由 `fabric.mod.json` 的 `depends.crimsoncoppergrid` 保证 —— Fabric 的模组排序会先加载被依赖方。

### 3.3 电网是怎么把电送进机器的

导线侧的逻辑（`CableBlockEntity` + `CableTickManager`）：

1. `CableBlockEntity#appendTargets` 对六个方向做 `BlockApiCache<EnergyStorage, Direction>` 查询，
   把**非导线**的相邻储能收集成 `OfferedEnergyStorage` 列表（结果带缓存，`neighbourUpdate()` 作废）；
2. `CableTickManager#tick` 用 BFS 把相连的导线聚成一个网络，把各段缓冲摊平，
   然后 `dispatchTransfer(EnergyStorage::extract, ...)` 收电、`dispatchTransfer(EnergyStorage::insert, ...)` 送电；
3. 送电走的就是 `EnergyStorage.insert`，也就是我们机器 `SimpleSidedEnergyContainer`
   的 `getMaxInsert(face)`，最终落到 `getBaseMaxInput()`。

**对机器的要求**：只要 `getBaseMaxInput() > 0`，导线网络就会主动把电送进来。
不需要机器自己"拉电"，不需要自己遍历邻居。

#### ⚠️ 反向查询导线时的一个坑（Phase 3 实测踩到）

机器想反过来判断"我接在电网上了吗"，自然会想到去查邻居的 `EnergyStorage`。
**但这在导线身上不可靠**：

`SimpleSidedEnergyContainer` 把 `supportsInsertion()` / `supportsExtraction()`
实现为 `getMaxInsert(side) > 0` / `getMaxExtract(side) > 0`；
而 `CableBlockEntity` 的 `getMaxExtract(side)` 还乘了 `allowTransfer(side)`，
后者要求 `(blockedSides & (1 << side.ordinal())) == 0`。
`blockedSides` 是"这个方向本轮已经搬过电，别再反向搬一次"的记账位
（`OfferedEnergyStorage#afterTransfer` 置位），
**清除时机是下一轮网络结算的开头（`appendTargets` 末尾），不是本 tick 的结尾**。

后果：导线刚向某个方向送过电，那个方向上查到的 `supportsExtraction()` 会在
"本轮结算结束 → 下轮结算开始"之间读到 `0`。拿它当"是否连着电网"的判据，
结果会随双方 tick 的先后顺序随机横跳。

**正确做法**：导体看 `CableBlockEntity#conducts()`（只读方块状态，与 tick 中间态无关），
非导体设备才看 `supportsExtraction()`。本模组的 `power/GridLink` 就是这么写的。

### 3.4 档位（`CcgEnergyTier`）

```
MICRO(8)  LOW(32)  MEDIUM(128)  HIGH(512)  EXTREME(2048)  INSANE(8192)  INFINITE
```

档位是**纯描述性**的（注释里明确写了"它不限制任何东西"），
但 `PowerAcceptorBlockEntity#checkTier()` 会按
`getBaseMaxInput() == 0 ? getBaseMaxOutput() : getBaseMaxInput()` 反查档位给界面用。
本模组直接取 `CcgEnergyTier.LOW.getMaxInput()`（= 32）作为输入速率，
这样"实际速率"与"UI 上显示的档位"天然一致，不会出现标称与实测对不上的情况。

### 3.5 现有用电设备的参数（用于横向对齐）

| 机器 | 容量 | 输入 | 输出 |
| --- | --- | --- | --- |
| 电力熔炉 `ElectricFurnaceBlockEntity` | 2 000 FE | 20 FE/t | 0 |
| 电池 `BatteryBlockEntity` | 1 000 000 FE | 1 000 FE/t | 1 000 FE/t |
| 铜制电线 `CableBlockEntity` | 128 FE | 32 FE/t | 32 FE/t |

---

## 四、需要复用的类与接口

| 上游类 / 接口 | 用途 | 复用方式 |
| --- | --- | --- |
| `common.powerSystem.PowerAcceptorBlockEntity` | 电力缓冲、输入速率、能量存档、方向判定、比较器 | **继承**（本模组方块实体的直接父类） |
| `common.blocks.BlockMachineBase` | `FACING` / `ACTIVE` 方块状态、放置朝向、旋转镜像、`useWithoutItem` 开界面、比较器 | **继承**（本模组方块类的直接父类） |
| `common.blockentity.MachineBaseBlockEntity` | tick 调度、`serverTick()` 钩子、`onPlace`/`onBreak` 生命周期、20 刻合并的客户端同步、`Container` 默认实现 | 通过 `PowerAcceptorBlockEntity` 间接继承 |
| `common.powerSystem.CcgEnergyTier` | 输入速率档位取值（保持与上游 UI 口径一致） | **引用常量** |
| `common.powerSystem.PowerSystem` | 能量数值的界面格式化（`1.2 kFE`） | **调用静态方法** |
| `common.menu.ContainerDataCodec` | `long` ↔ `ContainerData` 的 16 位分片编解码 | **调用**（复用它的 bug 修复成果） |
| `common.menu.MachineMenu` | 界面公共父类：玩家背包槽位、`quickMoveStack`、`readLong` | **继承** |
| `client.screen.MachineScreen` | 界面公共父类：原版贴图背景、面板遮盖、补画槽位底图、`drawBar` | **继承** |
| `EnergyStorage.SIDED` 的 fallback | 让新机器自动进入电网 | **被动复用**（不注册，靠继承被 fallback 命中） |

### 明确**不**复用的部分（附原因）

| 上游部分 | 不复用原因 |
| --- | --- |
| `CableTickManager` | 它是导线网络内部的结算器，不是对外 API。机器只需要作为 `EnergyStorage` 被它收送电，把它当作"电网"去主动解析反而会破坏上游的推流模型。 |
| `OfferedEnergyStorage` | 包私有导向导线组的内部类型，机器侧用不到。 |
| `ModPowerRegistration` | 上游的方法只处理它自己的类；我们靠 fallback 被动命中，不需要也不应该去调用它。 |
| `ModBlocks` / `ModBlockEntities` / `ModMenuTypes` / `ModCreativeTab` | 这些是**上游自己的注册表**，不是可扩展点。本模组必须有自己的同名职责类。 |

---

## 五、需要新增的类

按需求文档「十、代码架构要求」的职责划分，并依据上游的实际分层风格（`common/` 放基类）：

| 新增类 | 包 | 职责 |
| --- | --- | --- |
| `CyxAutoFishingMachine` | `com.cyx.cyxautofishingmachine` | 主入口、`MOD_ID`、`id()` |
| `CyxAutoFishingMachineClient` | `...client` | 客户端入口（注册 Screen） |
| `ModBlocks` | `...init` | 方块注册 |
| `ModBlockEntities` | `...init` | 方块实体类型注册 |
| `ModItems` | `...init` | 物品注册（可并入 `ModBlocks`，视是否需要独立物品而定） |
| `ModScreenHandlers` | `...init` | `MenuType` 注册 |
| `ModCreativeTab` | `...init` | 创造模式物品栏 |
| `AutoFishingMachineBlock` | `...blocks` | 机器方块（继承 `BlockMachineBase`） |
| `AutoFishingMachineBlockEntity` | `...blockentity` | 机器状态、电力、库存、`MenuProvider`（继承 `PowerAcceptorBlockEntity`） |
| `FishingController` | `...fishing` | 钓鱼周期状态机 |
| `FishingLootHandler` | `...fishing` | 调用原版 `BuiltInLootTables.FISHING` |
| `FishingRodHandler` | `...fishing` | 读附魔（饵钓/海之眷顾/耐久/修补）、耐久损耗 |
| `WaterAreaValidator` | `...fishing` | 开放水域检测 |
| `AdjacentInventoryHandler` | `...inventory` | 相邻容器检测与输出 |
| `MachineConfig` | `...config` | 机器参数集中定义 |
| `GridLink` | `...power` | 电网连接检测（Phase 3 新增） |
| `AutoFishingMachineScreenHandler` | `...menu` | 服务端 GUI 逻辑（继承 `MachineMenu`） |
| `AutoFishingMachineScreen` | `...client.screen` | 客户端 GUI（继承 `MachineScreen`） |
| `FishingHookAccessor`（Mixin） | `...mixin` | 见下方「7.3」 |

> 上表是 **Phase 1 的预测**，实际实现会随源码分析结果修订。已经发生的一处修订：
> `ModTags`（Phase 2）与 `GridLink`（Phase 3）是分析时没想到、写代码时才发现需要的；
> 而 `ModItems` 因为方块物品并入了 `ModBlocks`（对齐上游写法）而暂未创建空类。
> 各阶段的实际类清单以 `docs/ARCHITECTURE.md` 为准。

> 命名风格说明：上游统一用 `com.cyx.<modid>` 作为包名前缀（`group=com.cyx.crimsoncoppergrid`）。
> 需求文档给的是「暂定 `com.cui707.cyxautofishingmachine`」，同时要求
> 「如上游项目存在明确的包名规范，应优先参考其命名风格」。
> 因此本项目采用 **`com.cyx.cyxautofishingmachine`**，Mod ID 保持 `cyxautofishingmachine` 不变。

---

## 六、依赖关系

```
CyxAutoFishingMachine (本模组)
├── depends: crimsoncoppergrid >= 1.1.0   ← 硬依赖，编译期 + 运行期
│   └── 内嵌 (JiJ) team_reborn_energy 5.0.0
├── depends: fabricloader >= 0.19.5
├── depends: minecraft ~26.3
├── depends: java >= 25
└── depends: fabric-api *
```

### 编译期依赖怎么解决

CrimsonCopperGrid **没有发布到任何 Maven 仓库**（`build.gradle` 的 `repositories {}` 是空的，
`publishing` 的仓库列表也是空的）。因此本模组用**本地 jar 依赖**：

```gradle
modImplementation files(ccgJar)
```

`ccgJar` 按顺序查找：

1. `libs/crimsoncoppergrid-1.1.0.jar`（仓库自带，CI 用这一份）
2. `../CrimsonCopperGrid/build/libs/crimsoncoppergrid-1.1.0.jar`（本机开发直接吃上游产物）
3. `-PccgJar=<绝对路径>` 显式覆盖

三处都找不到时在**配置阶段**直接抛 `GradleException` 并给出可执行的提示 ——
否则会退化成编译期一堆「找不到符号」，很难看出根因是依赖没配好。

### Team Reborn Energy 的归属

上游用 `include(api("teamreborn:energy:5.0.0"))` 把 energy **内嵌（JiJ）**进了自己的 jar
（我核对了产物：`crimsoncoppergrid-1.1.0.jar` 里确实有 `META-INF/jars/energy-5.0.0.jar`）。

所以本模组：

- **运行时**：直接用 CrimsonCopperGrid 内嵌的那一份，**不再 include**（否则会出现两份同名模组）；
- **编译期**：声明 `modImplementation "teamreborn:energy:5.0.0"`，只为解析 `team.reborn.energy.*`。

---

## 七、原版钓鱼机制的核对结果（Phase 5 的技术前提）

这一节的内容取自 26.3 的**反编译源码**（`./gradlew genSources` 产物）：

- `net/minecraft/world/entity/projectile/FishingHook.java`
- `net/minecraft/world/item/FishingRodItem.java`
- `net/minecraft/world/level/storage/loot/LootParams.java`
- `net/minecraft/world/level/storage/loot/parameters/LootContextParamSets.java`
- `net/minecraft/advancements/predicates/entity/FishingHookPredicate.java`
- `net/minecraft/world/item/ItemStack.java`
- `data/minecraft/loot_table/gameplay/fishing.json` 及 `fishing/{fish,junk,treasure}.json`

（这些参考文件同时也留在项目的 `.refs/mcsrc/` 下，已被 `.gitignore` 排除。）

### 7.1 战利品生成 —— 可以完整复用

`FishingHook#retrieve(ItemStack)` 里的这段就是原版钓鱼产出的全部实现：

```java
LootParams params = new LootParams.Builder((ServerLevel) this.level())
        .withParameter(LootContextParams.ORIGIN, this.position())
        .withParameter(LootContextParams.TOOL, rod)
        .withParameter(LootContextParams.THIS_ENTITY, this)
        .withLuck(this.luck + owner.getLuck())
        .create(LootContextParamSets.FISHING);
LootTable lootTable = this.level().getServer().reloadableRegistries().getLootTable(BuiltInLootTables.FISHING);
List<ItemStack> items = lootTable.getRandomItems(params);
```

`retrieve` 本身是 `public` 的，但它前面有一道 `shouldStopFishing(owner)` 闸门，
要求「真实玩家手持钓竿且在 32 格内」—— 自动钓鱼机不满足这个前提，
所以**不能直接调 `retrieve`**，而是①照抄上面这段战利品生成逻辑，
②自己处理耐久与经验。

这个方案天然满足文档里的几条硬要求：

- 用的是真 `BuiltInLootTables.FISHING`，所以**任何通过数据包改钓鱼战利品表的模组都会自动生效**；
- 概率全部来自 `LootTable`，Java 侧不写死任何物品或概率；
- **海之眷顾**通过 `withLuck(...)` 生效，因为原版 `fishing.json` 里的三个候选条目
  正是用 `quality`（`-2 / +2 / -1`）与 luck 交互的；
- **不得在封闭水池里拿到宝藏**这条，不需要我们额外写判断 —— 原版 `fishing.json` 里
  treasure 条目自带条件：

  ```json
  "condition": {
    "type": "minecraft:entity_properties",
    "entity": "this",
    "predicate": { "minecraft:type_specific/fishing_hook": { "in_open_water": true } }
  }
  ```

  只要 `THIS_ENTITY` 是一个 `isOpenWaterFishing()` 为 `false` 的 `FishingHook`，宝藏池就不会被选中。

`LootContextParamSets.FISHING` 的定义也核对过：

```java
register("fishing", builder -> builder
        .required(LootContextParams.ORIGIN)
        .required(LootContextParams.TOOL)
        .optional(LootContextParams.THIS_ENTITY));
```

`THIS_ENTITY` 是 **optional**，所以不带实体也能建出上下文 ——
但那样宝藏池会因条件不满足而永远选不中。因此我们**会**传一个实体（见 7.3）。

### 7.2 开放水域判定 —— 私有方法，需要 Mixin 打通

原版实现（`FishingHook`）：

```java
private boolean calculateOpenWater(BlockPos pos) {
    OpenWaterType previousLayer = OpenWaterType.INVALID;
    for (int y = -1; y <= 2; y++) {
        OpenWaterType layer = getOpenWaterTypeForArea(pos.offset(-2, y, -2), pos.offset(2, y, 2));
        switch (layer) {
            case ABOVE_WATER -> { if (previousLayer == OpenWaterType.INVALID) return false; }
            case INSIDE_WATER -> { if (previousLayer == OpenWaterType.ABOVE_WATER) return false; }
            case INVALID -> { return false; }
        }
        previousLayer = layer;
    }
    return true;
}

private OpenWaterType getOpenWaterTypeForBlock(BlockPos pos) {
    BlockState state = level().getBlockState(pos);
    if (!state.isAir() && !state.is(Blocks.LILY_PAD)) {
        FluidState fluid = state.getFluidState();
        return fluid.is(FluidTags.WATER) && fluid.isSource() && state.getCollisionShape(level(), pos).isEmpty()
                ? OpenWaterType.INSIDE_WATER : OpenWaterType.INVALID;
    }
    return OpenWaterType.ABOVE_WATER;
}
```

要点：

- `getOpenWaterTypeForArea` 用 `reduce((a, b) -> a == b ? a : INVALID)`，
  即 **5×5 范围内必须完全一致**，混了就 INVALID；
- 判定范围是 `x/z ∈ [-2, 2]`、`y ∈ [-1, 2]` 共 4 层；
- 由此推出的实际条件：钓点所在水块**下方必须还有水**（第 -1 层要求 `INSIDE_WATER`），
  往上 2 格必须全是空气/睡莲，且整个 5×5 区域在这 4 层上分别纯净。

这两个方法都是 `private` 实例方法（依赖 `this.level()`），无法静态调用。
本项目的处理方式：

1. 用 Mixin `@Invoker` 打开 `calculateOpenWater(BlockPos)`；
2. 用一个**不加入世界**的 `FishingHook` 实例来承载调用（也因此不用伪造玩家、不新增实体）。

这样检测逻辑是**原版那段代码本身**在跑，而不是我们复刻一份 —— 未来原版改判定规则时不用跟着改。

### 7.3 需要的那一个 Mixin

`FishingHook` 里有两处私有状态必须打通：

| 目标 | 原因 |
| --- | --- |
| `@Invoker("calculateOpenWater") boolean invokeCalculateOpenWater(BlockPos)` | 复用原版开放水域判定 |
| `@Accessor("openWater") void setOpenWater(boolean)` | 让 `isOpenWaterFishing()` 返回我们算出的值，从而让原版战利品表自己决定要不要给宝藏 |

`FishingHook` 的构造函数已被 Fabric 的 `fabric-transitive-access-wideners-v1` 放开为
`public FishingHook(EntityType, Level, int luck, int lureSpeed)`（源码注释里明确写了
"Access widened by fabric-transitive-access-wideners-v1 to accessible"），
所以我们可以直接 `new`，**不需要**为构造函数再写 Mixin。

### 7.4 附魔与耐久

`FishingRodItem#use` 里给出了抛竿时的两个参数：

```java
int lureSpeed = (int) (EnchantmentHelper.getFishingTimeReduction(serverLevel, itemStack, player) * 20.0F);
int luck      = EnchantmentHelper.getFishingLuckBonus(serverLevel, itemStack, player);
```

两个方法在 26.3 的签名是 `(ServerLevel, ItemStack, Entity fisher)` —— 收的是 `Entity`
而不是 `Player`，所以机器侧可以把"虚拟钓手"（那个不加入世界的 `FishingHook`）传进去。
**饵钓**每级 `reduction = 5 秒` → `lureSpeed = 100 刻`；**海之眷顾**每级 `+1 luck`。

等待时间（`FishingHook#catchingFish`）：

```java
this.timeUntilLured = Mth.nextInt(this.random, 100, 600);
this.timeUntilLured = this.timeUntilLured - this.lureSpeed;
...
if (this.timeUntilLured <= 0) { this.timeUntilHooked = Mth.nextInt(this.random, 20, 80); }
```

→ 基础 **100~600 刻**，再减去 `lureSpeed`，全部走完才咬钩。
（本模组只把两个区间的上下限调短成 `30~50` / `15~25` 刻，机制不变 ——
见 ARCHITECTURE §3.20。）

耐久：`retrieve` 返回的 `dmg` 在普通钓获时是 `1`，然后
`itemStack.hurtAndBreak(dmg, owner, slot)`。机器没有 `LivingEntity` 所有者，
但 `ItemStack` 上还有一个更底层的重载：

```java
public void hurtAndBreak(int amount, ServerLevel level, @Nullable ServerPlayer player, Consumer<ItemStack> onBreak)
```

它内部会走 `EnchantmentHelper.processDurabilityChange(level, this, amount)`
——**耐久（Unbreaking）附魔因此自动生效**；`player` 传 `null` 即可。

> **Phase 5 落地时的修正**：`onBreak` 回调其实**不需要**做任何事。
> `hurtAndBreak` 内部在耐久归零时已经把物品数量减到 0（钓竿槽随之自然变空），
> 回调只是原版用来播「装备损坏」动画/音效的钩子（`owner.onEquippedItemBroken`）。
> 机器侧只需自己补一声 `ITEM_BREAK`，不需要「清空钓竿槽 / 切 BROKEN_ROD 状态」——
> 那是当初对原版行为的误读，实际实现（`fishing.RodWear`）没有这么做。

另经反编译确认，`processDurabilityChange` 是把数量交给
`enchantment.value().modifyDurabilityChange(...)` 逐条附魔去改
（对应数据文件里的 `minecraft:item_damage` 效果组件），所以
「耐久 III 有 3/4 概率免掉这次损耗」这条概率逻辑也在原版侧，本模组不参与。

### 7.5 经验（含修补附魔）

原版在 `retrieve` 里对**每一个**产出物品生成一颗经验球
（`new ExperienceOrb(level, owner.getX(), owner.getY() + 0.5, owner.getZ() + 0.5, random.nextInt(6) + 1)`），
并且只在 `itemStack.is(ItemTags.FISHES)` 时给玩家记 `Stats.FISH_CAUGHT` 统计。

**Phase 5 已定位**（此前记录的「没有公开入口」是错的，更正如下）：
修补的实现就是原版 `ExperienceOrb#repairPlayerItems`，它调用的两个 `EnchantmentHelper`
方法都是 public 的：

```java
EnchantmentHelper.has(rod, EnchantmentEffectComponents.REPAIR_WITH_XP)   // 是否带修补
EnchantmentHelper.modifyDurabilityToRepairFromXp(level, rod, xp)         // xp → 耐久点数（含 clamp ≥ 0）
```

机器侧照抄这条路（`fishing.RodWear#repairWithExperience`）：这一杆的
`nextInt(6) + 1` 点经验不再生成经验球，而是直接喂给钓竿修补。
**顺序必须是「先扣耐久、再修补」**（与原版一致）：反过来的话
耐久永远修不满、也就永远不会断，Mending 会被写成无敌。

GameTest `mendingRepairsRod` 实测：磨损 30 的钓竿钓一杆后耐久**净上升**。

### 7.6 26.3 容器 API 事实（Phase 6 的技术前提，均为反编译 + 实测确认）

输出到相邻容器要用的原版 API 有几处和直觉不符，逐条记录：

1. **大箱子是两半。** `ChestBlockEntity#getContainerSize()` 硬编码返回 27，
   `level.getBlockEntity(pos)` 拿到的永远只有一半。
   要 54 格必须走 `ChestBlock.getContainer(ChestBlock, BlockState, Level, BlockPos, boolean)`，
   内部走 `DoubleBlockCombiner`，双箱时返回 `CompoundContainer(first, second)`。
   合并条件：邻居是同类方块、两者 `ChestType` 互斥且非 SINGLE、`FACING` 相同。
2. **有些容器没有方块实体。** `WorldlyContainerHolder#getContainer(BlockState, LevelAccessor, BlockPos)`
   （堆肥桶的 `ComposterBlock` 实现它）返回的容器是**现造**的，
   必须排在「查方块实体」之前 —— 原版 `HopperBlockEntity#getBlockContainer` 就是这个顺序。
3. **三层准入。** `Container#canPlaceItem`（物品类型）+
   `WorldlyContainer#canPlaceItemThroughFace`（从哪一面进）+ 槽位余量。
   堆肥桶是严格区分入料面的代表：`InputContainer` 只在 `Direction.UP` 收
   `COMPOSTABLE` 组件的物品、`getMaxStackSize() == 1`。
4. **「满」的判据。** 原版 `isFullContainer` 看的是「每个槽位的数量都达到
   槽里物品自己的上限」（`stack.getCount() < stack.getMaxStackSize()`），不是拿 64 比。
5. **上游机器基类的 `Container` 是占位骨架。** `MachineBaseBlockEntity` 实现
   `Container` 但 `getContainerSize()` 恒为 0、`isEmpty()` 恒为 true ——
   查找相邻容器时必须先用「该面有没有可用槽位」把它排除，否则
   「没放容器」会被误报成「容器已满」。

### 7.7 26.3 界面与模型 API 事实（Phase 7 的技术前提，均为反编译 + 实测确认）

做工具提示与方块外观时踩到的几处与旧版本直觉不符的地方，逐条记录：

1. **界面渲染是「渲染状态提取」模型，不再是每帧直接画。**
   一帧的顺序是 `extractRenderState` → `extractContents` → `extractLabels`
   → `extractSlots` → `extractCarriedItem` → `extractTooltip`。
   自绘内容（电量条、文字、悬停命中测试）挂在 `extractLabels` 上。
2. **`extractLabels` 在平移坐标系里执行。**
   `AbstractContainerScreen` 先 `graphics.pose().pushMatrix()` +
   `translate(leftPos, topPos)` 再调它 —— 画的时候用**面板相对坐标**，
   做鼠标命中测试时要自己减回去（`mouseX - leftPos`）。
   方法签名本身就带 `mouseX/mouseY`，不需要自己追踪鼠标。
3. **工具提示是延迟委托，先设置者生效。**
   `GuiGraphicsExtractor#setTooltipForNextFrame(font, List<Component>,
   Optional<TooltipComponent>, x, y)` 只是登记 `deferredTooltip`，
   真正绘制在所有内容层画完之后（`extractDeferredElements`）。
   `setTooltipForNextFrameInternal` 只在 `deferredTooltip == null ||
   replaceExisting` 时写入 —— 传 `replaceExisting = false` 并把调用放在
   原版 `extractTooltip` **之前**，槽位物品自己的提示就不会被覆盖。
   传入的坐标必须是**屏幕绝对坐标**：委托执行时 pose 已经还原。
4. **翻译参数只有 `%s` 能用。** `Component.translatable(key, Object... args)`
   的参数全是对象，`%d`/`%f` 会直接抛异常 —— 数值要自己先格式化成字符串。
   服务端想断言提示内容，用 `TranslatableContents#getKey()/getArgs()`，
   与玩家语言无关。
5. **方块模型的 `light_emission` 是元素级，不是面级。**
   `net.minecraft.client.resources.model.cuboid.CuboidModelElement`
   的 `FIELD_LIGHT_EMISSION`（0–15）挂在**元素**上；
   消费点在 `VertexConsumer#putBlockBakedQuad` →
   `getLightCoordsWithEmission(vertex, lightEmission)`。
   想让方块一部分自发光（比如指示灯），必须把那几个像素拆成独立 cuboid 元素
   —— 本项目评估过并决定不做（见 `docs/ARCHITECTURE.md` §3.19）。
6. **客户端 GameTest 的光标单位是窗口像素，不是 GUI 缩放坐标。**
   `TestInput#setCursorPos` 写的是 `MouseHandler.xpos/ypos`（窗口屏幕坐标），
   原版画界面时才按 `x * guiScaledWidth / screenWidth` 换算。
   所以把 GUI 缩放坐标塞进去会点偏，要反过来乘
   `screenWidth / guiScaledWidth`。
7. **`ClientGameTestContext#runOnClient` 返回 void。**
   需要返回值（比如界面左上角坐标）用 `computeOnClient`。
   另外 26.3 把「当前屏幕」从 `Minecraft.screen` 挪到了 `Minecraft.gui.screen()`。
8. **`fabric-gametest` 注解在 Fabric 侧。**
   是 `net.fabricmc.fabric.api.gametest.v1.GameTest`，
   不是 `net.minecraft.gametest.framework.GameTest`（后者根本不在编译类路径上）。

---

## 八、潜在兼容性问题

| # | 风险 | 说明与对策 |
| --- | --- | --- |
| 1 | **上游未发布 Maven 包** | 依赖只能走本地 jar。上游一旦发版，本项目的 `ccg_version` 需要同步；已在 `build.gradle` 里做了清晰的报错提示与三处查找路径。 |
| 2 | **fallback 的注册时机** | 我们的机器依赖上游那条 fallback。若将来上游把 fallback 换成"按类型逐个注册"，本模组的机器会静默地不再接电（不报错，只是不动）。**缓解**：Phase 3 会增加一个启动后的自检日志（查询一次自己的能量能力，拿不到就报 WARN）。 |
| 3 | **fallback 的 `instanceof` 受类加载影响** | 上游 `CableBlockEntity` 的注释里记过一次真实事故：同一个类被加载了两份导致 `instanceof` 失效，所以他们改用"比较注册表里的类型对象"。我们的机器由 Fabric 统一加载，理论上不会双份；但**风险存在**，Phase 8 的多机联测要专门盯这条。 |
| 4 | **`MenuType` 构造函数可见性** | 26.3 里 `MenuType` 的构造函数是包私有，靠 Fabric 的 `fabric-transitive-access-wideners-v1` 放开为 `protected`。本模组与上游用同一条路径，没问题；但如果将来去掉对 `fabric-api` 的依赖就会编译失败。 |
| 5 | **`MenuScreens.register` / `BlockEntityRenderers.register` 也是被拓宽的** | 同上，客户端入口依赖 `fabric-api`。 |
| 6 | **`ContainerData` 只有 16 位/槽** | 上游踩过坑（`ContainerDataCodec` 的类注释记录了完整事故）。本模组的状态字段（进度、状态码、暂停原因）都是小整数，电量直接复用上游的 `DATA_STORED` / `DATA_CAPACITY` 分片方案。 |
| 7 | **GUI 绘制 API 在 26.3 变了** | 覆写点从旧版的 `renderBg` 变成 `extractBackground` / `extractLabels`，文字默认带投影（上游因为中文糊成一团而统一关掉投影）。本模组继承 `MachineScreen` 即可自动继承这些修正。 |
| 8 | **`Identifier` 取代 `ResourceLocation`** | 26.3 的类名是 `net.minecraft.resources.Identifier`（`Identifier.withDefaultNamespace(...)` / `Identifier.fromNamespaceAndPath(...)`）。 |
| 9 | **钓鱼战利品表可能被别的模组改** | 这正是我们要的行为（数据包兼容目标）。但反过来意味着"测得的结果"会随数据包变化 —— Phase 8 的自动化测试不能硬编码产物品类，只能验证"产出的物品 ∈ 当前战利品表可能产出的集合"。 |
| 10 | **等待时间的取值：需求文档与原版都未被采用** | 需求文档表格写 `600~1200 ticks`，正文又要求"尽可能忠实地模拟原版"——这两者本身就冲突。初版按**原版**取 `100~600`（减饵钓）；实机测试后用户明确要求「等待约 2 秒 + 靠近约 1 秒」，最终取 `30~50` / `15~25` 刻，**既不是文档值也不是原版值**。四个上下限都收在 `MachineConfig` 的「钓鱼节奏」一节里，改一行即可切换，状态机没有第二处硬编码。取舍与后果见 ARCHITECTURE §3.20。 |
| 11 | **导线的 `supportsExtraction()` 会因为 `blockedSides` 记账而短暂读到 0** | 见 3.3 末尾。反向判断"是否连着电网"时**不能**依赖它，导体必须改看 `conducts()`。Phase 3 实测期间发现。 |
| 12 | **上游的 `MachineMenu` 继承了原版 `moveItemStackTo` 的合并漏洞**：原版该方法「合并进同类物品堆」的分支不检查 `Slot#mayPlace`，玩家可把同类物品 shift 点击合并进本应「只出不进」的槽位 | 本模组已在 `AutoFishingMachineMenu` 覆写修复（见 ARCHITECTURE §3.7），GameTest 实测发现。上游的 `OutputSlot`（电力熔炉输出槽）理论上同样受影响，属于上游侧问题，本模组不改上游源码 |
| 13 | **`MachineBaseBlockEntity#tick` 的签名带第 4 个参数（方块实体自身）** | GameTest 手动推刻时直接调 `machine.tick(level, pos, state, machine)`。若上游改这个签名，测试会**编译失败**（大声报错，不是静默失效），可接受 |
| 14 | **上游 `setStored` 自带 `clamp(0, capacity)`** | 已核实。这意味着「中止周期退还预扣电」即使碰上缓冲已被电网填满的情况也**不会溢出容量**（多退的部分被钳掉，最坏是机器白赚 ≤500 FE，不会超容、不会复制物品） |

---

## 九、上游未提供、需要留意的能力缺口

以下几件事上游**没有**提供可复用实现，本模组要自己写（都属于"不需要电网配合"的纯逻辑）：

1. ~~**相邻容器检测与输出**（大箱子合并库存）~~ —— **已在 Phase 6 落地**
   （`inventory.AdjacentInventory` + `AutoFishingMachineBlockEntity#outputTick`，
   技术前提见 7.6，实测见 `docs/TESTING.md` §6）。
2. **开放水域的空间扫描与缓存** —— 原版只有单点判定，没有"找一片合法水域"这件事。
3. **钓鱼状态机** —— 上游的机器都是"无条件 + 进度条"的简单模型
   （如电力熔炉：有料就烧）。本机器的"四项条件全部满足才能开始"复杂得多，需要独立的状态机。
4. **机器内的经验处理 / 修补附魔** —— 已在 Phase 5 落地（见 7.5）：走原版
   `EnchantmentHelper.has(REPAIR_WITH_XP)` + `modifyDurabilityToRepairFromXp`，无自研逻辑。

---

## 十、结论

1. **可以继承而不是复刻**：`BlockMachineBase` + `PowerAcceptorBlockEntity` 这两个基类
   正好覆盖了本项目一半的需求（方块状态、放置破坏、tick 调度、电力缓冲、输入速率、
   能量存档、比较器、客户端同步节流）。继承它们比自己实现电网接入要短得多，也更不容易出错。
2. **不需要重新实现电网**：上游的 fallback 让"继承即接入"，这是最干净的一条路。
3. **不需要改上游一行代码**：所有复用都通过 `public` / `protected` 的扩展点完成。
4. **原版钓鱼逻辑可以做到高保真**：战利品、概率、海之眷顾、宝藏的开放水域门槛
   全部由原版 `LootTable` + 原版 `FishingHook` 判定函数承担；
   代价是**一个只含两个成员的 Mixin**（一个 `@Invoker`、一个 `@Accessor`）。
