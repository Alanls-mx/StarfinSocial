package com.starfinplugins.social.bungee;

import com.starfinplugins.social.api.PartyPrivacy;
import com.starfinplugins.social.common.PartyRules;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import java.util.Set;
import java.util.HashSet;
import java.util.Collections;
import net.md_5.bungee.config.Configuration;
import net.md_5.bungee.config.ConfigurationProvider;
import net.md_5.bungee.config.YamlConfiguration;

final class SocialConfig {
    final String proxyId, locale, host, database, user, password, sslMode;
    final int port;
    final boolean aliasP, aliasPc;
    final Set<String> defaultActions;
    final PartyRules rules;

    private SocialConfig(Configuration config) {
        proxyId = config.getString("proxy-id", "proxy-1").trim();
        locale = config.getString("locale", "pt_BR").trim();
        host = config.getString("mysql.host", "localhost").trim();
        port = config.getInt("mysql.port", 3306);
        database = config.getString("mysql.database", "starfin").trim();
        user = config.getString("mysql.username", "starfin").trim();
        password = config.getString("mysql.password", "");
        sslMode = config.getString("mysql.ssl-mode", "PREFERRED").trim().toUpperCase(Locale.ROOT);
        aliasP = config.getBoolean("party.aliases.p", true);
        aliasPc = config.getBoolean("party.aliases.pc", true);
        Set<String> granted = new HashSet<String>();
        for (String action : config.getStringList("party.permissions.default-actions"))
            if (action.matches("[a-z]{2,16}")) granted.add(action);
        defaultActions = Collections.unmodifiableSet(granted);
        rules = new PartyRules(config.getBoolean("party.enabled", true),
            config.getBoolean("party.network.cross-server", true),
            config.getBoolean("party.chat.enabled", true),
            config.getBoolean("party.leader-disconnect.transfer-on-timeout", true),
            config.getInt("party.default-max-members", 16),
            config.getInt("party.absolute-max-members", 32),
            config.getLong("party.invite-expiration-seconds", 60) * 1000,
            config.getLong("party.invite-cooldown-seconds", 3) * 1000,
            config.getLong("party.chat.cooldown-seconds", 1) * 1000,
            config.getLong("party.leader-disconnect.grace-period-seconds", 120) * 1000,
            config.getLong("party.network.presence-timeout-seconds", 30) * 1000,
            PartyPrivacy.valueOf(config.getString("party.privacy.default", "INVITE_ONLY")
                .toUpperCase(Locale.ROOT)));
        if (!proxyId.matches("[A-Za-z0-9_-]{1,32}") || !locale.matches("[A-Za-z_]{2,12}")
                || !host.matches("[A-Za-z0-9.:-]+") || !database.matches("[A-Za-z0-9_]+")
                || user.isEmpty() || port < 1 || port > 65535
                || !(sslMode.equals("DISABLED") || sslMode.equals("PREFERRED")
                    || sslMode.equals("REQUIRED") || sslMode.equals("VERIFY_CA")
                    || sslMode.equals("VERIFY_IDENTITY")))
            throw new IllegalArgumentException("Configuração StarfinSocial inválida");
    }

    static SocialConfig load(File folder) throws IOException {
        if (!folder.exists() && !folder.mkdirs()) throw new IOException("Pasta de configuração indisponível");
        File file = new File(folder, "config.yml");
        copyDefault(file, "config.yml");
        return new SocialConfig(ConfigurationProvider.getProvider(YamlConfiguration.class).load(file));
    }

    static void copyDefault(File file, String resource) throws IOException {
        if (file.exists()) return;
        try (InputStream in = SocialConfig.class.getClassLoader().getResourceAsStream(resource);
             FileOutputStream out = new FileOutputStream(file)) {
            if (in == null) throw new IOException("Recurso ausente: " + resource);
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
        }
    }
}
