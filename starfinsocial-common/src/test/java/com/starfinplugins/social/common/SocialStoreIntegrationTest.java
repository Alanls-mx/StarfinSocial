package com.starfinplugins.social.common;

import com.starfinplugins.social.api.PartyPrivacy;
import com.starfinplugins.social.api.PartyResult;
import com.starfinplugins.social.api.PartyRole;
import com.starfinplugins.social.api.PartySnapshot;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;
import javax.sql.DataSource;
import org.junit.Assume;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import static org.junit.Assert.*;

/** Runs against a disposable MySQL database when SOCIAL_TEST_JDBC is configured. */
public final class SocialStoreIntegrationTest {
    private static DataSource source;
    private AtomicLong clock;
    private SocialStore store;
    private UUID a,b,c,d;

    @BeforeClass public static void setupDatabase() throws Exception {
        String url = System.getenv("SOCIAL_TEST_JDBC");
        Assume.assumeTrue("Set SOCIAL_TEST_JDBC for MySQL integration", url != null && !url.isEmpty());
        String user = System.getenv("SOCIAL_TEST_USER");
        String password = System.getenv("SOCIAL_TEST_PASSWORD");
        Class.forName("com.mysql.cj.jdbc.Driver");
        source = new DataSource() {
            @Override public Connection getConnection() throws SQLException {
                return DriverManager.getConnection(url,user,password);
            }
            @Override public Connection getConnection(String u,String p) throws SQLException {
                return DriverManager.getConnection(url,u,p);
            }
            @Override public PrintWriter getLogWriter() { return null; }
            @Override public void setLogWriter(PrintWriter writer) { }
            @Override public void setLoginTimeout(int seconds) { }
            @Override public int getLoginTimeout() { return 5; }
            @Override public Logger getParentLogger() throws SQLFeatureNotSupportedException {
                throw new SQLFeatureNotSupportedException(); }
            @Override public <T> T unwrap(Class<T> type) throws SQLException { throw new SQLException(); }
            @Override public boolean isWrapperFor(Class<?> type) { return false; }
        };
    }

    @Before public void setup() throws Exception {
        clock = new AtomicLong(1_000_000L);
        PartyRules rules = new PartyRules(true,true,true,true,2,4,
            60000,3000,1000,120000,30000,PartyPrivacy.INVITE_ONLY);
        store = new SocialStore(source,rules,clock::get);
        store.migrate();
        try (Connection conn = source.getConnection(); Statement q = conn.createStatement()) {
            q.executeUpdate("DELETE FROM ss_notifications");
            q.executeUpdate("DELETE FROM ss_invites");
            q.executeUpdate("DELETE FROM ss_members");
            q.executeUpdate("DELETE FROM ss_parties");
            q.executeUpdate("DELETE FROM ss_presence");
            q.executeUpdate("DELETE FROM ss_cooldowns");
        }
        a = UUID.randomUUID(); b = UUID.randomUUID(); c = UUID.randomUUID(); d = UUID.randomUUID();
        online(a,"Alice","lobby-1"); online(b,"Bob","lobby-2");
        online(c,"Carol","bedwars-1"); online(d,"Dave","lobby-1");
    }

    private void online(UUID player,String name,String server) throws Exception {
        store.sessionStarted(player,name,"proxy-test",UUID.randomUUID(),server);
    }
    private void advance(long millis) { clock.addAndGet(millis); }
    private void assertCode(PartyResult.Code expected, PartyResult<?> result) {
        assertEquals(expected,result.code);
    }

    @Test public void createInviteAcceptCrossServerAndRestart() throws Exception {
        PartySnapshot party = store.create(a,3).value;
        assertNotNull(party);
        assertCode(PartyResult.Code.OK,store.invite(a,b));
        assertTrue(store.inviteDeliverable(b,a,party.id));
        assertCode(PartyResult.Code.DUPLICATE_INVITE,store.invite(a,b));
        assertCode(PartyResult.Code.OK,store.accept(b,a));
        assertFalse(store.inviteDeliverable(b,a,party.id));
        assertEquals(2,store.getParty(b).size());
        assertEquals("lobby-2",store.getParty(b).member(b).server);
        SocialStore restarted = new SocialStore(source,PartyRules.defaults(),clock::get);
        restarted.migrate();
        assertEquals(party.id,restarted.getParty(b).id);
        assertEquals(party.id,restarted.getPartyById(party.id).id);
        assertCode(PartyResult.Code.ALREADY_IN_PARTY,restarted.create(b,2));
    }

    @Test public void denyExpireCooldownAndCapacity() throws Exception {
        store.create(a,2);
        assertCode(PartyResult.Code.OK,store.invite(a,b));
        assertCode(PartyResult.Code.OK,store.deny(b,a));
        assertCode(PartyResult.Code.NO_INVITE,store.accept(b,a));
        assertCode(PartyResult.Code.COOLDOWN,store.invite(a,c));
        advance(3001);
        assertCode(PartyResult.Code.OK,store.invite(a,c));
        advance(60001);
        assertCode(PartyResult.Code.INVITE_EXPIRED,store.accept(c,a));
        online(a,"Alice","lobby-1"); online(b,"Bob","lobby-2");
        online(d,"Dave","lobby-1");
        assertCode(PartyResult.Code.OK,store.invite(a,b));
        assertCode(PartyResult.Code.OK,store.accept(b,a));
        advance(3001);
        assertCode(PartyResult.Code.FULL,store.invite(a,d));
    }

    @Test public void kickPromoteTransferSettingsAndDisband() throws Exception {
        store.create(a,3);
        store.invite(a,b); store.accept(b,a);
        advance(3001); store.invite(a,c); store.accept(c,a);
        assertCode(PartyResult.Code.NOT_LEADER,store.kick(b,c));
        assertCode(PartyResult.Code.OK,store.promote(a,b));
        assertEquals(PartyRole.OFFICER,store.getParty(a).member(b).role);
        assertCode(PartyResult.Code.OK,store.transfer(a,b));
        assertEquals(b,store.getParty(a).leaderId);
        assertCode(PartyResult.Code.NOT_LEADER,store.disband(a));
        assertCode(PartyResult.Code.OK,store.kick(b,c));
        assertNull(store.getParty(c));
        assertCode(PartyResult.Code.OK,store.setPrivacy(b,PartyPrivacy.OPEN));
        assertCode(PartyResult.Code.OK,store.setMaxMembers(b,4));
        assertCode(PartyResult.Code.OK,store.joinOpen(d,b));
        assertCode(PartyResult.Code.CONFLICT,store.leave(b));
        assertCode(PartyResult.Code.OK,store.disband(b));
        assertNull(store.getParty(a));
        assertNull(store.getParty(d));
    }

    @Test public void chatAndServerMoveAndOutbox() throws Exception {
        store.create(a,3); store.invite(a,b); store.accept(b,a);
        assertCode(PartyResult.Code.OK,store.chat(a,"hello"));
        assertCode(PartyResult.Code.COOLDOWN,store.chat(a,"too fast"));
        List<SocialStore.Notification> notifications = store.claimNotifications(b,UUID.randomUUID(),50);
        assertTrue(notifications.stream().anyMatch(n -> "CHAT".equals(n.kind)
            && "hello".equals(n.body)));
        assertCode(PartyResult.Code.OK,store.setChatEnabled(a,false));
        assertCode(PartyResult.Code.DISABLED,store.chat(b,"blocked"));
        store.sessionStarted(b,"Bob","proxy-test",UUID.randomUUID(),"bedwars-2");
        assertEquals("bedwars-2",store.getParty(b).member(b).server);
        assertEquals(2,store.getParty(a).size());
    }

    @Test public void leaderDisconnectGraceAndElection() throws Exception {
        store.create(a,3); store.invite(a,b); store.accept(b,a);
        advance(30001);
        store.sessionStarted(b,"Bob","proxy-test",UUID.randomUUID(),"lobby-2");
        store.sweep();
        assertEquals(a,store.getParty(b).leaderId);
        advance(119000);
        store.sessionStarted(a,"Alice","proxy-test",UUID.randomUUID(),"lobby-1");
        store.sweep();
        assertEquals(a,store.getParty(b).leaderId);
        advance(30001);
        store.sessionStarted(b,"Bob","proxy-test",UUID.randomUUID(),"lobby-2");
        store.sweep();
        advance(120001);
        store.sessionStarted(b,"Bob","proxy-test",UUID.randomUUID(),"lobby-2");
        store.sweep();
        assertEquals(b,store.getParty(b).leaderId);
    }

    @Test public void concurrentAcceptHasSingleMembership() throws Exception {
        store.create(a,3); store.invite(a,b);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            List<Future<PartyResult<PartySnapshot>>> results = new ArrayList<Future<PartyResult<PartySnapshot>>>();
            for (int i=0;i<2;i++) results.add(workers.submit((Callable<PartyResult<PartySnapshot>>)
                () -> store.accept(b,a)));
            int successes=0;
            for (Future<PartyResult<PartySnapshot>> result : results)
                if (result.get().succeeded()) successes++;
            assertEquals(1,successes);
            assertEquals(2,store.getParty(a).size());
        } finally { workers.shutdownNow(); }
    }

    @Test public void expiredLeaderWithoutSuccessorDisbandsAndFreesMembership() throws Exception {
        store.create(a,2);
        advance(30001); store.sweep();
        advance(120001); store.sweep();
        assertNull(store.getParty(a));
        online(a,"Alice","lobby-2");
        assertCode(PartyResult.Code.OK,store.create(a,2));
    }

    @Test public void staleSessionCannotHeartbeatOrClaimTwice() throws Exception {
        UUID old = UUID.randomUUID(), current = UUID.randomUUID();
        store.sessionStarted(a,"Alice","proxy-test",old,"lobby-1");
        store.sessionStarted(a,"Alice","proxy-test",current,"bedwars-1");
        assertFalse(store.heartbeat(a,old,"lobby-1"));
        assertTrue(store.sessionMatches(a,current));
        assertFalse(store.sessionMatches(a,old));
        store.create(a,2); store.invite(a,b);
        List<SocialStore.Notification> first = store.claimNotifications(b,UUID.randomUUID(),50);
        List<SocialStore.Notification> duplicate = store.claimNotifications(b,UUID.randomUUID(),50);
        assertEquals(1,first.size());
        assertTrue(duplicate.isEmpty());
    }

    @Test public void unavailableDatabaseFailsClosedAndChatSanitizes() throws Exception {
        assertEquals("hi red", PartyRules.chat("hi &cred"));
        assertCode(PartyResult.Code.INVALID,store.chat(a,""));
        DataSource down = new DataSource() {
            @Override public Connection getConnection() throws SQLException { throw new SQLException("offline"); }
            @Override public Connection getConnection(String u,String p) throws SQLException { throw new SQLException("offline"); }
            @Override public PrintWriter getLogWriter() { return null; }
            @Override public void setLogWriter(PrintWriter w) { }
            @Override public void setLoginTimeout(int s) { }
            @Override public int getLoginTimeout() { return 0; }
            @Override public Logger getParentLogger() throws SQLFeatureNotSupportedException {
                throw new SQLFeatureNotSupportedException(); }
            @Override public <T> T unwrap(Class<T> c) throws SQLException { throw new SQLException(); }
            @Override public boolean isWrapperFor(Class<?> c) { return false; }
        };
        try { new SocialStore(down,PartyRules.defaults()).create(a,2); fail("Must fail closed"); }
        catch (SQLException expected) { assertEquals("offline",expected.getMessage()); }
    }

    @Test public void concurrentLeadershipTransfersChooseOnlyOne() throws Exception {
        store.create(a,3);
        store.invite(a,b); store.accept(b,a);
        advance(3001);
        store.invite(a,c); store.accept(c,a);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<PartyResult<PartySnapshot>> first = workers.submit(() -> store.transfer(a,b));
            Future<PartyResult<PartySnapshot>> second = workers.submit(() -> store.transfer(a,c));
            int successes = (first.get().succeeded() ? 1 : 0) + (second.get().succeeded() ? 1 : 0);
            assertEquals(1,successes);
            PartySnapshot party = store.getParty(a);
            assertEquals(1,party.members.stream().filter(m -> m.role == PartyRole.LEADER).count());
            assertEquals(PartyRole.LEADER,party.member(party.leaderId).role);
        } finally { workers.shutdownNow(); }
    }
}
