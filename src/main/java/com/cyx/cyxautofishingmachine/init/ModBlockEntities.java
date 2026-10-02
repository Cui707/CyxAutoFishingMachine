package com.cyx.cyxautofishingmachine.init;

import java.util.Set;

import com.cyx.cyxautofishingmachine.CyxAutoFishingMachine;
import com.cyx.cyxautofishingmachine.blockentity.AutoFishingMachineBlockEntity;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;

/**
 * 方块实体类型注册。
 *
 * <p>26.3 已经没有 {@code BlockEntityType.Builder} 了，构造函数本身就是
 * {@code (BlockEntitySupplier<T>, Set<Block>)}，所以这里直接 new。
 *
 * <h2>能量能力（{@code EnergyStorage.SIDED}）为什么不在这里登记</h2>
 * 前置 Mod 的 {@code ModPowerRegistration#init()} 注册的是一条 <b>fallback</b>，
 * 它对所有方块实体生效，判定条件是 {@code blockEntity instanceof PowerAcceptorBlockEntity}。
 * 本模组的方块实体继承的就是那个类，因此它会<b>自动</b>成为电网里的合法储能节点。
 * 这里再注册一遍不但多余，还会因为「按类型注册」覆盖掉全局 fallback 的判定，
 * 反而把「副手方向 / 每面速率」这些上游已经处理好的细节弄丢。
 *
 * <p>详见 {@code docs/UPSTREAM_ANALYSIS.md} 第 2 节。
 */
public final class ModBlockEntities {

	/** 自动钓鱼机的方块实体。 */
	public static final BlockEntityType<AutoFishingMachineBlockEntity> AUTO_FISHING_MACHINE = register(
			"auto_fishing_machine", AutoFishingMachineBlockEntity::new, ModBlocks.AUTO_FISHING_MACHINE);

	private ModBlockEntities() {
	}

	public static void init() {
	}

	private static <T extends BlockEntity> BlockEntityType<T> register(
			String name, BlockEntityType.BlockEntitySupplier<T> supplier, Block... blocks) {
		ResourceKey<BlockEntityType<?>> key = ResourceKey.create(
				Registries.BLOCK_ENTITY_TYPE, CyxAutoFishingMachine.id(name));
		BlockEntityType<T> type = new BlockEntityType<>(supplier, Set.of(blocks));
		Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE, key, type);
		return type;
	}
}
