package com.cyx.cyxautofishingmachine.fishing;

import com.cyx.cyxautofishingmachine.mixin.FishingHookAccessor;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.EnchantmentHelper;

/**
 * 一个「不进入世界」的浮动标替身：把原版钓鱼的数值与判定原封不动地借过来用。
 *
 * <h2>为什么不真的生成一个 {@link FishingHook} 实体</h2>
 * 看起来最省事的做法是把浮标真的扔进水里，让原版自己跑 {@code tick}。
 * 但那条路有几个绕不过去的坑：
 * <ul>
 *   <li>{@code FishingHook#shouldStopFishing} 要求所有者是<b>真实玩家且手上拿着钓竿</b>，
 *       机器没有玩家，浮标会在第一次 tick 里自毁；</li>
 *   <li>原版 {@code retrieve} 只负责生成掉落物<b>实体</b>并让它们飞向玩家，
 *       而需求要求产物直接进内部缓存、由机器自动输出到相邻容器；</li>
 *   <li>浮标实体需要区块加载、参与实体 tick、还会被玩家看到 —— 一台机器一个浮标，
 *       规模化之后是纯粹的浪费。</li>
 * </ul>
 *
 * <h2>借用的是什么</h2>
 * 本类只借三样东西，全部来自原版 {@link FishingHook} 本体：
 *
 * <ol>
 *   <li><b>附魔参数</b> —— {@code luck}（海之眷顾）与 {@code lureSpeed}（饵钓）。
 *       原版是在构造浮标时用 {@code EnchantmentHelper} 算好、再 {@code Math.max(0, …)}
 *       钳一次。这里是读回那两个字段，而不是自己再算一遍，
 *       避免「两处算法各自漂移」；</li>
 *   <li><b>开放水域判定</b> —— {@link FishingHookAccessor#callCalculateOpenWater}，
 *       逻辑见下；</li>
 *   <li><b>战利品表的 {@code THIS_ENTITY} 参数</b> —— 原版 {@code fishing.json} 的宝藏条目
 *       挂了一条 {@code type_specific/fishing_hook.in_open_water} 谓词，
 *       它会把实体转成 {@link FishingHook} 再读 {@code isOpenWaterFishing()}。
 *       所以把本替身传进去，就等价于把「这片水算不算开放」的结论直接告诉原版战利品表。</li>
 * </ol>
 *
 * <h2>为什么「开放水域」不影响能不能钓鱼</h2>
 * 原版在任何水里都能钓上东西，不开放只是<b>钓不到宝藏</b>
 * （战利品表里 junk 权重 10、treasure 权重 5 且带谓词、fish 权重 85）。
 * 本模组严格沿用这条规则：周围没水 = 完全不能钓（暂停原因 {@code NO_WATER}）；
 * 有水但不开放 = 照钓，只是原版战利品表会自己把宝藏条目筛掉。
 *
 * <h2>为什么探测只在收杆那一刻做一次</h2>
 * 原版是每刻把 {@code openWater} 与当前判定结果<b>取与</b>（见 {@code FishingHook#tick} 的
 * {@code this.openWater = this.openWater && …}），因为它的浮标会被玩家拽着到处移动。
 * 本模组的下钩点是静止的，同一片水每刻的结论必然相同，
 * 所以取与退化成单次判定。<b>而一次判定要扫 5×5×4 = 100 格方块</b> ——
 * 每刻做这件事正是需求里明令禁止的「每 tick 大范围扫描」。
 * 两者结论一致，代价差 100 倍，所以选后者。
 *
 * <h2>关于 {@code fisher} 参数</h2>
 * {@code EnchantmentHelper#getFishingLuckBonus}/{@code getFishingTimeReduction} 的第三个参数
 * 在原版里传的是玩家。机器没有玩家，而传 {@code null} 会让附魔效果里的实体条件判定失去依据。
 * 于是这里先用一根<b>零附魔探针</b>浮标充当这个 {@code fisher}，
 * 再用算出来的参数构造真正要用的那根 —— 两次对象分配，每个周期一次，可以忽略。
 *
 * <p>本类不是线程安全的，也不该跨 tick 缓存：它绑定的是「某个时刻的某个钓竿」。
 * 使用方式是「开周期时建、收杆时用、之后丢掉」。
 */
public final class HookProbe {

	/** 原版 {@code FishingRodItem#use} 里把「秒」换成「刻」的那个系数（{@code * 20.0F}）。 */
	private static final float TICKS_PER_SECOND = 20.0F;

	/**
	 * 原版每个饵钓等级缩短的等待刻数。
	 *
	 * <p>{@code EnchantmentHelper#getFishingTimeReduction} 每级返回 5 秒，
	 * 原版乘上 {@link #TICKS_PER_SECOND} 之后就是 100 刻。这个常量是
	 * {@link #lureTicksScaledTo(int)} 的换算基准 —— 只有本类知道「原版每级是多少」，
	 * 所以基准值放在这里，别处不要重复写 100。
	 */
	public static final int VANILLA_TICKS_PER_LURE_LEVEL = 100;

	private final FishingHook hook;

	private HookProbe(FishingHook hook) {
		this.hook = hook;
	}

	/**
	 * 按钓竿当前的附魔，造一根原版参数的浮动标替身。
	 *
	 * @param level 服务端世界；替身会持有它的引用，这也是本对象不能长期保存的原因
	 * @param rod   钓竿槽里的钓竿（调用方需保证它确实是一根钓竿）
	 */
	public static HookProbe create(ServerLevel level, ItemStack rod) {
		// 零附魔探针：只是为了让原版 API 有一个非空的实体参数可传。
		// 刻意不 discard()：它从未进入过世界，标记移除反而会多走一次实体移除流程。
		FishingHook probe = new FishingHook(EntityTypes.FISHING_BOBBER, level, 0, 0);

		int luck = EnchantmentHelper.getFishingLuckBonus(level, rod, probe);
		// 原版这里是 (int)(秒 * 20)，得到的是「刻」。
		// 千万不要自己乘 5 或乘 100 —— 附魔返回的单位是秒，换算只在这一处。
		int lureTicks = (int) (EnchantmentHelper.getFishingTimeReduction(level, rod, probe) * TICKS_PER_SECOND);

		// 真正的替身。两个参数都会被原版构造函数钳到非负，所以不需要在这里再钳一次。
		return new HookProbe(new FishingHook(EntityTypes.FISHING_BOBBER, level, luck, lureTicks));
	}

	/**
	 * 造一根「无附魔」替身，只用来做地形判定。
	 *
	 * <p>开放水域判定完全不依赖钓竿 —— 原版 {@code calculateOpenWater} 只看方块状态，
	 * 所以界面上「这片水算不算开放」这件事在<b>还没放钓竿</b>时也该能算出来。
	 * 这个工厂就是为那个场景准备的，不要拿它去掷战利品表（幸运值会是 0）。
	 */
	public static HookProbe plain(ServerLevel level) {
		return new HookProbe(new FishingHook(EntityTypes.FISHING_BOBBER, level, 0, 0));
	}

	/** 饵钓换算出的等待缩短量（刻）。原版尺度：每级 {@link #VANILLA_TICKS_PER_LURE_LEVEL} 刻。 */
	public int lureTicks() {
		return accessor().getLureSpeed();
	}

	/**
	 * 把原版换算结果按本模组的节奏重新标定，返回「等级 × {@code ticksPerLevel}」刻。
	 *
	 * <h2>为什么需要这一步</h2>
	 * 原版每级缩短 {@link #VANILLA_TICKS_PER_LURE_LEVEL} = 100 刻，那是配合它
	 * 100~600 刻的等待区间设计的。本模组的等待区间只有 30~50 刻 ——
	 * 照原样相减，<b>任何等级的饵钓都会把等待压到下限 1 刻</b>，
	 * I 级和 III 级跑出来一模一样，附魔等级阶梯完全失效。
	 *
	 * <p>缩放的语义是「把每级缩短量按区间比例缩到 {@code ticksPerLevel} 刻」：
	 * 先由 {@link #lureTicks()} 还原出等级（原版值恒为 {@code 等级 × 100}，
	 * 所以这次除法是精确的），再乘上调用方给的每级刻数。
	 *
	 * <p>换算链本身仍然完全走原版 —— 等级从 {@code EnchantmentHelper} 读回，
	 * 本方法只改那个标量系数，附魔的语义没有被重写。
	 *
	 * @param ticksPerLevel 本模组期望的「每级缩短刻数」，
	 *                      传 {@code MachineConfig.LURE_TICKS_PER_LEVEL}
	 */
	public int lureTicksScaledTo(int ticksPerLevel) {
		return accessor().getLureSpeed() / VANILLA_TICKS_PER_LURE_LEVEL * ticksPerLevel;
	}

	/** 海之眷顾加成，直接进战利品表的 {@code withLuck}。 */
	public int luck() {
		return accessor().getLuck();
	}

	/**
	 * 用原版逻辑判定某处是不是开放水域，并把结论写回替身。
	 *
	 * <p>写回是关键的一步：稍后掷战利品表时，原版的
	 * {@code FishingHookPredicate} 会读这个字段。只算不写，宝藏条目会永远被筛掉。
	 *
	 * @param bobberPos 参考点，取 {@link FishingSpot#water()}（水柱顶端那一格）
	 * @return 与写入替身的值相同
	 */
	public boolean probeOpenWater(BlockPos bobberPos) {
		FishingHookAccessor accessor = accessor();
		boolean open = accessor.callCalculateOpenWater(bobberPos);
		accessor.setOpenWater(open);
		return open;
	}

	/**
	 * 把替身交给战利品表当 {@code THIS_ENTITY}。
	 *
	 * <p>包内可见：只有 {@link LootRoller} 需要它，别处拿到这个实体也做不了什么有用的事。
	 */
	FishingHook entityForLoot() {
		return hook;
	}

	/**
	 * Mixin 生成的访问器在运行时被织进 {@link FishingHook}，
	 * 编译期只能靠强制转换拿到 —— 这是 Mixin 访问器的标准用法。
	 */
	private FishingHookAccessor accessor() {
		return (FishingHookAccessor) (Object) hook;
	}
}
