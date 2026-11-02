package dev.gep;

import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import java.util.*;

public class GEP extends JavaPlugin implements CommandExecutor, TabCompleter {
    Store store;
    Rules rules;
    Requests requests;
    static final List<String> STAFF_NODES = List.of("staffchat.send", "staffchat.receive", "staff", "chat.seeall", "chat.mute", "chat.mute.bypass", "spy", "freeze", "freeze.exempt",
        "elimination.exempt", "revive", "back", "requests.view", "requests.dashboard", "requests.visit.lore", "requests.visit.kill", "requests.visit.materials", "requests.visit.support",
        "requests.decide", "requests.close", "requests.release", "vanish", "vanish.see", "history", "audit", "reload", "config", "spectator.bypass");
    public boolean enabled(String path) { return getConfig().getBoolean(path + ".enabled", true); }
    boolean staff(Player p) { return STAFF_NODES.stream().anyMatch(node -> p.hasPermission("gep." + node)); }
    static void tell(CommandSender p, String text) { p.sendMessage(Component.text("[GEP] ", NamedTextColor.GOLD).append(Component.text(text, NamedTextColor.GRAY))); }
    static void require(CommandSender sender, String node) {
        if (!sender.hasPermission("gep." + node)) throw new IllegalArgumentException("Missing permission: gep." + node);
    }
    void feature(String path) { if (!enabled(path)) throw new IllegalArgumentException("This feature is disabled: " + path); }
    @Override public void onEnable() {
        saveDefaultConfig();
        try {
            validate(); store = new Store(this); rules = new Rules(this); requests = new Requests(this);
            getServer().getPluginManager().registerEvents(rules, this);
            getServer().getPluginManager().registerEvents(requests, this);
            getServer().getPluginManager().registerEvents(new ConfigMenu(this), this);
            getServer().getPluginManager().registerEvents(new RequestDashboard(this), this);
            for (String name : List.of("gep", "staff", "back", "freeze", "chatmute", "revive", "vanish")) {
                Objects.requireNonNull(getCommand(name)).setExecutor(this); getCommand(name).setTabCompleter(this);
            }
            for (Player p : Bukkit.getOnlinePlayers()) { store.remember(p); rules.enforce(p); }
            getServer().getScheduler().runTaskTimer(this, () -> {
                requests.tick();
                for (Player p : Bukkit.getOnlinePlayers()) rules.enforce(p);
            }, 20, 20);
        } catch (Exception ex) {
            getLogger().log(java.util.logging.Level.SEVERE, "GEP cannot start safely", ex);
            getServer().getPluginManager().disablePlugin(this);
        }
    }
    @Override public void onDisable() {
        if (requests != null) requests.showAll();
        if (rules != null) { try { rules.flushTeleports(); } catch (Exception ex) { getLogger().log(java.util.logging.Level.SEVERE, "Teleport history flush failed", ex); } }
        if (store != null) { try { store.save(); } catch (Exception ex) { getLogger().log(java.util.logging.Level.SEVERE, "Final save failed", ex); } }
    }
    void validate() {
        double radius = getConfig().getDouble("local-chat.radius-blocks");
        if (!Double.isFinite(radius) || radius < 0) throw new IllegalArgumentException("local-chat.radius-blocks must be finite and nonnegative");
        if (!Set.of("spectator", "ban").contains(getConfig().getString("elimination.mode", ""))) throw new IllegalArgumentException("elimination.mode must be spectator or ban");
        if (enabled("death-messages")) {
            var messages = getConfig().getStringList("death-messages.messages");
            if (messages.isEmpty() || messages.stream().anyMatch(s -> !s.contains("<player>"))) throw new IllegalArgumentException("Each death message must contain <player>; list must not be empty");
        }
        range("back.history-size", 1, 20); range("staff-requests.cooldown-seconds", 0, 86400);
        range("staff-requests.prompt-timeout-seconds", 1, 3600); range("history.max-entries-per-player", 1, 10000);
        if (getConfig().contains("teleport-restrictions.message-cooldown-seconds")) range("teleport-restrictions.message-cooldown-seconds", 0, 3600);
    }
    private void range(String key, int min, int max) {
        int value = getConfig().getInt(key); if (value < min || value > max) throw new IllegalArgumentException(key + " must be between " + min + " and " + max);
    }
    void record(UUID subject, CommandSender actor, String action, String detail) {
        store.history(subject, actor.getName(), action, detail);
        store.save(); store.audit(actor.getName(), action, detail);
    }
    private Player player(CommandSender sender) { if (sender instanceof Player p) return p; throw new IllegalArgumentException("This command requires a player."); }
    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        try {
            switch (command.getName()) {
                case "staff" -> requests.command(player(sender), args);
                case "back" -> { require(sender, "back"); rules.back(player(sender), args.length == 0 ? 1 : Integer.parseInt(args[0])); }
                case "chatmute" -> mute(sender, args);
                case "vanish" -> requests.vanish(player(sender), args);
                case "freeze" -> freeze(sender, args);
                case "revive" -> revive(sender, args);
                case "gep" -> admin(sender, args);
                default -> { return false; }
            }
        } catch (IllegalArgumentException ex) { tell(sender, ex instanceof NumberFormatException ? "Enter a valid number." : ex.getMessage()); }
        catch (RuntimeException ex) { getLogger().log(java.util.logging.Level.SEVERE, "Command failed", ex); tell(sender, "The action failed. Check the server log before trying again."); }
        return true;
    }
    void mute(CommandSender sender, String[] args) {
        require(sender, "chat.mute"); feature("chat-mute");
        boolean old = store.data.getBoolean("chat-muted");
        boolean value = toggle(args.length == 0 ? "toggle" : args[0], old);
        store.change(() -> { store.data.set("chat-muted", value); store.history(null, sender.getName(), "CHAT_MUTE", "muted=" + value); });
        store.audit(sender.getName(), "CHAT_MUTE", "muted=" + value);
        Bukkit.broadcast(Component.text("[GEP] Public chat has been " + (value ? "muted." : "unmuted."), NamedTextColor.GOLD));
    }
    static boolean toggle(String input, boolean old) {
        return switch (input.toLowerCase(Locale.ROOT)) { case "on" -> true; case "off" -> false; case "toggle" -> !old; default -> throw new IllegalArgumentException("Use on, off, or toggle."); };
    }
    void freeze(CommandSender sender, String[] args) {
        require(sender, "freeze"); feature("freeze");
        if (args.length < 1 || args.length > 2) throw new IllegalArgumentException("/freeze <player|selector> [on|off]");
        String mode = args.length == 2 ? args[1] : "toggle";
        toggle(mode, false); // Validate before changing any matched player.
        List<UUID> targets = freezeTargets(sender, args[0]);
        int changed = 0, skipped = 0;
        for (UUID id : targets) {
            try { freezeOne(sender, id, mode); changed++; }
            catch (IllegalArgumentException ex) {
                if (targets.size() == 1) throw ex;
                skipped++; tell(sender, "Skipped " + store.name(id) + ": " + ex.getMessage());
            }
        }
        if (args[0].startsWith("@")) tell(sender, "Freeze: " + changed + " player(s) processed, " + skipped + " skipped.");
    }
    List<UUID> freezeTargets(CommandSender sender, String input) {
        if (!input.startsWith("@")) return List.of(store.known(input));
        feature("target-selectors");
        List<UUID> matches;
        try { matches = Bukkit.selectEntities(sender, input).stream().filter(Player.class::isInstance).map(org.bukkit.entity.Entity::getUniqueId).distinct().toList(); }
        catch (IllegalArgumentException ex) { throw new IllegalArgumentException("Invalid player selector: " + input + ". " + ex.getMessage()); }
        if (matches.isEmpty()) throw new IllegalArgumentException("That selector matched no online players.");
        return matches;
    }
    void freezeOne(CommandSender sender, UUID id, String mode) {
        String key = store.player(id) + "frozen";
        boolean value = toggle(mode, store.data.getBoolean(key));
        Player target = Bukkit.getPlayer(id);
        if (value && target != null && target.hasPermission("gep.freeze.exempt")) throw new IllegalArgumentException("That player is exempt from freezing.");
        store.change(() -> { store.data.set(key, value); store.history(id, sender.getName(), value ? "FREEZE" : "UNFREEZE", store.name(id)); });
        store.audit(sender.getName(), value ? "FREEZE" : "UNFREEZE", store.name(id) + " (" + id + ")");
        if (target != null) { if (value) rules.anchor(target); else rules.anchors.remove(id); tell(target, value ? "You have been frozen. You can still look around and chat." : "You have been unfrozen."); }
        tell(sender, store.name(id) + (value ? " frozen." : " unfrozen."));
    }
    void revive(CommandSender sender, String[] args) {
        require(sender, "revive"); // Recovery remains available even when elimination is disabled.
        if (args.length != 1) throw new IllegalArgumentException("/revive <player>");
        UUID id = store.known(args[0]); String root = store.player(id);
        if (!store.data.getBoolean(root + "eliminated")) throw new IllegalArgumentException("That player is not eliminated by GEP.");
        store.change(() -> {
            store.data.set(root + "eliminated", false); store.data.set(root + "death-ban", false);
            store.data.set(root + "restore-mode", true); store.history(id, sender.getName(), "REVIVE", store.name(id));
        });
        store.audit(sender.getName(), "REVIVE", store.name(id) + " (" + id + ")");
        Player target = Bukkit.getPlayer(id); if (target != null) { rules.restore(target); tell(target, "You have been revived."); }
        tell(sender, "Revived " + store.name(id) + ". Any unrelated server bans remain in place.");
    }
    void admin(CommandSender sender, String[] args) {
        if (args.length == 0) { tell(sender, "General Event Plugin | /staff | /back [1-3] | /freeze | /chatmute | /vanish [on|off] | /revive | /gep history <player> [page] | /gep audit [page] | /gep reload"); return; }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "config" -> { require(sender, "config"); ConfigMenu.open(this, player(sender), 0); }
            case "reload" -> {
                require(sender, "reload"); var previous = getConfig(); reloadConfig();
                try { validate(); } catch (RuntimeException ex) {
                    // Restore previous effective settings without overwriting the user's invalid file.
                    getConfig().getKeys(false).forEach(key -> getConfig().set(key, null));
                    previous.getValues(false).forEach((key, value) -> getConfig().set(key, value)); throw ex;
                }
                requests.tick(); for (Player p : Bukkit.getOnlinePlayers()) rules.enforce(p);
                store.audit(sender.getName(), "RELOAD", "Configuration reloaded"); tell(sender, "Configuration reloaded.");
            }
            case "history" -> {
                require(sender, "history"); feature("history");
                if (args.length < 2) throw new IllegalArgumentException("/gep history <player> [page]");
                UUID id = store.known(args[1]); String root = store.player(id);
                tell(sender, store.name(id) + " | eliminated=" + store.data.getBoolean(root + "eliminated") + " | death-ban=" + store.data.getBoolean(root + "death-ban") + " | frozen=" + store.data.getBoolean(root + "frozen") + " | deaths=" + store.data.getInt(root + "deaths"));
                page(sender, store.data.getStringList(root + "history"), args.length > 2 ? Integer.parseInt(args[2]) : 1);
            }
            case "audit" -> {
                require(sender, "audit"); feature("audit");
                try {
                    var dir = getDataFolder().toPath().resolve("audit"); List<String> lines = new ArrayList<>();
                    if (java.nio.file.Files.exists(dir)) try (var files = java.nio.file.Files.list(dir)) {
                        // Display latest day's log; older files remain available on disk.
                        var file = files.filter(p -> p.getFileName().toString().endsWith(".log")).sorted().reduce((a,b) -> b);
                        if (file.isPresent()) lines = java.nio.file.Files.readAllLines(file.get());
                    }
                    page(sender, lines, args.length > 1 ? Integer.parseInt(args[1]) : 1);
                } catch (java.io.IOException ex) { throw new IllegalStateException(ex); }
            }
            default -> throw new IllegalArgumentException("Use /gep history, /gep audit, or /gep reload.");
        }
    }
    static void page(CommandSender sender, List<String> lines, int page) {
        int pages = Math.max(1, (lines.size() + 9) / 10);
        if (page < 1 || page > pages) throw new IllegalArgumentException("Page must be 1–" + pages);
        tell(sender, "Page " + page + "/" + pages + " (newest first)");
        for (int i = lines.size() - 1 - (page - 1) * 10; i >= Math.max(0, lines.size() - page * 10); i--) tell(sender, lines.get(i));
    }
    @Override public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> choices = new ArrayList<>();
        switch (command.getName()) {
            case "gep" -> { if (args.length == 1) for (String node : List.of("history", "audit", "reload", "config")) { if (sender.hasPermission("gep." + node)) choices.add(node); } else if (args[0].equalsIgnoreCase("history") && sender.hasPermission("gep.history")) choices.addAll(knownNames()); }
            case "staff" -> { return requests.tabs(sender, args); }
            case "back" -> { if (sender.hasPermission("gep.back")) choices.addAll(List.of("1", "2", "3")); }
            case "chatmute" -> { if (sender.hasPermission("gep.chat.mute")) choices.addAll(List.of("on", "off", "toggle")); }
            case "vanish" -> { if (args.length == 1 && sender.hasPermission("gep.vanish")) choices.addAll(List.of("on", "off", "toggle")); }
            case "freeze", "revive" -> {
                if (sender.hasPermission("gep." + command.getName())) {
                    if (args.length == 1) {
                        choices.addAll(knownNames());
                        if (command.getName().equals("freeze") && enabled("target-selectors")) choices.addAll(List.of("@a", "@p", "@r", "@s", "@e[type=minecraft:player]"));
                    } else if (command.getName().equals("freeze") && args.length == 2) choices.addAll(List.of("on", "off", "toggle"));
                }
            }
        }
        String prefix = args[args.length - 1].toLowerCase(Locale.ROOT);
        return choices.stream().filter(s -> s.toLowerCase(Locale.ROOT).startsWith(prefix)).toList();
    }
    List<String> knownNames() { return store.keys("players").stream().map(id -> store.name(UUID.fromString(id))).toList(); }
}
