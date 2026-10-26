package com.starfinplugins.social.common;

import com.starfinplugins.social.api.InviteSnapshot;
import com.starfinplugins.social.api.InviteState;
import com.starfinplugins.social.api.PartyMember;
import com.starfinplugins.social.api.PartyPrivacy;
import com.starfinplugins.social.api.PartyResult;
import com.starfinplugins.social.api.PartyRole;
import com.starfinplugins.social.api.PartySnapshot;
import com.starfinplugins.social.api.PartyState;
import com.starfinplugins.social.api.PartyTransferPlan;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.LongSupplier;
import javax.sql.DataSource;

/** Blocking MySQL authority. Call exclusively from bounded I/O executors. */
public final class SocialStore {
    private final DataSource dataSource;
    private final PartyRules rules;
    private final LongSupplier clock;

    public SocialStore(DataSource dataSource, PartyRules rules) {
        this(dataSource, rules, System::currentTimeMillis);
    }
    public SocialStore(DataSource dataSource, PartyRules rules, LongSupplier clock) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.rules = Objects.requireNonNull(rules, "rules");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public void migrate() throws SQLException {
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE IF NOT EXISTS ss_schema_migrations (version INT PRIMARY KEY,"
                + " applied_at BIGINT NOT NULL) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
            s.execute("CREATE TABLE IF NOT EXISTS ss_parties ("
                + "party_id CHAR(36) PRIMARY KEY,leader_uuid CHAR(36) NOT NULL,"
                + "state VARCHAR(12) NOT NULL,privacy VARCHAR(16) NOT NULL,"
                + "max_members INT NOT NULL,chat_enabled TINYINT(1) NOT NULL,"
                + "created_at BIGINT NOT NULL,updated_at BIGINT NOT NULL,revision BIGINT NOT NULL,"
                + "leader_missing_since BIGINT NULL,INDEX idx_ss_leader (leader_uuid,state),"
                + "INDEX idx_ss_active (state,updated_at)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
            s.execute("CREATE TABLE IF NOT EXISTS ss_members ("
                + "player_uuid CHAR(36) PRIMARY KEY,party_id CHAR(36) NOT NULL,"
                + "role VARCHAR(8) NOT NULL,joined_at BIGINT NOT NULL,"
                + "INDEX idx_ss_party_members (party_id,joined_at),"
                + "CONSTRAINT fk_ss_member_party FOREIGN KEY (party_id) REFERENCES ss_parties(party_id))"
                + " ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
            s.execute("CREATE TABLE IF NOT EXISTS ss_invites ("
                + "invite_id CHAR(36) PRIMARY KEY,party_id CHAR(36) NOT NULL,"
                + "inviter_uuid CHAR(36) NOT NULL,target_uuid CHAR(36) NOT NULL,"
                + "state VARCHAR(12) NOT NULL,pending_key TINYINT NULL,"
                + "created_at BIGINT NOT NULL,expires_at BIGINT NOT NULL,updated_at BIGINT NOT NULL,"
                + "UNIQUE KEY uq_ss_pending (party_id,target_uuid,pending_key),"
                + "INDEX idx_ss_target (target_uuid,state,expires_at),"
                + "CONSTRAINT fk_ss_invite_party FOREIGN KEY (party_id) REFERENCES ss_parties(party_id))"
                + " ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
            s.execute("CREATE TABLE IF NOT EXISTS ss_presence ("
                + "player_uuid CHAR(36) PRIMARY KEY,last_name VARCHAR(16) NOT NULL,"
                + "proxy_id VARCHAR(32) NOT NULL,session_id CHAR(36) NOT NULL,"
                + "server_name VARCHAR(64) NOT NULL,connected TINYINT(1) NOT NULL,"
                + "last_seen BIGINT NOT NULL,INDEX idx_ss_presence_name (last_name),"
                + "INDEX idx_ss_presence_live (connected,last_seen))"
                + " ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
            s.execute("CREATE TABLE IF NOT EXISTS ss_cooldowns ("
                + "player_uuid CHAR(36) NOT NULL,kind VARCHAR(12) NOT NULL,last_at BIGINT NOT NULL,"
                + "PRIMARY KEY(player_uuid,kind)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
            s.execute("CREATE TABLE IF NOT EXISTS ss_notifications ("
                + "id BIGINT AUTO_INCREMENT PRIMARY KEY,recipient_uuid CHAR(36) NOT NULL,"
                + "party_id CHAR(36) NULL,kind VARCHAR(24) NOT NULL,actor_uuid CHAR(36) NULL,"
                + "body VARCHAR(256) NOT NULL,created_at BIGINT NOT NULL,expires_at BIGINT NOT NULL,"
                + "claimed_session CHAR(36) NULL,claim_until BIGINT NOT NULL DEFAULT 0,"
                + "delivered_at BIGINT NULL,INDEX idx_ss_delivery (recipient_uuid,delivered_at,id),"
                + "INDEX idx_ss_notification_expiry (expires_at))"
                + " ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
            s.execute("INSERT IGNORE INTO ss_schema_migrations(version,applied_at) VALUES(1,"
                + System.currentTimeMillis() + ")");
        }
    }

    public void sessionStarted(UUID player, String name, String proxy, UUID session, String server)
            throws SQLException {
        if (player == null || session == null || name == null
                || !name.matches("[A-Za-z0-9_]{1,16}") || proxy == null
                || !proxy.matches("[A-Za-z0-9_-]{1,32}"))
            throw new IllegalArgumentException("Sessao invalida");
        try (Connection c = dataSource.getConnection(); PreparedStatement q = prepare(c,
                "INSERT INTO ss_presence(player_uuid,last_name,proxy_id,session_id,server_name,"
                + "connected,last_seen) VALUES(?,?,?,?,?,1,?) ON DUPLICATE KEY UPDATE "
                + "last_name=VALUES(last_name),proxy_id=VALUES(proxy_id),"
                + "session_id=VALUES(session_id),server_name=VALUES(server_name),"
                + "connected=1,last_seen=VALUES(last_seen)",
                player, name, proxy, session, server == null ? "" : server, clock.getAsLong())) {
            q.executeUpdate();
        }
    }

    public boolean heartbeat(UUID player, UUID session, String server) throws SQLException {
        try (Connection c = dataSource.getConnection(); PreparedStatement q = prepare(c,
                "UPDATE ss_presence SET last_seen=?,server_name=? WHERE player_uuid=?"
                + " AND session_id=? AND connected=1", clock.getAsLong(),
                server == null ? "" : server, player, session)) {
            return q.executeUpdate() == 1;
        }
    }

    public void sessionEnded(UUID player, UUID session) throws SQLException {
        try (Connection c = dataSource.getConnection(); PreparedStatement q = prepare(c,
                "UPDATE ss_presence SET connected=0,last_seen=? WHERE player_uuid=?"
                + " AND session_id=?", clock.getAsLong(), player, session)) {
            q.executeUpdate();
        }
    }

    public boolean sessionMatches(UUID player, UUID session) throws SQLException {
        try (Connection c = dataSource.getConnection(); PreparedStatement q = prepare(c,
                "SELECT 1 FROM ss_presence WHERE player_uuid=? AND session_id=?"
                + " AND connected=1 AND last_seen>=?", player, session,
                clock.getAsLong() - rules.presenceTimeoutMs);
             ResultSet r = q.executeQuery()) { return r.next(); }
    }

    public UUID resolveName(String name) throws SQLException {
        if (name == null || !name.matches("[A-Za-z0-9_]{1,16}")) return null;
        try (Connection c = dataSource.getConnection(); PreparedStatement q = prepare(c,
                "SELECT player_uuid FROM ss_presence WHERE last_name=? ORDER BY last_seen DESC LIMIT 2",
                name); ResultSet r = q.executeQuery()) {
            if (!r.next()) return null;
            UUID candidate = UUID.fromString(r.getString(1));
            return r.next() ? null : candidate;
        }
    }

    public String name(UUID player) throws SQLException {
        try (Connection c = dataSource.getConnection(); PreparedStatement q = prepare(c,
                "SELECT last_name FROM ss_presence WHERE player_uuid=?", player);
             ResultSet r = q.executeQuery()) { return r.next() ? r.getString(1) : player.toString(); }
    }

    public PartySnapshot getParty(UUID player) throws SQLException {
        try (Connection c = dataSource.getConnection()) {
            UUID id = partyId(c, player);
            return id == null ? null : snapshot(c, id);
        }
    }
    public PartySnapshot getPartyById(UUID party) throws SQLException {
        try (Connection c = dataSource.getConnection()) { return snapshot(c, party); }
    }

    public List<Notification> claimNotifications(UUID recipient, UUID session, int limit)
            throws SQLException {
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("Limite invalido");
        List<Notification> claimed = new ArrayList<Notification>();
        long now = clock.getAsLong();
        try (Connection c = dataSource.getConnection(); PreparedStatement list = prepare(c,
                "SELECT id,party_id,kind,actor_uuid,body FROM ss_notifications"
                + " WHERE recipient_uuid=? AND delivered_at IS NULL AND expires_at>?"
                + " AND claim_until<? ORDER BY id LIMIT ?", recipient, now, now, limit);
             ResultSet rows = list.executeQuery()) {
            while (rows.next()) {
                long id = rows.getLong(1);
                try (PreparedStatement claim = prepare(c,
                        "UPDATE ss_notifications SET claimed_session=?,claim_until=?"
                        + " WHERE id=? AND recipient_uuid=? AND delivered_at IS NULL"
                        + " AND claim_until<?", session, now + 10000, id, recipient, now)) {
                    if (claim.executeUpdate() == 1)
                        claimed.add(new Notification(id, recipient, rows.getString(2),
                            rows.getString(3), rows.getString(4), rows.getString(5)));
                }
            }
        }
        return claimed;
    }

    public boolean markDelivered(long id, UUID session) throws SQLException {
        try (Connection c = dataSource.getConnection(); PreparedStatement q = prepare(c,
                "UPDATE ss_notifications SET delivered_at=? WHERE id=? AND claimed_session=?"
                + " AND delivered_at IS NULL", clock.getAsLong(), id, session)) {
            return q.executeUpdate() == 1;
        }
    }

    public boolean inviteDeliverable(UUID recipient, UUID inviter, UUID party) throws SQLException {
        if (recipient == null || inviter == null || party == null) return false;
        try (Connection c = dataSource.getConnection(); PreparedStatement q = prepare(c,
                "SELECT 1 FROM ss_invites i JOIN ss_parties p ON p.party_id=i.party_id"
                + " JOIN ss_members m ON m.party_id=i.party_id AND m.player_uuid=i.inviter_uuid"
                + " WHERE i.party_id=? AND i.target_uuid=? AND i.inviter_uuid=?"
                + " AND i.state='PENDING' AND i.expires_at>? AND p.state='ACTIVE'"
                + " AND m.role IN ('LEADER','OFFICER') LIMIT 1",
                party, recipient, inviter, clock.getAsLong());
             ResultSet r = q.executeQuery()) { return r.next(); }
    }

    public void cleanupNotifications() throws SQLException {
        try (Connection c = dataSource.getConnection(); PreparedStatement q = prepare(c,
                "DELETE FROM ss_notifications WHERE expires_at<? OR delivered_at<?",
                clock.getAsLong() - 86400000L, clock.getAsLong() - 86400000L)) {
            q.executeUpdate();
        }
    }

    public static final class Notification {
        public final long id;
        public final UUID recipient;
        public final String partyId, kind, actorId, body;
        Notification(long id, UUID recipient, String partyId, String kind,
                     String actorId, String body) {
            this.id = id; this.recipient = recipient; this.partyId = partyId;
            this.kind = kind; this.actorId = actorId; this.body = body;
        }
    }

    private static PreparedStatement prepare(Connection c, String sql, Object... values)
            throws SQLException {
        PreparedStatement q = c.prepareStatement(sql);
        q.setQueryTimeout(5);
        for (int i = 0; i < values.length; i++) {
            Object value = values[i];
            if (value instanceof UUID) q.setString(i + 1, value.toString());
            else q.setObject(i + 1, value);
        }
        return q;
    }

    private UUID partyId(Connection c, UUID player) throws SQLException {
        try (PreparedStatement q = prepare(c,
                "SELECT party_id FROM ss_members WHERE player_uuid=?", player);
             ResultSet r = q.executeQuery()) {
            return r.next() ? UUID.fromString(r.getString(1)) : null;
        }
    }

    private PartySnapshot snapshot(Connection c, UUID id) throws SQLException {
        if (id == null) return null;
        try (PreparedStatement q = prepare(c,
                "SELECT leader_uuid,state,privacy,max_members,chat_enabled,created_at,revision,"
                + "leader_missing_since FROM ss_parties WHERE party_id=?", id);
             ResultSet r = q.executeQuery()) {
            if (!r.next()) return null;
            UUID leader = UUID.fromString(r.getString(1));
            PartyState state = PartyState.valueOf(r.getString(2));
            PartyPrivacy privacy = PartyPrivacy.valueOf(r.getString(3));
            int max = r.getInt(4);
            boolean chat = r.getBoolean(5);
            long created = r.getLong(6), revision = r.getLong(7);
            long missing = r.getLong(8);
            List<PartyMember> members = new ArrayList<PartyMember>();
            try (PreparedStatement m = prepare(c,
                    "SELECT m.player_uuid,m.role,m.joined_at,p.last_name,p.server_name,"
                    + "p.connected,p.last_seen FROM ss_members m LEFT JOIN ss_presence p"
                    + " ON p.player_uuid=m.player_uuid WHERE m.party_id=?"
                    + " ORDER BY m.joined_at,m.player_uuid", id);
                 ResultSet rows = m.executeQuery()) {
                while (rows.next()) {
                    UUID memberId = UUID.fromString(rows.getString(1));
                    String name = rows.getString(4);
                    if (name == null) name = memberId.toString();
                    members.add(new PartyMember(memberId, name, rows.getString(5),
                        PartyRole.valueOf(rows.getString(2)), rows.getLong(3),
                        rows.getBoolean(6) && rows.getLong(7) >= clock.getAsLong()
                            - rules.presenceTimeoutMs));
                }
            }
            return new PartySnapshot(id, leader, state, privacy, max, chat,
                created, revision, missing, members);
        }
    }

    private interface Transaction<T> { T execute(Connection c) throws SQLException; }
    private <T> T tx(Transaction<T> operation) throws SQLException {
        try (Connection c = dataSource.getConnection()) {
            c.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            c.setAutoCommit(false);
            try {
                T result = operation.execute(c);
                c.commit();
                return result;
            } catch (SQLException | RuntimeException error) {
                c.rollback();
                throw error;
            } finally { c.setAutoCommit(true); }
        }
    }

    private static boolean duplicate(SQLException error) {
        return error.getSQLState() != null && error.getSQLState().startsWith("23");
    }

    private Row lockParty(Connection c, UUID id) throws SQLException {
        try (PreparedStatement q = prepare(c,
                "SELECT leader_uuid,state,privacy,max_members,chat_enabled,revision,"
                + "leader_missing_since FROM ss_parties WHERE party_id=? FOR UPDATE", id);
             ResultSet r = q.executeQuery()) {
            return r.next() ? new Row(id, UUID.fromString(r.getString(1)),
                PartyState.valueOf(r.getString(2)), PartyPrivacy.valueOf(r.getString(3)),
                r.getInt(4), r.getBoolean(5), r.getLong(6), r.getLong(7)) : null;
        }
    }

    private static final class Row {
        final UUID id, leader;
        final PartyState state;
        final PartyPrivacy privacy;
        final int max;
        final boolean chat;
        final long revision, missingSince;
        Row(UUID id, UUID leader, PartyState state, PartyPrivacy privacy, int max,
            boolean chat, long revision, long missingSince) {
            this.id = id; this.leader = leader; this.state = state; this.privacy = privacy;
            this.max = max; this.chat = chat; this.revision = revision; this.missingSince = missingSince;
        }
    }

    private int size(Connection c, UUID party) throws SQLException {
        try (PreparedStatement q = prepare(c,
                "SELECT COUNT(*) FROM ss_members WHERE party_id=?", party);
             ResultSet r = q.executeQuery()) { r.next(); return r.getInt(1); }
    }

    private PartyRole role(Connection c, UUID party, UUID player) throws SQLException {
        try (PreparedStatement q = prepare(c,
                "SELECT role FROM ss_members WHERE party_id=? AND player_uuid=?", party, player);
             ResultSet r = q.executeQuery()) {
            return r.next() ? PartyRole.valueOf(r.getString(1)) : null;
        }
    }

    private boolean online(Connection c, UUID player) throws SQLException {
        try (PreparedStatement q = prepare(c,
                "SELECT 1 FROM ss_presence WHERE player_uuid=? AND connected=1 AND last_seen>=?",
                player, clock.getAsLong() - rules.presenceTimeoutMs);
             ResultSet r = q.executeQuery()) { return r.next(); }
    }

    private String server(Connection c, UUID player) throws SQLException {
        try (PreparedStatement q = prepare(c,
                "SELECT server_name FROM ss_presence WHERE player_uuid=?", player);
             ResultSet r = q.executeQuery()) { return r.next() ? r.getString(1) : ""; }
    }

    private boolean sameServer(Connection c, UUID first, UUID second) throws SQLException {
        return rules.crossServer || server(c, first).equals(server(c, second));
    }

    private boolean cooldown(Connection c, UUID player, String kind, long duration)
            throws SQLException {
        if (duration == 0) return true;
        long now = clock.getAsLong();
        try (PreparedStatement seed = prepare(c,
                "INSERT IGNORE INTO ss_cooldowns(player_uuid,kind,last_at) VALUES(?,?,0)",
                player, kind)) { seed.executeUpdate(); }
        try (PreparedStatement q = prepare(c,
                "SELECT last_at FROM ss_cooldowns WHERE player_uuid=? AND kind=? FOR UPDATE",
                player, kind); ResultSet r = q.executeQuery()) {
            if (!r.next() || now - r.getLong(1) < duration) return false;
        }
        try (PreparedStatement update = prepare(c,
                "UPDATE ss_cooldowns SET last_at=? WHERE player_uuid=? AND kind=?",
                now, player, kind)) { update.executeUpdate(); }
        return true;
    }

    private void emit(Connection c, UUID recipient, UUID party, String kind,
                      UUID actor, String body) throws SQLException {
        long now = clock.getAsLong();
        try (PreparedStatement q = prepare(c,
                "INSERT INTO ss_notifications(recipient_uuid,party_id,kind,actor_uuid,body,"
                + "created_at,expires_at,claim_until) VALUES(?,?,?,?,?,?,?,0)",
                recipient, party, kind, actor, body == null ? "" : body,
                now, now + 120000)) { q.executeUpdate(); }
    }

    private void emitToParty(Connection c, UUID party, String kind, UUID actor,
                             String body) throws SQLException {
        try (PreparedStatement q = prepare(c,
                "SELECT player_uuid FROM ss_members WHERE party_id=? ORDER BY joined_at", party);
             ResultSet r = q.executeQuery()) {
            while (r.next()) emit(c, UUID.fromString(r.getString(1)), party, kind, actor, body);
        }
    }

    private void bump(Connection c, UUID party) throws SQLException {
        try (PreparedStatement q = prepare(c,
                "UPDATE ss_parties SET revision=revision+1,updated_at=? WHERE party_id=?",
                clock.getAsLong(), party)) { q.executeUpdate(); }
    }

    private void cancelInvites(Connection c, UUID party) throws SQLException {
        try (PreparedStatement q = prepare(c,
                "UPDATE ss_invites SET state='CANCELLED',pending_key=NULL,updated_at=?"
                + " WHERE party_id=? AND state='PENDING'", clock.getAsLong(), party)) {
            q.executeUpdate();
        }
    }

    public PartyResult<PartySnapshot> create(UUID leader, int maxMembers) throws SQLException {
        if (!rules.enabled) return PartyResult.fail(PartyResult.Code.DISABLED);
        if (leader == null || maxMembers < 2 || maxMembers > rules.absoluteMaxMembers)
            return PartyResult.fail(PartyResult.Code.INVALID);
        try {
            return tx(c -> {
                if (!online(c, leader)) return PartyResult.fail(PartyResult.Code.NOT_ONLINE);
                if (partyId(c, leader) != null) return PartyResult.fail(PartyResult.Code.ALREADY_IN_PARTY);
                UUID id = UUID.randomUUID();
                long now = clock.getAsLong();
                try (PreparedStatement party = prepare(c,
                        "INSERT INTO ss_parties(party_id,leader_uuid,state,privacy,max_members,"
                        + "chat_enabled,created_at,updated_at,revision)"
                        + " VALUES(?,?,'ACTIVE',?,?,?,?,?,1)", id, leader,
                        rules.defaultPrivacy.name(), maxMembers, rules.chatEnabled, now, now);
                     PreparedStatement member = prepare(c,
                        "INSERT INTO ss_members(player_uuid,party_id,role,joined_at)"
                        + " VALUES(?,?,'LEADER',?)", leader, id, now)) {
                    party.executeUpdate();
                    member.executeUpdate();
                }
                return PartyResult.ok(snapshot(c, id));
            });
        } catch (SQLException error) {
            if (duplicate(error)) return PartyResult.fail(PartyResult.Code.ALREADY_IN_PARTY);
            throw error;
        }
    }

    public PartyResult<InviteSnapshot> invite(UUID actor, UUID target) throws SQLException {
        if (!rules.enabled) return PartyResult.fail(PartyResult.Code.DISABLED);
        if (actor == null || target == null) return PartyResult.fail(PartyResult.Code.INVALID);
        if (actor.equals(target)) return PartyResult.fail(PartyResult.Code.SELF_TARGET);
        try {
            return tx(c -> {
                UUID id = partyId(c, actor);
                if (id == null) return PartyResult.fail(PartyResult.Code.NOT_IN_PARTY);
                Row party = lockParty(c, id);
                if (party == null || party.state != PartyState.ACTIVE)
                    return PartyResult.fail(PartyResult.Code.NOT_FOUND);
                PartyRole actorRole = role(c, id, actor);
                if (actorRole != PartyRole.LEADER && actorRole != PartyRole.OFFICER)
                    return PartyResult.fail(PartyResult.Code.NOT_AUTHORIZED);
                if (party.privacy == PartyPrivacy.CLOSED)
                    return PartyResult.fail(PartyResult.Code.PRIVACY);
                if (!online(c, target)) return PartyResult.fail(PartyResult.Code.NOT_ONLINE);
                if (!sameServer(c, actor, target)) return PartyResult.fail(PartyResult.Code.PRIVACY);
                if (partyId(c, target) != null) return PartyResult.fail(PartyResult.Code.ALREADY_IN_PARTY);
                if (size(c, id) >= party.max) return PartyResult.fail(PartyResult.Code.FULL);
                expireInvite(c, id, target);
                try (PreparedStatement pending = prepare(c,
                        "SELECT 1 FROM ss_invites WHERE party_id=? AND target_uuid=?"
                        + " AND state='PENDING'", id, target);
                     ResultSet r = pending.executeQuery()) {
                    if (r.next()) return PartyResult.fail(PartyResult.Code.DUPLICATE_INVITE);
                }
                if (!cooldown(c, actor, "INVITE", rules.inviteCooldownMs))
                    return PartyResult.fail(PartyResult.Code.COOLDOWN);
                UUID inviteId = UUID.randomUUID();
                long now = clock.getAsLong(), expiry = now + rules.inviteExpirationMs;
                try (PreparedStatement q = prepare(c,
                        "INSERT INTO ss_invites(invite_id,party_id,inviter_uuid,target_uuid,"
                        + "state,pending_key,created_at,expires_at,updated_at)"
                        + " VALUES(?,?,?,?,'PENDING',1,?,?,?)",
                        inviteId, id, actor, target, now, expiry, now)) { q.executeUpdate(); }
                emit(c, target, id, "INVITE", actor, "");
                return PartyResult.ok(new InviteSnapshot(inviteId, id, actor, target,
                    InviteState.PENDING, now, expiry));
            });
        } catch (SQLException error) {
            if (duplicate(error)) return PartyResult.fail(PartyResult.Code.DUPLICATE_INVITE);
            throw error;
        }
    }

    public PartyResult<PartySnapshot> accept(UUID target, UUID inviter) throws SQLException {
        if (!rules.enabled) return PartyResult.fail(PartyResult.Code.DISABLED);
        if (target == null || inviter == null || target.equals(inviter))
            return PartyResult.fail(PartyResult.Code.INVALID);
        try {
            return tx(c -> {
                InviteSnapshot preview = pendingInvite(c, target, inviter);
                if (preview == null) return PartyResult.fail(PartyResult.Code.NO_INVITE);
                Row party = lockParty(c, preview.partyId);
                if (party == null || party.state != PartyState.ACTIVE) {
                    terminalInvite(c, preview.id, InviteState.CANCELLED);
                    return PartyResult.fail(PartyResult.Code.NOT_FOUND);
                }
                InviteSnapshot invite = lockedInvite(c, preview.id);
                if (invite == null || invite.state != InviteState.PENDING)
                    return PartyResult.fail(PartyResult.Code.NO_INVITE);
                if (invite.expiresAt <= clock.getAsLong()) {
                    terminalInvite(c, invite.id, InviteState.EXPIRED);
                    return PartyResult.fail(PartyResult.Code.INVITE_EXPIRED);
                }
                PartyRole inviterRole = role(c, party.id, inviter);
                if (inviterRole != PartyRole.LEADER && inviterRole != PartyRole.OFFICER) {
                    terminalInvite(c, invite.id, InviteState.CANCELLED);
                    return PartyResult.fail(PartyResult.Code.NOT_AUTHORIZED);
                }
                if (!online(c, target)) return PartyResult.fail(PartyResult.Code.NOT_ONLINE);
                if (!sameServer(c, inviter, target)) return PartyResult.fail(PartyResult.Code.PRIVACY);
                if (partyId(c, target) != null) return PartyResult.fail(PartyResult.Code.ALREADY_IN_PARTY);
                if (size(c, party.id) >= party.max) return PartyResult.fail(PartyResult.Code.FULL);
                try (PreparedStatement member = prepare(c,
                        "INSERT INTO ss_members(player_uuid,party_id,role,joined_at)"
                        + " VALUES(?,?,'MEMBER',?)", target, party.id, clock.getAsLong())) {
                    member.executeUpdate();
                }
                terminalInvite(c, invite.id, InviteState.ACCEPTED);
                cancelOtherInvites(c, target);
                bump(c, party.id);
                emitToParty(c, party.id, "JOIN", target, "");
                return PartyResult.ok(snapshot(c, party.id));
            });
        } catch (SQLException error) {
            if (duplicate(error)) return PartyResult.fail(PartyResult.Code.ALREADY_IN_PARTY);
            throw error;
        }
    }

    public PartyResult<InviteSnapshot> deny(UUID target, UUID inviter) throws SQLException {
        if (target == null || inviter == null) return PartyResult.fail(PartyResult.Code.INVALID);
        return tx(c -> {
            InviteSnapshot preview = pendingInvite(c, target, inviter);
            if (preview == null) return PartyResult.fail(PartyResult.Code.NO_INVITE);
            Row party = lockParty(c, preview.partyId);
            InviteSnapshot invite = lockedInvite(c, preview.id);
            if (invite == null || invite.state != InviteState.PENDING)
                return PartyResult.fail(PartyResult.Code.NO_INVITE);
            if (invite.expiresAt <= clock.getAsLong()) {
                terminalInvite(c, invite.id, InviteState.EXPIRED);
                return PartyResult.fail(PartyResult.Code.INVITE_EXPIRED);
            }
            terminalInvite(c, invite.id, InviteState.DENIED);
            if (party != null && party.state == PartyState.ACTIVE)
                emit(c, inviter, party.id, "DENY", target, "");
            return PartyResult.ok(new InviteSnapshot(invite.id, invite.partyId,
                inviter, target, InviteState.DENIED, invite.createdAt, invite.expiresAt));
        });
    }

    public PartyResult<PartySnapshot> joinOpen(UUID player, UUID leader) throws SQLException {
        if (!rules.enabled) return PartyResult.fail(PartyResult.Code.DISABLED);
        if (player == null || leader == null || player.equals(leader))
            return PartyResult.fail(PartyResult.Code.INVALID);
        try {
            return tx(c -> {
                UUID id = activePartyByLeader(c, leader);
                if (id == null) return PartyResult.fail(PartyResult.Code.NOT_FOUND);
                Row party = lockParty(c, id);
                if (party == null || party.state != PartyState.ACTIVE)
                    return PartyResult.fail(PartyResult.Code.NOT_FOUND);
                if (party.privacy != PartyPrivacy.OPEN)
                    return PartyResult.fail(PartyResult.Code.PRIVACY);
                if (!online(c, player)) return PartyResult.fail(PartyResult.Code.NOT_ONLINE);
                if (!sameServer(c, player, leader)) return PartyResult.fail(PartyResult.Code.PRIVACY);
                if (partyId(c, player) != null)
                    return PartyResult.fail(PartyResult.Code.ALREADY_IN_PARTY);
                if (size(c, id) >= party.max) return PartyResult.fail(PartyResult.Code.FULL);
                try (PreparedStatement member = prepare(c,
                        "INSERT INTO ss_members(player_uuid,party_id,role,joined_at)"
                        + " VALUES(?,?,'MEMBER',?)", player, id, clock.getAsLong())) {
                    member.executeUpdate();
                }
                cancelOtherInvites(c, player);
                bump(c, id);
                emitToParty(c, id, "JOIN", player, "");
                return PartyResult.ok(snapshot(c, id));
            });
        } catch (SQLException error) {
            if (duplicate(error)) return PartyResult.fail(PartyResult.Code.ALREADY_IN_PARTY);
            throw error;
        }
    }

    private UUID activePartyByLeader(Connection c, UUID leader) throws SQLException {
        try (PreparedStatement q = prepare(c,
                "SELECT party_id FROM ss_parties WHERE leader_uuid=? AND state='ACTIVE'"
                + " ORDER BY created_at DESC LIMIT 1", leader);
             ResultSet r = q.executeQuery()) {
            return r.next() ? UUID.fromString(r.getString(1)) : null;
        }
    }

    private InviteSnapshot pendingInvite(Connection c, UUID target, UUID inviter)
            throws SQLException {
        try (PreparedStatement q = prepare(c,
                "SELECT invite_id,party_id,created_at,expires_at FROM ss_invites"
                + " WHERE target_uuid=? AND inviter_uuid=? AND state='PENDING'"
                + " ORDER BY created_at DESC LIMIT 1", target, inviter);
             ResultSet r = q.executeQuery()) {
            return r.next() ? new InviteSnapshot(UUID.fromString(r.getString(1)),
                UUID.fromString(r.getString(2)), inviter, target, InviteState.PENDING,
                r.getLong(3), r.getLong(4)) : null;
        }
    }

    private InviteSnapshot lockedInvite(Connection c, UUID id) throws SQLException {
        try (PreparedStatement q = prepare(c,
                "SELECT party_id,inviter_uuid,target_uuid,state,created_at,expires_at"
                + " FROM ss_invites WHERE invite_id=? FOR UPDATE", id);
             ResultSet r = q.executeQuery()) {
            return r.next() ? new InviteSnapshot(id, UUID.fromString(r.getString(1)),
                UUID.fromString(r.getString(2)), UUID.fromString(r.getString(3)),
                InviteState.valueOf(r.getString(4)), r.getLong(5), r.getLong(6)) : null;
        }
    }

    private void terminalInvite(Connection c, UUID id, InviteState state) throws SQLException {
        try (PreparedStatement q = prepare(c,
                "UPDATE ss_invites SET state=?,pending_key=NULL,updated_at=?"
                + " WHERE invite_id=? AND state='PENDING'",
                state.name(), clock.getAsLong(), id)) { q.executeUpdate(); }
    }

    private void expireInvite(Connection c, UUID party, UUID target) throws SQLException {
        try (PreparedStatement q = prepare(c,
                "UPDATE ss_invites SET state='EXPIRED',pending_key=NULL,updated_at=?"
                + " WHERE party_id=? AND target_uuid=? AND state='PENDING' AND expires_at<=?",
                clock.getAsLong(), party, target, clock.getAsLong())) { q.executeUpdate(); }
    }

    private void cancelOtherInvites(Connection c, UUID target) throws SQLException {
        try (PreparedStatement q = prepare(c,
                "UPDATE ss_invites SET state='CANCELLED',pending_key=NULL,updated_at=?"
                + " WHERE target_uuid=? AND state='PENDING'", clock.getAsLong(), target)) {
            q.executeUpdate();
        }
    }

    public PartyResult<PartySnapshot> leave(UUID player) throws SQLException {
        if (player == null) return PartyResult.fail(PartyResult.Code.INVALID);
        return tx(c -> {
            UUID id = partyId(c, player);
            if (id == null) return PartyResult.fail(PartyResult.Code.NOT_IN_PARTY);
            Row party = lockParty(c, id);
            if (party == null || party.state != PartyState.ACTIVE)
                return PartyResult.fail(PartyResult.Code.NOT_FOUND);
            if (party.leader.equals(player)) {
                if (size(c, id) > 1) return PartyResult.fail(PartyResult.Code.CONFLICT);
                return close(c, party, PartyState.DISBANDED, player);
            }
            emitToParty(c, id, "LEAVE", player, "");
            deleteMember(c, player);
            bump(c, id);
            return PartyResult.ok(snapshot(c, id));
        });
    }

    public PartyResult<PartySnapshot> kick(UUID actor, UUID target) throws SQLException {
        if (actor == null || target == null) return PartyResult.fail(PartyResult.Code.INVALID);
        return tx(c -> {
            UUID id = partyId(c, actor);
            if (id == null) return PartyResult.fail(PartyResult.Code.NOT_IN_PARTY);
            Row party = lockParty(c, id);
            if (party == null || party.state != PartyState.ACTIVE)
                return PartyResult.fail(PartyResult.Code.NOT_FOUND);
            if (!party.leader.equals(actor)) return PartyResult.fail(PartyResult.Code.NOT_LEADER);
            if (actor.equals(target)) return PartyResult.fail(PartyResult.Code.SELF_TARGET);
            if (role(c, id, target) == null) return PartyResult.fail(PartyResult.Code.NOT_FOUND);
            emit(c, target, id, "KICKED", actor, "");
            emitToParty(c, id, "KICK", actor, nameInConnection(c, target));
            deleteMember(c, target);
            bump(c, id);
            return PartyResult.ok(snapshot(c, id));
        });
    }

    public PartyResult<PartySnapshot> promote(UUID actor, UUID target) throws SQLException {
        if (actor == null || target == null) return PartyResult.fail(PartyResult.Code.INVALID);
        return tx(c -> {
            UUID id = partyId(c, actor);
            if (id == null) return PartyResult.fail(PartyResult.Code.NOT_IN_PARTY);
            Row party = lockParty(c, id);
            if (party == null || party.state != PartyState.ACTIVE)
                return PartyResult.fail(PartyResult.Code.NOT_FOUND);
            if (!party.leader.equals(actor)) return PartyResult.fail(PartyResult.Code.NOT_LEADER);
            if (role(c, id, target) != PartyRole.MEMBER)
                return PartyResult.fail(PartyResult.Code.INVALID);
            try (PreparedStatement q = prepare(c,
                    "UPDATE ss_members SET role='OFFICER' WHERE party_id=? AND player_uuid=?",
                    id, target)) { q.executeUpdate(); }
            bump(c, id);
            emitToParty(c, id, "PROMOTE", actor, nameInConnection(c, target));
            return PartyResult.ok(snapshot(c, id));
        });
    }

    public PartyResult<PartySnapshot> transfer(UUID actor, UUID target) throws SQLException {
        if (actor == null || target == null) return PartyResult.fail(PartyResult.Code.INVALID);
        return tx(c -> {
            UUID id = partyId(c, actor);
            if (id == null) return PartyResult.fail(PartyResult.Code.NOT_IN_PARTY);
            Row party = lockParty(c, id);
            if (party == null || party.state != PartyState.ACTIVE)
                return PartyResult.fail(PartyResult.Code.NOT_FOUND);
            if (!party.leader.equals(actor)) return PartyResult.fail(PartyResult.Code.NOT_LEADER);
            if (actor.equals(target)) return PartyResult.fail(PartyResult.Code.SELF_TARGET);
            if (role(c, id, target) == null) return PartyResult.fail(PartyResult.Code.NOT_FOUND);
            changeLeader(c, id, actor, target);
            emitToParty(c, id, "LEADER", target, "");
            return PartyResult.ok(snapshot(c, id));
        });
    }

    public PartyResult<PartySnapshot> disband(UUID actor) throws SQLException {
        if (actor == null) return PartyResult.fail(PartyResult.Code.INVALID);
        return tx(c -> {
            UUID id = partyId(c, actor);
            if (id == null) return PartyResult.fail(PartyResult.Code.NOT_IN_PARTY);
            Row party = lockParty(c, id);
            if (party == null || party.state != PartyState.ACTIVE)
                return PartyResult.fail(PartyResult.Code.NOT_FOUND);
            if (!party.leader.equals(actor)) return PartyResult.fail(PartyResult.Code.NOT_LEADER);
            return close(c, party, PartyState.DISBANDED, actor);
        });
    }

    private PartyResult<PartySnapshot> close(Connection c, Row party, PartyState state,
                                              UUID actor) throws SQLException {
        emitToParty(c, party.id, "DISBAND", actor, "");
        cancelInvites(c, party.id);
        try (PreparedStatement members = prepare(c,
                "DELETE FROM ss_members WHERE party_id=?", party.id);
             PreparedStatement update = prepare(c,
                "UPDATE ss_parties SET state=?,revision=revision+1,updated_at=?,"
                + "leader_missing_since=NULL WHERE party_id=?",
                state.name(), clock.getAsLong(), party.id)) {
            members.executeUpdate(); update.executeUpdate();
        }
        return PartyResult.ok(snapshot(c, party.id));
    }

    private void deleteMember(Connection c, UUID member) throws SQLException {
        try (PreparedStatement q = prepare(c,
                "DELETE FROM ss_members WHERE player_uuid=?", member)) { q.executeUpdate(); }
    }

    private void changeLeader(Connection c, UUID party, UUID oldLeader, UUID newLeader)
            throws SQLException {
        try (PreparedStatement old = prepare(c,
                "UPDATE ss_members SET role='MEMBER' WHERE party_id=? AND player_uuid=?",
                party, oldLeader);
             PreparedStatement next = prepare(c,
                "UPDATE ss_members SET role='LEADER' WHERE party_id=? AND player_uuid=?",
                party, newLeader);
             PreparedStatement p = prepare(c,
                "UPDATE ss_parties SET leader_uuid=?,leader_missing_since=NULL,"
                + "revision=revision+1,updated_at=? WHERE party_id=?",
                newLeader, clock.getAsLong(), party)) {
            old.executeUpdate(); next.executeUpdate(); p.executeUpdate();
        }
        cancelInvites(c, party);
    }

    public PartyResult<PartySnapshot> setPrivacy(UUID actor, PartyPrivacy privacy)
            throws SQLException {
        if (privacy == null) return PartyResult.fail(PartyResult.Code.INVALID);
        return setting(actor, "privacy", privacy.name());
    }

    public PartyResult<PartySnapshot> setChatEnabled(UUID actor, boolean enabled)
            throws SQLException { return setting(actor, "chat_enabled", enabled ? 1 : 0); }

    public PartyResult<PartySnapshot> setMaxMembers(UUID actor, int limit)
            throws SQLException {
        if (limit < 2 || limit > rules.absoluteMaxMembers)
            return PartyResult.fail(PartyResult.Code.INVALID);
        return setting(actor, "max_members", limit);
    }

    private PartyResult<PartySnapshot> setting(UUID actor, String column, Object value)
            throws SQLException {
        if (actor == null) return PartyResult.fail(PartyResult.Code.INVALID);
        return tx(c -> {
            UUID id = partyId(c, actor);
            if (id == null) return PartyResult.fail(PartyResult.Code.NOT_IN_PARTY);
            Row party = lockParty(c, id);
            if (party == null || party.state != PartyState.ACTIVE)
                return PartyResult.fail(PartyResult.Code.NOT_FOUND);
            if (!party.leader.equals(actor)) return PartyResult.fail(PartyResult.Code.NOT_LEADER);
            if ("max_members".equals(column) && (Integer) value < size(c, id))
                return PartyResult.fail(PartyResult.Code.FULL);
            // Column comes exclusively from the three fixed methods above.
            try (PreparedStatement q = prepare(c,
                    "UPDATE ss_parties SET " + column + "=?,revision=revision+1,updated_at=?"
                    + " WHERE party_id=?", value, clock.getAsLong(), id)) { q.executeUpdate(); }
            if ("privacy".equals(column) && PartyPrivacy.CLOSED.name().equals(value)) cancelInvites(c, id);
            emitToParty(c, id, "SETTINGS", actor, column);
            return PartyResult.ok(snapshot(c, id));
        });
    }

    public PartyResult<Void> chat(UUID sender, String raw) throws SQLException {
        if (!rules.enabled || !rules.chatEnabled) return PartyResult.fail(PartyResult.Code.DISABLED);
        final String body;
        try { body = PartyRules.chat(raw); }
        catch (IllegalArgumentException error) { return PartyResult.fail(PartyResult.Code.INVALID); }
        return tx(c -> {
            UUID id = partyId(c, sender);
            if (id == null) return PartyResult.fail(PartyResult.Code.NOT_IN_PARTY);
            Row party = lockParty(c, id);
            if (party == null || party.state != PartyState.ACTIVE)
                return PartyResult.fail(PartyResult.Code.NOT_FOUND);
            if (!party.chat) return PartyResult.fail(PartyResult.Code.DISABLED);
            if (!online(c, sender)) return PartyResult.fail(PartyResult.Code.NOT_ONLINE);
            if (!cooldown(c, sender, "CHAT", rules.chatCooldownMs))
                return PartyResult.fail(PartyResult.Code.COOLDOWN);
            try (PreparedStatement q = prepare(c,
                    "SELECT m.player_uuid FROM ss_members m JOIN ss_presence p"
                    + " ON p.player_uuid=m.player_uuid WHERE m.party_id=? AND p.connected=1"
                    + " AND p.last_seen>=? ORDER BY m.joined_at,m.player_uuid",
                    id, clock.getAsLong() - rules.presenceTimeoutMs);
                 ResultSet recipients = q.executeQuery()) {
                while (recipients.next()) emit(c, UUID.fromString(recipients.getString(1)),
                    id, "CHAT", sender, body);
            }
            return PartyResult.ok(null);
        });
    }

    public PartyResult<PartyTransferPlan> planTransfer(UUID actor, String destination,
                                                        int capacity) throws SQLException {
        if (destination == null || !destination.matches("[A-Za-z0-9_-]{1,64}") || capacity < 1)
            return PartyResult.fail(PartyResult.Code.INVALID);
        PartySnapshot party = getParty(actor);
        if (party == null) return PartyResult.fail(PartyResult.Code.NOT_IN_PARTY);
        if (!party.leaderId.equals(actor)) return PartyResult.fail(PartyResult.Code.NOT_LEADER);
        List<UUID> online = new ArrayList<UUID>();
        for (PartyMember member : party.members) if (member.online) online.add(member.playerId);
        if (online.size() > capacity) return PartyResult.fail(PartyResult.Code.FULL);
        return PartyResult.ok(new PartyTransferPlan(party.id, party.revision,
            destination, online, capacity));
    }

    /** Called periodically by one or more proxies. Party row locks serialize elections. */
    public int sweep() throws SQLException {
        long now = clock.getAsLong();
        try (Connection c = dataSource.getConnection(); PreparedStatement q = prepare(c,
                "UPDATE ss_invites SET state='EXPIRED',pending_key=NULL,updated_at=?"
                + " WHERE state='PENDING' AND expires_at<=?", now, now)) { q.executeUpdate(); }
        List<UUID> parties = new ArrayList<UUID>();
        try (Connection c = dataSource.getConnection(); PreparedStatement q = prepare(c,
                "SELECT party_id FROM ss_parties WHERE state='ACTIVE'");
             ResultSet r = q.executeQuery()) {
            while (r.next()) parties.add(UUID.fromString(r.getString(1)));
        }
        int changed = 0;
        for (UUID id : parties) {
            Boolean result = tx(c -> {
                Row party = lockParty(c, id);
                if (party == null || party.state != PartyState.ACTIVE) return false;
                if (online(c, party.leader)) {
                    if (party.missingSince != 0) {
                        try (PreparedStatement q = prepare(c,
                                "UPDATE ss_parties SET leader_missing_since=NULL WHERE party_id=?", id))
                            { q.executeUpdate(); }
                    }
                    return false;
                }
                if (party.missingSince == 0) {
                    try (PreparedStatement q = prepare(c,
                            "UPDATE ss_parties SET leader_missing_since=? WHERE party_id=?",
                            now, id)) { q.executeUpdate(); }
                    return false;
                }
                if (now - party.missingSince < rules.leaderGraceMs) return false;
                UUID successor = rules.transferOnLeaderTimeout ? successor(c, id, party.leader) : null;
                if (successor == null) close(c, party, PartyState.EXPIRED, party.leader);
                else {
                    changeLeader(c, id, party.leader, successor);
                    emitToParty(c, id, "LEADER", successor, "");
                }
                return true;
            });
            if (result) changed++;
        }
        return changed;
    }

    private UUID successor(Connection c, UUID party, UUID leader) throws SQLException {
        try (PreparedStatement q = prepare(c,
                "SELECT m.player_uuid FROM ss_members m JOIN ss_presence p"
                + " ON p.player_uuid=m.player_uuid WHERE m.party_id=? AND m.player_uuid<>?"
                + " AND p.connected=1 AND p.last_seen>=?"
                + " ORDER BY CASE WHEN m.role='OFFICER' THEN 0 ELSE 1 END,"
                + " m.joined_at,m.player_uuid LIMIT 1", party, leader,
                clock.getAsLong() - rules.presenceTimeoutMs);
             ResultSet r = q.executeQuery()) {
            return r.next() ? UUID.fromString(r.getString(1)) : null;
        }
    }

    private String nameInConnection(Connection c, UUID player) throws SQLException {
        try (PreparedStatement q = prepare(c,
                "SELECT last_name FROM ss_presence WHERE player_uuid=?", player);
             ResultSet r = q.executeQuery()) { return r.next() ? r.getString(1) : player.toString(); }
    }
}
