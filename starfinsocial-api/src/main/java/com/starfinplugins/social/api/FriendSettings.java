package com.starfinplugins.social.api;

/** Persisted, immutable privacy choices. */
public final class FriendSettings {
    public final boolean allowRequests, notifyOnline, notifyOffline;
    public final boolean showOnline, showServer, allowPartyInvites;

    public FriendSettings(boolean allowRequests, boolean notifyOnline, boolean notifyOffline,
                          boolean showOnline, boolean showServer, boolean allowPartyInvites) {
        this.allowRequests = allowRequests;
        this.notifyOnline = notifyOnline;
        this.notifyOffline = notifyOffline;
        this.showOnline = showOnline;
        this.showServer = showServer;
        this.allowPartyInvites = allowPartyInvites;
    }

    public boolean value(String key) {
        switch (key) {
            case "requests": return allowRequests;
            case "online-notify": return notifyOnline;
            case "offline-notify": return notifyOffline;
            case "show-online": return showOnline;
            case "show-server": return showServer;
            case "party-invites": return allowPartyInvites;
            default: throw new IllegalArgumentException("Unknown friend setting");
        }
    }

    public FriendSettings with(String key, boolean value) {
        switch (key) {
            case "requests": return new FriendSettings(value,notifyOnline,notifyOffline,showOnline,showServer,allowPartyInvites);
            case "online-notify": return new FriendSettings(allowRequests,value,notifyOffline,showOnline,showServer,allowPartyInvites);
            case "offline-notify": return new FriendSettings(allowRequests,notifyOnline,value,showOnline,showServer,allowPartyInvites);
            case "show-online": return new FriendSettings(allowRequests,notifyOnline,notifyOffline,value,showServer,allowPartyInvites);
            case "show-server": return new FriendSettings(allowRequests,notifyOnline,notifyOffline,showOnline,value,allowPartyInvites);
            case "party-invites": return new FriendSettings(allowRequests,notifyOnline,notifyOffline,showOnline,showServer,value);
            default: throw new IllegalArgumentException("Unknown friend setting");
        }
    }
}
