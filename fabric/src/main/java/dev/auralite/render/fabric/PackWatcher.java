package dev.auralite.render.fabric;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;

/**
 * Pack hot-reload: filesystem watch plus a debounce, surfaced as a flag the
 * frame loop consumes at a frame boundary.
 *
 * <p>Deliberately dumb about what changed: any create/modify/delete under the
 * pack root marks the pack dirty, and the renderer does one
 * {@code waitIdle() + reload} at the next frame edge. Reloading mid-frame
 * would race the GPU; reloading on every individual file event during a
 * `git checkout` would reload forty times - hence the debounce window.
 *
 * <p>If the watch cannot be established the watcher disables itself and says
 * so; a hot-reload feature that silently does nothing is a lie the log must
 * prevent.
 */
public final class PackWatcher implements AutoCloseable {

    private static final long DEBOUNCE_NANOS = 250_000_000L;

    private final WatchService service;
    private final long debounceNanos;
    private volatile boolean dirty;
    private long lastEventNanos;
    private boolean enabled = true;

    public PackWatcher(Path packRoot) throws IOException {
        this(packRoot, DEBOUNCE_NANOS);
    }

    public PackWatcher(Path packRoot, long debounceNanos) throws IOException {
        this.debounceNanos = debounceNanos;
        Path root = packRoot.toAbsolutePath().normalize();
        Path watchRoot = Files.isDirectory(root) ? root : root.getParent();
        if (watchRoot == null || !Files.isDirectory(watchRoot)) {
            throw new IOException("pack root '" + root + "' does not exist");
        }
        this.service = FileSystems.getDefault().newWatchService();
        watchRoot.register(service,
                StandardWatchEventKinds.ENTRY_CREATE,
                StandardWatchEventKinds.ENTRY_MODIFY,
                StandardWatchEventKinds.ENTRY_DELETE);
        // A pack tree changes one file at a time; watch the subtree cheaply by
        // polling the key after each event batch (sub-directories re-register).
        registerSubtree(root);
    }

    private void registerSubtree(Path root) throws IOException {
        // Best-effort recursive registration; flat packs (the common case) are
        // covered by the root registration alone.
        try (java.util.stream.Stream<Path> s = Files.walk(root)) {
            s.filter(Files::isDirectory).forEach(dir -> {
                try {
                    dir.register(service,
                            StandardWatchEventKinds.ENTRY_CREATE,
                            StandardWatchEventKinds.ENTRY_MODIFY,
                            StandardWatchEventKinds.ENTRY_DELETE);
                } catch (IOException ignored) {
                    // A directory that vanished between walk and register is fine.
                }
            });
        }
    }

    /**
     * Drains pending events. Returns true when a reload is due (debounced).
     * Call once per frame boundary.
     */
    public boolean pollReloadDue() {
        if (!enabled) return false;
        boolean sawEvent = false;
        for (WatchKey key = service.poll(); key != null; key = service.poll()) {
            for (WatchEvent<?> ev : key.pollEvents()) {
                if (ev.kind() != StandardWatchEventKinds.OVERFLOW) sawEvent = true;
            }
            if (!key.reset()) {
                enabled = false;
                AuraLiteRenderMod.LOGGER.warn("pack watcher: watched directory went away; hot-reload disabled");
                return dirty;
            }
        }
        if (sawEvent) {
            dirty = true;
            lastEventNanos = System.nanoTime();
        }
        if (dirty && System.nanoTime() - lastEventNanos >= debounceNanos) {
            dirty = false;
            return true;
        }
        return false;
    }

    public boolean enabled() { return enabled; }

    @Override public void close() {
        try { service.close(); } catch (IOException ignored) { }
    }
}
