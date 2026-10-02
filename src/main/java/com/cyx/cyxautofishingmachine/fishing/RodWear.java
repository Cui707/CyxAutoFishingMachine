package com.cyx.cyxautofishingmachine.fishing;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.EnchantmentEffectComponents;
import net.minecraft.world.item.enchantment.EnchantmentHelper;

/**
 * 钓竿的磨损与修补 —— 把原版「收杆」那一下对钓竿做的两件事搬过来。
 *
 * <h2>原版那一瞬间发生了什么</h2>
 * <pre>
 * FishingRodItem#use
 *   ├─ int dmg = fishing.retrieve(rod);       // 内部：掷战利品 + 在玩家脚下生成经验球
 *   └─ rod.hurtAndBreak(dmg, player, hand);   // 扣耐久
 *                                              ↓ 大约一秒后
 *                       玩家吸到经验球 → Player#giveExperiencePoints
 *                                     → ExperienceOrb#repairPlayerItems
 *                                     → 若钓竿带修补，用经验修耐久
 * </pre>
 * 本模组没有玩家，但<b>两台机器的等价物都还在</b>：钓竿在钓竿槽里，
 * 经验由机器「代收」。于是这一段被压缩成一次调用：先扣耐久（原版顺序），
 * 再把这一杆的经验喂给修补附魔（原版一秒后发生的事）。
 *
 * <h3>为什么顺序不能反</h3>
 * 先扣耐久再修补，和原版的先后完全一致。反过来写会引入一个真实差别：
 * 耐久只剩 1 点时，先修补会把耐久修回一半，再扣 1 点就永远不会损坏；
 * 而原版是先扣 —— 钓竿确实会断。这种「看起来更善良」的改动本质是改机制。
 *
 * <h3>耐久附魔 / 修补附魔都不是自己实现的</h3>
 * <ul>
 *   <li><b>耐久</b>：{@code ItemStack#hurtAndBreak} 内部会走
 *       {@code EnchantmentHelper#processDurabilityChange}，
 *       耐久附魔「有 (等级+1) 分之一的概率不消耗耐久」的规则自动生效；</li>
 *   <li><b>修补</b>：判定用 {@code EnchantmentHelper#has(rod, REPAIR_WITH_XP)}，
 *       换算用 {@code EnchantmentHelper#modifyDurabilityToRepairFromXp}。
 *       后者读的是附魔数据里 {@code repair_with_xp} 的值（原版修补是「1 点经验修 2 点耐久」），
 *       所以数据包把修补改成「1 点经验修 3 点耐久」时，本模组不用改代码也跟着变。</li>
 * </ul>
 *
 * <h3>修补的换算为什么与 {@code ExperienceOrb} 一模一样</h3>
 * 原版 {@code repairPlayerItems} 里是：
 * <pre>
 * int toRepair = modifyDurabilityToRepairFromXp(level, stack, xp);   // 这么多经验能修多少耐久
 * int repair   = Math.min(toRepair, stack.getDamageValue());         // 但修不了超过已损的量
 * stack.setDamageValue(stack.getDamageValue() - repair);
 * </pre>
 * 而且它只挑<b>已受损</b>的物品（{@code ItemStack::isDamaged}）。机器里只有一根钓竿，
 * 「随机挑一件」退化成「就是它」，所以上面三行就是原版在本场景下的全部行为。
 */
public final class RodWear {

	private RodWear() {
	}

	/** 原版一次成功钓获扣的耐久：{@code FishingHook#retrieve} 返回的 {@code dmg = 1}。 */
	public static final int DAMAGE_PER_CATCH = 1;

	/**
	 * 施加一杆钓获的磨损，并把这一杆的经验用于修补。
	 *
	 * <p><b>会原地修改 {@code rod}</b>（耐久值、乃至钓竿损坏时把数量减到 0）。
	 * 调用方在调用后必须 {@code setChanged()} —— {@link ItemStack} 自己不知道
	 * 它躺在哪个容器里，不会替我们标脏。
	 *
	 * @param level  服务端世界
	 * @param rod    钓竿槽里的钓竿
	 * @param random 随机源
	 * @return 钓竿是否正好在这一杆损坏（返回 true 时 {@code rod} 已经是空栈）
	 */
	public static boolean apply(ServerLevel level, ItemStack rod, RandomSource random) {
		// 经验先算出来。原版是「生成经验球实体」，这里是「机器代收」——
		// 数值完全一样（1~6 点），只是不需要真的生成实体再飞过来。
		int experience = LootRoller.rollExperience(random);

		// 扣耐久。player 传 null 的理由：机器没有玩家，而 hasInfiniteMaterials()
		// 那条创造模式豁免、以及 ITEM_DURABILITY_CHANGED 进度触发器都依赖真实玩家。
		// 回调本身不需要做事 —— 钓竿损坏时 applyDamage 已经把数量减到 0 了，
		// 兴趣方（音效）从返回值就能知道。
		rod.hurtAndBreak(DAMAGE_PER_CATCH, level, null, broken -> { });

		// 钓竿已经断了就没什么可修的了。
		if (rod.isEmpty()) {
			return true;
		}

		repairWithExperience(level, rod, experience);
		return false;
	}

	/**
	 * 用这一杆的经验修补钓竿。
	 *
	 * <p>没有修补附魔时这份经验<b>直接消失</b>。这是刻意的：
	 * 原版里这份经验归玩家，机器没有玩家，凭空给它变成「储存经验」是本模组自创机制。
	 * 换句话说，机器只承接原版链路里「经验 → 修补钓竿」这一步。
	 */
	private static void repairWithExperience(ServerLevel level, ItemStack rod, int experience) {
		if (experience <= 0) {
			return;
		}
		// 先看有没有修补；再看值不值得修 —— 满耐久的钓竿不会被消耗经验。
		if (!EnchantmentHelper.has(rod, EnchantmentEffectComponents.REPAIR_WITH_XP) || !rod.isDamaged()) {
			return;
		}

		int repairable = EnchantmentHelper.modifyDurabilityToRepairFromXp(level, rod, experience);
		int repaired = Math.min(repairable, rod.getDamageValue());
		if (repaired > 0) {
			rod.setDamageValue(rod.getDamageValue() - repaired);
		}
	}
}
