package com.starfinplugins.social.api;

public final class PartyResult<T> {
    public enum Code {
        OK, DISABLED, UNAVAILABLE, INVALID, NOT_ONLINE, NOT_FOUND, ALREADY_IN_PARTY,
        NOT_IN_PARTY, NOT_LEADER, NOT_AUTHORIZED, SELF_TARGET, FULL, DUPLICATE_INVITE,
        INVITE_EXPIRED, NO_INVITE, COOLDOWN, PRIVACY, CONFLICT, NO_PERMISSION
    }
    public final Code code;
    public final T value;

    private PartyResult(Code code, T value) { this.code = code; this.value = value; }
    public static <T> PartyResult<T> ok(T value) { return new PartyResult<T>(Code.OK, value); }
    public static <T> PartyResult<T> fail(Code code) {
        if (code == Code.OK) throw new IllegalArgumentException("Use ok");
        return new PartyResult<T>(code, null);
    }
    public boolean succeeded() { return code == Code.OK; }
}
