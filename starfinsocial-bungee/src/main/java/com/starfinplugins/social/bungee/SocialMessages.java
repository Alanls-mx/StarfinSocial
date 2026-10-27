package com.starfinplugins.social.bungee;

import java.io.File;
import java.io.IOException;
import java.util.Map;
import net.md_5.bungee.api.ChatColor;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.config.Configuration;
import net.md_5.bungee.config.ConfigurationProvider;
import net.md_5.bungee.config.YamlConfiguration;

final class SocialMessages {
    private final Configuration selected, bundled;

    SocialMessages(File folder, String locale) throws IOException {
        File pt = new File(folder, "messages_pt_BR.yml");
        SocialConfig.copyDefault(pt, "messages_pt_BR.yml");
        String resource = "en_US".equals(locale) ? "messages_en_US.yml" : "messages_pt_BR.yml";
        try (java.io.InputStream stream=SocialMessages.class.getClassLoader().getResourceAsStream(resource)) {
            if (stream==null) throw new IOException("Missing bundled messages: " + resource);
            bundled=ConfigurationProvider.getProvider(YamlConfiguration.class).load(stream);
        }
        File wanted = new File(folder, "messages_" + locale + ".yml");
        if ("en_US".equals(locale)) SocialConfig.copyDefault(wanted, "messages_en_US.yml");
        selected = wanted.exists() ? ConfigurationProvider.getProvider(YamlConfiguration.class).load(wanted)
            : ConfigurationProvider.getProvider(YamlConfiguration.class).load(pt);
    }

    String raw(String key, Map<String, String> vars) {
        String value = selected.getString(key, bundled.getString(key, key));
        if (vars != null) for (Map.Entry<String, String> entry : vars.entrySet())
            value = value.replace("{" + entry.getKey() + "}", entry.getValue() == null ? "" : entry.getValue());
        return ChatColor.translateAlternateColorCodes('&', value);
    }
    TextComponent line(String key, Map<String, String> vars) {
        return new TextComponent(raw(key.startsWith("friend-") ? "friend-prefix" : "prefix", null)
            + raw(key, vars));
    }
}
