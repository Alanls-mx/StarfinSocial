package com.starfinplugins.social.common;

import com.starfinplugins.social.api.PartyPrivacy;

/** Immutable policy shared by proxy operations and tests. */
public final class PartyRules {
    public final boolean enabled, crossServer, chatEnabled, transferOnLeaderTimeout;
    public final int defaultMaxMembers, absoluteMaxMembers;
    public final long inviteExpirationMs, inviteCooldownMs, chatCooldownMs;
    public final long leaderGraceMs, presenceTimeoutMs;
    public final PartyPrivacy defaultPrivacy;

    public PartyRules(boolean enabled, boolean crossServer, boolean chatEnabled,
                      boolean transferOnLeaderTimeout, int defaultMaxMembers,
                      int absoluteMaxMembers, long inviteExpirationMs, long inviteCooldownMs,
                      long chatCooldownMs, long leaderGraceMs, long presenceTimeoutMs,
                      PartyPrivacy defaultPrivacy) {
        if (defaultMaxMembers < 2 || absoluteMaxMembers < defaultMaxMembers
                || absoluteMaxMembers > 64 || inviteExpirationMs < 1000
                || inviteCooldownMs < 0 || chatCooldownMs < 0 || leaderGraceMs < 1000
                || presenceTimeoutMs < 3000 || defaultPrivacy == null)
            throw new IllegalArgumentException("Politica de Party invalida");
        this.enabled = enabled;
        this.crossServer = crossServer;
        this.chatEnabled = chatEnabled;
        this.transferOnLeaderTimeout = transferOnLeaderTimeout;
        this.defaultMaxMembers = defaultMaxMembers;
        this.absoluteMaxMembers = absoluteMaxMembers;
        this.inviteExpirationMs = inviteExpirationMs;
        this.inviteCooldownMs = inviteCooldownMs;
        this.chatCooldownMs = chatCooldownMs;
        this.leaderGraceMs = leaderGraceMs;
        this.presenceTimeoutMs = presenceTimeoutMs;
        this.defaultPrivacy = defaultPrivacy;
    }

    public static PartyRules defaults() {
        return new PartyRules(true, true, true, true, 16, 32,
            60000, 3000, 1000, 120000, 30000, PartyPrivacy.INVITE_ONLY);
    }
    public int effectiveLimit(int permitted) {
        return Math.min(absoluteMaxMembers, Math.max(defaultMaxMembers, permitted));
    }
    public static String chat(String raw) {
        if (raw == null) throw new IllegalArgumentException("Mensagem vazia");
        String value = raw.replaceAll("(?i)&[0-9a-fk-or]", "")
            .replaceAll("[\\p{Cntrl}§&]", "").trim();
        if (value.isEmpty() || value.length() > 160)
            throw new IllegalArgumentException("Mensagem deve ter 1 a 160 caracteres");
        return value;
    }
}
