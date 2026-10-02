package com.cyx.cyxautofishingmachine.blockentity;

import java.util.List;
import java.util.Objects;

import org.jspecify.annotations.Nullable;

import com.cyx.crimsoncoppergrid.common.powerSystem.PowerAcceptorBlockEntity;
import com.cyx.cyxautofishingmachine.config.MachineConfig;
import com.cyx.cyxautofishingmachine.fishing.FishingPhase;
import com.cyx.cyxautofishingmachine.fishing.FishingSpot;
import com.cyx.cyxautofishingmachine.fishing.HookProbe;
import com.cyx.cyxautofishingmachine.fishing.LootRoller;
import com.cyx.cyxautofishingmachine.fishing.PauseReason;
import com.cyx.cyxautofishingmachine.fishing.RodWear;
import com.cyx.cyxautofishingmachine.fishing.WaterFinder;
import com.cyx.cyxautofishingmachine.fishing.WaterVerdict;
import com.cyx.cyxautofishingmachine.init.ModBlockEntities;
import com.cyx.cyxautofishingmachine.init.ModTags;
import com.cyx.cyxautofishingmachine.inventory.AdjacentInventory;
import com.cyx.cyxautofishingmachine.menu.AutoFishingMachineMenu;
import com.cyx.cyxautofishingmachine.power.GridLink;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.NonNullList;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.Container;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.FishingRodItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/**
 * 自动钓鱼机的方块实体：机器状态、电力、物品栏与钓鱼状态机。
 *
 * <h2>继承链</h2>
 * <pre>
 * BlockEntity
 *   └─ MachineBaseBlockEntity        （前置 Mod：tick 调度 / 方块状态 / 同步 / 生命周期钩子）
 *        └─ PowerAcceptorBlockEntity （前置 Mod：Team Reborn Energy 的能量缓冲与推流）
 *             └─ AutoFishingMachineBlockEntity（本类：钓竿槽 + 输出缓存 + 电力基准 + 钓鱼状态机）
 * </pre>
 * 继承 {@code PowerAcceptorBlockEntity} 是本项目的关键决策：
 * 上游的能量能力（{@code EnergyStorage.SIDED}）注册的是一条全局 <b>fallback</b>，
 * 判定条件就是 {@code instanceof PowerAcceptorBlockEntity}。因此本类<b>不需要注册任何能力</b>
 * 就已经是电网里的合法节点，「电网断开」也被上游的电缆 tick 结算自动覆盖。
 * 详见 {@code docs/UPSTREAM_ANALYSIS.md}。
 *
 * <h2>钓鱼状态机</h2>
 * 三个状态（{@link FishingPhase}）与原版 {@code FishingHook#catchingFish} 的两个计时器一一对应。
 * <b>机制照抄原版，两个区间的时长则是本项目调过的</b>（约 2 秒 + 1 秒，均值 3 秒一杆；
 * 原版是 5~30 秒 + 1~4 秒），对照表见 {@link MachineConfig} 的「钓鱼节奏」。
 *
 * <pre>
 * IDLE ──条件齐备且预扣成功──▶ WAITING ──timeUntilLured 归零──▶ APPROACHING ──timeUntilHooked 归零──▶ 收杆 ──▶ IDLE
 *   ▲                                                                            │
 *   └──────────────── 钓竿被拿走 / 水没了 / 缓存满（中止并退还电力）◀─────────────┘
 * </pre>
 *
 * <h3>一个周期的完整流程</h3>
 * <ol>
 *   <li><b>开周期</b>：检查 {@code 钓竿 → 水域 → 缓存空间 → 电网连接}，
 *       全部通过后<b>预扣</b> {@link MachineConfig#ENERGY_PER_CATCH}，
 *       再用饵钓附魔算出的缩短量掷出本次等待刻数，进入 {@link FishingPhase#WAITING}；</li>
 *   <li><b>等鱼</b>：每刻按原版的「下雨加速 / 无天空减速」规则推进 {@code timeUntilLured}；</li>
 *   <li><b>靠近</b>：{@code timeUntilLured} 归零后掷出 {@code timeUntilHooked} 并进入
 *       {@link FishingPhase#APPROACHING}。这一段是鱼<b>游向浮标、还没咬</b>的时间，
 *       不要读成「咬钩之后的等待」；</li>
 *   <li><b>咬钩 → 收杆</b>：{@code timeUntilHooked} 归零那一刻就是咬钩
 *       （原版在此播水花音效并置 {@code DATA_BITING}），机器在<b>同一 tick</b> 收杆，
 *       不走原版留给玩家的 {@code nibble} 反应窗口；</li>
 *   <li><b>结算</b>：掷原版战利品表 → 产物进缓存 → 播咬钩音效 → 扣钓竿耐久 → 用本杆经验修补。</li>
 * </ol>
 *
 * <h3>周期内不再检查电网与电量</h3>
 * 电是<b>预扣</b>的。已经开始的周期即使中途断线、电量被抽空也照样走完，
 * 不会出现「钓到一半电没了，这次白等」这种反直觉的结果（理由见
 * {@link MachineConfig#ENERGY_PER_CATCH}）。代价是周期<b>被中止</b>时会退还这笔电 ——
 * 因为那一杆并没有产出任何东西，不退等于收了钱不办事。
 *
 * <h3>为什么是「缓存满就停机」而不是「满了就掉地上」</h3>
 * 需求明令禁止「箱子满了还继续生成」。掉在地上等于把「装满」这个信号变成噪音，
 * 玩家会看到机器脚下一地垃圾却不知道哪里出了问题。停机 + 界面写明原因，
 * 才是可排查的行为。唯一的例外是防御性分支：产物多到 9 格都放不下时
 * （原版钓鱼恒定只掉一件，正常情况下不可达），剩余部分掉在机器上方而不是凭空消失。
 *
 * <h3>产物输出</h3>
 * 缓存里的东西由 {@link #pushOneItem} 每 {@link MachineConfig#OUTPUT_INTERVAL_TICKS} 刻
 * 往相邻容器搬一件，六个面都算。它<b>不受状态机影响</b>：机器停着也在搬。
 * 这不是顺手写在一起，而是必须如此 —— 否则「缓存满 → 停机 → 更不搬 → 永远满」
 * 就是一条死锁。分辨「没容器」与「容器满」的取值见 {@link #cacheBlockedReason}。
 */
public class AutoFishingMachineBlockEntity extends PowerAcceptorBlockEntity implements MenuProvider {

	// ------------------------------------------------------------ 界面数据同步
	//
	// 电量（存量 + 容量）由父类占了前 8 格（每个 long 4 格，原因见 ContainerDataCodec），
	// 这里在它之后追加本机特有的字段。
	//
	// 追加字段的规则只有一条：get(index) 必须直接读**真实字段**。
	// 服务端每 tick 会拿这份数据和上一次发出去的快照比对，变了才发包；
	// 若这里返回一个快照副本，比对结果永远相等，界面上的数就再也不会更新。

	/** 电网连接状态：1 = 已连接，0 = 未连接。 */
	public static final int DATA_GRID_LINKED = PowerAcceptorBlockEntity.DATA_COUNT;

	/** 钓鱼阶段，取值见 {@link FishingPhase}。 */
	public static final int DATA_PHASE = DATA_GRID_LINKED + 1;

	/** 停止原因，取值见 {@link PauseReason}。 */
	public static final int DATA_PAUSE_REASON = DATA_PHASE + 1;

	/** 当前阶段剩余刻数。 */
	public static final int DATA_TIMER = DATA_PAUSE_REASON + 1;

	/** 当前阶段总刻数 —— 进度条的分母。 */
	public static final int DATA_TIMER_TOTAL = DATA_TIMER + 1;

	/** 下钩点的开放水域状态，取值见 {@link WaterVerdict}。 */
	public static final int DATA_OPEN_WATER = DATA_TIMER_TOTAL + 1;

	/** 本机界面数据的格数（含父类的电量 8 格）。 */
	public static final int DATA_COUNT = DATA_OPEN_WATER + 1;

	/**
	 * 开放水域状态：还没判定过（机器刚放下、或读档后尚未重算）。
	 *
	 * <p>取值<b>不再在这里写字面量</b>，而是直接取 {@link WaterVerdict} 的线上值。
	 * 三个常量本来就与枚举同义，两处各写一遍 0/1/2 迟早会分叉，
	 * 而分叉的表现是「界面说开放、实际按封闭抽奖」这种不报错的错。
	 *
	 * <p>三个取值全部<b>非负</b>。原因是 {@code ContainerData} 走
	 * {@code ClientboundContainerSetDataPacket} 传输，那条通道是按无符号数编解码的，
	 * 传 -1 会在客户端读成一个很大的正数。用 0 当「未知」既避开了这个坑，
	 * 也让 {@code SimpleContainerData} 的默认初值天然就是「未知」。
	 */
	public static final int OPEN_WATER_UNKNOWN = WaterVerdict.UNKNOWN.state();

	/** 开放水域状态：有水但不算开放 —— 能钓，但钓不到宝藏。 */
	public static final int OPEN_WATER_CLOSED = WaterVerdict.CLOSED.state();

	/** 开放水域状态：开放水域，宝藏可进入抽取。 */
	public static final int OPEN_WATER_OPEN = WaterVerdict.OPEN.state();

	@Override
	public int getCount() {
		return DATA_COUNT;
	}

	@Override
	public int get(int index) {
		if (index == DATA_GRID_LINKED) {
			return gridLinked ? 1 : 0;
		}
		if (index == DATA_PHASE) {
			return phase.id();
		}
		if (index == DATA_PAUSE_REASON) {
			return pauseReason.id();
		}
		if (index == DATA_TIMER) {
			return remainingTicks();
		}
		if (index == DATA_TIMER_TOTAL) {
			return phaseTotalTicks;
		}
		if (index == DATA_OPEN_WATER) {
			return openWater;
		}
		return super.get(index);
	}

	// ------------------------------------------------------------ 物品栏

	/**
	 * 方块实体对外暴露的全部槽位。
	 *
	 * <p>用 {@link NonNullList} 而不是普通数组：原版约定容器里不允许出现 {@code null}，
	 * 空槽必须是 {@link ItemStack#EMPTY}。用普通数组很容易漏掉初始化，
	 * 之后某个 {@code getItem()} 返回 null 就会在很远的地方炸掉。
	 */
	private final NonNullList<ItemStack> items =
			NonNullList.withSize(MachineConfig.SLOT_COUNT, ItemStack.EMPTY);

	public AutoFishingMachineBlockEntity(BlockPos pos, BlockState state) {
		super(ModBlockEntities.AUTO_FISHING_MACHINE, pos, state);
	}

	// ------------------------------------------------------------ 电力基准
	//
	// 这三个值决定机器在电网里的身份：容量、能吃多快、能吐多快。
	// 具体数字全部来自 MachineConfig，这里只做转达，不在本类里写任何魔法数字。

	/** 内部能量缓冲容量。 */
	@Override
	public long getBaseMaxPower() {
		return MachineConfig.ENERGY_CAPACITY;
	}

	/** 最大输出速率。本机纯用电，恒为 0 —— 于是它这一侧的推流自然是空操作。 */
	@Override
	public long getBaseMaxOutput() {
		return MachineConfig.MAX_OUTPUT;
	}

	/** 最大输入速率。 */
	@Override
	public long getBaseMaxInput() {
		return MachineConfig.MAX_INPUT;
	}

	/**
	 * 缓冲满了就不再要电。
	 *
	 * <p>看起来只是省一点无谓的计算，实际影响更大：上游电缆每刻都在向邻居推电，
	 * 如果这里永远回 true，电会一直推进来又被立刻丢弃，电缆那侧会做一整轮空转账。
	 * 返回 false 让推流提前短路，是「多台机器同时在线也不掉 TPS」的一部分。
	 */
	@Override
	protected boolean canAcceptEnergy(Direction side) {
		return getStored() < getMaxStoredPower();
	}

	// ------------------------------------------------------------ 电网连接
	//
	// 推流模型下设备收不到「电网断了」的通知，只能自己隔一段时间去问一次。
	// 判定规则与两条分支的原因写在 GridLink 的注释里，这里只负责缓存与节流。

	/** 最近一次检测到的电网连接状态。 */
	private boolean gridLinked;

	/** 距离下一次重检还有多少刻。初始 0 → 第一次 serverTick 立刻检测。 */
	private int gridCheckCountdown;

	/**
	 * 本机是否接在电网/电源上。
	 *
	 * <p>与「缓冲里有多少电」是两件事：接了电但发电机停机时这里仍然是 {@code true}。
	 * 状态机把两者分成两种停止原因（{@code GRID_DISCONNECTED} / {@code NOT_ENOUGH_POWER}），
	 * 玩家才知道该去查线路还是查发电。
	 */
	public boolean isGridLinked() {
		return gridLinked;
	}

	// ------------------------------------------------------------ 钓鱼状态
	//
	// 计时器字段名直接沿用原版 FishingHook：timeUntilLured / timeUntilHooked。
	// 不换名字的收益很直接 —— 对着原版源码读本类时不需要做一次心算翻译。

	/** 当前阶段。 */
	private FishingPhase phase = FishingPhase.IDLE;

	/** 当前为什么没在钓鱼。{@link FishingPhase#IDLE} 时它才代表真正的停止原因。 */
	private PauseReason pauseReason = PauseReason.NO_ROD;

	/** 原版 {@code timeUntilLured}：还要等多少刻鱼才会来。 */
	private int timeUntilLured;

	/** 原版 {@code timeUntilHooked}：咬钩窗口还剩多少刻。 */
	private int timeUntilHooked;

	/**
	 * 当前阶段开始时的总刻数，纯粹给界面进度条当分母。
	 *
	 * <p>原版没有这个字段（它不需要画进度条）。这是一个<b>显示用派生值</b>，
	 * 不参与任何逻辑判断，所以存盘时一并保存只是为了让读档瞬间的进度条不跳一下。
	 */
	private int phaseTotalTicks;

	/** 下钩点。派生值，不存盘 —— 读档后 {@code spotCheckCountdown} 归零会立刻重算。 */
	private @Nullable FishingSpot spot;

	/** 距离下一次重新找水域还有多少刻。 */
	private int spotCheckCountdown;

	/** 下钩点的开放水域状态，取值见 {@code OPEN_WATER_*} 常量。派生值，不存盘。 */
	private int openWater = OPEN_WATER_UNKNOWN;

	/** 当前阶段还剩多少刻。 */
	public int remainingTicks() {
		return switch (phase) {
			case IDLE -> 0;
			case WAITING -> Math.max(0, timeUntilLured);
			case APPROACHING -> Math.max(0, timeUntilHooked);
		};
	}

	public FishingPhase getPhase() {
		return phase;
	}

	public PauseReason getPauseReason() {
		return pauseReason;
	}

	public int getOpenWaterState() {
		return openWater;
	}

	// ------------------------------------------------------------ tick

	@Override
	protected void serverTick() {
		// 两项检测各自节流。电网是玩家动作（接线/剪线），水域是玩家动方块，
		// 都不需要每刻查 —— 具体间隔与理由见 MachineConfig 里的两个常量。
		if (gridCheckCountdown-- <= 0) {
			gridCheckCountdown = MachineConfig.GRID_RECHECK_INTERVAL;
			refreshGridLink();
		}
		if (spotCheckCountdown-- <= 0) {
			spotCheckCountdown = MachineConfig.WATER_RECHECK_INTERVAL;
			refreshSpot();
		}

		if (getLevel() instanceof ServerLevel level) {
			// 输出排在钓鱼之前：先看能不能把缓存搬空，「缓存还放得下吗」这一 tick
			// 给出的才是最新答案，能少停机一刻是一刻。
			outputTick(level);
			tickFishing(level);
		}
	}

	private void refreshGridLink() {
		if (!(getLevel() instanceof ServerLevel serverLevel)) {
			return;
		}
		boolean linked = GridLink.isLinked(serverLevel, worldPosition);
		if (linked == gridLinked) {
			return;
		}
		gridLinked = linked;
		// 这只是一个布尔缓存，没必要为它单独刷包：
		// 界面要显示供电状态时走菜单的 ContainerData，那条通道本来就是按需、按秒级的。
		setChanged();
	}

	/**
	 * 重新确定下钩点。
	 *
	 * <p>顺着这次重算，如果下钩点<b>变了</b>，就顺手重算开放水域状态。
	 * 这样界面上「这片水能不能出宝藏」永远是当前地形的答案，
	 * 而且不需要把它存盘 —— 变的是地形，不是机器状态。
	 */
	private void refreshSpot() {
		if (!(getLevel() instanceof ServerLevel level)) {
			spot = null;
			openWater = OPEN_WATER_UNKNOWN;
			return;
		}

		FishingSpot found = WaterFinder.find(level, worldPosition, getFacing());
		if (Objects.equals(found, spot)) {
			return;
		}
		spot = found;

		if (found == null) {
			openWater = OPEN_WATER_UNKNOWN;
			return;
		}
		// 开放水域判定要扫 5×5×4 = 100 格，所以只在「下钩点变了」时做。
		// 一台不动的机器一年也不会触发几次。
		openWater = HookProbe.plain(level).probeOpenWater(found.water())
				? OPEN_WATER_OPEN
				: OPEN_WATER_CLOSED;
	}

	// ------------------------------------------------------------ 产物输出
	//
	// 这是一个与钓鱼状态机平行的任务：机器停着也照搬。
	// 必须如此，否则「缓存满 → 停机 → 不搬 → 永远满」是一条死锁。

	/** 距离下一次输出尝试还有多少刻。初始 0 → 第一次 serverTick 立刻尝试。 */
	private int outputCooldown;

	/**
	 * 输出节拍。
	 *
	 * <h2>为什么是轮询</h2>
	 * 原版的箱子和漏斗都不会通知邻居「我这里腾出位置了」，设备只能自己去问。
	 * 原版漏斗的解法是每刻问一次；本模组问一次要查六个方向，所以按
	 * {@link MachineConfig#OUTPUT_INTERVAL_TICKS}（8 刻，与漏斗搬运速度同频）节流。
	 */
	private void outputTick(ServerLevel level) {
		// 前置自减，周期正好是 OUTPUT_INTERVAL_TICKS 刻。
		// （上面两项检测用的是后置自减，那两处刻意不改：它们的实际周期是「间隔 + 1」，
		//   位移一刻无害，而改它们要重新验证 Phase 3 的实机结论。输出这一条会被测试
		//   逐刻断言，所以写准。）
		if (--outputCooldown > 0) {
			return;
		}
		outputCooldown = MachineConfig.OUTPUT_INTERVAL_TICKS;
		pushOneItem(level);
	}

	/**
	 * 从缓存里搬一件物品到相邻容器。
	 *
	 * <p>遍历顺序是「先按缓存格的顺序，再按 {@link AdjacentInventory#OUTPUT_ORDER} 的面顺序」：
	 * 从第 0 格开始找第一个搬得动的（格, 面）组合，搬一件就收工。
	 * 一次只碰一件，是为了让「上一格搬不动」不会拖住后面所有格子，
	 * 也为了与漏斗逐件搬运的语义对齐（理由见 {@link AdjacentInventory#insertOne}）。
	 *
	 * @return 是否搬动了一件
	 */
	private boolean pushOneItem(ServerLevel level) {
		for (int index = 0; index < MachineConfig.SLOT_CACHE_COUNT; index++) {
			ItemStack source = getCacheStack(index);
			if (source.isEmpty()) {
				continue;
			}

			for (Direction direction : AdjacentInventory.OUTPUT_ORDER) {
				Direction face = direction.getOpposite();
				Container target = AdjacentInventory.find(level, worldPosition.relative(direction));

				// 挡掉「长得像容器但塞不进东西」的方块。上游机器基类也实现了 Container，
				// 但它的 getContainerSize() 恒为 0 —— 不排除的话，机器会把贴着的电池
				// 当成一个「已经满了的容器」，停机原因从「没放容器」错报成「容器已满」。
				if (target == null || !AdjacentInventory.canReceive(target, face)) {
					continue;
				}
				// 与漏斗一致的早期退出：整个容器都满了就不用再逐槽试了。
				// 注意它只是「放不下任何东西」的快速判断，不代表这件物品一定塞得进去
				// （容器可能只是不收这一种），所以后面还要看 insertOne 的结果。
				if (AdjacentInventory.isFull(target, face)) {
					continue;
				}
				if (!AdjacentInventory.insertOne(target, face, source)) {
					continue;
				}

				source.shrink(1);
				if (source.isEmpty()) {
					setItem(MachineConfig.SLOT_CACHE_START + index, ItemStack.EMPTY);
				} else {
					setChanged();
				}
				return true;
			}
		}
		return false;
	}

	/**
	 * 缓存满了的时候，进一步分辨是「没容器可用」还是「容器也满了」。
	 *
	 * <p>这两种情况玩家要做的动作相反（去放一个 / 去清空一个），所以分成两种停机原因。
	 *
	 * <p>第三种情况 —— 有容器而且还有空位 —— 最多只会持续到下一个输出节拍
	 * （≤ {@link MachineConfig#OUTPUT_INTERVAL_TICKS} 刻），那时缓存里就有空格了。
	 * 它连一次界面刷新都撑不过去，所以不再单独区分，并入「缓存满」这一条。
	 */
	private PauseReason cacheBlockedReason(ServerLevel level) {
		boolean hasTarget = false;
		for (Direction direction : AdjacentInventory.OUTPUT_ORDER) {
			Direction face = direction.getOpposite();
			Container target = AdjacentInventory.find(level, worldPosition.relative(direction));
			if (target == null || !AdjacentInventory.canReceive(target, face)) {
				continue;
			}
			hasTarget = true;
			if (!AdjacentInventory.isFull(target, face)) {
				return PauseReason.CACHE_FULL;
			}
		}
		return hasTarget ? PauseReason.OUTPUT_FULL : PauseReason.CACHE_FULL;
	}

	// ------------------------------------------------------------ 钓鱼状态机

	private void tickFishing(ServerLevel level) {
		if (phase == FishingPhase.IDLE) {
			tryStartCycle(level);
		} else {
			advanceCycle(level);
		}
	}

	/**
	 * 开一个新周期。
	 *
	 * <p>条件检查有确定的先后顺序，为的是给出<b>最该先处理</b>的那条原因：
	 * 钓竿 → 水域 → 缓存 → 电网 → 电量。
	 * 例如一台没放钓竿又没接电的机器，说「没钓竿」比说「没接电」有用得多。
	 */
	private void tryStartCycle(ServerLevel level) {
		PauseReason reason = evaluateStartConditions(level);
		if (reason != PauseReason.NONE) {
			setPauseReason(reason);
			return;
		}

		// 预扣。放在所有前置条件之后，避免「条件不满足却先把电扣了」。
		// 用 tryUseExact 而不是先 getStored() 比较再扣：前者是原子的，且语义就是
		// 「够就扣、不够就一动不动」，不需要在这里重复一遍电量比较。
		if (!tryUseExact(MachineConfig.ENERGY_PER_CATCH)) {
			setPauseReason(PauseReason.NOT_ENOUGH_POWER);
			return;
		}

		beginWaiting(level);
		setPauseReason(PauseReason.NONE);
	}

	/**
	 * 开周期需要满足的条件。不包含电量 —— 那一项由预扣本身回答。
	 *
	 * <p>「缓存满了」这一条会进一步分辨成两种原因（没容器 / 容器满），
	 * 见 {@link #cacheBlockedReason}。
	 */
	private PauseReason evaluateStartConditions(ServerLevel level) {
		if (!isFishingRod(getRod())) {
			return PauseReason.NO_ROD;
		}
		if (spot == null) {
			return PauseReason.NO_WATER;
		}
		if (!hasCacheRoom()) {
			return cacheBlockedReason(level);
		}
		if (!gridLinked) {
			return PauseReason.GRID_DISCONNECTED;
		}
		return PauseReason.NONE;
	}

	/**
	 * 掷出本次等待刻数并进入 {@link FishingPhase#WAITING}。
	 *
	 * <p>结构与原版 {@code FishingHook#catchingFish} 的收尾分支逐句对应，
	 * 只有区间上下限换成了本项目的 {@link MachineConfig#MIN_LURE_WAIT} /
	 * {@link MachineConfig#MAX_LURE_WAIT}（原版是 {@code Mth.nextInt(random, 100, 600)}）：
	 * <pre>{@code
	 * this.timeUntilLured = Mth.nextInt(this.random, MIN_LURE_WAIT, MAX_LURE_WAIT);
	 * this.timeUntilLured = this.timeUntilLured - this.lureSpeed;
	 * }</pre>
	 * 另外多一次 {@code Math.max(MIN_WAIT_TICKS, …)}：它是防呆用的 ——
	 * 3 级以内的饵钓（最多缩 {@code 3 × LURE_TICKS_PER_LEVEL = 24} 刻）
	 * 压在 30 刻的区间下限上仍有 6 刻，够不到保底；但若将来把区间收得更窄、
	 * 或把 {@link MachineConfig#LURE_TICKS_PER_LEVEL} 调大，就轮到它兜住 ——
	 * 状态机拿到 0 刻会在同一刻内空转一轮，原版那边是玩家手动收放浮标，0 刻无所谓。
	 */
	private void beginWaiting(ServerLevel level) {
		RandomSource random = level.getRandom();
		// 饵钓：HookProbe 从原版 EnchantmentHelper 读回等级（换算链一步不改），
		// 再按本模组的区间把「每级缩短刻数」从原版的 100 标定到 LURE_TICKS_PER_LEVEL。
		// 不缩放的话任何等级都会把等待压到下限，附魔阶梯失效 —— 见 MachineConfig 的说明。
		int lureTicks = HookProbe.create(level, getRod())
				.lureTicksScaledTo(MachineConfig.LURE_TICKS_PER_LEVEL);

		int wait = Mth.nextInt(random, MachineConfig.MIN_LURE_WAIT, MachineConfig.MAX_LURE_WAIT)
				- lureTicks;
		timeUntilLured = Math.max(MachineConfig.MIN_WAIT_TICKS, wait);
		timeUntilHooked = 0;
		phaseTotalTicks = timeUntilLured;
		phase = FishingPhase.WAITING;
		// 方块状态里的 ACTIVE 就是给贴图用的「在运转」指示灯。
		// 上游的 setActive 自带「值没变就不写方块」的短路，所以不必在这里判断。
		setActive(true);
		setChanged();
	}

	/**
	 * 推进当前周期。
	 *
	 * <p>周期内只检查三件事：钓竿还在、水还在、缓存还有空位。
	 * <b>刻意不检查电网与电量</b> —— 电是预扣的，理由见类文档。
	 */
	private void advanceCycle(ServerLevel level) {
		if (!isFishingRod(getRod())) {
			abortCycle(PauseReason.NO_ROD);
			return;
		}
		if (spot == null) {
			abortCycle(PauseReason.NO_WATER);
			return;
		}
		if (!hasCacheRoom()) {
			abortCycle(PauseReason.CACHE_FULL);
			return;
		}

		if (timeUntilHooked > 0) {
			timeUntilHooked -= fishingSpeed(level, spot);
			if (timeUntilHooked <= 0) {
				// 归零 == 原版播 FISHING_BOBBER_SPLASH 并置 DATA_BITING 的那一刻，也就是「咬钩」。
				// 原版紧接着给 nibble = 20~40 刻，把这段时间留给玩家反应；
				// 机器不需要反应时间，所以在这一刻直接收杆 —— nibble 窗口一刻都不用。
				resolveCatch(level, spot);
			}
			return;
		}

		if (timeUntilLured > 0) {
			timeUntilLured -= fishingSpeed(level, spot);
			if (timeUntilLured <= 0) {
				// 鱼群到位。原版同一分支里紧接着给「鱼游向浮标」的倒计时取值 ——
				// 这段是咬钩**之前**的时间，不要读成咬钩之后的反应窗口。
				timeUntilHooked = Mth.nextInt(level.getRandom(),
						MachineConfig.MIN_BITE_WAIT, MachineConfig.MAX_BITE_WAIT);
				phaseTotalTicks = timeUntilHooked;
				phase = FishingPhase.APPROACHING;
			}
			return;
		}

		// 兜底：两个计时器都归零。正常路径不可达（开周期时已给 timeUntilLured 赋过值），
		// 保留它是为了对应原版 catchingFish 的收尾 else 分支，
		// 并且在存档被外部工具改坏时不至于让机器彻底卡死。
		beginWaiting(level);
	}

	/**
	 * 原版的「下雨加速 / 无天空减速」每刻判定。
	 *
	 * <p>速度可能是 0，那一 tick 计时器不推进。这是原版行为：
	 * 在看不到天空的地方（屋檐下、洞里、水下）钓鱼本来就忽快忽慢。
	 *
	 * <p><b>两次 {@code nextFloat()} 都必须执行</b>：写成
	 * {@code if (nextFloat() < 0.25F && isRaining)} 时第一次一定执行，
	 * 但若把顺序反过来或加上短路，随机序列就会与原版错位。
	 */
	private static int fishingSpeed(ServerLevel level, FishingSpot fishingSpot) {
		RandomSource random = level.getRandom();
		BlockPos above = fishingSpot.water().above();

		int speed = 1;
		if (random.nextFloat() < MachineConfig.VANILLA_RAIN_SPEED_CHANCE && level.isRainingAt(above)) {
			speed++;
		}
		if (random.nextFloat() < MachineConfig.VANILLA_NO_SKY_SPEED_CHANCE && !level.canSeeSky(above)) {
			speed--;
		}
		return speed;
	}

	/**
	 * 收杆：决定钓上来什么、放哪儿、以及钓竿的损耗。
	 *
	 * <p>步骤顺序照搬原版「先掷战利品、再扣耐久」，因为耐久只剩 1 点时
	 * 先修补后扣耐久会得到完全不同的结果（详见 {@link RodWear}）。
	 */
	private void resolveCatch(ServerLevel level, FishingSpot catchSpot) {
		ItemStack rod = getRod();

		// 1) 造替身并判定开放水域。判定结果写进替身，原版战利品表的宝藏条目会读它 ——
		//    也就是说「封闭水域不出宝藏」这件事完全由原版把关，这里一行判定逻辑都没重写。
		HookProbe probe = HookProbe.create(level, rod);
		openWater = probe.probeOpenWater(catchSpot.water()) ? OPEN_WATER_OPEN : OPEN_WATER_CLOSED;

		// 2) 掷原版战利品表。
		List<ItemStack> loot = LootRoller.roll(level, rod, probe, catchSpot);

		// 3) 进内部缓存。放不下的部分掉在机器上方 ——
		//    宁可让玩家去捡，也绝不凭空销毁物品（防复制的前提是先不丢东西）。
		//    原版钓鱼恒定只掉一件、而开周期时已确认有空格，所以这条分支正常情况下走不到。
		for (ItemStack stack : loot) {
			ItemStack leftover = insertIntoCache(stack);
			if (!leftover.isEmpty()) {
				Block.popResource(level, worldPosition.above(), leftover);
			}
		}

		playBiteSound(level, catchSpot);

		// 4) 钓竿磨损 + 修补。返回 true 表示这根钓竿正好断在这一杆。
		if (RodWear.apply(level, rod, level.getRandom())) {
			level.playSound(null, worldPosition, SoundEvents.ITEM_BREAK.value(), SoundSource.BLOCKS, 0.8F, 1.0F);
		}

		// itemStack 是在原容器实例上原地改的耐久，容器不会自己收到通知。
		setChanged();
		syncWithAll();

		// 5) 本周期结束。下一 tick 会重新走一遍条件检查与预扣 ——
		//    「每杆之间重新确认电网和电量」就是靠这一步实现的。
		endCycle();
	}

	/** 咬钩音效。位置、音量、音调抖动全部照抄原版在浮标处播放的那一句。 */
	private static void playBiteSound(ServerLevel level, FishingSpot catchSpot) {
		RandomSource random = level.getRandom();
		BlockPos water = catchSpot.water();
		level.playSound(null,
				water.getX() + 0.5D, water.getY() + 0.5D, water.getZ() + 0.5D,
				SoundEvents.FISHING_BOBBER_SPLASH, SoundSource.NEUTRAL,
				0.25F, 1.0F + (random.nextFloat() - random.nextFloat()) * 0.4F);
	}

	/** 中止当前周期：退还预扣的电力，回到 {@link FishingPhase#IDLE}。 */
	private void abortCycle(PauseReason reason) {
		// 退还。这一杆什么都没产出，扣着电就是「收了钱不办事」。
		// 不存在刷电的可能：退的是恰好扣掉的那一份，回到同一个缓冲里。
		addEnergy(MachineConfig.ENERGY_PER_CATCH);
		endCycle();
		setPauseReason(reason);
	}

	private void endCycle() {
		phase = FishingPhase.IDLE;
		timeUntilLured = 0;
		timeUntilHooked = 0;
		phaseTotalTicks = 0;
		// 熄灭贴图上的运转指示灯。停机时它亮着会把「机器停了」这件事藏起来。
		setActive(false);
		setChanged();
	}

	/** 只在值真的变化时标脏，免得每 tick 都触发一次区块保存。 */
	private void setPauseReason(PauseReason reason) {
		if (pauseReason == reason) {
			return;
		}
		pauseReason = reason;
		setChanged();
	}

	/**
	 * 缓存里还有没有空槽。
	 *
	 * <p>刻意用「有没有空槽」而不是「有没有槽放得下这件具体物品」：
	 * 后者的答案取决于掷出什么，而停顿判定必须在<b>掷之前</b>就能回答。
	 * 由于原版钓鱼恒定只掉一件，一个空槽永远够用，这条保守判定不会漏接。
	 *
	 * <p>这条判定从 Phase 6 起变得容易满足了 —— 输出每 8 刻腾一次位置。
	 * 它仍然是「缓存满」的准确判据：只要有空格，这一杆就一定放得下。
	 * 反过来，9 格全非空并不代表搬不出去，所以「为什么搬不出去」交给
	 * {@link #cacheBlockedReason} 去查一次相邻容器，而不是在这里猜。
	 */
	private boolean hasCacheRoom() {
		for (int index = 0; index < MachineConfig.SLOT_CACHE_COUNT; index++) {
			if (getCacheStack(index).isEmpty()) {
				return true;
			}
		}
		return false;
	}

	/**
	 * 把物品塞进缓存槽，返回没塞进去的部分。
	 *
	 * <p>两轮：先并进同类堆，再找空槽。顺序不能反 —— 反了会把 9 个槽拆成一堆 1 个的小堆，
	 * 缓存看起来"满了"，实际每格还能再放 63 个。
	 *
	 * <p>槽位容量取 {@code min(容器单格上限, 该物品自身上限)}：
	 * 容器上限是 64（见 {@link #getMaxStackSize()}，不要用 {@code Container} 默认的 99），
	 * 物品上限则挡住工具这类不可堆叠的东西。
	 */
	private ItemStack insertIntoCache(ItemStack stack) {
		if (stack.isEmpty()) {
			return ItemStack.EMPTY;
		}
		ItemStack remaining = stack;
		int containerMax = getMaxStackSize();
		int capacity = Math.min(containerMax, remaining.getMaxStackSize());

		// 第一轮：并进同类物品堆。
		for (int index = 0; index < MachineConfig.SLOT_CACHE_COUNT && !remaining.isEmpty(); index++) {
			ItemStack target = getCacheStack(index);
			if (target.isEmpty() || !ItemStack.isSameItemSameComponents(target, remaining)) {
				continue;
			}
			int space = capacity - target.getCount();
			if (space <= 0) {
				continue;
			}
			int moved = Math.min(space, remaining.getCount());
			target.grow(moved);
			remaining.shrink(moved);
			setChanged();
		}

		// 第二轮：放进空槽。
		for (int index = 0; index < MachineConfig.SLOT_CACHE_COUNT && !remaining.isEmpty(); index++) {
			if (!getCacheStack(index).isEmpty()) {
				continue;
			}
			int moved = Math.min(capacity, remaining.getCount());
			setItem(MachineConfig.SLOT_CACHE_START + index, remaining.copyWithCount(moved));
			remaining.shrink(moved);
		}

		return remaining;
	}

	// ------------------------------------------------------------ 容器

	@Override
	public int getContainerSize() {
		return items.size();
	}

	@Override
	public boolean isEmpty() {
		for (ItemStack stack : items) {
			if (!stack.isEmpty()) {
				return false;
			}
		}
		return true;
	}

	@Override
	public ItemStack getItem(int slot) {
		return items.get(slot);
	}

	@Override
	public ItemStack removeItem(int slot, int amount) {
		ItemStack stack = ContainerHelper.removeItem(items, slot, amount);
		if (!stack.isEmpty()) {
			setChanged();
		}
		return stack;
	}

	@Override
	public ItemStack removeItemNoUpdate(int slot) {
		return ContainerHelper.takeItem(items, slot);
	}

	@Override
	public void setItem(int slot, ItemStack stack) {
		items.set(slot, stack);
		setChanged();
	}

	/**
	 * 单槽容量上限。取 64 与普通箱子一致。
	 *
	 * <p>{@code Container} 接口的默认值是 99，那是给「不分堆的特殊容器」用的。
	 * 不覆写的话，菜单里的快速移动（shift 点击）会按 99 计算目标槽位，
	 * 出现「一次搬 99 个但物品只有 64 上限」这类对不上的行为。
	 */
	@Override
	public int getMaxStackSize() {
		return 64;
	}

	/**
	 * 槽位准入规则。
	 *
	 * <p><b>钓竿槽</b>（{@link MachineConfig#SLOT_ROD}）：只接受钓竿，
	 * 判定见 {@link #isFishingRod(ItemStack)}。
	 *
	 * <p><b>缓存槽</b>：一律不接受外部放入。这 9 个槽是机器自己的产物暂存区，
	 * 由自动输出逻辑负责清空。允许玩家往里塞东西会带来两个真问题：
	 * 一是玩家可以把缓存当成免费箱子用，绕过了「机器满了要停下来」的设计；
	 * 二是「缓存满 → 停机」的判定会被玩家的手动塞入误触发。
	 * 取出不受限制，避免玩家放进去的东西取不出来。
	 */
	@Override
	public boolean canPlaceItem(int slot, ItemStack stack) {
		return slot == MachineConfig.SLOT_ROD && isFishingRod(stack);
	}

	@Override
	public void clearContent() {
		items.clear();
	}

	/**
	 * 该物品能否放进钓竿槽。
	 *
	 * <p>「或」的两条分支各有用处，说明见 {@link ModTags}：
	 * 继承原版钓竿类的走第一条，自己实现钓鱼逻辑的模组钓竿走第二条（数据包即可扩展）。
	 */
	public static boolean isFishingRod(ItemStack stack) {
		if (stack.isEmpty()) {
			return false;
		}
		return stack.getItem() instanceof FishingRodItem
				|| stack.typeHolder().is(ModTags.Items.FISHING_RODS);
	}

	// ------------------------------------------------------------ 便捷读取
	// 后面几个阶段（状态机、菜单）反复要用，集中在这里，避免各处直接算槽位下标。

	/** 当前钓竿。没有钓竿时返回 {@link ItemStack#EMPTY}。 */
	public ItemStack getRod() {
		return items.get(MachineConfig.SLOT_ROD);
	}

	/** 缓存槽 {@code index}（0 起）里的物品。 */
	public ItemStack getCacheStack(int index) {
		return items.get(MachineConfig.SLOT_CACHE_START + index);
	}

	// ------------------------------------------------------------ 同步

	/**
	 * 电量变化时标脏并请求同步。
	 *
	 * <p>{@code syncWithAll()} 不是「立刻发包」——父类把它合并到最长 20 刻一次的同步窗口里。
	 * 这一点很重要：进电是每刻都在发生的事，如果每刻都发一次方块实体包，
	 * 几十台机器就足以把玩家的带宽打满。
	 */
	@Override
	protected void onEnergyChanged() {
		setChanged();
		syncWithAll();
	}

	// ------------------------------------------------------------ 界面
	//
	// 只要方块实体实现 MenuProvider，玩家空手右键就会被 BlockMachineBase 打开界面 ——
	// 方块类那边不需要写任何代码（它调用的 getMenuProvider 会从方块实体上取）。

	@Override
	public Component getDisplayName() {
		return Component.translatable("block.cyxautofishingmachine.auto_fishing_machine");
	}

	/**
	 * 服务端在玩家右键时调用，把「方块实体本身」当作容器交给菜单。
	 *
	 * <p>客户端那条路走的是另一个构造器（{@code (int, Inventory)}），
	 * 它会新建一个等尺寸的空壳容器 —— 所以菜单类型上不需要携带方块坐标，
	 * 也就不必动用 Fabric 的 {@code ExtendedMenuType}。
	 */
	@Nullable
	@Override
	public AbstractContainerMenu createMenu(int containerId, Inventory playerInventory, Player player) {
		return new AutoFishingMachineMenu(containerId, playerInventory, this);
	}

	// ------------------------------------------------------------ 存档

	/** NBT 键：电网连接状态。 */
	private static final String KEY_GRID_LINKED = "GridLinked";

	/** NBT 键：钓鱼阶段。 */
	private static final String KEY_PHASE = "FishingPhase";

	/** NBT 键：等待计时器。 */
	private static final String KEY_TIME_UNTIL_LURED = "TimeUntilLured";

	/** NBT 键：咬钩窗口计时器。 */
	private static final String KEY_TIME_UNTIL_HOOKED = "TimeUntilHooked";

	/** NBT 键：当前阶段总刻数（只为进度条，见 {@link #phaseTotalTicks}）。 */
	private static final String KEY_PHASE_TOTAL = "PhaseTotal";

	/** NBT 键：停止原因（只为读档瞬间界面不闪，见 {@link #pauseReason}）。 */
	private static final String KEY_PAUSE_REASON = "PauseReason";

	@Override
	protected void loadAdditional(ValueInput input) {
		super.loadAdditional(input);
		// 电量由父类从 "PowerAcceptor" 子节点读取，这里只管物品栏、状态机与电网状态。
		ContainerHelper.loadAllItems(input, items);

		this.gridLinked = input.getBooleanOr(KEY_GRID_LINKED, false);
		this.phase = FishingPhase.byId(input.getIntOr(KEY_PHASE, FishingPhase.IDLE.id()));
		// 计时器读进来后钳到非负：负数会让 advanceCycle 里的 `> 0` 判断全部落空，
		// 直接掉进兜底分支。钳一次比在状态机里到处防御便宜。
		this.timeUntilLured = Math.max(0, input.getIntOr(KEY_TIME_UNTIL_LURED, 0));
		this.timeUntilHooked = Math.max(0, input.getIntOr(KEY_TIME_UNTIL_HOOKED, 0));
		this.phaseTotalTicks = Math.max(0, input.getIntOr(KEY_PHASE_TOTAL, 0));
		this.pauseReason = PauseReason.byId(input.getIntOr(KEY_PAUSE_REASON, PauseReason.NO_ROD.id()));

		// 下面全是派生值，一律不存盘，读档后重算：
		//  - 电网状态与拓扑有关，区块载入后可能已经变了（比如线被拆了）
		//  - 下钩点与开放水域与地形有关，同理
		// 两者都把倒计时清零，让下一次 serverTick 立刻重算，不做任何「看起来对」的乐观假设。
		this.gridCheckCountdown = 0;
		this.spotCheckCountdown = 0;
		this.spot = null;
		this.openWater = OPEN_WATER_UNKNOWN;
		// 输出倒计时也清零。缓存是存盘的，读档后可能已经堆了一批东西，
		// 早 8 刻开始搬没有副作用；反过来「等满 8 刻再搬」只会多停一拍。
		this.outputCooldown = 0;

		// 注意：**不重置 phase**。计时器只在 tick 里推进，而区块卸载期间没有 tick，
		// 所以「读档后接着等」恰恰是正确的语义；把阶段重置成 IDLE 反而会
		// 在每次区块卸载时吞掉已经预扣的那 500 FE。
	}

	@Override
	protected void saveAdditional(ValueOutput output) {
		super.saveAdditional(output);
		ContainerHelper.saveAllItems(output, items);

		// 电网状态是**派生值**，本来不必存盘（20 刻内必定重算）。
		// 写进去是为了可排查：服主遇到「机器不动」，一条
		// /data get block <坐标> GridLinked 就能区分「没接电」和「接了电但没电」，
		// 否则只能靠猜。代价是 1 个布尔。
		output.putBoolean(KEY_GRID_LINKED, gridLinked);

		// 状态机则**必须**存盘：它携带的是「已经付过钱的那一杆」，
		// 丢掉就等于每次区块卸载都白扣一次电。
		output.putInt(KEY_PHASE, phase.id());
		output.putInt(KEY_TIME_UNTIL_LURED, timeUntilLured);
		output.putInt(KEY_TIME_UNTIL_HOOKED, timeUntilHooked);
		output.putInt(KEY_PHASE_TOTAL, phaseTotalTicks);
		// 停止原因其实下一刻就会被重算，存它只是为了让读档瞬间的界面不显示错误的原因。
		output.putInt(KEY_PAUSE_REASON, pauseReason.id());
	}
}
