package com.starfinplugins.social.common;

import com.starfinplugins.social.api.FriendEntry;
import com.starfinplugins.social.api.FriendRequest;
import com.starfinplugins.social.api.FriendResult;
import com.starfinplugins.social.api.FriendSettings;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.function.LongSupplier;
import javax.sql.DataSource;

/** Blocking MySQL authority. Invoke only from the bounded social I/O executor. */
public final class FriendStore {
    private final DataSource source;
    private final LongSupplier clock;
    private final long expirationMs, cooldownMs, presenceTimeoutMs;
    private final int maxPending;
    private final boolean offlineRequests;
    private final FriendSettings defaults;

    public FriendStore(DataSource source, long expirationMs, long cooldownMs, int maxPending,
                       boolean offlineRequests, long presenceTimeoutMs, FriendSettings defaults) {
        this(source, System::currentTimeMillis, expirationMs, cooldownMs, maxPending,
            offlineRequests, presenceTimeoutMs, defaults);
    }
    public FriendStore(DataSource source, LongSupplier clock, long expirationMs, long cooldownMs,
                       int maxPending, boolean offlineRequests, long presenceTimeoutMs,
                       FriendSettings defaults) {
        this.source = source; this.clock = clock; this.expirationMs = expirationMs;
        this.cooldownMs = cooldownMs; this.maxPending = maxPending;
        this.offlineRequests = offlineRequests; this.presenceTimeoutMs = presenceTimeoutMs;
        this.defaults = defaults;
    }

    public void migrate() throws SQLException {
        try (Connection c = source.getConnection(); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE IF NOT EXISTS ss_friend_mutex (id TINYINT PRIMARY KEY) ENGINE=InnoDB");
            s.execute("INSERT IGNORE INTO ss_friend_mutex(id) VALUES(1)");
            s.execute("CREATE TABLE IF NOT EXISTS ss_friendships (a CHAR(36) NOT NULL,b CHAR(36) NOT NULL,"
                + "created_at BIGINT NOT NULL,PRIMARY KEY(a,b),INDEX idx_ss_friends_b(b,a))"
                + " ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
            s.execute("CREATE TABLE IF NOT EXISTS ss_friend_requests (id CHAR(36) PRIMARY KEY,"
                + "a CHAR(36) NOT NULL,b CHAR(36) NOT NULL,sender_uuid CHAR(36) NOT NULL,"
                + "target_uuid CHAR(36) NOT NULL,sender_limit INT NOT NULL,"
                + "state VARCHAR(12) NOT NULL,pending_key TINYINT NULL,"
                + "created_at BIGINT NOT NULL,expires_at BIGINT NOT NULL,updated_at BIGINT NOT NULL,"
                + "UNIQUE KEY uq_ss_friend_pending(a,b,pending_key),"
                + "INDEX idx_ss_friend_received(target_uuid,state,expires_at),"
                + "INDEX idx_ss_friend_sent(sender_uuid,state,expires_at),"
                + "INDEX idx_ss_friend_expiry(state,expires_at))"
                + " ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
            s.execute("CREATE TABLE IF NOT EXISTS ss_friend_settings (player_uuid CHAR(36) PRIMARY KEY,"
                + "allow_requests TINYINT(1) NOT NULL,notify_online TINYINT(1) NOT NULL,"
                + "notify_offline TINYINT(1) NOT NULL,show_online TINYINT(1) NOT NULL,"
                + "show_server TINYINT(1) NOT NULL,allow_party_invites TINYINT(1) NOT NULL)"
                + " ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
            s.execute("CREATE TABLE IF NOT EXISTS ss_friend_presence_announcements ("
                + "player_uuid CHAR(36) PRIMARY KEY,last_online_at BIGINT NOT NULL DEFAULT 0,"
                + "last_offline_at BIGINT NOT NULL DEFAULT 0) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
            s.execute("INSERT IGNORE INTO ss_schema_migrations(version,applied_at) VALUES(3,"
                + clock.getAsLong() + ")");
        }
    }

    private interface Action<T> { T run(Connection c) throws SQLException; }
    private <T> T write(Action<T> action) throws SQLException {
        try (Connection c = source.getConnection()) {
            c.setAutoCommit(false);
            try (PreparedStatement lock = q(c,"SELECT id FROM ss_friend_mutex WHERE id=1 FOR UPDATE");
                 ResultSet row = lock.executeQuery()) {
                if (!row.next()) throw new SQLException("Friend mutex missing");
                T result = action.run(c);
                c.commit();
                return result;
            } catch (SQLException | RuntimeException e) { c.rollback(); throw e; }
            finally { c.setAutoCommit(true); }
        }
    }
    private static PreparedStatement q(Connection c, String sql, Object... args) throws SQLException {
        PreparedStatement p = c.prepareStatement(sql);
        p.setQueryTimeout(5);
        for (int i=0; i<args.length; i++) p.setObject(i+1,
            args[i] instanceof UUID ? args[i].toString() : args[i]);
        return p;
    }
    private long now() { return clock.getAsLong(); }
    private static String[] pair(UUID x, UUID y) {
        String a=x.toString(), b=y.toString();
        return a.compareTo(b)<0 ? new String[]{a,b} : new String[]{b,a};
    }
    private static FriendResult result(FriendResult.Code code) { return FriendResult.of(code); }
    private boolean known(Connection c, UUID id) throws SQLException {
        try (PreparedStatement p=q(c,"SELECT 1 FROM ss_presence WHERE player_uuid=?",id);
             ResultSet r=p.executeQuery()) { return r.next(); }
    }
    private boolean online(Connection c, UUID id) throws SQLException {
        try (PreparedStatement p=q(c,"SELECT 1 FROM ss_presence WHERE player_uuid=?"
                + " AND connected=1 AND last_seen>=?",id,now()-presenceTimeoutMs);
             ResultSet r=p.executeQuery()) { return r.next(); }
    }
    private boolean friends(Connection c, UUID x, UUID y) throws SQLException {
        String[] ab=pair(x,y);
        try (PreparedStatement p=q(c,"SELECT 1 FROM ss_friendships WHERE a=? AND b=?",ab[0],ab[1]);
             ResultSet r=p.executeQuery()) { return r.next(); }
    }
    public boolean isFriend(UUID x, UUID y) throws SQLException {
        if (x==null || y==null || x.equals(y)) return false;
        try (Connection c=source.getConnection()) { return friends(c,x,y); }
    }
    private int count(Connection c, UUID id) throws SQLException {
        try (PreparedStatement p=q(c,"SELECT COUNT(*) FROM ss_friendships WHERE a=? OR b=?",id,id);
             ResultSet r=p.executeQuery()) { r.next(); return r.getInt(1); }
    }
    public int count(UUID id) throws SQLException {
        try (Connection c=source.getConnection()) { return count(c,id); }
    }
    private FriendRequest pending(Connection c, UUID x, UUID y) throws SQLException {
        String[] ab=pair(x,y);
        try (PreparedStatement p=q(c,"SELECT id,sender_uuid,target_uuid,created_at,expires_at"
                + " FROM ss_friend_requests WHERE a=? AND b=? AND pending_key=1",ab[0],ab[1]);
             ResultSet r=p.executeQuery()) {
            if (!r.next()) return null;
            return new FriendRequest(UUID.fromString(r.getString(1)),UUID.fromString(r.getString(2)),
                UUID.fromString(r.getString(3)),"","",FriendRequest.State.PENDING,
                r.getLong(4),r.getLong(5));
        }
    }
    private void expire(Connection c) throws SQLException {
        try (PreparedStatement p=q(c,"UPDATE ss_friend_requests SET state='EXPIRED',"
                + "pending_key=NULL,updated_at=? WHERE state='PENDING' AND expires_at<=?",now(),now())) {
            p.executeUpdate();
        }
    }
    private void notify(Connection c, UUID recipient, String kind, UUID actor) throws SQLException {
        try (PreparedStatement p=q(c,"INSERT INTO ss_notifications(recipient_uuid,party_id,kind,"
                + "actor_uuid,body,created_at,expires_at) VALUES(?,NULL,?,?, '',?,?)",
                recipient,kind,actor,now(),now()+7*86400000L)) { p.executeUpdate(); }
    }
    public FriendResult add(UUID actor, UUID target, int limit) throws SQLException {
        if (actor==null || target==null || limit<1) return result(FriendResult.Code.INVALID);
        if (actor.equals(target)) return result(FriendResult.Code.SELF);
        return write(c -> {
            expire(c);
            if (!known(c,target)) return result(FriendResult.Code.UNKNOWN);
            if (!offlineRequests && !online(c,target)) return result(FriendResult.Code.UNKNOWN);
            if (!settings(c,target).allowRequests) return result(FriendResult.Code.PRIVACY);
            if (friends(c,actor,target)) return result(FriendResult.Code.ALREADY_FRIENDS);
            if (pending(c,actor,target)!=null) return result(FriendResult.Code.DUPLICATE);
            if (count(c,actor)>=limit) return result(FriendResult.Code.LIMIT);
            try (PreparedStatement p=q(c,"SELECT COUNT(*) FROM ss_friend_requests WHERE sender_uuid=?"
                    + " AND state='PENDING' AND expires_at>?",actor,now());
                 ResultSet r=p.executeQuery()) {
                r.next(); if (r.getInt(1)>=maxPending) return result(FriendResult.Code.PENDING_LIMIT);
            }
            try (PreparedStatement p=q(c,"SELECT last_at FROM ss_cooldowns WHERE player_uuid=?"
                    + " AND kind='FRIEND'",actor); ResultSet r=p.executeQuery()) {
                if (r.next() && now()-r.getLong(1)<cooldownMs)
                    return result(FriendResult.Code.COOLDOWN);
            }
            try (PreparedStatement p=q(c,"INSERT INTO ss_cooldowns(player_uuid,kind,last_at)"
                    + " VALUES(?,'FRIEND',?) ON DUPLICATE KEY UPDATE last_at=VALUES(last_at)",actor,now()))
                { p.executeUpdate(); }
            String[] ab=pair(actor,target);
            try (PreparedStatement p=q(c,"INSERT INTO ss_friend_requests(id,a,b,sender_uuid,"
                    + "target_uuid,sender_limit,state,pending_key,created_at,expires_at,updated_at)"
                    + " VALUES(?,?,?,?,?,?,'PENDING',1,?,?,?)",UUID.randomUUID(),ab[0],ab[1],
                    actor,target,limit,now(),now()+expirationMs,now())) { p.executeUpdate(); }
            notify(c,target,"FRIEND_REQUEST",actor);
            return result(FriendResult.Code.OK);
        });
    }
    public FriendResult accept(UUID actor, UUID sender, int actorLimit) throws SQLException {
        if (actor==null || sender==null || actor.equals(sender)) return result(FriendResult.Code.INVALID);
        return write(c -> {
            expire(c);
            FriendRequest request=pending(c,actor,sender);
            if (request==null || !request.sender.equals(sender) || !request.target.equals(actor))
                return result(FriendResult.Code.NO_REQUEST);
            if (friends(c,actor,sender)) return result(FriendResult.Code.ALREADY_FRIENDS);
            if (count(c,actor)>=actorLimit || count(c,sender)>=senderLimit(c,request.id))
                return result(FriendResult.Code.LIMIT);
            String[] ab=pair(actor,sender);
            try (PreparedStatement p=q(c,"INSERT INTO ss_friendships(a,b,created_at) VALUES(?,?,?)",
                    ab[0],ab[1],now())) { p.executeUpdate(); }
            state(c,request.id,"ACCEPTED");
            notify(c,sender,"FRIEND_ACCEPT",actor);
            return result(FriendResult.Code.OK);
        });
    }
    private int senderLimit(Connection c, UUID request) throws SQLException {
        try (PreparedStatement p=q(c,"SELECT sender_limit FROM ss_friend_requests WHERE id=?",request);
             ResultSet r=p.executeQuery()) { r.next(); return r.getInt(1); }
    }
    private void state(Connection c, UUID request, String state) throws SQLException {
        try (PreparedStatement p=q(c,"UPDATE ss_friend_requests SET state=?,pending_key=NULL,"
                + "updated_at=? WHERE id=?",state,now(),request)) { p.executeUpdate(); }
    }
    public FriendResult resolve(UUID actor, UUID other, boolean cancel) throws SQLException {
        if (actor==null || other==null || actor.equals(other)) return result(FriendResult.Code.INVALID);
        return write(c -> {
            expire(c);
            FriendRequest r=pending(c,actor,other);
            if (r==null || (cancel ? !r.sender.equals(actor) : !r.target.equals(actor)))
                return result(FriendResult.Code.NO_REQUEST);
            state(c,r.id,cancel ? "CANCELLED" : "DENIED");
            if (!cancel) notify(c,other,"FRIEND_DENY",actor);
            return result(FriendResult.Code.OK);
        });
    }
    public FriendResult remove(UUID actor, UUID other) throws SQLException {
        if (actor==null || other==null || actor.equals(other)) return result(FriendResult.Code.INVALID);
        return write(c -> {
            String[] ab=pair(actor,other);
            try (PreparedStatement p=q(c,"DELETE FROM ss_friendships WHERE a=? AND b=?",ab[0],ab[1])) {
                if (p.executeUpdate()==0) return result(FriendResult.Code.UNKNOWN);
            }
            notify(c,other,"FRIEND_REMOVE",actor);
            return result(FriendResult.Code.OK);
        });
    }
    private FriendSettings settings(Connection c, UUID player) throws SQLException {
        try (PreparedStatement p=q(c,"SELECT allow_requests,notify_online,notify_offline,"
                + "show_online,show_server,allow_party_invites FROM ss_friend_settings"
                + " WHERE player_uuid=?",player); ResultSet r=p.executeQuery()) {
            return r.next() ? new FriendSettings(r.getBoolean(1),r.getBoolean(2),r.getBoolean(3),
                r.getBoolean(4),r.getBoolean(5),r.getBoolean(6)) : defaults;
        }
    }
    public FriendSettings settings(UUID player) throws SQLException {
        try (Connection c=source.getConnection()) { return settings(c,player); }
    }
    public FriendResult setSetting(UUID player, String key, boolean value) throws SQLException {
        if (player==null) return result(FriendResult.Code.INVALID);
        return write(c -> {
            FriendSettings s;
            try { s=settings(c,player).with(key,value); }
            catch (IllegalArgumentException error) { return result(FriendResult.Code.INVALID); }
            try (PreparedStatement p=q(c,"INSERT INTO ss_friend_settings VALUES(?,?,?,?,?,?,?)"
                + " ON DUPLICATE KEY UPDATE allow_requests=VALUES(allow_requests),"
                + "notify_online=VALUES(notify_online),notify_offline=VALUES(notify_offline),"
                + "show_online=VALUES(show_online),show_server=VALUES(show_server),"
                + "allow_party_invites=VALUES(allow_party_invites)",player,s.allowRequests,
                s.notifyOnline,s.notifyOffline,s.showOnline,s.showServer,s.allowPartyInvites))
                { p.executeUpdate(); }
            return result(FriendResult.Code.OK);
        });
    }
    public List<FriendEntry> friends(UUID viewer, int page, int size) throws SQLException {
        if (viewer==null || page<1 || size<1 || size>45 || (long)(page-1)*size>Integer.MAX_VALUE)
            return Collections.emptyList();
        List<FriendEntry> list=new ArrayList<FriendEntry>();
        try (Connection c=source.getConnection(); PreparedStatement p=q(c,
            "SELECT CASE WHEN f.a=? THEN f.b ELSE f.a END AS friend_uuid,"
            + "p.last_name,p.server_name,p.connected,p.last_seen,f.created_at,"
            + "s.show_online,s.show_server FROM ss_friendships f"
            + " LEFT JOIN ss_presence p ON p.player_uuid=CASE WHEN f.a=? THEN f.b ELSE f.a END"
            + " LEFT JOIN ss_friend_settings s ON s.player_uuid=CASE WHEN f.a=? THEN f.b ELSE f.a END"
            + " WHERE f.a=? OR f.b=? ORDER BY p.last_name,friend_uuid LIMIT ? OFFSET ?",
            viewer,viewer,viewer,viewer,viewer,size,(page-1)*size);
             ResultSet r=p.executeQuery()) {
            while (r.next()) {
                UUID id=UUID.fromString(r.getString(1));
                boolean showOnline=r.getObject(7)==null ? defaults.showOnline : r.getBoolean(7);
                boolean showServer=r.getObject(8)==null ? defaults.showServer : r.getBoolean(8);
                boolean online=showOnline && r.getBoolean(4) && r.getLong(5)>=now()-presenceTimeoutMs;
                list.add(new FriendEntry(id,r.getString(2)==null?id.toString():r.getString(2),
                    online && showServer ? r.getString(3) : "", online,!showOnline,
                    showOnline ? r.getLong(5) : 0,r.getLong(6)));
            }
        }
        return Collections.unmodifiableList(list);
    }
    public List<FriendRequest> requests(UUID actor, boolean received) throws SQLException {
        List<FriendRequest> list=new ArrayList<FriendRequest>();
        try (Connection c=source.getConnection(); PreparedStatement p=q(c,
            "SELECT r.id,r.sender_uuid,r.target_uuid,s.last_name,t.last_name,r.created_at,r.expires_at"
            + " FROM ss_friend_requests r LEFT JOIN ss_presence s ON s.player_uuid=r.sender_uuid"
            + " LEFT JOIN ss_presence t ON t.player_uuid=r.target_uuid"
            + " WHERE " +(received ? "r.target_uuid" : "r.sender_uuid")+"=? AND r.state='PENDING'"
            + " AND r.expires_at>? ORDER BY r.created_at DESC LIMIT 100",actor,now());
             ResultSet r=p.executeQuery()) {
            while (r.next()) list.add(new FriendRequest(UUID.fromString(r.getString(1)),
                UUID.fromString(r.getString(2)),UUID.fromString(r.getString(3)),
                r.getString(4),r.getString(5),FriendRequest.State.PENDING,r.getLong(6),r.getLong(7)));
        }
        return Collections.unmodifiableList(list);
    }
    public boolean canSend(UUID actor, UUID target, int actorLimit) throws SQLException {
        if (actor==null || target==null || actor.equals(target)) return false;
        try (Connection c=source.getConnection()) {
            if (!(known(c,target) && (offlineRequests || online(c,target))
                && settings(c,target).allowRequests && !friends(c,actor,target)
                && pending(c,actor,target)==null) || count(c,actor)>=actorLimit) return false;
            try (PreparedStatement p=q(c,"SELECT COUNT(*) FROM ss_friend_requests WHERE sender_uuid=?"
                    + " AND state='PENDING' AND expires_at>?",actor,now());
                 ResultSet r=p.executeQuery()) {
                r.next(); if (r.getInt(1)>=maxPending) return false;
            }
            try (PreparedStatement p=q(c,"SELECT last_at FROM ss_cooldowns WHERE player_uuid=?"
                    + " AND kind='FRIEND'",actor); ResultSet r=p.executeQuery()) {
                return !r.next() || now()-r.getLong(1)>=cooldownMs;
            }
        }
    }
    public boolean requestDeliverable(UUID sender, UUID target) throws SQLException {
        if (sender==null || target==null || sender.equals(target)) return false;
        try (Connection c=source.getConnection()) {
            FriendRequest request=pending(c,sender,target);
            return request!=null && request.sender.equals(sender)
                && request.target.equals(target) && request.expiresAt>now();
        }
    }
    public boolean presenceDeliverable(UUID actor, UUID recipient, boolean joining)
            throws SQLException {
        if (actor==null || recipient==null || actor.equals(recipient)) return false;
        try (Connection c=source.getConnection()) {
            if (!friends(c,actor,recipient) || !settings(c,actor).showOnline) return false;
            FriendSettings recipientSettings=settings(c,recipient);
            if (!(joining ? recipientSettings.notifyOnline : recipientSettings.notifyOffline))
                return false;
            return joining==online(c,actor);
        }
    }
    public boolean canInviteParty(UUID actor, UUID target) throws SQLException {
        if (actor==null || target==null || actor.equals(target)) return false;
        try (Connection c=source.getConnection()) {
            return !friends(c,actor,target) || settings(c,target).allowPartyInvites;
        }
    }
    /** Resolve recipients in one query; no one query per friend. */
    public List<UUID> presenceRecipients(UUID actor, boolean joining) throws SQLException {
        List<UUID> result=new ArrayList<UUID>();
        try (Connection c=source.getConnection()) {
            FriendSettings actorSettings=settings(c,actor);
            if (!actorSettings.showOnline) return result;
            try (PreparedStatement p=q(c,"SELECT CASE WHEN f.a=? THEN f.b ELSE f.a END AS recipient,"
                + (joining ? "s.notify_online" : "s.notify_offline")
                + " FROM ss_friendships f LEFT JOIN ss_friend_settings s ON s.player_uuid="
                + "CASE WHEN f.a=? THEN f.b ELSE f.a END JOIN ss_presence p ON p.player_uuid="
                + "CASE WHEN f.a=? THEN f.b ELSE f.a END WHERE (f.a=? OR f.b=?)"
                + " AND p.connected=1 AND p.last_seen>=?",
                actor,actor,actor,actor,actor,now()-presenceTimeoutMs); ResultSet r=p.executeQuery()) {
                while (r.next()) if (r.getObject(2)==null
                    ? (joining ? defaults.notifyOnline : defaults.notifyOffline) : r.getBoolean(2))
                    result.add(UUID.fromString(r.getString(1)));
            }
        }
        return result;
    }
    public boolean isOnline(UUID player) throws SQLException {
        try (Connection c=source.getConnection()) { return online(c,player); }
    }
    public boolean notifyPresence(UUID actor, boolean joining) throws SQLException {
        if (actor==null || (joining && !isOnline(actor)) || (!joining && isOnline(actor))) return false;
        List<UUID> recipients=presenceRecipients(actor,joining);
        return write(c -> {
            try (PreparedStatement p=q(c,"INSERT IGNORE INTO ss_friend_presence_announcements"
                    + "(player_uuid) VALUES(?)",actor)) { p.executeUpdate(); }
            long last;
            try (PreparedStatement p=q(c,"SELECT "
                    + (joining?"last_online_at":"last_offline_at")
                    + " FROM ss_friend_presence_announcements WHERE player_uuid=?",actor);
                 ResultSet r=p.executeQuery()) { r.next(); last=r.getLong(1); }
            if (last>0 && now()-last<10000) return false;
            try (PreparedStatement p=q(c,"UPDATE ss_friend_presence_announcements SET "
                    + (joining?"last_online_at":"last_offline_at")+"=? WHERE player_uuid=?",
                    now(),actor)) { p.executeUpdate(); }
            for (UUID recipient:recipients)
                notify(c,recipient,joining?"FRIEND_ONLINE":"FRIEND_OFFLINE",actor);
            return true;
        });
    }
    public List<UUID> expirePresence() throws SQLException {
        List<UUID> expired=write(c -> {
            List<UUID> stale=new ArrayList<UUID>();
            try (PreparedStatement p=q(c,"SELECT player_uuid FROM ss_presence WHERE connected=1"
                    + " AND last_seen<? LIMIT 100",now()-presenceTimeoutMs);
                 ResultSet r=p.executeQuery()) {
                while (r.next()) stale.add(UUID.fromString(r.getString(1)));
            }
            for (UUID id:stale) try (PreparedStatement p=q(c,"UPDATE ss_presence SET connected=0"
                    + " WHERE player_uuid=? AND connected=1 AND last_seen<?",
                    id,now()-presenceTimeoutMs)) { p.executeUpdate(); }
            return stale;
        });
        List<UUID> announced=new ArrayList<UUID>();
        for (UUID id:expired) if (notifyPresence(id,false)) announced.add(id);
        return announced;
    }
    public void expireRequests() throws SQLException {
        try (Connection c=source.getConnection()) { expire(c); }
    }
}
