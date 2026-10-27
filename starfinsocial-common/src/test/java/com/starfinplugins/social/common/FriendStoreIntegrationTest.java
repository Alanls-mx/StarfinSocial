package com.starfinplugins.social.common;

import com.mysql.cj.jdbc.MysqlDataSource;
import com.starfinplugins.social.api.FriendResult;
import com.starfinplugins.social.api.FriendSettings;
import java.sql.Connection;
import java.sql.Statement;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import javax.sql.DataSource;
import org.junit.Assume;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import static org.junit.Assert.*;

/** Runs only against a disposable MySQL schema, never the live network database. */
public final class FriendStoreIntegrationTest {
    private static DataSource source;
    private AtomicLong clock;
    private SocialStore presence;
    private FriendStore friends;
    private UUID alice,bob,carol;
    private UUID bobSession;

    @BeforeClass public static void database() {
        String url=System.getenv("SOCIAL_TEST_JDBC");
        Assume.assumeTrue("Disposable MySQL required",url!=null && !url.isEmpty());
        MysqlDataSource mysql=new MysqlDataSource();
        mysql.setURL(url);
        mysql.setUser(System.getenv("SOCIAL_TEST_USER"));
        mysql.setPassword(System.getenv("SOCIAL_TEST_PASSWORD"));
        source=mysql;
    }
    @Before public void setup() throws Exception {
        clock=new AtomicLong(1000000);
        presence=new SocialStore(source,PartyRules.defaults(),clock::get);
        presence.migrate();
        friends=new FriendStore(source,clock::get,7*86400000L,5000,25,true,30000,
            new FriendSettings(true,true,false,true,true,true));
        friends.migrate();
        try (Connection c=source.getConnection(); Statement s=c.createStatement()) {
            s.executeUpdate("DELETE FROM ss_friendships");
            s.executeUpdate("DELETE FROM ss_friend_requests");
            s.executeUpdate("DELETE FROM ss_friend_settings");
            s.executeUpdate("DELETE FROM ss_notifications");
            s.executeUpdate("DELETE FROM ss_cooldowns");
            s.executeUpdate("DELETE FROM ss_presence");
        }
        alice=UUID.randomUUID(); bob=UUID.randomUUID(); carol=UUID.randomUUID();
        presence.sessionStarted(alice,"Alice","proxy1",UUID.randomUUID(),"auth");
        bobSession=UUID.randomUUID();
        presence.sessionStarted(bob,"Bob","proxy2",bobSession,"bedwars");
        presence.sessionStarted(carol,"Carol","proxy2",UUID.randomUUID(),"auth");
    }
    private void code(FriendResult.Code code,FriendResult result) { assertEquals(code,result.code); }

    @Test public void requestAcceptPersistsAcrossStoreRestartAndUsesUuid() throws Exception {
        code(FriendResult.Code.OK,friends.add(alice,bob,50));
        assertEquals(1,friends.requests(bob,true).size());
        assertEquals(1,friends.requests(alice,false).size());
        code(FriendResult.Code.OK,friends.accept(bob,alice,50));
        assertTrue(friends.isFriend(alice,bob));
        assertTrue(friends.isFriend(bob,alice));
        assertTrue(friends.requests(bob,true).isEmpty());
        presence.sessionStarted(bob,"NewBob","proxy2",UUID.randomUUID(),"bedwars");
        FriendStore restarted=new FriendStore(source,clock::get,7*86400000L,5000,25,true,30000,
            new FriendSettings(true,true,false,true,true,true));
        assertEquals("NewBob",restarted.friends(alice,1,10).get(0).name);
        assertEquals("bedwars",restarted.friends(alice,1,10).get(0).server);
        code(FriendResult.Code.ALREADY_FRIENDS,restarted.add(alice,bob,50));
        code(FriendResult.Code.OK,restarted.remove(alice,bob));
        assertFalse(restarted.isFriend(bob,alice));
    }
    @Test public void selfDuplicateReverseAndCooldown() throws Exception {
        code(FriendResult.Code.SELF,friends.add(alice,alice,50));
        code(FriendResult.Code.OK,friends.add(alice,bob,50));
        code(FriendResult.Code.DUPLICATE,friends.add(alice,bob,50));
        code(FriendResult.Code.DUPLICATE,friends.add(bob,alice,50));
        code(FriendResult.Code.COOLDOWN,friends.add(alice,carol,50));
        clock.addAndGet(5001);
        code(FriendResult.Code.OK,friends.add(alice,carol,50));
    }
    @Test public void denyCancelExpireAndOfflineRequest() throws Exception {
        code(FriendResult.Code.OK,friends.add(alice,bob,50));
        code(FriendResult.Code.OK,friends.resolve(bob,alice,false));
        code(FriendResult.Code.NO_REQUEST,friends.accept(bob,alice,50));
        clock.addAndGet(5001);
        code(FriendResult.Code.OK,friends.add(alice,bob,50));
        code(FriendResult.Code.OK,friends.resolve(alice,bob,true));
        clock.addAndGet(5001);
        code(FriendResult.Code.OK,friends.add(alice,bob,50));
        clock.addAndGet(7*86400000L+1);
        code(FriendResult.Code.NO_REQUEST,friends.accept(bob,alice,50));
        presence.sessionEnded(carol,UUID.randomUUID()); // stale sessions cannot end somebody else's session
        code(FriendResult.Code.OK,friends.add(carol,bob,50));
    }
    @Test public void privacyAndLimitsCannotBeBypassed() throws Exception {
        code(FriendResult.Code.OK,friends.setSetting(bob,"requests",false));
        code(FriendResult.Code.PRIVACY,friends.add(alice,bob,50));
        code(FriendResult.Code.OK,friends.setSetting(bob,"requests",true));
        code(FriendResult.Code.OK,friends.add(alice,bob,50));
        code(FriendResult.Code.LIMIT,friends.accept(bob,alice,0));
        code(FriendResult.Code.OK,friends.accept(bob,alice,50));
        code(FriendResult.Code.OK,friends.setSetting(bob,"show-online",false));
        assertFalse(friends.friends(alice,1,10).get(0).online);
        assertEquals(0,friends.friends(alice,1,10).get(0).lastSeen);
        code(FriendResult.Code.OK,friends.setSetting(bob,"show-server",false));
        assertEquals("",friends.friends(alice,1,10).get(0).server);
        code(FriendResult.Code.OK,friends.setSetting(bob,"party-invites",false));
        assertFalse(friends.canInviteParty(alice,bob));
    }
    @Test public void concurrentReverseRequestsAndAcceptsCreateOnlyOneFriendship() throws Exception {
        ExecutorService pool=Executors.newFixedThreadPool(2);
        try {
            Future<FriendResult> first=pool.submit(() -> friends.add(alice,bob,50));
            Future<FriendResult> second=pool.submit(() -> friends.add(bob,alice,50));
            assertTrue(first.get().succeeded() ^ second.get().succeeded());
            if (friends.requests(alice,true).isEmpty()) code(FriendResult.Code.OK,friends.accept(bob,alice,50));
            else code(FriendResult.Code.OK,friends.accept(alice,bob,50));
            assertEquals(1,friends.count(alice));
            assertEquals(1,friends.count(bob));
        } finally { pool.shutdownNow(); }
    }
    @Test public void serverTransferDoesNotCreateOfflineEventAndDisconnectDoes() throws Exception {
        code(FriendResult.Code.OK,friends.add(alice,bob,50));
        code(FriendResult.Code.OK,friends.accept(bob,alice,50));
        code(FriendResult.Code.OK,friends.setSetting(alice,"offline-notify",true));
        assertTrue(friends.notifyPresence(bob,true));
        assertTrue(presence.heartbeat(bob,bobSession,"survival"));
        assertFalse(friends.notifyPresence(bob,true));
        assertEquals("survival",friends.friends(alice,1,10).get(0).server);
        presence.sessionEnded(bob,bobSession);
        assertTrue(friends.notifyPresence(bob,false));
        assertFalse(friends.notifyPresence(bob,false));
        assertFalse(friends.friends(alice,1,10).get(0).online);
    }
    @Test public void listPagesAreStableAndCountMatches() throws Exception {
        code(FriendResult.Code.OK,friends.add(alice,bob,50));
        code(FriendResult.Code.OK,friends.accept(bob,alice,50));
        clock.addAndGet(5001);
        code(FriendResult.Code.OK,friends.add(alice,carol,50));
        code(FriendResult.Code.OK,friends.accept(carol,alice,50));
        assertEquals(2,friends.count(alice));
        assertEquals(1,friends.friends(alice,1,1).size());
        assertEquals(1,friends.friends(alice,2,1).size());
        assertNotEquals(friends.friends(alice,1,1).get(0).playerId,
            friends.friends(alice,2,1).get(0).playerId);
        assertTrue(friends.friends(alice,3,1).isEmpty());
    }
}
