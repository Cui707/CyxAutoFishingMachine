package com.cyx.cyxautofishingmachine.init;

import com.cyx.cyxautofishingmachine.CyxAutoFishingMachine;
import com.cyx.cyxautofishingmachine.menu.AutoFishingMachineMenu;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;

/**
 * 菜单类型注册。
 *
 * <h2>为什么可以直接 {@code new MenuType}</h2>
 * 26.3 里 {@code MenuType} 的构造函数是包私有的，而 Fabric API 通过
 * <b>传递性访问拓宽</b>（{@code fabric-transitive-access-wideners-v1}）把它开放为
 * {@code protected}，因此模组侧可以直接构造。这与上游 CrimsonCopperGrid 以及
 * TechReborn 的做法一致。
 *
 * <h2>为什么不用 {@code ExtendedMenuType}</h2>
 * 本模组的界面不需要向客户端传方块坐标：客户端那条构造器会自建一个等尺寸的空壳容器，
 * 物品靠槽位同步包填入。所以原版 {@code MenuType} 的 {@code (int, Inventory)} 工厂签名
 * 就够了，没必要引入额外的网络负载。
 */
public final class ModMenuTypes {

	/** 自动钓鱼机界面。 */
	public static final MenuType<AutoFishingMachineMenu> AUTO_FISHING_MACHINE =
			register("auto_fishing_machine", AutoFishingMachineMenu::new);

	private ModMenuTypes() {
	}

	/**
	 * 触发类加载。字段初始化时完成注册，主入口显式调用一次。
	 *
	 * <p>注册后立刻回查一次注册表：菜单类型漏注册的后果是「右键没反应」，
	 * 现象安静、不报错，排查起来要从方块实体一路查到客户端，
	 * 不如启动时用一行日志把结论直接说出来。
	 */
	public static void init() {
		if (BuiltInRegistries.MENU.getValue(CyxAutoFishingMachine.id("auto_fishing_machine")) == null) {
			throw new IllegalStateException("菜单类型 auto_fishing_machine 未注册成功");
		}
		CyxAutoFishingMachine.LOGGER.info("菜单类型已注册：auto_fishing_machine");
	}

	private static <T extends AbstractContainerMenu> MenuType<T> register(String name,
			MenuType.MenuSupplier<T> factory) {
		MenuType<T> type = new MenuType<>(factory, FeatureFlags.VANILLA_SET);
		return Registry.register(BuiltInRegistries.MENU, CyxAutoFishingMachine.id(name), type);
	}
}
