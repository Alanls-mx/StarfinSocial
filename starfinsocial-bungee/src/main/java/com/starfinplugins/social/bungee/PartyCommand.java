package com.starfinplugins.social.bungee;

import com.starfinplugins.social.api.InviteSnapshot;
import com.starfinplugins.social.api.PartyMember;
import com.starfinplugins.social.api.PartyPrivacy;
import com.starfinplugins.social.api.PartyResult;
import com.starfinplugins.social.api.PartySnapshot;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import net.md_5.bungee.api.CommandSender;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.plugin.Command;

final class PartyCommand extends Command {
    private final StarfinSocialBungee plugin;

    PartyCommand(StarfinSocialBungee plugin, String label) {
        super(label);
        this.plugin = plugin;
    }

    @Override public void execute(CommandSender sender, String[] args) {
        if (!(sender instanceof ProxiedPlayer)) { sender.sendMessage(plugin.messages().line("no-permission", null)); return; }
        ProxiedPlayer player = (ProxiedPlayer) sender;
        if (!plugin.authenticated(player)) { send(player,"not-authenticated"); return; }
        if (!allowed(player, "use")) return;
        if (args.length == 0 || "help".equalsIgnoreCase(args[0])) {
            send(player, "help"); return;
        }
        String action = args[0].toLowerCase(Locale.ROOT);
        UUID id = player.getUniqueId();
        switch (action) {
            case "create":
                if (!allowed(player, "create")) return;
                finish(player, plugin.partyService().createParty(id, limit(player)), "created"); return;
            case "invite":
                target(player, args, "invite", t -> plugin.partyService().invitePlayer(id,t), "invite-sent"); return;
            case "accept":
                target(player, args, "use", t -> plugin.partyService().acceptInvite(id,t), "accepted"); return;
            case "deny":
                target(player, args, "use", t -> plugin.partyService().denyInvite(id,t), "denied"); return;
            case "join":
                target(player, args, "use", t -> plugin.partyService().joinOpen(id,t), "accepted"); return;
            case "leave":
                finish(player, plugin.partyService().leaveParty(id), "left"); return;
            case "kick":
                target(player, args, "kick", t -> plugin.partyService().kickMember(id,t), "member-kicked"); return;
            case "promote":
                target(player, args, "promote", t -> plugin.partyService().promoteMember(id,t), "promoted"); return;
            case "transfer":
                target(player, args, "transfer", t -> plugin.partyService().transferLeadership(id,t), "leader-changed"); return;
            case "disband":
                if (!allowed(player, "disband")) return;
                finish(player, plugin.partyService().disbandParty(id), "disbanded"); return;
            case "list": case "info":
                plugin.partyService().getParty(id).whenComplete((party,error) -> {
                    if (error != null) { send(player,"unavailable"); return; }
                    if (party == null) { fail(player,PartyResult.Code.NOT_IN_PARTY); return; }
                    info(player, party, "list".equals(action));
                }); return;
            case "chat":
                if (!allowed(player, "chat")) return;
                if (args.length > 1) {
                    String body = String.join(" ",java.util.Arrays.copyOfRange(args,1,args.length));
                    finish(player,plugin.partyService().sendChat(id,body),"chat-sent");
                    return;
                }
                plugin.partyService().getParty(id).whenComplete((party,error) -> {
                    if (error != null) { send(player,"unavailable"); return; }
                    if (party == null) { fail(player,PartyResult.Code.NOT_IN_PARTY); return; }
                    if (plugin.chatMode().remove(id)) send(player,"chat-mode-off");
                    else { plugin.chatMode().add(id); send(player,"chat-mode-on"); }
                }); return;
            case "settings":
                if (!allowed(player, "settings")) return;
                settings(player, args); return;
            default: send(player,"help");
        }
    }

    private void settings(ProxiedPlayer player, String[] args) {
        UUID id = player.getUniqueId();
        if (args.length == 1) {
            plugin.partyService().getParty(id).whenComplete((party,error) -> {
                if (error != null) { send(player,"unavailable"); return; }
                if (party == null) { fail(player,PartyResult.Code.NOT_IN_PARTY); return; }
                player.sendMessage(plugin.messages().line("settings", StarfinSocialBungee.vars(
                    "privacy",party.privacy.name(),"chat",Boolean.toString(party.chatEnabled),
                    "max_members",Integer.toString(party.maxMembers))));
            }); return;
        }
        if (args.length != 3) { usage(player,"/party settings privacy|chat|size <valor>"); return; }
        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "privacy":
                try {
                    PartyPrivacy value = PartyPrivacy.valueOf(args[2].toUpperCase(Locale.ROOT));
                    finish(player, plugin.partyService().setPrivacy(id,value), "settings-changed");
                } catch (IllegalArgumentException error) { usage(player,"/party settings privacy open|invite_only|closed"); }
                break;
            case "chat":
                if (!"on".equalsIgnoreCase(args[2]) && !"off".equalsIgnoreCase(args[2])) {
                    usage(player,"/party settings chat on|off"); return;
                }
                finish(player, plugin.partyService().setChatEnabled(id,"on".equalsIgnoreCase(args[2])),
                    "settings-changed"); break;
            case "size":
                try {
                    int n = Integer.parseInt(args[2]);
                    if (n > limit(player)) { fail(player, PartyResult.Code.NO_PERMISSION); return; }
                    finish(player, plugin.partyService().setMaxMembers(id,n), "settings-changed");
                } catch (NumberFormatException error) { usage(player,"/party settings size <2-32>"); }
                break;
            default: usage(player,"/party settings privacy|chat|size <valor>");
        }
    }

    private <T> void target(ProxiedPlayer player, String[] args, String permission,
                            Function<UUID, CompletableFuture<PartyResult<T>>> operation,
                            String success) {
        if (!allowed(player,permission)) return;
        if (args.length != 2) { usage(player,"/party " + args[0] + " <jogador>"); return; }
        if (!args[1].matches("[A-Za-z0-9_]{1,16}")) { fail(player,PartyResult.Code.INVALID); return; }
        if (plugin.database() == null) { send(player,"unavailable"); return; }
        plugin.async(() -> plugin.database().resolveName(args[1]))
            .whenComplete((target,error) -> {
                if (error != null) { send(player,"unavailable"); return; }
                if (target == null) { send(player,"unknown-player"); return; }
                finish(player, operation.apply(target), success, args[1]);
            });
    }

    static int limit(ProxiedPlayer player, StarfinSocialBungee plugin) {
        int effective = plugin.settings().rules.defaultMaxMembers;
        for (int tier : new int[] {4,8,16,32})
            if (player.hasPermission("starfinsocial.party.size." + tier))
                effective = Math.max(effective, tier);
        return plugin.settings().rules.effectiveLimit(effective);
    }
    private int limit(ProxiedPlayer player) { return limit(player,plugin); }

    private boolean allowed(ProxiedPlayer player, String node) {
        if (plugin.permitted(player,node)) return true;
        send(player,"no-permission"); return false;
    }

    private <T> void finish(ProxiedPlayer player, CompletableFuture<PartyResult<T>> future,
                            String success) { finish(player,future,success,player.getName()); }
    private <T> void finish(ProxiedPlayer player, CompletableFuture<PartyResult<T>> future,
                            String success, String targetName) {
        future.whenComplete((result,error) -> {
            if (error != null || result == null) { send(player,"unavailable"); return; }
            if (!result.succeeded()) { fail(player,result.code); return; }
            if (!"created".equals(success) && !"invite-sent".equals(success)
                    && !"denied".equals(success)) return;
            int max = result.value instanceof PartySnapshot ? ((PartySnapshot) result.value).maxMembers
                : plugin.settings().rules.defaultMaxMembers;
            player.sendMessage(plugin.messages().line(success, StarfinSocialBungee.vars(
                "player",targetName,"max_members",Integer.toString(max),"setting","Party")));
        });
    }

    private void info(ProxiedPlayer player, PartySnapshot party, boolean list) {
        String leader = party.leaderId.toString();
        PartyMember lead = party.member(party.leaderId);
        if (lead != null) leader = lead.name;
        player.sendMessage(plugin.messages().line("info", StarfinSocialBungee.vars(
            "party",party.id.toString().substring(0,8),"leader",leader,
            "members",Integer.toString(party.size()),"max_members",Integer.toString(party.maxMembers),
            "privacy",party.privacy.name())));
        if (list) for (PartyMember member : party.members)
            player.sendMessage(plugin.messages().line("member-line", StarfinSocialBungee.vars(
                "player",member.name,"role",member.role.name(),"server",member.server)));
    }

    private void fail(ProxiedPlayer player, PartyResult.Code code) {
        String reason = plugin.messages().raw("reason-"
            + code.name().toLowerCase(Locale.ROOT).replace('_','-'), null);
        player.sendMessage(plugin.messages().line("error",StarfinSocialBungee.vars("reason",reason)));
    }
    private void usage(ProxiedPlayer player, String text) {
        player.sendMessage(plugin.messages().line("usage",StarfinSocialBungee.vars("usage",text)));
    }
    private void send(ProxiedPlayer player, String key) {
        player.sendMessage(plugin.messages().line(key,null));
    }
}
