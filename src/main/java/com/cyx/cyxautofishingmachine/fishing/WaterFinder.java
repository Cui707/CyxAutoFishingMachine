package com.cyx.cyxautofishingmachine.fishing;

import org.jspecify.annotations.Nullable;

import com.cyx.cyxautofishingmachine.config.MachineConfig;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.FluidState;

/**
 * 决定机器把浮标扔到哪儿。
 *
 * <h2>为什么不能就用「紧贴机器的那格水」</h2>
 * 第一版是这么写的，被开放水域判定当场否掉：原版 {@code calculateOpenWater} 要看浮标
 * 周围 <b>±2 格、上下 4 层</b>的方块。机器摆在岸边时，紧贴它的那格水必然也贴着岸 ——
 * ±2 的范围一伸出去就撞上陆地，结论永远是「非开放水域」，
 * <b>于是这台机器永远只能钓到鱼和垃圾，一件宝藏都拿不到。</b>
 *
 * <p>这恰恰是原版钓鱼的一条真实规则：站在岸边钓只能钓到鱼，
 * 想把宝藏钓上来就得把浮标甩到开阔水面上。玩家是这样做的，
 * 所以机器也必须这样做，否则「开放水域」这个条件在本模组里就是死的。
 *
 * <h2>机器怎么「瞄」</h2>
 * 机器没有准星，必须替它定一条规则。这里用的是：
 *
 * <pre>
 * 沿机器正面方向，量出连续的水面段（水面在哪儿断就停），
 * 然后把浮标扔到这段水面的<b>中点</b>。
 * </pre>
 *
 * 选中点而不是岸边或最远处，是因为「把浮标甩到开阔水面上」这个意图的几何答案就是中点：
 * 水面越宽，中点离岸越远，±2 范围越可能全是水。
 * 换成最远处，在一个 5 格宽的小水塘里反而会把浮标甩到对面的岸边去。
 *
 * <p>正面方向没水时依次试左手边、右手边、正后方（同一套规则），
 * 最后才退到「机器自己这一列的正下方 / 正上方」—— 那是「机器悬在水面上」的摆法，
 * 只取单格，因为这时没有「往哪儿扔」的问题。
 *
 * <p><b>这是一条设计决策，不是原版规则</b>：原版由玩家瞄准，机器必须自己定一条。
 * 选这条是因为它能实现原版那条「离岸越远越可能出宝藏」的因果关系，
 * 而不是为了让它更容易出宝藏。界面上会如实显示判定结果（开放 / 非开放），
 * 玩家看到「非开放」就知道该把机器往水面中间挪。
 *
 * <h2>水柱怎么定顶</h2>
 * 从命中的那格水往上走，直到不再是水。这样「水面下三格的暗流」和「瀑布上方的活水」
 * 都会归到各自水柱的顶端，交给原版判定。顶端那一格才等价于原版浮标漂浮的位置，
 * 理由见 {@link FishingSpot}。
 *
 * <h2>本类刻意不做的事</h2>
 * 不判断水「够不够开放」。那是原版 {@code calculateOpenWater} 的职责，
 * 而且它的结论只影响<b>能不能钓到宝藏</b>，不影响「能不能钓」——
 * 原版在任何水里都能钓上鱼和垃圾。把「不开放」当成「不能钓鱼」会直接改变游戏行为。
 */
public final class WaterFinder {

	private WaterFinder() {
	}

	/**
	 * 每一列上「同高 / 低一格 / 高一格」的查找顺序。
	 *
	 * <p>三档都试，是为了让机器放在比水面高一格或低一格的岸上都还能找到水 ——
	 * 码头、堤岸、台阶这类摆法非常常见。
	 */
	private static final int[] VERTICAL_ORDER = { 0, -1, 1 };

	/** 方向优先级：正面 → 两侧 → 背面。 */
	private static Direction[] searchOrder(Direction facing) {
		return new Direction[] {
				facing,
				facing.getClockWise(),
				facing.getCounterClockWise(),
				facing.getOpposite(),
		};
	}

	/**
	 * 找一处可下钩的水。
	 *
	 * <p>只读操作，可以在 tick 里调用；调用方按
	 * {@link MachineConfig#WATER_RECHECK_INTERVAL} 节流即可。
	 *
	 * @return 水柱顶端那一格；周围没有水时返回 {@code null}
	 */
	@Nullable
	public static FishingSpot find(Level level, BlockPos machinePos, Direction facing) {
		BlockPos hit = null;
		for (Direction direction : searchOrder(facing)) {
			hit = castAlong(level, machinePos, direction);
			if (hit != null) {
				break;
			}
		}
		if (hit == null) {
			hit = verticalFallback(level, machinePos);
		}
		if (hit == null) {
			return null;
		}

		BlockPos surface = topOfColumn(level, hit);
		FluidState fluid = level.getFluidState(surface);
		// 用原版自己的高度公式，而不是写死 8/9：活水、下落水的高度都不一样，
		// 而这个值决定浮标坐标的 Y，进而决定 location_check 这类判定的结果。
		return new FishingSpot(surface, fluid.getHeight(level, surface));
	}

	/**
	 * 沿一个水平方向抛竿。
	 *
	 * <p>先量出连续水面段的长度，再取它的中点。水面在哪儿断（不是水）就停，
	 * 所以「一条 3 格宽的水沟」与「一片海」会被区分对待 —— 前者的中点在沟里，后者在远处。
	 */
	@Nullable
	private static BlockPos castAlong(Level level, BlockPos machinePos, Direction direction) {
		int length = 0;
		for (int distance = 1; distance <= MachineConfig.MAX_CAST_DISTANCE; distance++) {
			if (waterAtColumn(level, machinePos.relative(direction, distance)) == null) {
				break;
			}
			length = distance;
		}
		if (length == 0) {
			return null;
		}
		// 中点。整数除法向近岸偏一格（4 格宽取第 2 格而不是第 3 格），
		// 与「先量出一段、再从岸边往里数一半」的手感一致。
		int target = (length + 1) / 2;
		return waterAtColumn(level, machinePos.relative(direction, target));
	}

	/**
	 * 在这一列的上中下三格里找水。
	 *
	 * <p>允许相邻列相差一格高度，是为了让斜坡水岸和瀑布也能连成一段。
	 */
	@Nullable
	private static BlockPos waterAtColumn(Level level, BlockPos column) {
		for (int dy : VERTICAL_ORDER) {
			BlockPos candidate = column.offset(0, dy, 0);
			if (isWater(level, candidate)) {
				return candidate;
			}
		}
		return null;
	}

	/** 机器自己这一列：先看脚底下（贴着水面的摆法），再看头顶。 */
	@Nullable
	private static BlockPos verticalFallback(Level level, BlockPos machinePos) {
		if (isWater(level, machinePos.below())) {
			return machinePos.below();
		}
		if (isWater(level, machinePos.above())) {
			return machinePos.above();
		}
		return null;
	}

	/**
	 * 从水柱中的任意一格走到顶端。
	 *
	 * <p>用 {@code level.getMaxY()} 当上界而不是写一个魔法数字：
	 * 世界顶部由数据包决定，写死 320 在自定义高度的世界里会直接走出边界。
	 */
	private static BlockPos topOfColumn(Level level, BlockPos start) {
		BlockPos top = start;
		BlockPos above = top.above();
		while (above.getY() <= level.getMaxY() && isWater(level, above)) {
			top = above;
			above = top.above();
		}
		return top.immutable();
	}

	/**
	 * 这一格是不是水。
	 *
	 * <p>用流体标签而不是「方块是 {@code minecraft:water}」：含水的方块
	 * （水浸台阶、水浸栅栏等）流体状态同样是水，原版判定也会把它们算进去，
	 * 只是它们有碰撞箱，最终会被判成「非开放水域」。两条结论一致，不需要在这里特判。
	 */
	public static boolean isWater(Level level, BlockPos pos) {
		return level.getFluidState(pos).is(FluidTags.WATER);
	}
}
