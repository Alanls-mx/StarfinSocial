package com.starfinplugins.social.bungee;

import java.util.Set;
import java.util.function.Predicate;

final class PartyPermissions {
    private PartyPermissions() { }
    static boolean allowed(Set<String> defaults, Predicate<String> granted, String action) {
        return granted.test("starfinsocial.party.admin")
            || granted.test("starfinsocial.party." + action)
            || defaults.contains(action);
    }
}
