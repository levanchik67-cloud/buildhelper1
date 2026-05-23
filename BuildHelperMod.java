package com.buildhelper;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class BuildHelperMod implements ModInitializer {
    public static final String MOD_ID = "buildhelper";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    private static KeyBinding toggleKey;
    private static boolean active = false;
    private static BuildProcess currentProcess = null;

    @Override
    public void onInitialize() {
        LOGGER.info("BuildHelper starting up!");
        toggleKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
            "key.buildhelper.toggle", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_F6, "category.buildhelper"));
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.player == null) return;
            while (toggleKey.wasPressed()) {
                if (!active) startBuilding(client);
                else stopBuilding(client);
            }
            if (active && currentProcess != null && !currentProcess.tick(client)) stopBuilding(client);
        });
    }

    private void startBuilding(MinecraftClient client) {
        try {
            currentProcess = new BuildProcess(client);
            active = true;
            client.player.sendMessage(Text.literal("§a[BuildHelper] Строительство запущено!"), false);
        } catch (Exception e) {
            client.player.sendMessage(Text.literal("§c[BuildHelper] Ошибка: " + e.getMessage()), false);
            active = false;
        }
    }

    private void stopBuilding(MinecraftClient client) {
        active = false;
        if (currentProcess != null) { currentProcess.stop(); currentProcess = null; }
        client.player.sendMessage(Text.literal("§e[BuildHelper] Остановлено."), false);
    }
}
