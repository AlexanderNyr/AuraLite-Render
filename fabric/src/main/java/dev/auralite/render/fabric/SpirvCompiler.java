package dev.auralite.render.fabric;

import dev.auralite.render.rhi.GfxStage;
import dev.auralite.render.rhi.GfxException;
import dev.auralite.render.rhi.ShaderSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

/**
 * Compiles the pack's GLSL to SPIR-V with an external {@code glslangValidator}
 * - the same tool the gpu-test VK lane uses, so the fabric VK lane compiles
 * exactly the shaders the gate hashes.
 *
 * <p>Executable resolution, in order: {@code -Dauralite.glslang=/path},
 * {@code config/auralite-render/bin/glslangValidator(.exe)} (the kit's
 * {@code gpu-test-win/bin} copy belongs there), then {@code PATH}. When none
 * resolves the first compile fails by name with that instruction in the
 * message - a missing tool is a refused lane, not a crash.
 *
 * <p>Caches by source text (origin + stage + hash), so a debug-view rebuild
 * with different option defines compiles once per variant. The cache dies
 * with the provider on invalidate.
 */
final class SpirvCompiler {

    private final String executable;
    private final Path dir;
    private final Map<String, ShaderSource> cache = new HashMap<String, ShaderSource>();

    SpirvCompiler() {
        this.executable = resolveExecutable();
        try {
            this.dir = Files.createTempDirectory("auralite-spv");
        } catch (java.io.IOException e) {
            throw new GfxException("vulkan lane: cannot create a SPIR-V work dir: " + e);
        }
    }

    /** The glslangValidator to run; see the class javadoc for the order. */
    static String resolveExecutable() {
        String prop = System.getProperty("auralite.glslang");
        if (prop != null && !prop.isEmpty()) {
            Path p = Paths.get(prop);
            if (Files.isRegularFile(p)) return p.toAbsolutePath().toString();
        }
        boolean windows = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT)
                .contains("win");
        String name = windows ? "glslangValidator.exe" : "glslangValidator";
        Path inConfig = Paths.get("config/auralite-render/bin").resolve(name);
        if (Files.isRegularFile(inConfig)) return inConfig.toAbsolutePath().toString();
        return name; // PATH - ProcessBuilder resolves it, or fails by name
    }

    /**
     * {@code src} as SPIR-V, compiling through glslangValidator on a miss.
     *
     * <p>Compiles {@link ShaderSource#preprocessed()}, not the raw text: that
     * is where the pack's active option defines physically land (the GL lane
     * always went through preprocessed(); this lane used to write text()
     * straight to disk, so {@code #if DEBUG_VIEW == 1} saw an undefined
     * identifier - which the GLSL preprocessor evaluates to 0 - and F9
     * cycled the debug view in the log while the screen never changed
     * (r39). The cache key carries {@link ShaderSource#hash()}, which mixes
     * in the defines, so two variants of one stage never share an entry.
     */
    ShaderSource toSpirv(ShaderSource src) {
        if (src == null) return null;
        if (src.kind() != ShaderSource.Kind.GLSL) return src;
        String key = src.origin() + "|" + src.stage() + "|" + Long.toHexString(src.hash());
        ShaderSource hit = cache.get(key);
        if (hit != null) return hit;

        String stageArg;
        if (src.stage() == GfxStage.VERTEX) stageArg = "vert";
        else if (src.stage() == GfxStage.FRAGMENT) stageArg = "frag";
        else if (src.stage() == GfxStage.COMPUTE) stageArg = "comp";
        else throw new GfxException("vulkan lane: stage " + src.stage() + " of '"
                + src.origin() + "' has no glslangValidator stage name");

        try {
            String safe = src.origin().replace('/', '_').replace('\\', '_');
            Path in = dir.resolve(safe + "." + stageArg);
            Files.write(in, src.preprocessed().getBytes(StandardCharsets.UTF_8));
            Path out = dir.resolve(safe + "." + stageArg + ".spv");
            ProcessBuilder pb = new ProcessBuilder(
                    executable, "-V", "-S", stageArg, "-o", out.toString(), in.toString());
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            byte[] log = proc.getInputStream().readAllBytes();
            int rc = proc.waitFor();
            if (rc != 0) {
                throw new GfxException("vulkan lane: glslangValidator rejected '" + src.origin()
                        + "' (rc=" + rc + "): " + firstLines(new String(log, StandardCharsets.UTF_8), 5));
            }
            ShaderSource spv = ShaderSource.spirv(src.stage(), Files.readAllBytes(out),
                    "main", src.origin());
            cache.put(key, spv);
            return spv;
        } catch (GfxException e) {
            throw e;
        } catch (java.io.IOException e) {
            throw new GfxException("vulkan lane: cannot run glslangValidator ('" + executable
                    + "'): " + e + " - install it, put it in config/auralite-render/bin/, or set"
                    + " -Dauralite.glslang=/path/to/glslangValidator");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GfxException("vulkan lane: glslangValidator interrupted: " + e);
        }
    }

    /** Drops the compiled-stage cache (pack reload / debug-view rebuild). */
    void clearCache() {
        cache.clear();
    }

    private static String firstLines(String text, int n) {
        StringBuilder sb = new StringBuilder();
        String[] lines = text.split("\n");
        for (int i = 0; i < Math.min(n, lines.length); i++) {
            if (i > 0) sb.append(" | ");
            sb.append(lines[i].trim());
        }
        return sb.toString();
    }
}
