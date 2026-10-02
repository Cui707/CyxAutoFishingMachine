package com.cyx.cyxautofishingmachine.gametest;

import com.cyx.cyxautofishingmachine.blockentity.AutoFishingMachineBlockEntity;
import com.cyx.cyxautofishingmachine.config.MachineConfig;
import com.cyx.cyxautofishingmachine.init.ModBlocks;
import com.cyx.cyxautofishingmachine.menu.AutoFishingMachineMenu;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;

/**
 * 界面与物品栏逻辑的自动化测试。
 *
 * <h2>为什么用 GameTest 而不是 JUnit</h2>
 * 「shift 点击会不会把物品复制出来」这件事的答案藏在
 * {@code AbstractContainerMenu#quickMoveStack} 与 {@code Slot#mayPlace} 的协作里，
 * 而这两者都要真实的 {@code ServerPlayer}、真实的方块实体和真实的容器才能跑起来。
 * GameTest 提供的正是这个环境：它会起一个无头服务端，在真实世界里执行测试方法。
 * 单元测试框架在这里够不着 —— 它无法在没有 Minecraft 运行时的情况下构造这些对象。
 *
 * <h2>怎么运行</h2>
 * <pre>{@code ./gradlew runGameTest}</pre>
 * 报告写在 {@code build/junit.xml}。测试类通过 {@code fabric.mod.json} 的
 * {@code fabric-gametest} 入口点登记。
 *
 * <h2>这些测试覆盖了什么</h2>
 * 需求文档里「无物品复制」「服务器端权威校验」这几条，在这里第一次被真正验证 ——
 * 而不是靠「读过代码，看着没问题」。
 */
public class MenuGameTests {

	/** 空结构模板是 8×8×8，这个坐标在范围内且四周有余量。 */
	private static final BlockPos MACHINE_POS = new BlockPos(1, 1, 1);

	/** 玩家主背包第一格在 {@code Inventory} 里的下标（0~8 是快捷栏）。 */
	private static final int MAIN_INV_FIRST = 9;

	/** GameTest 通过入口点反射实例化本类，必须有公开的无参构造器。 */
	public MenuGameTests() {
	}

	// ------------------------------------------------------------ 槽位准入

	/** 钓竿槽只接受钓竿。 */
	@GameTest
	public void rodSlotAcceptsOnlyFishingRods(GameTestHelper helper) {
		AutoFishingMachineBlockEntity machine = placeMachine(helper);

		require(helper, machine.canPlaceItem(MachineConfig.SLOT_ROD, new ItemStack(Items.FISHING_ROD)),
				"钓竿槽应当接受普通钓竿");
		require(helper, !machine.canPlaceItem(MachineConfig.SLOT_ROD, new ItemStack(Items.COD)),
				"钓竿槽不应当接受鱼");
		require(helper, !machine.canPlaceItem(MachineConfig.SLOT_ROD, new ItemStack(Items.STICK)),
				"钓竿槽不应当接受木棍");
		require(helper, !machine.canPlaceItem(MachineConfig.SLOT_ROD, ItemStack.EMPTY),
				"空物品堆不应当被当作合法内容");

		helper.succeed();
	}

	/** 缓存槽是机器的私有产物区，外部一律不得放入。 */
	@GameTest
	public void cacheSlotsRejectExternalItems(GameTestHelper helper) {
		AutoFishingMachineBlockEntity machine = placeMachine(helper);

		for (int index = 0; index < MachineConfig.SLOT_CACHE_COUNT; index++) {
			int slot = MachineConfig.SLOT_CACHE_START + index;
			require(helper, !machine.canPlaceItem(slot, new ItemStack(Items.COD)),
					"缓存槽 " + index + " 不应当接受外部放入的鱼");
			require(helper, !machine.canPlaceItem(slot, new ItemStack(Items.FISHING_ROD)),
					"缓存槽 " + index + " 不应当接受钓竿");
		}

		helper.succeed();
	}

	/**
	 * 菜单槽位的准入判断必须与容器完全一致。
	 *
	 * <p>这是「漏斗塞不进、界面塞得进」这类复制漏洞的防线：
	 * 两个入口若各写一套规则，迟早会分叉。这条测试把两者钉在一起。
	 */
	@GameTest
	public void menuSlotRulesMatchContainerRules(GameTestHelper helper) {
		AutoFishingMachineBlockEntity machine = placeMachine(helper);
		AutoFishingMachineMenu menu = openMenu(helper, machine);

		ItemStack[] probes = {
				new ItemStack(Items.FISHING_ROD),
				new ItemStack(Items.COD),
				new ItemStack(Items.STICK),
		};

		for (int index = 0; index < menu.machineSlotCount(); index++) {
			Slot slot = menu.getSlot(index);
			for (ItemStack probe : probes) {
				boolean viaMenu = slot.mayPlace(probe);
				boolean viaContainer = machine.canPlaceItem(slot.getContainerSlot(), probe);
				require(helper, viaMenu == viaContainer,
						"槽位 " + index + " 对 " + probe.getItem() + " 的判定不一致：界面=" + viaMenu
								+ "，容器=" + viaContainer);
			}
		}

		helper.succeed();
	}

	// ------------------------------------------------------------ 快速移动（shift 点击）

	/**
	 * 钓竿从玩家背包 shift 点击进机器：搬运过程不能让钓竿变多。
	 *
	 * <p>这是需求文档「不能通过快速点击造成物品复制」的直接验证。
	 */
	@GameTest
	public void quickMoveIntoMachineKeepsItemCount(GameTestHelper helper) {
		AutoFishingMachineBlockEntity machine = placeMachine(helper);
		Player player = makePlayer(helper);
		AutoFishingMachineMenu menu = openMenu(helper, machine, player);

		player.getInventory().setItem(MAIN_INV_FIRST, new ItemStack(Items.FISHING_ROD));

		menu.quickMoveStack(player, menuIndexForPlayerSlot(menu, MAIN_INV_FIRST));

		require(helper, !machine.getRod().isEmpty(), "钓竿应当被搬进钓竿槽");
		require(helper, countFishingRods(player) == 0, "背包里的钓竿应当被搬空");
		require(helper, totalFishingRods(player, machine) == 1,
				"钓竿总数应恒为 1，实际 " + totalFishingRods(player, machine));

		helper.succeed();
	}

	/** 钓竿从机器 shift 点击回背包：同样不能让钓竿变多。 */
	@GameTest
	public void quickMoveOutOfMachineKeepsItemCount(GameTestHelper helper) {
		AutoFishingMachineBlockEntity machine = placeMachine(helper);
		Player player = makePlayer(helper);
		AutoFishingMachineMenu menu = openMenu(helper, machine, player);

		machine.setItem(MachineConfig.SLOT_ROD, new ItemStack(Items.FISHING_ROD));

		// 机器槽位恒定排在菜单槽位表的最前面，钓竿槽就是 0 号。
		menu.quickMoveStack(player, MachineConfig.SLOT_ROD);

		require(helper, machine.getRod().isEmpty(), "钓竿应当被搬离钓竿槽");
		require(helper, countFishingRods(player) == 1, "钓竿应当出现在玩家背包里");
		require(helper, totalFishingRods(player, machine) == 1,
				"钓竿总数应恒为 1，实际 " + totalFishingRods(player, machine));

		helper.succeed();
	}

	/**
	 * 非钓竿物品 shift 点击时，机器槽位必须一个都不收。
	 *
	 * <p>这条专门防「缓存槽变成免费箱子」：玩家的鱼一旦能 shift 点击塞进缓存，
	 * 「缓存满 → 停机」的保护就被绕过去了。
	 */
	@GameTest
	public void quickMoveRejectsNonRodIntoMachine(GameTestHelper helper) {
		AutoFishingMachineBlockEntity machine = placeMachine(helper);
		Player player = makePlayer(helper);
		AutoFishingMachineMenu menu = openMenu(helper, machine, player);

		player.getInventory().setItem(MAIN_INV_FIRST, new ItemStack(Items.COD, 64));
		// 缓存里先放一份机器产物，用于确认搬运失败时不会被顺手合并掉。
		machine.setItem(MachineConfig.SLOT_CACHE_START, new ItemStack(Items.COD, 1));

		menu.quickMoveStack(player, menuIndexForPlayerSlot(menu, MAIN_INV_FIRST));

		require(helper, countItems(player, Items.COD) == 64,
				"玩家背包里的 64 条鱼应当原样留下，实际 " + countItems(player, Items.COD));
		require(helper, machine.getItem(MachineConfig.SLOT_CACHE_START).getCount() == 1,
				"缓存槽里的鱼不应当被合并或覆盖");
		require(helper, machine.getRod().isEmpty(), "钓竿槽不应当凭空出现东西");

		helper.succeed();
	}

	// ------------------------------------------------------------ 界面数据通道

	/**
	 * 界面读到的电量与电网状态必须来自方块实体。
	 *
	 * <p>顺带钉住了 {@code ContainerData} 的索引布局：存量与容量各占 4 格
	 * （原因见 {@code ContainerDataCodec}），电网状态紧随其后。
	 * 谁改了格数而忘了改 {@code getCount()}，这里立刻会越界报错。
	 */
	@GameTest
	public void containerDataExposesEnergyAndGridState(GameTestHelper helper) {
		AutoFishingMachineBlockEntity machine = placeMachine(helper);
		AutoFishingMachineMenu menu = openMenu(helper, machine);

		require(helper, menu.getCapacity() == MachineConfig.ENERGY_CAPACITY,
				"容量应当来自配置，实际 " + menu.getCapacity());
		require(helper, menu.getStored() == 0L, "初始存量应为 0，实际 " + menu.getStored());
		require(helper, !menu.isGridLinked(), "周围没有电源时不应当报告已连接");

		machine.setStored(1234L);
		require(helper, menu.getStored() == 1234L,
				"存量变化应当立刻反映到界面数据，实际 " + menu.getStored());

		// 格数必须覆盖到最后一个字段，否则 addDataSlots 读不到它。
		require(helper, machine.getCount() == AutoFishingMachineBlockEntity.DATA_COUNT,
				"getCount() 与 DATA_COUNT 不一致");

		helper.succeed();
	}

	// ------------------------------------------------------------ 辅助

	/** 在测试世界里放一台机器并取回它的方块实体。 */
	private static AutoFishingMachineBlockEntity placeMachine(GameTestHelper helper) {
		helper.setBlock(MACHINE_POS, ModBlocks.AUTO_FISHING_MACHINE);
		return helper.getBlockEntity(MACHINE_POS, AutoFishingMachineBlockEntity.class);
	}

	private static Player makePlayer(GameTestHelper helper) {
		return helper.makeMockPlayer(GameType.SURVIVAL);
	}

	/** 服务端侧打开界面：方块实体既是容器也是数据源。 */
	private static AutoFishingMachineMenu openMenu(GameTestHelper helper,
			AutoFishingMachineBlockEntity machine) {
		return openMenu(helper, machine, makePlayer(helper));
	}

	private static AutoFishingMachineMenu openMenu(GameTestHelper helper,
			AutoFishingMachineBlockEntity machine, Player player) {
		AutoFishingMachineMenu menu = new AutoFishingMachineMenu(0, player.getInventory(), machine);
		require(helper, menu.machineSlotCount() == MachineConfig.SLOT_COUNT,
				"菜单槽位数应当等于容器槽位数");
		return menu;
	}

	/**
	 * 把 {@code Inventory} 的下标换算成菜单里的槽位下标。
	 *
	 * <p>菜单的槽位表是「机器槽位在前、玩家背包在后」：背包主区（inventory 9~35）
	 * 从 {@code machineSlotCount()} 开始，快捷栏（inventory 0~8）接在主区之后。
	 */
	private static int menuIndexForPlayerSlot(AutoFishingMachineMenu menu, int inventorySlot) {
		int machineSlots = menu.machineSlotCount();
		if (inventorySlot >= 9) {
			return machineSlots + (inventorySlot - 9);
		}
		return machineSlots + 27 + inventorySlot;
	}

	private static int countFishingRods(Container container) {
		return countItems(container, Items.FISHING_ROD);
	}

	/** 数玩家背包里的钓竿。写这个重载是为了让调用处读起来是「数玩家」而不是「数背包」。 */
	private static int countFishingRods(Player player) {
		return countItems(player.getInventory(), Items.FISHING_ROD);
	}

	private static int countItems(Player player, Item item) {
		return countItems(player.getInventory(), item);
	}

	private static int countItems(Container container, Item item) {
		int total = 0;
		for (int slot = 0; slot < container.getContainerSize(); slot++) {
			ItemStack stack = container.getItem(slot);
			if (stack.is(item)) {
				total += stack.getCount();
			}
		}
		return total;
	}

	private static int totalFishingRods(Player player, Container machine) {
		return countFishingRods(player.getInventory()) + countFishingRods(machine);
	}

	/** 断言辅助：失败时抛 GameTest 自己的异常，报告里会带上这条消息。 */
	private static void require(GameTestHelper helper, boolean condition, String message) {
		if (!condition) {
			throw helper.assertionException(message);
		}
	}
}
