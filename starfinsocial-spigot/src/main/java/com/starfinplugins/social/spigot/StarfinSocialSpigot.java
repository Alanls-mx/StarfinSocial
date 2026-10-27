package com.starfinplugins.social.spigot;

import com.starfinplugins.social.api.PartyDirectory;
import com.starfinplugins.social.api.PartySnapshot;
import com.starfinplugins.social.api.FriendSettings;
import com.starfinplugins.social.common.FriendStore;
import com.starfinplugins.social.common.PartyRules;
import com.starfinplugins.social.common.SocialStore;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

/** Optional Spigot-side read service. All mutations remain proxy authoritative. */
public final class StarfinSocialSpigot extends JavaPlugin {
    private HikariDataSource pool;
    private SocialStore store;
    private ThreadPoolExecutor io;
    private FriendMenu friendMenu;
    private final PartyDirectory directory = new PartyDirectory() {
        @Override public CompletableFuture<PartySnapshot> getParty(UUID player) {
            return read(() -> store.getParty(player));
        }
        @Override public CompletableFuture<PartySnapshot> getPartyById(UUID party) {
            return read(() -> store.getPartyById(party));
        }
    };

    @Override public void onEnable() {
        saveDefaultConfig();
        String host = getConfig().getString("mysql.host", "localhost");
        String database = getConfig().getString("mysql.database", "starfin");
        String user = getConfig().getString("mysql.username", "starfin");
        String ssl = getConfig().getString("mysql.ssl-mode", "PREFERRED");
        int port = getConfig().getInt("mysql.port", 3306);
        if (!host.matches("[A-Za-z0-9.:-]+") || !database.matches("[A-Za-z0-9_]+")
                || user == null || user.isEmpty() || port < 1 || port > 65535
                || !java.util.Arrays.asList("DISABLED","PREFERRED","REQUIRED", "VERIFY_CA",
                    "VERIFY_IDENTITY").contains(ssl.toUpperCase(java.util.Locale.ROOT))) {
            getLogger().severe("Configuração MySQL StarfinSocial inválida.");
            getServer().getPluginManager().disablePlugin(this); return;
        }
        HikariConfig settings = new HikariConfig();
        settings.setJdbcUrl("jdbc:mysql://" + host + ":" + port + "/" + database
            + "?sslMode=" + ssl + "&useUnicode=true&characterEncoding=UTF-8");
        settings.setUsername(user);
        settings.setPassword(getConfig().getString("mysql.password", ""));
        settings.setDriverClassName(com.mysql.cj.jdbc.Driver.class.getName());
        settings.setMaximumPoolSize(2);
        settings.setMinimumIdle(0);
        settings.setConnectionTimeout(5000);
        settings.setInitializationFailTimeout(-1);
        settings.setPoolName("StarfinSocial-Spigot");
        pool = new HikariDataSource(settings);
        store = new SocialStore(pool, PartyRules.defaults());
        io = new ThreadPoolExecutor(1, 2, 30, TimeUnit.SECONDS,
            new ArrayBlockingQueue<Runnable>(32), runnable -> {
                Thread thread = new Thread(runnable,"StarfinSocial-Spigot-SQL");
                thread.setDaemon(true); return thread;
            }, new ThreadPoolExecutor.AbortPolicy());
        getServer().getServicesManager().register(PartyDirectory.class, directory,
            this, ServicePriority.Normal);
        FriendStore friends = new FriendStore(pool,7*86400000L,5000,25,true,30000,
            new FriendSettings(true,true,false,true,true,true));
        friendMenu = new FriendMenu(this,friends,io);
        getLogger().info("PartyDirectory registrada. SQL é consultado assincronamente.");
    }

    private interface SqlRead<T> { T run() throws SQLException; }
    private <T> CompletableFuture<T> read(SqlRead<T> operation) {
        CompletableFuture<T> future = new CompletableFuture<T>();
        try {
            if (io == null || io.isShutdown()) throw new RejectedExecutionException();
            io.execute(() -> {
                try { future.complete(operation.run()); }
                catch (Exception error) { future.completeExceptionally(error); }
            });
        } catch (RejectedExecutionException error) { future.completeExceptionally(error); }
        return future;
    }

    public PartyDirectory partyDirectory() { return directory; }

    @Override public void onDisable() {
        getServer().getServicesManager().unregisterAll(this);
        if (io != null) {
            io.shutdown();
            try { if (!io.awaitTermination(5, TimeUnit.SECONDS)) io.shutdownNow(); }
            catch (InterruptedException error) { Thread.currentThread().interrupt(); io.shutdownNow(); }
        }
        if (pool != null) pool.close();
    }
}
