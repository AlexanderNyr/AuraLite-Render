package dev.auralite.render.fabric.mixin;

import dev.auralite.render.fabric.RendererLoop;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.RenderTickCounter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The frame hook: tail of {@code GameRenderer.render}, the last point where
 * the world is drawn and the window framebuffer holds this frame's image.
 *
 * <p>MAPPING NOTE: 1.21.1's render takes a RenderTickCounter (yarn), not the
 * pre-1.21 long nanotime. The offline-written long descriptor failed exactly
 * as its warning predicted - on a real 1.21.1 client mixin rejected it with
 * 'Expected (Lnet/minecraft/class_9779;Z...' - fixed from that runtime
 * receipt; the counter is unused here (delta time comes from System.nanoTime
 * inside RendererLoop).
 */
@Mixin(GameRenderer.class)
public class GameRendererMixin {

    @Inject(method = "render", at = @At("TAIL"))
    private void auralite$frame(RenderTickCounter tickCounter, boolean tick, CallbackInfo ci) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.getWindow() == null) return;
        RendererLoop.frame(tick,
                client.getWindow().getFramebufferWidth(),
                client.getWindow().getFramebufferHeight());
    }
}
