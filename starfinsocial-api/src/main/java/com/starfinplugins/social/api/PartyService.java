package com.starfinplugins.social.api;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Proxy-authoritative mutations. Caller IDs must come from authenticated sessions. */
public interface PartyService extends PartyDirectory {
    CompletableFuture<PartyResult<PartySnapshot>> createParty(UUID leaderId, int maxMembers);
    CompletableFuture<PartyResult<InviteSnapshot>> invitePlayer(UUID actorId, UUID targetId);
    CompletableFuture<PartyResult<PartySnapshot>> acceptInvite(UUID targetId, UUID inviterId);
    CompletableFuture<PartyResult<InviteSnapshot>> denyInvite(UUID targetId, UUID inviterId);
    CompletableFuture<PartyResult<PartySnapshot>> joinOpen(UUID playerId, UUID leaderId);
    CompletableFuture<PartyResult<PartySnapshot>> leaveParty(UUID playerId);
    CompletableFuture<PartyResult<PartySnapshot>> kickMember(UUID actorId, UUID targetId);
    CompletableFuture<PartyResult<PartySnapshot>> promoteMember(UUID actorId, UUID targetId);
    CompletableFuture<PartyResult<PartySnapshot>> transferLeadership(UUID actorId, UUID targetId);
    CompletableFuture<PartyResult<PartySnapshot>> disbandParty(UUID actorId);
    CompletableFuture<PartyResult<PartySnapshot>> setPrivacy(UUID actorId, PartyPrivacy privacy);
    CompletableFuture<PartyResult<PartySnapshot>> setChatEnabled(UUID actorId, boolean enabled);
    CompletableFuture<PartyResult<PartySnapshot>> setMaxMembers(UUID actorId, int limit);
    CompletableFuture<PartyResult<Void>> sendChat(UUID senderId, String message);
    CompletableFuture<PartyResult<PartyTransferPlan>> planTransfer(UUID actorId,
                                                                    String destination, int capacity);
    AutoCloseable subscribe(PartyEventListener listener);
}
