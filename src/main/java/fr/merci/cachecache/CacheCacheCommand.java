package fr.merci.cachecache;

import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class CacheCacheCommand implements CommandExecutor {
    private final CacheCachePlugin plugin;
    private final GameManager gameManager;

    public CacheCacheCommand(CacheCachePlugin plugin, GameManager gameManager) {
        this.plugin = plugin;
        this.gameManager = gameManager;
    }

    private static final String USAGE = ChatColor.YELLOW
            + "/cachecache start <prophunt|hideandseek> [nbChats] [nbSouris] | queue <prophunt|hideandseek> [nbChats] [nbSouris] | join | leave | stop | reload";

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage(USAGE);
            return true;
        }
        switch (args[0].toLowerCase()) {
            case "start" -> {
                if (args.length < 2) {
                    sender.sendMessage(USAGE);
                    return true;
                }
                GameManager.Mode mode = parseMode(args[1]);
                if (mode == null) {
                    sender.sendMessage(USAGE);
                    return true;
                }
                int[] counts = parseCounts(sender, args);
                if (counts == null) return true;
                gameManager.start(sender, mode, counts[0], counts[1]);
            }
            case "queue" -> {
                if (args.length < 2) {
                    sender.sendMessage(USAGE);
                    return true;
                }
                GameManager.Mode mode = parseMode(args[1]);
                if (mode == null) {
                    sender.sendMessage(USAGE);
                    return true;
                }
                int[] counts = parseCounts(sender, args);
                if (counts == null) return true;
                gameManager.configureLobby(sender, mode, counts[0], counts[1]);
            }
            case "join" -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage(ChatColor.RED + "Seul un joueur peut rejoindre la file d'attente.");
                    return true;
                }
                gameManager.join(player);
            }
            case "leave" -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage(ChatColor.RED + "Seul un joueur peut quitter la file d'attente.");
                    return true;
                }
                gameManager.leave(player);
            }
            case "stop" -> gameManager.stop(sender);
            case "reload" -> {
                plugin.reloadConfig();
                gameManager.reloadSettings();
                sender.sendMessage(ChatColor.GREEN + "Configuration de CacheCache rechargée.");
            }
            default -> sender.sendMessage(USAGE);
        }
        return true;
    }

    private GameManager.Mode parseMode(String arg) {
        return switch (arg.toLowerCase()) {
            case "prophunt" -> GameManager.Mode.PROP_HUNT;
            case "hideandseek" -> GameManager.Mode.HIDE_AND_SEEK;
            default -> null;
        };
    }

    /** @return [nbChats, nbSouris] (0 = valeur par défaut/pas de limite), ou null si erreur (message déjà envoyé) */
    private int[] parseCounts(CommandSender sender, String[] args) {
        int nbChats = 0;   // 0 = valeur par défaut (1 chat)
        int nbSouris = 0;  // 0 = pas de limite (tous les joueurs restants)

        if (args.length >= 3) {
            Integer parsed = parseInt(args[2]);
            if (parsed == null || parsed < 1) {
                sender.sendMessage(ChatColor.RED + "Le nombre de chats doit être un entier positif.");
                return null;
            }
            nbChats = parsed;
        }
        if (args.length >= 4) {
            Integer parsed = parseInt(args[3]);
            if (parsed == null || parsed < 1) {
                sender.sendMessage(ChatColor.RED + "Le nombre de souris doit être un entier positif.");
                return null;
            }
            nbSouris = parsed;
        }
        return new int[]{nbChats, nbSouris};
    }

    private Integer parseInt(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
