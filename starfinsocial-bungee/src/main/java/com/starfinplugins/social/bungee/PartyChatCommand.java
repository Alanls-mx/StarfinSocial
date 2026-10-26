package com.starfinplugins.social.bungee;

import com.starfinplugins.social.api.PartyResult;
import java.util.Locale;
import net.md_5.bungee.api.CommandSender;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.plugin.Command;

final class PartyChatCommand extends Command {
    private final StarfinSocialBungee plugin;
    PartyChatCommand(StarfinSocialBungee plugin) { super("pc"); this.plugin = plugin; }

    @Override public void execute(CommandSender sender, String[] args) {
        if (!(sender instanceof ProxiedPlayer)) return;
        ProxiedPlayer player = (ProxiedPlayer) sender;
        if (!plugin.authenticated(player)) {
            player.sendMessage(plugin.messages().line("not-authenticated",null)); return;
        }
        if (!plugin.permitted(player,"chat") || !plugin.permitted(player,"use")) {
            player.sendMessage(plugin.messages().line("no-permission",null)); return;
        }
        if (args.length == 0) {
            player.sendMessage(plugin.messages().line("usage",
                StarfinSocialBungee.vars("usage","/pc <mensagem>"))); return;
        }
        String body = String.join(" ",args);
        plugin.partyService().sendChat(player.getUniqueId(),body).whenComplete((result,error) -> {
            if (error != null || result == null) {
                player.sendMessage(plugin.messages().line("unavailable",null)); return;
            }
            if (!result.succeeded()) {
                String reason = plugin.messages().raw("reason-"
                    + result.code.name().toLowerCase(Locale.ROOT).replace('_','-'),null);
                player.sendMessage(plugin.messages().line("error",
                    StarfinSocialBungee.vars("reason",reason)));
            }
        });
    }
}
