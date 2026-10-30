package dev.gep;

import org.bukkit.*;
import org.bukkit.configuration.file.YamlConfiguration;
import java.nio.file.*;
import java.io.IOException;
import java.time.Instant;
import java.util.*;

/** Main-thread state, saved with atomic replacement. Unknown worlds remain serialized. */
final class Store {
    final GEP plugin;
    final Path file;
    YamlConfiguration data = new YamlConfiguration();
    Store(GEP plugin) {
        this.plugin = plugin;
        file = plugin.getDataFolder().toPath().resolve("state.yml");
        if (Files.exists(file)) {
            try { data.load(file.toFile()); }
            catch (Exception ex) { throw new IllegalStateException("Cannot load state.yml; refusing to overwrite it", ex); }
        }
    }
    String player(UUID id) { return "players." + id + "."; }
    Set<String> keys(String path) {
        var section = data.getConfigurationSection(path);
        return section == null ? Set.of() : new LinkedHashSet<>(section.getKeys(false));
    }
    void save() {
        try {
            Files.createDirectories(file.getParent());
            Path temp = file.resolveSibling("state.yml.tmp");
            Files.writeString(temp, data.saveToString(), java.nio.charset.StandardCharsets.UTF_8);
            try { Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
            catch (AtomicMoveNotSupportedException ex) { Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING); }
        } catch (IOException ex) { throw new IllegalStateException("GEP could not save state.yml", ex); }
    }
    /** Roll back in-memory state if a critical disk commit fails. */
    void change(Runnable action) {
        String before = data.saveToString();
        try { action.run(); save(); }
        catch (RuntimeException ex) {
            try { data.loadFromString(before); } catch (Exception impossible) { ex.addSuppressed(impossible); }
            throw ex;
        }
    }
    void remember(org.bukkit.entity.Player p) {
        data.set(player(p.getUniqueId()) + "name", p.getName());
    }
    UUID known(String input) {
        var online = Bukkit.getPlayerExact(input);
        if (online != null) return online.getUniqueId();
        try { UUID id = UUID.fromString(input); if (data.contains(player(id) + "name")) return id; }
        catch (IllegalArgumentException ignored) { }
        for (String key : keys("players")) if (input.equalsIgnoreCase(data.getString("players." + key + ".name"))) return UUID.fromString(key);
        throw new IllegalArgumentException("Player not found. They must have joined this server at least once.");
    }
    String name(UUID id) { return data.getString(player(id) + "name", id.toString()); }
    void history(UUID subject, String actor, String action, String detail) {
        String line = Instant.now() + " | " + safe(actor) + " | " + safe(action) + " | " + safe(detail);
        if (plugin.enabled("history") && subject != null) {
            String key = player(subject) + "history";
            List<String> rows = new ArrayList<>(data.getStringList(key));
            rows.add(line);
            int max = plugin.getConfig().getInt("history.max-entries-per-player", 200);
            while (rows.size() > max) rows.removeFirst();
            data.set(key, rows);
        }
    }
    void audit(String actor, String action, String detail) {
        if (!plugin.enabled("audit")) return;
        String line = Instant.now() + " | " + safe(actor) + " | " + safe(action) + " | " + safe(detail) + System.lineSeparator();
        try {
            Path dir = file.getParent().resolve("audit"); Files.createDirectories(dir);
            Files.writeString(dir.resolve(java.time.LocalDate.now(java.time.ZoneOffset.UTC) + ".log"), line,
                java.nio.charset.StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ex) { plugin.getLogger().log(java.util.logging.Level.SEVERE, "Could not append audit log", ex); }
    }
    private String safe(String value) { return value.replace('\n', ' ').replace('\r', ' ').replace('|', '/'); }
    static Map<String, Object> location(Location at) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("world", at.getWorld().getUID().toString()); map.put("x", at.getX()); map.put("y", at.getY()); map.put("z", at.getZ());
        map.put("yaw", at.getYaw()); map.put("pitch", at.getPitch()); return map;
    }
    static Location location(Map<?, ?> map) {
        try {
            World world = Bukkit.getWorld(UUID.fromString(map.get("world").toString()));
            if (world == null) return null;
            return new Location(world, number(map, "x"), number(map, "y"), number(map, "z"), (float)number(map, "yaw"), (float)number(map, "pitch"));
        } catch (RuntimeException ex) { return null; }
    }
    private static double number(Map<?, ?> map, String key) { return ((Number)map.get(key)).doubleValue(); }
    List<Map<?, ?>> backs(UUID id) { return new ArrayList<>(data.getMapList(player(id) + "back")); }
    Location readLocation(String path) {
        Object value = data.get(path);
        if (value instanceof org.bukkit.configuration.ConfigurationSection section) return location(section.getValues(false));
        if (value instanceof Map<?, ?> map) return location(map);
        return null;
    }
}
