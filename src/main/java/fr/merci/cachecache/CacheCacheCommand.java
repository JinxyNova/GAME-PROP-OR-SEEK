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

    private static final String USAGE = ChatColor.YELLOW
            + "/cachecache start <prophunt|hideandseek> [nbChats] [nbSouris] | stop";

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
                GameManager.Mode mode = switch (args[1].toLowerCase()) {
                    case "prophunt" -> GameManager.Mode.PROP_HUNT;
                    case "hideandseek" -> GameManager.Mode.HIDE_AND_SEEK;
                    default -> null;
                };
                if (mode == null) {
                    sender.sendMessage(USAGE);
                    return true;
                }

                int nbChats = 0;   // 0 = valeur par défaut (1 chat)
                int nbSouris = 0;  // 0 = pas de limite (tous les joueurs restants)

                if (args.length >= 3) {
                    Integer parsed = parseInt(args[2]);
                    if (parsed == null || parsed < 1) {
                        sender.sendMessage(ChatColor.RED + "Le nombre de chats doit être un entier positif.");
                        return true;
                    }
                    nbChats = parsed;
                }
                if (args.length >= 4) {
                    Integer parsed = parseInt(args[3]);
                    if (parsed == null || parsed < 1) {
                        sender.sendMessage(ChatColor.RED + "Le nombre de souris doit être un entier positif.");
                        return true;
                    }
                    nbSouris = parsed;
                }

                gameManager.start(sender, mode, nbChats, nbSouris);
            }
            case "stop" -> gameManager.stop(sender);
            default -> sender.sendMessage(USAGE);
        }
        return true;
    }

    private Integer parseInt(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
