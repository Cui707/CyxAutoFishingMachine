package com.cyx.cyxautofishingmachine.gametest;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.cyx.crimsoncoppergrid.common.blocks.BlockMachineBase;
import com.cyx.cyxautofishingmachine.blockentity.AutoFishingMachineBlockEntity;
import com.cyx.cyxautofishingmachine.config.MachineConfig;
import com.cyx.cyxautofishingmachine.fishing.FishingPhase;
import com.cyx.cyxautofishingmachine.fishing.HookProbe;
import com.cyx.cyxautofishingmachine.fishing.PauseReason;
import com.cyx.cyxautofishingmachine.init.ModBlocks;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/**
 * 钓鱼状态机的自动化测试。
 *
 * <h2>为什么要手动 tick 而不是「等」</h2>
 * 一个钓鱼周期约 46~76 刻（2.3~3.8 秒，见 {@link MachineConfig} 的「钓鱼节奏」）。
 * 若靠真实世界时间推进，十几条测试要跑几十秒，而 GameTest 默认的单条上限只有 20 刻，
 * 根本等不到结果。
 *
 * <p>这里用的办法是直接调 {@code MachineBaseBlockEntity#tick} —— 原版给方块实体调度 tick
 * 时走的正是这个入口。一次调用等于推进一刻，于是<b>全套测试在毫秒级完成，且完全确定</b>：
 * 没有真实时间、没有并发、没有 tick 顺序抖动。
 *
 * <p>代价是绕过了原版的调度层（区块加载、tick 列表维护），也就是「状态机本身」
 * 被测到了，「状态机被正确调度」没有。后者由 Phase 4 的客户端端到端测试
 * 与 Phase 8 的实机验证覆盖。
 *
 * <h2>场景都是手搭的</h2>
 * 原版开放水域判定要求浮标周围 ±2 格、上下 4 层全部符合规则，
 * 所以「一片水算不算开放」必须用真实地形来试，不能打桩。
 * 下面每个场景都逐格 {@code setBlock} 搭出来，形状在注释里画清楚了。
 * 每个 GameTest 方法会分到一份独立的结构副本，互不干扰，不需要清理。
 */
public class FishingGameTests {

	// ------------------------------------------------------------ 封闭水域场景
	//
	// x∈[0,4] × z∈[0,4] × y∈[0,2] 全是石头，只在 (2,1,2) 挖一格水。
	// 那格水的水柱顶端就是它自己，而它周围 ±2 全是石头 —— 判定必然为「非开放」。

	/** 封闭水域里的 1 号机器：站在水坑东侧，正对 (2,1,2)。 */
	private static final BlockPos POND_MACHINE = new BlockPos(3, 1, 2);

	/** 封闭水域里的 2 号机器：站在水坑南侧，同样正对 (2,1,2)。耐久对比测试用。 */
	private static final BlockPos POND_MACHINE_2 = new BlockPos(2, 1, 3);

	/** 那一格水。 */
	private static final BlockPos POND_WATER = new BlockPos(2, 1, 2);

	// ------------------------------------------------------------ 开放水域场景
	//
	//   y=4  ........        '.' = 空气
	//   y=3  ........
	//   y=2  .#~~~~~#.        '#' = 石头    '~' = 水    'M' = 机器（朝向 WEST，在 x=7）
	//   y=1  .#~~~~~#.
	//   y=0  .#######.
	//        01234567  (x)，z 方向同构
	//
	// 水体是 x∈[2,6] × z∈[2,6] × y∈[1,2] 的 5×5×2，上方 y∈[3,4] 是 5×5×2 的空气 ——
	// 正好满足原版对开放水域的形状要求。

	/** 开放水域里的机器，站在水池东岸。 */
	private static final BlockPos POOL_MACHINE = new BlockPos(7, 2, 4);

	/** 机器正前方那段水面的中点：从 x=7 往西数，水面段是 x=6..2（长 5），中点是 x=4。 */
	private static final BlockPos POOL_BOBBER = new BlockPos(4, 2, 4);

	/** 石头外框在 x / z 上的范围。 */
	private static final int STONE_MIN = 1;
	private static final int STONE_MAX = 7;

	/** 水体在 x / z 上的范围。 */
	private static final int WATER_MIN = 2;
	private static final int WATER_MAX = 6;

	/** 水体所在的两层 Y。 */
	private static final int WATER_LOW = 1;
	private static final int WATER_HIGH = 2;

	// ------------------------------------------------------------ 战利品分类
	//
	// 三张原版子表的条目并集。测试断言「产物必须属于这个集合」，
	// 以及「封闭水域里绝不出现宝藏专属物品」—— 而不是去断言具体概率，
	// 那会因为原版调权重而随机变红。

	/** 只在宝藏子表里出现、鱼和垃圾两张表里都没有的条目。 */
	private static final Set<Item> TREASURE_ONLY = Set.of(
			Items.NAME_TAG,
			Items.SADDLE,
			Items.BOW,
			Items.NAUTILUS_SHELL,
			Items.BOOK,
			Items.ENCHANTED_BOOK);

	/** 三张子表所有可能条目（含只在丛林群系出现的竹子）。 */
	private static final Set<Item> ANY_FISHING_LOOT = Set.of(
			// gameplay/fishing/fish
			Items.COD, Items.SALMON, Items.TROPICAL_FISH, Items.PUFFERFISH,
			// gameplay/fishing/junk
			Items.LILY_PAD, Items.LEATHER_BOOTS, Items.LEATHER, Items.BONE, Items.POTION,
			Items.STRING, Items.FISHING_ROD, Items.BOWL, Items.STICK, Items.INK_SAC,
			Items.TRIPWIRE_HOOK, Items.ROTTEN_FLESH, Items.BAMBOO,
			// gameplay/fishing/treasure
			Items.NAME_TAG, Items.SADDLE, Items.BOW, Items.NAUTILUS_SHELL, Items.BOOK)

			;

	// ------------------------------------------------------------ 供电方

	/**
	 * 充当「电网」的电池方块，来自前置 Mod。
	 *
	 * <p>这里写全限定名而不是 import：上游与本模组各有一个 {@code init.ModBlocks}，
	 * 而 Java 没有 import 别名。写成常量还有个额外好处 ——
	 * 调用处一眼能看出「这个方块不是本模组的」。
	 *
	 * <p>为什么电池可以直接当电源用（不需要先充过电）：它的
	 * {@code getMaxExtract} 只取决于 {@code getBaseMaxOutput}，与自身存量无关。
	 * 而 {@code GridLink} 对非导体邻居的要求正是 {@code supportsExtraction()}。
	 */
	private static final Block GRID_BATTERY = com.cyx.crimsoncoppergrid.init.ModBlocks.BATTERY;

	/** 一条周期最长 680 刻（600 等待 + 80 咬钩），这里留了近三倍余量。 */
	private static final int TICKS_PER_CYCLE_BUDGET = 2_000;

	/** 统计用样本量：跑这么多杆来验证「封闭水域不出宝藏 / 开放水域能出宝藏」。 */
	private static final int TREASURE_SAMPLE_SIZE = 300;

	/** GameTest 通过入口点反射实例化本类，必须有公开的无参构造器。 */
	public FishingGameTests() {
	}

	// ============================================================ 停机原因

	/** 没放钓竿 → NO_ROD，而且一分钱都不该扣。 */
	@GameTest
	public void noRodReportsNoRod(GameTestHelper helper) {
		AutoFishingMachineBlockEntity machine = buildPoolScene(helper, true);
		machine.setStored(MachineConfig.ENERGY_CAPACITY);

		tickOnce(helper, machine);

		require(helper, machine.getPhase() == FishingPhase.IDLE, "没有钓竿时不应当进入钓鱼状态");
		require(helper, machine.getPauseReason() == PauseReason.NO_ROD,
				"停机原因应为 NO_ROD，实际 " + machine.getPauseReason());
		require(helper, machine.getStored() == MachineConfig.ENERGY_CAPACITY,
				"条件不满足时不该扣电，实际 " + machine.getStored());

		helper.succeed();
	}

	/** 有钓竿但周围没有水 → NO_WATER。 */
	@GameTest
	public void noWaterReportsNoWater(GameTestHelper helper) {
		buildStonework(helper);
		AutoFishingMachineBlockEntity machine = placeMachine(helper, POND_MACHINE, Direction.WEST, true);
		machine.setItem(MachineConfig.SLOT_ROD, new ItemStack(Items.FISHING_ROD));
		machine.setStored(MachineConfig.ENERGY_CAPACITY);

		tickOnce(helper, machine);

		require(helper, machine.getPauseReason() == PauseReason.NO_WATER,
				"停机原因应为 NO_WATER，实际 " + machine.getPauseReason());
		require(helper, machine.getStored() == MachineConfig.ENERGY_CAPACITY, "条件不满足时不该扣电");

		helper.succeed();
	}

	/** 缓存 9 格全满 → CACHE_FULL。停机，而不是把产物丢在地上。 */
	@GameTest
	public void cacheFullReportsCacheFull(GameTestHelper helper) {
		AutoFishingMachineBlockEntity machine = buildPondScene(helper, true);
		machine.setItem(MachineConfig.SLOT_ROD, new ItemStack(Items.FISHING_ROD));
		machine.setStored(MachineConfig.ENERGY_CAPACITY);
		for (int index = 0; index < MachineConfig.SLOT_CACHE_COUNT; index++) {
			machine.setItem(MachineConfig.SLOT_CACHE_START + index, new ItemStack(Items.COD));
		}

		tickOnce(helper, machine);

		require(helper, machine.getPauseReason() == PauseReason.CACHE_FULL,
				"停机原因应为 CACHE_FULL，实际 " + machine.getPauseReason());

		helper.succeed();
	}

	/** 钓竿、水、缓存都就绪，但旁边没有电源 → GRID_DISCONNECTED。 */
	@GameTest
	public void disconnectedGridReportsDisconnected(GameTestHelper helper) {
		AutoFishingMachineBlockEntity machine = buildPondScene(helper, false);
		machine.setItem(MachineConfig.SLOT_ROD, new ItemStack(Items.FISHING_ROD));
		machine.setStored(MachineConfig.ENERGY_CAPACITY);

		tickOnce(helper, machine);

		require(helper, machine.getPauseReason() == PauseReason.GRID_DISCONNECTED,
				"停机原因应为 GRID_DISCONNECTED，实际 " + machine.getPauseReason());
		require(helper, machine.getStored() == MachineConfig.ENERGY_CAPACITY, "没接电不该扣电");

		helper.succeed();
	}

	/** 接了电网但缓冲里的电不够预扣 → NOT_ENOUGH_POWER。 */
	@GameTest
	public void insufficientPowerReportsNotEnoughPower(GameTestHelper helper) {
		AutoFishingMachineBlockEntity machine = buildPondScene(helper, true);
		machine.setItem(MachineConfig.SLOT_ROD, new ItemStack(Items.FISHING_ROD));
		machine.setStored(MachineConfig.ENERGY_PER_CATCH - 1L);

		tickOnce(helper, machine);

		require(helper, machine.getPauseReason() == PauseReason.NOT_ENOUGH_POWER,
				"停机原因应为 NOT_ENOUGH_POWER，实际 " + machine.getPauseReason());
		require(helper, machine.getStored() == MachineConfig.ENERGY_PER_CATCH - 1L,
				"电量不足时不该扣掉一部分电");

		helper.succeed();
	}

	// ============================================================ 单杆行为

	/**
	 * 一杆的完整账：预扣 500 FE、产出正好一件、钓竿耐久正好 -1。
	 *
	 * <p>这是需求里「预扣电力」与「钓竿会消耗」两条的直接验证。
	 */
	@GameTest
	public void singleCatchConsumesEnergyAndDamagesRod(GameTestHelper helper) {
		AutoFishingMachineBlockEntity machine = buildPondScene(helper, true);
		machine.setItem(MachineConfig.SLOT_ROD, new ItemStack(Items.FISHING_ROD));
		machine.setStored(MachineConfig.ENERGY_CAPACITY);
		int damageBefore = machine.getRod().getDamageValue();

		require(helper, runUntilCatch(helper, machine), "等待 " + TICKS_PER_CYCLE_BUDGET + " 刻也没有钓到任何东西");

		ItemStack caught = singleCachedItem(helper, machine);
		require(helper, ANY_FISHING_LOOT.contains(caught.getItem()),
				"产物必须是原版钓鱼战利品表里的东西，实际 " + caught.getItem());

		require(helper, machine.getStored() == MachineConfig.ENERGY_CAPACITY - MachineConfig.ENERGY_PER_CATCH,
				"应当正好预扣 " + MachineConfig.ENERGY_PER_CATCH + " FE，实际扣了 "
						+ (MachineConfig.ENERGY_CAPACITY - machine.getStored()));

		require(helper, machine.getRod().getDamageValue() == damageBefore + 1,
				"普通钓竿每杆应当只掉 1 点耐久，实际 " + damageBefore + " → "
						+ machine.getRod().getDamageValue());

		helper.succeed();
	}

	/** 周期内把钓竿拿走 → 中止，并**退还**预扣的电。 */
	@GameTest
	public void abortingCycleRefundsPreChargedEnergy(GameTestHelper helper) {
		AutoFishingMachineBlockEntity machine = buildPondScene(helper, true);
		machine.setItem(MachineConfig.SLOT_ROD, new ItemStack(Items.FISHING_ROD));
		machine.setStored(MachineConfig.ENERGY_CAPACITY);

		// 推一刻让周期开起来：此刻电已经预扣。
		tickOnce(helper, machine);
		require(helper, machine.getPhase() == FishingPhase.WAITING,
				"条件齐备时应当立刻进入等待，实际 " + machine.getPhase());
		require(helper, machine.getStored() == MachineConfig.ENERGY_CAPACITY - MachineConfig.ENERGY_PER_CATCH,
				"开周期时应当预扣 " + MachineConfig.ENERGY_PER_CATCH + " FE");

		// 把钓竿抽走，再推一刻。
		machine.setItem(MachineConfig.SLOT_ROD, ItemStack.EMPTY);
		tickOnce(helper, machine);

		require(helper, machine.getPhase() == FishingPhase.IDLE, "钓竿被拿走之后应当中止周期");
		require(helper, machine.getPauseReason() == PauseReason.NO_ROD,
				"中止原因应为 NO_ROD，实际 " + machine.getPauseReason());
		require(helper, machine.getStored() == MachineConfig.ENERGY_CAPACITY,
				"中止的周期没有产出，应当把预扣的 " + MachineConfig.ENERGY_PER_CATCH
						+ " FE 退回来，实际 " + machine.getStored());

		helper.succeed();
	}

	// ============================================================ 开放水域

	/**
	 * 封闭水域跑满 300 杆，一件宝藏都不能出。
	 *
	 * <p>这不是统计断言，而是<b>确定性断言</b>：原版 {@code fishing.json} 的宝藏条目挂着
	 * {@code in_open_water == true} 的谓词，封闭水域下它一定被筛掉。
	 * 哪天有人在代码里「顺手」把 openWater 写成恒真，这条立刻变红。
	 */
	@GameTest
	public void closedWaterNeverYieldsTreasure(GameTestHelper helper) {
		AutoFishingMachineBlockEntity machine = buildPondScene(helper, true);
		machine.setItem(MachineConfig.SLOT_ROD, new ItemStack(Items.FISHING_ROD));
		machine.setStored(MachineConfig.ENERGY_CAPACITY);

		int treasure = 0;
		for (int round = 0; round < TREASURE_SAMPLE_SIZE; round++) {
			require(helper, runUntilCatch(helper, machine),
					"第 " + round + " 杆：封闭水域里也应该能钓上东西");
			if (containsTreasureOnly(machine)) {
				treasure++;
			}
			recycle(machine, true);
		}

		// 先断言产物，再断言状态。
		//
		// 顺序是有意的：这两条都被同一个 bug 触发（把 openWater 写成恒真）时，
		// 「产物里混进了宝藏」比「状态显示成开放」严重得多 —— 报后者会把前者的证据藏起来。
		// 反过来若只有状态错（算对了没传给战利品表），第二条会补上，两条一起构成完整定位。
		require(helper, treasure == 0,
				TREASURE_SAMPLE_SIZE + " 杆里出现了 " + treasure + " 件宝藏，封闭水域不该出宝藏");
		require(helper, machine.getOpenWaterState() != AutoFishingMachineBlockEntity.OPEN_WATER_OPEN,
				"一格水坑不该被判定为开放水域，实际状态 " + machine.getOpenWaterState());

		helper.succeed();
	}

	/**
	 * 开放水域跑满 300 杆，至少要出一件宝藏。
	 *
	 * <p>断言的是「宝藏条目确实能进抽取」，概率上非常宽松：幸运为零时宝藏权重是
	 * {@code 5 + 2}，三张子表合计 99，单杆约 7%。300 杆全落空的概率约 {@code 3e-10}。
	 */
	@GameTest
	public void openWaterYieldsTreasure(GameTestHelper helper) {
		AutoFishingMachineBlockEntity machine = buildPoolScene(helper, true);
		machine.setItem(MachineConfig.SLOT_ROD, new ItemStack(Items.FISHING_ROD));
		machine.setStored(MachineConfig.ENERGY_CAPACITY);

		int treasure = 0;
		for (int round = 0; round < TREASURE_SAMPLE_SIZE; round++) {
			require(helper, runUntilCatch(helper, machine),
					"第 " + round + " 杆没钓到东西 —— 开放水域场景可能没搭对");
			if (containsTreasureOnly(machine)) {
				treasure++;
			}
			recycle(machine, true);
		}

		// 同 closedWaterNeverYieldsTreasure：先产物、后状态，理由见那一条。
		require(helper, treasure > 0, TREASURE_SAMPLE_SIZE + " 杆里一件宝藏都没出，宝藏条目被筛掉了");
		require(helper, machine.getOpenWaterState() == AutoFishingMachineBlockEntity.OPEN_WATER_OPEN,
				"5×5×2 的水池上方留空，应判为开放水域，实际状态 " + machine.getOpenWaterState());

		helper.succeed();
	}

	/**
	 * 浮标落在水面段的中点，而不是紧贴机器的第一格水。
	 *
	 * <p>这条钉住 {@code WaterFinder} 里那个决策：机器在水池东岸（x=7），水体是 x∈[2,6]，
	 * 因此浮标应当落在 x=4 这一列。若改回「就用紧贴机器的那格水」，浮标会落到 x=6，
	 * 而 x=6 周围 ±2 会伸到 x=8 的岸上去，判定立刻从开放变成非开放。
	 */
	@GameTest
	public void bobberLandsOnMiddleOfWaterRun(GameTestHelper helper) {
		AutoFishingMachineBlockEntity machine = buildPoolScene(helper, true);
		machine.setItem(MachineConfig.SLOT_ROD, new ItemStack(Items.FISHING_ROD));
		machine.setStored(MachineConfig.ENERGY_CAPACITY);

		tickOnce(helper, machine);
		require(helper, machine.getOpenWaterState() == AutoFishingMachineBlockEntity.OPEN_WATER_OPEN,
				"抛到水面中点才可能拿到开放水域判定，实际状态 " + machine.getOpenWaterState());

		// 把水面中点那一格（x=4 的表层水）换成石头：水面段被切成两段，中点会跟着挪。
		helper.setBlock(POOL_BOBBER, Blocks.STONE);

		// 等水域重检的节流窗口过去。多推两刻是因为节流是「减到 0 才触发」，
		// 触发那一拍本身不消费倒计时。
		for (int i = 0; i < MachineConfig.WATER_RECHECK_INTERVAL + 2; i++) {
			tickOnce(helper, machine);
		}

		require(helper, machine.getOpenWaterState() == AutoFishingMachineBlockEntity.OPEN_WATER_CLOSED,
				"水面被切断之后应当变成非开放水域，实际状态 " + machine.getOpenWaterState());

		helper.succeed();
	}

	// ============================================================ 附魔

	/**
	 * 饵钓：「等级 → 刻数」的换算链、本模组的缩放、以及保底。
	 *
	 * <h2>三条断言各守什么</h2>
	 * <ol>
	 *   <li><b>原版换算链</b>：{@code HookProbe.lureTicks()} 必须给出 0 / 100 / 300。
	 *       它守的是原版 {@code FishingRodItem} 那次 {@code * 20.0F} 换算没被漏掉或重复
	 *       —— 漏掉会让刻数差 20 倍。与等待区间无关，无论区间怎么调都该是这个数。</li>
	 *   <li><b>本模组的缩放</b>：{@code lureTicksScaledTo(LURE_TICKS_PER_LEVEL)}
	 *       必须给出 0 / 8 / 24。这是「等级阶梯重新生效」的结构依据 ——
	 *       和上面那条一起，正好把「原版基准」与「本模组标量」两侧都钉住，
	 *       任何一侧算错都会当场失败。</li>
	 *   <li><b>保底</b>：缩短量超过区间上限时，等待必须被 {@code MIN_WAIT_TICKS} 兜住。</li>
	 * </ol>
	 *
	 * <p>原本这条测试断言的是「饵钓III 之后等待刻数落在 1~300」，
	 * 但「等鱼上钩」调短到 30~50 刻之后这个式子会算成负数。
	 * 「缩短量是否真的拉开了等级差距」现在由
	 * {@link #lureEnchantActuallyShortensTheWait} 实测，不靠常量推导。
	 */
	@GameTest
	public void lureEnchantMapsLevelToTicks(GameTestHelper helper) {
		buildPondStonework(helper);
		AutoFishingMachineBlockEntity machine = placeMachine(helper, POND_MACHINE, Direction.WEST, true);
		ServerLevel level = helper.getLevel();

		for (int enchantLevel : new int[] { 0, 1, 3 }) {
			ItemStack rod = enchantLevel == 0
					? new ItemStack(Items.FISHING_ROD)
					: enchanted(helper, Enchantments.LURE, enchantLevel);
			HookProbe probe = HookProbe.create(level, rod);

			// 1) 原版尺度：0 / 100 / 300 刻。
			int vanillaExpected = enchantLevel * HookProbe.VANILLA_TICKS_PER_LURE_LEVEL;
			require(helper, probe.lureTicks() == vanillaExpected,
					"原版换算：饵钓 " + enchantLevel + " 级应当缩短 " + vanillaExpected
							+ " 刻，实际 " + probe.lureTicks());

			// 2) 本模组尺度：0 / 8 / 24 刻。
			int scaled = probe.lureTicksScaledTo(MachineConfig.LURE_TICKS_PER_LEVEL);
			int scaledExpected = enchantLevel * MachineConfig.LURE_TICKS_PER_LEVEL;
			require(helper, scaled == scaledExpected,
					"缩放后：饵钓 " + enchantLevel + " 级应当缩短 " + scaledExpected
							+ " 刻，实际 " + scaled);
		}

		// 3) 保底（Math.max(MIN_WAIT_TICKS, …)）。取一个「缩短量必然不低于新区间上限」的
		//    等级 —— 等级由常量算出来、不写死，将来把区间或每级刻数改回原版时这条断言仍然成立。
		//    少了这个保底：wait 变负数 → timeUntilLured 为负 → 每刻都会落进「两个计时器
		//    都归零」的兜底分支重新 beginWaiting，机器永远钓不上任何东西。
		int floorLevel = Math.max(1,
				(MachineConfig.MAX_LURE_WAIT + MachineConfig.LURE_TICKS_PER_LEVEL - 1)
						/ MachineConfig.LURE_TICKS_PER_LEVEL);

		machine.setItem(MachineConfig.SLOT_ROD, enchanted(helper, Enchantments.LURE, floorLevel));
		machine.setStored(MachineConfig.ENERGY_CAPACITY);
		tickOnce(helper, machine);
		require(helper, machine.getPhase() == FishingPhase.WAITING,
				"饵钓 " + floorLevel + " 级那一轮应当进入等待，实际 " + machine.getPhase());
		require(helper, machine.remainingTicks() == MachineConfig.MIN_WAIT_TICKS,
				"饵钓 " + floorLevel + " 级缩短 " + floorLevel * MachineConfig.LURE_TICKS_PER_LEVEL
						+ " 刻，不低于区间上限 " + MachineConfig.MAX_LURE_WAIT + " 刻，"
						+ "等待应被压到下限 " + MachineConfig.MIN_WAIT_TICKS + "，实际 "
						+ machine.remainingTicks());

		helper.succeed();
	}

	/**
	 * 饵钓确实让等待变短 —— 等级阶梯存在的运行时证据。
	 *
	 * <h2>为什么必须是实测</h2>
	 * 「{@code MAX_LURE_WAIT − 3 × LURE_TICKS_PER_LEVEL < MIN_LURE_WAIT}」这种式子
	 * 只说明配置<b>自洽</b>，不说明状态机真的用了它。本项目已经吃过一次亏：
	 * 早先那条区间测试的期望值取自被测代码读的同一份常量，成了同义反复 ——
	 * 两次突变检验（把常量退回原版值）都没抓到。
	 * 所以这里真跑：多轮采样两组掷出的刻数，再断言
	 * <b>饵钓III 的最坏样本仍严格优于无附魔的最好样本</b>。
	 *
	 * <h2>期望值为什么是字面量</h2>
	 * 与 {@link #bothWaitIntervalsMatchTheRequirement} 同一个理由 —— 需求锚点是
	 * 「无附魔约 2 秒（30~50 刻）」，饵钓III 在此之上再减 3 × 8 = 24 刻。
	 * 从常量推导期望值，断言就会跟着常量一起漂移，等于什么都没守。
	 */
	@GameTest
	public void lureEnchantActuallyShortensTheWait(GameTestHelper helper) {
		buildPondStonework(helper);
		AutoFishingMachineBlockEntity machine = placeMachine(helper, POND_MACHINE, Direction.WEST, true);

		final int plainMin = 30;
		final int plainMax = 50;
		final int lureReduction = 24; // 饵钓III：3 级 × 8 刻/级

		int plainBest = Integer.MAX_VALUE;
		int plainWorst = Integer.MIN_VALUE;
		int lureBest = Integer.MAX_VALUE;
		int lureWorst = Integer.MIN_VALUE;

		for (int round = 0; round < 12; round++) {
			int plain = rolledLureWait(helper, machine, new ItemStack(Items.FISHING_ROD));
			int lure = rolledLureWait(helper, machine, enchanted(helper, Enchantments.LURE, 3));
			require(helper, plain > 0 && lure > 0,
					"第 " + round + " 轮没有观测到「等鱼上钩」的倒计时（阶段切换失效？）");

			plainBest = Math.min(plainBest, plain);
			plainWorst = Math.max(plainWorst, plain);
			lureBest = Math.min(lureBest, lure);
			lureWorst = Math.max(lureWorst, lure);
		}

		require(helper, plainBest >= plainMin && plainWorst <= plainMax,
				"无附魔的等待应落在 " + plainMin + "~" + plainMax + " 刻，实测 "
						+ plainBest + "~" + plainWorst + " 刻");
		require(helper, lureBest >= plainMin - lureReduction && lureWorst <= plainMax - lureReduction,
				"饵钓III 的等待应落在 " + (plainMin - lureReduction) + "~" + (plainMax - lureReduction)
						+ " 刻（每级缩 8 刻），实测 " + lureBest + "~" + lureWorst + " 刻");
		// 阶梯的定义：III 级最坏的一次也快过无附魔最好的一次，两组区间不重叠。
		require(helper, lureWorst < plainBest,
				"饵钓III 最坏 " + lureWorst + " 刻并不快于无附魔最好的 " + plainBest + " 刻 ——"
						+ "等级阶梯没有生效（LURE_TICKS_PER_LEVEL 是否被改回原版值？）");

		helper.succeed();
	}

	/**
	 * 插上指定钓竿跑一轮，返回「等鱼上钩」阶段刚掷出的刻数。
	 *
	 * <p>观测到阶段切换就立刻把钓竿拿走，不跑完整个周期 —— 本方法只关心那个数。
	 * 读数的时机是<b>阶段刚切换</b>那一 tick：那一刻计时器刚被赋值、还没被
	 * {@code fishingSpeed} 扣过，所以结论与下雨/遮天导致的速度浮动无关。
	 *
	 * @return 掷出的等待刻数；没观测到阶段切换则返回 -1（调用方负责报错）
	 */
	private static int rolledLureWait(GameTestHelper helper, AutoFishingMachineBlockEntity machine,
			ItemStack rod) {
		machine.setItem(MachineConfig.SLOT_ROD, rod);
		machine.setStored(MachineConfig.ENERGY_CAPACITY);

		int rolled = -1;
		FishingPhase previous = machine.getPhase();
		for (int ticks = 0; ticks < 8 && rolled < 0; ticks++) {
			tickOnce(helper, machine);
			FishingPhase now = machine.getPhase();
			if (previous != FishingPhase.WAITING && now == FishingPhase.WAITING) {
				rolled = machine.remainingTicks();
			}
			previous = now;
		}

		// 中止本轮（拿走钓竿 → 下一 tick 带 NO_ROD 退回 IDLE），好让下次调用重新开周期。
		machine.setItem(MachineConfig.SLOT_ROD, ItemStack.EMPTY);
		tickOnce(helper, machine);
		return rolled;
	}

	/**
	 * 两个等待区间必须符合「等待约 2 秒、靠近约 1 秒」这个要求。
	 *
	 * <h2>期望值为什么写成字面量，而不是读 {@link MachineConfig} 的常量</h2>
	 * 第一版写成「掷出的值 ∈ {@code [MIN_LURE_WAIT, MAX_LURE_WAIT]}」，实测发现那是
	 * <b>同义反复</b> —— 掷出的值本来就来自这两个常量，常量怎么改断言都成立。
	 * 两次突变检验证实了这一点：把 {@code MIN/MAX_LURE_WAIT} 退回原版 100~600，
	 * 这条测试照样通过；把 {@code MAX_BITE_WAIT} 退回 80，整批 46 条全绿、什么都没抓到。
	 *
	 * <p>所以期望值必须锚在<b>需求</b>上：等待 2 秒（40 刻）、靠近 1 秒（20 刻），
	 * 各留 ±0.5 秒的区间宽度。这样任何一边被单方面改动都会当场失败 ——
	 * 那正是这条测试存在的理由。
	 *
	 * <h2>为什么在「阶段刚切换」那一 tick 读倒计时</h2>
	 * 那一刻计时器刚被赋值、还没有被 {@code fishingSpeed} 扣过，读到的就是掷出来的原始值。
	 * 于是断言与「下雨加速 / 遮天减速」导致的速度浮动无关 ——
	 * 否则速度 2（下雨）会让实测刻数腰斩，断言就得放宽到失去意义。
	 */
	@GameTest
	public void bothWaitIntervalsMatchTheRequirement(GameTestHelper helper) {
		// 需求：等待约 2 秒、靠近约 1 秒（20 刻 = 1 秒）。区间宽度各留半秒。
		int lureCenter = 2 * 20;
		int lureHalfWidth = 10;
		int biteCenter = 1 * 20;
		int biteHalfWidth = 5;

		buildPondStonework(helper);
		AutoFishingMachineBlockEntity machine = placeMachine(helper, POND_MACHINE, Direction.WEST, true);

		// 多轮是为了让区间两端真的被掷到过，边界上的 off-by-one 才会暴露。
		for (int round = 0; round < 40; round++) {
			// 把上一轮的周期中止掉（拿走钓竿，下一 tick 会带 NO_ROD 退回 IDLE）。
			machine.setItem(MachineConfig.SLOT_ROD, ItemStack.EMPTY);
			tickOnce(helper, machine);

			// 清空缓存。这个场景没有相邻容器（机器上方只是电池，会被 canReceive 挡掉），
			// 产物不会自己流走 —— 不清理的话，下一轮一进内层循环就会
			// 因为「上一轮那一件还在缓存里」而立刻 break，两个倒计时都读不到。
			for (int slot = 0; slot < MachineConfig.SLOT_CACHE_COUNT; slot++) {
				machine.setItem(MachineConfig.SLOT_CACHE_START + slot, ItemStack.EMPTY);
			}

			machine.setItem(MachineConfig.SLOT_ROD, new ItemStack(Items.FISHING_ROD));
			machine.setStored(MachineConfig.ENERGY_CAPACITY);

			int lureRolled = -1;
			int biteRolled = -1;
			FishingPhase previous = machine.getPhase();

			for (int ticks = 0; ticks < TICKS_PER_CYCLE_BUDGET; ticks++) {
				tickOnce(helper, machine);
				FishingPhase now = machine.getPhase();

				if (previous != FishingPhase.WAITING && now == FishingPhase.WAITING) {
					lureRolled = machine.remainingTicks();
				}
				if (previous != FishingPhase.APPROACHING && now == FishingPhase.APPROACHING) {
					biteRolled = machine.remainingTicks();
				}
				previous = now;

				if (cacheItemCount(machine) > 0) {
					break;
				}
			}

			require(helper, lureRolled > 0,
					"第 " + round + " 轮没有观察到 WAITING 的倒计时（阶段切换失效？）");
			require(helper, biteRolled > 0,
					"第 " + round + " 轮没有观察到 APPROACHING 的倒计时（阶段切换失效？）");
			require(helper, Math.abs(lureRolled - lureCenter) <= lureHalfWidth,
					"第 " + round + " 轮「等鱼上钩」掷出 " + lureRolled + " 刻，超出要求的 "
							+ (lureCenter - lureHalfWidth) + "~" + (lureCenter + lureHalfWidth)
							+ " 刻（约 2 秒 ±0.5 秒）");
			require(helper, Math.abs(biteRolled - biteCenter) <= biteHalfWidth,
					"第 " + round + " 轮「鱼正在靠近」掷出 " + biteRolled + " 刻，超出要求的 "
							+ (biteCenter - biteHalfWidth) + "~" + (biteCenter + biteHalfWidth)
							+ " 刻（约 1 秒 ±0.25 秒）");
		}

		helper.succeed();
	}

	/**
	 * {@link FishingPhase#APPROACHING} 必须描述真正在跑的那个计时器。
	 *
	 * <h2>这条测试守的是哪一类 bug</h2>
	 * 这个阶段曾经叫 {@code HOOKED}、界面写「鱼咬钩了」，因为它被设想成
	 * 「咬钩之后等玩家右键的那个窗口（原版 {@code nibble}，20~40 刻）」，
	 * 而实现里机器人会在窗口第一刻收杆、所以它只持续一刻。
	 *
	 * <p>但真实实现覆盖的是 {@code timeUntilHooked} 的倒计时 —— 咬钩<b>之前</b>的那一段
	 * （15~25 刻，原版 20~80），
	 * 于是名字与文案都在撒谎：玩家看到「鱼咬钩了 · 剩余 2 秒」，
	 * 会以为机器在咬钩后又磨蹭了 2 秒，而手动钓鱼不会有这种感觉。
	 *
	 * <p>文案的准确性没法自动测，但它的<b>前提</b>可以。只要同时成立：
	 * <ol>
	 *   <li>处在这个阶段时计时器还没归零（{@code remainingTicks() > 0}）；</li>
	 *   <li>处在这个阶段时鱼还没进缓存。</li>
	 * </ol>
	 * 「它是咬钩之前的阶段」这个语义就是真的。
	 *
	 * <p>反过来：哪天有人把收杆挪到计时器归零之后若干刻（比如为了「看起来更自然」、
	 * 或真去照抄了 {@code nibble} 窗口），第 1 条会在那一刻当场失败 ——
	 * 「比手动钓鱼还慢」会重新出现的地方，正是这里。
	 *
	 * <p>最后还断言这一轮里确实<b>观察到过</b>这个阶段：否则阶段切换一旦失效，
	 * 循环体一次都不进，测试会「通过」但什么都没验证。
	 */
	@GameTest
	public void approachPhaseIsBeforeTheBite(GameTestHelper helper) {
		buildPondStonework(helper);
		AutoFishingMachineBlockEntity machine = placeMachine(helper, POND_MACHINE, Direction.WEST, true);
		// placeMachine 只放方块与电池，竿要自己插 —— 不加这一行机器会一直停在
		// IDLE/NO_ROD 上，循环空转 2000 刻后由下面那条 observed 断言报出来。
		machine.setItem(MachineConfig.SLOT_ROD, new ItemStack(Items.FISHING_ROD));
		machine.setStored(MachineConfig.ENERGY_CAPACITY);

		int observed = 0;
		for (int ticks = 0; ticks < TICKS_PER_CYCLE_BUDGET; ticks++) {
			tickOnce(helper, machine);

			switch (machine.getPhase()) {
				case IDLE -> { }
				case WAITING -> require(helper, machine.remainingTicks() > 0,
						"第 " + ticks + " 刻：处于 WAITING 但「等鱼上钩」的倒计时已归零（"
								+ machine.remainingTicks() + "）—— 界面那一行与实际不符");
				case APPROACHING -> {
					observed++;
					int remaining = machine.remainingTicks();
					require(helper, remaining > 0,
							"第 " + ticks + " 刻：处于 APPROACHING 但倒计时已归零（" + remaining
									+ "）—— 说明计时器归零后机器还在磨蹭，"
									+ "这一段正是「比手动钓鱼还慢」的来源");
					require(helper, cacheItemCount(machine) == 0,
							"第 " + ticks + " 刻：已处于 APPROACHING，鱼却已经进缓存了 —— "
									+ "APPROACHING 按定义是咬钩**之前**的阶段");
				}
			}

			if (cacheItemCount(machine) > 0) {
				break;
			}
		}

		require(helper, observed > 0,
				"一轮完整周期里从未观察到 APPROACHING —— 这条测试什么也没验证到，"
						+ "先检查阶段切换与 TICKS_PER_CYCLE_BUDGET");
		helper.succeed();
	}

	/** 修补附魔：一杆之后钓竿的耐久应当上升，而不是下降。 */
	@GameTest
	public void mendingRepairsRod(GameTestHelper helper) {
		buildPondStonework(helper);
		AutoFishingMachineBlockEntity machine = placeMachine(helper, POND_MACHINE, Direction.WEST, true);

		ItemStack rod = enchanted(helper, Enchantments.MENDING, 1);
		// 先人为磨损，好让修补有东西可修。
		rod.setDamageValue(30);
		machine.setItem(MachineConfig.SLOT_ROD, rod);
		machine.setStored(MachineConfig.ENERGY_CAPACITY);

		require(helper, runUntilCatch(helper, machine), "带修补的钓竿也应当能钓上东西");

		int damage = machine.getRod().getDamageValue();
		require(helper, !machine.getRod().isEmpty(), "钓竿不应该断");
		require(helper, damage < 30,
				"修补应当把这一杆的经验用于修耐久（30 → " + damage + "），实际没有下降");

		helper.succeed();
	}

	/**
	 * 耐久附魔：同样 40 杆，带耐久III 的钓竿磨损应当明显少于普通钓竿。
	 *
	 * <p>原版耐久III 是「每次消耗有 3/4 的概率被完全挡掉」，期望磨损约为四分之一。
	 * 这里断言「更少」而不是「约等于 10」—— 二项分布仍有极小概率偏出去，
	 * 钉死一个精确值只会制造偶发性的红。
	 */
	@GameTest
	public void unbreakingSlowsDurabilityLoss(GameTestHelper helper) {
		buildPondStonework(helper);
		AutoFishingMachineBlockEntity plain = placeMachine(helper, POND_MACHINE, Direction.WEST, true);
		AutoFishingMachineBlockEntity tough = placeMachine(helper, POND_MACHINE_2, Direction.NORTH, true);

		plain.setItem(MachineConfig.SLOT_ROD, new ItemStack(Items.FISHING_ROD));
		tough.setItem(MachineConfig.SLOT_ROD, enchanted(helper, Enchantments.UNBREAKING, 3));

		final int rounds = 40;
		for (int round = 0; round < rounds; round++) {
			plain.setStored(MachineConfig.ENERGY_CAPACITY);
			tough.setStored(MachineConfig.ENERGY_CAPACITY);
			require(helper, runUntilCatch(helper, plain), "普通钓竿第 " + round + " 杆失败");
			require(helper, runUntilCatch(helper, tough), "耐久钓竿第 " + round + " 杆失败");
			// 注意这里传 false：不能修钓竿，否则磨损被抹掉就比不出差别了。
			recycle(plain, false);
			recycle(tough, false);
		}

		int plainDamage = plain.getRod().getDamageValue();
		int toughDamage = tough.getRod().getDamageValue();

		require(helper, plainDamage == rounds,
				"普通钓竿 " + rounds + " 杆应当正好磨损 " + rounds + " 点，实际 " + plainDamage);
		require(helper, toughDamage < plainDamage,
				"耐久III 的磨损（" + toughDamage + "）应当明显少于普通钓竿（" + plainDamage + "）");

		helper.succeed();
	}

	// ============================================================ 场景搭建

	/** 封闭水域场景：石头方块里挖一格水，机器站在东侧朝西。 */
	static AutoFishingMachineBlockEntity buildPondScene(GameTestHelper helper, boolean withGrid) {
		buildPondStonework(helper);
		return placeMachine(helper, POND_MACHINE, Direction.WEST, withGrid);
	}

	/**
	 * 封闭水域的地形：x∈[0,4] × z∈[0,4] × y∈[0,2] 全是石头，只在 (2,1,2) 挖一格水。
	 *
	 * <p>水坑上方 (2,2,2) 保持石头，所以这是一个完全封闭的水袋 ——
	 * 原版判定第一层就会返回 INVALID。做这个形状是为了得到一个
	 * 「能钓鱼、但一定钓不到宝藏」的确定性场景。
	 */
	private static void buildPondStonework(GameTestHelper helper) {
		buildStonework(helper);
		helper.setBlock(POND_WATER, Blocks.WATER);
	}

	/** 5×5×3 的实心石头块，没有任何水。 */
	private static void buildStonework(GameTestHelper helper) {
		for (int x = 0; x <= 4; x++) {
			for (int z = 0; z <= 4; z++) {
				for (int y = 0; y <= 2; y++) {
					helper.setBlock(new BlockPos(x, y, z), Blocks.STONE);
				}
			}
		}
	}

	/** 开放水域场景：5×5×2 的水池，机器站在东岸朝西。 */
	static AutoFishingMachineBlockEntity buildPoolScene(GameTestHelper helper, boolean withGrid) {
		buildPoolStonework(helper);

		// 水体
		for (int x = WATER_MIN; x <= WATER_MAX; x++) {
			for (int z = WATER_MIN; z <= WATER_MAX; z++) {
				for (int y = WATER_LOW; y <= WATER_HIGH; y++) {
					helper.setBlock(new BlockPos(x, y, z), Blocks.WATER);
				}
			}
		}

		return placeMachine(helper, POOL_MACHINE, Direction.WEST, withGrid);
	}

	/** 池底与池壁。水体与机器由调用方补上，方便复用给不同摆位。 */
	private static void buildPoolStonework(GameTestHelper helper) {
		for (int x = STONE_MIN; x <= STONE_MAX; x++) {
			for (int z = STONE_MIN; z <= STONE_MAX; z++) {
				helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
			}
		}
		for (int y = WATER_LOW; y <= WATER_HIGH; y++) {
			for (int i = STONE_MIN; i <= STONE_MAX; i++) {
				helper.setBlock(new BlockPos(i, y, STONE_MIN), Blocks.STONE);
				helper.setBlock(new BlockPos(i, y, STONE_MAX), Blocks.STONE);
				helper.setBlock(new BlockPos(STONE_MIN, y, i), Blocks.STONE);
				helper.setBlock(new BlockPos(STONE_MAX, y, i), Blocks.STONE);
			}
		}
	}

	/**
	 * 放一台机器（可选地连同它的电池）。
	 *
	 * <p>电池放在机器正上方一格。之所以能当「接上电网」用，是因为
	 * {@code GridLink} 对非导体邻居的要求是 {@code supportsExtraction()} 为真，
	 * 而电池的输出速率与电量无关（它的 {@code getMaxExtract} 只看 {@code getBaseMaxOutput}）。
	 * 也就是说这台电池不需要先充过电。
	 */
	static AutoFishingMachineBlockEntity placeMachine(GameTestHelper helper,
			BlockPos pos, Direction facing, boolean withGrid) {
		if (withGrid) {
			helper.setBlock(pos.above(), GRID_BATTERY);
		}
		helper.setBlock(pos, ModBlocks.AUTO_FISHING_MACHINE.defaultBlockState()
				.setValue(BlockMachineBase.FACING, facing));
		return helper.getBlockEntity(pos, AutoFishingMachineBlockEntity.class);
	}

	// ============================================================ 辅助

	/**
	 * 手动推一刻。
	 *
	 * <p>方块状态每次都从世界里现取：状态机开周期时会写 {@code ACTIVE}，
	 * 若用方块实体里可能过期的缓存，{@code getFacing()} 会读到旧朝向。
	 */
	static void tickOnce(GameTestHelper helper, AutoFishingMachineBlockEntity machine) {
		BlockPos pos = machine.getBlockPos();
		machine.tick(helper.getLevel(), pos, helper.getLevel().getBlockState(pos), machine);
	}

	/**
	 * 一直推到这个周期钓上东西为止。
	 *
	 * @return 是否在预算内钓到了东西
	 */
	private static boolean runUntilCatch(GameTestHelper helper, AutoFishingMachineBlockEntity machine) {
		for (int ticks = 0; ticks < TICKS_PER_CYCLE_BUDGET; ticks++) {
			tickOnce(helper, machine);
			if (cacheItemCount(machine) > 0) {
				return true;
			}
		}
		return false;
	}

	/**
	 * 收走缓存，可选地把钓竿修好、并把电补满 —— 让每一轮从同一状态出发。
	 *
	 * <p>缓存必须清空，否则从第 10 杆开始机器会因为「缓存满」停机，
	 * 统计出来的都是停机而不是钓鱼。
	 *
	 * @param repairRod 是否把钓竿耐久清零。测磨损的用例必须传 {@code false}，
	 *                  否则每轮都把磨损抹掉，两根钓竿的差别也就没了。
	 */
	private static void recycle(AutoFishingMachineBlockEntity machine, boolean repairRod) {
		for (int index = 0; index < MachineConfig.SLOT_CACHE_COUNT; index++) {
			machine.setItem(MachineConfig.SLOT_CACHE_START + index, ItemStack.EMPTY);
		}
		if (repairRod && !machine.getRod().isEmpty()) {
			machine.getRod().setDamageValue(0);
		}
		machine.setStored(MachineConfig.ENERGY_CAPACITY);
	}

	/** 造一根带指定附魔的钓竿。 */
	private static ItemStack enchanted(GameTestHelper helper, ResourceKey<Enchantment> key, int level) {
		ItemStack stack = new ItemStack(Items.FISHING_ROD);
		Registry<Enchantment> registry = helper.getLevel().registryAccess().lookupOrThrow(Registries.ENCHANTMENT);
		Holder<Enchantment> enchantment = registry.getOrThrow(key);
		stack.enchant(enchantment, level);
		return stack;
	}

	static int cacheItemCount(AutoFishingMachineBlockEntity machine) {
		int total = 0;
		for (int index = 0; index < MachineConfig.SLOT_CACHE_COUNT; index++) {
			total += machine.getCacheStack(index).getCount();
		}
		return total;
	}

	private static boolean containsTreasureOnly(AutoFishingMachineBlockEntity machine) {
		for (int index = 0; index < MachineConfig.SLOT_CACHE_COUNT; index++) {
			ItemStack stack = machine.getCacheStack(index);
			if (!stack.isEmpty() && TREASURE_ONLY.contains(stack.getItem())) {
				return true;
			}
		}
		return false;
	}

	/** 取缓存里那唯一一件产物，顺带断言「确实只有一件」。 */
	private static ItemStack singleCachedItem(GameTestHelper helper, AutoFishingMachineBlockEntity machine) {
		List<ItemStack> found = new ArrayList<>();
		for (int index = 0; index < MachineConfig.SLOT_CACHE_COUNT; index++) {
			ItemStack stack = machine.getCacheStack(index);
			if (!stack.isEmpty()) {
				found.add(stack);
			}
		}
		require(helper, found.size() == 1, "缓存里应当只有一件产物，实际 " + found.size() + " 件");
		return found.get(0);
	}

	/** 断言辅助：失败时抛 GameTest 自己的异常，报告里会带上这条消息。 */
	private static void require(GameTestHelper helper, boolean condition, String message) {
		if (!condition) {
			throw helper.assertionException(message);
		}
	}
}
