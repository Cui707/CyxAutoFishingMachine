package com.cyx.cyxautofishingmachine.fishing;

import java.util.Locale;

/**
 * 钓鱼周期的三个阶段。
 *
 * <h2>为什么是三个阶段而不是一个"正在钓鱼"的布尔</h2>
 * 这三个阶段直接对应原版 {@code FishingHook#catchingFish} 里的两个计时器，
 * 而且<b>界面上要显示的东西完全不同</b>：
 *
 * <table border="1">
 *   <caption>阶段与原版字段的对应</caption>
 *   <tr><th>阶段</th><th>原版对应</th><th>界面该说的事</th></tr>
 *   <tr><td>{@link #IDLE}</td><td>没有浮标在水里</td>
 *       <td>为什么没在钓（{@link PauseReason}）</td></tr>
 *   <tr><td>{@link #WAITING}</td><td>{@code timeUntilLured &gt; 0}</td>
 *       <td>「等鱼上钩」+ 进度条</td></tr>
 *   <tr><td>{@link #APPROACHING}</td><td>{@code timeUntilHooked &gt; 0}</td>
 *       <td>「鱼正在靠近」+ 进度条，归零那一刻收杆</td></tr>
 * </table>
 *
 * <h2>APPROACHING 这个名字是在说什么（曾经叫 HOOKED，是个误导）</h2>
 * 这个阶段覆盖的是 {@code timeUntilHooked} 的倒计时，
 * 也就是<b>鱼已经朝浮标游过来、但还没有咬</b>的那 15~25 刻（原版是 20~80 刻）。
 * 咬钩（水花音效 + {@code DATA_BITING = true}）恰恰发生在它归零那一刻。
 *
 * <p>原版在这个阶段的粒子能看出方向：{@code fishX = getX() + sin(fishAngle) * timeUntilHooked * 0.1F} ——
 * 鱼的位置随 {@code timeUntilHooked} 减小而收敛到浮标上。<b>所以它是「靠近」，不是「咬钩」。</b>
 *
 * <p>原先叫 {@code HOOKED}、界面写「鱼咬钩了」，都依据一个从未实现的设想：
 * 以为这个阶段对应咬钩之后的 {@code nibble} 窗口（20~40 刻，等玩家右键的那个），
 * 机器会「在窗口第一刻收杆」因而只持续一刻。
 * 实际上机器是在 {@code timeUntilHooked} 归零时才收杆，这个阶段结结实实要走满整个倒计时。
 * 名字与文案都改成了描述真正在跑的那个计时器 —— 否则玩家会把这十几刻
 * 误读成「机器在咬钩之后还磨蹭了一下」，而这段时间本来就在等待之内。
 *
 * <h2>机器的时间账</h2>
 * 一次收杆的耗时 = {@code timeUntilLured} + {@code timeUntilHooked}，
 * 两者都按原版「下雨加速 / 无天空减速」的规则逐刻递减：
 *
 * <table border="1">
 *   <caption>两个区间的当前取值与原版对照</caption>
 *   <tr><th>阶段</th><th>本模组</th><th>原版</th></tr>
 *   <tr><td>{@link #WAITING}</td><td>30~50 刻（1.5~2.5 秒）</td>
 *       <td>100~600 刻（5~30 秒），再减饵钓等级 × 100 刻</td></tr>
 *   <tr><td>{@link #APPROACHING}</td><td>15~25 刻（0.75~1.25 秒）</td>
 *       <td>20~80 刻（1~4 秒）</td></tr>
 * </table>
 *
 * 即<b>一杆约 2.25~3.75 秒、均值 3 秒</b>，比手动抛竿（均值约 20 秒）快一个数量级。
 * <b>这是主动调参的结果，不是原版行为</b> —— 区间上下限都在
 * {@link com.cyx.cyxautofishingmachine.config.MachineConfig} 的「钓鱼节奏」一节里，
 * 那里同时留着原版数值与调参理由。
 *
 * <p>另有一处「机器天生比人快」与区间取值无关：原版在咬钩那一刻紧接着
 * {@code nibble = Mth.nextInt(random, 20, 40)}，把 20~40 刻留给玩家右键，
 * 没在窗口内收杆鱼就跑了、一切重来。机器不需要反应时间 ——
 * <b>计时器归零的同一 tick 就收杆，这个窗口一个 tick 都不用</b>。
 * 再加上机器收杆后立刻开下一周期（人还要重新抛竿），机器恒快于任何人类玩家。
 *
 * <p>真正卡住「一杆更短」的不是这两个区间，而是电：每杆预扣
 * {@code ENERGY_PER_CATCH = 500 FE}，而进电只有 {@code MAX_INPUT = 32 FE/t}（CCG LOW 档），
 * 稳态最快约 {@code 500 / 32 ≈ 15.6} 刻一杆（约 0.78 秒）。把区间压到比这更短，
 * 缓冲区（10,000 FE，约 20 杆）耗尽后就会退化成「攒够 500 FE 才开下一杆」，
 * 界面上的等待倒计时也就不再反映真实节奏了。
 *
 * <p>id 同样是显式的，理由见 {@link PauseReason}。
 */
public enum FishingPhase {

	/** 没有在钓鱼。此时 {@link PauseReason} 说明原因。 */
	IDLE(0),

	/** 已经下钩，等鱼游过来。对应原版 {@code timeUntilLured}。 */
	WAITING(1),

	/**
	 * 鱼正在朝浮标靠近，还差若干刻咬钩。对应原版 {@code timeUntilHooked}。
	 *
	 * <p>这个阶段归零的那一 tick，机器立刻收杆 —— 不再走原版留给玩家的
	 * {@code nibble} 反应窗口。见类文档的「机器的时间账」。
	 */
	APPROACHING(2);

	private final int id;

	FishingPhase(int id) {
		this.id = id;
	}

	public int id() {
		return id;
	}

	/** 界面文本的翻译键。 */
	public String translationKey() {
		return "gui.cyxautofishingmachine.phase." + name().toLowerCase(Locale.ROOT);
	}

	/** 按 id 反查；未知 id 退回 {@link #IDLE}（界面最多显示一句「未运行」，不会崩）。 */
	public static FishingPhase byId(int id) {
		for (FishingPhase phase : values()) {
			if (phase.id == id) {
				return phase;
			}
		}
		return IDLE;
	}
}
