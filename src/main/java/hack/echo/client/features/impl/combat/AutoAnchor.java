package hack.echo.client.features.impl.combat;

import hack.echo.client.event.EventSubscribe;
import hack.echo.client.event.impl.EventStartUseItem;
import hack.echo.client.event.impl.EventTick;
import hack.echo.client.event.impl.MouseUpdateEvent;
import hack.echo.client.features.Category;
import hack.echo.client.features.Feature;
import hack.echo.client.features.FeatureInfo;
import hack.echo.client.features.impl.combat.autoanchor.AnchorAimSearch;
import hack.echo.client.features.settings.impl.BoolSetting;
import hack.echo.client.features.settings.impl.FloatSetting;
import hack.echo.client.features.settings.impl.IntSetting;
import hack.echo.client.features.settings.impl.ModeSetting;
import hack.echo.client.features.settings.impl.RangeSetting;
import hack.echo.client.handlers.InputHandler;
import hack.echo.client.handlers.RotationHandler;
import hack.echo.client.handlers.impl.SwapStateManager;
import hack.echo.client.api.MinecraftCompat;
import hack.echo.client.utils.blocks.BlockUtils;
import hack.echo.client.utils.combat.ExplosionUtils;
import hack.echo.client.utils.inventory.InventoryUtils;
import hack.echo.client.utils.rotation.RotationConvergenceTracker;
import hack.echo.client.utils.rotation.RotationUtils;
import hack.echo.client.utils.strings.Concat;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RespawnAnchorBlock;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import java.util.function.Predicate;

/**
 * Smart Auto Anchor - Automates anchor charge/explode cycles with smart POV-based safe/normal path selection.
 *
 * <p>Ported from Stegared's SmartAutoAnchorModule. This module detects when the player places a
 * Respawn Anchor, automatically charges it with Glowstone, then watches the player's POV movement
 * to decide between a safe path (place glowstone safety block between player and anchor before
 * detonation) or a normal path (detonate directly with totem or fallback item).</p>
 *
 * <p>State machine: IDLE → WAIT_ANCHOR → CHARGE_ANCHOR → CONFIRM_CHARGE → WATCH_POV →
 * (PLACE_SAFE_BLOCK → CONFIRM_SAFE_BLOCK → SWITCH_DETONATOR → WAIT_ANCHOR_AIM → FINISH) or
 * (SWITCH_DETONATOR → WAIT_ANCHOR_AIM → FINISH)</p>
 */
public class AutoAnchor extends Feature {

    private enum State {
        IDLE,
        WAIT_ANCHOR,
        CHARGE_ANCHOR,
        CONFIRM_CHARGE,
        WATCH_POV,
        PLACE_SAFE_BLOCK,
        CONFIRM_SAFE_BLOCK,
        SWITCH_DETONATOR,
        WAIT_ANCHOR_AIM,
        FINISH
    }

    private final BoolSetting povThreshold = new BoolSetting(Concat.of("POV Threshold"), true);
    private final FloatSetting povDegrees = new FloatSetting(Concat.of("POV Degrees"), 35.0f, 0.0f, 180.0f, 1.0f);
    private final IntSetting decisionWindow = new IntSetting(Concat.of("Decision Window"), 6, 1, 20, Concat.of(" ticks"));
    private final ModeSetting serverTiming = new ModeSetting(Concat.of("Server Timing"), Concat.of("Strict"), Concat.of("Strict"), Concat.of("Fast"));
    private final IntSetting strictActionGap = new IntSetting(Concat.of("Strict Action Gap"), 2, 1, 10, Concat.of(" ticks"));
    private final IntSetting chargeDelay = new IntSetting(Concat.of("Charge Delay"), 2, 0, 10, Concat.of(" ticks"));
    private final RangeSetting safePlaceDelay = new RangeSetting(Concat.of("Safe Place Delay"), 1.0f, 2.0f, 1.0f, 5.0f, 1.0f, Concat.of(" ticks"));
    private final RangeSetting detonationDelay = new RangeSetting(Concat.of("Detonation Delay"), 1.0f, 2.0f, 1.0f, 5.0f, 1.0f, Concat.of(" ticks"));
    private final RangeSetting slotDelay = new RangeSetting(Concat.of("Slot Delay"), 1.0f, 2.0f, 1.0f, 5.0f, 1.0f, Concat.of(" ticks"));
    private final BoolSetting preferTotem = new BoolSetting(Concat.of("Prefer Totem"), true);
    private final IntSetting fallbackSlot = new IntSetting(Concat.of("Fallback Slot"), 5, 1, 9);
    private final BoolSetting restoreSlotOnCancel = new BoolSetting(Concat.of("Restore Slot On Cancel"), true);
    private final IntSetting confirmationTimeout = new IntSetting(Concat.of("Confirmation Timeout"), 40, 10, 100, Concat.of(" ticks"));

    private State currentState = State.IDLE;
    private int stateTimer;
    private int actionCooldown;
    private int confirmationTimer;
    private int decisionTimer;
    private float startYaw;
    private float startPitch;
    private float accumulatedYawDelta;
    private float accumulatedPitchDelta;
    private boolean usePathSafe;
    private boolean interactionPerformedThisTick;
    private boolean slotChangedThisTick;
    private int originalSlot = -1;
    private int anchorX;
    private int anchorY;
    private int anchorZ;
    private BlockPos targetAnchorPos;
    private int glowstoneSlot = -1;
    private int detonatorSlot = -1;
    private int safeBlockSlot = -1;
    private final RotationConvergenceTracker convergenceTracker = new RotationConvergenceTracker();

    public AutoAnchor() {
        super(new FeatureInfo(Concat.of("Auto Anchor"), Concat.of("Automates anchor charge/explode cycles with smart POV-based safe/normal path selection"), Category.COMBAT));
        this.settings.addAll(
            povThreshold,
            povDegrees,
            decisionWindow,
            serverTiming,
            strictActionGap,
            chargeDelay,
            safePlaceDelay,
            detonationDelay,
            slotDelay,
            preferTotem,
            fallbackSlot,
            restoreSlotOnCancel,
            confirmationTimeout
        );
    }

    @EventSubscribe
    public void onTick(EventTick event) {
        if (mc.player == null || mc.level == null) {
            reset();
            return;
        }

        interactionPerformedThisTick = false;
        slotChangedThisTick = false;

        if (actionCooldown > 0) {
            actionCooldown--;
        }

        switch (currentState) {
            case IDLE:
                handleIdle();
                break;
            case WAIT_ANCHOR:
                handleWaitAnchor();
                break;
            case CHARGE_ANCHOR:
                handleChargeAnchor();
                break;
            case CONFIRM_CHARGE:
                handleConfirmCharge();
                break;
            case WATCH_POV:
                handleWatchPov();
                break;
            case PLACE_SAFE_BLOCK:
                handlePlaceSafeBlock();
                break;
            case CONFIRM_SAFE_BLOCK:
                handleConfirmSafeBlock();
                break;
            case SWITCH_DETONATOR:
                handleSwitchDetonator();
                break;
            case WAIT_ANCHOR_AIM:
                handleWaitAnchorAim();
                break;
            case FINISH:
                handleFinish();
                break;
        }
    }

    @EventSubscribe
    public void onUseItem(EventStartUseItem.Pre event) {
        if (mc.player == null) {
            return;
        }

        if (currentState == State.IDLE && event.isTargetingBlock()) {
            BlockHitResult hitResult = event.getBlockHitResult();
            if (hitResult != null) {
                BlockPos pos = hitResult.getBlockPos().relative(hitResult.getDirection());
                if (BlockUtils.isValidPlacement(pos) && BlockUtils.hasSupportingFace(pos)) {
                    targetAnchorPos = pos;
                    anchorX = pos.getX();
                    anchorY = pos.getY();
                    anchorZ = pos.getZ();
                    currentState = State.WAIT_ANCHOR;
                    stateTimer = 0;
                }
            }
        }
    }

    @EventSubscribe
    public void onMouseUpdate(MouseUpdateEvent event) {
        if (currentState == State.WATCH_POV) {
            float currentYaw = mc.gameRenderer.getCamera().getYaw();
            float currentPitch = mc.gameRenderer.getCamera().getPitch();
            float deltaYaw = Mth.wrapDegrees(currentYaw - startYaw);
            float deltaPitch = currentPitch - startPitch;
            accumulatedYawDelta += Math.abs(deltaYaw);
            accumulatedPitchDelta += Math.abs(deltaPitch);
            startYaw = currentYaw;
            startPitch = currentPitch;
        }
    }

    private void handleIdle() {
    }

    private void handleWaitAnchor() {
        stateTimer++;
        if (stateTimer > confirmationTimeout.getIntValue()) {
            reset();
            return;
        }
        if (targetAnchorPos != null && BlockUtils.isBlockAtPosition(targetAnchorPos, Blocks.RESPAWN_ANCHOR)) {
            currentState = State.CHARGE_ANCHOR;
            stateTimer = 0;
            originalSlot = mc.player.getInventory().getSelectedSlot();
            glowstoneSlot = InventoryUtils.findItemWithPredicateInHotbar(item -> item.is(Items.GLOWSTONE));
        }
    }

    private void handleChargeAnchor() {
        if (glowstoneSlot == -1) {
            reset();
            return;
        }
        stateTimer++;
        if (stateTimer < chargeDelay.getIntValue()) {
            return;
        }
        if (SwapStateManager.isOwnerActive(this)) {
            if (SwapStateManager.getActiveTargetSlot(this) == glowstoneSlot) {
                if (actionCooldown <= 0) {
                    interactWithAnchor();
                    actionCooldown = getActionGap();
                    currentState = State.CONFIRM_CHARGE;
                    stateTimer = 0;
                }
            }
            return;
        }
        SwapStateManager.swapToIfNeeded(this, glowstoneSlot, false, getSlotDelay(), false);
        slotChangedThisTick = true;
    }

    private void handleConfirmCharge() {
        stateTimer++;
        if (stateTimer > confirmationTimeout.getIntValue()) {
            reset();
            return;
        }
        if (targetAnchorPos != null && BlockUtils.isRespawnAnchorCharged(targetAnchorPos)) {
            convergenceTracker.reset();
            currentState = State.WATCH_POV;
            stateTimer = 0;
            decisionTimer = decisionWindow.getIntValue();
            startYaw = mc.gameRenderer.getCamera().getYaw();
            startPitch = mc.gameRenderer.getCamera().getPitch();
            accumulatedYawDelta = 0.0f;
            accumulatedPitchDelta = 0.0f;
        }
    }

    private void handleWatchPov() {
        decisionTimer--;
        if (decisionTimer <= 0) {
            float totalDelta = (float) Math.sqrt(accumulatedYawDelta * accumulatedYawDelta + accumulatedPitchDelta * accumulatedPitchDelta);
            if (povThreshold.isEnabled() && totalDelta >= povDegrees.getFloatValue()) {
                usePathSafe = true;
                startSafePath();
            } else {
                usePathSafe = false;
                startNormalPath();
            }
        }
    }

    private void startSafePath() {
        safeBlockSlot = findSafeBlockSlot();
        if (safeBlockSlot == -1) {
            reset();
            return;
        }
        currentState = State.PLACE_SAFE_BLOCK;
        stateTimer = 0;
    }

    private void startNormalPath() {
        detonatorSlot = findDetonatorSlot();
        if (detonatorSlot == -1) {
            reset();
            return;
        }
        currentState = State.SWITCH_DETONATOR;
        stateTimer = 0;
    }

    private void handlePlaceSafeBlock() {
        if (targetAnchorPos == null || safeBlockSlot == -1) {
            reset();
            return;
        }
        stateTimer++;
        if (stateTimer < safePlaceDelay.getRandom()) {
            return;
        }
        if (SwapStateManager.isOwnerActive(this)) {
            if (SwapStateManager.getActiveTargetSlot(this) == safeBlockSlot) {
                if (actionCooldown <= 0) {
                    placeSafetyBlock();
                    actionCooldown = getActionGap();
                    currentState = State.CONFIRM_SAFE_BLOCK;
                    stateTimer = 0;
                }
            }
            return;
        }
        SwapStateManager.swapToIfNeeded(this, safeBlockSlot, false, getSlotDelay(), false);
        slotChangedThisTick = true;
    }

    private void handleConfirmSafeBlock() {
        stateTimer++;
        if (stateTimer > confirmationTimeout.getIntValue()) {
            reset();
            return;
        }
        if (targetAnchorPos != null) {
            BlockPos safePos = getSafetyBlockPos();
            if (safePos != null && BlockUtils.isBlockAtPosition(safePos, Blocks.GLOWSTONE)) {
                detonatorSlot = findDetonatorSlot();
                if (detonatorSlot == -1) {
                    reset();
                    return;
                }
                currentState = State.SWITCH_DETONATOR;
                stateTimer = 0;
            }
        }
    }

    private void handleSwitchDetonator() {
        if (detonatorSlot == -1) {
            reset();
            return;
        }
        stateTimer++;
        if (stateTimer < slotDelay.getRandom()) {
            return;
        }
        if (SwapStateManager.isOwnerActive(this)) {
            if (SwapStateManager.getActiveTargetSlot(this) == detonatorSlot) {
                currentState = State.WAIT_ANCHOR_AIM;
                stateTimer = 0;
            }
            return;
        }
        SwapStateManager.swapToIfNeeded(this, detonatorSlot, false, getSlotDelay(), false);
        slotChangedThisTick = true;
    }

    private void handleWaitAnchorAim() {
        if (targetAnchorPos == null) {
            reset();
            return;
        }
        stateTimer++;
        if (stateTimer < detonationDelay.getRandom()) {
            return;
        }
        Vec3 eyePos = mc.player.getEyePosition(1.0f);
        Vec3 aimPoint = AnchorAimSearch.findClearAimPoint(targetAnchorPos, 1.0f);
        if (aimPoint == null) {
            reset();
            return;
        }
        if (!AnchorAimSearch.canHitAnchor(eyePos, aimPoint, targetAnchorPos)) {
            return;
        }
        if (RotationUtils.hasSilentRotation() && !RotationUtils.isControlledBy(this)) {
            return;
        }
        if (!convergenceTracker.isDuplicateRotPlaceSafe(1.0f)) {
            RotationUtils.aim(this)
                .priority(100)
                .silent()
                .speed(180.0f)
                .aimType(RotationUtils.AimType.SMOOTH)
                .to(aimPoint);
        }
        convergenceTracker.update();
        if (actionCooldown <= 0) {
            float partialTick = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
            Vec3 playerEye = mc.player.getEyePosition(partialTick);
            if (targetAnchorPos != null) {
                Vec3 anchorCenter = Vec3.atCenterOf(targetAnchorPos);
                double dist = playerEye.distanceTo(anchorCenter);
                if (dist <= mc.player.blockInteractionRange()) {
                    BlockHitResult hitResult = BlockUtils.findPlacementHit(targetAnchorPos, false);
                    if (hitResult != null) {
                        BlockUtils.interactWithBlock(hitResult, true);
                        interactionPerformedThisTick = true;
                        actionCooldown = getActionGap();
                        convergenceTracker.markInteraction();
                        if (!isStrictMode()) {
                            currentState = State.FINISH;
                            stateTimer = 0;
                        } else {
                            stateTimer = 0;
                            if (stateTimer >= getActionGap()) {
                                currentState = State.FINISH;
                                stateTimer = 0;
                            }
                        }
                    }
                }
            }
        }
    }

    private void handleFinish() {
        stateTimer++;
        if (stateTimer > 5) {
            reset();
        }
    }

    private void interactWithAnchor() {
        if (targetAnchorPos == null || mc.player == null) {
            return;
        }
        float partialTick = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        Vec3 playerEye = mc.player.getEyePosition(partialTick);
        Vec3 anchorCenter = Vec3.atCenterOf(targetAnchorPos);
        double dist = playerEye.distanceTo(anchorCenter);
        if (dist <= mc.player.blockInteractionRange()) {
            BlockHitResult hitResult = BlockUtils.findPlacementHit(targetAnchorPos, false);
            if (hitResult != null) {
                BlockUtils.interactWithBlock(hitResult, true);
                interactionPerformedThisTick = true;
                convergenceTracker.markInteraction();
            }
        }
    }

    private void placeSafetyBlock() {
        if (targetAnchorPos == null || mc.player == null) {
            return;
        }
        BlockPos safePos = getSafetyBlockPos();
        if (safePos == null) {
            return;
        }
        float partialTick = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        Vec3 playerEye = mc.player.getEyePosition(partialTick);
        Vec3 safeCenter = Vec3.atCenterOf(safePos);
        double dist = playerEye.distanceTo(safeCenter);
        if (dist <= mc.player.blockInteractionRange()) {
            BlockHitResult hitResult = BlockUtils.findPlacementHit(safePos, false);
            if (hitResult != null) {
                BlockUtils.interactWithBlock(hitResult, true);
                interactionPerformedThisTick = true;
                convergenceTracker.markInteraction();
            }
        }
    }

    private BlockPos getSafetyBlockPos() {
        if (targetAnchorPos == null || mc.player == null) {
            return null;
        }
        Vec3 eyePos = mc.player.getEyePosition(1.0f);
        Vec3 anchorCenter = Vec3.atCenterOf(targetAnchorPos);
        Vec3 dir = anchorCenter.subtract(eyePos).normalize();
        double closestDist = Double.MAX_VALUE;
        BlockPos closestPos = null;
        for (Direction facing : Direction.values()) {
            if (facing == Direction.UP || facing == Direction.DOWN) {
                continue;
            }
            BlockPos candidate = targetAnchorPos.relative(facing);
            if (BlockUtils.isValidPlacement(candidate) && BlockUtils.hasSupportingFace(candidate) && !BlockUtils.collidesWithPlayer(candidate) && !BlockUtils.collidesWithBlockingEntity(candidate)) {
                Vec3 candidateCenter = Vec3.atCenterOf(candidate);
                double dot = dir.dot(candidateCenter.subtract(eyePos).normalize());
                double dist = eyePos.distanceTo(candidateCenter);
                double score = dot - dist * 0.1;
                if (score < closestDist) {
                    closestDist = score;
                    closestPos = candidate;
                }
            }
        }
        return closestPos;
    }

    private int findSafeBlockSlot() {
        return InventoryUtils.findItemWithPredicateInHotbar(item -> item.is(Items.GLOWSTONE));
    }

    private int findDetonatorSlot() {
        if (preferTotem.isEnabled()) {
            int totemSlot = InventoryUtils.findItemWithPredicateInHotbar(item -> item.is(Items.TOTEM_OF_UNDYING));
            if (totemSlot != -1) {
                return totemSlot;
            }
        }
        return fallbackSlot.getIntValue() - 1;
    }

    private boolean isStrictMode() {
        return serverTiming.is(Concat.of("Strict"));
    }

    private int getActionGap() {
        if (isStrictMode()) {
            return strictActionGap.getIntValue();
        }
        return 1;
    }

    private int getSlotDelay() {
        return (int) slotDelay.getRandom();
    }

    private boolean isUseHeld() {
        return InputHandler.isBindDown(mc.options.keyUse);
    }

    private void reset() {
        if (restoreSlotOnCancel.isEnabled() && originalSlot != -1 && mc.player != null) {
            InventoryUtils.setInvSlot(originalSlot);
        }
        SwapStateManager.cancel(this, restoreSlotOnCancel.isEnabled());
        currentState = State.IDLE;
        stateTimer = 0;
        actionCooldown = 0;
        confirmationTimer = 0;
        decisionTimer = 0;
        startYaw = 0.0f;
        startPitch = 0.0f;
        accumulatedYawDelta = 0.0f;
        accumulatedPitchDelta = 0.0f;
        usePathSafe = false;
        interactionPerformedThisTick = false;
        slotChangedThisTick = false;
        originalSlot = -1;
        targetAnchorPos = null;
        glowstoneSlot = -1;
        detonatorSlot = -1;
        safeBlockSlot = -1;
        convergenceTracker.reset();
    }

    @Override
    public void onDisable() {
        reset();
        super.onDisable();
    }
}
