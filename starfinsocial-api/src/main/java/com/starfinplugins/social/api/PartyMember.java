package com.starfinplugins.social.api;

import java.util.Objects;
import java.util.UUID;

public final class PartyMember {
    public final UUID playerId;
    public final String name, server;
    public final PartyRole role;
    public final long joinedAt;
    public final boolean online;

    public PartyMember(UUID playerId, String name, String server, PartyRole role,
                       long joinedAt, boolean online) {
        this.playerId = Objects.requireNonNull(playerId, "playerId");
        this.name = Objects.requireNonNull(name, "name");
        this.server = server == null ? "" : server;
        this.role = Objects.requireNonNull(role, "role");
        this.joinedAt = joinedAt;
        this.online = online;
    }
}
