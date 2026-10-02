package com.cyx.cyxautofishingmachine.config;

import com.cyx.crimsoncoppergrid.common.powerSystem.CcgEnergyTier;

/**
 * 自动钓鱼机的全部可调参数，集中放一处。
 *
 * <h2>为什么要有这个类</h2>
 * 容量、能耗、等待时间、槽位编号这些东西会被方块实体、状态机、菜单、屏幕同时读到。
 * 散落到各个类里之后，「把每次能耗从 500 改成 800」就变成了一次跨文件的搜索，
 * 一定会漏。所以这里全部收成常量，并且每个常量都写清楚它的来源与含义。
 *
 * <h2>与上游电力体系的关系</h2>
 * 输入速率不自己定数字，而是直接取 {@link CcgEnergyTier} 的档位值 ——
 * 上游的 {@code PowerAcceptorBlockEntity#checkTier()} 会按
 * {@code getBaseMaxInput()} 反查档位用于界面显示，取档位里的数字能让
 * 「实际速率」和「显示的档位」天然一致，不会出现「标称 LOW 档、实际跑 64 FE/t」这种对不上的情况。
 */
public final class MachineConfig {

	private MachineConfig() {
	}

	// ------------------------------------------------------------ 电力
	// 单位统一为 FE（Forge Energy），也就是 Team Reborn Energy 的能量语义。

	/** 内部能量缓冲容量。 */
	public static final long ENERGY_CAPACITY = 10_000L;

	/**
	 * 每次成功钓鱼的能耗。
	 *
	 * <p>采用<b>预扣</b>：进入等待阶段时一次性扣掉，等待期间不再扣。
	 * 这样「已经开始的周期」即使中途电网断开也能走完，不会出现
	 * 「钓到一半电没了、这次白等」这种反直觉的结果。
	 */
	public static final long ENERGY_PER_CATCH = 500L;

	/** 单 tick 最大进电速率。取上游 LOW 档（= 32 FE/t），与电力熔炉同量级。 */
	public static final long MAX_INPUT = CcgEnergyTier.LOW.getMaxInput();

	/** 单 tick 最大出电速率。本机是纯用电设备，恒为 0 —— 推流模型下它这一侧自然是空操作。 */
	public static final long MAX_OUTPUT = 0L;

	/**
	 * 电网连接检测的重检间隔（刻）。
	 *
	 * <p>检测本身要查六个方向的邻居方块实体（可能还要过一次能力查阅表），
	 * 每 tick 做一次纯属浪费 —— 电线的接与断都是玩家动作，秒级延迟完全够用。
	 *
	 * <p>20 刻 = 1 秒。原先还用「钓鱼周期本身最短也有 100 刻，所以电网被剪断总能赶在
	 * 下一次扣电之前被察觉」来支持这个取值 —— 节奏调快之后，那条理由<b>只剩一半成立</b>：
	 * 常规一杆是 46~76 刻，远大于 20 刻的重检窗口；只有把饵钓开满、一杆压到十余刻时，
	 * 重检窗口才可能跨过一整次扣电。后果仅限于「剪线后最多多跑一杆」，
	 * 而那一杆本来就是预扣电换来的产物，不是白扣，所以没有跟着调小。
	 */
	public static final int GRID_RECHECK_INTERVAL = 20;

	/**
	 * 水域存在性检测的重检间隔（刻）。
	 *
	 * <p>「周围还有没有水」每 tick 查一次是十几条 {@code getBlockState}，
	 * 一台机器无所谓，但玩家摆几十台就没必要了 —— 水面不会自己跑掉，
	 * 只有玩家放 / 拆方块才会变。10 刻 = 0.5 秒的延迟对「拆了水才发现停机」来说完全够用。
	 *
	 * <p><b>注意这项节流的不是开放水域判定。</b>后者要扫 5×5×4 = 100 格，代价高一个数量级，
	 * 只在「下钩点变了」和「收杆那一刻」做，理由见 {@code HookProbe}。
	 */
	public static final int WATER_RECHECK_INTERVAL = 10;

	/**
	 * 抛竿的最远距离（格）。
	 *
	 * <p>机器沿正面方向量出连续的水面段，再把浮标扔到这段水面的中点。
	 * 这个常量是「水面段最长量到哪里」的上限。
	 *
	 * <p>为什么不是越大越好：量水面段每格都要查高度，四个方向都试的话
	 * 单次检测的方块查询量正比于这个值。16 格已经足够覆盖「湖面 / 河面 / 海面」，
	 * 再远对开放水域判定也没有额外收益 —— 那个判定只看浮标周围 ±2 格。
	 */
	public static final int MAX_CAST_DISTANCE = 16;

	/**
	 * 产物输出的节拍（刻）：每隔这么多刻，往相邻容器搬<b>一件</b>物品。
	 *
	 * <p>8 刻是原版漏斗的搬运速度（{@code HopperBlockEntity.MOVE_ITEM_SPEED}），
	 * 取同一个数字是为了「输出速度和一根漏斗一样」这句话可以直接对玩家说，
	 * 不需要另造一套时间单位。
	 *
	 * <p>够不够用？最快的钓鱼周期出现在「饵钓III + 下雨」时：等待被压到下限 1 刻，
	 * 靠近窗口取最小值 15 刻、速度 2，合计约 9 刻一杆。输出是 8 刻一件，
	 * 仍略快于产出，另外还有 9 格缓存（最多 576 件）当缓冲。
	 * 余量比调快节奏之前薄（那时约 11 刻一杆），但结论不变：瓶颈仍是钓鱼，不是输出。
	 *
	 * <p>为什么不是「一次搬一整堆」：见 {@code AdjacentInventory#insertOne} ——
	 * 整堆搬会让堆肥桶这类「每件算一次」的容器凭空吞掉多余物品。
	 */
	public static final int OUTPUT_INTERVAL_TICKS = 8;

	// ------------------------------------------------------------ 槽位布局

	/** 钓竿槽。只能放钓竿，只能放 1 个。 */
	public static final int SLOT_ROD = 0;

	/** 内部缓存的第一个槽位。 */
	public static final int SLOT_CACHE_START = 1;

	/** 内部缓存的槽位数量。钓鱼结果先进这里，再由相邻容器处理器往外搬。 */
	public static final int SLOT_CACHE_COUNT = 9;

	/** 方块实体对外暴露的槽位总数。 */
	public static final int SLOT_COUNT = SLOT_CACHE_START + SLOT_CACHE_COUNT;

	// ------------------------------------------------------------ 钓鱼节奏
	//
	// 下面两组区间<b>不是原版数值</b>，是按「一杆约 3~4 秒」主动调短的：
	//
	//              等待鱼上钩            鱼正在靠近            一杆合计
	//   原版       100~600（5~30 秒）    20~80（1~4 秒）      6~34 秒，均值 20 秒
	//   本模组      30~50 （1.5~2.5 秒）  15~25（0.75~1.25 秒）2.25~3.75 秒，均值 3 秒
	//
	// <b>机制仍然逐句照抄原版</b> {@code FishingHook#catchingFish}：同样两个计时器、
	// 每刻按 {@code fishingSpeed} 递减、归零即切阶段、收杆那一刻掷原版战利品表。
	// 改动的只有两个区间的上下限，外加「咬钩后不走 {@code nibble} 反应窗口」。
	//
	// <h2>命名约定</h2>
	// 带 {@code VANILLA_} 前缀的常量，其<b>数值</b>确实取自原版且未被改动
	// （如下面的两个速度概率，以及 HookProbe#VANILLA_TICKS_PER_LURE_LEVEL）；
	// 不带前缀的则是本项目调过的。原版数值写在各常量的 javadoc 里，
	// 要「回到原版」时可以直接对照替换。
	//
	// <h2>饵钓：按区间等比缩放，保住等级阶梯</h2>
	// 原版每级缩短 100 刻，而整段「等鱼上钩」只剩 30~50 刻 —— 照原样相减，
	// <b>任何等级都会把等待压到下限 1 刻</b>，I 级与 III 级完全没有区别。
	// 所以 {@link #LURE_TICKS_PER_LEVEL} 按区间等比缩到 8：
	// III 级共缩 24 刻，与区间宽度 20 刻同量级，于是 III 级（6~26 刻）
	// 与无附魔（30~50 刻）<b>完全不重叠</b>，等级越高越快。
	//
	// 缩放只作用于「每级刻数」这一个标量：等级仍由 HookProbe 从 EnchantmentHelper
	// 读回（换算链一步没改），只是把那个系数从原版的 100 换成 8。

	/**
	 * 「等鱼上钩」的最短等待（刻）。
	 *
	 * <p>30 刻 = 1.5 秒。原版为 100（5 秒）。
	 */
	public static final int MIN_LURE_WAIT = 30;

	/**
	 * 「等鱼上钩」的最长等待（刻）。
	 *
	 * <p>50 刻 = 2.5 秒，区间中点 40 刻 = 2 秒。原版为 600（30 秒）。
	 */
	public static final int MAX_LURE_WAIT = 50;

	/**
	 * 饵钓（Lure）每级缩短的等待刻数。
	 *
	 * <p>原版是每级 100 刻（{@code getFishingTimeReduction} 每级 5 秒，再 ×20 换成刻），
	 * 但本模组的等待区间只有 30~50 刻 —— 直接用原版值会让<b>任何等级都压到下限 1 刻</b>，
	 * 附魔等级阶梯完全失效。这里按区间等比缩到 8 刻/级：III 级共缩 24 刻，
	 * 与区间宽度 20 刻同量级，于是 III 级（6~26 刻）与无附魔（30~50 刻）完全不重叠。
	 *
	 * <p>实际运算发生在 {@code HookProbe#lureTicksScaledTo(int)}：等级由原版
	 * {@code EnchantmentHelper} 读出，本常量只决定缩放后的每级刻数，换算链本身没被改写。
	 */
	public static final int LURE_TICKS_PER_LEVEL = 8;

	/**
	 * 「鱼正在靠近」的最短等待（刻）。
	 *
	 * <p>15 刻 = 0.75 秒。原版为 20（1 秒）。
	 */
	public static final int MIN_BITE_WAIT = 15;

	/**
	 * 「鱼正在靠近」的最长等待（刻）。
	 *
	 * <p>25 刻 = 1.25 秒，区间中点 20 刻 = 1 秒。原版为 80（4 秒）。
	 */
	public static final int MAX_BITE_WAIT = 25;

	/**
	 * 等待时间被饵钓缩短后的下限。
	 *
	 * <p>原版只是用 {@code Mth.nextInt(random, 100, 600)} 取一个值再减，
	 * 减完仍可能很小；这里保底 1 刻，避免出现「等待 0 刻」导致状态机在同 tick 内空转。
	 *
	 * <p><b>这个保底只作用于「等鱼上钩」。</b>{@code timeUntilHooked} 是直接
	 * {@code Mth.nextInt(random, MIN_BITE_WAIT, MAX_BITE_WAIT)} 取值的，外面没有
	 * {@code Math.max} —— 若把 {@link #MIN_BITE_WAIT} 与 {@link #MAX_BITE_WAIT} 都设成 0，
	 * 状态机里 {@code if (timeUntilHooked > 0)} 这个守卫会直接跳过收杆分支，
	 * 机器会在「等待 ↔ 靠近」之间空转、永远钓不上任何东西且不报错。
	 * 所以这两个常量<b>必须 ≥ 1</b>。
	 */
	public static final int MIN_WAIT_TICKS = 1;

	/**
	 * 原版「正在下雨则计时加速」的每刻判定概率。
	 *
	 * <p>原版 {@code FishingHook#catchingFish} 每刻掷两次：
	 * <pre>
	 * float speed = 1;
	 * if (random.nextFloat() &lt; 0.25F &amp;&amp; level.isRainingAt(浮标上方)) speed++;
	 * if (random.nextFloat() &lt; 0.5F  &amp;&amp; !level.canSeeSky(浮标上方))  speed--;
	 * </pre>
	 * 于是每刻速度在 0~2 之间浮动：下雨时平均更快，
	 * 被方块遮挡（看不到天）时平均更慢 —— 后者就是「在屋檐下钓鱼会变慢」的实现。
	 * <b>速度可能为 0</b>，那一 tick 计时器不推进，这是原版行为，不是 bug。
	 *
	 * <p>两次 {@code nextFloat()} <b>必须都执行</b>（不能用短路求值跳过），
	 * 否则随机序列会与原版错位。
	 */
	public static final float VANILLA_RAIN_SPEED_CHANCE = 0.25F;

	/** 原版「看不到天空则计时减速」的每刻判定概率。见 {@link #VANILLA_RAIN_SPEED_CHANCE}。 */
	public static final float VANILLA_NO_SKY_SPEED_CHANCE = 0.5F;
}
