package dev.auralite.render.fabric;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

/**
 * In-game status screen for the render glue (opened with Right Shift).
 *
 * <p>Shows exactly what the loop knows: the one-line state (RUNNING with
 * the device name, FAILED with the refusal that stopped the frames, or
 * IDLE before the first frame), the pack directory, the successful-frame
 * count, and the last error. Buttons re-arm the device, hot-reload the
 * pack, or close. Everything runs on the render thread, so the calls are
 * direct - no queueing, no cross-thread state.
 */
public final class AuraLiteHudScreen extends Screen {

    public AuraLiteHudScreen() {
        super(Text.literal("AuraLite-Render"));
    }

    @Override
    protected void init() {
        int cx = width / 2 - 100;
        int y = height / 6 + 96;
        addDrawableChild(ButtonWidget.builder(Text.literal("Retry device"), b -> {
            RendererLoop.retry();
            close();
        }).dimensions(cx, y, 200, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Reload pack"), b -> {
            RendererLoop.requestPackReload();
            close();
        }).dimensions(cx, y + 26, 200, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Close"), b -> close())
                .dimensions(cx, y + 52, 200, 20).build());
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        renderBackground(ctx, mouseX, mouseY, delta);
        super.render(ctx, mouseX, mouseY, delta);

        int x = width / 2 - 140;
        int y = 28;
        ctx.drawTextWithShadow(textRenderer, "AuraLite-Render", x, y, 0x55FF55);
        y += 12;
        y = line(ctx, x, y, RendererLoop.statusLine());
        y = line(ctx, x, y, "frames: " + RendererLoop.framesRendered()
                + " (successful, this session)");
        y = line(ctx, x, y, "pack: config/auralite-render/pack (override with "
                + "-D" + AuraLiteRenderMod.PACK_DIR_PROPERTY + "=...)");
        String err = RendererLoop.lastError();
        if (err != null) {
            y += 4;
            y = line(ctx, x, y, "last error:");
            y = line(ctx, x, y, err);
        }
        y += 8;
        line(ctx, x, y, "Retry re-creates the device on the next frame; "
                + "Reload re-reads the pack.");
    }

    /** One logical line, wrapped to the screen width; returns the next y. */
    private int line(DrawContext ctx, int x, int y, String text) {
        for (net.minecraft.text.OrderedText wrapped
                : textRenderer.wrapLines(net.minecraft.text.Text.literal(text), width - 2 * x)) {
            ctx.drawTextWithShadow(textRenderer, wrapped, x, y, 0xE0E0E0);
            y += 11;
        }
        return y;
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}
