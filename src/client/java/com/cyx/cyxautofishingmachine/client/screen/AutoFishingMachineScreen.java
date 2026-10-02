package com.cyx.cyxautofishingmachine.client.screen;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

import com.cyx.crimsoncoppergrid.client.screen.MachineScreen;
import com.cyx.cyxautofishingmachine.fishing.FishingPhase;
import com.cyx.cyxautofishingmachine.fishing.WaterVerdict;
import com.cyx.cyxautofishingmachine.menu.AutoFishingMachineMenu;
import com.cyx.cyxautofishingmachine.menu.MachineTooltips;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

/**
 * 自动钓鱼机界面。
 *
 * <h2>全部靠继承上游的 MachineScreen</h2>
 * 底图、面板、槽位底框、进度条绘制、文字排版都由它提供，本类只负责
 * <b>这台机器独有的四样东西</b>：电量、钓鱼进度、水域结论、状态一句话。
 * 连「界面层不新增任何美术资源」也是继承来的 —— 底图仍是原版熔炉贴图，
 * 机器区被一块面板盖掉，再按槽位坐标把槽位凹槽补画回去。
 *
 * <p>坐标常量一律从 {@link AutoFishingMachineMenu} 取，不在这里重复写一遍：
 * 槽位在哪儿由菜单决定，界面只是照着画。
 *
 * <h2>一行状态 + 一个常驻标记 + 四处悬停提示</h2>
 * 面板左下角能用的横向空间只有 86px（右边是缓存区的第三排槽位），
 * 一行中文最多九个字。所以这里做了一次明确的取舍：
 * <ul>
 *   <li>「还剩多少秒」交给进度条 —— 它是连续量，条形比数字读得快；</li>
 *   <li>「为什么停了」交给状态文字 —— 它是离散的、必须精确读到的信息；</li>
 *   <li>「这片水能不能出宝藏」交给钓竿槽右侧的两行常驻读数 ——
 *       它是这台机器的选址结论，不看到就不知道自己的机器选错了地方；</li>
 *   <li><b>精确数值、单杆耗电、还够钓几杆、下一步该做什么</b>交给悬停提示 ——
 *       这些放不进 86px，但都有宽度不受限的地方可去。</li>
 * </ul>
 * 于是界面呈现分成两层：<b>常驻的一眼扫过</b>（条 + 标签 + 一句话），
 * 和<b>悬停才出现的细节</b>（提示）。提示的文案由
 * {@link MachineTooltips} 生成 —— 那一层不依赖任何客户端类，
 * 因此服务端 GameTest 能直接断言它的内容。
 *
 * <h2>电网状态为什么不再单独占一行</h2>
 * Phase 4 时电网状态是独立一行。加入状态机之后它变成了重复信息：
 * 停机时的「电网未连接」就是暂停原因之一，而运行中电网断开是唯一
 * 一行说不完的情况。于是合并成一条有优先级的判断（见
 * {@link MachineTooltips#statusLine}）：
 * <pre>
 *   停机      → 暂停原因（红色）
 *   运行中且断线 → 「运行中·电网断开」（橙色）
 *   运行中    → 当前阶段（深灰）
 * </pre>
 */
public class AutoFishingMachineScreen extends MachineScreen<AutoFishingMachineMenu> {

	/** 条内文字的白色。 */
	private static final int BAR_TEXT_COLOR = 0xFFFFFFFF;

	/** 需要玩家处理的状态文字：红石红，与上游的电量条同色。 */
	private static final int WARN_COLOR = 0xFFAA0000;

	/**
	 * 需要注意但没停机：橙色。
	 *
	 * <p>与红色刻意区分开：「周期内电网被剪断」不会让机器停下（电是预扣的），
	 * 用红色会误导玩家以为它已经停了。
	 */
	private static final int CAUTION_COLOR = 0xFFCC6600;

	/**
	 * 好消息：深绿。
	 *
	 * <p>面板底色是 {@code 0xFFC6C6C6} 的浅灰，浅绿在上面几乎看不见，
	 * 所以这里用比直觉更暗的一档绿 —— 可读性优先于「绿得显眼」。
	 */
	private static final int OK_COLOR = 0xFF1E7A1E;

	/**
	 * 文字类悬停区的纵向高度（比字高多一点）。
	 *
	 * <p>字实际只有 9px 高，按字数高度判定的话鼠标得压得很准才弹得出来。
	 * 上下各放宽一格，手感明显变好，也不会和别的区域重叠。
	 */
	private static final int TEXT_HIT_HEIGHT = 11;

	public AutoFishingMachineScreen(AutoFishingMachineMenu menu, Inventory playerInventory, Component title) {
		super(menu, playerInventory, title);
	}

	@Override
	protected void extractLabels(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		super.extractLabels(graphics, mouseX, mouseY);

		drawEnergy(graphics);
		drawFishingProgress(graphics);
		drawWaterVerdict(graphics);
		drawStatus(graphics);
		drawTooltips(graphics, mouseX, mouseY);
	}

	/** 电量条 + 条内数值。 */
	private void drawEnergy(GuiGraphicsExtractor graphics) {
		long stored = menu.getStored();
		long capacity = menu.getCapacity();
		double ratio = capacity > 0L ? (double) stored / capacity : 0.0D;

		drawBar(graphics,
				AutoFishingMachineMenu.ENERGY_BAR_X,
				AutoFishingMachineMenu.ENERGY_BAR_Y,
				AutoFishingMachineMenu.ENERGY_BAR_WIDTH,
				AutoFishingMachineMenu.ENERGY_BAR_HEIGHT,
				ratio,
				ENERGY_COLOR);

		// 数值画在条内居中：条宽 82px，只放得下「10.0K / 10.0K」这种短格式。
		// 完整数值、单杆耗电、还够钓几杆都在悬停提示里（MachineTooltips#energy）。
		drawCenteredText(graphics,
				Component.translatable("gui.cyxautofishingmachine.energy_amount",
						shortNumber(stored), shortNumber(capacity)),
				AutoFishingMachineMenu.ENERGY_CENTER_X,
				AutoFishingMachineMenu.ENERGY_BAR_Y + 1,
				BAR_TEXT_COLOR);
	}

	/**
	 * 钓鱼进度条。
	 *
	 * <p>停机时不画填充（比例 0）：那一条空槽本身就是「没有在钓鱼」的视觉信号，
	 * 比任何文字都先被看到。
	 */
	private void drawFishingProgress(GuiGraphicsExtractor graphics) {
		double ratio = menu.getPhase() == FishingPhase.IDLE ? 0.0D : menu.getPhaseProgress();
		drawBar(graphics,
				AutoFishingMachineMenu.PROGRESS_BAR_X,
				AutoFishingMachineMenu.PROGRESS_BAR_Y,
				AutoFishingMachineMenu.PROGRESS_BAR_WIDTH,
				AutoFishingMachineMenu.PROGRESS_BAR_HEIGHT,
				ratio,
				PROGRESS_COLOR);
	}

	/**
	 * 开放水域指示器：钓竿槽右侧的两行常驻读数（表头 + 结论）。
	 *
	 * <h2>为什么它必须常驻，而不是藏在提示里</h2>
	 * 这是<b>选址结论</b>：机器摆在岸边还是水面中间，决定了这台机器能不能出宝藏。
	 * 玩家把机器放下、插上钓竿之后，第一件想确认的事就是「我摆对地方了吗」——
	 * 要他先悬停才知道答案，等于把最重要的一条信息设成了需要猜的。
	 * 所以它占一块常驻位置，悬停只是补上「为什么」和「怎么办」。
	 *
	 * <p>表头用浅灰、结论按好坏上色：目光扫过时先看到颜色，
	 * 需要确认时再看文字 —— 两行的分工就是这个。
	 */
	private void drawWaterVerdict(GuiGraphicsExtractor graphics) {
		drawText(graphics, waterLabel(),
				AutoFishingMachineMenu.WATER_X, AutoFishingMachineMenu.WATER_Y, LABEL_COLOR);
		drawText(graphics, waterValue(),
				AutoFishingMachineMenu.WATER_X, AutoFishingMachineMenu.WATER_VALUE_Y, waterColor());
	}

	/** 状态一句话。优先级由 {@link MachineTooltips#statusLine} 决定。 */
	private void drawStatus(GuiGraphicsExtractor graphics) {
		MachineTooltips.StatusLine line = MachineTooltips.statusLine(menu);
		drawText(graphics, line.text(),
				AutoFishingMachineMenu.STATUS_X, AutoFishingMachineMenu.STATUS_Y,
				severityColor(line.severity()));
	}

	// ------------------------------------------------------------ 悬停提示
	//
	// 四个悬停区互不重叠，命中就返回（第一个命中的生效）。
	//
	// 坐标换算：extractLabels 是在「已平移到面板原点」的坐标系里画的，
	// 所以画的时候直接用面板相对坐标；而传进来的鼠标坐标是屏幕绝对坐标，
	// 判定时要先减掉面板原点。
	//
	// 传给 setTooltipForNextFrame 的必须是<b>原始的绝对坐标</b>：
	// 它只是登记一个「本帧稍后要画」的委托，真正绘制发生在所有内容层画完之后，
	// 那时坐标系已经还原了。

	private void drawTooltips(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		int x = mouseX - leftPos;
		int y = mouseY - topPos;

		if (isInside(x, y,
				AutoFishingMachineMenu.ENERGY_BAR_X, AutoFishingMachineMenu.ENERGY_BAR_Y,
				AutoFishingMachineMenu.ENERGY_BAR_WIDTH, AutoFishingMachineMenu.ENERGY_BAR_HEIGHT)) {
			showTooltip(graphics, MachineTooltips.energy(menu), mouseX, mouseY);
			return;
		}

		if (isInside(x, y,
				AutoFishingMachineMenu.PROGRESS_BAR_X, AutoFishingMachineMenu.PROGRESS_BAR_Y - 1,
				AutoFishingMachineMenu.PROGRESS_BAR_WIDTH, AutoFishingMachineMenu.PROGRESS_BAR_HEIGHT + 2)) {
			showTooltip(graphics, MachineTooltips.progress(menu), mouseX, mouseY);
			return;
		}

		// 文字型悬停区的宽度按实际文本量出来：中英长度差很多，
		// 写死一个宽度必然有一侧要么点不中、要么点到旁边的空白也弹。
		// 指示器是两行，命中区取两行里更宽的那一行。
		int waterWidth = Math.max(font.width(waterLabel()), font.width(waterValue()));
		if (isInside(x, y,
				AutoFishingMachineMenu.WATER_X, AutoFishingMachineMenu.WATER_Y - 1,
				waterWidth, AutoFishingMachineMenu.WATER_VALUE_Y - AutoFishingMachineMenu.WATER_Y + 9)) {
			showTooltip(graphics, MachineTooltips.water(menu), mouseX, mouseY);
			return;
		}

		Component statusText = MachineTooltips.statusLine(menu).text();
		if (isInside(x, y,
				AutoFishingMachineMenu.STATUS_X, AutoFishingMachineMenu.STATUS_Y - 1,
				font.width(statusText), TEXT_HIT_HEIGHT)) {
			showTooltip(graphics, MachineTooltips.status(menu), mouseX, mouseY);
		}
	}

	private void showTooltip(GuiGraphicsExtractor graphics, List<Component> lines, int mouseX, int mouseY) {
		// 图像槽位传 empty：本模组的提示全是文字，没有物品图标。
		graphics.setTooltipForNextFrame(font, lines, Optional.empty(), mouseX, mouseY);
	}

	private static boolean isInside(int x, int y, int areaX, int areaY, int width, int height) {
		return x >= areaX && x < areaX + width && y >= areaY && y < areaY + height;
	}

	// ------------------------------------------------------------ 小工具

	private Component waterLabel() {
		return Component.translatable(WaterVerdict.LABEL_KEY);
	}

	private Component waterValue() {
		return Component.translatable(menu.getWaterVerdict().valueKey());
	}

	private int waterColor() {
		return switch (menu.getWaterVerdict()) {
			case OPEN -> OK_COLOR;
			case CLOSED -> CAUTION_COLOR;
			case UNKNOWN -> LABEL_COLOR;
		};
	}

	private static int severityColor(MachineTooltips.Severity severity) {
		return switch (severity) {
			case NORMAL -> LABEL_COLOR;
			case WARNING -> WARN_COLOR;
			case CAUTION -> CAUTION_COLOR;
		};
	}

	/**
	 * 把电量压成短格式。
	 *
	 * <p>界面空间只够十几个字符，而 10000 写成全量就要占掉一半。
	 * 用 {@code Locale.ROOT} 是为了避开某些地区的十进制逗号 —— 那会让文本变长并撑破布局。
	 */
	private static String shortNumber(long value) {
		if (value >= 1_000_000L) {
			return String.format(Locale.ROOT, "%.1fM", value / 1_000_000.0D);
		}
		if (value >= 1_000L) {
			return String.format(Locale.ROOT, "%.1fK", value / 1_000.0D);
		}
		return Long.toString(value);
	}
}
