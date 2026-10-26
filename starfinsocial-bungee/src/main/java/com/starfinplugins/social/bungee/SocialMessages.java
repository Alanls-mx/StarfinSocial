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
    private final Configuration selected, defaultMessages;

    SocialMessages(File folder, String locale) throws IOException {
        File pt = new File(folder, "messages_pt_BR.yml");
        SocialConfig.copyDefault(pt, "messages_pt_BR.yml");
        defaultMessages = ConfigurationProvider.getProvider(YamlConfiguration.class).load(pt);
        File wanted = new File(folder, "messages_" + locale + ".yml");
        if ("en_US".equals(locale)) SocialConfig.copyDefault(wanted, "messages_en_US.yml");
        selected = wanted.exists() ? ConfigurationProvider.getProvider(YamlConfiguration.class).load(wanted)
            : defaultMessages;
    }

    String raw(String key, Map<String, String> vars) {
        String value = selected.getString(key, defaultMessages.getString(key, key));
        if (vars != null) for (Map.Entry<String, String> entry : vars.entrySet())
            value = value.replace("{" + entry.getKey() + "}", entry.getValue() == null ? "" : entry.getValue());
        return ChatColor.translateAlternateColorCodes('&', value);
    }
    TextComponent line(String key, Map<String, String> vars) {
        return new TextComponent(raw("prefix", null) + raw(key, vars));
    }
}
