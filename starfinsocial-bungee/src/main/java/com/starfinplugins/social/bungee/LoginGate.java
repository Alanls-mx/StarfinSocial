package com.starfinplugins.social.bungee;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.plugin.Plugin;

/** Optional StarfinLogin bridge; failures deny access instead of trusting a pending offline UUID. */
final class LoginGate {
    private final Object registry;
    private final Method current, isCurrent;

    LoginGate(Plugin login) throws ReflectiveOperationException {
        Field sessions = login.getClass().getDeclaredField("sessions");
        sessions.setAccessible(true);
        registry = sessions.get(login);
        current = registry.getClass().getMethod("current", java.util.UUID.class);
        Method match = null;
        for (Method method : registry.getClass().getMethods())
            if (method.getName().equals("isCurrent") && method.getParameterTypes().length == 2)
                match = method;
        if (match == null) throw new NoSuchMethodException("isCurrent");
        isCurrent = match;
    }

    boolean authenticated(ProxiedPlayer player) {
        try {
            Object session = current.invoke(registry, player.getUniqueId());
            if (session == null || !Boolean.TRUE.equals(isCurrent.invoke(registry, session, player)))
                return false;
            Object state = session.getClass().getMethod("getState").invoke(session);
            return "AUTHENTICATED".equals(String.valueOf(state));
        } catch (ReflectiveOperationException | RuntimeException error) { return false; }
    }
}
