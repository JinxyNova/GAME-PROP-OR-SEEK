package fr.merci.cachecache;

import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;

public class CacheCacheCommand implements CommandExecutor {
    private final GameManager gameManager;

    public CacheCacheCommand(GameManager gameManager) {
        this.gameManager = gameManager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage(ChatColor.YELLOW + "/cachecache start | stop");
            return true;
        }
        switch (args[0].toLowerCase()) {
            case "start" -> gameManager.start(sender);
            case "stop" -> gameManager.stop(sender);
            default -> sender.sendMessage(ChatColor.YELLOW + "/cachecache start | stop");
        }
        return true;
    }
}
