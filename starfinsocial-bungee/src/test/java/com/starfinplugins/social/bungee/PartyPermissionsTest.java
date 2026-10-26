package com.starfinplugins.social.bungee;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import org.junit.Test;
import static org.junit.Assert.*;

public final class PartyPermissionsTest {
    @Test public void defaultActionsAndExplicitNodes() {
        Set<String> defaults = new HashSet<String>();
        defaults.add("use");
        assertTrue(PartyPermissions.allowed(defaults,node -> false,"use"));
        assertFalse(PartyPermissions.allowed(defaults,node -> false,"kick"));
        assertTrue(PartyPermissions.allowed(defaults,
            node -> node.equals("starfinsocial.party.kick"),"kick"));
        assertTrue(PartyPermissions.allowed(Collections.emptySet(),
            node -> node.equals("starfinsocial.party.admin"),"settings"));
    }
}
