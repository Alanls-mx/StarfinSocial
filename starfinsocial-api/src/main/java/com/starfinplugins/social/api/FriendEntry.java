package com.starfinplugins.social.api;

import java.util.UUID;

public final class FriendEntry {
    public final UUID playerId;
    public final String name, server;
    public final boolean online, hidden;
    public final long lastSeen, friendsSince;

    public FriendEntry(UUID playerId, String name, String server, boolean online, boolean hidden,
                       long lastSeen, long friendsSince) {
        this.playerId = playerId;
        this.name = name;
        this.server = server;
        this.online = online;
        this.hidden = hidden;
        this.lastSeen = lastSeen;
        this.friendsSince = friendsSince;
    }
}
