package com.buildhelper;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.pathing.goals.GoalBlock;
import net.minecraft.util.math.BlockPos;

public class BaritoneIntegration {
    public static boolean isAvailable() {
        try {
            Class.forName("baritone.api.BaritoneAPI");
            return BaritoneAPI.getProvider() != null;
        } catch (ClassNotFoundException e) { return false; }
    }

    public static void moveTo(BlockPos pos) {
        if (!isAvailable()) return;
        IBaritone baritone = BaritoneAPI.getProvider().getPrimaryBaritone();
        if (baritone != null) baritone.getCustomGoalProcess().setGoalAndPath(new GoalBlock(pos));
    }

    public static void stop() {
        if (!isAvailable()) return;
        IBaritone baritone = BaritoneAPI.getProvider().getPrimaryBaritone();
        if (baritone != null) baritone.getPathingBehavior().cancelEverything();
    }
}
