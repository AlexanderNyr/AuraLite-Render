package dev.auralite.render.pack;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Read access to a pack, whether it is a directory, a {@code .zip} or an
 * in-memory map.
 *
 * <p>One abstraction for all three so the parsers and every test are identical
 * whichever way the pack arrived. Paths inside a pack always use {@code /}.
 */
public interface PackSource extends AutoCloseable {

    boolean exists(String path);

    /** Text content, or {@code null} when absent. Lines are returned as-is. */
    String readText(String path);

    byte[] readBytes(String path);

    /** All entries under {@code prefix}, in stable lexicographic order. */
    List<String> list(String prefix);

    /** Where the pack came from, for load reports and error messages. */
    String origin();

    @Override void close();

    // ------------------------------------------------------------- directory
    final class Directory implements PackSource {
        private final Path root;

        public Directory(Path root) { this.root = root; }

        private Path resolve(String path) {
            Path p = root.resolve(path.replace('/', java.io.File.separatorChar)).normalize();
            if (!p.startsWith(root)) throw new IllegalArgumentException("pack path escapes root: " + path);
            return p;
        }

        @Override public boolean exists(String path) { return Files.exists(resolve(path)); }

        @Override public String readText(String path) {
            try {
                Path p = resolve(path);
                if (!Files.exists(p)) return null;
                return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
            } catch (IOException e) {
                return null;
            }
        }

        @Override public byte[] readBytes(String path) {
            try {
                Path p = resolve(path);
                return Files.exists(p) ? Files.readAllBytes(p) : null;
            } catch (IOException e) {
                return null;
            }
        }

        @Override public List<String> list(String prefix) {
            List<String> out = new ArrayList<String>();
            Path base = resolve(prefix.isEmpty() ? "." : prefix);
            if (!Files.isDirectory(base)) return out;
            try (java.util.stream.Stream<Path> s = Files.walk(base)) {
                s.filter(Files::isRegularFile).forEach(p -> {
                    String rel = root.relativize(p).toString().replace(java.io.File.separatorChar, '/');
                    out.add(rel);
                });
            } catch (IOException e) {
                return out;
            }
            Collections.sort(out);
            return out;
        }

        @Override public String origin() { return root.toString(); }
        @Override public void close() { }
    }

    // ------------------------------------------------------------------- zip
    final class Zip implements PackSource {
        private final Path file;
        private final Map<String, byte[]> entries = new LinkedHashMap<String, byte[]>();

        public Zip(Path file) throws IOException {
            this.file = file;
            try (ZipFile z = new ZipFile(file.toFile())) {
                java.util.Enumeration<? extends ZipEntry> e = z.entries();
                while (e.hasMoreElements()) {
                    ZipEntry en = e.nextElement();
                    if (en.isDirectory()) continue;
                    ByteArrayOutputStream bos = new ByteArrayOutputStream((int) Math.max(64, en.getSize()));
                    try (InputStream in = z.getInputStream(en)) {
                        byte[] buf = new byte[8192];
                        int n;
                        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
                    }
                    entries.put(en.getName(), bos.toByteArray());
                }
            }
        }

        @Override public boolean exists(String path) { return entries.containsKey(path); }

        @Override public String readText(String path) {
            byte[] b = entries.get(path);
            return b == null ? null : new String(b, StandardCharsets.UTF_8);
        }

        @Override public byte[] readBytes(String path) { return entries.get(path); }

        @Override public List<String> list(String prefix) {
            List<String> out = new ArrayList<String>();
            for (String k : entries.keySet()) if (k.startsWith(prefix)) out.add(k);
            Collections.sort(out);
            return out;
        }

        @Override public String origin() { return file.toString(); }
        @Override public void close() { }
    }

    // ------------------------------------------------------------- in-memory
    final class Memory implements PackSource {
        private final Map<String, byte[]> files = new LinkedHashMap<String, byte[]>();
        private final String origin;

        public Memory() { this("<memory>"); }
        public Memory(String origin) { this.origin = origin; }

        public Memory put(String path, String text) {
            files.put(path, text.getBytes(StandardCharsets.UTF_8));
            return this;
        }

        public Memory put(String path, byte[] bytes) {
            files.put(path, bytes);
            return this;
        }

        @Override public boolean exists(String path) { return files.containsKey(path); }

        @Override public String readText(String path) {
            byte[] b = files.get(path);
            return b == null ? null : new String(b, StandardCharsets.UTF_8);
        }

        @Override public byte[] readBytes(String path) { return files.get(path); }

        @Override public List<String> list(String prefix) {
            List<String> out = new ArrayList<String>();
            for (String k : files.keySet()) if (k.startsWith(prefix)) out.add(k);
            Collections.sort(out);
            return out;
        }

        @Override public String origin() { return origin; }
        @Override public void close() { }
    }
}
