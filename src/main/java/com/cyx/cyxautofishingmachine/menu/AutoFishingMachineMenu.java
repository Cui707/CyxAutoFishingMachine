package com.cyx.cyxautofishingmachine.menu;

import com.cyx.crimsoncoppergrid.common.menu.MachineMenu;
import com.cyx.crimsoncoppergrid.common.powerSystem.PowerAcceptorBlockEntity;
import com.cyx.cyxautofishingmachine.blockentity.AutoFishingMachineBlockEntity;
import com.cyx.cyxautofishingmachine.config.MachineConfig;
import com.cyx.cyxautofishingmachine.fishing.FishingPhase;
import com.cyx.cyxautofishingmachine.fishing.PauseReason;
import com.cyx.cyxautofishingmachine.fishing.WaterVerdict;
import com.cyx.cyxautofishingmachine.init.ModMenuTypes;

import net.minecraft.util.Mth;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * 自动钓鱼机界面（服务端与客户端共用一份逻辑）。
 *
 * <h2>两个构造器</h2>
 * <ul>
 *   <li><b>服务端</b> {@code (int, Inventory, AutoFishingMachineBlockEntity)} ——
 *       直接把方块实体当容器，它的 {@code canPlaceItem} 就是槽位准入的权威；</li>
 *   <li><b>客户端</b> {@code (int, Inventory)} —— 新建一个同尺寸的空壳容器，
 *       里面的物品由服务端发来的槽位同步包填入。</li>
 * </ul>
 * 两者最终都汇到私有的三参数构造器，槽位布局只有一份代码。
 *
 * <h2>槽位布局</h2>
 * <pre>
 *   面板区域 x: 7~169, y: 16~78
 *
 *   ┌────────────────────────────────────────────────┐
 *   │ [钓竿]                          ┌──────────┐   │
 *   │                                 │ 缓存 3×3 │   │
 *   │ [██████ 电量条 ██████]          │          │   │
 *   │ [▬▬▬▬▬ 钓鱼进度条 ▬▬▬]          └──────────┘   │
 *   │ 状态一句话（暂停原因 / 当前阶段）               │
 *   └────────────────────────────────────────────────┘
 * </pre>
 * 坐标常量放在这里而不是界面类里：菜单决定槽位在哪儿，界面只是照着画。
 * 两边各写一套迟早会对不上。
 */
public class AutoFishingMachineMenu extends MachineMenu {

	// ------------------------------------------------------------ 布局
	// 系统提示：这些常量同时被 AutoFishingMachineScreen 读取，改一处即可。

	/** 钓竿槽左上角。 */
	public static final int ROD_X = 20;
	public static final int ROD_Y = 20;

	/** 缓存区（3×3）左上角。 */
	public static final int CACHE_X = 110;
	public static final int CACHE_Y = 17;

	/** 缓存区的列数。9 个槽拆成 3×3 —— 横排 9 格宽 162px，这个面板放不下。 */
	public static final int CACHE_COLUMNS = 3;

	/** 相邻槽位的间距（原版标准）。 */
	public static final int SLOT_STEP = 18;

	/** 电量条：横条，位于钓竿槽下方。 */
	public static final int ENERGY_BAR_X = 20;
	public static final int ENERGY_BAR_Y = 42;
	public static final int ENERGY_BAR_WIDTH = 82;
	public static final int ENERGY_BAR_HEIGHT = 10;

	/** 电量条内文字的居中基准线。 */
	public static final int ENERGY_CENTER_X = ENERGY_BAR_X + ENERGY_BAR_WIDTH / 2;

	/**
	 * 钓鱼进度条：紧贴电量条下方的一条细横条。
	 *
	 * <p>刻意比电量条矮（5px 对 10px）：它表示的是「这一杆还剩多久」这种一次性信息，
	 * 不需要抢电量那种「机器还能不能动」的视觉权重。
	 */
	public static final int PROGRESS_BAR_X = ENERGY_BAR_X;
	public static final int PROGRESS_BAR_Y = 57;
	public static final int PROGRESS_BAR_WIDTH = ENERGY_BAR_WIDTH;
	public static final int PROGRESS_BAR_HEIGHT = 5;

	/**
	 * 状态文字的左上角。
	 *
	 * <p>横向只能用到 106 —— 再往右就撞上缓存区的第三排槽位了（缓存从 x=110 起，
	 * 三排 18px 下来会盖到 y=70）。这条限制决定了状态文本必须短，
	 * 所以「还剩多少秒」交给进度条表达，这里只说要紧的一句话。
	 */
	public static final int STATUS_X = 20;
	public static final int STATUS_Y = 67;

	/**
	 * 开放水域指示器：钓竿槽右侧那块空地上的两行常驻读数。
	 *
	 * <p>位置是算出来的，不是试出来的：面板 7~169 宽，钓竿槽占 20~36，
	 * 缓存区从 110 起 —— 于是 x 42~109 这一段整块空着。
	 *
	 * <p>纵向与钓竿槽对齐（槽 20~36，两行各 9 像素 → 20 与 30），
	 * 让「钓竿」和「这片水」在视觉上属于同一行 —— 它们合起来才是
	 * 「这根竿插在这片水里」这一件事。
	 *
	 * <p>拆成「表头 + 结论」两行而不是一行，理由见
	 * {@link WaterVerdict#LABEL_KEY}：一行放不下中英两种语言。
	 */
	public static final int WATER_X = 42;

	/** 指示器表头那一行的基线。 */
	public static final int WATER_Y = 20;

	/** 指示器结论那一行的基线。 */
	public static final int WATER_VALUE_Y = 30;

	/**
	 * 指示器可用宽度 —— 到缓存区左侧留 2 像素为止。
	 *
	 * <p>这个值被客户端测试用来断言「三种结论值在任何语言下都不会压到缓存槽上」。
	 * 写成常量而不是散在界面类里，是为了让那条断言有唯一的分母可引用。
	 */
	public static final int WATER_MAX_WIDTH = CACHE_X - WATER_X - 2;

	// ------------------------------------------------------------ 构造

	/** 客户端：空壳容器 + 空壳数据，实际内容靠同步包填入。 */
	public AutoFishingMachineMenu(int containerId, Inventory playerInventory) {
		this(containerId, playerInventory,
				new SimpleContainer(MachineConfig.SLOT_COUNT),
				new SimpleContainerData(AutoFishingMachineBlockEntity.DATA_COUNT));
	}

	/** 服务端：方块实体既是容器也是数据源。 */
	public AutoFishingMachineMenu(int containerId, Inventory playerInventory,
			AutoFishingMachineBlockEntity machine) {
		this(containerId, playerInventory, machine, machine);
	}

	private AutoFishingMachineMenu(int containerId, Inventory playerInventory,
			Container machine, ContainerData data) {
		super(ModMenuTypes.AUTO_FISHING_MACHINE, containerId, playerInventory, machine, data);
	}

	// ------------------------------------------------------------ 槽位

	@Override
	protected void addMachineSlots(Container machine) {
		addSlot(new MachineSlot(machine, MachineConfig.SLOT_ROD, ROD_X, ROD_Y));

		for (int index = 0; index < MachineConfig.SLOT_CACHE_COUNT; index++) {
			int column = index % CACHE_COLUMNS;
			int row = index / CACHE_COLUMNS;
			addSlot(new MachineSlot(machine,
					MachineConfig.SLOT_CACHE_START + index,
					CACHE_X + column * SLOT_STEP,
					CACHE_Y + row * SLOT_STEP));
		}
	}

	/**
	 * 机器槽位：准入完全交给容器（也就是方块实体）判断。
	 *
	 * <h2>为什么不在菜单里重写一遍规则</h2>
	 * 漏斗、投掷器这类外部自动化走的是 {@code Container#canPlaceItem}，
	 * 而界面走的是这个 {@code Slot#mayPlace}。两者若各写一套规则，
	 * 迟早会出现「漏斗塞不进、界面塞得进」这种只在某一条路径上成立的漏洞 —— 那就是复制漏洞的来源。
	 * 委托之后两个入口共用一个判定，只有一处需要维护。
	 *
	 * <p>于是「缓存槽只能取不能放」这件事不需要在这里写死：
	 * {@code AutoFishingMachineBlockEntity#canPlaceItem} 已经对缓存槽返回 false。
	 *
	 * <p><b>取出不受限制</b>（{@code mayPickup} 默认 true），
	 * 否则玩家误放进缓存的东西就永远拿不回来了。
	 */
	private static class MachineSlot extends Slot {

		MachineSlot(Container container, int index, int x, int y) {
			super(container, index, x, y);
		}

		@Override
		public boolean mayPlace(ItemStack stack) {
			return container.canPlaceItem(getContainerSlot(), stack);
		}
	}

	// ------------------------------------------------------------ 快速移动

	/**
	 * 覆写快速移动，让「合并进同类物品堆」这一步也受槽位准入约束。
	 *
	 * <h2>为什么必须覆写</h2>
	 * 26.3 原版 {@code AbstractContainerMenu#moveItemStackTo} 的循环里有两个分支，
	 * 但<b>只有「找空槽放入」那一支</b>会调用 {@code Slot#mayPlace}：
	 *
	 * <pre>{@code
	 * // 分支一：合并进已有的同类物品堆 —— 没有任何准入检查
	 * if (!target.isEmpty() && ItemStack.isSameItemSameComponents(itemStack, target)) { ... }
	 *
	 * // 分支二：放进空槽 —— 这里有 mayPlace
	 * if (target.isEmpty() && slot.mayPlace(itemStack)) { ... }
	 * }</pre>
	 *
	 * <p>这个疏漏对原版容器无害 —— 它们的槽位本来就都能放东西。
	 * 但本模组的缓存槽是<b>只出不进</b>的（{@code canPlaceItem} 恒为 false），
	 * 于是出现一条侧信道：只要缓存里已经有一份同类物品，玩家就能 shift 点击
	 * 把背包里的同类物品合并进去，完全绕开 {@code canPlaceItem}。
	 *
	 * <p>危害不是复制（物品总数守恒、不增不减），而是绕过设计意图：
	 * 缓存会被玩家当成免费箱子，Phase 6 依赖的「缓存满 → 停机」保护也跟着失效。
	 *
	 * <p>这不是「读代码觉得可能有问题」，而是 GameTest
	 * {@code quickMoveRejectsNonRodIntoMachine} <b>实测失败</b>暴露出来的 ——
	 * 修复前，玩家背包里 64 条鱼会变成「缓存 64 条 + 背包剩 1 条」。
	 *
	 * <p>本覆写与原版逐句对应，唯一差别是合并分支多了一次 {@code slot.mayPlace(stack)}。
	 * 玩家背包槽位的 {@code mayPlace} 默认就是 true，所以那一侧的搬运行为没有变化。
	 */
	@Override
	protected boolean moveItemStackTo(ItemStack stack, int startIndex, int endIndex, boolean reverseDirection) {
		boolean moved = false;
		int index = reverseDirection ? endIndex - 1 : startIndex;

		// 第一轮：合并进已有的同类物品堆。
		if (stack.isStackable()) {
			while (!stack.isEmpty() && (reverseDirection ? index >= startIndex : index < endIndex)) {
				Slot slot = slots.get(index);
				ItemStack target = slot.getItem();
				if (slot.mayPlace(stack) && !target.isEmpty()
						&& ItemStack.isSameItemSameComponents(stack, target)) {
					int total = target.getCount() + stack.getCount();
					int maxStackSize = slot.getMaxStackSize(target);
					if (total <= maxStackSize) {
						stack.setCount(0);
						target.setCount(total);
						slot.setChanged();
						moved = true;
					} else if (target.getCount() < maxStackSize) {
						stack.shrink(maxStackSize - target.getCount());
						target.setCount(maxStackSize);
						slot.setChanged();
						moved = true;
					}
				}
				index += reverseDirection ? -1 : 1;
			}
		}

		// 第二轮：放进空槽（与原版一致，含 mayPlace 检查）。
		if (!stack.isEmpty()) {
			index = reverseDirection ? endIndex - 1 : startIndex;
			while (reverseDirection ? index >= startIndex : index < endIndex) {
				Slot slot = slots.get(index);
				if (slot.getItem().isEmpty() && slot.mayPlace(stack)) {
					int maxStackSize = slot.getMaxStackSize(stack);
					slot.setByPlayer(stack.split(Math.min(stack.getCount(), maxStackSize)));
					slot.setChanged();
					moved = true;
					break;
				}
				index += reverseDirection ? -1 : 1;
			}
		}

		return moved;
	}

	// ------------------------------------------------------------ 界面读取

	/** 缓冲里的电量。 */
	public long getStored() {
		return readLong(PowerAcceptorBlockEntity.DATA_STORED);
	}

	/** 缓冲容量。 */
	public long getCapacity() {
		return readLong(PowerAcceptorBlockEntity.DATA_CAPACITY);
	}

	/**
	 * 是否接在电网/电源上。
	 *
	 * <p>这是「接上了没」，不是「有没有电」—— 一张接通但发电机停机的电网同样是 true。
	 * 两件事分开呈现，玩家才知道该去检查线路还是去检查发电。
	 */
	public boolean isGridLinked() {
		return readData(AutoFishingMachineBlockEntity.DATA_GRID_LINKED) != 0;
	}

	// ------------------------------------------------------------ 钓鱼状态读取
	//
	// 下面这组 getter 把「编号 → 语义」的翻译收在菜单里，
	// 界面类只跟枚举和原始数值打交道，不需要知道 ContainerData 的下标。
	// 下标写错一格不会报错、只会显示错东西，所以只留一处出错的机会。

	/** 当前钓鱼阶段。 */
	public FishingPhase getPhase() {
		return FishingPhase.byId(readData(AutoFishingMachineBlockEntity.DATA_PHASE));
	}

	/** 当前为什么没在钓鱼。 */
	public PauseReason getPauseReason() {
		return PauseReason.byId(readData(AutoFishingMachineBlockEntity.DATA_PAUSE_REASON));
	}

	/** 当前阶段剩余刻数。 */
	public int getRemainingTicks() {
		return readData(AutoFishingMachineBlockEntity.DATA_TIMER);
	}

	/** 当前阶段总刻数（进度条分母）。 */
	public int getTotalTicks() {
		return readData(AutoFishingMachineBlockEntity.DATA_TIMER_TOTAL);
	}

	/**
	 * 下钩点的开放水域状态，取值见
	 * {@link AutoFishingMachineBlockEntity#OPEN_WATER_OPEN} 等常量。
	 */
	public int getOpenWaterState() {
		return readData(AutoFishingMachineBlockEntity.DATA_OPEN_WATER);
	}

	/**
	 * 下钩点的水域结论，界面直接用它取标签、提示与颜色。
	 *
	 * <p>翻译在这里做掉，界面类就只跟枚举打交道 ——
	 * 「0/1/2 是什么意思」这个问题在界面里不存在，
	 * 也就不可能出现「标签按一个解释、颜色按另一个解释」的错位。
	 */
	public WaterVerdict getWaterVerdict() {
		return WaterVerdict.fromState(getOpenWaterState());
	}

	/**
	 * 当前阶段已完成的比例，0~1。
	 *
	 * <p>分母为 0（刚开周期、或阶段已结束）时返回 0 而不是 NaN：
	 * NaN 一路传到绘制代码里会变成「整条进度条消失」这种毫无线索的现象。
	 */
	public double getPhaseProgress() {
		int total = getTotalTicks();
		if (total <= 0) {
			return 0.0D;
		}
		int remaining = getRemainingTicks();
		double done = (double) (total - remaining) / total;
		return Mth.clamp(done, 0.0D, 1.0D);
	}
}
