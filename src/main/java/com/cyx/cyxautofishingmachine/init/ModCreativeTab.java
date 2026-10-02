package com.cyx.cyxautofishingmachine.init;

import com.cyx.cyxautofishingmachine.CyxAutoFishingMachine;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;

/**
 * 创造模式物品栏。
 *
 * <h2>为什么单开一栏，而不是塞进前置 Mod 的栏里</h2>
 * 前置 Mod 的创造栏在注册时就把 {@code displayItems} 那个 lambda 定死了，
 * 外部想往里加条目只能改它的源码；而需求里明确「不得修改 CrimsonCopperGrid 的原始源码」。
 * 所以本模组自建一栏 —— 顺带也符合「独立 Mod」的定位。
 *
 * <p>用的是原版 {@code CreativeModeTab} 构建器，不依赖 Fabric API 的物品栏事件，
 * 注册时机与其它注册项完全一致，不引入额外的时序假设。
 */
public final class ModCreativeTab {

	public static final ResourceKey<CreativeModeTab> KEY = ResourceKey.create(
			Registries.CREATIVE_MODE_TAB, CyxAutoFishingMachine.id("main"));

	public static final CreativeModeTab TAB = CreativeModeTab.builder(CreativeModeTab.Row.TOP, 11)
			.title(Component.translatable("itemGroup.cyxautofishingmachine.main"))
			.icon(() -> new ItemStack(ModBlocks.AUTO_FISHING_MACHINE))
			.displayItems((parameters, output) -> {
				output.accept(ModBlocks.AUTO_FISHING_MACHINE);
			})
			.build();

	private ModCreativeTab() {
	}

	public static void init() {
		Registry.register(BuiltInRegistries.CREATIVE_MODE_TAB, KEY, TAB);
	}
}
