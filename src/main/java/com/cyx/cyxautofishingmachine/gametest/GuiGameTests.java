package com.cyx.cyxautofishingmachine.gametest;

import com.cyx.crimsoncoppergrid.common.blocks.BlockMachineBase;
import com.cyx.cyxautofishingmachine.blockentity.AutoFishingMachineBlockEntity;
import com.cyx.cyxautofishingmachine.config.MachineConfig;
import com.cyx.cyxautofishingmachine.fishing.FishingPhase;
import com.cyx.cyxautofishingmachine.fishing.PauseReason;
import com.cyx.cyxautofishingmachine.fishing.WaterVerdict;
import com.cyx.cyxautofishingmachine.menu.AutoFishingMachineMenu;
import com.cyx.cyxautofishingmachine.menu.MachineTooltips;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 界面数据层的自动化测试：提示内容、水域结论、{@code ACTIVE} 方块状态。
 *
 * <h2>为什么这些要放在服务端测</h2>
 * 提示的<b>内容</b>（显示哪个数、挂哪个原因、给哪条建议）来自
 * {@code ContainerData} —— 那是服务端与客户端共用的一条通道，
 * 服务端读到的与客户端看到的是同一份。所以在服务端就能把
 * 「悬停电量条显示的是不是真实电量」这类问题验掉，
 * 而且是逐字段断言，比看截图精确得多。
 *
 * <p><b>画在哪、什么时候弹</b>是另一回事，那属于渲染，
 * 由客户端端到端测试与截图覆盖（见 {@code docs/TESTING.md}）。
 * 两层合起来才是完整的界面验证。
 *
 * <h2>场景为什么复用 {@link FishingGameTests}</h2>
 * 水域结论的两种取值必须用真实地形试（封闭水域 = 石头里挖一格水；
 * 开放水域 = 5×5×2 的水池），这套地形在钓鱼测试里已经搭好并验证过。
 * 这里直接调用它的场景助手（包内可见），而不是再抄一份 ——
 * 两份地形迟早会有一份被改坏而另一份不知道。
 *
 * <h2>提示内容怎么断言</h2>
 * {@link Component} 的内容是 {@link TranslatableContents}，
 * 里面带着翻译键和参数。断言<b>键和参数</b>而不是 {@code getString()}：
 * 后者取决于服务器当前的语言表，服务端上往往原样返回键名，
 * 拿它断言等于什么都没断言；键和参数才是与语言无关、真正说「显示了对的东西」的部分。
 */
public class GuiGameTests {

	// ============================================================ 水域结论

	/**
	 * 线上取值与枚举的对应关系。
	 *
	 * <p>这里的三个数字是<b>线上格式</b>：老存档、老客户端读到的就是这个。
	 * 动它们等于改协议，所以用字面量写死在断言里 —— 改了这里就会失败，
	 * 提醒你去核对存档兼容性，而不是悄悄改掉。
	 */
	@GameTest
	public void waterVerdictMapsEveryWireValue(GameTestHelper helper) {
		require(helper, WaterVerdict.fromState(0) == WaterVerdict.UNKNOWN, "线上 0 应当是「未知」");
		require(helper, WaterVerdict.fromState(1) == WaterVerdict.CLOSED, "线上 1 应当是「封闭」");
		require(helper, WaterVerdict.fromState(2) == WaterVerdict.OPEN, "线上 2 应当是「开放」");
		// 客户端比服务端旧时可能读到没见过的数字。退回 UNKNOWN 会让界面显示
		// 一句「还没确定」，而不是把整个连接炸掉。
		require(helper, WaterVerdict.fromState(99) == WaterVerdict.UNKNOWN, "未知取值应当退回「未知」而不是抛异常");

		// 宝藏门槛只有一个出口：OPEN。这正是原版 in_open_water 谓词的语义。
		require(helper, WaterVerdict.OPEN.treasurePossible(), "开放水域可以出宝藏");
		require(helper, !WaterVerdict.CLOSED.treasurePossible(), "封闭水域出不了宝藏");
		require(helper, !WaterVerdict.UNKNOWN.treasurePossible(), "未判定的水域不能默认能出宝藏");

		// 三个取值的键必须互不相同：撞了键就会出现「封闭」显示成「开放」。
		require(helper, !WaterVerdict.UNKNOWN.valueKey().equals(WaterVerdict.CLOSED.valueKey())
				&& !WaterVerdict.CLOSED.valueKey().equals(WaterVerdict.OPEN.valueKey()),
				"三个水域结论的翻译键必须互不相同");

		helper.succeed();
	}

	/**
	 * 菜单读到的水域结论必须与方块实体算出来的一致。
	 *
	 * <p>这条测试真正防的是<b>下标错位</b>：{@code DATA_OPEN_WATER} 在
	 * 方块实体与菜单两边各写了一遍，错一格不会报错、只会显示错东西。
	 * 封闭与开放两种地形都跑一遍，两种错位都会被抓到。
	 */
	@GameTest
	public void menuShowsTheWaterVerdictTheMachineComputed(GameTestHelper helper) {
		AutoFishingMachineBlockEntity pond = FishingGameTests.buildPondScene(helper, false);
		insertRod(pond);
		FishingGameTests.tickOnce(helper, pond);

		AutoFishingMachineMenu pondMenu = openMenu(helper, pond);
		require(helper, pond.getOpenWaterState() == AutoFishingMachineBlockEntity.OPEN_WATER_CLOSED,
				"一格水坑应当判定为封闭水域，实际 " + pond.getOpenWaterState());
		require(helper, pondMenu.getOpenWaterState() == pond.getOpenWaterState(),
				"菜单读到的水域状态（" + pondMenu.getOpenWaterState()
						+ "）与方块实体算出的（" + pond.getOpenWaterState() + "）不一致 —— 下标错位");
		require(helper, pondMenu.getWaterVerdict() == WaterVerdict.CLOSED,
				"菜单的水域结论应当是「封闭」");
		helper.succeed();
	}

	/** 同上，但用开放水池 —— 验证另一个方向的错位。 */
	@GameTest
	public void menuShowsOpenWaterInThePool(GameTestHelper helper) {
		AutoFishingMachineBlockEntity pool = FishingGameTests.buildPoolScene(helper, false);
		insertRod(pool);
		FishingGameTests.tickOnce(helper, pool);

		AutoFishingMachineMenu menu = openMenu(helper, pool);
		require(helper, pool.getOpenWaterState() == AutoFishingMachineBlockEntity.OPEN_WATER_OPEN,
				"5×5×2 水池应当判定为开放水域，实际 " + pool.getOpenWaterState());
		require(helper, menu.getWaterVerdict() == WaterVerdict.OPEN, "菜单的水域结论应当是「开放」");
		helper.succeed();
	}

	// ============================================================ 工具提示内容

	/**
	 * 电量提示必须给出<b>精确</b>数值 —— 这是它在条内短格式之外存在的全部意义。
	 *
	 * <p>电量刻意取 4321 而不是一个整数：取整数的电量（比如 5000）
	 * 在「除以 500」时两者都得出干净的商，分不清提示算的是
	 * 「电量 ÷ 单杆耗电」还是「随手写死的一个数」。
	 */
	@GameTest
	public void energyTooltipUsesExactNumbers(GameTestHelper helper) {
		AutoFishingMachineBlockEntity machine = FishingGameTests.buildPondScene(helper, false);
		machine.setStored(4321L);
		AutoFishingMachineMenu menu = openMenu(helper, machine);

		var lines = MachineTooltips.energy(menu);
		require(helper, lines.size() == 3, "电量提示应当是三行，实际 " + lines.size());

		TranslatableContents amount = translatable(helper, lines.get(0), "电量数值行");
		require(helper, amount.getKey().equals("gui.cyxautofishingmachine.tooltip.energy"),
				"第一行应当是精确数值，实际键 " + amount.getKey());
		require(helper, argsAre(amount, 4321L, 10000L),
				"第一行应当是 4321 / 10000，实际 " + describe(amount));

		TranslatableContents perCatch = translatable(helper, lines.get(1), "单杆耗电行");
		require(helper, perCatch.getKey().equals("gui.cyxautofishingmachine.tooltip.energy_per_catch"),
				"第二行应当是单杆耗电，实际键 " + perCatch.getKey());
		require(helper, argsAre(perCatch, (long) MachineConfig.ENERGY_PER_CATCH),
				"第二行应当是每杆 " + MachineConfig.ENERGY_PER_CATCH + " FE，实际 " + describe(perCatch));

		TranslatableContents left = translatable(helper, lines.get(2), "剩余杆数行");
		require(helper, left.getKey().equals("gui.cyxautofishingmachine.tooltip.catches_left"),
				"第三行应当是还能钓几杆，实际键 " + left.getKey());
		require(helper, argsAre(left, 8L), "4321 ÷ 500 应当是 8 杆，实际 " + describe(left));

		helper.succeed();
	}

	/** 空缓冲不能让提示崩掉或出现负数 —— 这段代码跑在渲染路径上。 */
	@GameTest
	public void energyTooltipSurvivesAnEmptyBuffer(GameTestHelper helper) {
		AutoFishingMachineBlockEntity machine = FishingGameTests.buildPondScene(helper, false);
		machine.setStored(0L);
		AutoFishingMachineMenu menu = openMenu(helper, machine);

		var lines = MachineTooltips.energy(menu);
		TranslatableContents left = translatable(helper, lines.get(2), "剩余杆数行");
		require(helper, argsAre(left, 0L), "电量为 0 时应当显示还够 0 杆，实际 " + describe(left));
		helper.succeed();
	}

	/**
	 * 停机时的提示必须包含「该怎么办」—— 这是状态行那 86 像素放不下的部分。
	 *
	 * <p>两种原因一起测，因为它们的建议<b>必须不同</b>：
	 * 「缓存满」要放箱子、「电量不足」要去查发电。若两条建议撞成同一句，
	 * 这层提示就失去了它存在的理由。
	 */
	@GameTest
	public void pausedTooltipSaysWhatToDo(GameTestHelper helper) {
		// 必须带电池：NOT_ENOUGH_POWER 的前提是「接上了电网但电不够」。
		// 没有电池时先撞上的是 GRID_DISCONNECTED，根本轮不到电量这一条。
		AutoFishingMachineBlockEntity machine = FishingGameTests.buildPondScene(helper, true);
		FishingGameTests.tickOnce(helper, machine);
		AutoFishingMachineMenu menu = openMenu(helper, machine);

		require(helper, menu.getPauseReason() == PauseReason.NO_ROD, "没放钓竿应当报 NO_ROD");
		var lines = MachineTooltips.status(menu);
		require(helper, lines.size() == 2, "停机提示应当是「是什么 + 怎么办」两行，实际 " + lines.size());
		require(helper, translatable(helper, lines.get(0), "原因行").getKey()
						.equals("gui.cyxautofishingmachine.status.no_rod"),
				"第一行应当说没钓竿，实际 " + describe(translatable(helper, lines.get(0), "原因行")));
		require(helper, translatable(helper, lines.get(1), "建议行").getKey()
						.equals("gui.cyxautofishingmachine.hint.no_rod"),
				"第二行应当建议放钓竿，实际 " + describe(translatable(helper, lines.get(1), "建议行")));
		require(helper, MachineTooltips.statusLine(menu).severity() == MachineTooltips.Severity.WARNING,
				"停机原因应当是告警级（红色）");

		// 同一台机器，把电抽干 —— 原因换成「电量不足」，建议也要跟着换。
		insertRod(machine);
		machine.setStored(0L);
		FishingGameTests.tickOnce(helper, machine);
		require(helper, menu.getPauseReason() == PauseReason.NOT_ENOUGH_POWER, "电不够应当报 NOT_ENOUGH_POWER");
		var powered = MachineTooltips.status(menu);
		require(helper, translatable(helper, powered.get(1), "建议行").getKey()
						.equals("gui.cyxautofishingmachine.hint.not_enough_power"),
				"电量不足的建议应当是去查发电，实际 "
						+ describe(translatable(helper, powered.get(1), "建议行")));
		helper.succeed();
	}

	/**
	 * 运行中的提示要给「还剩多久」，两种单位都给。
	 *
	 * <p>刻数直接拿菜单里的值比对：提示里的参数若与进度条用的不是同一个数，
	 * 玩家会看到「条还剩一半、文字说快完了」这种自相矛盾。
	 */
	@GameTest
	public void runningTooltipShowsPhaseAndRemaining(GameTestHelper helper) {
		AutoFishingMachineBlockEntity machine = startCycle(helper);
		AutoFishingMachineMenu menu = openMenu(helper, machine);

		require(helper, menu.getPhase() == FishingPhase.WAITING, "应当已经进入等待阶段");
		var lines = MachineTooltips.status(menu);
		require(helper, lines.size() == 2, "运行中提示应当是「阶段 + 剩余时间」两行，实际 " + lines.size());
		require(helper, translatable(helper, lines.get(0), "阶段行").getKey()
						.equals("gui.cyxautofishingmachine.phase.waiting"),
				"第一行应当说在等鱼上钩，实际 " + describe(translatable(helper, lines.get(0), "阶段行")));

		TranslatableContents progress = translatable(helper, lines.get(1), "剩余时间行");
		require(helper, progress.getKey().equals("gui.cyxautofishingmachine.tooltip.progress"),
				"第二行应当是剩余时间，实际键 " + progress.getKey());
		Object[] args = progress.getArgs();
		require(helper, args.length == 2 && args[1] instanceof Integer ticks
						&& ticks.intValue() == menu.getRemainingTicks(),
				"提示里的刻数（" + (args.length > 1 ? args[1] : "无")
						+ "）应当与进度条用的 " + menu.getRemainingTicks() + " 一致");
		require(helper, MachineTooltips.statusLine(menu).severity() == MachineTooltips.Severity.NORMAL,
				"正常运行不应当是告警色");
		helper.succeed();
	}

	/**
	 * 周期内电网被剪断：机器不停（电是预扣的），但状态行必须提醒。
	 *
	 * <p>严重级必须是 CAUTION 而不是 WARNING —— 用红色会让玩家以为机器停了。
	 * 这正是 Phase 5 把「运行中」与「停机」分开呈现的理由，这里把它钉死。
	 */
	@GameTest
	public void gridCutWhileRunningStaysCautionary(GameTestHelper helper) {
		AutoFishingMachineBlockEntity machine = startCycle(helper);
		AutoFishingMachineMenu menu = openMenu(helper, machine);

		// 剪掉电池。状态机在周期内不查电网，所以机器照常等鱼。
		//
		// 这里刻意走 level 而不是 helper.setBlock：方块实体的 worldPosition 是
		// 世界坐标，而 helper.setBlock 吃的是相对结构原点的坐标 ——
		// 两者之间要过一层 absolutePos/relativePos 换算。实测这条换算链
		// 在「用方块实体的坐标反推相对坐标」时并不可靠（电池纹丝不动，
		// 且不报任何坐标相关的错误）。既然要动的就是「机器上方那一格」，
		// 直接用世界坐标写最不容易错。
		helper.getLevel().setBlockAndUpdate(machine.getBlockPos().above(), Blocks.AIR.defaultBlockState());
		// 电网连接是节流重算的（20 刻一次），推过这个间隔才拿得到新结论。
		for (int tick = 0; tick < 25; tick++) {
			FishingGameTests.tickOnce(helper, machine);
		}

		require(helper, menu.getPhase() == FishingPhase.WAITING, "周期内剪线不应当让机器停");
		require(helper, !menu.isGridLinked(), "剪线之后菜单应当显示已断开");
		var line = MachineTooltips.statusLine(menu);
		require(helper, translatable(helper, line.text(), "状态行").getKey()
						.equals("gui.cyxautofishingmachine.status.running_unpowered"),
				"运行中剪线应当显示「运行中·电网断开」，实际 " + describe(translatable(helper, line.text(), "状态行")));
		require(helper, line.severity() == MachineTooltips.Severity.CAUTION,
				"运行中剪线是橙色（CAUTION），不是红色 —— 红色会让玩家以为机器停了");
		helper.succeed();
	}

	/**
	 * 封闭水域的提示要多给一句「移向水面中间」。
	 *
	 * <p>判定门槛（浮标周围 ±2 格、上下 4 层）在本模组里没有第二处解释，
	 * 这一句是玩家唯一的线索。开放与未知不需要它 —— 一个是好消息，一个还没开始。
	 */
	@GameTest
	public void waterTooltipExplainsClosedWater(GameTestHelper helper) {
		AutoFishingMachineBlockEntity machine = FishingGameTests.buildPondScene(helper, false);
		insertRod(machine);
		FishingGameTests.tickOnce(helper, machine);
		AutoFishingMachineMenu menu = openMenu(helper, machine);

		var lines = MachineTooltips.water(menu);
		require(helper, lines.size() == 2, "封闭水域的提示应当有两行（结论 + 怎么办），实际 " + lines.size());
		require(helper, translatable(helper, lines.get(0), "结论行").getKey()
						.equals("gui.cyxautofishingmachine.tooltip.water_closed"),
				"第一行应当说封闭水域，实际 " + describe(translatable(helper, lines.get(0), "结论行")));
		require(helper, translatable(helper, lines.get(1), "建议行").getKey()
						.equals("gui.cyxautofishingmachine.tooltip.water_closed_hint"),
				"第二行应当建议移向水面中间，实际 " + describe(translatable(helper, lines.get(1), "建议行")));

		// 没有可下钩的水时只有一句「还没确定」—— 它不是故障，不需要建议行。
		AutoFishingMachineBlockEntity bare = FishingGameTests.placeMachine(helper,
				new BlockPos(1, 1, 1), Direction.WEST, false);
		FishingGameTests.tickOnce(helper, bare);
		var unknown = MachineTooltips.water(openMenu(helper, bare));
		require(helper, unknown.size() == 1, "未判定的水域不需要建议行，实际 " + unknown.size() + " 行");
		require(helper, translatable(helper, unknown.get(0), "结论行").getKey()
						.equals("gui.cyxautofishingmachine.tooltip.water_unknown"),
				"未判定应当说还没确定，实际 " + describe(translatable(helper, unknown.get(0), "结论行")));
		helper.succeed();
	}

	// ============================================================ ACTIVE 方块状态

	/**
	 * 「运行中」贴图的开关跟着周期走。
	 *
	 * <p>方块状态从世界里现取 —— 方块实体里若有缓存，读到的是旧值。
	 * 这条测试防的是「状态机改了但忘了写方块状态」（或者反过来）：
	 * 一旦两边脱钩，界面上说在钓鱼、方块上灯却是灭的。
	 */
	@GameTest
	public void activeBlockStateFollowsTheCycle(GameTestHelper helper) {
		AutoFishingMachineBlockEntity machine = startCycle(helper);

		BlockState running = helper.getLevel().getBlockState(machine.getBlockPos());
		require(helper, running.getValue(BlockMachineBase.ACTIVE),
				"进入等待阶段后 ACTIVE 应当为 true（贴图上的运转指示灯应当亮）");

		// 拿走钓竿 → 周期中止 → 指示灯必须熄灭。
		machine.setItem(MachineConfig.SLOT_ROD, ItemStack.EMPTY);
		FishingGameTests.tickOnce(helper, machine);

		BlockState stopped = helper.getLevel().getBlockState(machine.getBlockPos());
		require(helper, !stopped.getValue(BlockMachineBase.ACTIVE),
				"周期中止后 ACTIVE 应当为 false —— 灯亮着会把「机器停了」藏起来");
		require(helper, openMenu(helper, machine).getPauseReason() == PauseReason.NO_ROD,
				"拿走钓竿后的停机原因应当是 NO_ROD");
		helper.succeed();
	}

	// ============================================================ 辅助

	/** 造一台「插了竿、有水、有电」并已进入等待阶段的机器。 */
	private static AutoFishingMachineBlockEntity startCycle(GameTestHelper helper) {
		AutoFishingMachineBlockEntity machine = FishingGameTests.buildPondScene(helper, true);
		insertRod(machine);
		machine.setStored(MachineConfig.ENERGY_CAPACITY);
		FishingGameTests.tickOnce(helper, machine);
		require(helper, machine.getPhase() == FishingPhase.WAITING,
				"竿、水、电齐备时第一刻就应当开周期，实际 " + machine.getPhase());
		return machine;
	}

	private static void insertRod(AutoFishingMachineBlockEntity machine) {
		machine.setItem(MachineConfig.SLOT_ROD, new ItemStack(Items.FISHING_ROD));
	}

	private static AutoFishingMachineMenu openMenu(GameTestHelper helper, AutoFishingMachineBlockEntity machine) {
		Player player = helper.makeMockPlayer(GameType.SURVIVAL);
		return new AutoFishingMachineMenu(0, player.getInventory(), machine);
	}

	private static TranslatableContents translatable(GameTestHelper helper, Component component, String what) {
		if (component.getContents() instanceof TranslatableContents translatable) {
			return translatable;
		}
		throw helper.assertionException(what + " 不是可翻译组件：" + component);
	}

	private static boolean argsAre(TranslatableContents contents, Object... expected) {
		Object[] actual = contents.getArgs();
		if (actual.length != expected.length) {
			return false;
		}
		for (int index = 0; index < expected.length; index++) {
			if (!expected[index].equals(actual[index])) {
				return false;
			}
		}
		return true;
	}

	private static String describe(TranslatableContents contents) {
		StringBuilder text = new StringBuilder(contents.getKey());
		for (Object arg : contents.getArgs()) {
			text.append(" | ").append(arg);
		}
		return text.toString();
	}

	private static void require(GameTestHelper helper, boolean condition, String message) {
		if (!condition) {
			throw helper.assertionException(message);
		}
	}
}
