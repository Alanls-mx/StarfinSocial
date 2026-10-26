package com.starfinplugins.social.api;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/** Capacity preflight only; phase 4.4 will reserve and execute the transfer. */
public final class PartyTransferPlan {
    public final UUID partyId;
    public final long revision;
    public final String destination;
    public final List<UUID> onlineMembers;
    public final int capacity;

    public PartyTransferPlan(UUID partyId, long revision, String destination,
                             List<UUID> onlineMembers, int capacity) {
        this.partyId = partyId; this.revision = revision; this.destination = destination;
        this.onlineMembers = Collections.unmodifiableList(new ArrayList<UUID>(onlineMembers));
        this.capacity = capacity;
    }
    public boolean fits() { return onlineMembers.size() <= capacity; }
}
