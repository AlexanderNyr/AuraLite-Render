package dev.auralite.render.fabric;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
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
    }
}
