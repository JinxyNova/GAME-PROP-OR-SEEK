package fr.merci.cachecache;

import org.bukkit.plugin.java.JavaPlugin;

public class CacheCachePlugin extends JavaPlugin {

    private GameManager gameManager;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        this.gameManager = new GameManager(this);
        GameMenu gameMenu = new GameMenu(this, gameManager);

        getCommand("cachecache").setExecutor(new CacheCacheCommand(this, gameManager, gameMenu));
        getServer().getPluginManager().registerEvents(new GameListener(gameManager, gameMenu), this);
        getServer().getPluginManager().registerEvents(gameMenu, this);

        getLogger().info("CacheCache est activé !");
    }

    @Override
    public void onDisable() {
        if (gameManager != null) {
            gameManager.forceStop();
        }
    }

    public GameManager getGameManager() {
        return gameManager;
    }
}
