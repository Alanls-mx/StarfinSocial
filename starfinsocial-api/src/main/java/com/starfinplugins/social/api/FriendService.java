package com.starfinplugins.social.api;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Network-authoritative operations. Presence is filtered for the viewer. */
public interface FriendService {
    CompletableFuture<FriendResult> add(UUID actor, UUID target, int actorLimit);
    CompletableFuture<FriendResult> accept(UUID actor, UUID sender, int actorLimit);
    CompletableFuture<FriendResult> deny(UUID actor, UUID sender);
    CompletableFuture<FriendResult> cancel(UUID actor, UUID target);
    CompletableFuture<FriendResult> remove(UUID actor, UUID target);
    CompletableFuture<Boolean> isFriend(UUID actor, UUID target);
    CompletableFuture<List<FriendEntry>> getFriends(UUID actor, int page, int size);
    CompletableFuture<Integer> countFriends(UUID actor);
    CompletableFuture<List<FriendEntry>> getOnlineFriends(UUID actor);
    CompletableFuture<List<FriendRequest>> requests(UUID actor, boolean received);
    CompletableFuture<FriendSettings> settings(UUID actor);
    CompletableFuture<FriendResult> setSetting(UUID actor, String key, boolean value);
    CompletableFuture<Boolean> canSendFriendRequest(UUID actor, UUID target);
    CompletableFuture<Boolean> canInviteToParty(UUID actor, UUID target);
    AutoCloseable subscribe(FriendEventListener listener);
}
