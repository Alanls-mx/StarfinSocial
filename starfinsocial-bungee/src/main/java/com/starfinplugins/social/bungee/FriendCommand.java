package com.starfinplugins.social.bungee;

import com.starfinplugins.social.api.FriendEntry;
import com.starfinplugins.social.api.FriendRequest;
import com.starfinplugins.social.api.FriendResult;
import com.starfinplugins.social.api.FriendSettings;
import java.util.List;
import java.util.Locale;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import net.md_5.bungee.api.CommandSender;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.plugin.Command;
import net.md_5.bungee.api.plugin.TabExecutor;

/** Proxy command: works from every backend without blocking a Minecraft tick. */
final class FriendCommand extends Command implements TabExecutor {
    private final StarfinSocialBungee plugin;
    FriendCommand(StarfinSocialBungee plugin, String label) { super(label); this.plugin=plugin; }

    @Override public void execute(CommandSender sender, String[] args) {
        if (!(sender instanceof ProxiedPlayer)) { sender.sendMessage(plugin.messages().line("friend-no-permission",null)); return; }
        ProxiedPlayer player=(ProxiedPlayer)sender;
        if (!plugin.authenticated(player)) { send(player,"friend-not-authenticated"); return; }
        if (!allowed(player,"use")) return;
        if (args.length==0 && "friends".equalsIgnoreCase(getName())) {
            plugin.openFriendMenu(player); return;
        }
        String action=args.length==0 ? "help" : args[0].toLowerCase(Locale.ROOT);
        UUID actor=player.getUniqueId();
        switch (action) {
            case "help": send(player,"friend-help"); break;
            case "add": target(player,args,"add",t -> plugin.friendService().add(actor,t,limit(player)),"friend-sent"); break;
            case "accept": target(player,args,"accept",t -> plugin.friendService().accept(actor,t,limit(player)),"friend-accepted"); break;
            case "deny": target(player,args,"deny",t -> plugin.friendService().deny(actor,t),"friend-denied"); break;
            case "cancel": target(player,args,"add",t -> plugin.friendService().cancel(actor,t),"friend-cancelled"); break;
            case "remove": target(player,args,"remove",t -> plugin.friendService().remove(actor,t),"friend-removed"); break;
            case "list": if (allowed(player,"list")) list(player,args); break;
            case "requests": if (allowed(player,"requests")) requests(player,true); break;
            case "pending": if (allowed(player,"requests")) requests(player,false); break;
            case "settings": if (allowed(player,"settings")) settings(player,args); break;
            case "toggle": if (allowed(player,"settings")) toggle(player); break;
            case "status": if (allowed(player,"list")) status(player,args); break;
            default: send(player,"friend-help");
        }
    }
    private void target(ProxiedPlayer player,String[] args,String permission,
            Function<UUID,CompletableFuture<FriendResult>> operation,String success) {
        if (!allowed(player,permission)) return;
        if (args.length!=2 || !args[1].matches("[A-Za-z0-9_]{1,16}")) {
            player.sendMessage(plugin.messages().line("friend-usage",StarfinSocialBungee.vars(
                "usage","/friend " + args[0] + " <jogador>"))); return;
        }
        if (plugin.database()==null) { send(player,"friend-unavailable"); return; }
        plugin.async(() -> plugin.database().resolveName(args[1])).whenComplete((target,error) -> {
            if (error!=null) { send(player,"friend-unavailable"); return; }
            if (target==null) { send(player,"friend-unknown"); return; }
            operation.apply(target).whenComplete((result,failure) -> {
                if (failure!=null || result==null) { send(player,"friend-unavailable"); return; }
                if (!result.succeeded()) { fail(player,result.code); return; }
                player.sendMessage(plugin.messages().line(success,StarfinSocialBungee.vars("friend",args[1])));
            });
        });
    }
    private void list(ProxiedPlayer player,String[] args) {
        final int page;
        try { page=args.length<2 ? 1 : Integer.parseInt(args[1]); }
        catch (NumberFormatException error) { send(player,"friend-invalid-page"); return; }
        if (page<1 || page>1000) { send(player,"friend-invalid-page"); return; }
        plugin.friendService().getFriends(player.getUniqueId(),page,plugin.settings().friendsPerPage)
            .thenCombine(plugin.friendService().countFriends(player.getUniqueId()),
                (entries,count) -> new FriendPage(entries,count))
            .whenComplete((result,error) -> {
                if (error!=null) { send(player,"friend-unavailable"); return; }
                List<FriendEntry> entries=result.entries;
                int pages=Math.max(1,(result.count+plugin.settings().friendsPerPage-1)
                    /plugin.settings().friendsPerPage);
                player.sendMessage(plugin.messages().line("friend-list-title",StarfinSocialBungee.vars(
                    "page",Integer.toString(page),"pages",Integer.toString(pages))));
                if (entries.isEmpty()) send(player,"friend-list-empty");
                for (FriendEntry entry:entries) {
                    String state=entry.hidden ? plugin.messages().raw("friend-hidden",null)
                        : entry.online ? (entry.server.isEmpty()
                            ? plugin.messages().raw("friend-online-status",null) : entry.server)
                            : plugin.messages().raw("friend-offline-status",null);
                    player.sendMessage(plugin.messages().line("friend-list-entry",StarfinSocialBungee.vars(
                        "friend",entry.name,"status",state,"server",entry.server,
                        "time",entry.lastSeen==0?"?":new SimpleDateFormat("dd/MM/yyyy HH:mm").format(new Date(entry.lastSeen)))));
                    if (!entry.online && !entry.hidden && entry.lastSeen>0)
                        player.sendMessage(plugin.messages().line("friend-last-seen",StarfinSocialBungee.vars(
                            "time",new SimpleDateFormat("dd/MM/yyyy HH:mm").format(new Date(entry.lastSeen)))));
                }
            });
    }
    private static final class FriendPage {
        final List<FriendEntry> entries;
        final int count;
        FriendPage(List<FriendEntry> entries,int count) { this.entries=entries; this.count=count; }
    }
    private void requests(ProxiedPlayer player,boolean received) {
        plugin.friendService().requests(player.getUniqueId(),received).whenComplete((list,error) -> {
            if (error!=null) { send(player,"friend-unavailable"); return; }
            send(player,received?"friend-requests-title":"friend-pending-title");
            if (list.isEmpty()) send(player,"friend-requests-empty");
            for (FriendRequest request:list) {
                String name=received?request.senderName:request.targetName;
                player.sendMessage(plugin.messages().line("friend-request-entry",
                    StarfinSocialBungee.vars("friend",name==null?"?":name)));
            }
        });
    }
    private void settings(ProxiedPlayer player,String[] args) {
        UUID actor=player.getUniqueId();
        if (args.length==1) {
            plugin.friendService().settings(actor).whenComplete((s,error) -> {
                if (error!=null) { send(player,"friend-unavailable"); return; }
                for (String key:new String[]{"requests","online-notify","offline-notify",
                        "show-online","show-server","party-invites"})
                    player.sendMessage(plugin.messages().line("friend-setting-entry",
                        StarfinSocialBungee.vars("setting",key,"value",Boolean.toString(s.value(key)))));
            }); return;
        }
        if (args.length!=3 || !(args[2].equalsIgnoreCase("on") || args[2].equalsIgnoreCase("off"))) {
            send(player,"friend-settings-usage"); return;
        }
        update(player,args[1].toLowerCase(Locale.ROOT),args[2].equalsIgnoreCase("on"));
    }
    private void toggle(ProxiedPlayer player) {
        plugin.friendService().settings(player.getUniqueId()).whenComplete((s,error) -> {
            if (error!=null) { send(player,"friend-unavailable"); return; }
            update(player,"requests",!s.allowRequests);
        });
    }
    private void update(ProxiedPlayer player,String key,boolean value) {
        plugin.friendService().setSetting(player.getUniqueId(),key,value).whenComplete((result,error) -> {
            if (error!=null || result==null) { send(player,"friend-unavailable"); return; }
            if (!result.succeeded()) { fail(player,result.code); return; }
            player.sendMessage(plugin.messages().line("friend-setting-saved",StarfinSocialBungee.vars(
                "setting",key,"value",Boolean.toString(value))));
        });
    }
    private void status(ProxiedPlayer player,String[] args) {
        if (args.length!=2 || !args[1].matches("[A-Za-z0-9_]{1,16}")) {
            send(player,"friend-status-usage"); return;
        }
        plugin.async(() -> {
            UUID target=plugin.database().resolveName(args[1]);
            if (target==null || !plugin.friendDatabase().isFriend(player.getUniqueId(),target)) return null;
            int pages=(plugin.friendDatabase().count(player.getUniqueId())+44)/45;
            for (int page=1;page<=pages;page++)
                for (FriendEntry entry:plugin.friendDatabase().friends(player.getUniqueId(),page,45))
                    if (entry.playerId.equals(target)) return entry;
            return null;
        }).whenComplete((entry,error) -> {
            if (error!=null) { send(player,"friend-unavailable"); return; }
            if (entry==null) { send(player,"friend-not-friend"); return; }
            player.sendMessage(plugin.messages().line("friend-status-entry",StarfinSocialBungee.vars(
                "friend",entry.name,"status",entry.hidden?plugin.messages().raw("friend-hidden",null)
                    : entry.online?plugin.messages().raw("friend-online-status",null)
                        : plugin.messages().raw("friend-offline-status",null),
                "server",entry.server.isEmpty()?"?":entry.server)));
        });
    }
    private boolean allowed(ProxiedPlayer player,String action) {
        if (plugin.friendPermitted(player,action)) return true;
        send(player,"friend-no-permission"); return false;
    }
    static int limit(ProxiedPlayer player,StarfinSocialBungee plugin) {
        int limit=plugin.settings().defaultMaxFriends;
        for (int tier:new int[]{100,250,500})
            if (player.hasPermission("starfinsocial.friend.limit." + tier)) limit=Math.max(limit,tier);
        return limit;
    }
    private int limit(ProxiedPlayer player) { return limit(player,plugin); }
    private void fail(ProxiedPlayer player,FriendResult.Code code) {
        String key="friend-reason-" + code.name().toLowerCase(Locale.ROOT).replace('_','-');
        player.sendMessage(plugin.messages().line("friend-error",StarfinSocialBungee.vars(
            "reason",plugin.messages().raw(key,null))));
    }
    private void send(ProxiedPlayer player,String key) { player.sendMessage(plugin.messages().line(key,null)); }
    @Override public Iterable<String> onTabComplete(CommandSender sender,String[] args) {
        if (args.length==1) return java.util.Arrays.asList("add","accept","deny","cancel",
            "remove","list","requests","pending","settings","toggle","status","help");
        if (args.length==2 && sender instanceof ProxiedPlayer) {
            List<String> names=new java.util.ArrayList<String>();
            for (ProxiedPlayer player:plugin.proxy().getPlayers())
                if (player.getName().toLowerCase(Locale.ROOT).startsWith(args[1].toLowerCase(Locale.ROOT)))
                    names.add(player.getName());
            return names;
        }
        return java.util.Collections.emptyList();
    }
}
