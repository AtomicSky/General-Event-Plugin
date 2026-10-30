package dev.gep;

import io.papermc.paper.event.player.ChatEvent;
import com.destroystokyo.paper.event.player.PlayerStartSpectatingEntityEvent;
import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.*;
import org.bukkit.event.vehicle.VehicleEnterEvent;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;
import net.kyori.adventure.text.Component;
import java.util.*;

@SuppressWarnings("deprecation")
final class Rules implements Listener {
    final GEP p;
    final Set<UUID> internalTeleport = new HashSet<>();
    final Map<UUID, Location> anchors = new HashMap<>();
    final Map<UUID, Long> teleportWarnings = new HashMap<>();
    record PendingTeleport(PlayerTeleportEvent event, UUID player, Location origin) { }
    final List<PendingTeleport> pendingTeleports = new ArrayList<>();
    boolean flushScheduled;
    Rules(GEP plugin) { p = plugin; }
    boolean frozen(Player player) {
        return p.enabled("freeze") && !player.hasPermission("gep.freeze.exempt") && p.store.data.getBoolean(p.store.player(player.getUniqueId()) + "frozen");
    }
    boolean restricted(Player player) {
        return p.enabled("spectator-restrictions") && player.getGameMode() == GameMode.SPECTATOR && !player.hasPermission("gep.spectator.bypass");
    }
    boolean chatBlocked(Player player) {
        return restricted(player) && p.getConfig().getBoolean("spectator-restrictions.block-chat");
    }
    boolean muted(Player player) {
        return p.enabled("chat-mute") && p.store.data.getBoolean("chat-muted") && !player.hasPermission("gep.chat.mute.bypass");
    }
    void anchor(Player player) {
        player.leaveVehicle();
        Location at = player.getLocation().clone(); anchors.put(player.getUniqueId(), at);
        p.store.change(() -> p.store.data.set(p.store.player(player.getUniqueId()) + "freeze-anchor", Store.location(at)));
    }
    Location anchorFor(Player player) {
        return anchors.computeIfAbsent(player.getUniqueId(), id -> {
            Location saved = p.store.readLocation(p.store.player(id) + "freeze-anchor");
            return saved == null ? player.getLocation().clone() : saved;
        });
    }
    void enforce(Player player) {
        restore(player);
        String root = p.store.player(player.getUniqueId());
        if (p.enabled("elimination") && p.store.data.getBoolean(root + "eliminated")) {
            if (p.store.data.getBoolean(root + "death-ban")) {
                if (player.isOnline()) player.kick(Component.text(p.getConfig().getString("elimination.ban-message", "Eliminated.")));
                return;
            }
            if (!player.isDead() && player.getGameMode() != GameMode.SPECTATOR) player.setGameMode(GameMode.SPECTATOR);
        }
        if (restricted(player) && p.getConfig().getBoolean("spectator-restrictions.block-spectating-entities") && player.getSpectatorTarget() != null) player.setSpectatorTarget(null);
        if (frozen(player)) {
            if (player.isInsideVehicle()) player.leaveVehicle();
            Location at = anchorFor(player);
            if (!player.getWorld().equals(at.getWorld()) || player.getLocation().distanceSquared(at) > 0.0001) {
                Location target = at.clone(); target.setYaw(player.getYaw()); target.setPitch(player.getPitch());
                teleportInternal(player, target);
            }
            player.setVelocity(new org.bukkit.util.Vector());
            player.setFallDistance(0);
        } else anchors.remove(player.getUniqueId());
    }
    void restore(Player player) {
        String root = p.store.player(player.getUniqueId());
        if (!p.store.data.getBoolean(root + "restore-mode") || player.isDead()) return;
        GameMode mode;
        try { mode = GameMode.valueOf(p.store.data.getString(root + "previous-mode", "SURVIVAL")); }
        catch (IllegalArgumentException ex) { mode = GameMode.SURVIVAL; }
        if (mode == GameMode.SPECTATOR) mode = GameMode.SURVIVAL;
        player.setGameMode(mode);
        if (player.getGameMode() == mode) p.store.change(() -> p.store.data.set(root + "restore-mode", false));
    }
    boolean teleportInternal(Player player, Location target) {
        internalTeleport.add(player.getUniqueId());
        try { return player.teleport(target, TeleportCause.PLUGIN); }
        finally { internalTeleport.remove(player.getUniqueId()); }
    }
    @EventHandler(priority = EventPriority.HIGHEST)
    void login(PlayerLoginEvent e) {
        String root = p.store.player(e.getPlayer().getUniqueId());
        if (p.enabled("elimination") && p.store.data.getBoolean(root + "eliminated") && p.store.data.getBoolean(root + "death-ban"))
            e.disallow(PlayerLoginEvent.Result.KICK_BANNED, Component.text(p.getConfig().getString("elimination.ban-message", "Eliminated.")));
    }
    @EventHandler void join(PlayerJoinEvent e) {
        Player player = e.getPlayer();
        p.store.change(() -> {
            p.store.remember(player);
            p.store.history(player.getUniqueId(), player.getName(), "JOIN", "Joined the event");
        });
        p.getServer().getScheduler().runTask(p, () -> {
            if (!player.isOnline()) return;
            enforce(player);
            if (frozen(player)) GEP.tell(player, "You are still frozen. You can look around and chat.");
        });
    }
    @EventHandler void quit(PlayerQuitEvent e) {
        Player player = e.getPlayer(); anchors.remove(player.getUniqueId()); teleportWarnings.remove(player.getUniqueId());
        p.store.change(() -> {
            p.store.remember(player); p.store.history(player.getUniqueId(), player.getName(), "QUIT", "Left the event");
        });
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    void deathMessage(PlayerDeathEvent e) {
        if (p.enabled("death-messages")) {
            var choices = p.getConfig().getStringList("death-messages.messages");
            e.deathMessage(Component.text(choices.get(java.util.concurrent.ThreadLocalRandom.current().nextInt(choices.size())).replace("<player>", e.getEntity().getName())));
        }
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    void died(PlayerDeathEvent e) {
        Player player = e.getEntity(); String root = p.store.player(player.getUniqueId());
        boolean eliminate = p.enabled("elimination") && !player.hasPermission("gep.elimination.exempt")
            && !(p.getConfig().getBoolean("elimination.staff-exempt") && p.staff(player));
        p.store.change(() -> {
            p.store.remember(player); p.store.data.set(root + "deaths", p.store.data.getInt(root + "deaths") + 1);
            p.store.history(player.getUniqueId(), "SERVER", "DEATH", "At " + player.getWorld().getName() + " " + player.getLocation().toVector());
            if (eliminate) {
                p.store.data.set(root + "previous-mode", player.getGameMode().name());
                p.store.data.set(root + "eliminated", true);
                p.store.data.set(root + "death-ban", p.getConfig().getString("elimination.mode").equals("ban"));
                p.store.history(player.getUniqueId(), "SERVER", "ELIMINATE", p.getConfig().getString("elimination.mode"));
            }
        });
        if (eliminate) p.store.audit("SERVER", "ELIMINATE", player.getName() + " (" + player.getUniqueId() + ") mode=" + p.getConfig().getString("elimination.mode"));
        if (p.enabled("death-lightning")) player.getWorld().strikeLightningEffect(player.getLocation());
        if (eliminate) p.getServer().getScheduler().runTask(p, () -> { if (player.isOnline()) enforce(player); });
    }
    @EventHandler void respawn(PlayerRespawnEvent e) {
        p.getServer().getScheduler().runTask(p, () -> { if (e.getPlayer().isOnline()) enforce(e.getPlayer()); });
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    void mode(PlayerGameModeChangeEvent e) {
        String root = p.store.player(e.getPlayer().getUniqueId());
        if (p.enabled("elimination") && p.store.data.getBoolean(root + "eliminated") && e.getNewGameMode() != GameMode.SPECTATOR) {
            e.setCancelled(true); GEP.tell(e.getPlayer(), "You are eliminated. Staff must use /revive first.");
        }
    }
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    void blockChat(ChatEvent e) {
        if (chatBlocked(e.getPlayer())) { e.setCancelled(true); GEP.tell(e.getPlayer(), "Spectators cannot chat."); }
        else if (muted(e.getPlayer())) { e.setCancelled(true); GEP.tell(e.getPlayer(), "Public chat is muted by staff."); }
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    void localChat(ChatEvent e) {
        if (!p.enabled("local-chat")) return;
        Player sender = e.getPlayer(); Location origin = sender.getLocation();
        double radius = p.getConfig().getDouble("local-chat.radius-blocks");
        e.viewers().removeIf(viewer -> viewer instanceof Player target
            && !(p.getConfig().getBoolean("local-chat.staff-see-all") && target.hasPermission("gep.chat.seeall"))
            && (!target.getWorld().equals(sender.getWorld()) || target.getLocation().distanceSquared(origin) > radius * radius));
    }
    static String commandName(String text) {
        String token = text.substring(1).split("\\s+", 2)[0].toLowerCase(Locale.ROOT);
        return token.substring(token.lastIndexOf(':') + 1);
    }
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    void commands(PlayerCommandPreprocessEvent e) {
        Player player = e.getPlayer(); String name = commandName(e.getMessage());
        if (restricted(player) && p.getConfig().getBoolean("spectator-restrictions.block-commands")) {
            boolean allowed = p.getConfig().getStringList("spectator-restrictions.allowed-commands").stream().anyMatch(s -> s.equalsIgnoreCase(name));
            if (!allowed) { e.setCancelled(true); GEP.tell(player, "Spectators can only fly around; commands are restricted."); return; }
        }
        if (p.getConfig().getBoolean("chat-mute.block-private-messages") && muted(player) && Set.of("msg", "w", "tell", "me", "teammsg", "tm").contains(name)) {
            e.setCancelled(true); GEP.tell(player, "Messaging is muted by staff.");
        }
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    void spy(PlayerCommandPreprocessEvent e) {
        if (!p.enabled("private-message-spy")) return;
        String[] args = e.getMessage().substring(1).split("\\s+", 3);
        if (args.length != 3 || args[2].isBlank() || !Set.of("msg", "tell", "w", "minecraft:msg", "minecraft:tell", "minecraft:w").contains(args[0].toLowerCase(Locale.ROOT))) return;
        Player sender = e.getPlayer(); if (!sender.hasPermission("minecraft.command.msg")) return;
        List<Player> targets = new ArrayList<>();
        if (args[1].startsWith("@")) {
            if (!sender.hasPermission("minecraft.command.selector")) return;
            try { for (Entity entity : Bukkit.selectEntities(sender, args[1])) if (entity instanceof Player target) targets.add(target); }
            catch (IllegalArgumentException ex) { return; }
        } else {
            Player target = Bukkit.getPlayerExact(args[1]);
            if (target == null) try { target = Bukkit.getPlayer(UUID.fromString(args[1])); } catch (IllegalArgumentException ignored) { }
            if (target != null) targets.add(target);
        }
        if (targets.isEmpty()) return;
        p.getServer().getScheduler().runTask(p, () -> {
            if (e.isCancelled() || !p.enabled("private-message-spy")) return;
            Component line = Component.text("[GEP Spy] " + sender.getName() + " → " + String.join(", ", targets.stream().map(Player::getName).toList()) + ": " + args[2]);
            for (Player viewer : Bukkit.getOnlinePlayers()) if (viewer.hasPermission("gep.spy") && !viewer.equals(sender) && !targets.contains(viewer)) viewer.sendMessage(line);
        });
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    void move(PlayerMoveEvent e) {
        if (e instanceof PlayerTeleportEvent || !frozen(e.getPlayer()) || e.getTo() == null) return;
        Location at = anchorFor(e.getPlayer());
        Location to = e.getTo();
        if (at.getWorld().equals(to.getWorld()) && at.getX() == to.getX() && at.getY() == to.getY() && at.getZ() == to.getZ()) return;
        // Paper 1.21.11 internally corrects a cancelled move using the original From
        // object. Update that shared object in place: setFrom() replaces only the
        // event field, while setTo() invokes a new, externally blocked teleport.
        Location rollback = e.getFrom();
        rollback.setWorld(at.getWorld()); rollback.setX(at.getX()); rollback.setY(at.getY()); rollback.setZ(at.getZ());
        rollback.setYaw(to.getYaw()); rollback.setPitch(to.getPitch());
        e.setCancelled(true);
        e.getPlayer().setFallDistance(0);
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    void teleport(PlayerTeleportEvent e) {
        if (internalTeleport.contains(e.getPlayer().getUniqueId())) return;
        if ((frozen(e.getPlayer()) && p.getConfig().getBoolean("freeze.block-teleports"))
            || (restricted(e.getPlayer()) && p.getConfig().getBoolean("spectator-restrictions.block-teleports"))) {
            e.setCancelled(true);
            // Plugin/unknown teleports include automatic corrections; never turn
            // those into chat spam. Deliberate denied teleports are rate-limited.
            if (e.getCause() != TeleportCause.PLUGIN && e.getCause() != TeleportCause.UNKNOWN
                && p.getConfig().getBoolean("teleport-restrictions.notify-on-denial", true)) {
                long now = System.nanoTime();
                long delay = p.getConfig().getLong("teleport-restrictions.message-cooldown-seconds", 3) * 1_000_000_000L;
                Long previous = teleportWarnings.get(e.getPlayer().getUniqueId());
                if (previous == null || now - previous >= delay) {
                    teleportWarnings.put(e.getPlayer().getUniqueId(), now);
                    GEP.tell(e.getPlayer(), "Teleporting is currently restricted.");
                }
            }
        }
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    void rememberTeleport(PlayerTeleportEvent e) {
        Player player = e.getPlayer();
        if (!p.enabled("back") || !player.hasPermission("gep.back") || internalTeleport.contains(player.getUniqueId()) || e.getTo() == null) return;
        pendingTeleports.add(new PendingTeleport(e, player.getUniqueId(), e.getFrom().clone()));
        // Check cancellation after all event handlers; flush also runs before /back and shutdown.
        if (!flushScheduled) {
            flushScheduled = true;
            p.getServer().getScheduler().runTask(p, this::flushTeleports);
        }
    }
    void flushTeleports() {
        flushScheduled = false;
        if (pendingTeleports.isEmpty()) return;
        p.store.change(() -> {
            for (PendingTeleport entry : pendingTeleports) {
                if (entry.event.isCancelled()) continue;
                List<Map<?, ?>> backs = p.store.backs(entry.player);
                backs.addFirst(Store.location(entry.origin));
                int max = p.getConfig().getInt("back.history-size", 3);
                while (backs.size() > max) backs.removeLast();
                p.store.data.set(p.store.player(entry.player) + "back", backs);
            }
        });
        pendingTeleports.clear();
    }
    void back(Player player, int index) {
        p.feature("back");
        flushTeleports();
        if (frozen(player) || restricted(player)) throw new IllegalArgumentException("You cannot return while frozen or restricted as a spectator.");
        List<Map<?, ?>> backs = p.store.backs(player.getUniqueId());
        if (index < 1 || index > backs.size()) throw new IllegalArgumentException("No saved location at that position. Available: " + backs.size());
        Location target = Store.location(backs.get(index - 1));
        if (target == null) throw new IllegalArgumentException("The destination world is unavailable; the location was kept.");
        if (!teleportInternal(player, target)) throw new IllegalArgumentException("Return teleport was blocked; the location was kept.");
        backs.remove(index - 1);
        p.store.change(() -> {
            p.store.data.set(p.store.player(player.getUniqueId()) + "back", backs);
            p.store.history(player.getUniqueId(), player.getName(), "BACK", "Saved location " + index);
        });
        p.store.audit(player.getName(), "BACK", "Saved location " + index);
        p.requests.endVisit(player); GEP.tell(player, "Returned. " + backs.size() + " saved location(s) remain.");
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    void target(PlayerStartSpectatingEntityEvent e) {
        if (restricted(e.getPlayer()) && p.getConfig().getBoolean("spectator-restrictions.block-spectating-entities")) e.setCancelled(true);
    }
    boolean noInteract(Player player) { return frozen(player) && p.getConfig().getBoolean("freeze.block-interactions"); }
    @EventHandler(priority=EventPriority.HIGHEST) void interact(PlayerInteractEvent e) { if (noInteract(e.getPlayer())) e.setCancelled(true); }
    @EventHandler(priority=EventPriority.HIGHEST, ignoreCancelled=true) void interactEntity(PlayerInteractEntityEvent e) { if (noInteract(e.getPlayer())) e.setCancelled(true); }
    @EventHandler(priority=EventPriority.HIGHEST, ignoreCancelled=true) void breakBlock(BlockBreakEvent e) { if (noInteract(e.getPlayer())) e.setCancelled(true); }
    @EventHandler(priority=EventPriority.HIGHEST, ignoreCancelled=true) void placeBlock(BlockPlaceEvent e) { if (noInteract(e.getPlayer())) e.setCancelled(true); }
    @EventHandler(priority=EventPriority.HIGHEST, ignoreCancelled=true) void drop(PlayerDropItemEvent e) { if (noInteract(e.getPlayer())) e.setCancelled(true); }
    @EventHandler(priority=EventPriority.HIGHEST, ignoreCancelled=true) void pickup(EntityPickupItemEvent e) { if (e.getEntity() instanceof Player player && noInteract(player)) e.setCancelled(true); }
    @EventHandler(priority=EventPriority.HIGHEST, ignoreCancelled=true) void swap(PlayerSwapHandItemsEvent e) { if (noInteract(e.getPlayer())) e.setCancelled(true); }
    @EventHandler(priority=EventPriority.HIGHEST, ignoreCancelled=true) void consume(PlayerItemConsumeEvent e) { if (noInteract(e.getPlayer())) e.setCancelled(true); }
    @EventHandler(priority=EventPriority.HIGHEST, ignoreCancelled=true) void click(InventoryClickEvent e) { if (e.getWhoClicked() instanceof Player player && noInteract(player)) e.setCancelled(true); }
    @EventHandler(priority=EventPriority.HIGHEST, ignoreCancelled=true) void drag(InventoryDragEvent e) { if (e.getWhoClicked() instanceof Player player && noInteract(player)) e.setCancelled(true); }
    @EventHandler(priority=EventPriority.HIGHEST, ignoreCancelled=true) void vehicle(VehicleEnterEvent e) { if (e.getEntered() instanceof Player player && frozen(player)) e.setCancelled(true); }
    @EventHandler(priority=EventPriority.HIGHEST, ignoreCancelled=true) void damage(EntityDamageEvent e) {
        if (e.getEntity() instanceof Player target && frozen(target) && p.getConfig().getBoolean("freeze.prevent-damage")) e.setCancelled(true);
        if (e instanceof EntityDamageByEntityEvent by) {
            Entity source = by.getDamager();
            if (source instanceof Projectile projectile && projectile.getShooter() instanceof Entity shooter) source = shooter;
            if (source instanceof Player player && noInteract(player)) e.setCancelled(true);
        }
    }
}
