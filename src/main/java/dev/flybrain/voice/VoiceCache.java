package dev.flybrain.voice;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Collection;
import java.util.HashSet;
import java.util.UUID;

/** Shared client cache scoped by connection identity; authoritative manifests remove offline-deleted clips. */
public final class VoiceCache {
    public record Entry(UUID owner, UUID id, int size, String hash) {
        public Entry {
            if (owner == null || id == null || size < 44 + VoiceAudio.MIN_SAMPLES * 2 || size > VoiceAudio.MAX_WAV_BYTES || !hash.matches("[0-9a-f]{64}"))
                throw new IllegalArgumentException("Invalid voice cache entry");
        }
    }
    private final Path root, budgetRoot;
    public static final long WORLD_BUDGET = 64L * 1024 * 1024, TOTAL_BUDGET = 256L * 1024 * 1024;
    public VoiceCache(Path root) throws IOException { this(root, root); }
    public VoiceCache(Path root, Path budgetRoot) throws IOException {
        this.root = root.toAbsolutePath().normalize(); this.budgetRoot = budgetRoot.toAbsolutePath().normalize();
        if (!this.root.startsWith(this.budgetRoot)) throw new IllegalArgumentException("Invalid cache root");
        Files.createDirectories(this.root);
    }
    public boolean contains(Entry entry) throws IOException {
        Path path = path(entry.owner(), entry.id());
        return Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && Files.size(path) == entry.size() && VoiceAudio.hash(VoiceLibrary.readBounded(path)).equals(entry.hash());
    }
    public void put(Entry entry, byte[] data) throws IOException {
        if (data.length != entry.size() || !VoiceAudio.hash(data).equals(entry.hash())) throw new IllegalArgumentException("Cache checksum/size mismatch");
        VoiceAudio.pcm(data);
        Path destination = path(entry.owner(), entry.id());
        long replaced = Files.isRegularFile(destination, LinkOption.NOFOLLOW_LINKS) ? Files.size(destination) : 0;
        if (diskBytes(root) - replaced + data.length > WORLD_BUDGET || diskBytes(budgetRoot) - replaced + data.length > TOTAL_BUDGET)
            throw new IOException("Voice cache storage cap reached (64 MiB/world, 256 MiB total); no old clips deleted");
        VoiceLibrary.atomic(destination, data);
    }
    public void delete(UUID owner, UUID id) throws IOException { Files.deleteIfExists(path(owner, id)); }
    public void reconcile(Collection<Entry> manifest) throws IOException {
        if (manifest.size() > VoiceLibrary.MAX_CLIPS) throw new IllegalArgumentException("Manifest too large");
        var keep = new HashSet<Path>(); for (var entry : manifest) keep.add(path(entry.owner(), entry.id()));
        try (var owners = Files.list(root)) {
            for (var owner : owners.toList()) {
                if (!Files.isDirectory(owner, LinkOption.NOFOLLOW_LINKS)) continue;
                try { UUID.fromString(owner.getFileName().toString()); } catch (IllegalArgumentException ignored) { continue; }
                try (var paths = Files.list(owner)) {
                    for (var file : paths.toList()) {
                        String name = file.getFileName().toString(); if (!name.endsWith(".wav")) continue;
                        try { UUID.fromString(name.substring(0, name.length() - 4)); } catch (IllegalArgumentException ignored) { continue; }
                        if (!keep.contains(file)) Files.deleteIfExists(file);
                    }
                }
            }
        }
    }
    private static long diskBytes(Path directory) throws IOException {
        long total = 0;
        try (var files = Files.walk(directory, 3)) {
            var iterator = files.iterator();
            while (iterator.hasNext()) {
                Path file = iterator.next();
                if (Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) total += Files.size(file);
            }
        }
        return total;
    }
    private Path path(UUID owner, UUID id) throws IOException {
        Path directory = root.resolve(owner.toString());
        if (Files.isSymbolicLink(root) || Files.isSymbolicLink(directory)) throw new IOException("Voice cache directory must not be a symlink");
        return directory.resolve(id + ".wav");
    }
}

