package com.starfinplugins.social.api;

import java.util.UUID;

public final class FriendRequest {
    public enum State { PENDING, ACCEPTED, DENIED, CANCELLED, EXPIRED }
    public final UUID id, sender, target;
    public final String senderName, targetName;
    public final State state;
    public final long createdAt, expiresAt;

    public FriendRequest(UUID id, UUID sender, UUID target, String senderName,
                         String targetName, State state, long createdAt, long expiresAt) {
        this.id = id;
        this.sender = sender;
        this.target = target;
        this.senderName = senderName;
        this.targetName = targetName;
        this.state = state;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
    }
}
