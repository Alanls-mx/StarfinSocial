package com.starfinplugins.social.common;

import com.starfinplugins.social.api.*;
import java.util.Arrays;
import java.util.Collections;
import java.util.UUID;
import org.junit.Test;
import static org.junit.Assert.*;

public final class PartyChatStateTest {
    private final UUID partyId = UUID.randomUUID();
    private final UUID leader = UUID.randomUUID();
    private final UUID member = UUID.randomUUID();

    private PartySnapshot snapshot(long revision, PartyState state, boolean includeMember) {
        PartyMember first = new PartyMember(leader,"Leader","lobby",PartyRole.LEADER,1,true);
        PartyMember second = new PartyMember(member,"Member","bedwars",PartyRole.MEMBER,2,true);
        return new PartySnapshot(partyId,leader,state,PartyPrivacy.INVITE_ONLY,4,true,
            1,revision,0,includeMember ? Arrays.asList(first,second)
                : Collections.singletonList(first));
    }

    @Test public void revisionAndMembershipPreventStaleChat() {
        PartyChatState state = new PartyChatState();
        state.apply(snapshot(1,PartyState.ACTIVE,true));
        assertEquals(partyId,state.forPlayer(member).id);
        state.apply(snapshot(2,PartyState.ACTIVE,false));
        assertNull(state.forPlayer(member));
        assertFalse(state.memberOf(member,partyId));
        state.apply(snapshot(1,PartyState.ACTIVE,true));
        assertNull(state.forPlayer(member));
        state.apply(snapshot(3,PartyState.DISBANDED,false));
        state.apply(snapshot(2,PartyState.ACTIVE,true));
        assertNull(state.forPlayer(leader));
    }

    @Test public void cooldownIsAppliedBeforeDelivery() {
        PartyChatState state = new PartyChatState();
        assertTrue(state.allowChat(leader,1_000_000_000L,1000));
        assertFalse(state.allowChat(leader,1_500_000_000L,1000));
        assertTrue(state.allowChat(leader,2_000_000_000L,1000));
    }
}
