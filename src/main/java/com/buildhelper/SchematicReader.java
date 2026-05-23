package com.buildhelper;

import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.BlockPos;
import java.util.*;

public class SchematicReader {
    public static List<BuildProcess.SchematicBlock> getBlocksToPlace(MinecraftClient client) {
        List<BuildProcess.SchematicBlock> blocks = new ArrayList<>();
        try {
            var manager = DataManager.getSchematicPlacementManager();
            List<SchematicPlacement> placements = manager.getAllPlacements();
            if (placements.isEmpty()) return blocks;
            for (SchematicPlacement placement : placements) {
                LitematicaSchematic schematic = placement.getSchematic();
                if (schematic == null) continue;
                BlockPos origin = placement.getOrigin();
                int sizeX = schematic.getWidth();
                int sizeY = schematic.getHeight();
                int sizeZ = schematic.getLength();
                for (int x = 0; x < sizeX; x++) {
                    for (int y = 0; y < sizeY; y++) {
                        for (int z = 0; z < sizeZ; z++) {
                            BlockPos localPos = new BlockPos(x, y, z);
                            BlockState state = schematic.getBlockState(localPos);
                            if (state.isAir()) continue;
                            BlockPos worldPos = origin.add(localPos);
                            if (client.world.getBlockState(worldPos).equals(state)) continue;
                            blocks.add(new BuildProcess.SchematicBlock(worldPos, state));
                        }
                    }
                }
            }
        } catch (Exception e) {
            BuildHelperMod.LOGGER.error("Error reading schematic", e);
        }
        return blocks;
    }
}
