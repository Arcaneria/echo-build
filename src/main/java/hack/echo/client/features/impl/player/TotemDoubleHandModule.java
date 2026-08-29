package hack.echo.client.features.impl.player;

import hack.echo.client.event.EventSubscribe;
import hack.echo.client.event.impl.EventHandleInput;
import hack.echo.client.event.impl.EventOnAttackEntity;
import hack.echo.client.event.impl.EventPacketReceive;
import hack.echo.client.event.impl.EventSetScreen;
import hack.echo.client.features.Category;
import hack.echo.client.features.Feature;
import hack.echo.client.features.FeatureInfo;
import hack.echo.client.features.settings.impl.BoolSetting;
import hack.echo.client.features.settings.impl.IntSetting;
import hack.echo.client.features.settings.impl.ModeSetting;
import hack.echo.client.features.settings.impl.RangeSetting;
import hack.echo.client.utils.inventory.InventoryUtils;
import hack.echo.client.utils.math.TimerUtils;
import hack.echo.client.utils.strings.Concat;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityEvent;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Items;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

public class TotemDoubleHandModule extends Feature {

    private final BoolSetting checkPlayersLook = new BoolSetting(Concat.of("Check Players Look"), false);
    private final BoolSetting predictCrystals = new BoolSetting(Concat.of("Predict Crystals"), false);
    private final BoolSetting predictSword = new BoolSetting(Concat.of("Predict Sword"), false);
    private final BoolSetting predictMultiply = new BoolSetting(Concat.of("Predict Multiply"), false);
    private final BoolSetting doubleHandAfterPop = new BoolSetting(Concat.of("After Pop"), true);
    private final BoolSetting doubleHandAfterKill = new BoolSetting(Concat.of("After Kill"), true);
    private final BoolSetting switchOnOpenInv = new BoolSetting(Concat.of("Switch On Open Inv"), false);
    private final BoolSetting notWhileShielding = new BoolSetting(Concat.of("Not While Shielding"), true);

    private static final CharSequence SLOT_OFFHAND = Concat.of("Offhand");
    private static final CharSequence SLOT_1 = Concat.of("1");
    private static final CharSequence SLOT_2 = Concat.of("2");
    private static final CharSequence SLOT_3 = Concat.of("3");
    private static final CharSequence SLOT_4 = Concat.of("4");
    private static final CharSequence SLOT_5 = Concat.of("5");
    private static final CharSequence SLOT_6 = Concat.of("6");
    private static final CharSequence SLOT_7 = Concat.of("7");
    private static final CharSequence SLOT_8 = Concat.of("8");
    private static final CharSequence SLOT_9 = Concat.of("9");

    private final ModeSetting slotToSwitch = new ModeSetting(
        Concat.of("Slot To Switch"),
        SLOT_OFFHAND,
        SLOT_OFFHAND,
        SLOT_1,
        SLOT_2,
        SLOT_3,
        SLOT_4,
        SLOT_5,
        SLOT_6,
        SLOT_7,
        SLOT_8,
        SLOT_9
    );

    private final RangeSetting delay = new RangeSetting(
        Concat.of("Delay"),
        0,
        50,
        0,
        200,
        10,
        Concat.of(" ms")
    );

    private final IntSetting cooldown = new IntSetting(Concat.of("Cooldown"), 500, 0, 2000, Concat.of(" ms"));

    private final Map<UUID, Long> trackedPlayers = new HashMap<>();
    private final TimerUtils cooldownTimer = new TimerUtils();

    public TotemDoubleHandModule() {
        super(new FeatureInfo(
            Concat.of("Totem Hand"),
            Concat.of("Totem Double Hand"),
            Category.COMBAT
        ));
    }

    @Override
    public void onEnable() {
        super.onEnable();
        trackedPlayers.clear();
        cooldownTimer.reset();
    }

    @Override
    public void onDisable() {
        trackedPlayers.clear();
        super.onDisable();
    }

    @EventSubscribe
    private void onPacketReceive(EventPacketReceive event) {
        if (isNull()) return;

        if (event.getPacket() instanceof ClientboundEntityEventPacket packet) {
            if (packet.getEventId() == EntityEvent.PROTECTED_FROM_DEATH) {
                Entity entity = packet.getEntity(mc.level);
                if (!(entity instanceof Player)) return;
                if (entity != mc.player) return;

                if (!doubleHandAfterPop.getValue()) return;
                if (!isCooldownReady()) return;

                performSwap();
            }
        }
    }

    @EventSubscribe
    private void onAttackEntity(EventOnAttackEntity.Post event) {
        if (isNull()) return;
        if (!(event.getTarget() instanceof Player target)) return;

        trackedPlayers.put(target.getUUID(), System.currentTimeMillis());
    }

    @EventSubscribe
    private void onTick(EventHandleInput.Early event) {
        if (isNull()) return;

        pruneStaleTrackedPlayers();
    }

    @EventSubscribe
    private void onSetScreen(EventSetScreen event) {
        if (isNull()) return;
        if (!switchOnOpenInv.getValue()) return;
        if (!isCooldownReady()) return;

        if (event.getScreen() instanceof InventoryScreen) {
            if (hasTotemInHotbar()) {
                performSwap();
            }
        }
    }

    private void pruneStaleTrackedPlayers() {
        long current = System.currentTimeMillis();
        Iterator<Map.Entry<UUID, Long>> it = trackedPlayers.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Long> entry = it.next();
            long timestamp = entry.getValue();

            if (current - timestamp > 10000L) {
                it.remove();
                continue;
            }

            Player player = mc.level.getPlayerByUUID(entry.getKey());
            if (player == null || player.isDeadOrDying()) {
                if (doubleHandAfterKill.getValue() && isCooldownReady()) {
                    performSwap();
                }
                it.remove();
                continue;
            }

            if (mc.player.distanceTo(player) > 12.0) {
                it.remove();
            }
        }
    }

    private boolean hasTotemInHotbar() {
        return InventoryUtils.findItemWithPredicateInHotbar(
            itemStack -> itemStack.getItem() == Items.TOTEM_OF_UNDYING
        ) != -1;
    }

    private int getSlotIndex() {
        if (slotToSwitch.is(SLOT_1)) return 0;
        if (slotToSwitch.is(SLOT_2)) return 1;
        if (slotToSwitch.is(SLOT_3)) return 2;
        if (slotToSwitch.is(SLOT_4)) return 3;
        if (slotToSwitch.is(SLOT_5)) return 4;
        if (slotToSwitch.is(SLOT_6)) return 5;
        if (slotToSwitch.is(SLOT_7)) return 6;
        if (slotToSwitch.is(SLOT_8)) return 7;
        if (slotToSwitch.is(SLOT_9)) return 8;
        return -1;
    }

    private void performSwap() {
        if (notWhileShielding.getValue() && mc.player.isUsingItem()) {
            if (mc.player.getActiveItem().getItem() == Items.SHIELD) {
                return;
            }
        }

        long delayMs = (long) delay.getRandom();
        if (delayMs > 0 && !cooldownTimer.hasReached(delayMs)) {
            return;
        }

        if (slotToSwitch.is(SLOT_OFFHAND)) {
            int hotbarSlot = InventoryUtils.findItemWithPredicateInHotbar(
                itemStack -> itemStack.getItem() == Items.TOTEM_OF_UNDYING
            );
            if (hotbarSlot != -1) {
                InventoryUtils.setInvSlot(hotbarSlot);
                InventoryUtils.swapToOffhand();
            }
        } else {
            int targetSlot = getSlotIndex();
            if (targetSlot == -1) return;
            if (targetSlot == mc.player.getInventory().getSelectedSlot()) return;
            InventoryUtils.setInvSlot(targetSlot);
        }

        cooldownTimer.reset();
    }

    private boolean isCooldownReady() {
        return cooldownTimer.hasReached(cooldown.getValue());
    }
}
