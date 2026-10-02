package com.cyx.cyxautofishingmachine.fishing;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/**
 * 机器这一轮下钩的位置 —— 一个「水柱」的最上面那一格水。
 *
 * <h2>为什么记的是「最上面那一格水」而不是「机器旁边的水」</h2>
 * 原版浮标是一个有重力、有浮力的实体，它最终会停在水面处：
 * {@code FishingHook#tick} 每刻把速度往「让浮标的 Y 等于该格流体高度」的方向掰，
 * 于是浮标静止时的 {@code blockPosition()} 恰好落在<b>水柱顶端那一格水方块</b>上
 * （该格上方没有水，流体高度按 {@code amount / 9} 算，源方块是 8/9 &lt; 1，
 * 所以 {@code Mth.floor(Y)} 往回落到这一格，而不是上面那格空气）。
 *
 * <p>这不是推演出来的猜测，而是对着反编译源码逐句核对过的：
 * {@code FlowingFluid#getHeight} 在「上方还是同种流体」时返回 1.0，否则返回 {@code amount / 9}。
 * 把这条结论用在原版自己身上，就能解释为什么原版 {@code calculateOpenWater(blockPos)}
 * 的第 {@code y = -1} 层必须是「水下」—— 因为参考点在水面那一格。
 *
 * <h2>为什么还要记流体高度</h2>
 * 战利品表的 {@code ORIGIN} 参数原版传的是 {@code this.position()}，也就是浮标的精确坐标
 * （X/Z 是方块中心 +0.5，Y 是水面 + 流体高度）。少数数据包会按 {@code location_check}
 * 判定「在哪个生物群系钓的」，所以这里也照着还原，而不是图省事传一个方块中心。
 *
 * @param water         水柱顶端那一格水的坐标；同时也是交给原版开放水域判定的参考点
 * @param surfaceHeight 该格流体的高度（源方块为 8/9，见 {@code FlowingFluid#getHeight}）
 */
public record FishingSpot(BlockPos water, float surfaceHeight) {

	/** 原版浮标静止时的精确坐标 —— 直接对应 {@code FishingHook#position()}。 */
	public Vec3 bobberPosition() {
		return new Vec3(water.getX() + 0.5D, water.getY() + surfaceHeight, water.getZ() + 0.5D);
	}
}
