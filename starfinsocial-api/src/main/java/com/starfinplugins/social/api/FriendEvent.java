package com.starfinplugins.social.api;

import java.util.UUID;

/** Emitted after the MySQL mutation or proxy presence decision succeeds. */
public final class FriendEvent {
    public enum Type { REQUEST_SENT, REQUEST_ACCEPTED, REQUEST_DENIED, REQUEST_CANCELLED,
        FRIEND_REMOVED, FRIEND_ONLINE, FRIEND_OFFLINE }
    public final Type type;
    public final UUID actor, target;
    public final long occurredAt;
    public FriendEvent(Type type,UUID actor,UUID target,long occurredAt) {
        this.type=type; this.actor=actor; this.target=target; this.occurredAt=occurredAt;
    }
}
