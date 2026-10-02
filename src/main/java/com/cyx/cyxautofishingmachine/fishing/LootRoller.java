package com.cyx.cyxautofishingmachine.fishing;

import java.util.List;

import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.loot.BuiltInLootTables;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;

/**
 * 掷战利品表 —— 本模组唯一决定「钓上来什么」的地方。
 *
 * <h2>这里没有自研概率表</h2>
 * 需求里最硬的一条是「不允许用随机数自己糊一个掉落表」。所以本类逐句照搬
 * 原版 {@code FishingHook#retrieve} 里构造 {@link LootParams} 的那一段，然后交给
 * <b>原版自己的 {@code minecraft:gameplay/fishing} 表</b>去掷：
 *
 * <pre>
 * fishing                            总表，1 次投掷，三选一
 *   ├─ loot_table junk     权重 10   quality -2   垃圾
 *   ├─ loot_table treasure 权重  5   quality +2   宝藏（带 in_open_water 谓词）
 *   └─ loot_table fish     权重 85   quality -1   鱼
 * </pre>
 *
 * <h3>于是这三件事同时自动成立</h3>
 * <ul>
 *   <li><b>海之眷顾有效</b>：三条候选各带一个 {@code quality}，与其他条目按
 *       {@code weight + quality × luck} 参与抽取，luck 由 {@link LootParams.Builder#withLuck}
 *       传进来；</li>
 *   <li><b>封闭水域不出宝藏</b>：宝藏条目的 {@code entity_properties} 谓词会把
 *       {@code THIS_ENTITY} 转成浮动标读 {@code in_open_water}，
 *       而我们的替身已经由 {@link HookProbe#probeOpenWater} 写好了这个字段；</li>
 *   <li><b>数据包 / 附属模组可覆盖</b>：走的是 {@code reloadableRegistries()}，
 *       服主换一张 {@code fishing.json} 就完全接管了产出，本模组不用改一行代码。</li>
 * </ul>
 *
 * <h3>一个刻意的差异：主人的幸运属性不参与</h3>
 * 原版是 {@code withLuck(this.luck + owner.getLuck())} —— 额外叠加持竿玩家的
 * {@code Luck} 属性（幸运药水的效果）。机器没有玩家，这一项恒为 0。
 * 这不是遗漏：给机器凭空一个幸运加成反而是篡改原版机制。
 */
public final class LootRoller {

	private LootRoller() {
	}

	/** 原版一次钓获的经验球数量区间：每个掉落物 {@code nextInt(6) + 1}，即 1~6 点。 */
	private static final int XP_BOUND = 6;

	/**
	 * 掷一次钓鱼战利品表。
	 *
	 * <p>本方法<b>无副作用</b>：不写钓竿耐久、不动容器、不生成实体。
	 * 调用方拿到结果之后自行决定「塞进缓存」还是「因为放不下而放弃这一杆」。
	 *
	 * @param level 服务端世界
	 * @param rod   钓竿槽里的钓竿，作为 {@code TOOL} 参数（附魔、耐久改动都在外面做）
	 * @param probe 浮动标替身，提供 {@code THIS_ENTITY} 与幸运值
	 * @param spot  下钩点，提供 {@code ORIGIN}
	 */
	public static List<ItemStack> roll(ServerLevel level, ItemStack rod, HookProbe probe, FishingSpot spot) {
		LootParams params = new LootParams.Builder(level)
				// ORIGIN 用浮标静止时的精确坐标，而不是方块中心：
				// 少数数据包会用 location_check 判定「在哪个生物群系 / 结构里钓的」。
				.withParameter(LootContextParams.ORIGIN, spot.bobberPosition())
				.withParameter(LootContextParams.TOOL, rod)
				// THIS_ENTITY 在 LootContextParamSets.FISHING 里是 optional，
				// 但必须传 —— 宝藏条目的谓词完全靠它判断是否开放水域。漏传 = 永远没有宝藏。
				.withParameter(LootContextParams.THIS_ENTITY, probe.entityForLoot())
				.withLuck(probe.luck())
				.create(LootContextParamSets.FISHING);

		LootTable table = lootTable(level);
		return table.getRandomItems(params);
	}

	/**
	 * 取原版的钓鱼战利品表。
	 *
	 * <p>走 {@code reloadableRegistries()} 而不是某个静态常量：
	 * 前者拿的是「这次数据包加载后实际生效」的那一份，服主改表立刻生效。
	 */
	private static LootTable lootTable(ServerLevel level) {
		ResourceKey<LootTable> key = BuiltInLootTables.FISHING;
		return level.getServer().reloadableRegistries().getLootTable(key);
	}

	/**
	 * 原版每掉落一件物品给的经验点数。
	 *
	 * <p>原版是 {@code owner.level().addFreshEntity(new ExperienceOrb(..., nextInt(6) + 1))}，
	 * 每件掉落物各生成一个球。钓鱼恒定只掉一件，所以一杆就是 1~6 点。
	 *
	 * <p>这些经验在 {@link RodWear} 里被用来喂修补附魔 ——
	 * 等价于「浮标在玩家脚下、经验球被玩家吸收」的那条原版链路。
	 */
	public static int rollExperience(RandomSource random) {
		return random.nextInt(XP_BOUND) + 1;
	}
}
