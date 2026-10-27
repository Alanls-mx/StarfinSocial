package com.starfinplugins.social.spigot;

import com.google.common.io.ByteArrayDataInput;
import com.google.common.io.ByteArrayDataOutput;
import com.google.common.io.ByteStreams;
import com.starfinplugins.social.api.FriendEntry;
import com.starfinplugins.social.api.FriendRequest;
import com.starfinplugins.social.api.FriendSettings;
import com.starfinplugins.social.common.FriendStore;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.concurrent.Executor;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.plugin.messaging.PluginMessageListener;

/** 54-slot viewer; every database read is scheduled off the server thread. */
final class FriendMenu implements Listener, PluginMessageListener {
    private static final String CHANNEL="SSFriends";
    private final StarfinSocialSpigot plugin;
    private final FriendStore store;
    private final Executor io;
    private final Map<UUID,View> views=new HashMap<UUID,View>();

    FriendMenu(StarfinSocialSpigot plugin,FriendStore store,Executor io) {
        this.plugin=plugin; this.store=store; this.io=io;
        Bukkit.getPluginManager().registerEvents(this,plugin);
        Bukkit.getMessenger().registerIncomingPluginChannel(plugin,CHANNEL,this);
        Bukkit.getMessenger().registerOutgoingPluginChannel(plugin,CHANNEL);
    }
    @Override public void onPluginMessageReceived(String channel,Player carrier,byte[] bytes) {
        if (!CHANNEL.equals(channel)) return;
        try {
            ByteArrayDataInput input=ByteStreams.newDataInput(bytes);
            if (!"open".equals(input.readUTF())) return;
            UUID id=UUID.fromString(input.readUTF());
            Player target=Bukkit.getPlayer(id);
            if (target!=null) open(target,views.containsKey(id)?views.get(id).page:1);
        } catch (RuntimeException error) { plugin.getLogger().warning("Mensagem inválida do proxy no menu de amigos."); }
    }
    private void open(Player player,int page) {
        io.execute(() -> {
            try {
                List<FriendEntry> friends=store.friends(player.getUniqueId(),page,36);
                List<FriendRequest> requests=store.requests(player.getUniqueId(),true);
                List<FriendRequest> sent=store.requests(player.getUniqueId(),false);
                FriendSettings settings=store.settings(player.getUniqueId());
                Bukkit.getScheduler().runTask(plugin,() -> render(player,page,friends,requests,sent,settings));
            } catch (Exception error) {
                Bukkit.getScheduler().runTask(plugin,() -> player.sendMessage(text("menu.error",
                    "&c[Amigos] Dados temporariamente indisponíveis.")));
            }
        });
    }
    private void render(Player player,int page,List<FriendEntry> friends,
                        List<FriendRequest> requests,List<FriendRequest> sent,
                        FriendSettings settings) {
        if (!player.isOnline()) return;
        View view=new View(page);
        Inventory inventory=Bukkit.createInventory(view,54,text("menu.title","&bAmigos"));
        view.inventory=inventory;
        for (int i=0;i<friends.size() && i<36;i++) {
            FriendEntry friend=friends.get(i);
            view.targets.put(i,friend.playerId);
            view.names.put(i,friend.name);
            String status=friend.hidden ? text("menu.hidden","Oculto")
                : friend.online ? (friend.server.isEmpty()?text("menu.online","Online"):friend.server)
                    : text("menu.offline","Offline");
            List<String> lore=new ArrayList<String>(Arrays.asList(
                text("menu.friend-status","&7Estado: &f{status}").replace("{status}",status),
                text("menu.friend-actions","&eEsquerdo: status &7| &cDireito: remover"),
                text("menu.party-action","&bShift + esquerdo: convidar para Party")));
            if (!friend.online && !friend.hidden && friend.lastSeen>0)
                lore.add(text("menu.last-seen","&7Última conexão: &f{time}").replace("{time}",
                    new SimpleDateFormat("dd/MM/yyyy HH:mm").format(new Date(friend.lastSeen))));
            inventory.setItem(i,skull(friend.name,lore));
        }
        for (int i=0;i<requests.size() && i<9;i++) {
            FriendRequest request=requests.get(i);
            view.requests.put(36+i,request.sender);
            inventory.setItem(36+i,skull(request.senderName==null?"?":request.senderName,
                Arrays.asList(text("menu.request-title","&eSolicitação recebida"),
                    text("menu.request-actions","&aEsquerdo: aceitar &7| &cDireito: recusar"))));
        }
        inventory.setItem(45,item(Material.ARROW,text("menu.previous","&ePágina anterior")));
        inventory.setItem(46,item(Material.ARROW,text("menu.next","&ePróxima página")));
        inventory.setItem(47,item(Material.PAPER,text("menu.pending","&bSolicitações enviadas: &f{count}")
            .replace("{count}",Integer.toString(sent.size()))));
        inventory.setItem(48,item(Material.LEVER,text("menu.toggle","&bReceber solicitações: &f{value}")
            .replace("{value}",Boolean.toString(settings.allowRequests))));
        inventory.setItem(49,item(Material.BOOK,text("menu.settings","&bPreferências: /friend settings")));
        inventory.setItem(50,item(Material.COMPASS,text("menu.refresh","&aAtualizar")));
        for (int i=0;i<sent.size() && i<3;i++) {
            FriendRequest request=sent.get(i);
            view.sent.put(51+i,request.target);
            inventory.setItem(51+i,skull(request.targetName==null?"?":request.targetName,
                Arrays.asList(text("menu.sent-title","&eSolicitação enviada"),
                    text("menu.sent-actions","&cClique para cancelar"))));
        }
        views.put(player.getUniqueId(),view);
        player.openInventory(inventory);
    }
    private String text(String key,String fallback) {
        return ChatColor.translateAlternateColorCodes('&',plugin.getConfig().getString(key,fallback));
    }
    private static ItemStack item(Material material,String name) {
        ItemStack item=new ItemStack(material);
        ItemMeta meta=item.getItemMeta(); meta.setDisplayName(name); item.setItemMeta(meta);
        return item;
    }
    private static ItemStack skull(String owner,List<String> lore) {
        ItemStack item=new ItemStack(Material.SKULL_ITEM,1,(short)3);
        SkullMeta meta=(SkullMeta)item.getItemMeta();
        if (owner.matches("[A-Za-z0-9_]{1,16}")) meta.setOwner(owner);
        meta.setDisplayName(ChatColor.AQUA+owner);
        meta.setLore(lore); item.setItemMeta(meta); return item;
    }
    @EventHandler public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) return;
        Player player=(Player)event.getWhoClicked();
        View view=views.get(player.getUniqueId());
        if (view==null || event.getView().getTopInventory().getHolder()!=view) return;
        event.setCancelled(true);
        int slot=event.getRawSlot();
        if (slot<0 || slot>=54) return;
        if (slot==45) { if (view.page>1) open(player,view.page-1); return; }
        if (slot==46) { if (view.targets.size()==36) open(player,view.page+1); return; }
        if (slot==50) { open(player,view.page); return; }
        if (slot==48) { click(player,"toggle",null); return; }
        UUID request=view.requests.get(slot);
        if (request!=null) { click(player,event.isRightClick()?"deny":"accept",request); return; }
        UUID sent=view.sent.get(slot);
        if (sent!=null) { click(player,"cancel",sent); return; }
        UUID target=view.targets.get(slot);
        if (target==null) return;
        if (event.isShiftClick() && event.isLeftClick()) click(player,"party",target);
        else if (event.isRightClick()) click(player,"remove",target);
        else player.sendMessage(text("menu.friend-info","&b[Amigos] /friend status {friend}; direito remove; Shift + esquerdo convida para Party.")
            .replace("{friend}",view.names.get(slot)));
    }
    private void click(Player player,String action,UUID target) {
        ByteArrayDataOutput data=ByteStreams.newDataOutput();
        data.writeUTF("click"); data.writeUTF(player.getUniqueId().toString());
        data.writeUTF(action); if (target!=null) data.writeUTF(target.toString());
        player.sendPluginMessage(plugin,CHANNEL,data.toByteArray());
    }
    @EventHandler public void onClose(InventoryCloseEvent event) {
        View view=views.get(event.getPlayer().getUniqueId());
        if (view!=null && event.getInventory().getHolder()==view)
            views.remove(event.getPlayer().getUniqueId());
    }
    private static final class View implements InventoryHolder {
        Inventory inventory;
        final int page;
        final Map<Integer,UUID> targets=new HashMap<Integer,UUID>();
        final Map<Integer,UUID> requests=new HashMap<Integer,UUID>();
        final Map<Integer,UUID> sent=new HashMap<Integer,UUID>();
        final Map<Integer,String> names=new HashMap<Integer,String>();
        View(int page) { this.page=page; }
        @Override public Inventory getInventory() { return inventory; }
    }
}
