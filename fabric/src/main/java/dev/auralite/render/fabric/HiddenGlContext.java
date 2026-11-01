package dev.auralite.render.fabric;

import dev.auralite.render.rhi.GfxException;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GLCapabilities;

/**
 * A private OpenGL 4.6 core context on a hidden GLFW window, created with
 * the game window as the share target so textures and buffers are visible
 * to both contexts.
 *
 * <p>Why this exists: Minecraft asks GLFW for a 3.2 core context, and the
 * RHI contract is OpenGL 4.6. The render loop therefore runs the graph on
 * this context and presents by blitting a shared texture into the game's
 * framebuffer from the game's own context (see RendererLoop.present).
 *
 * <p>Context switching is explicit and render-thread only: makeCurrent()
 * remembers the previous context and its LWJGL capabilities, restore()
 * puts both back. Nothing outside this loop may run GL between the two.
 */
final class HiddenGlContext {

    private final long window;
    private long previous;
    private GLCapabilities prevCaps;
    private GLCapabilities caps;

    HiddenGlContext(long shareWindow) {
        GLFW.glfwDefaultWindowHints();
        GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
        GLFW.glfwWindowHint(GLFW.GLFW_RESIZABLE, GLFW.GLFW_FALSE);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 4);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 6);
        GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE, GLFW.GLFW_OPENGL_CORE_PROFILE);
        GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_FORWARD_COMPAT, GLFW.GLFW_TRUE);
        window = GLFW.glfwCreateWindow(1, 1, "auralite-render-hidden", 0, shareWindow);
        if (window == 0) {
            throw new GfxException("hidden OpenGL 4.6 context: glfwCreateWindow failed; "
                    + "the driver may not offer 4.6 on this machine");
        }
    }

    /** Make the hidden context current; remembers the previous one and caps. */
    void makeCurrent() {
        previous = GLFW.glfwGetCurrentContext();
        try {
            prevCaps = GL.getCapabilities();
        } catch (IllegalStateException e) {
            prevCaps = null;
        }
        GLFW.glfwMakeContextCurrent(window);
        if (caps == null) {
            caps = GL.createCapabilities();
        } else {
            GL.setCapabilities(caps);
        }
    }

    /** Restore the context (and LWJGL caps) that was current before makeCurrent. */
    void restore() {
        GLFW.glfwMakeContextCurrent(previous);
        if (prevCaps != null) {
            GL.setCapabilities(prevCaps);
        }
    }

    void destroy() {
        if (window == 0) return;
        if (GLFW.glfwGetCurrentContext() == window) {
            GLFW.glfwMakeContextCurrent(0);
        }
        GLFW.glfwDestroyWindow(window);
    }
}
