package com.buildhelper;

import net.minecraft.block.*;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.state.property.Properties;
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
    private int stateTimer = 0;
    private static final int INTERACT_DELAY = 8;
    private static final int STUCK_THRESHOLD = 60;

    // Для движения
    private BlockPos lastTarget = null;
    private int stuckTicks = 0;
    private int stuckJumpCounter = 0;
    private BlockPos previousPos = null;
    private List<BlockPos> currentPath = null;
    private int pathIndex = 0;

    // Для повторителей
    private int adjustAttempts = 0;
    private static final int MAX_ADJUST_ATTEMPTS = 16;

    private enum State { MOVING, PLACING, ADJUSTING, GATHERING }

    public static class SchematicBlock {
        public final BlockPos pos;
        public final BlockState state;
        public SchematicBlock(BlockPos pos, BlockState state) { this.pos = pos; this.state = state; }
    }

    public BuildProcess(MinecraftClient client) throws Exception {
        this.blocksToPlace = SchematicReader.getBlocksToPlace(client);
        if (this.blocksToPlace.isEmpty()) throw new Exception("Нет активной схемы!");
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

    // ==================== ДВИЖЕНИЕ (УЛУЧШЕННЫЙ PATHFINDER) ====================

    private boolean handleMoving(MinecraftClient client, SchematicBlock target) {
        ClientPlayerEntity player = client.player;
        double distance = player.getPos().distanceTo(Vec3d.ofCenter(target.pos));

        // Проверка застревания
        BlockPos currentPos = player.getBlockPos();
        if (previousPos != null && previousPos.equals(currentPos)) stuckTicks++;
        else { stuckTicks = 0; stuckJumpCounter = 0; }
        previousPos = currentPos;

        if (stuckTicks > STUCK_THRESHOLD) {
            handleStuck(player);
        }

        if (distance < 2.5) {
            resetMovement(client);
            currentState = State.PLACING;
            stateTimer = 0;
            currentPath = null;
            return true;
        }

        // Строим путь если нет или цель изменилась
        if (currentPath == null || !target.pos.equals(lastTarget)) {
            currentPath = findPath(client, player.getBlockPos(), target.pos);
            pathIndex = 0;
            lastTarget = target.pos;
        }

        if (currentPath != null && pathIndex < currentPath.size()) {
            moveAlongPath(client);
        } else {
            // Запасной вариант — идти прямо
            simpleMoveTo(client, target.pos);
        }

        return true;
    }

    private List<BlockPos> findPath(MinecraftClient client, BlockPos from, BlockPos to) {
        // Упрощённый A* для ближней дистанции
        List<BlockPos> path = new ArrayList<>();
        Set<BlockPos> closed = new HashSet<>();
        Queue<Node> open = new PriorityQueue<>(Comparator.comparingDouble(n -> n.cost + n.distTo(to)));
        Map<BlockPos, BlockPos> cameFrom = new HashMap<>();

        open.add(new Node(from, 0));
        int maxIter = 500;

        while (!open.isEmpty() && maxIter-- > 0) {
            Node current = open.poll();
            if (current.pos.equals(to)) {
                // Восстанавливаем путь
                BlockPos p = to;
                while (p != null) {
                    path.add(0, p);
                    p = cameFrom.get(p);
                }
                return path;
            }
            closed.add(current.pos);

            for (Direction dir : Direction.values()) {
                if (dir == Direction.DOWN) continue; // Не копаем вниз
                BlockPos next = current.pos.offset(dir);
                if (closed.contains(next)) continue;
                if (!canWalkThrough(client, next)) continue;

                double newCost = current.cost + (dir == Direction.UP ? 2 : 1);
                open.add(new Node(next, newCost));
                cameFrom.put(next, current.pos);
            }
        }
        return null; // Путь не найден
    }

    private boolean canWalkThrough(MinecraftClient client, BlockPos pos) {
        World world = client.player.getWorld();
        BlockState state = world.getBlockState(pos);
        BlockState above = world.getBlockState(pos.up());

        // Можно пройти если блок проходимый и над ним есть место
        return state.isAir() || state.getCollisionShape(world, pos).isEmpty()
            || state.getBlock() instanceof DoorBlock
            || state.getBlock() instanceof FenceGateBlock
            || state.getBlock() instanceof TrapdoorBlock
            || state.getBlock() instanceof CarpetBlock
            || state.getBlock() instanceof PressurePlateBlock;
    }

    private void moveAlongPath(MinecraftClient client) {
        ClientPlayerEntity player = client.player;
        if (pathIndex >= currentPath.size()) return;

        BlockPos target = currentPath.get(pathIndex);
        double dist = player.getPos().distanceTo(Vec3d.ofCenter(target).add(0, 0, 0));

        if (dist < 0.8) {
            pathIndex++;
            return;
        }

        Vec3d dir = Vec3d.ofCenter(target).subtract(player.getPos()).normalize();
        client.options.forwardKey.setPressed(true);

        float targetYaw = (float) Math.toDegrees(Math.atan2(-dir.x, dir.z));
        float currentYaw = player.getYaw();
        float diff = ((targetYaw - currentYaw) % 360 + 540) % 360 - 180;
        player.setYaw(currentYaw + diff * 0.4f);

        // Прыжок если нужно подняться
        if (dir.y > 0.3 && player.isOnGround()
            && !client.player.getWorld().getBlockState(target).isAir()) {
            player.jump();
        }

        // Бег
        if (player.getPos().distanceTo(Vec3d.ofCenter(currentPath.get(currentPath.size() - 1))) > 6) {
            player.setSprinting(true);
        }
    }

    private void handleStuck(ClientPlayerEntity player) {
        player.jump();
        stuckTicks = 0;
        stuckJumpCounter++;
        player.setSprinting(true);
        if (stuckJumpCounter % 2 == 0) {
            MinecraftClient.getInstance().options.leftKey.setPressed(true);
        } else {
            MinecraftClient.getInstance().options.leftKey.setPressed(false);
            MinecraftClient.getInstance().options.rightKey.setPressed(true);
        }
    }

    private void simpleMoveTo(MinecraftClient client, BlockPos target) {
        ClientPlayerEntity player = client.player;
        Vec3d dir = Vec3d.ofCenter(target).subtract(player.getPos()).normalize();
        client.options.forwardKey.setPressed(true);
        float yaw = (float) Math.toDegrees(Math.atan2(-dir.x, dir.z));
        player.setYaw(yaw);
        if (dir.y > 0.3 && player.isOnGround()) player.jump();
    }

    // ==================== УСТАНОВКА БЛОКОВ ====================

    private boolean handlePlacing(MinecraftClient client, SchematicBlock target) {
        if (stateTimer < INTERACT_DELAY) return true;
        World world = client.player.getWorld();
        ClientPlayerEntity player = client.player;
        BlockState existing = world.getBlockState(target.pos);

        if (existing.equals(target.state)) { advanceBlock(client); return true; }

        if (!existing.isAir() && !existing.equals(target.state)) {
            if (client.interactionManager != null)
                client.interactionManager.attackBlock(target.pos, Direction.UP);
            return true;
        }

        Item neededItem = target.state.getBlock().asItem();
        if (!hasItem(player, neededItem, 1)) {
            currentState = State.GATHERING; stateTimer = 0; return true;
        }

        selectSlotWithItem(player, neededItem);

        // Выбираем правильную сторону для клика
        BlockHitResult hit = findBestPlacementHit(client, target);
        if (client.interactionManager != null && hit != null) {
            client.interactionManager.interactBlock(player, Hand.MAIN_HAND, hit);
        }

        if (needsAdjustment(target.state)) {
            currentState = State.ADJUSTING;
            stateTimer = 0;
            adjustAttempts = 0;
        } else {
            advanceBlock(client);
        }
        return true;
    }

    private BlockHitResult findBestPlacementHit(MinecraftClient client, SchematicBlock target) {
        World world = client.player.getWorld();

        // Приоритет: кликаем по блоку снизу (как в обычной установке)
        if (!world.getBlockState(target.pos.down()).isAir()) {
            return new BlockHitResult(Vec3d.ofCenter(target.pos.down()), Direction.UP, target.pos.down(), false);
        }

        // Ищем любой соседний блок
        for (Direction dir : Direction.values()) {
            BlockPos neighbor = target.pos.offset(dir);
            if (!world.getBlockState(neighbor).isAir()) {
                return new BlockHitResult(Vec3d.ofCenter(neighbor), dir.getOpposite(), neighbor, false);
            }
        }

        // Кликаем снизу даже если воздуха нет (может сработать)
        return new BlockHitResult(Vec3d.ofCenter(target.pos.down()), Direction.UP, target.pos.down(), false);
    }

    // ==================== ПОВТОРИТЕЛИ ====================

    private boolean handleAdjusting(MinecraftClient client, SchematicBlock target) {
        if (stateTimer < 4) return true; // Ждём установки

        World world = client.player.getWorld();
        BlockState current = world.getBlockState(target.pos);

        // Уже правильно
        if (current.equals(target.state)) {
            advanceBlock(client);
            adjustAttempts = 0;
            return true;
        }

        // Блок пропал или сменился
        if (current.isAir() || !(current.getBlock() instanceof RepeaterBlock)
            && !(current.getBlock() instanceof ComparatorBlock)) {
            currentState = State.PLACING;
            stateTimer = 0;
            adjustAttempts = 0;
            return true;
        }

        // Превысили попытки — пропускаем
        if (adjustAttempts >= MAX_ADJUST_ATTEMPTS) {
            advanceBlock(client);
            adjustAttempts = 0;
            return true;
        }

        // Кликаем для изменения состояния
        if (client.interactionManager != null) {
            client.interactionManager.interactBlock(client.player, Hand.MAIN_HAND,
                new BlockHitResult(Vec3d.ofCenter(target.pos), Direction.UP, target.pos, false));
        }
        adjustAttempts++;
        stateTimer = 0;
        return true;
    }

    // ==================== СБОР РЕСУРСОВ ====================

    private boolean handleGathering(MinecraftClient client, SchematicBlock target) {
        Item neededItem = target.state.getBlock().asItem();
        if (hasItem(client.player, neededItem, 1)) {
            currentState = State.MOVING; stateTimer = 0; return true;
        }
        BlockPos chestPos = findNearestChest(client);
        if (chestPos == null) {
            client.player.sendMessage(Text.literal("§c[BuildHelper] Нет блока: " + neededItem.getName().getString()), false);
            return false;
        }
        if (client.player.getPos().distanceTo(Vec3d.ofCenter(chestPos)) > 2.5) {
            simpleMoveTo(client, chestPos);
            return true;
        }
        openChestAndTakeItems(client, chestPos, neededItem);
        if (hasItem(client.player, neededItem, 1)) {
            client.player.sendMessage(Text.literal("§a[BuildHelper] Взял: " + neededItem.getName().getString()), false);
            currentState = State.MOVING; stateTimer = 0;
        }
        return true;
    }

    // ==================== ВСПОМОГАТЕЛЬНЫЕ МЕТОДЫ ====================

    private void advanceBlock(MinecraftClient client) {
        currentIndex++;
        currentState = State.MOVING;
        stateTimer = 0;
        stuckTicks = 0;
        stuckJumpCounter = 0;
        previousPos = null;
        currentPath = null;
        resetMovement(client);
    }

    private void resetMovement(MinecraftClient client) {
        client.options.forwardKey.setPressed(false);
        client.options.leftKey.setPressed(false);
        client.options.rightKey.setPressed(false);
        if (client.player != null) client.player.setSprinting(false);
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

    private boolean needsAdjustment(BlockState state) {
        return state.getBlock() instanceof RepeaterBlock || state.getBlock() instanceof ComparatorBlock;
    }

    private BlockPos findNearestChest(MinecraftClient client) {
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
        resetMovement(client);
        BaritoneIntegration.stop();
    }

    // Вспомогательный класс для A*
    private static class Node {
        BlockPos pos;
        double cost;
        Node(BlockPos pos, double cost) { this.pos = pos; this.cost = cost; }
        double distTo(BlockPos other) {
            return Math.abs(pos.getX() - other.getX())
                + Math.abs(pos.getY() - other.getY())
                + Math.abs(pos.getZ() - other.getZ());
        }
    }
            }
