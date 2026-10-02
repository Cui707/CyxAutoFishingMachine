package com.cyx.cyxautofishingmachine.fishing;

/**
 * 机器「为什么没在钓鱼」的唯一答案。
 *
 * <h2>为什么是一个枚举而不是一个布尔</h2>
 * 需求里写得很明确：机器停下来时必须能说清原因。用一个 {@code boolean paused}
 * 加一句「暂停中」的界面文本，等于把排查工作全推给玩家 ——
 * 「没钓竿」和「电不够」需要玩家做完全相反的两件事。
 * 所以每一种停止原因各占一个枚举值，界面按值取翻译键。
 *
 * <h2>为什么带显式 id 而不是用 ordinal</h2>
 * 这个值要经 {@code ContainerData} 发到客户端。用 {@code ordinal()}
 * 意味着「以后在中间插入一个枚举值」就会静默地把老存档、老包全部错位 ——
 * 而且是那种不报错、只是显示错原因的错位。显式 id 让插值变成无害操作。
 *
 * <h2>语义边界</h2>
 * 本枚举只回答「能不能<b>开一个新周期</b>」。已经开始的周期不再检查电网与电量
 * （电是预扣的，理由见 {@code MachineConfig#ENERGY_PER_CATCH}），
 * 中途只可能因为「钓竿被拿走」「水域消失」「缓存满了」而中止。
 * 中止同样会落到本枚举上，界面看到的仍然是同一套原因文本。
 *
 * <h2>取值与 id 的关系</h2>
 * id 一旦发出去就<b>只增不改</b>：客户端读到的 id 与枚举值的对应关系
 * 是靠这里的常量钉死的，中间插一个值会让老存档里的 id 指向新的含义。
 * 新增的 {@link #OUTPUT_FULL} 因此排在末尾而不是插在 {@link #CACHE_FULL} 后面。
 */
public enum PauseReason {

	/** 一切正常，机器正在钓鱼。 */
	NONE(0, false),

	/** 钓竿槽是空的（或者放的东西不是钓竿）。 */
	NO_ROD(1, true),

	/** 机器周围没有可下钩的水。 */
	NO_WATER(2, true),

	/**
	 * 缓存 9 格全满，四周没有能把产物接走的容器 —— 停机等玩家放一个箱子 / 漏斗。
	 *
	 * <p>另有一种过渡态也归到这里：容器其实还有空位，只是还没轮到下一次输出节拍
	 * （≤ 8 刻）。它连一次界面刷新都撑不过去，不值得单独占一个原因。
	 * 「容器存在但已经装满了」才是真正的另一种情况，见 {@link #OUTPUT_FULL}。
	 */
	CACHE_FULL(3, true),

	/** 没有接在电网 / 电源上。 */
	GRID_DISCONNECTED(4, true),

	/** 接上了电网，但缓冲里的电不够预扣一次。 */
	NOT_ENOUGH_POWER(5, true),

	/**
	 * 缓存满了，而且相邻容器也接不下（全满了）。
	 *
	 * <h2>为什么要与 {@link #CACHE_FULL} 分开</h2>
	 * 两者在界面上都是一句「缓存满了」，但玩家要做的动作完全相反：
	 * 一个是「去放一个箱子」，一个是「去把箱子清空」。合成一条只会让玩家
	 * 围着机器转圈找不出问题 —— 与 {@link #GRID_DISCONNECTED} /
	 * {@link #NOT_ENOUGH_POWER} 分开的理由是同一个。
	 */
	OUTPUT_FULL(6, true);

	/**
	 * 线上表示。写入 {@code ContainerData} 的就是它。
	 *
	 * <p>取值范围 0~32767：{@code ContainerData} 每格在网络上是按无符号 16 位传的，
	 * 超出会被截断。枚举值少，这里顺带把「别再加太多值」的约束写下来。
	 */
	private final int id;

	/** 是否属于「需要玩家处理」的告警态；{@link #NONE} 是唯一为 false 的值。 */
	private final boolean warning;

	PauseReason(int id, boolean warning) {
		this.id = id;
		this.warning = warning;
	}

	public int id() {
		return id;
	}

	/** 界面用它决定文字颜色：告警态用红色，正常态用普通标签色。 */
	public boolean isWarning() {
		return warning;
	}

	/** 界面文本的翻译键。命名与 {@code lang} 文件里的键一一对应。 */
	public String translationKey() {
		return "gui.cyxautofishingmachine.status." + name().toLowerCase(java.util.Locale.ROOT);
	}

	/**
	 * 「该怎么办」的翻译键 —— 悬停状态文字时给出的下一步动作。
	 *
	 * <h2>为什么要与 {@link #translationKey()} 分开</h2>
	 * 面板底部那一行只有 86 像素可用，只能放一句<b>是什么</b>（见
	 * {@code AutoFishingMachineScreen} 的类文档）。而玩家真正需要的是
	 * <b>做什么</b>：「缓存已满」不告诉他去放箱子还是去清箱子，
	 * 就得围着机器转圈猜。工具提示没有宽度限制，正好放这一句。
	 *
	 * <p>两者放在同一个枚举里，是为了让「原因 → 建议」的对应关系
	 * 只可能有一份：新增一个停机原因时，{@link #translationKey()} 与
	 * {@link #hintKey()} 会一起出现，本地化校验脚本也会一起检查。
	 *
	 * @see #translationKey()
	 */
	public String hintKey() {
		return "gui.cyxautofishingmachine.hint." + name().toLowerCase(java.util.Locale.ROOT);
	}

	/** 按 id 反查。越界或未知 id 一律退回 {@link #NO_WATER} 之外的「安全默认」。 */
	public static PauseReason byId(int id) {
		for (PauseReason reason : values()) {
			if (reason.id == id) {
				return reason;
			}
		}
		// 走到这里只可能是客户端拿到了比它更新的服务端发来的 id。
		// 不抛异常：为了一次界面文本把整个连接炸掉不值得。
		// 选 GRID_DISCONNECTED 是因为它在中英文本里都是「去检查供电」这类中性提示，
		// 不会像 NO_ROD 那样误导玩家去翻钓竿槽。
		return GRID_DISCONNECTED;
	}
}
