package dev.auralite.render.graph;

/** Half-open pass-index interval during which a resource is live. */
public final class ResourceLifetime {
    public final GraphResource resource;
    public final int firstPass;
    public final int lastPass;
    /** Alias group id; resources in the same group may share one allocation. -1 = own allocation. */
    private int aliasGroup;

    public ResourceLifetime(GraphResource resource, int firstPass, int lastPass, int aliasGroup) {
        this.resource = resource;
        this.firstPass = firstPass;
        this.lastPass = lastPass;
        this.aliasGroup = aliasGroup;
    }

    public int aliasGroup() { return aliasGroup; }
    void setAliasGroup(int g) { this.aliasGroup = g; }

    public boolean overlaps(ResourceLifetime other) {
        return firstPass <= other.lastPass && other.firstPass <= lastPass;
    }

    public int span() { return lastPass - firstPass + 1; }

    @Override public String toString() {
        return resource.name() + " [" + firstPass + ".." + lastPass + "] g" + aliasGroup;
    }
}
