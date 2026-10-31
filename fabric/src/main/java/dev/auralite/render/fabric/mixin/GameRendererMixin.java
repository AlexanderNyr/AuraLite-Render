package dev.auralite.render.fabric.mixin;

import dev.auralite.render.fabric.RendererLoop;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The frame hook: tail of {@code GameRenderer.render}, the last point where
 * the world is drawn and the window framebuffer holds this frame's image.
 *
 * <p>MAPPING WARNING: the descriptor below is yarn 1.21.1 as best read
 * offline; this sandbox cannot resolve minecraft classes (no JDK 21 lane, no
 * Loom run). The first `./gradlew build` on a real lane either matches it or
 * fails loudly here - which is the correct failure mode for an unverified
 * descriptor, and the reason Phase 4's gate is not claimed.
 */
@Mixin(GameRenderer.class)
public class GameRendererMixin {

    @Inject(method = "render", at = @At("TAIL"))
    private void auralite$frame(long time, boolean tick, CallbackInfo ci) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.getWindow() == null) return;
        RendererLoop.frame(time, tick,
                client.getWindow().getFramebufferWidth(),
                client.getWindow().getFramebufferHeight());
    }
}
