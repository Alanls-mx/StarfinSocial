package com.starfinplugins.social.bungee;

import com.starfinplugins.social.api.*;
import com.starfinplugins.social.common.SocialStore;
import com.starfinplugins.social.common.PartyChatState;
import com.starfinplugins.social.common.PartyRules;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.lang.reflect.Field;
import java.sql.SQLException;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.event.PlayerDisconnectEvent;
import net.md_5.bungee.api.event.PostLoginEvent;
import net.md_5.bungee.api.event.ServerConnectedEvent;
import net.md_5.bungee.api.event.ChatEvent;
import net.md_5.bungee.api.plugin.Command;
import net.md_5.bungee.api.plugin.Listener;
import net.md_5.bungee.api.plugin.Plugin;
import net.md_5.bungee.api.scheduler.ScheduledTask;
import net.md_5.bungee.event.EventHandler;

/** Network authority. All SQL work runs on the bounded worker pool. */
public final class StarfinSocialBungee extends Plugin implements Listener {
    private SocialConfig config;
    private SocialMessages messages;
    private ThreadPoolExecutor io;
    private HikariDataSource pool;
    private volatile SocialStore store;
    private LoginGate loginGate;
    private boolean loginPresent;
    private ScheduledTask maintenance;
    private final Map<UUID, UUID> sessions = new ConcurrentHashMap<UUID, UUID>();
    private final Set<UUID> chatMode = java.util.Collections.newSetFromMap(
        new ConcurrentHashMap<UUID, Boolean>());
    private final CopyOnWriteArrayList<PartyEventListener> listeners =
        new CopyOnWriteArrayList<PartyEventListener>();
    private final PartyService service = new Service();
    private final PartyChatState chatState = new PartyChatState();
    private final AtomicBoolean maintaining = new AtomicBoolean();
    private volatile boolean stopping;
    private volatile long lastSweepAt, lastCleanupAt, lastHeartbeatAt, lastInitAttemptAt;
    private volatile long lastDatabaseWarningAt;

    @Override public void onEnable() {
        try {
            config = SocialConfig.load(getDataFolder());
            messages = new SocialMessages(getDataFolder(), config.locale);
            Plugin login = getProxy().getPluginManager().getPlugin("StarfinLogin");
            loginPresent = login != null;
            if (loginPresent) try { loginGate = new LoginGate(login); }
            catch (ReflectiveOperationException | RuntimeException error) {
                getLogger().severe("StarfinLogin detectado sem sessão verificável; comandos de Party bloqueados.");
            }
            io = new ThreadPoolExecutor(2, 4, 30, TimeUnit.SECONDS,
                new ArrayBlockingQueue<Runnable>(128), runnable -> {
                    Thread t = new Thread(runnable, "StarfinSocial-SQL");
                    t.setDaemon(true); return t;
                }, new ThreadPoolExecutor.AbortPolicy());
            getProxy().getPluginManager().registerListener(this, this);
            registerSafe("party", new PartyCommand(this, "party"));
            if (config.aliasP) registerSafe("p", new PartyCommand(this, "p"));
            if (config.aliasPc) registerSafe("pc", new PartyChatCommand(this));
            runIo(() -> initialize());
            maintenance = getProxy().getScheduler().schedule(this, () -> {
                if (!maintaining.compareAndSet(false,true)) return;
                if (!runIo(() -> {
                    try { maintain(); }
                    finally { maintaining.set(false); }
                })) maintaining.set(false);
            },
                250, 250, TimeUnit.MILLISECONDS);
            getLogger().info("StarfinSocial-Bungee iniciado; banco será validado assincronamente.");
        } catch (Exception error) {
            getLogger().log(Level.SEVERE, "StarfinSocial-Bungee não iniciou", error);
            getProxy().getPluginManager().unregisterCommands(this);
            getProxy().getPluginManager().unregisterListeners(this);
            if (io != null) io.shutdownNow();
            if (pool != null) pool.close();
        }
    }

    private synchronized void initialize() {
        if (stopping || store != null) return;
        long now = System.currentTimeMillis();
        if (now - lastInitAttemptAt < 5000) return;
        lastInitAttemptAt = now;
        try {
            if (pool == null) {
                HikariConfig hikari = new HikariConfig();
                hikari.setJdbcUrl("jdbc:mysql://" + config.host + ":" + config.port + "/"
                    + config.database + "?sslMode=" + config.sslMode
                    + "&useUnicode=true&characterEncoding=UTF-8");
                hikari.setUsername(config.user);
                hikari.setPassword(config.password);
                hikari.setDriverClassName(com.mysql.cj.jdbc.Driver.class.getName());
                hikari.setMaximumPoolSize(4);
                hikari.setMinimumIdle(0);
                hikari.setConnectionTimeout(5000);
                hikari.setInitializationFailTimeout(-1);
                hikari.setPoolName("StarfinSocial-Bungee");
                pool = new HikariDataSource(hikari);
            }
            SocialStore next = new SocialStore(pool, config.rules);
            next.migrate();
            store = next;
            getLogger().info("StarfinSocial MySQL pronto (ss_schema_migrations v2).");
            for (ProxiedPlayer player : getProxy().getPlayers()) startSession(player);
        } catch (Exception error) {
            warnDatabase("StarfinSocial MySQL indisponível; nova tentativa em breve ("
                + error.getClass().getSimpleName() + ").");
        }
    }

    private void maintain() {
        if (stopping) return;
        SocialStore db = store;
        if (db == null) { initialize(); return; }
        try {
            long now = System.currentTimeMillis();
            boolean heartbeatDue = now - lastHeartbeatAt >= 10000;
            Map<UUID, UUID> recipients = new java.util.HashMap<UUID, UUID>();
            for (ProxiedPlayer player : getProxy().getPlayers()) {
                UUID session = sessions.get(player.getUniqueId());
                if (session == null) { startSession(player); continue; }
                if (heartbeatDue) {
                    boolean valid = db.heartbeat(player.getUniqueId(), session, server(player));
                    if (!valid) {
                        sessions.remove(player.getUniqueId(), session);
                        chatMode.remove(player.getUniqueId());
                        continue;
                    }
                    PartySnapshot current = db.getParty(player.getUniqueId());
                    if (current == null) chatState.forget(player.getUniqueId());
                    else chatState.apply(current);
                }
                if (authenticated(player)) recipients.put(player.getUniqueId(), session);
            }
            if (heartbeatDue) lastHeartbeatAt = now;
            for (SocialStore.Notification notification : db.claimNotifications(recipients, 100)) {
                UUID session = recipients.get(notification.recipient);
                ProxiedPlayer player = getProxy().getPlayer(notification.recipient);
                if (session == null || player == null
                        || !session.equals(sessions.get(notification.recipient))) continue;
                if (notification.partyId != null && !"CHAT".equals(notification.kind)
                        && !"INVITE".equals(notification.kind))
                    chatState.apply(db.getPartyById(UUID.fromString(notification.partyId)));
                if ("INVITE".equals(notification.kind)
                        && (notification.partyId == null || notification.actorId == null
                            || !db.inviteDeliverable(player.getUniqueId(),
                                UUID.fromString(notification.actorId),
                                UUID.fromString(notification.partyId)))) {
                    db.markDelivered(notification.id, session);
                    continue;
                }
                if (db.markDelivered(notification.id, session)) deliver(player, notification);
            }
            if (now - lastSweepAt >= 10000) { db.sweep(); lastSweepAt = now; }
            if (now - lastCleanupAt >= 60000) { db.cleanupNotifications(); lastCleanupAt = now; }
        } catch (SQLException | RuntimeException error) {
            warnDatabase("Falha transitória de Party/MySQL; operações serão rejeitadas até recuperar.");
        }
    }

    private synchronized void warnDatabase(String message) {
        long now = System.currentTimeMillis();
        if (now - lastDatabaseWarningAt < 30000) return;
        lastDatabaseWarningAt = now;
        getLogger().warning(message);
    }

    private void deliver(ProxiedPlayer player, SocialStore.Notification n) throws SQLException {
        UUID actor = n.actorId == null ? null : UUID.fromString(n.actorId);
        String name = actor == null ? "" : store.name(actor);
        Map<String, String> vars = vars("player", name, "message", n.body,
            "kind", n.kind, "setting", n.body);
        if ("INVITE".equals(n.kind)) {
            player.sendMessage(messages.line("invite-received", vars));
            TextComponent accept = messages.line("invite-accept", vars);
            accept.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND,
                "/party accept " + name));
            TextComponent deny = messages.line("invite-deny", vars);
            deny.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND,
                "/party deny " + name));
            player.sendMessage(accept);
            player.sendMessage(deny);
        } else if ("CHAT".equals(n.kind)) player.sendMessage(messages.line("chat-line", vars));
        else {
            String key;
            switch (n.kind) {
                case "JOIN": key = "member-joined"; break;
                case "LEAVE": key = "member-left"; break;
                case "KICK": key = "member-kicked"; break;
                case "KICKED": key = "kicked"; break;
                case "PROMOTE": key = "promoted"; break;
                case "LEADER": key = "leader-changed"; break;
                case "DISBAND": key = "disbanded"; break;
                case "SETTINGS": key = "settings-changed"; break;
                default: key = "notification";
            }
            player.sendMessage(messages.line(key, vars));
        }
    }

    @EventHandler public void onJoin(PostLoginEvent event) {
        ProxiedPlayer player = event.getPlayer();
        runIo(() -> startSession(player));
    }
    @EventHandler public void onServer(ServerConnectedEvent event) {
        ProxiedPlayer player = event.getPlayer();
        runIo(() -> {
            SocialStore db = store;
            UUID session = sessions.get(player.getUniqueId());
            if (db != null && session != null) try {
                db.heartbeat(player.getUniqueId(), session, server(player));
            } catch (SQLException ignored) { /* maintenance retries */ }
        });
    }
    @EventHandler public void onQuit(PlayerDisconnectEvent event) {
        ProxiedPlayer player = event.getPlayer();
        UUID session = sessions.remove(player.getUniqueId());
        chatMode.remove(player.getUniqueId());
        chatState.clearCooldown(player.getUniqueId());
        if (session != null) runIo(() -> {
            try { if (store != null) store.sessionEnded(player.getUniqueId(), session); }
            catch (SQLException ignored) { /* presence timeout handles crash */ }
        });
    }

    @EventHandler public void onChat(ChatEvent event) {
        if (!(event.getSender() instanceof ProxiedPlayer) || event.isCancelled()) return;
        ProxiedPlayer player = (ProxiedPlayer) event.getSender();
        if (!chatMode.contains(player.getUniqueId()) || event.isCommand()) return;
        event.setCancelled(true);
        if (!authenticated(player)) { player.sendMessage(messages.line("not-authenticated",null)); return; }
        service.sendChat(player.getUniqueId(), event.getMessage()).whenComplete((result,error) -> {
            if (error != null || result == null) {
                player.sendMessage(messages.line("unavailable",null)); return;
            }
            if (!result.succeeded()) player.sendMessage(messages.line("error",vars("reason",
                messages.raw("reason-" + result.code.name().toLowerCase(java.util.Locale.ROOT)
                    .replace('_','-'),null))));
        });
    }

    private void startSession(ProxiedPlayer player) {
        SocialStore db = store;
        if (db == null || player == null || getProxy().getPlayer(player.getUniqueId()) != player)
            return;
        if (sessions.containsKey(player.getUniqueId())) return;
        UUID session = UUID.randomUUID();
        try {
            db.sessionStarted(player.getUniqueId(), player.getName(), config.proxyId,
                session, server(player));
            sessions.put(player.getUniqueId(), session);
            PartySnapshot current = db.getParty(player.getUniqueId());
            if (current == null) chatState.forget(player.getUniqueId());
            else chatState.apply(current);
        } catch (SQLException error) {
            getLogger().warning("Sessão social indisponível; tentativa automática.");
        }
    }

    private static String server(ProxiedPlayer player) {
        return player.getServer() == null ? "" : player.getServer().getInfo().getName();
    }

    @Override public void onDisable() {
        stopping = true;
        if (maintenance != null) maintenance.cancel();
        getProxy().getPluginManager().unregisterCommands(this);
        getProxy().getPluginManager().unregisterListeners(this);
        if (io != null) {
            io.shutdown();
            try { if (!io.awaitTermination(5, TimeUnit.SECONDS)) io.shutdownNow(); }
            catch (InterruptedException error) { Thread.currentThread().interrupt(); io.shutdownNow(); }
        }
        if (pool != null) pool.close();
        sessions.clear(); chatMode.clear(); listeners.clear(); store = null;
    }

    public PartyService partyService() { return service; }
    SocialStore database() { return store; }
    SocialConfig settings() { return config; }
    SocialMessages messages() { return messages; }
    ProxyServer proxy() { return getProxy(); }
    Set<UUID> chatMode() { return chatMode; }
    boolean authenticated(ProxiedPlayer player) {
        return !loginPresent || (loginGate != null && loginGate.authenticated(player));
    }
    boolean permitted(ProxiedPlayer player, String action) {
        return PartyPermissions.allowed(config.defaultActions, player::hasPermission, action);
    }

    boolean runIo(Runnable task) {
        try { if (stopping || io == null) return false; io.execute(task); return true; }
        catch (RejectedExecutionException error) { return false; }
    }

    <T> CompletableFuture<T> async(SqlCall<T> call) {
        CompletableFuture<T> future = new CompletableFuture<T>();
        if (!runIo(() -> {
            try { future.complete(call.run()); }
            catch (Exception error) { future.completeExceptionally(error); }
        })) future.completeExceptionally(new IllegalStateException("Fila SQL ocupada"));
        return future;
    }
    interface SqlCall<T> { T run() throws Exception; }

    private interface StoreOp<T> { PartyResult<T> run(SocialStore db) throws SQLException; }
    private <T> CompletableFuture<PartyResult<T>> change(UUID actor, StoreOp<T> operation,
                                                           PartyEvent.Type event) {
        return change(actor, null, operation, event);
    }
    private <T> CompletableFuture<PartyResult<T>> change(UUID actor, UUID subject,
            StoreOp<T> operation, PartyEvent.Type event) {
        return async(() -> {
            SocialStore db = store;
            UUID session = actor == null ? null : sessions.get(actor);
            ProxiedPlayer player = actor == null ? null : getProxy().getPlayer(actor);
            if (db == null || session == null || player == null || !authenticated(player)
                    || !db.sessionMatches(actor, session))
                return PartyResult.fail(PartyResult.Code.UNAVAILABLE);
            String permission;
            if (event == null) permission = "use";
            else switch (event) {
                case CREATE: permission = "create"; break;
                case INVITE: permission = "invite"; break;
                case KICK: permission = "kick"; break;
                case PROMOTE: permission = "promote"; break;
                case LEADER_CHANGE: permission = "transfer"; break;
                case DISBAND: permission = "disband"; break;
                case SETTINGS_CHANGE: permission = "settings"; break;
                case CHAT: permission = "chat"; break;
                default: permission = "use";
            }
            if (!permitted(player, "use") || !permitted(player, permission))
                return PartyResult.fail(PartyResult.Code.NO_PERMISSION);
            PartyResult<T> result = operation.run(db);
            if (result.succeeded() && result.value instanceof PartySnapshot)
                chatState.apply((PartySnapshot) result.value);
            if (result.succeeded() && event != null) {
                PartySnapshot party = result.value instanceof PartySnapshot
                    ? (PartySnapshot) result.value : db.getParty(actor);
                PartyEvent.Type effectiveEvent = event == PartyEvent.Type.LEAVE && party != null
                    && party.state == PartyState.DISBANDED ? PartyEvent.Type.DISBAND : event;
                PartyEvent published = new PartyEvent(effectiveEvent,
                    party == null ? null : party.id, actor, subject,
                    party == null ? 0 : party.revision, System.currentTimeMillis());
                for (PartyEventListener listener : listeners) try { listener.onPartyEvent(published); }
                catch (RuntimeException error) { getLogger().warning("Listener de Party falhou."); }
            }
            return result;
        });
    }

    private final class Service implements PartyService {
        @Override public CompletableFuture<PartySnapshot> getParty(UUID player) {
            return async(() -> { if (store == null) throw new SQLException("Unavailable");
                PartySnapshot party = store.getParty(player);
                if (party == null) chatState.forget(player);
                else chatState.apply(party);
                return party; });
        }
        @Override public CompletableFuture<PartySnapshot> getPartyById(UUID id) {
            return async(() -> { if (store == null) throw new SQLException("Unavailable");
                PartySnapshot party = store.getPartyById(id);
                chatState.apply(party);
                return party; });
        }
        @Override public CompletableFuture<PartyResult<PartySnapshot>> createParty(UUID a,int n) {
            ProxiedPlayer player = getProxy().getPlayer(a);
            if (player == null || n > PartyCommand.limit(player,StarfinSocialBungee.this))
                return CompletableFuture.completedFuture(PartyResult.fail(PartyResult.Code.NO_PERMISSION));
            return change(a, db -> db.create(a,n), PartyEvent.Type.CREATE); }
        @Override public CompletableFuture<PartyResult<InviteSnapshot>> invitePlayer(UUID a,UUID t) {
            return change(a, t, db -> db.invite(a,t), PartyEvent.Type.INVITE); }
        @Override public CompletableFuture<PartyResult<PartySnapshot>> acceptInvite(UUID a,UUID i) {
            return change(a, i, db -> db.accept(a,i), PartyEvent.Type.JOIN); }
        @Override public CompletableFuture<PartyResult<InviteSnapshot>> denyInvite(UUID a,UUID i) {
            return change(a, db -> db.deny(a,i), null); }
        @Override public CompletableFuture<PartyResult<PartySnapshot>> joinOpen(UUID a,UUID l) {
            return change(a, l, db -> db.joinOpen(a,l), PartyEvent.Type.JOIN); }
        @Override public CompletableFuture<PartyResult<PartySnapshot>> leaveParty(UUID a) {
            return change(a, db -> db.leave(a), PartyEvent.Type.LEAVE); }
        @Override public CompletableFuture<PartyResult<PartySnapshot>> kickMember(UUID a,UUID t) {
            return change(a, t, db -> db.kick(a,t), PartyEvent.Type.KICK); }
        @Override public CompletableFuture<PartyResult<PartySnapshot>> promoteMember(UUID a,UUID t) {
            return change(a, t, db -> db.promote(a,t), PartyEvent.Type.PROMOTE); }
        @Override public CompletableFuture<PartyResult<PartySnapshot>> transferLeadership(UUID a,UUID t) {
            return change(a, t, db -> db.transfer(a,t), PartyEvent.Type.LEADER_CHANGE); }
        @Override public CompletableFuture<PartyResult<PartySnapshot>> disbandParty(UUID a) {
            return change(a, db -> db.disband(a), PartyEvent.Type.DISBAND); }
        @Override public CompletableFuture<PartyResult<PartySnapshot>> setPrivacy(UUID a,PartyPrivacy p) {
            return change(a, db -> db.setPrivacy(a,p), PartyEvent.Type.SETTINGS_CHANGE); }
        @Override public CompletableFuture<PartyResult<PartySnapshot>> setChatEnabled(UUID a,boolean b) {
            return change(a, db -> db.setChatEnabled(a,b), PartyEvent.Type.SETTINGS_CHANGE); }
        @Override public CompletableFuture<PartyResult<PartySnapshot>> setMaxMembers(UUID a,int n) {
            ProxiedPlayer player = getProxy().getPlayer(a);
            if (player == null || n > PartyCommand.limit(player,StarfinSocialBungee.this))
                return CompletableFuture.completedFuture(PartyResult.fail(PartyResult.Code.NO_PERMISSION));
            return change(a, db -> db.setMaxMembers(a,n), PartyEvent.Type.SETTINGS_CHANGE); }
        @Override public CompletableFuture<PartyResult<Void>> sendChat(UUID a,String m) {
            ProxiedPlayer sender = getProxy().getPlayer(a);
            if (sender == null || sessions.get(a) == null || !authenticated(sender))
                return CompletableFuture.completedFuture(PartyResult.fail(PartyResult.Code.UNAVAILABLE));
            if (!permitted(sender, "use") || !permitted(sender, "chat"))
                return CompletableFuture.completedFuture(PartyResult.fail(PartyResult.Code.NO_PERMISSION));
            if (!config.rules.enabled || !config.rules.chatEnabled)
                return CompletableFuture.completedFuture(PartyResult.fail(PartyResult.Code.DISABLED));
            final String body;
            try { body = PartyRules.chat(m); }
            catch (IllegalArgumentException error) {
                return CompletableFuture.completedFuture(PartyResult.fail(PartyResult.Code.INVALID));
            }
            PartySnapshot party = chatState.forPlayer(a);
            if (party == null)
                return CompletableFuture.completedFuture(PartyResult.fail(PartyResult.Code.NOT_IN_PARTY));
            if (!party.chatEnabled)
                return CompletableFuture.completedFuture(PartyResult.fail(PartyResult.Code.DISABLED));
            if (!chatState.allowChat(a, System.nanoTime(), config.rules.chatCooldownMs))
                return CompletableFuture.completedFuture(PartyResult.fail(PartyResult.Code.COOLDOWN));
            TextComponent line = messages.line("chat-line", vars("player", sender.getName(),
                "message", body));
            for (PartyMember member : party.members) {
                if (!chatState.memberOf(member.playerId, party.id)) continue;
                ProxiedPlayer recipient = getProxy().getPlayer(member.playerId);
                if (recipient != null && sessions.get(member.playerId) != null
                        && authenticated(recipient)) recipient.sendMessage(line);
            }
            PartyEvent published = new PartyEvent(PartyEvent.Type.CHAT, party.id, a, null,
                party.revision, System.currentTimeMillis());
            for (PartyEventListener listener : listeners) try { listener.onPartyEvent(published); }
            catch (RuntimeException error) { getLogger().warning("Listener de Party falhou."); }
            if (!runIo(() -> {
                SocialStore db = store;
                if (db != null) try {
                    db.recordDeliveredChat(party.id, a, body, config.proxyId);
                } catch (SQLException error) {
                    warnDatabase("Falha ao registrar/replicar mensagem da Party.");
                }
            })) warnDatabase("Fila SQL ocupada; chat local entregue sem registro.");
            return CompletableFuture.completedFuture(PartyResult.ok(null));
        }
        @Override public CompletableFuture<PartyResult<PartyTransferPlan>> planTransfer(UUID a,String d,int c) {
            return change(a, db -> db.planTransfer(a,d,c), null); }
        @Override public AutoCloseable subscribe(PartyEventListener listener) {
            listeners.add(listener); return () -> listeners.remove(listener); }
    }

    static Map<String,String> vars(String... pairs) {
        Map<String,String> result = new java.util.HashMap<String,String>();
        for (int i=0;i<pairs.length;i+=2) result.put(pairs[i], pairs[i+1]);
        return result;
    }

    private void registerSafe(String label, Command command) {
        try {
            Field field = getProxy().getPluginManager().getClass().getDeclaredField("commandMap");
            field.setAccessible(true);
            Object value = field.get(getProxy().getPluginManager());
            if (!(value instanceof Map) || ((Map<?,?>) value).containsKey(label.toLowerCase())) {
                getLogger().warning("Comando /" + label + " ocupado; registro ignorado."); return;
            }
            getProxy().getPluginManager().registerCommand(this, command);
        } catch (ReflectiveOperationException | SecurityException error) {
            if ("party".equals(label)) getProxy().getPluginManager().registerCommand(this, command);
            else getLogger().warning("Não foi possível verificar conflito de /" + label
                + "; alias ignorado.");
        }
    }
}
