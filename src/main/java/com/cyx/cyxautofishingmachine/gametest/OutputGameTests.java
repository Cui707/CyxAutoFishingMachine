package com.cyx.cyxautofishingmachine.gametest;

import com.cyx.crimsoncoppergrid.common.blocks.BlockMachineBase;
import com.cyx.cyxautofishingmachine.blockentity.AutoFishingMachineBlockEntity;
import com.cyx.cyxautofishingmachine.config.MachineConfig;
import com.cyx.cyxautofishingmachine.fishing.FishingPhase;
import com.cyx.cyxautofishingmachine.fishing.PauseReason;
import com.cyx.cyxautofishingmachine.init.ModBlocks;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.Container;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;

/**
 * 产物输出（相邻容器）的自动化测试。
 *
 * <h2>为什么也是手动 tick</h2>
 * 与 {@link FishingGameTests} 同理由：输出节拍是 8 刻，靠真实时间推进做不了精确断言。
 * 直接调 {@code MachineBaseBlockEntity#tick}，一刻一次调用，于是
 * 「第 8 刻搬了、第 9 刻才搬第二件」这种话可以被逐刻钉死。
 *
 * <h2>场景为什么可以这么小</h2>
 * 输出<b>不走钓鱼状态机</b>：机器停着也照搬。所以大部分用例只需要
 * 「一块地 + 一台机器 + 一个容器」，连水都不用放。
 * 只有要断言「停机原因」和「恢复钓鱼」的几条才需要水与电 —— 因为那两条路径要
 * 先通过 {@code 钓竿 → 水域} 两道检查才轮到缓存那一道。
 */
public class OutputGameTests {

	// ------------------------------------------------------------ 基础场景
	//
	// 与 FishingGameTests 的池塘场景不同，这里只搭最小的一摊：
	//
	//   y=2  ... B/y ...        'B' = 电池（可选）   'C' = 堆肥桶（个别用例）
	//   y=1  ..~ M ...          '~' = 水   'M' = 机器（朝向 WEST，在 x=3）
	//   y=0  #########          '#' = 石头（也是放箱子的地基）

	/** 机器。朝向 WEST，正面（x=2）是水。 */
	private static final BlockPos MACHINE = new BlockPos(3, 1, 3);

	/** 机器朝向。 */
	private static final Direction FACING = Direction.WEST;

	/** 正面那一格水。 */
	private static final BlockPos WATER = new BlockPos(2, 1, 3);

	/** 机器正下方。放了容器就用它，没放就是地基石头。 */
	private static final BlockPos DOWN = MACHINE.below();

	/** 机器正上方。放电池或被堆肥桶占位。 */
	private static final BlockPos UP = MACHINE.above();

	/** 机器北侧。用来放第二个容器 / 第二台机器。 */
	private static final BlockPos NORTH = MACHINE.north();

	/** 大箱子的另一半：与下方那个箱子沿 X 轴相邻。 */
	private static final BlockPos CHEST_HALF = DOWN.east();

	/** 充当「电网」的电池方块（上游 Mod）。理由见 {@link FishingGameTests}。 */
	private static final Block GRID_BATTERY = com.cyx.crimsoncoppergrid.init.ModBlocks.BATTERY;

	/** GameTest 通过入口点反射实例化本类，必须有公开的无参构造器。 */
	public OutputGameTests() {
	}

	// ============================================================ 基本搬运

	/**
	 * 一件物品走进相邻箱子：机器少一件、箱子多一件。
	 *
	 * <p>这是「产物能输出」的最小证据。第一个输出节拍在放下机器的下一 tick 就会发生，
	 * 所以推一刻就够。
	 */
	@GameTest
	public void oneItemMovesIntoAdjacentChest(GameTestHelper helper) {
		AutoFishingMachineBlockEntity machine = buildScene(helper, false);
		placeChest(helper, DOWN);
		machine.setItem(MachineConfig.SLOT_CACHE_START, new ItemStack(Items.COD, 64));

		tickOnce(helper, machine);

		require(helper, countItems(helper, DOWN) == 1,
				"箱子应当收到 1 件，实际 " + countItems(helper, DOWN));
		require(helper, machine.getCacheStack(0).getCount() == 63,
				"缓存应当只剩 63 件，实际 " + machine.getCacheStack(0).getCount());

		helper.succeed();
	}

	/**
	 * 输出速率 = 每 {@link MachineConfig#OUTPUT_INTERVAL_TICKS} 刻一件。
	 *
	 * <p>三步各钉住一件事：第 1 刻就搬（不是先空等一个周期）、
	 * 满 8 刻仍然只搬了 1 件（节拍真的在起作用）、第 9 刻才搬第 2 件（周期正好是 8 刻）。
	 * 少了最后一步的话，把间隔写成 1 刻也能过。
	 */
	@GameTest
	public void outputRateFollowsInterval(GameTestHelper helper) {
		AutoFishingMachineBlockEntity machine = buildScene(helper, false);
		placeChest(helper, DOWN);
		machine.setItem(MachineConfig.SLOT_CACHE_START, new ItemStack(Items.COD, 64));

		tickOnce(helper, machine);
		require(helper, countItems(helper, DOWN) == 1, "第一次输出应当在第 1 刻发生");

		tick(helper, machine, MachineConfig.OUTPUT_INTERVAL_TICKS - 1);
		require(helper, countItems(helper, DOWN) == 1,
				"不到 " + MachineConfig.OUTPUT_INTERVAL_TICKS + " 刻不该有第二次输出，实际已搬 "
						+ countItems(helper, DOWN) + " 件");

		tickOnce(helper, machine);
		require(helper, countItems(helper, DOWN) == 2,
				"满 " + MachineConfig.OUTPUT_INTERVAL_TICKS + " 刻后应当搬出第 2 件，实际 "
						+ countItems(helper, DOWN) + " 件");

		helper.succeed();
	}

	/**
	 * 有多个方向可去时，先进「正下方」。
	 *
	 * <p>钉住 {@code AdjacentInventory.OUTPUT_ORDER}。这个顺序是设计的一部分
	 * （箱子摆在机器下面是常见摆法），不能靠 {@code Direction#values()} 的默认次序碰运气 ——
	 * 那个次序是 DOWN, UP, NORTH, SOUTH, WEST, EAST，正下方虽然也在第一位，
	 * 但紧跟其后的 UP 会把「上方」变成第二优先，与设计不符。
	 */
	@GameTest
	public void outputPrefersDownwards(GameTestHelper helper) {
		AutoFishingMachineBlockEntity machine = buildScene(helper, false);
		placeChest(helper, DOWN);
		placeChest(helper, NORTH);
		machine.setItem(MachineConfig.SLOT_CACHE_START, new ItemStack(Items.COD, 64));

		tickOnce(helper, machine);

		require(helper, countItems(helper, DOWN) == 1,
				"产物应当优先进正下方的箱子，实际下方 " + countItems(helper, DOWN) + " 件");
		require(helper, countItems(helper, NORTH) == 0,
				"北侧的箱子应当一件都没收到，实际 " + countItems(helper, NORTH) + " 件");

		helper.succeed();
	}

	/** 钓竿槽不参与输出。少这条断言的话，一次「顺手把 items 全遍历一遍」的改动就会把钓竿搬走。 */
	@GameTest
	public void rodSlotIsNeverOutput(GameTestHelper helper) {
		AutoFishingMachineBlockEntity machine = buildScene(helper, false);
		placeChest(helper, DOWN);
		machine.setItem(MachineConfig.SLOT_ROD, new ItemStack(Items.FISHING_ROD));
		machine.setItem(MachineConfig.SLOT_CACHE_START, new ItemStack(Items.COD, 64));

		tick(helper, machine, MachineConfig.OUTPUT_INTERVAL_TICKS * 4);

		require(helper, machine.getRod().is(Items.FISHING_ROD),
				"钓竿必须留在钓竿槽里，实际槽位内容 " + machine.getRod());
		require(helper, countItems(helper, DOWN) == 4,
				"应当只搬了缓存里的物品，实际 " + countItems(helper, DOWN) + " 件");

		helper.succeed();
	}

	/** 漏斗也是合法目标（需求点名了「箱子 / 漏斗」）。 */
	@GameTest
	public void hopperBelowReceivesItems(GameTestHelper helper) {
		AutoFishingMachineBlockEntity machine = buildScene(helper, false);
		helper.setBlock(DOWN, Blocks.HOPPER);
		machine.setItem(MachineConfig.SLOT_CACHE_START, new ItemStack(Items.COD, 64));

		tickOnce(helper, machine);

		require(helper, countItems(helper, DOWN) == 1,
				"漏斗应当收到 1 件，实际 " + countItems(helper, DOWN));

		helper.succeed();
	}

	// ============================================================ 容器语义

	/**
	 * 大箱子要当成一个 54 格的容器用。
	 *
	 * <p>构造方式让这条测试真的有鉴别力：把机器贴着的那<b>一半</b>塞满，
	 * 另一半留空。如果实现只拿 {@code level.getBlockEntity(pos)} 得到的
	 * {@code ChestBlockEntity}（永远只有 27 格），它看到的就是「满了」，
	 * 一件也搬不动；只有走原版的 {@code ChestBlock#getContainer} 拿到合并视图，
	 * 物品才会落进另一半。
	 *
	 * <p>箱子朝向与 TYPE 的搭配是算出来的，不是试出来的：
	 * LEFT 的连体方向是 {@code FACING.getClockWise()}，所以朝北的 LEFT 箱子
	 * 连的是它<b>东侧</b>那一半；两半朝向一致、TYPE 互斥（LEFT / RIGHT），
	 * 正好满足 {@code DoubleBlockCombiner} 的合并条件。
	 */
	@GameTest
	public void doubleChestIsTreatedAsOneInventory(GameTestHelper helper) {
		AutoFishingMachineBlockEntity machine = buildScene(helper, false);
		placeDoubleChest(helper);
		machine.setItem(MachineConfig.SLOT_CACHE_START, new ItemStack(Items.COD, 64));

		// 把贴着机器的那一半塞满，另一半留空。
		fillContainer(helper, DOWN, new ItemStack(Items.COD, 64));
		int full = countItems(helper, DOWN);

		tickOnce(helper, machine);

		require(helper, countItems(helper, DOWN) == full,
				"已经满掉的那一半不该有任何变化，实际 " + countItems(helper, DOWN) + " / 期望 " + full);
		require(helper, countItems(helper, CHEST_HALF) == 1,
				"产物理应落进大箱子的另一半，实际 " + countItems(helper, CHEST_HALF) + " 件");

		helper.succeed();
	}

	/**
	 * 相邻的另一台机器不是输出目标。
	 *
	 * <p>上游机器基类也实现了 {@code Container}（占位骨架），看起来是个合法目标。
	 * 真正挡住产物的是本模组自己的 {@code canPlaceItem}（只有钓竿槽收钓竿）——
	 * 也就是说这条测试同时验证了「槽位准入只有一份规则」：
	 * 界面、漏斗、相邻机器输出走的是同一个判定。
	 */
	@GameTest
	public void neighbourMachineIsNotAnOutputTarget(GameTestHelper helper) {
		AutoFishingMachineBlockEntity machine = buildScene(helper, false);
		AutoFishingMachineBlockEntity neighbour = placeMachine(helper, NORTH, FACING);
		machine.setItem(MachineConfig.SLOT_CACHE_START, new ItemStack(Items.COD, 64));

		tick(helper, machine, MachineConfig.OUTPUT_INTERVAL_TICKS * 4);

		require(helper, machine.getCacheStack(0).getCount() == 64,
				"产物不该被推进另一台机器，实际缓存剩 " + machine.getCacheStack(0).getCount());
		require(helper, neighbour.getCacheStack(0).isEmpty(),
				"邻居机器的缓存槽必须还是空的，实际 " + neighbour.getCacheStack(0));

		helper.succeed();
	}

	/**
	 * 容器「从哪一面进料」的语义必须传对。
	 *
	 * <p>物品从机器往<b>下</b>走，进的是容器的<b>顶面</b>，所以传给
	 * {@code canPlaceItemThroughFace} 的方向要是 {@code UP}。
	 * 堆肥桶是原版里少数严格区分入料面的容器（{@code getSlotsForFace} 只对
	 * {@code UP} 返回槽位），正好当探针。
	 *
	 * <p>这条用例把堆肥桶放在机器下方：方向传对了，种子进得去；
	 * 传成 {@code DOWN} 的话可入料槽位数是 0，一件都搬不动。
	 */
	@GameTest
	public void containerFaceIsUsedForInsertion(GameTestHelper helper) {
		AutoFishingMachineBlockEntity machine = buildScene(helper, false);
		helper.setBlock(DOWN, Blocks.COMPOSTER);
		machine.setItem(MachineConfig.SLOT_CACHE_START, new ItemStack(Items.DRIED_KELP, 64));

		tickOnce(helper, machine);

		require(helper, machine.getCacheStack(0).getCount() == 63,
				"堆肥桶在下方时应当能收到种子（入料面是它的顶面），实际缓存还剩 "
						+ machine.getCacheStack(0).getCount());

		helper.succeed();
	}

	/** 同一件事的反面：堆肥桶在机器上方时，物品要从它的底面进，它不收 —— 于是一件也不该动。 */
	@GameTest
	public void containerFaceRejectsWrongDirection(GameTestHelper helper) {
		AutoFishingMachineBlockEntity machine = buildScene(helper, false);
		helper.setBlock(UP, Blocks.COMPOSTER);
		machine.setItem(MachineConfig.SLOT_CACHE_START, new ItemStack(Items.DRIED_KELP, 64));

		tick(helper, machine, MachineConfig.OUTPUT_INTERVAL_TICKS * 4);

		require(helper, machine.getCacheStack(0).getCount() == 64,
				"堆肥桶不收从底面进来的东西，缓存不该有任何变化，实际 "
						+ machine.getCacheStack(0).getCount());

		helper.succeed();
	}

	// ============================================================ 停机与恢复

	/**
	 * 缓存满 + 相邻箱子也满 → {@link PauseReason#OUTPUT_FULL}。
	 *
	 * <p>与「没放容器」区分开是设计的一部分：两种情况玩家要做的动作相反
	 * （去清空箱子 / 去放一个箱子），合成一条只会让人围着机器转圈。
	 */
	@GameTest
	public void fullContainerReportsOutputFull(GameTestHelper helper) {
		AutoFishingMachineBlockEntity machine = buildScene(helper, true);
		placeChest(helper, DOWN);
		fillContainer(helper, DOWN, new ItemStack(Items.COD, 64));
		machine.setItem(MachineConfig.SLOT_ROD, new ItemStack(Items.FISHING_ROD));
		machine.setStored(MachineConfig.ENERGY_CAPACITY);
		fillCache(machine);

		tickOnce(helper, machine);

		require(helper, machine.getPauseReason() == PauseReason.OUTPUT_FULL,
				"停机原因应为 OUTPUT_FULL，实际 " + machine.getPauseReason());

		helper.succeed();
	}

	/**
	 * 缓存满 + 四周没有容器 → {@link PauseReason#CACHE_FULL}，而且**一件都不许丢**。
	 *
	 * <p>「不许丢」有三个层次，这里全测了：
	 * <ol>
	 *   <li>机器不许把产物掉在地上（不然地上会堆一地东西，玩家还不知道为什么）；</li>
	 *   <li>缓存里的东西不许凭空消失；</li>
	 *   <li>机器必须是<b>停下</b>而不是空转 —— 状态仍是 IDLE，没有偷偷继续新周期。</li>
	 * </ol>
	 */
	@GameTest
	public void nothingIsLostOrDroppedWhileBlocked(GameTestHelper helper) {
		AutoFishingMachineBlockEntity machine = buildScene(helper, true);
		machine.setItem(MachineConfig.SLOT_ROD, new ItemStack(Items.FISHING_ROD));
		machine.setStored(MachineConfig.ENERGY_CAPACITY);
		fillCache(machine);
		int before = cacheItemCount(machine);

		tick(helper, machine, 200);

		require(helper, machine.getPauseReason() == PauseReason.CACHE_FULL,
				"停机原因应为 CACHE_FULL，实际 " + machine.getPauseReason());
		require(helper, machine.getPhase() == FishingPhase.IDLE,
				"没地方放产物时必须停机，实际阶段 " + machine.getPhase());
		require(helper, cacheItemCount(machine) == before,
				"缓存里的东西不该凭空变化：" + before + " → " + cacheItemCount(machine));
		require(helper, helper.getLevel().getEntitiesOfClass(ItemEntity.class, helper.getBounds()).isEmpty(),
				"缓存满时不许把产物丢到地上");

		helper.succeed();
	}

	/**
	 * 把箱子腾空之后，机器要自己恢复钓鱼。
	 *
	 * <p>这条是「输出独立于状态机」的直接证据：机器刚才正因为
	 * {@link PauseReason#OUTPUT_FULL} 停着，输出任务却一直在跑，所以玩家一腾位置，
	 * 下一个输出节拍就把缓存腾出一个空格，状态机随即开了新周期。
	 * 若把输出写在状态机里面，这里会永远停在停机状态。
	 */
	@GameTest
	public void fishingResumesAfterContainerIsEmptied(GameTestHelper helper) {
		AutoFishingMachineBlockEntity machine = buildScene(helper, true);
		placeChest(helper, DOWN);
		fillContainer(helper, DOWN, new ItemStack(Items.COD, 64));
		machine.setItem(MachineConfig.SLOT_ROD, new ItemStack(Items.FISHING_ROD));
		machine.setStored(MachineConfig.ENERGY_CAPACITY);
		fillCache(machine);

		tickOnce(helper, machine);
		require(helper, machine.getPauseReason() == PauseReason.OUTPUT_FULL,
				"前置条件没搭对：此刻应当是 OUTPUT_FULL，实际 " + machine.getPauseReason());

		// 玩家把箱子清空。
		clearContainer(helper, DOWN);

		// 多推一刻是为了跨过输出节拍的相位：节拍可能刚好落在刚推完的那一拍的下一步。
		tick(helper, machine, MachineConfig.OUTPUT_INTERVAL_TICKS + 1);

		require(helper, machine.getPauseReason() != PauseReason.OUTPUT_FULL
						&& machine.getPauseReason() != PauseReason.CACHE_FULL,
				"箱子腾空后不该再报缓存/输出满，实际 " + machine.getPauseReason());
		require(helper, machine.getPhase() == FishingPhase.WAITING,
				"条件齐备后应当立刻开新周期，实际阶段 " + machine.getPhase());

		helper.succeed();
	}

	// ============================================================ 自然调度

	/**
	 * 输出在<b>真实 tick 调度</b>下确实会跑起来。
	 *
	 * <h2>为什么要有这么一条</h2>
	 * 本类其余用例都手动推刻。手动推刻能钉死「间隔对不对」「面传得对不对」这类逻辑，
	 * 但它<b>验证不了「这段代码会不会被叫到」</b> —— 手动 tick 是绕过调度层直接调
	 * {@code MachineBaseBlockEntity#tick} 的。
	 *
	 * <p>所以这条反过来：一行 tick 都不写，只挂一个延迟回查，让原版的方块实体调度器
	 * 自己把机器推起来。它钉住的是
	 * {@code BlockMachineBase#getTicker → serverTick → outputTick} 这条接线 ——
	 * 哪天输出被挪到一个没人调的地方，或者 ticker 没注册上，只有这条会红。
	 */
	@GameTest(maxTicks = 40)
	public void outputRunsOnTheNaturalTickLoop(GameTestHelper helper) {
		AutoFishingMachineBlockEntity machine = buildScene(helper, false);
		placeChest(helper, DOWN);
		machine.setItem(MachineConfig.SLOT_CACHE_START, new ItemStack(Items.COD, 64));

		int delay = MachineConfig.OUTPUT_INTERVAL_TICKS + 2;
		helper.runAfterDelay(delay, () -> {
			int moved = countItems(helper, DOWN);
			if (moved < 1) {
				throw helper.assertionException("自然 tick 跑了 " + delay + " 刻，箱子还是空的 —— "
						+ "方块实体的 ticker 没接上，或者输出没挂在 serverTick 上");
			}
			helper.succeed();
		});
	}

	// ============================================================ 场景搭建

	/** 一块地基 + 正面一格水，可选地在上方放电池（用来满足「已接电网」）。 */
	private static AutoFishingMachineBlockEntity buildScene(GameTestHelper helper, boolean withGrid) {
		for (int x = 1; x <= 5; x++) {
			for (int z = 2; z <= 4; z++) {
				helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
			}
		}
		helper.setBlock(WATER, Blocks.WATER);
		if (withGrid) {
			helper.setBlock(UP, GRID_BATTERY);
		}
		return placeMachine(helper, MACHINE, FACING);
	}

	private static AutoFishingMachineBlockEntity placeMachine(GameTestHelper helper, BlockPos pos, Direction facing) {
		helper.setBlock(pos, ModBlocks.AUTO_FISHING_MACHINE.defaultBlockState()
				.setValue(BlockMachineBase.FACING, facing));
		return helper.getBlockEntity(pos, AutoFishingMachineBlockEntity.class);
	}

	/** 单个箱子（朝北，SINGLE）。朝北只是为了让它不与沿 X 轴相邻的方块连体。 */
	private static void placeChest(GameTestHelper helper, BlockPos pos) {
		helper.setBlock(pos, chestState(ChestType.SINGLE));
	}

	/** 大箱子：两个沿 X 轴相邻、朝向一致、TYPE 互斥的箱子。 */
	private static void placeDoubleChest(GameTestHelper helper) {
		helper.setBlock(DOWN, chestState(ChestType.LEFT));
		helper.setBlock(CHEST_HALF, chestState(ChestType.RIGHT));
	}

	private static BlockState chestState(ChestType type) {
		return Blocks.CHEST.defaultBlockState()
				.setValue(ChestBlock.FACING, Direction.NORTH)
				.setValue(ChestBlock.TYPE, type);
	}

	// ============================================================ 辅助

	/**
	 * 手动推一刻。
	 *
	 * <p>方块状态每次都从世界里现取：状态机开周期时会写 {@code ACTIVE}，
	 * 用方块实体里可能过期的缓存会让 {@code getFacing()} 读到旧朝向。
	 */
	private static void tickOnce(GameTestHelper helper, AutoFishingMachineBlockEntity machine) {
		BlockPos pos = machine.getBlockPos();
		machine.tick(helper.getLevel(), pos, helper.getLevel().getBlockState(pos), machine);
	}

	private static void tick(GameTestHelper helper, AutoFishingMachineBlockEntity machine, int times) {
		for (int i = 0; i < times; i++) {
			tickOnce(helper, machine);
		}
	}

	/** 缓存里所有物品的件数之和。 */
	private static int cacheItemCount(AutoFishingMachineBlockEntity machine) {
		int total = 0;
		for (int index = 0; index < MachineConfig.SLOT_CACHE_COUNT; index++) {
			total += machine.getCacheStack(index).getCount();
		}
		return total;
	}

	/**
	 * 把 9 个缓存槽各放一件。
	 *
	 * <p>刻意用「每格一件」而不是「每格 64 件」：这样输出搬走一件就会腾出一个空槽，
	 * 机器的恢复只差一个节拍。若塞成每格 64 件，即使箱子被清空，
	 * 也要等 64 个节拍才能腾出第一格 —— 那是「逐件搬运」的必然代价（见
	 * {@code AdjacentInventory#insertOne}），不该混进这条用例。
	 */
	private static void fillCache(AutoFishingMachineBlockEntity machine) {
		for (int index = 0; index < MachineConfig.SLOT_CACHE_COUNT; index++) {
			machine.setItem(MachineConfig.SLOT_CACHE_START + index, new ItemStack(Items.COD));
		}
	}

	private static void fillContainer(GameTestHelper helper, BlockPos pos, ItemStack template) {
		Container container = containerAt(helper, pos);
		for (int slot = 0; slot < container.getContainerSize(); slot++) {
			container.setItem(slot, template.copy());
		}
	}

	private static void clearContainer(GameTestHelper helper, BlockPos pos) {
		Container container = containerAt(helper, pos);
		for (int slot = 0; slot < container.getContainerSize(); slot++) {
			container.setItem(slot, ItemStack.EMPTY);
		}
	}

	/** 某个坐标上的容器里有多少件东西。放的不是容器时返回 0。 */
	private static int countItems(GameTestHelper helper, BlockPos pos) {
		if (!(helper.getLevel().getBlockEntity(helper.absolutePos(pos)) instanceof Container container)) {
			return 0;
		}
		int total = 0;
		for (int slot = 0; slot < container.getContainerSize(); slot++) {
			total += container.getItem(slot).getCount();
		}
		return total;
	}

	/**
	 * 取某个坐标上的容器。
	 *
	 * <p>注意这里的坐标换算：{@code helper.setBlock} 用的是<b>相对</b>结构原点的坐标，
	 * 而 {@code level.getBlockEntity} 要的是世界坐标。少了 {@code absolutePos} 这一下，
	 * 读到的会是测试世界里跟结构无关的另一格方块 —— 而且不报错，只是一个看起来
	 * 莫名其妙的值（实测时读到的是 sandstone）。
	 */
	private static Container containerAt(GameTestHelper helper, BlockPos pos) {
		if (helper.getLevel().getBlockEntity(helper.absolutePos(pos)) instanceof Container container) {
			return container;
		}
		throw helper.assertionException("坐标 " + pos + " 上没有容器，测试场景没搭对");
	}

	/** 断言辅助：失败时抛 GameTest 自己的异常，报告里会带上这条消息。 */
	private static void require(GameTestHelper helper, boolean condition, String message) {
		if (!condition) {
			throw helper.assertionException(message);
		}
	}
}
