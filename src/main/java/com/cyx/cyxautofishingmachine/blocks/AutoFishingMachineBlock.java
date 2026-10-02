package com.cyx.cyxautofishingmachine.blocks;

import com.cyx.crimsoncoppergrid.common.blocks.BlockMachineBase;
import com.cyx.cyxautofishingmachine.blockentity.AutoFishingMachineBlockEntity;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 自动钓鱼机方块。
 *
 * <h2>这一层为什么这么薄</h2>
 * 放置朝向、旋转 / 镜像、{@code ACTIVE} 状态、右键开界面、比较器输出、服务端 tick 调度、
 * 放置与破坏钩子 —— 这些全部由前置 Mod 的 {@link BlockMachineBase} 提供。
 * 本类只负责把方块和它的方块实体绑起来。
 *
 * <p>这是有意为之：越少的重复代码，越不会出现「本机和上游机器行为不一致」。
 *
 * <h2>破坏时物品为什么不在这里处理</h2>
 * 需求要求「方块破坏时不能丢失内部物品」「机器被破坏时钓竿必须掉落」。
 * 26.3 的原版机制已经覆盖了这件事，不需要自己写掉落代码：
 * <pre>
 * LevelChunk#setBlockState(...)
 *     -&gt; 旧方块有方块实体且未被保留
 *     -&gt; blockEntity.preRemoveSideEffects(pos, oldState)
 *     -&gt; BlockEntity 的默认实现：
 *            if (this instanceof Container c &amp;&amp; this.level != null)
 *                Containers.dropContents(this.level, pos, c);
 * </pre>
 * 也就是说：<b>只要方块实体实现了 {@link net.minecraft.world.Container} 并返回真实物品，
 * 无论方块是玩家挖掉、被爆炸炸掉、被活塞推掉还是被 {@code /setblock} 换掉的，
 * 里面的东西都会自动掉出来</b>。
 *
 * <p>反过来说，如果在这里自己写一遍 {@code dropContents}，就会和原版逻辑<b>重复掉落</b>
 * （每件物品掉两份），这是必须避免的。所以本类刻意不覆写任何掉落相关方法。
 */
public class AutoFishingMachineBlock extends BlockMachineBase {

	public AutoFishingMachineBlock(Properties properties) {
		super(properties);
	}

	@Override
	public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
		return new AutoFishingMachineBlockEntity(pos, state);
	}
}
