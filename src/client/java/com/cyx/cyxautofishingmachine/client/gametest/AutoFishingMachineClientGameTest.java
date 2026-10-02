package com.cyx.cyxautofishingmachine.client.gametest;

import java.nio.file.Path;

import com.cyx.cyxautofishingmachine.client.screen.AutoFishingMachineScreen;
import com.cyx.cyxautofishingmachine.config.MachineConfig;
import com.cyx.cyxautofishingmachine.fishing.WaterVerdict;
import com.cyx.cyxautofishingmachine.menu.AutoFishingMachineMenu;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

import com.cyx.crimsoncoppergrid.common.blocks.BlockMachineBase;

import net.minecraft.client.gui.Font;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

/**
 * 客户端的端到端自动化测试：真的把界面打开，然后截图。
 *
 * <h2>为什么非要有它</h2>
 * 服务端那边的 GameTest 能验证物品搬运与提示<b>内容</b>，但它碰不到界面：
 * <ul>
 *   <li>「右键方块到底会不会打开界面」—— 走的是 {@code BlockMachineBase#useWithoutItem}
 *       与 {@code MenuScreens.register} 的接线，服务端一次都不会经过；</li>
 *   <li>「界面画出来长什么样」—— 布局重叠、文字溢出、坐标跑偏、
 *       悬停提示弹不弹得出来，这些只有真的渲染一遍才看得见。</li>
 * </ul>
 * 本类用一个真实客户端做完这一整套：建世界 → 放机器 → 截方块外观 →
 * 传送 → 看向方块 → 右键 → 等界面出现 → 断言 → 悬停电量条 → 截提示。
 *
 * <h2>三张截图各管一件事</h2>
 * <table border="1">
 *   <caption>截图与验证点的对应</caption>
 *   <tr><th>截图</th><th>验证什么</th></tr>
 *   <tr><td>{@code auto_fishing_machine_block_active}</td>
 *       <td>方块外观：{@code ACTIVE=true} 时正面指示灯点亮（模型/贴图接线的最终证据；
 *           截图前先用 {@link #assertActiveFlagReachedTheClient} 证明状态确实同步到了客户端）</td></tr>
 *   <tr><td>{@code auto_fishing_machine_gui}</td>
 *       <td>界面布局：电量条、进度条、水域指示器、状态文字各就各位</td></tr>
 *   <tr><td>{@code auto_fishing_machine_tooltip_energy}</td>
 *       <td>悬停机制： {@code setTooltipForNextFrame} 真的会弹出提示</td></tr>
 * </table>
 *
 * <p>提示里<b>写了什么</b>不在这里断言 —— 那是服务端 GameTest 的事（逐字段），
 * 这里只负责「弹出来了」和「肉眼看得清」。
 *
 * <h2>怎么运行</h2>
 * <pre>{@code ./gradlew runClientGameTest}</pre>
 * 会弹出客户端窗口，跑完自动关闭。截图落在运行目录的 {@code screenshots/} 下。
 */
public class AutoFishingMachineClientGameTest implements FabricClientGameTest {

	/** 机器的位置。挑在很高的地方，避开世界生成出来的地形。 */
	private static final BlockPos MACHINE_POS = new BlockPos(0, 100, 0);

	/** 玩家站的位置：机器正南 3 格，在创造模式的 5 格交互距离内。 */
	private static final int STAND_X = 0;
	private static final int STAND_Y = 100;
	private static final int STAND_Z = 3;

	/** 界面尺寸，与原版容器界面一致（也是上游 MachineScreen 用的那张熔炉贴图的尺寸）。 */
	private static final int GUI_WIDTH = 176;
	private static final int GUI_HEIGHT = 166;

	@Override
	public void runTest(ClientGameTestContext context) {
		try (TestSingleplayerContext singleplayer = context.worldBuilder()
				// 固定世界设置：每次跑出来的地形一样，测试结果才可复现。
				.setUseConsistentSettings(true)
				.create()) {

			setupScene(singleplayer);
			context.waitTicks(20);

			// 先看向方块 —— 后面所有截图都要这个视角。
			// 打不到就立刻把原因说清楚 —— 与其等到界面超时才发现，
			// 不如在这里区分「没对准」和「对准了但右键没生效」。
			context.getInput().lookAt(MACHINE_POS);
			context.waitTicks(5);
			assertCrosshairOnMachine(context);

			// 把机器摆成「运行中」再截方块外观：ACTIVE=true 的正面应当是
			// 指示灯点亮的那张贴图。这是 blockstate → 模型 → 贴图 这条
			// 静态接线的运行时证据 —— tools/check_resources.py 只能证明
			// 文件存在，证明不了画面对不对。
			run(singleplayer, "setblock %d %d %d cyxautofishingmachine:auto_fishing_machine[facing=south,active=true]"
					.formatted(MACHINE_POS.getX(), MACHINE_POS.getY(), MACHINE_POS.getZ()));
			context.waitTicks(5);
			assertActiveFlagReachedTheClient(context, true);
			Path activeShot = context.takeScreenshot("auto_fishing_machine_block_active");
			System.out.println("[ClientGameTest] 运行中方块截图：" + activeShot.toAbsolutePath());

			// 恢复成停机外观。机器没插竿，状态机不会碰 ACTIVE，所以这里设的值能一直留到后面。
			run(singleplayer, "setblock %d %d %d cyxautofishingmachine:auto_fishing_machine[facing=south]"
					.formatted(MACHINE_POS.getX(), MACHINE_POS.getY(), MACHINE_POS.getZ()));
			context.waitTicks(10);
			assertActiveFlagReachedTheClient(context, false);

			// 触发「使用方块」。
			//
			// 这里刻意走 gameMode.useItemOn —— 它是客户端侧交互的真实入口，
			// 原版的按键处理（Minecraft.startUseItem）最终也是调到同一个方法。
			// 跳过「按键 → startUseItem」这一段是权衡后的选择：
			// 那一层是纯原版逻辑，与本模组无关；而 Fabric 的按键模拟走的是
			// 注入式输入，在自动化环境里不一定被 Minecraft 的按键轮询读到。
			// 本测试要回答的问题是「右键方块会不会打开我们的界面」，
			// 而这个问题在 useItemOn 这一层就能得到完整、可信的答案。
			context.runOnClient(minecraft -> minecraft.gameMode.useItemOn(
					minecraft.player,
					InteractionHand.MAIN_HAND,
					(BlockHitResult) minecraft.hitResult));
			context.waitTicks(5);

			// 界面真的打开了才会往下走，否则这里会等到超时并报失败。
			context.waitForScreen(AutoFishingMachineScreen.class);
			context.waitTicks(10);

			int[] origin = verifyMenuLayout(context);
			assertWaterIndicatorFits(context);

			// 把光标挪到界面外，截一张「干净」的布局图。
			context.getInput().setCursorPos(
					toPixelX(context, 2),
					toPixelY(context, origin[1] + GUI_HEIGHT + 4));
			context.waitTicks(2);
			Path screenshot = context.takeScreenshot("auto_fishing_machine_gui");
			System.out.println("[ClientGameTest] 界面截图：" + screenshot.toAbsolutePath());

			// 悬停在电量条正中，验证提示真的会弹出来。
			context.getInput().setCursorPos(
					toPixelX(context, origin[0] + AutoFishingMachineMenu.ENERGY_BAR_X
							+ AutoFishingMachineMenu.ENERGY_BAR_WIDTH / 2),
					toPixelY(context, origin[1] + AutoFishingMachineMenu.ENERGY_BAR_Y
							+ AutoFishingMachineMenu.ENERGY_BAR_HEIGHT / 2));
			context.waitTicks(3);
			Path tooltip = context.takeScreenshot("auto_fishing_machine_tooltip_energy");
			System.out.println("[ClientGameTest] 电量提示截图：" + tooltip.toAbsolutePath());
		}
	}

	// ------------------------------------------------------------ 场景搭建

	private static void setupScene(TestSingleplayerContext singleplayer) {
		// 让区块保持加载，免得刚放下的方块被卸载掉。
		run(singleplayer, "forceload add 0 0");

		// 清空一小片区域：世界生成出来的地形可能正好挡在玩家和机器之间。
		// 之后再垫一块基岩给玩家站，否则玩家会一路往下掉、越掉离机器越远。
		run(singleplayer, "fill -2 98 -2 2 104 6 minecraft:air");
		run(singleplayer, "setblock %d %d %d minecraft:bedrock".formatted(STAND_X, STAND_Y - 1, STAND_Z));
		run(singleplayer, "setblock %d %d %d cyxautofishingmachine:auto_fishing_machine[facing=south]"
				.formatted(MACHINE_POS.getX(), MACHINE_POS.getY(), MACHINE_POS.getZ()));

		run(singleplayer, "gamemode creative @a");
		run(singleplayer, "tp @a %d %d %d".formatted(STAND_X, STAND_Y, STAND_Z));
		// 玩家位置靠上面垫的基岩稳住 —— 26.3 不允许用 /data merge 改玩家实体数据
		//（实测 "Unable to modify player data"），所以没有 NoGravity 可用。
	}

	/** 确认准星确实落在机器上；没对准则直接给出可诊断的失败信息。 */
	private static void assertCrosshairOnMachine(ClientGameTestContext context) {
		context.runOnClient(minecraft -> {
			System.out.println("[ClientGameTest] 玩家位置=" + minecraft.player.blockPosition()
					+ "，准星命中=" + minecraft.hitResult);

			if (!(minecraft.hitResult instanceof BlockHitResult blockHit)) {
				throw new AssertionError("准星没有命中任何方块：" + minecraft.hitResult);
			}
			if (!blockHit.getBlockPos().equals(MACHINE_POS)) {
				throw new AssertionError("准星命中的是 " + blockHit.getBlockPos()
						+ "，不是机器所在的 " + MACHINE_POS);
			}
		});
	}

	/**
	 * 断言 {@code ACTIVE} 这个方块状态真的传到了客户端的世界里。
	 *
	 * <p>这条断言替代了原先一句只打印不判断的调试输出。它的价值在两处：
	 * <ul>
	 *   <li><b>它自己就是一条端到端证据</b> —— {@code /setblock} 在服务端执行，
	 *       客户端世界的方块状态要靠网络同步才看得到。同步断了，
	 *       界面里的一切照样正常，只有方块外观停在旧状态；</li>
	 *   <li><b>它是截图的前置条件</b> —— 截图的判读靠人眼，而人眼会出错。
	 *       先证明「此刻客户端认为 {@code ACTIVE} 确实是期望值」，
	 *       再看图，这两张图才说明得了「贴图跟着状态变了」。</li>
	 * </ul>
	 *
	 * @param expected 期望的 {@code ACTIVE} 取值
	 */
	private static void assertActiveFlagReachedTheClient(ClientGameTestContext context, boolean expected) {
		context.runOnClient(minecraft -> {
			BlockState state = minecraft.level.getBlockState(MACHINE_POS);
			if (!state.hasProperty(BlockMachineBase.ACTIVE)) {
				throw new AssertionError("客户端世界里的 " + MACHINE_POS + " 不是本模组的机器："
						+ state + " —— 方块可能没放上去，或者已经被别的东西换掉了");
			}
			boolean actual = state.getValue(BlockMachineBase.ACTIVE);
			if (actual != expected) {
				throw new AssertionError("客户端世界里 " + MACHINE_POS + " 的 ACTIVE 应为 "
						+ expected + "，实际 " + actual + "（状态=" + state + "）");
			}
		});
	}

	private static void run(TestSingleplayerContext singleplayer, String command) {
		singleplayer.getServer().runCommand(command);
	}

	// ------------------------------------------------------------ 坐标换算

	/**
	 * GUI 缩放坐标 → 光标的窗口像素坐标。
	 *
	 * <p>原版把光标位置存在<b>窗口屏幕坐标</b>里，画界面时才按
	 * {@code x * guiScaledWidth / screenWidth} 换算成缩放坐标
	 * （见 {@code MouseHandler#getScaledXPos}）。所以要反过来乘。
	 * 直接把缩放坐标塞给 {@code setCursorPos} 会点到一个偏左上的位置 ——
	 * 差得不多时看起来像「提示偶尔弹不出来」，那是最难排查的一类问题。
	 */
	private static double toPixelX(ClientGameTestContext context, int guiScaledX) {
		return context.computeOnClient(minecraft -> (double) guiScaledX
				* minecraft.getWindow().getScreenWidth()
				/ minecraft.getWindow().getGuiScaledWidth());
	}

	private static double toPixelY(ClientGameTestContext context, int guiScaledY) {
		return context.computeOnClient(minecraft -> (double) guiScaledY
				* minecraft.getWindow().getScreenHeight()
				/ minecraft.getWindow().getGuiScaledHeight());
	}

	// ------------------------------------------------------------ 界面断言

	/**
	 * 在客户端线程上检查界面里槽位的位置。
	 *
	 * <p>槽位坐标必须与 {@link AutoFishingMachineMenu} 里的常量一致 ——
	 * 界面画的底框是按槽位坐标补画的（见上游 {@code MachineScreen#extractBackground}），
	 * 两边一旦对不上，就会出现「凹槽画在这里、物品却在别处」的错位。
	 *
	 * @return 界面左上角在 GUI 缩放坐标里的位置（给后面算光标位置用）
	 */
	private static int[] verifyMenuLayout(ClientGameTestContext context) {
		return context.computeOnClient(minecraft -> {
			// 26.3 把「当前屏幕」从 Minecraft 挪到了 Gui 上：Minecraft.gui.screen()。
			if (!(minecraft.gui.screen() instanceof AutoFishingMachineScreen screen)) {
				throw new AssertionError("当前界面不是自动钓鱼机界面：" + minecraft.gui.screen());
			}

			AutoFishingMachineMenu menu = screen.getMenu();

			check(menu.machineSlotCount() == MachineConfig.SLOT_COUNT,
					"菜单槽位数应为 " + MachineConfig.SLOT_COUNT + "，实际 " + menu.machineSlotCount());

			Slot rodSlot = menu.getSlot(MachineConfig.SLOT_ROD);
			check(rodSlot.x == AutoFishingMachineMenu.ROD_X
							&& rodSlot.y == AutoFishingMachineMenu.ROD_Y,
					"钓竿槽坐标应为 (" + AutoFishingMachineMenu.ROD_X + ","
							+ AutoFishingMachineMenu.ROD_Y + ")，实际 ("
							+ rodSlot.x + "," + rodSlot.y + ")");

			Slot firstCache = menu.getSlot(MachineConfig.SLOT_CACHE_START);
			check(firstCache.x == AutoFishingMachineMenu.CACHE_X
							&& firstCache.y == AutoFishingMachineMenu.CACHE_Y,
					"首个缓存槽坐标应为 (" + AutoFishingMachineMenu.CACHE_X + ","
							+ AutoFishingMachineMenu.CACHE_Y + ")，实际 ("
							+ firstCache.x + "," + firstCache.y + ")");

			// 界面必须完整落在窗口的 GUI 缩放坐标里，否则会被裁掉一部分。
			int left = (minecraft.getWindow().getGuiScaledWidth() - GUI_WIDTH) / 2;
			int top = (minecraft.getWindow().getGuiScaledHeight() - GUI_HEIGHT) / 2;
			check(left >= 0 && top >= 0,
					"窗口太小，界面放不下：left=" + left + " top=" + top);
			return new int[] {left, top};
		});
	}

	/**
	 * 水域指示器的三种文本都必须放得下。
	 *
	 * <p>这是一条「本地化不溢出」的断言：中文短、英文长，
	 * 把键写死在这里逐个量，将来任何一次措辞改动只要超宽都会当场失败，
	 * 而不是等到某个语言的玩家看到文字压到缓存槽上。
	 */
	private static void assertWaterIndicatorFits(ClientGameTestContext context) {
		context.runOnClient(minecraft -> {
			Font font = minecraft.font;
			int available = AutoFishingMachineMenu.WATER_MAX_WIDTH;

			int header = font.width(Component.translatable(WaterVerdict.LABEL_KEY));
			check(header <= available,
					"水域表头宽 " + header + "，超过可用 " + available);

			for (WaterVerdict verdict : WaterVerdict.values()) {
				int width = font.width(Component.translatable(verdict.valueKey()));
				check(width <= available,
						verdict + " 的指示器文本宽 " + width + "，超过可用 " + available
								+ " —— 会压到缓存槽上");
			}
		});
	}

	private static void check(boolean condition, String message) {
		if (!condition) {
			throw new AssertionError(message);
		}
	}
}
