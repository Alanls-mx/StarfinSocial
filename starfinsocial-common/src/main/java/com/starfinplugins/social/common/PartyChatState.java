package com.starfinplugins.social.common;

import com.starfinplugins.social.api.PartyMember;
import com.starfinplugins.social.api.PartySnapshot;
import com.starfinplugins.social.api.PartyState;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;

/** Proxy-local membership view for instant chat; committed revisions replace older snapshots. */
public final class PartyChatState {
    private final Map<UUID, PartySnapshot> parties = new HashMap<UUID, PartySnapshot>();
    private final Map<UUID, UUID> membership = new HashMap<UUID, UUID>();
    private final ConcurrentMap<UUID, Long> lastChat = new ConcurrentHashMap<UUID, Long>();

    public synchronized void apply(PartySnapshot snapshot) {
        if (snapshot == null) return;
        PartySnapshot old = parties.get(snapshot.id);
        if (old != null && old.revision > snapshot.revision) return;
        Set<UUID> current = new HashSet<UUID>();
        if (snapshot.state == PartyState.ACTIVE)
            for (PartyMember member : snapshot.members) current.add(member.playerId);
        membership.entrySet().removeIf(entry -> entry.getValue().equals(snapshot.id)
            && !current.contains(entry.getKey()));
        parties.put(snapshot.id, snapshot);
        if (snapshot.state == PartyState.ACTIVE) {
            for (UUID player : current) membership.put(player, snapshot.id);
        }
    }

    public synchronized void forget(UUID player) { membership.remove(player); }

    public synchronized PartySnapshot forPlayer(UUID player) {
        UUID id = membership.get(player);
        PartySnapshot party = id == null ? null : parties.get(id);
        return party != null && party.state == PartyState.ACTIVE && party.contains(player)
            ? party : null;
    }

    public synchronized boolean memberOf(UUID player, UUID party) {
        return party.equals(membership.get(player));
    }

    public boolean allowChat(UUID player, long nowNanos, long cooldownMillis) {
        long duration = TimeUnit.MILLISECONDS.toNanos(cooldownMillis);
        if (duration == 0) return true;
        for (;;) {
            Long previous = lastChat.get(player);
            if (previous != null && nowNanos - previous < duration) return false;
            if (previous == null) {
                if (lastChat.putIfAbsent(player, nowNanos) == null) return true;
            } else if (lastChat.replace(player, previous, nowNanos)) return true;
        }
    }

    public void clearCooldown(UUID player) { lastChat.remove(player); }
}
