package dev.auralite.render.util;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Which cache entries a resource owns -- the bookkeeping that lets a backend
 * drop them the moment that resource dies.
 *
 * <h2>The hole this fills</h2>
 *
 * <p>A backend caches objects keyed by a hash of the things they were built
 * from: image views keyed by (image, mip, layer), framebuffers keyed by
 * (render pass, attachments). The cache outlives the resource the key names.
 * When a graph is released the images are destroyed, but the entries that
 * referenced them stay, and a driver that recycles the freed handle hands the
 * next frame a view of someone else's memory. That is not a wrong picture: it
 * is the use-after-free the GT 710 answers by dying inside its own driver
 * (0xC0000409) two or three frames after a rebuild -- the shape the r42 fix
 * closed and the r61/r62 trees lost.
 *
 * <h2>Why the rule is here and not in the backend</h2>
 *
 * <p>The rule needs no Vulkan, no GL and no device, so it can be gated by the
 * host suite instead of by a screenshot: ownership is a map of sets, and what
 * a set means is the caller's business. The backend hands in the keys it already
 * computes and destroys exactly what {@link #purge(Object)} returns, and the
 * counters it reports are this class's, so the receipt cannot be improvised at
 * log time.
 *
 * <h2>The shared-key rule</h2>
 *
 * <p>One key can belong to several resources -- a framebuffer is built from
 * several attachments, and a render pass from several images. Dropping the
 * entry when <em>any</em> of its owners dies is the only honest reading: a
 * framebuffer with one freed attachment is not "still valid for the others".
 * Purging one resource therefore removes the whole key for every resource that
 * shared it, and the next frame rebuilds it from the resources it still has.
 */
public final class ResourceCacheOwnership<R, V> {

    private final Map<R, Set<Object>> ownedKeys = new HashMap<R, Set<Object>>();
    private final Map<Object, Set<R>> keyOwners = new HashMap<Object, Set<R>>();
    private final Map<Object, V> entries = new HashMap<Object, V>();

    /** Records that {@code key} was built from {@code resources} and holds {@code value}. */
    public void attach(Object key, V value, Collection<R> resources) {
        if (key == null) throw new IllegalArgumentException("a cached entry needs the key it is stored under");
        if (resources == null || resources.isEmpty()) {
            throw new IllegalArgumentException("an entry with no owning resource can never be invalidated");
        }
        entries.put(key, value);
        Set<R> owners = keyOwners.get(key);
        if (owners == null) {
            owners = new HashSet<R>();
            keyOwners.put(key, owners);
        }
        for (R resource : resources) {
            owners.add(resource);
            Set<Object> keys = ownedKeys.get(resource);
            if (keys == null) {
                keys = new HashSet<Object>();
                ownedKeys.put(resource, keys);
            }
            keys.add(key);
        }
    }

    /** Drops one entry without destroying it: for a cache that is being cleared wholesale. */
    public void detach(Object key) {
        Set<R> owners = keyOwners.remove(key);
        entries.remove(key);
        if (owners == null) return;
        for (R resource : owners) {
            Set<Object> keys = ownedKeys.get(resource);
            if (keys != null) {
                keys.remove(key);
                if (keys.isEmpty()) ownedKeys.remove(resource);
            }
        }
    }

    /**
     * Removes every entry that names {@code resource} and returns the values it
     * dropped, so the caller can destroy them. The entry of a co-owner goes too
     * -- see the shared-key rule.
     */
    public List<V> purge(R resource) {
        Set<Object> keys = ownedKeys.remove(resource);
        List<V> dropped = new ArrayList<V>();
        if (keys == null) return dropped;
        for (Object key : keys) {
            Set<R> owners = keyOwners.remove(key);
            if (owners == null) continue;             // already dropped by a co-owner
            // Any owner left does not make the entry valid: it was built from
            // this resource too. Drop the key for all of them.
            V value = entries.remove(key);
            if (value != null) dropped.add(value);
            for (R coOwner : owners) {
                Set<Object> coKeys = ownedKeys.get(coOwner);
                if (coKeys != null) {
                    coKeys.remove(key);
                    if (coKeys.isEmpty()) ownedKeys.remove(coOwner);
                }
            }
        }
        return dropped;
    }

    public int entryCount() { return entries.size(); }
    public int ownerCount() { return ownedKeys.size(); }

    /**
     * Whether {@code key} is registered as belonging to some live resource.
     * The backend's teardown sweep asks this while walking its cache by KEY:
     * ownership returns handles, and treating a handle as a cache key is how a
     * purge silently evicted nothing (r63's `viewsTorn=0`).
     */
    public boolean owns(Object key) { return key != null && keyOwners.containsKey(key); }

    /** Entries still standing after {@code resource} is gone (0 is the leak-free answer). */
    public int entriesFor(R resource) {
        Set<Object> keys = ownedKeys.get(resource);
        return keys == null ? 0 : keys.size();
    }

    public void clear() {
        ownedKeys.clear();
        keyOwners.clear();
        entries.clear();
    }

    /** Every live value, for a wholesale teardown. Does not empty the maps. */
    public Collection<V> values() { return entries.values(); }
}
