package com.cyx.cyxautofishingmachine.menu;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.cyx.cyxautofishingmachine.config.MachineConfig;
import com.cyx.cyxautofishingmachine.fishing.FishingPhase;
import com.cyx.cyxautofishingmachine.fishing.PauseReason;
import com.cyx.cyxautofishingmachine.fishing.WaterVerdict;

import net.minecraft.network.chat.Component;

/**
 * 界面工具提示的<b>内容</b>。
 *
 * <h2>为什么不写在界面类里</h2>
 * 界面上能放下的字太少（面板底部只有 86 像素），所以「精确数值」「为什么」
 * 「该怎么办」这些放不下的信息全部推到悬停提示里。这些提示是这个界面里
 * <b>唯一会告诉玩家具体数字和下一步动作</b>的地方 —— 也就是说，
 * 它是信息量最大、也最容易写错（指错一格、算错一个数、挂错一个原因）的一层。
 *
 * <p>把它放在菜单包、只依赖 {@link Component} 与菜单的语义 getter，
 * 好处是：<b>服务端 GameTest 能直接断言它</b>。
 * {@code ContainerData} 是服务端与客户端共用的一条通道，
 * 服务端侧拿到的提示内容与客户端看到的是同一份 ——
 * 于是「悬停电量条显示的是不是真实电量」「缓存满了给的建议是不是去放箱子」
 * 这类问题不需要开客户端就能验证。渲染位置（画在哪儿、什么时候弹）
 * 才是客户端测试与截图的事（见 {@code docs/TESTING.md}）。
 *
 * <h2>翻译键为什么写成常量</h2>
 * 键名在这里集中出现两次以上（状态行与提示行共用同一条判断），
 * 写字面量就会有不一致的机会。集中成常量还有个额外好处：
 * {@code tools/check_localization.py} 能直接在源码里扫到这些字面量，
 * 断言它们在中英两个语言文件里都存在 —— 键名打错会当场失败，
 * 而不是等到玩家看到一片 {@code gui.cyxautofishingmachine.tooltip.xxx}。
 */
public final class MachineTooltips {

	private MachineTooltips() {
	}

	// ------------------------------------------------------------ 翻译键

	/** 电量条的完整数值。 */
	private static final String KEY_ENERGY = "gui.cyxautofishingmachine.tooltip.energy";

	/** 每一杆的耗电。 */
	private static final String KEY_ENERGY_PER_CATCH = "gui.cyxautofishingmachine.tooltip.energy_per_catch";

	/** 按当前电量还够钓几杆。 */
	private static final String KEY_CATCHES_LEFT = "gui.cyxautofishingmachine.tooltip.catches_left";

	/** 当前阶段还剩多少时间。 */
	private static final String KEY_PROGRESS = "gui.cyxautofishingmachine.tooltip.progress";

	/** 封闭水域时补一句「怎么办」。 */
	private static final String KEY_WATER_CLOSED_HINT = "gui.cyxautofishingmachine.tooltip.water_closed_hint";

	/** 运行中但电网被切断。与界面状态行共用，所以放在这里而不是界面类里。 */
	private static final String KEY_RUNNING_UNPOWERED = "gui.cyxautofishingmachine.status.running_unpowered";

	// ------------------------------------------------------------ 状态行的严重级

	/**
	 * 状态行需要引起多大注意。界面按它选颜色。
	 *
	 * <p>把「严重级」和「颜色」分开：这里回答「这件事有多要紧」，
	 * 界面回答「要紧的事画成什么颜色」。三档的语义见
	 * {@code AutoFishingMachineScreen} 的类文档。
	 */
	public enum Severity {
		/** 正常。 */
		NORMAL,
		/** 需要玩家处理：机器已经停了。 */
		WARNING,
		/** 需要注意但没停机。 */
		CAUTION,
	}

	/** 状态行：要画在面板上的那一句，以及它有多要紧。 */
	public record StatusLine(Component text, Severity severity) {
	}

	// ------------------------------------------------------------ 状态行的唯一判断

	/**
	 * 状态行此刻属于哪一种情况。
	 *
	 * <p>抽出来是为了让「画什么」和「悬停时说什么」不可能走岔：
	 * 两者都从这一个判断出发，优先级只写了一份。
	 */
	private enum Situation {
		/** 停机：说暂停原因。 */
		PAUSED,
		/** 运行中但电网断开：说这件事（比「正在钓鱼」要紧）。 */
		RUNNING_UNPOWERED,
		/** 一切正常：说当前阶段。 */
		RUNNING,
	}

	private static Situation situationOf(AutoFishingMachineMenu menu) {
		if (menu.getPhase() == FishingPhase.IDLE) {
			return Situation.PAUSED;
		}
		return menu.isGridLinked() ? Situation.RUNNING : Situation.RUNNING_UNPOWERED;
	}

	// ------------------------------------------------------------ 状态行

	/**
	 * 面板底部那一行状态文字。
	 *
	 * <p>界面把它画出来，颜色由 {@link StatusLine#severity()} 决定。
	 */
	public static StatusLine statusLine(AutoFishingMachineMenu menu) {
		return switch (situationOf(menu)) {
			case PAUSED -> {
				PauseReason reason = menu.getPauseReason();
				yield new StatusLine(Component.translatable(reason.translationKey()),
						reason.isWarning() ? Severity.WARNING : Severity.NORMAL);
			}
			case RUNNING_UNPOWERED -> new StatusLine(
					Component.translatable(KEY_RUNNING_UNPOWERED), Severity.CAUTION);
			case RUNNING -> new StatusLine(
					Component.translatable(menu.getPhase().translationKey()), Severity.NORMAL);
		};
	}

	/**
	 * 悬停状态文字时的提示：第一行是「是什么」，第二行是「该怎么办」。
	 *
	 * <p>第一行与面板上画出来的那一句<b>刻意相同</b>：悬停提示要能自解释，
	 * 玩家不必把视线在提示和面板之间来回对齐才知道自己在看哪一件事。
	 */
	public static List<Component> status(AutoFishingMachineMenu menu) {
		return switch (situationOf(menu)) {
			case PAUSED -> {
				PauseReason reason = menu.getPauseReason();
				yield List.of(
						Component.translatable(reason.translationKey()),
						Component.translatable(reason.hintKey()));
			}
			case RUNNING_UNPOWERED -> List.of(
					Component.translatable(KEY_RUNNING_UNPOWERED),
					// 这一档对应的处理动作与「停机原因是电网断开」完全相同，
					// 直接复用同一句建议，不另写一条措辞略有差别的文本。
					Component.translatable(PauseReason.GRID_DISCONNECTED.hintKey()));
			case RUNNING -> List.of(
					Component.translatable(menu.getPhase().translationKey()),
					progressLine(menu.getRemainingTicks()));
		};
	}

	// ------------------------------------------------------------ 电量

	/**
	 * 悬停电量条的提示：精确数值 + 单杆耗电 + 还够钓几杆。
	 *
	 * <h2>为什么要给「还够钓几杆」</h2>
	 * 条内只能写压缩过的 {@code 4.3K / 10.0K}（见 {@code shortNumber}）。
	 * 玩家真正要判断的是「这张电网供得上吗」—— 而 5000 FE 是多是少，
	 * 只有除以单杆耗电才变成一个能直接行动的答案。
	 */
	public static List<Component> energy(AutoFishingMachineMenu menu) {
		long stored = menu.getStored();
		long capacity = menu.getCapacity();
		long perCatch = MachineConfig.ENERGY_PER_CATCH;

		List<Component> lines = new ArrayList<>(3);
		lines.add(Component.translatable(KEY_ENERGY, stored, capacity));
		lines.add(Component.translatable(KEY_ENERGY_PER_CATCH, perCatch));

		// 除数守卫：ENERGY_PER_CATCH 是配置常量，改成 0 会让这里抛 ArithmeticException ——
		// 而这段代码跑在渲染路径上，抛出去的后果是「打开界面就崩」，
		// 比显示一个不对的数字严重得多。与 AutoFishingMachineMenu#getPhaseProgress
		// 对分母 0 的处理是同一个理由。
		long catchesLeft = perCatch > 0L ? stored / perCatch : 0L;
		lines.add(Component.translatable(KEY_CATCHES_LEFT, catchesLeft));
		return lines;
	}

	// ------------------------------------------------------------ 进度

	/**
	 * 悬停钓鱼进度条的提示：当前阶段 + 还剩多少时间。
	 *
	 * <p>刻意<b>不</b>重复水域结论 —— 那条信息常驻在指示器上，
	 * 在这里再写一遍只会让两条通道对同一件事各说一次，多一个说不一致的机会。
	 */
	public static List<Component> progress(AutoFishingMachineMenu menu) {
		return List.of(
				Component.translatable(menu.getPhase().translationKey()),
				progressLine(menu.getRemainingTicks()));
	}

	/**
	 * 「还剩多久」。
	 *
	 * <p>两种单位都给：<b>秒</b>是玩家能感知的量，<b>刻</b>是状态机内部真正在扣的计数器
	 * （存档与测试断言用的都是它，比「1.8 秒」这种约数精确）。只给一个都会有人算错。
	 *
	 * <p>{@code Locale.ROOT} 是必须的：某些地区的十进制是逗号，
	 * 那会让「12.4」变成「12,4」，读起来像两个数字。
	 */
	private static Component progressLine(int remainingTicks) {
		String seconds = String.format(Locale.ROOT, "%.1f", remainingTicks / 20.0D);
		return Component.translatable(KEY_PROGRESS, seconds, remainingTicks);
	}

	// ------------------------------------------------------------ 水域结论

	/**
	 * 悬停开放水域指示器的提示。
	 *
	 * <p>「封闭水域」多给一行「把机器移向水面中间」：这个结论不是让玩家知道就完了，
	 * 而是一个可操作的建议。判定门槛（浮标周围 ±2 格、上下 4 层）在本模组里
	 * 没有第二处解释，所以这一行是玩家唯一的线索来源。
	 */
	public static List<Component> water(AutoFishingMachineMenu menu) {
		WaterVerdict verdict = menu.getWaterVerdict();
		if (verdict == WaterVerdict.CLOSED) {
			return List.of(
					Component.translatable(verdict.tooltipKey()),
					Component.translatable(KEY_WATER_CLOSED_HINT));
		}
		return List.of(Component.translatable(verdict.tooltipKey()));
	}
}
