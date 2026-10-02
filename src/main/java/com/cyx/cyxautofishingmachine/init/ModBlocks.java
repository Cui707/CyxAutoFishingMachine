package com.cyx.cyxautofishingmachine.init;

import java.util.function.BiFunction;
import java.util.function.Function;

import com.cyx.crimsoncoppergrid.common.blocks.BlockMachineBase;
import com.cyx.cyxautofishingmachine.CyxAutoFishingMachine;
import com.cyx.cyxautofishingmachine.blocks.AutoFishingMachineBlock;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;

/**
 * 方块注册。
 *
 * <p>写法与前置 Mod 的 {@code init.ModBlocks} 保持一致（同样是 TechReborn 风格的集中声明），
 * 这样两个项目的注册代码读起来是同一套东西 —— 本模组是 CrimsonCopperGrid 的附属，
 * 风格割裂只会让后续维护变贵。
 *
 * <p>顺序有讲究：<b>方块 -&gt; 方块实体（要引用方块）-&gt; 物品（要引用方块）-&gt; 创造栏</b>。
 * 由 {@link CyxAutoFishingMachine#onInitialize()} 显式触发，不依赖类加载的偶然顺序。
 *
 * <p>外观属性直接复用上游的 {@link BlockMachineBase#machineProperties()}，
 * 于是「本机与 CrimsonCopperGrid 的机器是同一套材质语言」这件事只有一个修改点。
 */
public final class ModBlocks {

	/** 自动钓鱼机。 */
	public static final AutoFishingMachineBlock AUTO_FISHING_MACHINE =
			register("auto_fishing_machine", AutoFishingMachineBlock::new, BlockMachineBase.machineProperties());

	private ModBlocks() {
	}

	public static void init() {
	}

	// ------------------------------------------------------------ 工具

	private static ResourceKey<Block> blockKey(String name) {
		return ResourceKey.create(Registries.BLOCK, CyxAutoFishingMachine.id(name));
	}

	private static ResourceKey<Item> itemKey(String name) {
		return ResourceKey.create(Registries.ITEM, CyxAutoFishingMachine.id(name));
	}

	/**
	 * 注册方块，并顺带注册它的方块物品。
	 *
	 * <p>{@code properties.setId(key)} 是 26.3 的硬要求：方块必须知道自己的 id，
	 * 破坏掉落用的战利品表路径（{@code <ns>:blocks/<name>}）就是从这里推出来的。
	 * 漏掉它会在放置方块时抛异常。
	 */
	private static <T extends Block> T register(String name, Function<BlockBehaviour.Properties, T> factory,
			BlockBehaviour.Properties properties) {
		ResourceKey<Block> key = blockKey(name);
		T block = factory.apply(properties.setId(key));
		Registry.register(BuiltInRegistries.BLOCK, key, block);
		registerBlockItem(name, block);
		return block;
	}

	private static void registerBlockItem(String name, Block block) {
		ResourceKey<Item> key = itemKey(name);
		Item item = new BlockItem(block, new Item.Properties().setId(key).useBlockDescriptionPrefix());
		Registry.register(BuiltInRegistries.ITEM, key, item);
	}

	/**
	 * 供「没有对应方块的物品」或自定义物品使用。
	 *
	 * <p>目前本模组还没有这样的物品，但保留入口 —— 后续要加机器零件时不用再翻一遍注册写法。
	 */
	public static <T extends Item> T registerItem(String name, BiFunction<ResourceKey<Item>, Item.Properties, T> factory) {
		ResourceKey<Item> key = itemKey(name);
		T item = factory.apply(key, new Item.Properties().setId(key));
		Registry.register(BuiltInRegistries.ITEM, key, item);
		return item;
	}
}
