package com.example.playerhitbox;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;

public final class PlayerHitboxClient implements ClientModInitializer {
	public static final String MOD_ID = "playerhitbox";

	private static final KeyBinding.Category CATEGORY =
			KeyBinding.Category.create(Identifier.of(MOD_ID, "controls"));
	private static KeyBinding toggleKey;
	private static boolean enabled = true;

	public static boolean isEnabled() {
		return enabled;
	}

	@Override
	public void onInitializeClient() {
		// Клавиша включения/выключения (по умолчанию H, меняется в настройках управления)
		toggleKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
				"key.playerhitbox.toggle", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_H, CATEGORY));

		HitboxRenderer.initialize();

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			while (toggleKey.wasPressed()) {
				if (client.currentScreen == null) {
					toggle(client);
				}
			}
		});
		ClientLifecycleEvents.CLIENT_STOPPING.register(client -> HitboxRenderer.close());
	}

	private static void toggle(MinecraftClient client) {
		enabled = !enabled;
		if (client.player != null) {
			client.player.sendMessage(Text.translatable(
					enabled ? "message.playerhitbox.enabled" : "message.playerhitbox.disabled"), true);
		}
	}
}
