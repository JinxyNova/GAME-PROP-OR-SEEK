package fr.merci.cachecache;

import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * Menu graphique (inventaire) pour piloter CacheCache sans avoir à taper
 * toutes les commandes / options à la main. Ouvert via /cachecache ou
 * /cachecache menu.
 */
public class GameMenu implements Listener {

    private final CacheCachePlugin plugin;
    private final GameManager gameManager;

    public GameMenu(CacheCachePlugin plugin, GameManager gameManager) {
        this.plugin = plugin;
        this.gameManager = gameManager;
    }

    /** Support de menu : chaque inventaire ouvert a ses propres actions par slot. */
    private static class Holder implements InventoryHolder {
        private Inventory inventory;
        private final Map<Integer, BiConsumer<Player, ClickType>> actions = new HashMap<>();

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private Inventory createInventory(Holder holder, int size, String legacyTitle) {
        Inventory inv = Bukkit.createInventory(holder, size, LegacyComponentSerializer.legacySection().deserialize(legacyTitle));
        holder.inventory = inv;
        return inv;
    }

    private ItemStack item(Material material, String name, String... lore) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        meta.setDisplayName(name);
        if (lore.length > 0) meta.setLore(Arrays.asList(lore));
        stack.setItemMeta(meta);
        return stack;
    }

    // ---------------------------------------------------------------------
    // Menu principal
    // ---------------------------------------------------------------------

    public void openMain(Player player) {
        Holder holder = new Holder();
        Inventory inv = createInventory(holder, 27, ChatColor.GOLD + "Cache-Cache");

        GameManager.State state = gameManager.getState();
        String stateLabel = switch (state) {
            case WAITING -> "En attente";
            case LOBBY -> "File d'attente (" + gameManager.getLobbyCount() + " joueur(s))";
            case HIDING -> "Partie en cours - phase de cache";
            case SEEKING -> "Partie en cours - phase de recherche";
        };
        inv.setItem(4, item(Material.BOOK, ChatColor.YELLOW + "État actuel",
                ChatColor.GRAY + stateLabel,
                ChatColor.GRAY + "Mode : " + (gameManager.getMode() == GameManager.Mode.PROP_HUNT ? "Prop Hunt" : "Cache-cache classique")));

        inv.setItem(11, item(Material.LIME_WOOL, ChatColor.GREEN + "Rejoindre la file d'attente",
                ChatColor.GRAY + "Clique pour rejoindre la prochaine partie"));
        holder.actions.put(11, (p, click) -> {
            gameManager.join(p);
            p.closeInventory();
        });

        inv.setItem(15, item(Material.RED_WOOL, ChatColor.RED + "Quitter la file d'attente",
                ChatColor.GRAY + "Clique pour quitter la file"));
        holder.actions.put(15, (p, click) -> {
            gameManager.leave(p);
            p.closeInventory();
        });

        inv.setItem(19, item(Material.COBBLESTONE, ChatColor.LIGHT_PURPLE + "Lancer : Prop Hunt",
                ChatColor.GRAY + "Déguisement en bloc",
                ChatColor.GRAY + "Clique pour configurer et lancer/programmer"));
        holder.actions.put(19, (p, click) -> openStartConfig(p, GameManager.Mode.PROP_HUNT));

        inv.setItem(21, item(Material.OAK_LEAVES, ChatColor.LIGHT_PURPLE + "Lancer : Cache-cache classique",
                ChatColor.GRAY + "Pas de déguisement",
                ChatColor.GRAY + "Clique pour configurer et lancer/programmer"));
        holder.actions.put(21, (p, click) -> openStartConfig(p, GameManager.Mode.HIDE_AND_SEEK));

        inv.setItem(23, item(Material.BARRIER, ChatColor.RED + "Arrêter / annuler",
                ChatColor.GRAY + "Arrête la partie en cours",
                ChatColor.GRAY + "ou annule la file d'attente"));
        holder.actions.put(23, (p, click) -> {
            gameManager.stop(p);
            p.closeInventory();
        });

        inv.setItem(25, item(Material.COMPARATOR, ChatColor.AQUA + "Recharger la configuration",
                ChatColor.GRAY + "Recharge config.yml sans redémarrer"));
        holder.actions.put(25, (p, click) -> {
            plugin.reloadConfig();
            gameManager.reloadSettings();
            p.sendMessage(ChatColor.GREEN + "Configuration de CacheCache rechargée.");
            p.closeInventory();
        });

        player.openInventory(inv);
    }

    // ---------------------------------------------------------------------
    // Configuration du lancement (mode déjà choisi) : nombre de chats /
    // souris réglable directement par clics, puis lancer ou programmer.
    // ---------------------------------------------------------------------

    private void openStartConfig(Player player, GameManager.Mode mode) {
        Holder holder = new Holder();
        String modeLabel = mode == GameManager.Mode.PROP_HUNT ? "Prop Hunt" : "Cache-cache classique";
        Inventory inv = createInventory(holder, 27, ChatColor.GOLD + "Lancer : " + modeLabel);

        // 0 = valeur automatique (comportement par défaut du plugin)
        int[] seekerCount = {0};
        int[] hiderCount = {0};

        Runnable[] refresh = new Runnable[1];
        refresh[0] = () -> {
            inv.setItem(11, item(Material.OCELOT_SPAWN_EGG,
                    ChatColor.GOLD + "Nombre de chats : " + (seekerCount[0] == 0 ? "auto (1)" : String.valueOf(seekerCount[0])),
                    ChatColor.GRAY + "Clic gauche : +1    Clic droit : -1",
                    ChatColor.GRAY + "Shift + clic : +5 / -5"));
            inv.setItem(15, item(Material.RABBIT_SPAWN_EGG,
                    ChatColor.AQUA + "Nombre de souris : " + (hiderCount[0] == 0 ? "auto (tous les autres)" : String.valueOf(hiderCount[0])),
                    ChatColor.GRAY + "Clic gauche : +1    Clic droit : -1",
                    ChatColor.GRAY + "Shift + clic : +5 / -5"));
        };
        refresh[0].run();

        holder.actions.put(11, (p, click) -> {
            int delta = click.isShiftClick() ? 5 : 1;
            if (click.isRightClick()) delta = -delta;
            seekerCount[0] = Math.max(0, Math.min(50, seekerCount[0] + delta));
            refresh[0].run();
        });
        holder.actions.put(15, (p, click) -> {
            int delta = click.isShiftClick() ? 5 : 1;
            if (click.isRightClick()) delta = -delta;
            hiderCount[0] = Math.max(0, Math.min(50, hiderCount[0] + delta));
            refresh[0].run();
        });

        inv.setItem(20, item(Material.EMERALD_BLOCK, ChatColor.GREEN + "Lancer maintenant",
                ChatColor.GRAY + "Démarre la partie immédiatement avec tous les joueurs connectés"));
        holder.actions.put(20, (p, click) -> {
            gameManager.start(p, mode, seekerCount[0], hiderCount[0]);
            p.closeInventory();
        });

        inv.setItem(24, item(Material.CLOCK, ChatColor.GOLD + "Programmer (file d'attente)",
                ChatColor.GRAY + "Préconfigure le mode/effectifs pour le",
                ChatColor.GRAY + "prochain lancement automatique de la file"));
        holder.actions.put(24, (p, click) -> {
            gameManager.configureLobby(p, mode, seekerCount[0], hiderCount[0]);
            p.closeInventory();
        });

        inv.setItem(26, item(Material.ARROW, ChatColor.GRAY + "Retour"));
        holder.actions.put(26, (p, click) -> openMain(p));

        player.openInventory(inv);
    }

    // ---------------------------------------------------------------------
    // Gestion des clics
    // ---------------------------------------------------------------------

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof Holder holder)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (event.getClickedInventory() == null || event.getClickedInventory().getHolder() != holder) return;
        BiConsumer<Player, ClickType> action = holder.actions.get(event.getSlot());
        if (action != null) action.accept(player, event.getClick());
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof Holder) {
            event.setCancelled(true);
        }
    }
}
