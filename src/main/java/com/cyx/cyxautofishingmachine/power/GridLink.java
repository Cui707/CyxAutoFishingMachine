package com.cyx.cyxautofishingmachine.power;

import com.cyx.crimsoncoppergrid.blockentity.cable.CableBlockEntity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import team.reborn.energy.api.EnergyStorage;

/**
 * 「本机此刻接在电网上吗」—— 六邻检测。
 *
 * <h2>为什么需要它</h2>
 * 需求里的「条件 D」要求电网断开时暂停新的钓鱼周期。但 Team Reborn Energy 是<b>推流</b>模型：
 * 电源把电推向邻居，用电设备只是被动接收。也就是说设备这一侧<b>收不到任何「电网断了」的通知</b>，
 * 只能自己去问。
 *
 * <h2>为什么不是「缓冲里还有电就算连着」</h2>
 * 10 000 FE 的缓冲能撑 20 次钓鱼。如果拿「缓冲非空」当连接依据，
 * 玩家把电线剪掉之后机器还会自顾自地钓上二十分钟 —— 那是错的。
 * 所以这里查的是<b>拓扑</b>：机器旁边还有没有能供电的东西。
 *
 * <h2>判定规则（两个分支，各有原因）</h2>
 * <ol>
 *   <li><b>邻居是导体</b>（导线 / 电闸）：看 {@link CableBlockEntity#conducts()}。
 *       不导通就代表这里被电闸切断了，电网在这一格断开；</li>
 *   <li><b>邻居是别的设备</b>：走 Fabric 官方的 {@link EnergyStorage#SIDED} 查阅表，
 *       要求 {@code supportsExtraction()} 为真 —— 也就是「它能把电给我」
 *       （发电机、电池，以及任何第三方储能）。</li>
 * </ol>
 *
 * <h3>⚠️ 为什么导体这一支不能用 {@code supportsExtraction()}</h3>
 * {@code SimpleSidedEnergyContainer} 的按面视图把 {@code supportsExtraction()} 实现为
 * {@code getMaxExtract(side) > 0}，而导线的 {@code getMaxExtract} 还乘了一个
 * {@code allowTransfer(side)}，后者会检查 {@link CableBlockEntity} 的
 * <b>{@code blockedSides} 记账位</b>：某个方向刚搬过电，这一轮内就不允许反向操作。
 *
 * <p>问题在于这个记账位的清除时机是<b>下一轮网络结算的开头</b>，而不是本 tick 的结尾。
 * 也就是说「导线刚刚给我们送过电」的那个方向，在该 tick 之后的任何时刻去查
 * {@code supportsExtraction()} 都可能读到 0。若拿它当判据，
 * 机器会在「正常供电」和「电网断了」之间反复横跳 —— 而且是随机取决于
 * 双方的 tick 先后顺序。{@code conducts()} 只读方块状态，不受任何 tick 中间态影响，
 * 所以导体这一支必须走它。
 *
 * <h2>无副作用</h2>
 * 本类只读，不改任何状态，因此可以在 tick 之外调用（测试、调试命令都能用）。
 */
public final class GridLink {

	private GridLink() {
	}

	/**
	 * 本机是否接在一个「有供电方」的电网/电源上。
	 *
	 * <p><b>注意这只回答「接上了没」，不回答「有没有电」。</b>
	 * 一张接通了但发电机停机的电网仍然是 {@code true}，那是「电量不足」，
	 * 由状态机另行判断（缓冲里有多少电）。两者分开，暂停原因才能显示得准确。
	 */
	public static boolean isLinked(ServerLevel level, BlockPos machinePos) {
		for (Direction side : Direction.values()) {
			BlockPos neighborPos = machinePos.relative(side);
			if (!level.isLoaded(neighborPos)) {
				continue;
			}
			if (canSupplyInto(level, neighborPos, side)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * 邻居这一格能不能把电送进机器。
	 *
	 * @param sideFromMachine 从机器看向邻居的方向；查邻居的能力时要用它的反面
	 */
	private static boolean canSupplyInto(ServerLevel level, BlockPos neighborPos, Direction sideFromMachine) {
		BlockEntity neighbor = level.getBlockEntity(neighborPos);

		// 分支一：导体。导通 = 顺着它就能走到电网里去。
		if (neighbor instanceof CableBlockEntity conductor) {
			return conductor.conducts();
		}

		// 分支二：普通设备。只有能出电的才算供电方 ——
		// 也就是说旁边的电力熔炉、另一台钓鱼机都不会被误判成「接上电了」。
		EnergyStorage storage = EnergyStorage.SIDED.find(level, neighborPos, sideFromMachine.getOpposite());
		return storage != null && storage.supportsExtraction();
	}
}
