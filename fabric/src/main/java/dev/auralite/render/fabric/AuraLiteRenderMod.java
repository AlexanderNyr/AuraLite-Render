package dev.auralite.render.fabric;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Fabric entrypoint for AuraLite-Render (ROADMAP Phase 4).
 *
 * <p>What this module is: the window swapchain, frame pacing, pack
 * hot-reload and RenderDoc-friendly labelling that the BIG_PLAN assigns to
 * "fabric glue". What it is not: a verified frame. This sandbox has no
 * JDK 21 and no GPU, so the host build SKIPs this module on purpose and
 * the fabric suite gates only the static facts (sources + json present).
 * The windowed-frame gate (Phase 4) and the GPU behaviour gates (Phase 5)
 * stay unclaimed until they have been run where a GPU exists.
 */
public final class AuraLiteRenderMod implements ClientModInitializer {

    public static final String MOD_ID = "auralite_render";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    /** Pack directory to watch; overridden with -Dauralite.packDir=... */
    public static final String PACK_DIR_PROPERTY = "auralite.packDir";

    @Override
    public void onInitializeClient() {
        LOGGER.info("AuraLite-Render glue initializing (client); "
                + "device creation is deferred to the first rendered frame");
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> RendererLoop.shutdown());

        // Right Shift opens the status screen (state, retry, pack reload).
        KeyBinding menu = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.auralite_render.menu", InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_RIGHT_SHIFT, "category.auralite_render"));
        // F8 toggles the pack's output even when the gradient covers the GUI.
        KeyBinding toggle = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.auralite_render.toggle_present", InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_F8, "category.auralite_render"));
        // F9 cycles the pack's debug view over the captured vanilla frame.
        KeyBinding debugView = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.auralite_render.cycle_debug_view", InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_F9, "category.auralite_render"));
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (menu.wasPressed()) {
                if (client.currentScreen == null) {
                    client.setScreen(new AuraLiteHudScreen());
                }
            }
            while (toggle.wasPressed()) {
                RendererLoop.togglePresent();
            }
            while (debugView.wasPressed()) {
                RendererLoop.cycleDebugView();
            }
        });
    }
}
