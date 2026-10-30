package dev.gep;

import io.papermc.paper.event.player.ChatEvent;
import io.papermc.paper.chat.ChatRenderer;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.chat.SignedMessage;
import net.kyori.adventure.text.Component;
import org.bukkit.*;
import org.bukkit.damage.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.player.*;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.*;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@SuppressWarnings({"deprecation", "removal"})
class GEPTest {
    ServerMock server; GEP p;
    @BeforeEach void start() {
        // MockBukkit has no selector parser. Supply resolved target fixtures for
        // command behavior tests; Paper remains responsible for native parsing.
        server = MockBukkit.mock(new ServerMock() {
            @Override public List<org.bukkit.entity.Entity> selectEntities(org.bukkit.command.CommandSender sender, String selector) {
                return switch (selector) {
                    case "@a" -> new ArrayList<>(getOnlinePlayers());
                    case "@s" -> sender instanceof org.bukkit.entity.Player player ? List.of(player) : List.of();
                    default -> throw new IllegalArgumentException("Unsupported selector in test fixture");
                };
            }
        }); p = MockBukkit.load(GEP.class); assertTrue(p.isEnabled());
        p.getConfig().set("death-lightning.enabled", false);
        p.getConfig().set("staff-requests.cooldown-seconds", 0);
    }
    @AfterEach void stop() { MockBukkit.unmock(); }
    PlayerMock player(String name, double x) {
        PlayerMock player = server.addPlayer(name); player.setOp(false);
        player.teleport(new Location(server.getWorlds().getFirst(), x, 64, 0)); return player;
    }
    void grant(PlayerMock player, String node) { player.addAttachment(p, "gep." + node, true); }
    void ticks() { server.getScheduler().performTicks(2); }
    ChatEvent chat(PlayerMock player, Audience... viewers) {
        Component message = Component.text("hello");
        return new ChatEvent(player, new HashSet<>(Arrays.asList(viewers)), ChatRenderer.defaultRenderer(), message, message, SignedMessage.system("hello", message));
    }
    PlayerDeathEvent death(PlayerMock player) {
        return new PlayerDeathEvent(player, DamageSource.builder(DamageType.GENERIC).build(), new ArrayList<>(), 0, "vanilla");
    }
    @Test void localBoundaryWorldsAndGranularStaffPermissions() {
        var a = player("Alpha", 0); var boundary = player("Boundary", 100); var far = player("Far", 101); var staff = player("Helper", 1000);
        grant(staff, "requests.view");
        var e = chat(a, a, boundary, far, staff, server.getConsoleSender()); p.rules.localChat(e);
        assertTrue(e.viewers().contains(boundary)); assertFalse(e.viewers().contains(far)); assertFalse(e.viewers().contains(staff));
        grant(staff, "chat.seeall"); e = chat(a, staff); p.rules.localChat(e); assertTrue(e.viewers().contains(staff));
        boundary.teleport(new Location(server.addSimpleWorld("other"), 0, 64, 0)); e = chat(a, boundary); p.rules.localChat(e); assertFalse(e.viewers().contains(boundary));
        p.getConfig().set("local-chat.enabled", false); e = chat(a, far); p.rules.localChat(e); assertTrue(e.viewers().contains(far));
    }
    @Test void muteRequiresSeparateBypassAndPersists() {
        var admin = player("Helper", 0); var user = player("User", 1); grant(admin, "chat.mute");
        p.mute(admin, new String[]{"on"}); assertTrue(p.rules.muted(admin)); assertTrue(p.rules.muted(user));
        grant(admin, "chat.mute.bypass"); assertFalse(p.rules.muted(admin));
        var e = chat(user); p.rules.blockChat(e); assertTrue(e.isCancelled());
        var reload = new Store(p); assertTrue(reload.data.getBoolean("chat-muted"));
        p.getConfig().set("chat-mute.enabled", false); assertFalse(p.rules.muted(user));
    }
    @Test void freezePreservesLookAndBlocksTeleportDamageAndSurvivesReload() {
        var admin = player("Helper", 0); var user = player("User", 5); grant(admin, "freeze");
        p.freeze(admin, new String[]{"User", "on"});
        var move = new PlayerMoveEvent(user, user.getLocation(), new Location(user.getWorld(), 20, 80, 30, 80, 35)); p.rules.move(move);
        assertTrue(move.isCancelled()); assertEquals(5, move.getFrom().getX()); assertEquals(64, move.getFrom().getY()); assertEquals(80, move.getFrom().getYaw()); assertEquals(35, move.getFrom().getPitch());
        var tp = new PlayerTeleportEvent(user, user.getLocation(), new Location(user.getWorld(), 90, 64, 0)); p.rules.teleport(tp); assertTrue(tp.isCancelled());
        var damage = new EntityDamageEvent(user, EntityDamageEvent.DamageCause.FALL, 10); p.rules.damage(damage); assertTrue(damage.isCancelled());
        p.store = new Store(p); p.rules.anchors.clear(); assertTrue(p.rules.frozen(user)); assertEquals(5, p.rules.anchorFor(user).getX());
        p.freeze(admin, new String[]{"User", "off"}); assertFalse(p.rules.frozen(user));
        assertThrows(IllegalArgumentException.class, () -> p.revive(admin, new String[]{"User"}));
    }
    @Test void spectatorBlocksChatCommandsAndTeleportButCanFly() {
        var user = player("User", 0); user.setGameMode(GameMode.SPECTATOR);
        var chat = chat(user); p.rules.blockChat(chat); assertTrue(chat.isCancelled());
        for (String command : List.of("/msg Helper secret", "/minecraft:tell Helper secret", "/tp Helper", "/staff", "/back")) {
            var event = new PlayerCommandPreprocessEvent(user, command); p.rules.commands(event); assertTrue(event.isCancelled(), command);
        }
        var tp = new PlayerTeleportEvent(user, user.getLocation(), new Location(user.getWorld(), 90, 64, 0), PlayerTeleportEvent.TeleportCause.SPECTATE);
        p.rules.teleport(tp); assertTrue(tp.isCancelled());
        var move = new PlayerMoveEvent(user, user.getLocation(), new Location(user.getWorld(), 10, 90, 0)); p.rules.move(move); assertEquals(90, move.getTo().getY());
        grant(user, "spectator.bypass"); assertFalse(p.rules.restricted(user));
    }
    @Test void spectatorEliminationReenforcesAndOfflineReviveRecovers() {
        var user = player("User", 0); p.rules.died(death(user)); ticks();
        String root = p.store.player(user.getUniqueId()); assertTrue(p.store.data.getBoolean(root + "eliminated"));
        p.rules.enforce(user); assertEquals(GameMode.SPECTATOR, user.getGameMode());
        var mode = new PlayerGameModeChangeEvent(user, GameMode.CREATIVE); p.rules.mode(mode); assertTrue(mode.isCancelled());
        p.store = new Store(p); assertTrue(p.store.data.getBoolean(root + "eliminated"));
        p.revive(server.getConsoleSender(), new String[]{"User"}); assertFalse(p.store.data.getBoolean(root + "eliminated")); assertEquals(GameMode.SURVIVAL, user.getGameMode());
    }
    @Test void deathBanUsesOwnStateAndStaffAreExempt() {
        p.getConfig().set("elimination.mode", "ban");
        var user = player("User", 0); p.rules.died(death(user));
        String root = p.store.player(user.getUniqueId()); assertTrue(p.store.data.getBoolean(root + "death-ban"));
        var login = new PlayerLoginEvent(user, "localhost", java.net.InetAddress.getLoopbackAddress()); p.rules.login(login); assertEquals(PlayerLoginEvent.Result.KICK_BANNED, login.getResult());
        p.revive(server.getConsoleSender(), new String[]{"User"}); login = new PlayerLoginEvent(user, "localhost", java.net.InetAddress.getLoopbackAddress()); p.rules.login(login); assertEquals(PlayerLoginEvent.Result.ALLOWED, login.getResult());
        var helper = player("Helper", 0); grant(helper, "requests.view"); p.rules.died(death(helper));
        assertFalse(p.store.data.getBoolean(p.store.player(helper.getUniqueId()) + "eliminated"));
    }
    @Test void cancelledDeathDoesNotEliminateOrAnnounce() {
        var user = player("User", 0); var event = death(user); event.setCancelled(true); server.getPluginManager().callEvent(event);
        assertFalse(p.store.data.getBoolean(p.store.player(user.getUniqueId()) + "eliminated")); assertEquals("vanilla", event.getDeathMessage());
    }
    @Test void threeBackLocationsConsumedAndCancelledTeleportIgnored() {
        var helper = player("Helper", 0); grant(helper, "back");
        for (int x : List.of(10, 20, 30, 40)) { assertTrue(helper.teleport(new Location(helper.getWorld(), x, 64, 0))); ticks(); }
        assertEquals(3, p.store.backs(helper.getUniqueId()).size());
        p.store = new Store(p); p.rules.back(helper, 1); assertEquals(30, helper.getX());
        p.rules.back(helper, 1); assertEquals(20, helper.getX()); p.rules.back(helper, 1); assertEquals(10, helper.getX());
        assertTrue(p.store.backs(helper.getUniqueId()).isEmpty());
        var event = new PlayerTeleportEvent(helper, helper.getLocation(), new Location(helper.getWorld(), 100, 64, 0)); event.setCancelled(true);
        // Event dispatch respects ignoreCancelled; no origin is recorded.
        server.getPluginManager().callEvent(event); ticks(); assertTrue(p.store.backs(helper.getUniqueId()).isEmpty());
    }
    @Test void requestClaimOnlyAfterSuccessfulVisitAndPersists() {
        var user = player("User", 5); var a = player("HelperA", 0); var b = player("HelperB", 10);
        grant(a, "requests.visit.materials"); grant(b, "requests.visit.materials");
        UUID id = p.requests.create(user, Requests.Kind.MATERIALS, "");
        p.requests.visit(a, id); assertEquals(a.getUniqueId().toString(), p.requests.ticket(id).claim());
        assertThrows(IllegalArgumentException.class, () -> p.requests.visit(b, id));
        assertThrows(IllegalArgumentException.class, () -> p.requests.decide(a, id, "CLOSED"));
        p.store = new Store(p); assertEquals(a.getUniqueId().toString(), p.requests.ticket(id).claim());
        grant(b, "requests.release"); p.requests.release(b, id); p.requests.visit(b, id);
        assertEquals(b.getUniqueId().toString(), p.requests.ticket(id).claim());
        p.requests.back(a); assertEquals(0, a.getX());
    }
    @Test void failedVisitDoesNotClaimOrSaveReturn() {
        var user = player("User", 5); var helper = player("Helper", 0); grant(helper, "requests.visit.materials");
        UUID id = p.requests.create(user, Requests.Kind.MATERIALS, "");
        server.getPluginManager().registerEvents(new org.bukkit.event.Listener() {
            @org.bukkit.event.EventHandler void block(PlayerTeleportEvent e) { e.setCancelled(true); }
        }, p);
        assertThrows(IllegalArgumentException.class, () -> p.requests.visit(helper, id));
        assertEquals("", p.requests.ticket(id).claim()); assertFalse(p.store.data.contains("visits." + helper.getUniqueId()));
    }
    @Test void requestCategoriesCooldownAndDecisionsArePersistent() {
        var user = player("User", 0); var helper = player("Helper", 5); grant(helper, "requests.decide");
        p.getConfig().set("staff-requests.cooldown-seconds", 60);
        UUID id = p.requests.create(user, Requests.Kind.KILL, "OfflineTarget");
        assertThrows(IllegalArgumentException.class, () -> p.requests.create(user, Requests.Kind.MATERIALS, ""));
        p.store = new Store(p); assertThrows(IllegalArgumentException.class, () -> p.requests.create(user, Requests.Kind.LORE, ""));
        p.requests.decide(helper, id, "APPROVED"); assertEquals("APPROVED", p.requests.ticket(id).result());
        assertThrows(IllegalArgumentException.class, () -> p.requests.decide(helper, id, "DENIED"));
        assertEquals(20, user.getHealth()); assertFalse(p.store.data.getBoolean(p.store.player(user.getUniqueId()) + "eliminated"));
        p.getConfig().set("staff-requests.materials.enabled", false);
        assertThrows(IllegalArgumentException.class, () -> p.requests.create(user, Requests.Kind.MATERIALS, ""));
    }
    @Test void loreHiddenFromRegularPlayersAndRestoredOnReturn() {
        var user = player("User", 5); var helper = player("Helper", 0); var observer = player("Observer", 10);
        grant(helper, "requests.visit.lore"); grant(observer, "vanish.see");
        UUID id = p.requests.create(user, Requests.Kind.LORE, ""); p.requests.visit(helper, id);
        assertFalse(user.canSee(helper)); assertTrue(observer.canSee(helper));
        p.requests.back(helper); assertTrue(user.canSee(helper)); assertEquals(0, helper.getX());
    }
    @Test void historyAndAuditCanBeDisabledAndConfigRejectsBadValues() throws Exception {
        var user = player("User", 0); int size = p.store.data.getStringList(p.store.player(user.getUniqueId()) + "history").size();
        p.getConfig().set("history.enabled", false); p.store.history(user.getUniqueId(), "Helper", "TEST", "test");
        assertEquals(size, p.store.data.getStringList(p.store.player(user.getUniqueId()) + "history").size());
        p.store.audit("Helper", "TEST", "no message content"); assertTrue(java.nio.file.Files.exists(p.getDataFolder().toPath().resolve("audit")));
        p.getConfig().set("local-chat.radius-blocks", -1); assertThrows(IllegalArgumentException.class, p::validate);
    }
    @Test void corruptStoreRefusesToLoadAndAtomicCommitRollsBackOnError() throws Exception {
        String before = p.store.data.saveToString();
        assertThrows(IllegalStateException.class, () -> p.store.change(() -> { p.store.data.set("test", true); throw new IllegalStateException("test"); }));
        assertEquals(before, p.store.data.saveToString());
        p.store.save(); java.nio.file.Files.writeString(p.store.file, "broken: [");
        assertThrows(IllegalStateException.class, () -> new Store(p));
    }
    String messages(PlayerMock player) {
        StringBuilder lines = new StringBuilder(); String line;
        while ((line = player.nextMessage()) != null) lines.append(line).append('\n');
        return lines.toString();
    }
    @Test void freezeCancelsPaperMovementWithoutStartingABlockedTeleport() {
        var user = player("User", 5); p.freeze(server.getConsoleSender(), new String[]{"User", "on"}); messages(user);
        Location paperFrom = user.getLocation();
        var move = new PlayerMoveEvent(user, paperFrom, new Location(user.getWorld(), 6, 65, 1, 125, 40));
        server.getPluginManager().callEvent(move);
        assertTrue(move.isCancelled());
        // Paper retains this exact From object outside the event, then uses it for
        // the internal correction before checking changed destinations.
        assertSame(paperFrom, move.getFrom()); assertEquals(5, paperFrom.getX()); assertEquals(64, paperFrom.getY());
        assertEquals(125, paperFrom.getYaw()); assertEquals(40, paperFrom.getPitch());
        assertEquals("", messages(user)); assertTrue(p.rules.pendingTeleports.isEmpty());
        var look = new PlayerMoveEvent(user, paperFrom.clone(), new Location(user.getWorld(), 5, 64, 0, 150, -20));
        server.getPluginManager().callEvent(look); assertFalse(look.isCancelled()); assertEquals(150, look.getTo().getYaw());
    }
    @Test void automaticTeleportDenialsAreSilentAndExplicitWarningsAreThrottled() {
        var user = player("User", 0); p.freeze(server.getConsoleSender(), new String[]{"User", "on"}); messages(user);
        for (int i = 0; i < 20; i++) {
            var correction = new PlayerTeleportEvent(user, user.getLocation(), new Location(user.getWorld(), 1, 64, 0), PlayerTeleportEvent.TeleportCause.PLUGIN);
            server.getPluginManager().callEvent(correction); assertTrue(correction.isCancelled());
        }
        assertEquals("", messages(user));
        for (int i = 0; i < 20; i++) {
            var command = new PlayerTeleportEvent(user, user.getLocation(), new Location(user.getWorld(), 10, 64, 0), PlayerTeleportEvent.TeleportCause.COMMAND);
            server.getPluginManager().callEvent(command); assertTrue(command.isCancelled());
        }
        assertEquals(1, messages(user).lines().filter(s -> s.contains("Teleporting is currently restricted")).count());
        p.getConfig().set("teleport-restrictions.notify-on-denial", false); p.rules.teleportWarnings.clear();
        var command = new PlayerTeleportEvent(user, user.getLocation(), new Location(user.getWorld(), 10, 64, 0), PlayerTeleportEvent.TeleportCause.COMMAND);
        server.getPluginManager().callEvent(command); assertTrue(command.isCancelled()); assertEquals("", messages(user));
    }
    @Test void vanillaSpyNeedsOwnPermissionAndRespectsLaterCancellation() {
        var sender = player("Sender", 0); var target = player("Target", 10); var spy = player("Spy", 1000); var helper = player("Helper", 1000);
        sender.addAttachment(p, "minecraft.command.msg", true); grant(spy, "spy"); grant(helper, "requests.view");
        for (var player : List.of(sender,target,spy,helper)) messages(player);
        var event = new PlayerCommandPreprocessEvent(sender, "/minecraft:msg Target secret"); p.rules.spy(event); ticks();
        assertTrue(messages(spy).contains("Sender → Target: secret")); assertFalse(messages(helper).contains("secret"));
        event = new PlayerCommandPreprocessEvent(sender, "/w Target blocked"); p.rules.spy(event); event.setCancelled(true); ticks();
        assertFalse(messages(spy).contains("blocked"));
        assertFalse(p.store.data.saveToString().contains("secret"));
    }
    @Test void independentFeatureTogglesAndRecoveryReturn() {
        var user = player("User", 5); var helper = player("Helper", 0); grant(helper, "requests.visit.materials");
        UUID id = p.requests.create(user, Requests.Kind.MATERIALS, ""); p.requests.visit(helper, id);
        p.getConfig().set("staff-requests.enabled", false); p.getConfig().set("back.enabled", false);
        p.requests.back(helper); assertEquals(0, helper.getX());
        p.getConfig().set("elimination.enabled", false); p.rules.died(death(user)); assertFalse(p.store.data.getBoolean(p.store.player(user.getUniqueId()) + "eliminated"));
        p.getConfig().set("death-messages.enabled", false); var death = death(user); p.rules.deathMessage(death); assertEquals("vanilla", death.getDeathMessage());
        p.store.data.set(p.store.player(user.getUniqueId()) + "frozen", true); p.getConfig().set("freeze.enabled", false); assertFalse(p.rules.frozen(user));
        p.getConfig().set("spectator-restrictions.enabled", false); user.setGameMode(GameMode.SPECTATOR); assertFalse(p.rules.restricted(user));
    }
    @Test void offlineDecisionNotificationSurvivesReloadAndIsAcknowledged() {
        var user = player("User", 0); var helper = player("Helper", 5); grant(helper, "requests.decide");
        UUID id = p.requests.create(user, Requests.Kind.KILL, "Target");
        user.disconnect(); p.requests.decide(helper, id, "DENIED");
        assertFalse(p.store.data.getBoolean(p.requests.root(id) + "notified"));
        p.store = new Store(p); messages(user); p.requests.deliver(user, p.requests.ticket(id));
        assertTrue(messages(user).contains("denied")); assertTrue(p.store.data.getBoolean(p.requests.root(id) + "notified"));
    }
    @Test void backKeepsUnavailableWorldAndCanSelectOlderLocation() {
        var helper = player("Helper", 0); grant(helper, "back");
        helper.teleport(new Location(helper.getWorld(), 10, 64, 0)); helper.teleport(new Location(helper.getWorld(), 20, 64, 0));
        // /back flushes current-tick origins before choosing one.
        p.rules.back(helper, 2); assertEquals(0, helper.getX());
        assertEquals(1, p.store.backs(helper.getUniqueId()).size());
        var map = new LinkedHashMap<String,Object>(Store.location(helper.getLocation())); map.put("world", UUID.randomUUID().toString());
        p.store.data.set(p.store.player(helper.getUniqueId()) + "back", List.of(map));
        assertThrows(IllegalArgumentException.class, () -> p.rules.back(helper, 1)); assertEquals(1, p.store.backs(helper.getUniqueId()).size());
    }
    @Test void freezePermissionIncludesSelectorsAndConfigCanDisableThem() {
        var helper = player("Helper", 0); player("User", 1); grant(helper, "freeze");
        p.freeze(helper, new String[]{"@a", "on"});
        assertTrue(p.rules.frozen(server.getPlayerExact("User")));
        p.getConfig().set("target-selectors.enabled", false);
        assertThrows(IllegalArgumentException.class, () -> p.freeze(helper, new String[]{"@a", "on"}));
        p.freeze(helper, new String[]{"User", "on"}); assertTrue(p.rules.frozen(server.getPlayerExact("User")));
    }
    @Test void freezeAllSkipsExemptStaffAndUnfreezesEveryoneMatched() {
        var helper = player("Helper", 0); var a = player("Alpha", 1); var b = player("Bravo", 2);
        grant(helper, "freeze"); grant(helper, "freeze.exempt");
        p.freeze(helper, new String[]{"@a", "on"});
        assertFalse(p.rules.frozen(helper)); assertTrue(p.rules.frozen(a)); assertTrue(p.rules.frozen(b));
        p.freeze(helper, new String[]{"@a", "off"}); assertFalse(p.rules.frozen(a)); assertFalse(p.rules.frozen(b));
    }
    @Test void freezeSelfSelectorAndInvalidModeDoNotAffectOtherPlayers() {
        var helper = player("Helper", 0); var user = player("User", 1); grant(helper, "freeze"); grant(helper, "selectors");
        assertThrows(IllegalArgumentException.class, () -> p.freeze(helper, new String[]{"@a", "invalid"}));
        assertFalse(p.rules.frozen(user)); assertFalse(p.rules.frozen(helper));
        p.freeze(helper, new String[]{"@s", "on"}); assertTrue(p.rules.frozen(helper)); assertFalse(p.rules.frozen(user));
    }


    @Test void chatTargetIsPrivateAndInvalidInputCanBeRetried() {
        var user = player("User", 0); var other = player("Other", 5);
        p.requests.prompts.put(user.getUniqueId(), System.currentTimeMillis() + 120000);
        var event = chat(user, user, other);
        event.message(Component.text("invalid name"));
        p.requests.input(event);
        assertTrue(event.isCancelled());
        assertTrue(p.requests.prompts.containsKey(user.getUniqueId()));
        assertTrue(p.requests.tickets().isEmpty());
        event = chat(user, user, other); event.message(Component.text("OfflineTarget")); p.requests.input(event); assertTrue(event.isCancelled());
        assertEquals("OfflineTarget", p.requests.tickets().getFirst().target());
        assertFalse(p.requests.prompts.containsKey(user.getUniqueId()));
        assertThrows(IllegalArgumentException.class, () -> p.requests.input(user, "AnotherTarget"));
    }
    @Test void cancelAndExpiryDoNotSubmitTickets() {
        var user = player("User", 0);
        p.requests.prompts.put(user.getUniqueId(), System.currentTimeMillis() + 120000);
        p.requests.command(user, new String[]{"cancel"});
        assertFalse(p.requests.prompts.containsKey(user.getUniqueId()));
        p.requests.prompts.put(user.getUniqueId(), 1L);
        p.requests.input(user, "Target");
        assertTrue(p.requests.tickets().isEmpty());
        assertFalse(p.requests.prompts.containsKey(user.getUniqueId()));
    }
    @Test void targetCommandCompletesVisibleUsernamesOnlyDuringPrompt() {
        var user = player("User", 0); var target = player("Target", 5); var hidden = player("Hidden", 10);
        user.hidePlayer(p, hidden);
        assertTrue(p.requests.tabs(user, new String[]{"target", ""}).isEmpty());
        p.requests.prompts.put(user.getUniqueId(), System.currentTimeMillis() + 120000);
        assertTrue(p.requests.tabs(user, new String[]{"target", "Ta"}).contains("Target"));
        assertFalse(p.requests.tabs(user, new String[]{"target", ""}).contains("Hidden"));
        p.requests.command(user, new String[]{"target", "Target"});
        assertEquals("Target", p.requests.tickets().getFirst().target());
    }

    @Test void standaloneVanishRequiresOwnPermissionAndPersists() {
        var helper = player("Helper", 0); var user = player("User", 5); var observer = player("Observer", 10);
        grant(helper, "requests.visit.lore"); grant(observer, "vanish.see");
        assertThrows(IllegalArgumentException.class, () -> p.requests.vanish(helper, new String[0]));
        grant(helper, "vanish");
        p.requests.vanish(helper, new String[0]);
        assertFalse(user.canSee(helper)); assertTrue(observer.canSee(helper));
        p.store = new Store(p); p.requests.tick();
        assertTrue(p.requests.vanished(helper)); assertFalse(user.canSee(helper));
        var newcomer = player("Newcomer", 15); p.requests.tick(); assertFalse(newcomer.canSee(helper));
        p.requests.vanish(helper, new String[0]); assertTrue(user.canSee(helper));
        assertFalse(p.requests.vanished(helper));
    }
    @Test void everyLoreTeleportForcesVanishAndManualOffDoesNotStickToNextVisit() {
        var helper = player("Helper", 0); var user = player("User", 5);
        grant(helper, "vanish"); grant(helper, "requests.visit.lore");
        UUID id = p.requests.create(user, Requests.Kind.LORE, "");
        p.requests.vanish(helper, new String[]{"off"});
        p.requests.visit(helper, id); assertFalse(user.canSee(helper));
        p.requests.vanish(helper, new String[]{"off"}); p.requests.tick(); assertTrue(user.canSee(helper));
        p.requests.visit(helper, id); assertFalse(user.canSee(helper));
        p.store = new Store(p); p.requests.tick(); assertFalse(user.canSee(helper));
        p.requests.back(helper); assertTrue(user.canSee(helper));
    }
    @Test void requestReturnAndOtherVisitsPreserveManualVanish() {
        var helper = player("Helper", 0); var user = player("User", 5);
        grant(helper, "vanish"); grant(helper, "requests.visit.lore"); grant(helper, "requests.visit.materials");
        p.requests.vanish(helper, new String[]{"on"});
        UUID lore = p.requests.create(user, Requests.Kind.LORE, "");
        p.requests.visit(helper, lore); p.requests.back(helper); assertFalse(user.canSee(helper));
        p.getConfig().set("staff-requests.cooldown-seconds", 0);
        UUID materials = p.requests.create(user, Requests.Kind.MATERIALS, "");
        p.requests.visit(helper, materials); p.requests.tick(); assertFalse(user.canSee(helper));
        p.requests.back(helper); assertTrue(p.requests.manualHidden(helper));
        p.requests.vanish(helper, new String[]{"off"}); assertTrue(user.canSee(helper));
    }
    @Test void cancelledLoreTeleportRestoresPreviousVanishState() {
        var helper = player("Helper", 0); var user = player("User", 5);
        grant(helper, "vanish"); grant(helper, "requests.visit.lore");
        UUID id = p.requests.create(user, Requests.Kind.LORE, "");
        server.getPluginManager().registerEvents(new org.bukkit.event.Listener() {
            @org.bukkit.event.EventHandler void block(PlayerTeleportEvent e) { e.setCancelled(true); }
        }, p);
        assertThrows(IllegalArgumentException.class, () -> p.requests.visit(helper, id));
        assertTrue(user.canSee(helper));
        p.requests.vanish(helper, new String[]{"on"});
        assertThrows(IllegalArgumentException.class, () -> p.requests.visit(helper, id));
        assertFalse(user.canSee(helper)); assertTrue(p.requests.manualHidden(helper));
        assertEquals("", p.requests.ticket(id).claim());
    }
    @Test void vanishConfigAndPermissionChangesRefreshVisibility() {
        var helper = player("Helper", 0); var user = player("User", 5);
        var access = helper.addAttachment(p, "gep.vanish", true);
        p.requests.vanish(helper, new String[]{"on"}); assertFalse(user.canSee(helper));
        p.getConfig().set("vanish.enabled", false); p.requests.tick(); assertTrue(user.canSee(helper));
        assertThrows(IllegalArgumentException.class, () -> p.requests.vanish(helper, new String[0]));
        p.getConfig().set("vanish.enabled", true); p.requests.tick(); assertFalse(user.canSee(helper));
        access.setPermission("gep.vanish", false); p.requests.tick(); assertTrue(user.canSee(helper));
        access.setPermission("gep.vanish", true); p.requests.tick(); assertFalse(user.canSee(helper));
        p.requests.showAll(); assertTrue(user.canSee(helper));
        assertTrue(p.store.data.getBoolean(p.store.player(helper.getUniqueId()) + "vanished"));
    }

    @Test void configMenuRequiresSeparatePermission() {
        var user=player("User",0);grant(user,"reload");
        assertThrows(IllegalArgumentException.class,()->ConfigMenu.open(p,user,0));
        assertThrows(IllegalArgumentException.class,()->ConfigMenu.change(p,user,"freeze.enabled",false));
        grant(user,"config");ConfigMenu.open(p,user,0);
        assertInstanceOf(ConfigMenu.class,user.getOpenInventory().getTopInventory().getHolder());
    }
    @Test void configEditsSaveAndValidateWithoutLosingOldValue() throws Exception {
        var user=player("Helper",0);grant(user,"config");
        ConfigMenu.change(p,user,"local-chat.radius-blocks",150.0);
        var disk=org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(new java.io.File(p.getDataFolder(),"config.yml"));
        assertEquals(150.0,disk.getDouble("local-chat.radius-blocks"));
        assertThrows(IllegalArgumentException.class,()->ConfigMenu.change(p,user,"local-chat.radius-blocks",-1.0));
        assertEquals(150.0,p.getConfig().getDouble("local-chat.radius-blocks"));
        assertThrows(IllegalArgumentException.class,()->ConfigMenu.change(p,user,"elimination.mode","invalid"));
        assertEquals("spectator",p.getConfig().getString("elimination.mode"));
        assertThrows(IllegalArgumentException.class,()->ConfigMenu.change(p,user,"death-messages.messages",List.of("missing placeholder")));
        ConfigMenu.change(p,user,"freeze.enabled",false);assertFalse(p.enabled("freeze"));
    }
    @Test void configMenuClickTogglesAndCancelsInventoryMovement() {
        var user=player("Helper",0);grant(user,"config");ConfigMenu.show(p,user,"freeze",0);
        var menu=(ConfigMenu)user.getOpenInventory().getTopInventory().getHolder();
        int slot=menu.keys.entrySet().stream().filter(e->e.getValue().equals("freeze.enabled")).findFirst().orElseThrow().getKey();
        var event=new org.bukkit.event.inventory.InventoryClickEvent(user.getOpenInventory(),org.bukkit.event.inventory.InventoryType.SlotType.CONTAINER,slot,org.bukkit.event.inventory.ClickType.LEFT,org.bukkit.event.inventory.InventoryAction.PICKUP_ALL);
        server.getPluginManager().callEvent(event);assertTrue(event.isCancelled());ticks();assertFalse(p.enabled("freeze"));
    }

    @Test void generalSupportCanBeSubmittedClaimedReturnedAndClosed() {
        var user=player("User",5);var helper=player("Helper",0);
        grant(helper,"requests.visit.support");grant(helper,"requests.close");
        p.requests.open(user);assertNotNull(user.getOpenInventory().getTopInventory().getItem(16));user.closeInventory();
        UUID id=p.requests.create(user,Requests.Kind.SUPPORT,"");
        p.requests.visit(helper,id);assertTrue(user.canSee(helper));assertEquals(5,helper.getX());
        p.store=new Store(p);assertEquals(Requests.Kind.SUPPORT,p.requests.ticket(id).kind());
        assertEquals(helper.getUniqueId().toString(),p.requests.ticket(id).claim());
        p.requests.back(helper);assertEquals(0,helper.getX());
        p.requests.decide(helper,id,"CLOSED");assertEquals("CLOSED",p.requests.ticket(id).result());
        p.getConfig().set("staff-requests.support.enabled",false);
        assertThrows(IllegalArgumentException.class,()->p.requests.create(user,Requests.Kind.SUPPORT,""));
    }

    @Test void requestMenuCentersEveryEnabledCombinationAndMapsClicks() {
        var user=player("User",0);
        for(int mask=0;mask<16;mask++) {
            for(var kind:Requests.Kind.values())p.getConfig().set("staff-requests."+kind.key()+".enabled",(mask & (1<<kind.ordinal()))!=0);
            p.requests.open(user);
            var menu=(Requests.Menu)user.getOpenInventory().getTopInventory().getHolder();
            assertEquals(Integer.bitCount(mask),menu.options.size());
            assertArrayEquals(Requests.centeredSlots(Integer.bitCount(mask)),menu.options.keySet().stream().mapToInt(Integer::intValue).sorted().toArray());
            for(var entry:menu.options.entrySet())assertEquals(entry.getValue().icon,menu.inventory.getItem(entry.getKey()).getType());
        }
        for(var kind:Requests.Kind.values())p.getConfig().set("staff-requests."+kind.key()+".enabled",kind==Requests.Kind.SUPPORT);
        p.requests.open(user);
        var event=new org.bukkit.event.inventory.InventoryClickEvent(user.getOpenInventory(),org.bukkit.event.inventory.InventoryType.SlotType.CONTAINER,13,org.bukkit.event.inventory.ClickType.LEFT,org.bukkit.event.inventory.InventoryAction.PICKUP_ALL);
        server.getPluginManager().callEvent(event);ticks();assertEquals(Requests.Kind.SUPPORT,p.requests.tickets().getFirst().kind());
    }
}
