package dev.auralite.render.fabric.mixin;

import dev.auralite.render.fabric.RendererLoop;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.util.Window;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * The frame hook must run between the main-target draw and the buffer swap:
 * javap of 1.21.1 shows both inside MinecraftClient.render(boolean) -
 * Framebuffer.draw at offset 524, Window.swapBuffers at offset 570. An
 * @At("TAIL") hook lands AFTER the swap, so its blit never reaches the
 * screen (measured on the GT 710: backbuffer readback (0,0,0,0), vanilla
 * screenshots). This redirect runs the whole loop - render graph then the
 * present blit - right before the swap flips the frame we just drew.
 */
@Mixin(MinecraftClient.class)
public class MinecraftClientMixin {

    @Redirect(
            method = "render(Z)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/util/Window;swapBuffers()V"))
    private void auralite$presentThenSwap(Window window) {
        MinecraftClient client = MinecraftClient.getInstance();
        RendererLoop.frame(false, window.getFramebufferWidth(), window.getFramebufferHeight());
        window.swapBuffers();
    }
}
