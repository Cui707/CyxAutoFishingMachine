package com.cyx.cyxautofishingmachine.init;

import com.cyx.cyxautofishingmachine.CyxAutoFishingMachine;

import net.minecraft.core.registries.Registries;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;

/**
 * 本模组使用的物品 / 方块标签。
 *
 * <h2>为什么钓竿要额外挂一个标签</h2>
 * 需求「条件 C」里除了原版钓竿，还要支持「具有正常钓鱼功能的兼容钓竿」。
 * 判断方式有两种：
 * <ul>
 *   <li>{@code instanceof FishingRodItem} —— 覆盖所有继承原版钓竿类的模组钓竿，
 *       这是绝大多数情况；</li>
 *   <li>打标签 —— 覆盖那些<b>没有</b>继承 {@code FishingRodItem}、
 *       但自己实现了钓鱼逻辑的模组钓竿。</li>
 * </ul>
 * 所以 {@link com.cyx.cyxautofishingmachine.blockentity.AutoFishingMachineBlockEntity}
 * 判定时是「二者取或」，玩家或整合包只要把物品写进这个标签就能扩展，
 * 不需要改一行 Java 代码。
 */
public final class ModTags {

	private ModTags() {
	}

	/** 物品标签。 */
	public static final class Items {

		/**
		 * 可放入自动钓鱼机钓竿槽的物品。
		 *
		 * <p>内容在 {@code data/cyxautofishingmachine/tags/item/fishing_rods.json} 里维护，
		 * 默认只包含 {@code minecraft:fishing_rod}。
		 */
		public static final TagKey<Item> FISHING_RODS =
				TagKey.create(Registries.ITEM, CyxAutoFishingMachine.id("fishing_rods"));

		private Items() {
		}
	}
}
