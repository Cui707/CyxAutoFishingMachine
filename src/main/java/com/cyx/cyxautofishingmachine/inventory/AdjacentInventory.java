package com.cyx.cyxautofishingmachine.inventory;

import org.jspecify.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.Container;
import net.minecraft.world.WorldlyContainer;
import net.minecraft.world.WorldlyContainerHolder;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 相邻容器的查找与「塞进去」。
 *
 * <h2>这个类为什么存在</h2>
 * 需求里「产物要能输出到箱子 / 漏斗」这件事，看起来只是 {@code level.getBlockEntity(pos)}
 * 加一个类型判断。实际上有三个坑，每一个都会让输出静默地走偏：
 *
 * <ol>
 *   <li><b>大箱子拆成了两个方块实体。</b>{@code level.getBlockEntity} 拿到的
 *       {@code ChestBlockEntity} 永远只有 27 格。要用满 54 格必须走原版的
 *       {@code ChestBlock#getContainer}，它会把两半合成一个 {@code CompoundContainer}；</li>
 *   <li><b>有些容器根本没有方块实体。</b>堆肥桶是 {@code WorldlyContainerHolder}，
 *       容器对象是<b>现造</b>出来的，而且它的可入料面只有顶面；</li>
 *   <li><b>「能不能放」有三层判断，少一层就是复制 / 串味的来源。</b>
 *       {@code Container#canPlaceItem}（物品类型）、
 *       {@code WorldlyContainer#canPlaceItemThroughFace}（从哪一面进）、
 *       槽位是否放得下（数量）。</li>
 * </ol>
 *
 * 下面三个方法的逻辑与原版 {@code HopperBlockEntity} 的 {@code getBlockContainer} /
 * {@code addItem} 逐句对应，区别只有一处：<b>每次只搬一件物品</b>（见 {@link #insertOne}），
 * 而漏斗的调用方原本就是一件一件调的，所以语义完全一致。
 *
 * <h2>有意不做的那部分：实体容器</h2>
 * 原版漏斗在方块里找不到容器时，还会去查这个坐标上的<b>实体</b>容器
 * （箱子船、箱子矿车）。本类<b>刻意不做</b>这一步，两个原因：
 * <ul>
 *   <li>那条分支会从世界随机数里取一个（随机挑一个实体）。输出路径一旦消费世界随机数，
 *       「这一杆钓上来什么」就会受旁边有没有箱子船影响 —— 两条互不相干的逻辑被耦合在一起；</li>
 *   <li>代价是每台机器每 8 刻做 6 次实体查询，收益是「恰好有箱子船停在机器旁边」这种极罕见情况。</li>
 * </ul>
 * 需求要求输出到「箱子 / 漏斗」，方块容器已经全部覆盖。
 */
public final class AdjacentInventory {

	private AdjacentInventory() {
	}

	/**
	 * 输出侧的尝试顺序。
	 *
	 * <p>顺序固定，不随朝向走：一是玩家能形成直觉（箱子放机器正下方最常见，
	 * 所以放得下的话一定先进下方那个），二是测试可以断言「先进哪一个」。
	 * 六个面全部参与 —— 朝正面那一面虽然是水面，但如果玩家硬要塞个容器过去，
	 * 没有理由拒绝他。
	 */
	public static final Direction[] OUTPUT_ORDER = {
			Direction.DOWN,
			Direction.NORTH,
			Direction.SOUTH,
			Direction.WEST,
			Direction.EAST,
			Direction.UP,
	};

	/**
	 * 取某个坐标上的方块容器，没有就返回 {@code null}。
	 *
	 * <p>与原版 {@code HopperBlockEntity#getBlockContainer} 一致，只是不查实体容器。
	 */
	public static @Nullable Container find(Level level, BlockPos pos) {
		BlockState state = level.getBlockState(pos);
		Block block = state.getBlock();

		// 容器实例可能根本不属于方块实体（堆肥桶就是这样），所以这一支必须排在最前面。
		if (block instanceof WorldlyContainerHolder holder) {
			return holder.getContainer(state, level, pos);
		}

		if (!state.hasBlockEntity()) {
			return null;
		}
		if (!(level.getBlockEntity(pos) instanceof Container container)) {
			return null;
		}
		// 大箱子：换成原版的合并视图，否则只看得到一半。
		if (container instanceof ChestBlockEntity && block instanceof ChestBlock chest) {
			return ChestBlock.getContainer(chest, state, level, pos, true);
		}
		return container;
	}

	/**
	 * 这个容器在 {@code face} 这个方向上有没有可以入料的槽位。
	 *
	 * <p>这一条专门用来挡掉两类「长得像容器、其实塞不进东西」的方块：
	 * 上游机器基类也实现了 {@code Container}，但 {@code getContainerSize()} 恒为 0
	 * （占位骨架）；堆肥桶只在顶面收料，从其它面看过去它的可入料槽位数是 0。
	 * 不挡掉它们的话，机器会把旁边的电池当成「一个已经满了的容器」，
	 * 于是停机原因从「没放容器」错报成「容器已满」。
	 */
	public static boolean canReceive(Container container, Direction face) {
		return slotsFor(container, face).length > 0;
	}

	/**
	 * 容器满了没有。
	 *
	 * <p>判据是「每个槽位的数量都到了它自己的上限」，与原版
	 * {@code HopperBlockEntity#isFullContainer} 一致 —— 注意是拿<b>槽里物品</b>的上限比，
	 * 不是拿 64 比：不可堆叠的工具在 1 个时就已经算满了。
	 */
	public static boolean isFull(Container container, Direction face) {
		for (int slot : slotsFor(container, face)) {
			ItemStack stack = container.getItem(slot);
			if (stack.getCount() < stack.getMaxStackSize()) {
				return false;
			}
		}
		return true;
	}

	/**
	 * 往容器里塞<b>一件</b>物品。
	 *
	 * <h2>为什么一次只塞一件</h2>
	 * 漏斗就是这么做的（它的 {@code ejectItems} 每刻只搬一件）。整堆搬看起来快得多，
	 * 但会破坏「容器自己消化物品」的语义：堆肥桶每收到一件才算一次堆肥机会，
	 * 一次塞 64 件只会计一次堆肥，剩下 63 件凭空消失。
	 * 逐件搬是唯一对所有容器都成立的搬运方式，速率由调用方的节拍控制
	 * （见 {@code MachineConfig#OUTPUT_INTERVAL_TICKS}）。
	 *
	 * <p><b>传入的 stack 不会被修改</b>，内部拿它的副本去搬 ——
	 * 这一点是防复制的前提：目标容器的 {@code setItem} 会把对象本身存进去，
	 * 若直接把自己的物品堆递过去，源槽和目标槽就会指向同一个 {@code ItemStack}。
	 *
	 * @param stack 源物品堆，只用到它的一件
	 * @return 是否塞进去了
	 */
	public static boolean insertOne(Container container, Direction face, ItemStack stack) {
		if (stack.isEmpty()) {
			return false;
		}
		ItemStack remaining = stack.copyWithCount(1);
		for (int slot : slotsFor(container, face)) {
			remaining = tryMoveIn(container, remaining, slot, face);
			if (remaining.isEmpty()) {
				return true;
			}
		}
		return false;
	}

	/**
	 * 该容器在这个方向上允许入料的槽位下标。
	 *
	 * <p>原版把「平坦槽位表」按容器大小缓存起来了（{@code HopperBlockEntity.CACHED_SLOTS}），
	 * 那是给每刻都在搬东西的漏斗用的。本模组的输出节拍是 8 刻一次、且只搬一件，
	 * 一次几十分之一的数组分配不值得换一个缓存结构。
	 */
	private static int[] slotsFor(Container container, Direction face) {
		if (container instanceof WorldlyContainer worldly) {
			return worldly.getSlotsForFace(face);
		}
		int size = container.getContainerSize();
		int[] slots = new int[size];
		for (int index = 0; index < size; index++) {
			slots[index] = index;
		}
		return slots;
	}

	/**
	 * 试着把一件物品放进指定槽位，返回剩下的。
	 *
	 * <p>两条分支的对象归属完全不同，这是整段代码最容易出错的地方：
	 * <ul>
	 *   <li><b>放进空槽</b>：把物品堆对象整个交给容器，所以 {@code remaining} 必须是副本；</li>
	 *   <li><b>并进同类堆</b>：只改数量（{@code grow} / {@code shrink}），对象还是各自的。</li>
	 * </ul>
	 * 两条分支都必须调 {@code setChanged()}：容器改了内容却不标脏，区块存档时那次改动就丢了。
	 */
	private static ItemStack tryMoveIn(Container container, ItemStack remaining, int slot, Direction face) {
		if (!canPlaceItemIn(container, remaining, slot, face)) {
			return remaining;
		}
		ItemStack current = container.getItem(slot);
		if (current.isEmpty()) {
			container.setItem(slot, remaining);
			container.setChanged();
			return ItemStack.EMPTY;
		}
		if (ItemStack.isSameItemSameComponents(current, remaining)) {
			// 拿槽里物品自己的上限算余量：不可堆叠物品的余量天然是 0，不需要特判。
			int space = current.getMaxStackSize() - current.getCount();
			int moved = Math.min(space, remaining.getCount());
			if (moved > 0) {
				remaining.shrink(moved);
				current.grow(moved);
				container.setChanged();
			}
		}
		return remaining;
	}

	/**
	 * 物品类型 + 入料面两道准入。
	 *
	 * <p>两道都要问，缺一不可：{@code canPlaceItem} 回答「这个容器收不收这种东西」，
	 * {@code canPlaceItemThroughFace} 回答「从这个方向过来收不收」。
	 * 界面与漏斗都走同一套 {@code Container} 接口，所以这里不需要另写规则 ——
	 * 本模组机器自己的 {@code canPlaceItem}（只有钓竿槽收钓竿）也因此自动生效：
	 * 两台机器贴在一起时，产物不会被推去邻居的缓存槽。
	 */
	private static boolean canPlaceItemIn(Container container, ItemStack stack, int slot, Direction face) {
		if (!container.canPlaceItem(slot, stack)) {
			return false;
		}
		return !(container instanceof WorldlyContainer worldly)
				|| worldly.canPlaceItemThroughFace(slot, stack, face);
	}
}
