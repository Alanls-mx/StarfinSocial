package com.starfinplugins.social.api;

import java.util.UUID;

/** Delivered only after the MySQL transaction commits. */
public final class PartyEvent {
    public enum Type { CREATE, DISBAND, INVITE, JOIN, LEAVE, KICK, PROMOTE,
        LEADER_CHANGE, SETTINGS_CHANGE, CHAT }
    public final Type type;
    public final UUID partyId, actorId, subjectId;
    public final long revision, occurredAt;

    public PartyEvent(Type type, UUID partyId, UUID actorId, UUID subjectId,
                      long revision, long occurredAt) {
        this.type = type; this.partyId = partyId; this.actorId = actorId;
        this.subjectId = subjectId; this.revision = revision; this.occurredAt = occurredAt;
    }
}
