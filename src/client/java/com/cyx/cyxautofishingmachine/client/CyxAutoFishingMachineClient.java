package com.cyx.cyxautofishingmachine.client;

import com.cyx.cyxautofishingmachine.client.screen.AutoFishingMachineScreen;
import com.cyx.cyxautofishingmachine.init.ModMenuTypes;

import net.fabricmc.api.ClientModInitializer;

import net.minecraft.client.gui.screens.MenuScreens;

/**
 * 客户端入口：把菜单类型接到屏幕上。
 *
 * <p>26.3 里 {@code MenuScreens.register} 是私有的，Fabric API 通过传递性访问拓宽
 * （{@code fabric-transitive-access-wideners-v1}）把它开放给模组 —— 模组侧不需要做任何声明。
 *
 * <p>本模组<b>没有</b>也不应该有客户端侧的生产逻辑 ——
 * 钓鱼、扣电、耐久损耗全部由服务端的方块实体决定，客户端只负责显示服务端发来的状态。
 */
public class CyxAutoFishingMachineClient implements ClientModInitializer {

	@Override
	public void onInitializeClient() {
		MenuScreens.register(ModMenuTypes.AUTO_FISHING_MACHINE, AutoFishingMachineScreen::new);
	}
}
