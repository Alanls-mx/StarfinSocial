package com.starfinplugins.social.api;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Cross-server reads always consult MySQL asynchronously. */
public interface PartyDirectory {
    CompletableFuture<PartySnapshot> getParty(UUID playerId);
    CompletableFuture<PartySnapshot> getPartyById(UUID partyId);
    default CompletableFuture<Boolean> isInParty(UUID playerId) {
        return getParty(playerId).thenApply(party -> party != null);
    }
}
