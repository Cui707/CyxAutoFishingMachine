package com.cyx.cyxautofishingmachine.fishing;

import java.util.Locale;

/**
 * 下钩点的水域结论：这片水能不能钓到宝藏。
 *
 * <h2>为什么要有这个枚举，而不是直接用 {@code int}</h2>
 * 这个值经 {@code ContainerData} 发到客户端，本来只是一个 {@code 0/1/2}。
 * 但界面要为它做三件事：显示一个常驻短标签、给出一段解释、决定用什么颜色。
 * 三件事都靠「这个数字是什么意思」—— 把解释权散落在界面类里，
 * 就会出现「标签说开放、提示说封闭」这种自相矛盾的呈现。
 *
 * <p>更实际的一层原因：<b>它同时是「开放水域判定」这条链路的可测出口</b>。
 * 判定本身走原版 {@code calculateOpenWater}，本模组只负责把结论搬运到界面，
 * 所以「搬运有没有搬对」就是这里唯一能出错的地方，也是一个纯函数能测的地方。
 *
 * <h2>id 与原版判定结果的关系</h2>
 * 取值与 {@code AutoFishingMachineBlockEntity} 里原来的
 * {@code OPEN_WATER_UNKNOWN / CLOSED / OPEN} 三个常量<b>逐一对齐、保持不变</b>。
 * 这次只是把定义搬到了这里（方块实体改为引用本枚举），
 * 线上的 0/1/2 没有动 —— 老存档、老客户端读到的含义与之前完全一致。
 *
 * <p>顺序也刻意保持 {@code UNKNOWN, CLOSED, OPEN}：与数值同序，
 * 读代码时不必在两套顺序之间来回换算。
 */
public enum WaterVerdict {

	/**
	 * 还没判定过 —— 没有可下钩的水，或者机器还没跑起来。
	 *
	 * <p>界面必须把它与 {@link #CLOSED} 分开：一个是「不知道」，
	 * 一个是「知道，且不行」。混在一起玩家会把「没找到水」当成「水不对」。
	 */
	UNKNOWN(0),

	/** 判定为非开放水域：能钓鱼，但<b>钓不到宝藏</b>（原版战利品表的门槛）。 */
	CLOSED(1),

	/** 判定为开放水域：可以钓到宝藏。 */
	OPEN(2);

	/** 线上表示。写入 {@code ContainerData} 的就是它；取值不可改，见类文档。 */
	private final int state;

	WaterVerdict(int state) {
		this.state = state;
	}

	/**
	 * 指示器表头的翻译键 —— 说明下面那个值说的是「什么」。
	 *
	 * <p>界面上的水域指示器是<b>两行</b>：这一行是固定的表头，下一行是结论。
	 * 之所以不写成「水域 开放」这样的一行，是因为一行要在 64 像素内同时放下
	 * 表头和结论，英文的 {@code Water: closed} 已经到了 66 像素 ——
	 * 任何一次措辞调整都会溢出到缓存槽上。拆成两行之后，
	 * 最长的取值（{@code Unknown}）也只有 44 像素，余量翻了一倍多。
	 */
	public static final String LABEL_KEY = "gui.cyxautofishingmachine.water.label";

	public int state() {
		return state;
	}

	/** 宝藏是否可能出现。判定本身由原版负责，这里只是把结论翻译成一个布尔。 */
	public boolean treasurePossible() {
		return this == OPEN;
	}

	/** 指示器第二行（结论值）的翻译键。命名与 {@code lang} 文件里的键一一对应。 */
	public String valueKey() {
		return "gui.cyxautofishingmachine.water." + name().toLowerCase(Locale.ROOT);
	}

	/**
	 * 悬停解释的翻译键。
	 *
	 * <p>与 {@link #valueKey()} 分开，是因为两者篇幅差很多：
	 * 指示器上要在一行的宽度里放下，提示可以写一整句「为什么」和「怎么办」。
	 */
	public String tooltipKey() {
		return "gui.cyxautofishingmachine.tooltip.water_" + name().toLowerCase(Locale.ROOT);
	}

	/**
	 * 按线上取值反查。
	 *
	 * <p>未知取值退回 {@link #UNKNOWN} 而不是抛异常：这个值要经网络到达客户端，
	 * 客户端版本比服务端旧时读到没见过的数字是有可能的。
	 * 退回「未知」会显示成灰色的「尚未确定」，不会误导玩家去改水域。
	 */
	public static WaterVerdict fromState(int state) {
		for (WaterVerdict verdict : values()) {
			if (verdict.state == state) {
				return verdict;
			}
		}
		return UNKNOWN;
	}
}
