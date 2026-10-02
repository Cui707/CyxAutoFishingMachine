package com.cyx.cyxautofishingmachine.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.projectile.FishingHook;

/**
 * 借原版 {@link FishingHook} 的两个私有成员一用。
 *
 * <h2>为什么需要它</h2>
 * 原版的「开放水域」（open water）判定是一段相当啰嗦的代码 ——
 * {@code calculateOpenWater} 要在浮标位置上下 4 层、每层 5×5 的范围内逐格分类，
 * 再按层与层之间的顺序关系给出结论。这段规则直接决定了「能不能钓到宝藏」，
 * 而且随版本可能微调。自己照抄一份有几个坏处：
 * <ul>
 *   <li>抄错了不会报错，只会「偶尔钓不到宝藏」，极难排查；</li>
 *   <li>原版一旦调整判定范围，本模组要么跟着改，要么悄悄偏离；</li>
 *   <li>任何依赖原版语义的第三方数据包 / 附属模组都会看到两套不一致的结论。</li>
 * </ul>
 * 所以这里用 Mixin 开放两个成员，<b>直接调用原版实现</b>：
 *
 * <table border="1">
 *   <caption>开放的两个成员</caption>
 *   <tr><th>原版成员</th><th>签名</th><th>用途</th></tr>
 *   <tr><td>{@code calculateOpenWater}</td><td>{@code private boolean (BlockPos)}</td>
 *       <td>开放水域判定本体</td></tr>
 *   <tr><td>{@code openWater}</td><td>{@code private boolean}</td>
 *       <td>判定结果；原版战利品表就是通过它筛掉宝藏条目的</td></tr>
 * </table>
 *
 * <h2>为什么写 {@code openWater} 字段而不是只读</h2>
 * 原版 {@code fishing.json} 的宝藏条目挂了一条
 * {@code entity_properties → this → type_specific/fishing_hook.in_open_water == true}。
 * 换言之：<b>「封闭水域不出宝藏」不是钓鱼代码判断的，是战利品表自己判断的</b>。
 * 那条谓词（{@code FishingHookPredicate#matches}）会把我方伪造的浮动标实体
 * 强制转成 {@link FishingHook} 并读 {@code isOpenWaterFishing()}。
 * 所以只要把判定结果写进字段，原版战利品表就会照着它筛条目 —— 一行判定逻辑都不用重写。
 *
 * <h2>两个成员的额外用途：读回原版的附魔参数</h2>
 * {@code luck} 与 {@code lureSpeed} 是 {@link FishingHook} 在构造时按钓竿附魔算好、
 * 再自我钳到非负的字段。原本另存一份完全相同的副本，但那样就有两处会各自漂移。
 * 这里把它们一并开放，让状态机读的就是原版实际会用的那两个数。
 *
 * <p>注意本类只是 {@code FishingHook} 的一个接口 Mixin：它不改变原版行为，
 * 只是在类上「粘」了一层可直接调用的公开方法。
 * 名字故意取成 {@code getX} / {@code setX} / {@code callX} 的常规形态，
 * 与 {@code @Accessor} / {@code @Invoker} 显式给出的目标名互相印证 ——
 * 就算哪条自动推导规则变了，两边的结论也仍然一致。
 */
@Mixin(FishingHook.class)
public interface FishingHookAccessor {

	/** 读原版算出的开放水域结论。 */
	@Accessor("openWater")
	boolean getOpenWater();

	/**
	 * 写开放水域结论。
	 *
	 * <p>唯一的写入点是本模组的开放水域探测器：它先用 {@link #callCalculateOpenWater} 算出结果，
	 * 再写回来，好让原版战利品表能读到。
	 */
	@Accessor("openWater")
	void setOpenWater(boolean openWater);

	/**
	 * 调用原版的开放水域判定。
	 *
	 * <p>判定只用到了 {@code this.level()}，不碰浮标的其它状态，
	 * 所以一个「没有进入过世界」的浮动标实例也能安全地用它当纯函数。
	 *
	 * @param blockPos 浮标所在的方块坐标 —— 原版传的是 {@code this.blockPosition()}，
	 *                 也就是浮标漂浮的那个方块。本模组传「水柱最上面那一格水」，
	 *                 理由见 {@code HookProbe}
	 */
	@Invoker("calculateOpenWater")
	boolean callCalculateOpenWater(BlockPos blockPos);

	/** 读原版按钓竿附魔算出的幸运加成（海之眷顾）。 */
	@Accessor("luck")
	int getLuck();

	/** 读原版按钓竿附魔算出的等待缩短量（饵钓），单位：刻。 */
	@Accessor("lureSpeed")
	int getLureSpeed();
}
