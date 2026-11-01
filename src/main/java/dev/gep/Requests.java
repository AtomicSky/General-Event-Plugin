package dev.gep;

import io.papermc.paper.event.player.ChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import net.kyori.adventure.text.*;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.*;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.*;
import org.bukkit.inventory.*;
import java.util.*;

@SuppressWarnings("deprecation")
final class Requests implements Listener {
    enum Kind {
        LORE("Lore request", Material.WRITABLE_BOOK), KILL("Kill ticket", Material.IRON_SWORD), MATERIALS("Build materials request", Material.BRICKS), SUPPORT("General support", Material.LANTERN);
        final String label; final Material icon;
        Kind(String label, Material icon) { this.label = label; this.icon = icon; }
        String key() { return name().toLowerCase(Locale.ROOT); }
    }
    record Ticket(UUID id, UUID owner, String name, Kind kind, String target, String result, String claim) { }
    static final class Menu implements InventoryHolder {
        final UUID owner; Inventory inventory; boolean used;
        final Map<Integer, Kind> options = new HashMap<>();
        Menu(UUID owner) { this.owner = owner; }
        @Override public Inventory getInventory() { return inventory; }
    }
    final GEP p;
    final Map<UUID, Long> prompts = new HashMap<>();
    final Set<UUID> hidden = new HashSet<>();
    final Set<UUID> indicators = new HashSet<>();
    Requests(GEP plugin) { p = plugin; }
    String root(UUID id) { return "tickets." + id + "."; }
    boolean enabled(Kind kind) { return p.enabled("staff-requests") && p.enabled("staff-requests." + kind.key()); }
    Ticket ticket(UUID id) {
        String root = root(id);
        if (!p.store.data.contains(root + "owner")) throw new IllegalArgumentException("Unknown request.");
        return new Ticket(id, UUID.fromString(p.store.data.getString(root + "owner")), p.store.data.getString(root + "name"),
            Kind.valueOf(p.store.data.getString(root + "kind")), p.store.data.getString(root + "target", ""),
            p.store.data.getString(root + "result", "PENDING"), p.store.data.getString(root + "claim", ""));
    }
    List<Ticket> tickets() { return p.store.keys("tickets").stream().map(UUID::fromString).map(this::ticket).toList(); }
    Ticket pending(UUID id) {
        Ticket t = ticket(id);
        if (!t.result.equals("PENDING")) throw new IllegalArgumentException("This request is already resolved.");
        if (!enabled(t.kind)) throw new IllegalArgumentException("This request category is disabled.");
        return t;
    }
    static Component button(String label, String command) {
        return Component.text(" [" + label + "]", NamedTextColor.AQUA).clickEvent(ClickEvent.runCommand(command));
    }
    void canRequest(Player player, Kind kind) {
        if (!enabled(kind)) throw new IllegalArgumentException("This request category is disabled.");
        GEP.require(player, "requests.submit." + kind.key());
        if (p.rules.restricted(player)) throw new IllegalArgumentException("Spectators cannot submit requests.");
    }
    static int[] centeredSlots(int count) {
        return switch (count) {
            case 0 -> new int[0];
            case 1 -> new int[]{13};
            case 2 -> new int[]{12, 14};
            case 3 -> new int[]{11, 13, 15};
            case 4 -> new int[]{10, 12, 14, 16};
            default -> throw new IllegalArgumentException("Unsupported request count.");
        };
    }
    void open(Player player) {
        p.feature("staff-requests");
        if (p.rules.restricted(player)) throw new IllegalArgumentException("Spectators cannot submit requests.");
        Menu menu = new Menu(player.getUniqueId());
        menu.inventory = Bukkit.createInventory(menu, 27, Component.text("GEP - Staff requests"));
        List<Kind> available = Arrays.stream(Kind.values()).filter(kind -> enabled(kind) && player.hasPermission("gep.requests.submit." + kind.key())).toList();
        int[] slots = centeredSlots(available.size());
        for (int index = 0; index < available.size(); index++) {
            Kind kind = available.get(index);
            ItemStack item = new ItemStack(kind.icon); var meta = item.getItemMeta();
            meta.displayName(Component.text(kind.label, NamedTextColor.GOLD));
            meta.lore(List.of(Component.text(kind == Kind.KILL ? "Submit a target username for staff review." : "Ask staff to visit and help.", NamedTextColor.GRAY)));
            item.setItemMeta(meta); menu.inventory.setItem(slots[index], item); menu.options.put(slots[index], kind);
        }
        player.openInventory(menu.inventory);
    }
    @EventHandler(priority = EventPriority.HIGHEST)
    void click(InventoryClickEvent e) {
        if (!(e.getView().getTopInventory().getHolder() instanceof Menu menu)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player player) || !menu.owner.equals(player.getUniqueId()) || menu.used || p.rules.noInteract(player)) return;
        Kind kind = menu.options.get(e.getRawSlot());
        if (kind == null || !enabled(kind) || !player.hasPermission("gep.requests.submit." + kind.key())) return;
        menu.used = true;
        p.getServer().getScheduler().runTask(p, () -> {
            player.closeInventory(); if (!player.isOnline()) return;
            try {
                canRequest(player, kind);
                if (kind == Kind.KILL) {
                    prompts.put(player.getUniqueId(), System.currentTimeMillis() + p.getConfig().getLong("staff-requests.prompt-timeout-seconds", 120) * 1000);
                    player.sendMessage(Component.text("[GEP] Enter the target username in chat (private input), or use ", NamedTextColor.GOLD)
                        .append(Component.text("[/staff target …]", NamedTextColor.AQUA).clickEvent(ClickEvent.suggestCommand("/staff target ")))
                        .append(button("Cancel", "/staff cancel")));
                } else create(player, kind, "");
            } catch (IllegalArgumentException ex) { GEP.tell(player, ex.getMessage()); }
        });
    }
    @EventHandler(priority = EventPriority.HIGHEST)
    void drag(InventoryDragEvent e) { if (e.getView().getTopInventory().getHolder() instanceof Menu) e.setCancelled(true); }
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    void input(ChatEvent e) {
        if (!prompts.containsKey(e.getPlayer().getUniqueId())) return;
        e.setCancelled(true);
        try { input(e.getPlayer(), PlainTextComponentSerializer.plainText().serialize(e.message()).strip()); }
        catch (IllegalArgumentException ex) { GEP.tell(e.getPlayer(), ex.getMessage()); }
    }
    void input(Player player, String value) {
        Long expires = prompts.get(player.getUniqueId());
        if (expires == null) throw new IllegalArgumentException("Open /staff and select Kill ticket first.");
        if (value.equalsIgnoreCase("cancel") || expires < System.currentTimeMillis() || !enabled(Kind.KILL)) {
            prompts.remove(player.getUniqueId()); GEP.tell(player, "Kill ticket input cancelled."); return;
        }
        create(player, Kind.KILL, value); prompts.remove(player.getUniqueId());
    }
    UUID create(Player player, Kind kind, String target) {
        canRequest(player, kind);
        if (kind == Kind.KILL && !target.matches("[A-Za-z0-9_]{3,16}")) throw new IllegalArgumentException("Enter a Minecraft username (3-16 letters, digits, underscores), or cancel.");
        if (tickets().stream().anyMatch(t -> t.owner.equals(player.getUniqueId()) && t.kind == kind && t.result.equals("PENDING")))
            throw new IllegalArgumentException("You already have a pending " + kind.label.toLowerCase(Locale.ROOT) + ".");
        long now = System.currentTimeMillis(); String playerRoot = p.store.player(player.getUniqueId());
        if (p.getConfig().getLong("staff-requests.cooldown-seconds") > 0 && p.store.data.getLong(playerRoot + "request-cooldown") > now)
            throw new IllegalArgumentException("Please wait before submitting another request.");
        UUID id = UUID.randomUUID(); String root = root(id);
        p.store.change(() -> {
            p.store.remember(player);
            p.store.data.set(root + "owner", player.getUniqueId().toString()); p.store.data.set(root + "name", player.getName());
            p.store.data.set(root + "kind", kind.name()); p.store.data.set(root + "target", target);
            p.store.data.set(root + "result", "PENDING"); p.store.data.set(root + "created", now);
            p.store.data.set(playerRoot + "request-cooldown", now + p.getConfig().getLong("staff-requests.cooldown-seconds") * 1000);
            p.store.history(player.getUniqueId(), player.getName(), "REQUEST", kind + " " + target + " id=" + id);
        });
        p.store.audit(player.getName(), "REQUEST", kind + " " + target + " id=" + id);
        GEP.tell(player, "Request sent.");
        Ticket t = ticket(id);
        for (Player staff : Bukkit.getOnlinePlayers()) if (staff.hasPermission("gep.requests.view")) notify(staff, t);
        return id;
    }
    void notify(Player player, Ticket t) {
        if (!enabled(t.kind) || !player.hasPermission("gep.requests.view")) return;
        String command = "/staff ";
        Component text = Component.text("[GEP] " + t.name + ": " + t.kind.label + (t.target.isEmpty() ? "" : " -> " + t.target), NamedTextColor.GOLD);
        if (!t.claim.isEmpty() && p.enabled("staff-requests.claiming")) text = text.append(Component.text(" (claimed by " + p.store.name(UUID.fromString(t.claim)) + ")", NamedTextColor.GRAY));
        if (player.hasPermission("gep.requests.visit." + t.kind.key())) text = text.append(button("TP", command + "visit " + t.id));
        if (t.kind == Kind.KILL && player.hasPermission("gep.requests.decide")) text = text.append(button("Approve", command + "approve " + t.id)).append(button("Deny", command + "deny " + t.id));
        else if (t.kind != Kind.KILL && player.hasPermission("gep.requests.close")) text = text.append(button("Close", command + "close " + t.id));
        if (!t.claim.isEmpty() && player.hasPermission("gep.requests.release")) text = text.append(button("Release", command + "release " + t.id));
        player.sendMessage(text.hoverEvent(Component.text("Request ID: " + t.id)));
    }
    void visit(Player player, UUID id) {
        Ticket t = pending(id); GEP.require(player, "requests.visit." + t.kind.key());
        if (p.rules.frozen(player) || p.rules.restricted(player)) throw new IllegalArgumentException("You cannot visit while frozen or restricted as a spectator.");
        boolean claiming = p.enabled("staff-requests.claiming");
        if (claiming && !t.claim.isEmpty() && !t.claim.equals(player.getUniqueId().toString())) throw new IllegalArgumentException("Another staff member has already claimed this request.");
        Player target = Bukkit.getPlayer(t.owner);
        if (target == null) throw new IllegalArgumentException("The requesting player is offline.");
        if (target.equals(player)) throw new IllegalArgumentException("You are already the requester.");
        String visitRoot = "visits." + player.getUniqueId() + ".";
        boolean hadVisit = p.store.data.contains(visitRoot + "origin");
        boolean wasHidden = vanished(player);
        boolean hide = p.enabled("vanish") && t.kind == Kind.LORE && p.getConfig().getBoolean("staff-requests.lore.hidden-visits");
        Map<String, Object> savedOrigin = Store.location(player.getLocation());
        // Hide before teleport so a lore visitor never briefly appears at the destination.
        visibility(player, hide || manualHidden(player));
        boolean success;
        try { success = player.teleport(target.getLocation()); }
        catch (RuntimeException ex) { visibility(player, wasHidden); throw ex; }
        if (!success) { visibility(player, wasHidden); throw new IllegalArgumentException("Teleport was blocked. No claim was taken."); }
        // Teleport events run synchronously; all later clicks observe this committed claim.
        try { p.store.change(() -> {
            if (!hadVisit) p.store.data.set(visitRoot + "origin", savedOrigin);
            p.store.data.set(visitRoot + "hidden", hide);
            p.store.data.set(visitRoot + "kind", t.kind.name());
            if (claiming) p.store.data.set(root(id) + "claim", player.getUniqueId().toString());
            p.store.history(t.owner, player.getName(), "VISIT", t.kind + " request=" + id);
        }); } catch (RuntimeException ex) {
            visibility(player, wasHidden);
            p.rules.teleportInternal(player, Store.location(savedOrigin));
            throw ex;
        }
        p.store.audit(player.getName(), "VISIT", t.name + " request=" + id + " hidden=" + hide);
        player.sendMessage(Component.text("[GEP] Visiting " + t.name + ".", NamedTextColor.GOLD).append(button("Return", "/staff return")));
    }
    void decide(Player player, UUID id, String decision) {
        Ticket t = pending(id);
        if (t.kind == Kind.KILL) {
            GEP.require(player, "requests.decide");
            if (!Set.of("APPROVED", "DENIED").contains(decision)) throw new IllegalArgumentException("Kill tickets require approve or deny.");
        } else {
            GEP.require(player, "requests.close"); if (!decision.equals("CLOSED")) throw new IllegalArgumentException("Use close for lore, materials, or support requests.");
        }
        p.store.change(() -> {
            p.store.data.set(root(id) + "result", decision); p.store.data.set(root(id) + "decided-by", player.getUniqueId().toString());
            p.store.data.set(root(id) + "decided-at", System.currentTimeMillis());
            p.store.history(t.owner, player.getName(), decision, t.kind + " target=" + t.target + " request=" + id);
        });
        p.store.audit(player.getName(), decision, t.name + " " + t.kind + " target=" + t.target + " request=" + id);
        Player owner = Bukkit.getPlayer(t.owner); if (owner != null) deliver(owner, ticket(id));
        GEP.tell(player, "Request " + decision.toLowerCase(Locale.ROOT) + ".");
    }
    void deliver(Player player, Ticket t) {
        GEP.tell(player, "Your " + t.kind.label.toLowerCase(Locale.ROOT) + (t.target.isEmpty() ? "" : " for " + t.target) + " was " + t.result.toLowerCase(Locale.ROOT) + ".");
        p.store.change(() -> p.store.data.set(root(t.id) + "notified", true));
    }
    void release(Player player, UUID id) {
        GEP.require(player, "requests.release"); Ticket t = pending(id); p.feature("staff-requests.claiming");
        if (t.claim.isEmpty()) throw new IllegalArgumentException("This request is not claimed.");
        p.store.change(() -> {
            p.store.data.set(root(id) + "claim", null);
            p.store.history(t.owner, player.getName(), "RELEASE_CLAIM", "request=" + id);
        });
        p.store.audit(player.getName(), "RELEASE_CLAIM", "request=" + id);
        GEP.tell(player, "Claim released. Staff can visit again.");
        for (Player member : Bukkit.getOnlinePlayers()) notify(member, ticket(id));
    }
    void back(Player player) {
        String root = "visits." + player.getUniqueId() + ".";
        if (!p.store.data.contains(root + "origin")) throw new IllegalArgumentException("No request return location is saved.");
        if (p.rules.frozen(player) || p.rules.restricted(player)) throw new IllegalArgumentException("You cannot return while frozen or restricted as a spectator.");
        Location target = p.store.readLocation(root + "origin");
        if (target == null || !p.rules.teleportInternal(player, target)) throw new IllegalArgumentException("Return teleport was blocked or the world is unavailable. Your return location was kept.");
        endVisit(player); p.record(player.getUniqueId(), player, "REQUEST_RETURN", "Returned from request visit"); GEP.tell(player, "Returned from your request visit.");
    }
    void endVisit(Player player) {
        p.store.change(() -> p.store.data.set("visits." + player.getUniqueId(), null)); visibility(player, vanished(player));
    }
    boolean manualHidden(Player player) {
        return p.enabled("vanish") && player.hasPermission("gep.vanish")
            && p.store.data.getBoolean(p.store.player(player.getUniqueId()) + "vanished");
    }
    boolean vanished(Player player) {
        return manualHidden(player) || (p.enabled("vanish") && enabled(Kind.LORE)
            && p.getConfig().getBoolean("staff-requests.lore.hidden-visits")
            && player.hasPermission("gep.requests.visit.lore")
            && p.store.data.getBoolean("visits." + player.getUniqueId() + ".hidden"));
    }
    void vanish(Player player, String[] args) {
        GEP.require(player, "vanish"); p.feature("vanish");
        if (args.length > 1) throw new IllegalArgumentException("/vanish [on|off|toggle]");
        boolean value = GEP.toggle(args.length == 0 ? "toggle" : args[0], vanished(player));
        p.store.change(() -> {
            p.store.remember(player);
            p.store.data.set(p.store.player(player.getUniqueId()) + "vanished", value);
            // Manual off is immediate; the next lore visit forces automatic vanish again.
            if (!value && p.store.data.contains("visits." + player.getUniqueId() + ".hidden"))
                p.store.data.set("visits." + player.getUniqueId() + ".hidden", null);
            p.store.history(player.getUniqueId(), player.getName(), "VANISH", "enabled=" + value);
        });
        visibility(player, vanished(player));
        p.store.audit(player.getName(), "VANISH", "enabled=" + value);
        GEP.tell(player, "Vanish " + (value ? "enabled." : "disabled."));
    }
    void visibility(Player player, boolean hide) {
        if (hide) hidden.add(player.getUniqueId()); else hidden.remove(player.getUniqueId());
        if(hide && p.enabled("vanish.indicator")) {
            player.sendActionBar(Component.text("VANISHED - Hidden from players", NamedTextColor.AQUA));
            indicators.add(player.getUniqueId());
        } else if(indicators.remove(player.getUniqueId())) player.sendActionBar(Component.empty());
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            if (viewer.equals(player)) continue;
            if (hide && !viewer.hasPermission("gep.vanish.see")) viewer.hidePlayer(p, player);
            else viewer.showPlayer(p, player);
        }
    }
    void tick() {
        long now = System.currentTimeMillis();
        for (var entry : new HashMap<>(prompts).entrySet()) if (entry.getValue() < now || !enabled(Kind.KILL)) {
            prompts.remove(entry.getKey()); Player player = Bukkit.getPlayer(entry.getKey());
            if (player != null) { GEP.tell(player, "Kill ticket input expired or was disabled."); }
        }
        for (Player player : Bukkit.getOnlinePlayers()) visibility(player, vanished(player));
    }
    void showAll() { for (Player player : Bukkit.getOnlinePlayers()) visibility(player, false); }
    @EventHandler void join(PlayerJoinEvent e) {
        Player player = e.getPlayer();
        for (Ticket t : tickets()) if (enabled(t.kind) && t.owner.equals(player.getUniqueId()) && !t.result.equals("PENDING") && !p.store.data.getBoolean(root(t.id) + "notified")) deliver(player, t);
        if (p.enabled("staff-requests") && player.hasPermission("gep.requests.view")) {
            long count = tickets().stream().filter(t -> t.result.equals("PENDING") && enabled(t.kind)).count();
            if (count > 0) player.sendMessage(Component.text("[GEP] " + count + " pending request(s).", NamedTextColor.GOLD).append(button("Open", "/staff list")));
        }
        if (p.store.data.contains("visits." + player.getUniqueId())) player.sendMessage(Component.text("[GEP] Saved request return available.").append(button("Return", "/staff return")));
        tick();
    }
    @EventHandler void quit(PlayerQuitEvent e) { prompts.remove(e.getPlayer().getUniqueId()); }
    void command(Player player, String[] args) {
        if (args.length == 0) { open(player); return; }
        String action = args[0].toLowerCase(Locale.ROOT);
        if (action.equals("return")) { back(player); return; } // Owner-only recovery works after feature/permission removal.
        if (action.equals("cancel")) { prompts.remove(player.getUniqueId()); GEP.tell(player, "Request input cancelled."); return; }
        if (action.equals("target") && args.length == 2) { input(player, args[1]); return; }
        p.feature("staff-requests");
        if (action.equals("dashboard")) { RequestDashboard.open(p, player, 0); return; }
        if (action.equals("list")) {
            GEP.require(player, "requests.view");
            List<Ticket> pending = tickets().stream().filter(t -> t.result.equals("PENDING") && enabled(t.kind)).toList();
            if (pending.isEmpty()) GEP.tell(player, "No pending requests."); else pending.forEach(t -> notify(player, t)); return;
        }
        if (args.length != 2) throw new IllegalArgumentException("Use /staff, /staff list, or /staff return.");
        UUID id;
        try { id = UUID.fromString(args[1]); } catch (IllegalArgumentException ex) { throw new IllegalArgumentException("Invalid request ID."); }
        switch (action) {
            case "visit" -> visit(player, id);
            case "approve" -> decide(player, id, "APPROVED");
            case "deny" -> decide(player, id, "DENIED");
            case "close" -> decide(player, id, "CLOSED");
            case "release" -> release(player, id);
            default -> throw new IllegalArgumentException("Unknown request action.");
        }
    }
    List<String> tabs(CommandSender sender, String[] args) {
        List<String> values = new ArrayList<>(List.of("return", "cancel"));
        if (sender.hasPermission("gep.requests.view")) values.add("list");
        if (sender.hasPermission("gep.requests.dashboard") && sender.hasPermission("gep.requests.view")) values.add("dashboard");
        if (sender.hasPermission("gep.requests.decide")) values.addAll(List.of("approve", "deny"));
        if (sender.hasPermission("gep.requests.close")) values.add("close");
        if (sender.hasPermission("gep.requests.release")) values.add("release");
        if (Arrays.stream(Kind.values()).anyMatch(k -> sender.hasPermission("gep.requests.visit." + k.key()))) values.add("visit");
        if (sender instanceof Player player && prompts.containsKey(player.getUniqueId()) && enabled(Kind.KILL)
            && player.hasPermission("gep.requests.submit.kill")) {
            values.add("target");
            if (args.length == 2 && args[0].equalsIgnoreCase("target")) return Bukkit.getOnlinePlayers().stream()
                .filter(player::canSee).map(Player::getName)
                .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(args[1].toLowerCase(Locale.ROOT))).sorted().toList();
        }
        if (args.length == 1) return values.stream().filter(v -> v.startsWith(args[0].toLowerCase(Locale.ROOT))).toList();
        if (args.length == 2 && sender.hasPermission("gep.requests.view") && !args[0].equalsIgnoreCase("target")) return tickets().stream().filter(t -> t.result.equals("PENDING")).map(t -> t.id.toString()).filter(id -> id.startsWith(args[1])).toList();
        return List.of();
    }
}
