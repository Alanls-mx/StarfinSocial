package com.starfinplugins.social.bungee;

import com.starfinplugins.social.api.*;
import com.starfinplugins.social.common.SocialStore;
import com.starfinplugins.social.common.FriendStore;
import com.starfinplugins.social.common.PartyChatState;
import com.starfinplugins.social.common.PartyRules;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import com.google.common.io.ByteArrayDataInput;
import com.google.common.io.ByteArrayDataOutput;
import com.google.common.io.ByteStreams;
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
import net.md_5.bungee.api.event.PluginMessageEvent;
import net.md_5.bungee.api.connection.Server;
import net.md_5.bungee.api.plugin.Command;
import net.md_5.bungee.api.plugin.Listener;
import net.md_5.bungee.api.plugin.Plugin;
import net.md_5.bungee.api.scheduler.ScheduledTask;
import net.md_5.bungee.event.EventHandler;

/** Network authority. All SQL work runs on the bounded worker pool. */
public final class StarfinSocialBungee extends Plugin implements Listener {
    private static final String FRIEND_CHANNEL="SSFriends";
    private SocialConfig config;
    private SocialMessages messages;
    private ThreadPoolExecutor io;
    private HikariDataSource pool;
    private volatile SocialStore store;
    private volatile FriendStore friendStore;
    private LoginGate loginGate;
    private boolean loginPresent;
    private ScheduledTask maintenance;
    private final Map<UUID, UUID> sessions = new ConcurrentHashMap<UUID, UUID>();
    private final Set<UUID> pendingFriendOnline = java.util.Collections.newSetFromMap(
        new ConcurrentHashMap<UUID, Boolean>());
    private final Set<UUID> announcedFriendOnline = java.util.Collections.newSetFromMap(
        new ConcurrentHashMap<UUID, Boolean>());
    private final Set<UUID> chatMode = java.util.Collections.newSetFromMap(
        new ConcurrentHashMap<UUID, Boolean>());
    private final CopyOnWriteArrayList<PartyEventListener> listeners =
        new CopyOnWriteArrayList<PartyEventListener>();
    private final CopyOnWriteArrayList<FriendEventListener> friendListeners =
        new CopyOnWriteArrayList<FriendEventListener>();
    private final PartyService service = new Service();
    private final FriendService friends = new Friends();
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
            getProxy().registerChannel(FRIEND_CHANNEL);
            registerSafe("party", new PartyCommand(this, "party"));
            if (config.aliasP) registerSafe("p", new PartyCommand(this, "p"));
            if (config.aliasPc) registerSafe("pc", new PartyChatCommand(this));
            registerSafe("friend", new FriendCommand(this,"friend"));
            registerSafe("friends", new FriendCommand(this,"friends"));
            registerSafe("f", new FriendCommand(this,"f"));
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
            FriendStore nextFriends = new FriendStore(pool, config.friendExpirationMs,
                config.friendCooldownMs, config.maxPendingFriends, config.allowOfflineRequests,
                config.rules.presenceTimeoutMs, config.friendDefaults);
            nextFriends.migrate();
            friendStore = nextFriends;
            store = next;
            getLogger().info("StarfinSocial MySQL pronto (ss_schema_migrations v3).");
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
                if (authenticated(player) && pendingFriendOnline.remove(player.getUniqueId())
                        && friendStore != null) {
                    if (friendStore.notifyPresence(player.getUniqueId(),true))
                        publishFriend(new FriendEvent(FriendEvent.Type.FRIEND_ONLINE,
                            player.getUniqueId(),null,System.currentTimeMillis()));
                    announcedFriendOnline.add(player.getUniqueId());
                }
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
            if (now - lastSweepAt >= 10000) {
                db.sweep();
                if (friendStore != null) {
                    friendStore.expireRequests();
                    for (UUID expired:friendStore.expirePresence())
                        publishFriend(new FriendEvent(FriendEvent.Type.FRIEND_OFFLINE,
                            expired,null,System.currentTimeMillis()));
                }
                lastSweepAt = now;
            }
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
        else if (n.kind.startsWith("FRIEND_")) {
            if ("FRIEND_REQUEST".equals(n.kind)
                    && (actor==null || friendStore==null
                        || !friendStore.requestDeliverable(actor,player.getUniqueId()))) return;
            if (("FRIEND_ONLINE".equals(n.kind) || "FRIEND_OFFLINE".equals(n.kind))
                    && (actor==null || friendStore==null
                        || !friendStore.presenceDeliverable(actor,player.getUniqueId(),
                            "FRIEND_ONLINE".equals(n.kind)))) return;
            String key = n.kind.toLowerCase(java.util.Locale.ROOT).replace('_','-');
            player.sendMessage(messages.line(key, vars("friend", name, "player", name)));
            if ("FRIEND_REQUEST".equals(n.kind)) {
                TextComponent accept = messages.line("friend-click-accept",vars("friend",name));
                accept.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND,"/friend accept " + name));
                player.sendMessage(accept);
            }
        }
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
        pendingFriendOnline.remove(player.getUniqueId());
        boolean announced=announcedFriendOnline.remove(player.getUniqueId());
        chatMode.remove(player.getUniqueId());
        chatState.clearCooldown(player.getUniqueId());
        if (session != null) runIo(() -> {
            try { if (store != null) store.sessionEnded(player.getUniqueId(), session); }
            catch (SQLException ignored) { /* presence timeout handles crash */ }
        });
        if (session != null && announced) getProxy().getScheduler().schedule(this, () -> runIo(() -> {
            try {
                FriendStore db=friendStore;
                if (db!=null && !db.isOnline(player.getUniqueId()))
                    if (db.notifyPresence(player.getUniqueId(), false))
                        publishFriend(new FriendEvent(FriendEvent.Type.FRIEND_OFFLINE,
                            player.getUniqueId(),null,System.currentTimeMillis()));
            } catch (SQLException error) { warnDatabase("Falha ao publicar saída de amigo."); }
        }), 3, TimeUnit.SECONDS);
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
    void openFriendMenu(ProxiedPlayer player) {
        if (player.getServer()==null) return;
        ByteArrayDataOutput data=ByteStreams.newDataOutput();
        data.writeUTF("open"); data.writeUTF(player.getUniqueId().toString());
        player.getServer().sendData(FRIEND_CHANNEL,data.toByteArray());
    }
    @EventHandler public void onFriendMenuMessage(PluginMessageEvent event) {
        if (!FRIEND_CHANNEL.equals(event.getTag()) || !(event.getSender() instanceof Server)
                || !(event.getReceiver() instanceof ProxiedPlayer)) return;
        event.setCancelled(true);
        ProxiedPlayer player=(ProxiedPlayer)event.getReceiver();
        Server sender=(Server)event.getSender();
        if (player.getServer()==null || !sender.getInfo().equals(player.getServer().getInfo())
                || !authenticated(player) || !friendPermitted(player,"use")) return;
        try {
            ByteArrayDataInput input=ByteStreams.newDataInput(event.getData());
            if (!"click".equals(input.readUTF())
                    || !player.getUniqueId().equals(UUID.fromString(input.readUTF()))) return;
            String action=input.readUTF();
            UUID target="toggle".equals(action) ? null : UUID.fromString(input.readUTF());
            CompletableFuture<?> result;
            switch (action) {
                case "accept": result=friends.accept(player.getUniqueId(),target,
                    FriendCommand.limit(player,this)); break;
                case "deny": result=friends.deny(player.getUniqueId(),target); break;
                case "cancel": result=friends.cancel(player.getUniqueId(),target); break;
                case "remove": result=friends.remove(player.getUniqueId(),target); break;
                case "party": result=service.invitePlayer(player.getUniqueId(),target); break;
                case "toggle": result=friends.settings(player.getUniqueId()).thenCompose(s ->
                    friends.setSetting(player.getUniqueId(),"requests",!s.allowRequests)); break;
                default: return;
            }
            result.whenComplete((value,error) -> {
                if (getProxy().getPlayer(player.getUniqueId())!=player) return;
                if (error!=null) player.sendMessage(messages.line("friend-unavailable",null));
                else if (value instanceof FriendResult && !((FriendResult)value).succeeded())
                    player.sendMessage(messages.line("friend-error",vars("reason",
                        messages.raw("friend-reason-"+((FriendResult)value).code.name()
                            .toLowerCase(java.util.Locale.ROOT).replace('_','-'),null))));
                else if (value instanceof PartyResult && !((PartyResult<?>)value).succeeded())
                    player.sendMessage(messages.line("friend-error",vars("reason",
                        messages.raw("reason-"+((PartyResult<?>)value).code.name()
                            .toLowerCase(java.util.Locale.ROOT).replace('_','-'),null))));
                else player.sendMessage(messages.line("friend-menu-updated",null));
                openFriendMenu(player);
            });
        } catch (IllegalArgumentException | IndexOutOfBoundsException error) {
            getLogger().warning("Mensagem inválida do menu de amigos.");
        }
    }

    private void startSession(ProxiedPlayer player) {
        SocialStore db = store;
        if (db == null || player == null || getProxy().getPlayer(player.getUniqueId()) != player)
            return;
        if (!authenticated(player)) return;
        if (sessions.containsKey(player.getUniqueId())) return;
        UUID session = UUID.randomUUID();
        try {
            boolean previouslyOnline = friendStore != null && friendStore.isOnline(player.getUniqueId());
            db.sessionStarted(player.getUniqueId(), player.getName(), config.proxyId,
                session, server(player));
            sessions.put(player.getUniqueId(), session);
            announcedFriendOnline.add(player.getUniqueId());
            PartySnapshot current = db.getParty(player.getUniqueId());
            if (current == null) chatState.forget(player.getUniqueId());
            else chatState.apply(current);
            if (!previouslyOnline && friendStore != null)
                pendingFriendOnline.add(player.getUniqueId());
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
        getProxy().unregisterChannel(FRIEND_CHANNEL);
        if (io != null) {
            io.shutdown();
            try { if (!io.awaitTermination(5, TimeUnit.SECONDS)) io.shutdownNow(); }
            catch (InterruptedException error) { Thread.currentThread().interrupt(); io.shutdownNow(); }
        }
        if (pool != null) pool.close();
        sessions.clear(); pendingFriendOnline.clear(); announcedFriendOnline.clear();
        chatMode.clear(); listeners.clear(); friendListeners.clear();
        store = null; friendStore = null;
    }

    public PartyService partyService() { return service; }
    public FriendService friendService() { return friends; }
    FriendStore friendDatabase() { return friendStore; }
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
    boolean friendPermitted(ProxiedPlayer player, String action) {
        return player != null && (player.hasPermission("starfinsocial.friend.admin")
            || player.hasPermission("starfinsocial.friend." + action)
            || config.defaultFriendActions.contains(action));
    }

    private interface FriendChange { FriendResult run(FriendStore db) throws SQLException; }
    private CompletableFuture<FriendResult> friendChange(UUID actor, UUID target,
                                                         FriendEvent.Type type, String permission,
                                                         FriendChange operation) {
        return async(() -> {
            ProxiedPlayer player=getProxy().getPlayer(actor);
            UUID session=sessions.get(actor);
            FriendStore db=friendStore;
            if (db==null || player==null || session==null || !authenticated(player)
                || !store.sessionMatches(actor,session) || !config.friendsEnabled)
                return FriendResult.of(FriendResult.Code.UNAVAILABLE);
            if (!friendPermitted(player,"use") || !friendPermitted(player,permission))
                return FriendResult.of(FriendResult.Code.NO_PERMISSION);
            FriendResult result=operation.run(db);
            if (result.succeeded() && type!=null)
                publishFriend(new FriendEvent(type,actor,target,System.currentTimeMillis()));
            return result;
        });
    }
    private void publishFriend(FriendEvent event) {
        for (FriendEventListener listener:friendListeners) try { listener.onFriendEvent(event); }
        catch (RuntimeException error) { getLogger().warning("Listener de amigos falhou."); }
    }
    private final class Friends implements FriendService {
        @Override public CompletableFuture<FriendResult> add(UUID a,UUID t,int limit) {
            return friendChange(a,t,FriendEvent.Type.REQUEST_SENT,"add",db -> config.friendRequestsEnabled
                ? db.add(a,t,limit) : FriendResult.of(FriendResult.Code.UNAVAILABLE));
        }
        @Override public CompletableFuture<FriendResult> accept(UUID a,UUID t,int limit) {
            return friendChange(a,t,FriendEvent.Type.REQUEST_ACCEPTED,"accept",db -> db.accept(a,t,limit));
        }
        @Override public CompletableFuture<FriendResult> deny(UUID a,UUID t) {
            return friendChange(a,t,FriendEvent.Type.REQUEST_DENIED,"deny",db -> db.resolve(a,t,false));
        }
        @Override public CompletableFuture<FriendResult> cancel(UUID a,UUID t) {
            return friendChange(a,t,FriendEvent.Type.REQUEST_CANCELLED,"add",db -> db.resolve(a,t,true));
        }
        @Override public CompletableFuture<FriendResult> remove(UUID a,UUID t) {
            return friendChange(a,t,FriendEvent.Type.FRIEND_REMOVED,"remove",db -> db.remove(a,t));
        }
        @Override public CompletableFuture<Boolean> isFriend(UUID a,UUID t) {
            return async(() -> { checkFriendReader(a);
                return friendStore.isFriend(a,t); });
        }
        @Override public CompletableFuture<java.util.List<FriendEntry>> getFriends(UUID a,int page,int size) {
            return async(() -> { checkFriendReader(a);
                return friendStore.friends(a,page,size); });
        }
        @Override public CompletableFuture<java.util.List<FriendEntry>> getOnlineFriends(UUID a) {
            return async(() -> { checkFriendReader(a);
                java.util.List<FriendEntry> result=new java.util.ArrayList<FriendEntry>();
                for (int page=1;page<=20;page++) {
                    java.util.List<FriendEntry> entries=friendStore.friends(a,page,45);
                    for (FriendEntry e:entries) if (e.online) result.add(e);
                    if (entries.size()<45) break;
                }
                return java.util.Collections.unmodifiableList(result); });
        }
        @Override public CompletableFuture<Integer> countFriends(UUID a) {
            return async(() -> { checkFriendReader(a); return friendStore.count(a); });
        }
        @Override public CompletableFuture<java.util.List<FriendRequest>> requests(UUID a,boolean received) {
            return async(() -> { checkFriendReader(a);
                return friendStore.requests(a,received); });
        }
        @Override public CompletableFuture<FriendSettings> settings(UUID a) {
            return async(() -> { checkFriendReader(a);
                return friendStore.settings(a); });
        }
        @Override public CompletableFuture<FriendResult> setSetting(UUID a,String key,boolean value) {
            return friendChange(a,null,null,"settings",db -> db.setSetting(a,key,value));
        }
        @Override public CompletableFuture<Boolean> canSendFriendRequest(UUID a,UUID t) {
            return async(() -> { checkFriendReader(a);
                return friendStore.canSend(a,t,FriendCommand.limit(getProxy().getPlayer(a),
                    StarfinSocialBungee.this)); });
        }
        @Override public CompletableFuture<Boolean> canInviteToParty(UUID a,UUID t) {
            return async(() -> { checkFriendReader(a);
                return friendStore.canInviteParty(a,t); });
        }
        @Override public AutoCloseable subscribe(FriendEventListener listener) {
            if (listener==null) throw new IllegalArgumentException("listener");
            friendListeners.add(listener); return () -> friendListeners.remove(listener);
        }
    }
    private void checkFriendReader(UUID actor) throws SQLException {
        ProxiedPlayer player=actor==null ? null : getProxy().getPlayer(actor);
        UUID session=actor==null ? null : sessions.get(actor);
        if (friendStore==null || store==null || player==null || session==null
                || !authenticated(player) || !friendPermitted(player,"use")
                || !store.sessionMatches(actor,session))
            throw new SQLException("Friend reader unavailable");
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
            return change(a, t, db -> friendStore != null && !friendStore.canInviteParty(a,t)
                ? PartyResult.fail(PartyResult.Code.PRIVACY) : db.invite(a,t), PartyEvent.Type.INVITE); }
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
