package com.cyx.cyxautofishingmachine;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.cyx.cyxautofishingmachine.init.ModBlockEntities;
import com.cyx.cyxautofishingmachine.init.ModBlocks;
import com.cyx.cyxautofishingmachine.init.ModCreativeTab;
import com.cyx.cyxautofishingmachine.init.ModMenuTypes;

import net.fabricmc.api.ModInitializer;

import net.minecraft.resources.Identifier;

/**
 * CyxAutoFishingMachine —— 依赖 CrimsonCopperGrid 电网的自动化钓鱼机。
 *
 * <p>公共（客户端与服务端都会加载）入口。
 *
 * <h2>初始化顺序</h2>
 * <ol>
 *   <li>注册表：方块 -&gt; 方块实体 -&gt; 物品 -&gt; 创造模式物品栏。方块实体要引用方块，顺序不能换；</li>
 *   <li>能量能力：本模组<b>不重复注册</b>能量能力 —— 详见下面的说明。</li>
 * </ol>
 *
 * <h2>为什么本模组不需要自己注册 {@code EnergyStorage.SIDED}</h2>
 * 上游的 {@code ModPowerRegistration#init()} 里注册的是一条 <b>fallback</b>：
 * <pre>{@code
 * EnergyStorage.SIDED.registerFallback((level, pos, state, blockEntity, direction) ->
 *         blockEntity instanceof PowerAcceptorBlockEntity powerAcceptor
 *                 ? powerAcceptor.getSideEnergyStorage(direction)
 *                 : null);
 * }</pre>
 * fallback 是「问遍所有注册项都没命中时的兜底」，它对<b>所有</b>方块实体生效，
 * 包括本模组新加的机器。因此只要本模组的方块实体继承
 * {@code PowerAcceptorBlockEntity}，它就会自动成为电网里的一个合法储能节点，
 * 既不需要重复注册，也不会和上游的注册产生冲突。
 *
 * <p>这条路径依赖一个前提：<b>CrimsonCopperGrid 必须先于本模组完成初始化</b>。
 * 这一点由 {@code fabric.mod.json} 里的 {@code depends.crimsoncoppergrid} 保证 ——
 * Fabric 的模组排序会先加载被依赖方。
 */
public class CyxAutoFishingMachine implements ModInitializer {

	/** 本模组的命名空间。 */
	public static final String MOD_ID = "cyxautofishingmachine";

	/** 前置模组的命名空间。 */
	public static final String UPSTREAM_MOD_ID = "crimsoncoppergrid";

	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		// 注册顺序本身就是依赖关系，不能调换：
		//   方块 -> 方块实体（要引用方块）-> 菜单（构造器要引用方块实体）-> 创造栏（要引用方块物品）
		// 每一步都显式调用 init()，而不是靠「谁先被引用谁先加载」——
		// 那种写法在重构时极容易静默失效。
		ModBlocks.init();
		ModBlockEntities.init();
		ModMenuTypes.init();
		ModCreativeTab.init();

		LOGGER.info("CyxAutoFishingMachine 初始化完成：自动钓鱼机方块与方块实体已注册");
	}

	/** 便捷方法：按本模组命名空间生成 Identifier。 */
	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}

	/** 便捷方法：按前置模组命名空间生成 Identifier。 */
	public static Identifier upstreamId(String path) {
		return Identifier.fromNamespaceAndPath(UPSTREAM_MOD_ID, path);
	}
}
