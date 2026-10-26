package com.starfinplugins.social.api;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public final class PartySnapshot {
    public final UUID id, leaderId;
    public final PartyState state;
    public final PartyPrivacy privacy;
    public final int maxMembers;
    public final boolean chatEnabled;
    public final long createdAt, revision, leaderMissingSince;
    public final List<PartyMember> members;

    public PartySnapshot(UUID id, UUID leaderId, PartyState state, PartyPrivacy privacy,
                         int maxMembers, boolean chatEnabled, long createdAt, long revision,
                         long leaderMissingSince, List<PartyMember> members) {
        this.id = Objects.requireNonNull(id, "id");
        this.leaderId = Objects.requireNonNull(leaderId, "leaderId");
        this.state = Objects.requireNonNull(state, "state");
        this.privacy = Objects.requireNonNull(privacy, "privacy");
        this.maxMembers = maxMembers;
        this.chatEnabled = chatEnabled;
        this.createdAt = createdAt;
        this.revision = revision;
        this.leaderMissingSince = leaderMissingSince;
        this.members = Collections.unmodifiableList(new ArrayList<PartyMember>(members));
    }

    public PartyMember member(UUID id) {
        for (PartyMember member : members) if (member.playerId.equals(id)) return member;
        return null;
    }
    public boolean contains(UUID id) { return member(id) != null; }
    public int size() { return members.size(); }
}
