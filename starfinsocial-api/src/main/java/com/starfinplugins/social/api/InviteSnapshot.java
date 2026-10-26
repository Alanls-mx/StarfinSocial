package com.starfinplugins.social.api;

import java.util.UUID;

public final class InviteSnapshot {
    public final UUID id, partyId, inviterId, targetId;
    public final InviteState state;
    public final long createdAt, expiresAt;

    public InviteSnapshot(UUID id, UUID partyId, UUID inviterId, UUID targetId,
                          InviteState state, long createdAt, long expiresAt) {
        this.id = id; this.partyId = partyId; this.inviterId = inviterId;
        this.targetId = targetId; this.state = state;
        this.createdAt = createdAt; this.expiresAt = expiresAt;
    }
}
