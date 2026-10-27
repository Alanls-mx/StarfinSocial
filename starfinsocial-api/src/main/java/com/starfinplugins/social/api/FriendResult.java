package com.starfinplugins.social.api;

public final class FriendResult {
    public enum Code { OK, ACCEPTED, INVALID, SELF, UNKNOWN, ALREADY_FRIENDS,
        DUPLICATE, NO_REQUEST, EXPIRED, PRIVACY, COOLDOWN, LIMIT, PENDING_LIMIT,
        NO_PERMISSION, UNAVAILABLE }
    public final Code code;
    public FriendResult(Code code) { this.code = code; }
    public boolean succeeded() { return code == Code.OK || code == Code.ACCEPTED; }
    public static FriendResult of(Code code) { return new FriendResult(code); }
}
