package com.buildhelper;

import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.BlockPos;
import java.util.*;

public class SchematicReader {
    public static List<BuildProcess.SchematicBlock> getBlocksToPlace(MinecraftClient client) {
        List<BuildProcess.SchematicBlock> blocks = new ArrayList<>();
        try {
            // Через рефлексию получаем Litematica
            Class<?> dataManager = Class.forName("fi.dy.masa.litematica.data.DataManager");
            Object dmInstance = dataManager.getMethod("getInstance").invoke(null);
            Object spm = dataManager.getMethod("getSchematicPlacementManager").invoke(dmInstance);
            List<?> placements = (List<?>) spm.getClass().getMethod("getAllPlacements").invoke(spm);
            
            if (placements.isEmpty()) return blocks;
            
            for (Object placement : placements) {
                Object schematic = placement.getClass().getMethod("getSchematic").invoke(placement);
                if (schematic == null) continue;
                
                BlockPos origin = (BlockPos) placement.getClass().getMethod("getOrigin").invoke(placement);
                
                // Пробуем разные методы получения размера
                int sizeX, sizeY, sizeZ;
                try {
                    sizeX = (int) schematic.getClass().getMethod("getWidth").invoke(schematic);
                    sizeY = (int) schematic.getClass().getMethod("getHeight").invoke(schematic);
                    sizeZ = (int) schematic.getClass().getMethod("getLength").invoke(schematic);
                } catch (NoSuchMethodException e) {
                    // Fallback: getEnclosingSize
                    BlockPos size = (BlockPos) schematic.getClass().getMethod("getEnclosingSize").invoke(schematic);
                    sizeX = size.getX();
                    sizeY = size.getY();
                    sizeZ = size.getZ();
                }
                
                // Пробуем получить BlockState
                for (int x = 0; x < sizeX; x++) {
                    for (int y = 0; y < sizeY; y++) {
                        for (int z = 0; z < sizeZ; z++) {
                            BlockPos localPos = new BlockPos(x, y, z);
                            BlockState state;
                            try {
                                state = (BlockState) schematic.getClass()
                                    .getMethod("getBlockState", BlockPos.class)
                                    .invoke(schematic, localPos);
                            } catch (NoSuchMethodException e2) {
                                // Fallback: через schematicWorld
                                Object sWorld = schematic.getClass().getMethod("getSchematicWorld").invoke(schematic);
                                state = (BlockState) sWorld.getClass()
                                    .getMethod("getBlockState", BlockPos.class)
                                    .invoke(sWorld, localPos);
                            }
                            
                            if (state.isAir()) continue;
                            BlockPos worldPos = origin.add(localPos);
                            if (client.world.getBlockState(worldPos).equals(state)) continue;
                            blocks.add(new BuildProcess.SchematicBlock(worldPos, state));
                        }
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("[BuildHelper] Error reading schematic: " + e.getMessage());
        }
        return blocks;
    }
}
