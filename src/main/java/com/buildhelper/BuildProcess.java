package com.buildhelper;

import net.minecraft.block.*;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import java.util.*;

public class BuildProcess {
    private final List<SchematicBlock> blocksToPlace;
    private int currentIndex = 0;
    private State currentState = State.MOVING;
    private int stuckTicks = 0;
    private BlockPos lastTarget = null;
    private int stateTimer = 0;
    private static final int INTERACT_DELAY = 8;
    private static final int STUCK_THRESHOLD = 80;
    private int stuckJumpCounter = 0;
    private BlockPos previousPos = null;
    private enum State { MOVING, PLACING, ADJUSTING, GATHERING }

    public static class SchematicBlock {
        public final BlockPos pos;
        public final BlockState state;
        public SchematicBlock(BlockPos pos, BlockState state) { this.pos = pos; this.state = state; }
    }

    public BuildProcess(MinecraftClient client) throws Exception {
        this.blocksToPlace = SchematicReader.getBlocksToPlace(client);
        if (this.blocksToPlace.isEmpty()) throw new Exception("Нет активной схемы или все блоки размещены!");
    }

    public boolean tick(MinecraftClient client) {
        if (client.player == null || currentIndex >= blocksToPlace.size()) return false;
        stateTimer++;
        SchematicBlock target = blocksToPlace.get(currentIndex);
        return switch (currentState) {
            case MOVING -> handleMoving(client, target);
            case PLACING -> handlePlacing(client, target);
            case ADJUSTING -> handleAdjusting(client, target);
            case GATHERING -> handleGathering(client, target);
        };
    }

    private boolean handleMoving(MinecraftClient client, SchematicBlock target) {
        ClientPlayerEntity player = client.player;
        Vec3d targetVec = Vec3d.ofCenter(target.pos);
        double distance = player.getPos().distanceTo(targetVec);

        // Проверка застревания
        BlockPos currentPos = player.getBlockPos();
        if (previousPos != null && previousPos.equals(currentPos)) {
            stuckTicks++;
        } else {
            stuckTicks = 0;
            stuckJumpCounter = 0;
        }
        previousPos = currentPos;

        // Пытаемся выйти из застревания
        if (stuckTicks > STUCK_THRESHOLD) {
            player.jump();
            stuckTicks = 0;
            stuckJumpCounter++;
            // Двигаемся в сторону
            if (stuckJumpCounter > 3) {
                player.setSprinting(true);
                client.options.leftKey.setPressed(true);
                if (stuckJumpCounter > 6) {
                    client.options.leftKey.setPressed(false);
                    client.options.rightKey.setPressed(true);
                }
                if (stuckJumpCounter > 10) {
                    client.options.rightKey.setPressed(false);
                    stuckJumpCounter = 0;
                }
            }
        }

        if (distance < 2.5) {
            client.options.forwardKey.setPressed(false);
            client.options.leftKey.setPressed(false);
            client.options.rightKey.setPressed(false);
            player.setSprinting(false);
            currentState = State.PLACING;
            stateTimer = 0;
            return true;
        }

        if (BaritoneIntegration.isAvailable()) {
            BaritoneIntegration.moveTo(target.pos);
        } else {
            simpleMoveTo(client, target.pos);
        }

        return true;
    }

    private void simpleMoveTo(MinecraftClient client, BlockPos target) {
        ClientPlayerEntity player = client.player;
        Vec3d targetVec = Vec3d.ofCenter(target);
        Vec3d dir = targetVec.subtract(player.getPos()).normalize();
        client.options.forwardKey.setPressed(true);

        // Плавный поворот
        float targetYaw = (float) Math.toDegrees(Math.atan2(-dir.x, dir.z));
        float targetPitch = (float) Math.toDegrees(-Math.asin(Math.min(1, Math.max(-1, dir.y))));
        float currentYaw = player.getYaw();
        float yawDiff = ((targetYaw - currentYaw) % 360 + 540) % 360 - 180;
        player.setYaw(currentYaw + yawDiff * 0.3f);
        player.setPitch(targetPitch);

        if (dir.y > 0.5 && player.isOnGround()) {
            player.jump();
        }

        // Бежим если далеко
        if (player.getPos().distanceTo(targetVec) > 5) {
            player.setSprinting(true);
        }
    }

    private boolean handlePlacing(MinecraftClient client, SchematicBlock target) {
        if (stateTimer < INTERACT_DELAY) return true;
        World world = client.player.getWorld();
        ClientPlayerEntity player = client.player;
        BlockState existing = world.getBlockState(target.pos);

        // Блок уже на месте
        if (existing.equals(target.state)) {
            advanceBlock(client);
            return true;
        }

        // Если там чужой блок — ломаем
        if (!existing.isAir() && !existing.equals(target.state)) {
            if (client.interactionManager != null) {
                client.interactionManager.attackBlock(target.pos, Direction.UP);
            }
            return true;
        }

        // Проверяем материал
        Item neededItem = target.state.getBlock().asItem();
        if (!hasItem(player, neededItem, 1)) {
            currentState = State.GATHERING;
            stateTimer = 0;
            return true;
        }

        selectSlotWithItem(player, neededItem);
        BlockHitResult hit = calculatePlacementHit(target.pos, target.state, player);

        if (client.interactionManager != null && hit != null) {
            client.interactionManager.interactBlock(player, Hand.MAIN_HAND, hit);
        }

        // Повторители требуют докликивания
        if (needsAdjustment(target.state)) {
            currentState = State.ADJUSTING;
            stateTimer = 0;
        } else {
            advanceBlock(client);
        }

        return true;
    }

    private boolean handleAdjusting(MinecraftClient client, SchematicBlock target) {
        if (stateTimer < INTERACT_DELAY * 2) return true;
        World world = client.player.getWorld();
        BlockState current = world.getBlockState(target.pos);

        if (current.equals(target.state)) {
            advanceBlock(client);
            return true;
        }

        if (current.isAir() || !current.getBlock().equals(target.state.getBlock())) {
            currentState = State.PLACING;
            stateTimer = 0;
            return true;
        }

        // Докликиваем повторитель
        if (client.interactionManager != null) {
            client.interactionManager.interactBlock(client.player, Hand.MAIN_HAND,
                new BlockHitResult(Vec3d.ofCenter(target.pos), Direction.UP, target.pos, false));
        }
        stateTimer = 0;
        return true;
    }

    private boolean handleGathering(MinecraftClient client, SchematicBlock target) {
        Item neededItem = target.state.getBlock().asItem();
        if (hasItem(client.player, neededItem, 1)) {
            currentState = State.MOVING;
            stateTimer = 0;
            return true;
        }

        BlockPos chestPos = findItemInChests(client, neededItem);
        if (chestPos == null) {
            client.player.sendMessage(Text.literal("§c[BuildHelper] Нет блока: " + neededItem.getName().getString()), false);
            return false;
        }

        if (client.player.getPos().distanceTo(Vec3d.ofCenter(chestPos)) > 2.5) {
            if (BaritoneIntegration.isAvailable()) BaritoneIntegration.moveTo(chestPos);
            else simpleMoveTo(client, chestPos);
            return true;
        }

        openChestAndTakeItems(client, chestPos, neededItem);
        if (hasItem(client.player, neededItem, 1)) {
            client.player.sendMessage(Text.literal("§a[BuildHelper] Взял: " + neededItem.getName().getString()), false);
            currentState = State.MOVING;
            stateTimer = 0;
        }
        return true;
    }

    private void advanceBlock(MinecraftClient client) {
        currentIndex++;
        currentState = State.MOVING;
        stateTimer = 0;
        stuckTicks = 0;
        stuckJumpCounter = 0;
        previousPos = null;
        client.options.forwardKey.setPressed(false);
        client.options.leftKey.setPressed(false);
        client.options.rightKey.setPressed(false);
        client.player.setSprinting(false);
    }

    private boolean hasItem(PlayerEntity player, Item item, int count) {
        int found = 0;
        for (int i = 0; i < player.getInventory().size(); i++)
            if (player.getInventory().getStack(i).getItem().equals(item))
                found += player.getInventory().getStack(i).getCount();
        return found >= count;
    }

    private void selectSlotWithItem(PlayerEntity player, Item item) {
        for (int i = 0; i < 9; i++)
            if (player.getInventory().getStack(i).getItem().equals(item)) {
                player.getInventory().selectedSlot = i; return;
            }
        for (int i = 9; i < 36; i++)
            if (player.getInventory().getStack(i).getItem().equals(item)) {
                player.getInventory().swapSlotWithHotbar(i); return;
            }
    }

    private BlockHitResult calculatePlacementHit(BlockPos pos, BlockState state, PlayerEntity player) {
        World world = player.getWorld();
        // Ищем любой соседний блок для опоры
        for (Direction dir : Direction.values()) {
            BlockPos neighbor = pos.offset(dir);
            if (!world.getBlockState(neighbor).isAir()) {
                return new BlockHitResult(
                    Vec3d.ofCenter(neighbor),
                    dir.getOpposite(),
                    neighbor,
                    false
                );
            }
        }
        // Нет соседей — кликаем снизу
        return new BlockHitResult(
            Vec3d.ofCenter(pos.down()),
            Direction.UP,
            pos.down(),
            false
        );
    }

    private boolean needsAdjustment(BlockState state) {
        return state.getBlock() instanceof RepeaterBlock || state.getBlock() instanceof ComparatorBlock;
    }

    private BlockPos findItemInChests(MinecraftClient client, Item item) {
        BlockPos playerPos = client.player.getBlockPos();
        for (BlockPos pos : BlockPos.iterateOutwards(playerPos, 32, 32, 32))
            if (client.player.getWorld().getBlockState(pos).getBlock() instanceof ChestBlock)
                return pos.toImmutable();
        return null;
    }

    private void openChestAndTakeItems(MinecraftClient client, BlockPos chestPos, Item item) {
        ClientPlayerEntity player = client.player;
        if (client.interactionManager != null)
            client.interactionManager.interactBlock(player, Hand.MAIN_HAND,
                new BlockHitResult(Vec3d.ofCenter(chestPos), Direction.UP, chestPos, false));
        if (player.currentScreenHandler instanceof GenericContainerScreenHandler chest) {
            Inventory inv = chest.getInventory();
            for (int i = 0; i < inv.size(); i++)
                if (!inv.getStack(i).isEmpty() && inv.getStack(i).getItem().equals(item))
                    client.interactionManager.clickSlot(chest.syncId, i, 0,
                        net.minecraft.screen.slot.SlotActionType.QUICK_MOVE, player);
            client.setScreen(null);
        }
    }

    public void stop() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player != null) {
            client.options.forwardKey.setPressed(false);
            client.options.leftKey.setPressed(false);
            client.options.rightKey.setPressed(false);
            client.player.setSprinting(false);
        }
        BaritoneIntegration.stop();
    }
                }
