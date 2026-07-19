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
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.MenuType;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.view.AnvilView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;

/**
 * Menu graphique (inventaire) pour piloter CacheCache sans avoir à taper
 * toutes les commandes / options à la main. Ouvert via /cachecache ou
 * /cachecache menu.
 */
public class GameMenu implements Listener {

    private final CacheCachePlugin plugin;
    private final GameManager gameManager;

    // Joueurs actuellement en train de taper une recherche de bloc dans l'enclume
    // virtuelle (cf. openBlockSearch / onPrepareAnvil / onAnvilResultClick).
    private final Set<UUID> searchingBlockPicker = new HashSet<>();

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
    // Menu de sélection de bloc (déguisement Prop Hunt, cf. Pistolet Transformeur)
    // ---------------------------------------------------------------------

    // Blocs "techniques"/admin qu'on ne propose pas comme déguisement (invisibles,
    // réservés à la création, ou instables une fois posés via BlockDisplay).
    private static final Set<Material> DISGUISE_EXCLUDED = EnumSet.of(
            Material.AIR, Material.CAVE_AIR, Material.VOID_AIR,
            Material.BARRIER, Material.LIGHT, Material.STRUCTURE_VOID,
            Material.STRUCTURE_BLOCK, Material.JIGSAW,
            Material.COMMAND_BLOCK, Material.CHAIN_COMMAND_BLOCK, Material.REPEATING_COMMAND_BLOCK,
            Material.MOVING_PISTON, Material.PISTON_HEAD
    );

    private static final List<Material> DISGUISE_BLOCKS = buildDisguiseBlockList();

    private static List<Material> buildDisguiseBlockList() {
        List<Material> list = new ArrayList<>();
        for (Material material : Material.values()) {
            if (!material.isBlock() || !material.isItem()) continue;
            if (material.isLegacy()) continue;
            if (DISGUISE_EXCLUDED.contains(material)) continue;
            list.add(material);
        }
        list.sort(Comparator.comparing(Enum::name));
        return list;
    }

    private static final int BLOCK_PICKER_PAGE_SIZE = 45; // 5 lignes de 9, la dernière ligne sert à la navigation

    private String prettyBlockName(Material material) {
        String lower = material.name().toLowerCase().replace('_', ' ');
        return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }

    /**
     * Filtre DISGUISE_BLOCKS sur le nom du bloc (nom technique anglais, ex.
     * "oak_log") : la recherche est insensible à la casse et accepte aussi
     * bien les espaces que les underscores ("oak log" ou "oak_log" marchent
     * tous les deux). Une requête vide renvoie la liste complète.
     */
    private List<Material> filterBlocks(String query) {
        if (query == null || query.isBlank()) return DISGUISE_BLOCKS;
        String needle = query.trim().toLowerCase().replace(' ', '_');
        List<Material> out = new ArrayList<>();
        for (Material material : DISGUISE_BLOCKS) {
            if (material.name().toLowerCase().contains(needle)) out.add(material);
        }
        return out;
    }

    public void openBlockPicker(Player player) {
        openBlockPicker(player, 0, "");
    }

    private void openBlockPicker(Player player, int page, String query) {
        List<Material> results = filterBlocks(query);
        boolean hasQuery = query != null && !query.isBlank();
        int totalPages = Math.max(1, (int) Math.ceil(results.size() / (double) BLOCK_PICKER_PAGE_SIZE));
        int currentPage = Math.max(0, Math.min(page, totalPages - 1));

        Holder holder = new Holder();
        String titleQuery = hasQuery ? " \"" + query.trim() + "\"" : "";
        Inventory inv = createInventory(holder, 54,
                ChatColor.GOLD + "Choisir un bloc" + titleQuery + " (" + (currentPage + 1) + "/" + totalPages + ")");

        int start = currentPage * BLOCK_PICKER_PAGE_SIZE;
        int end = Math.min(results.size(), start + BLOCK_PICKER_PAGE_SIZE);
        for (int i = start; i < end; i++) {
            Material material = results.get(i);
            int slot = i - start;
            String label = prettyBlockName(material);
            inv.setItem(slot, item(material, ChatColor.WHITE + label,
                    ChatColor.GRAY + "Clique pour te déguiser en " + label.toLowerCase()));
            holder.actions.put(slot, (p, click) -> {
                gameManager.disguiseAsMaterial(p, material);
                p.closeInventory();
            });
        }
        if (results.isEmpty()) {
            inv.setItem(22, item(Material.BARRIER, ChatColor.RED + "Aucun bloc trouvé",
                    ChatColor.GRAY + "Le nom recherché est en anglais",
                    ChatColor.GRAY + "(ex. \"oak\", \"stone\", \"glass\")"));
        }

        if (currentPage > 0) {
            inv.setItem(45, item(Material.ARROW, ChatColor.YELLOW + "Page précédente"));
            holder.actions.put(45, (p, click) -> openBlockPicker(p, currentPage - 1, query));
        }
        inv.setItem(49, item(Material.BOOK, ChatColor.GRAY + "Page " + (currentPage + 1) + " / " + totalPages,
                ChatColor.GRAY + "" + results.size() + " bloc(s)"
                        + (hasQuery ? " correspondant(s)" : " disponibles")));
        if (currentPage < totalPages - 1) {
            inv.setItem(53, item(Material.ARROW, ChatColor.YELLOW + "Page suivante"));
            holder.actions.put(53, (p, click) -> openBlockPicker(p, currentPage + 1, query));
        }

        inv.setItem(48, item(Material.NAME_TAG,
                hasQuery ? ChatColor.AQUA + "Recherche : \"" + query.trim() + "\"" : ChatColor.AQUA + "Rechercher un bloc",
                ChatColor.GRAY + "Clique pour taper un nom de bloc",
                ChatColor.GRAY + "(nom anglais, ex. \"oak\", \"stone\")"));
        holder.actions.put(48, (p, click) -> openBlockSearch(p));

        if (hasQuery) {
            inv.setItem(50, item(Material.BARRIER, ChatColor.RED + "Effacer la recherche"));
            holder.actions.put(50, (p, click) -> openBlockPicker(p, 0, ""));
        }

        inv.setItem(47, item(Material.BARRIER, ChatColor.RED + "Redevenir toi-même",
                ChatColor.GRAY + "Annule le déguisement en cours"));
        holder.actions.put(47, (p, click) -> {
            gameManager.undisguise(p);
            p.sendMessage(ChatColor.GRAY + "Tu redeviens toi-même !");
            p.closeInventory();
        });

        inv.setItem(46, item(Material.ARROW, ChatColor.GRAY + "Retour"));
        holder.actions.put(46, (p, click) -> openMain(p));

        player.openInventory(inv);
    }

    /**
     * Ouvre une enclume "virtuelle" (pas besoin d'une vraie enclume posée dans
     * le monde) qui sert uniquement de champ de saisie de texte : on se sert
     * du champ de renommage comme barre de recherche, et de la case résultat
     * comme bouton de validation (cf. onPrepareAnvil / onAnvilResultClick).
     * Aucun coût en expérience ni en matériaux n'est réellement prélevé, vu
     * que le clic sur le résultat est annulé (event.setCancelled) avant que
     * quoi que ce soit ne soit transféré.
     */
    private void openBlockSearch(Player player) {
        AnvilView view = MenuType.ANVIL.create(player,
                LegacyComponentSerializer.legacySection().deserialize(ChatColor.GOLD + "Rechercher un bloc"));
        view.setRepairCost(0);
        searchingBlockPicker.add(player.getUniqueId());
        player.openInventory(view);
    }

    /**
     * Met à jour la case résultat de l'enclume de recherche à chaque frappe :
     * affiche juste le nombre de blocs qui correspondent au texte tapé, sans
     * jamais consommer d'objet/expérience (cf. openBlockSearch).
     */
    @EventHandler
    public void onPrepareAnvil(PrepareAnvilEvent event) {
        if (!(event.getView().getPlayer() instanceof Player player)) return;
        if (!searchingBlockPicker.contains(player.getUniqueId())) return;

        String query = event.getView().getRenameText();
        int count = filterBlocks(query).size();
        String label = (query == null || query.isBlank())
                ? ChatColor.GRAY + "Tape un nom de bloc..."
                : ChatColor.AQUA + "" + count + " bloc(s) trouvé(s)";
        event.setResult(item(Material.PAPER, label,
                ChatColor.GRAY + "Clique ici pour valider la recherche"));
    }

    /**
     * Valide la recherche quand le joueur clique sur la case résultat de
     * l'enclume : rouvre le menu de sélection de bloc filtré sur le texte
     * tapé, à la page 1. Le clic est toujours annulé pour ne jamais laisser
     * l'enclume prélever de coût.
     */
    @EventHandler
    public void onAnvilResultClick(InventoryClickEvent event) {
        if (!(event.getView() instanceof AnvilView anvilView)) return;
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (!searchingBlockPicker.contains(player.getUniqueId())) return;
        event.setCancelled(true);
        if (event.getRawSlot() != 2) return; // seule la case résultat valide la recherche

        String query = anvilView.getRenameText();
        searchingBlockPicker.remove(player.getUniqueId());
        player.closeInventory();
        openBlockPicker(player, 0, query == null ? "" : query);
    }

    /** Nettoie l'état de recherche si le joueur ferme l'enclume sans valider (touche Echap). */
    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (event.getPlayer() instanceof Player player) {
            searchingBlockPicker.remove(player.getUniqueId());
        }
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

        inv.setItem(13, item(Material.ANVIL, ChatColor.GOLD + "Réglages",
                ChatColor.GRAY + "Radar chaud/froid, sifflet auto,",
                ChatColor.GRAY + "feux d'artifice et sifflets-leurres"));
        holder.actions.put(13, (p, click) -> openSettings(p));

        inv.setItem(9, item(Material.ENDER_EYE, ChatColor.LIGHT_PURPLE + "Points de téléportation",
                ChatColor.GRAY + "Lobby, spawn des souris, spawn des chats"));
        holder.actions.put(9, (p, click) -> openTeleportPoints(p));

        inv.setItem(17, item(Material.GRASS_BLOCK, ChatColor.GREEN + "Choisir un bloc",
                ChatColor.GRAY + "" + DISGUISE_BLOCKS.size() + " blocs disponibles",
                ChatColor.GRAY + "(déguisement Prop Hunt)"));
        holder.actions.put(17, (p, click) -> openBlockPicker(p));

        player.openInventory(inv);
    }

    // ---------------------------------------------------------------------
    // Points de téléportation : lobby, spawn souris, spawn chats. Chaque
    // bouton enregistre la position actuelle du joueur (équivalent graphique
    // de /cachecache setlobby et /cachecache setmap souris|chats).
    // ---------------------------------------------------------------------

    private String describeLocation(org.bukkit.Location loc) {
        if (loc == null || loc.getWorld() == null) return ChatColor.RED + "Non défini";
        return ChatColor.GRAY + loc.getWorld().getName() + " "
                + (int) loc.getX() + ", " + (int) loc.getY() + ", " + (int) loc.getZ();
    }

    private void openTeleportPoints(Player player) {
        Holder holder = new Holder();
        Inventory inv = createInventory(holder, 27, ChatColor.GOLD + "Points de téléportation");

        Runnable[] refresh = new Runnable[1];
        refresh[0] = () -> {
            inv.setItem(11, item(Material.BEACON, ChatColor.AQUA + "Point du lobby",
                    describeLocation(gameManager.getLobbySpawn()),
                    ChatColor.GRAY + "Clique pour le définir à ta position actuelle"));
            inv.setItem(13, item(Material.RABBIT_SPAWN_EGG, ChatColor.GREEN + "Spawn des souris",
                    describeLocation(gameManager.getMapHidersSpawn()),
                    ChatColor.GRAY + "Clique pour le définir à ta position actuelle"));
            inv.setItem(15, item(Material.OCELOT_SPAWN_EGG, ChatColor.GOLD + "Spawn des chats",
                    describeLocation(gameManager.getMapSeekersSpawn()),
                    ChatColor.GRAY + "Clique pour le définir à ta position actuelle"));
        };
        refresh[0].run();

        holder.actions.put(11, (p, click) -> {
            if (gameManager.setLobbySpawn(p.getLocation())) {
                p.sendMessage(ChatColor.GREEN + "Point du lobby défini à ta position actuelle.");
            }
            refresh[0].run();
        });
        holder.actions.put(13, (p, click) -> {
            if (gameManager.setMapSpawn(true, p.getLocation())) {
                p.sendMessage(ChatColor.GREEN + "Spawn des souris défini à ta position actuelle.");
            }
            refresh[0].run();
        });
        holder.actions.put(15, (p, click) -> {
            if (gameManager.setMapSpawn(false, p.getLocation())) {
                p.sendMessage(ChatColor.GREEN + "Spawn des chats défini à ta position actuelle.");
            }
            refresh[0].run();
        });

        inv.setItem(26, item(Material.ARROW, ChatColor.GRAY + "Retour"));
        holder.actions.put(26, (p, click) -> openMain(p));

        player.openInventory(inv);
    }

    // ---------------------------------------------------------------------
    // Réglages : radar chaud/froid, sifflet automatique, décomptes de leurres
    // ---------------------------------------------------------------------

    private int getConfigInt(String path, int def) {
        return plugin.getConfig().getInt(path, def);
    }

    private boolean getConfigBoolean(String path, boolean def) {
        return plugin.getConfig().getBoolean(path, def);
    }

    private void setConfigInt(String path, int value) {
        plugin.getConfig().set(path, value);
        plugin.saveConfig();
        gameManager.reloadSettings();
    }

    private void toggleConfigBoolean(String path) {
        boolean current = plugin.getConfig().getBoolean(path, true);
        plugin.getConfig().set(path, !current);
        plugin.saveConfig();
        gameManager.reloadSettings();
    }

    private String formatMinSec(int totalSeconds) {
        int m = Math.max(0, totalSeconds) / 60;
        int s = Math.max(0, totalSeconds) % 60;
        return m + "m" + (s < 10 ? "0" + s : String.valueOf(s)) + "s";
    }

    private void openSettings(Player player) {
        Holder holder = new Holder();
        Inventory inv = createInventory(holder, 36, ChatColor.GOLD + "Réglages CacheCache");

        Runnable[] refresh = new Runnable[1];
        refresh[0] = () -> {
            int hideTime = getConfigInt("hide-time-seconds", 20);
            inv.setItem(10, item(Material.OAK_LEAVES,
                    ChatColor.GREEN + "Temps de cache : " + formatMinSec(hideTime),
                    ChatColor.GRAY + "Clic gauche : +5s   Clic droit : -5s",
                    ChatColor.GRAY + "Shift + clic : +30s / -30s"));

            int seekTime = getConfigInt("seek-time-seconds", 300);
            inv.setItem(11, item(Material.COMPASS,
                    ChatColor.GREEN + "Temps de recherche : " + formatMinSec(seekTime),
                    ChatColor.GRAY + "Clic gauche : +30s   Clic droit : -30s",
                    ChatColor.GRAY + "Shift + clic : +5min / -5min"));

            int minPlayers = getConfigInt("lobby.min-players", 2);
            inv.setItem(13, item(Material.PLAYER_HEAD,
                    ChatColor.YELLOW + "Joueurs min pour lancer : " + minPlayers,
                    ChatColor.GRAY + "Clic gauche : +1   Clic droit : -1"));

            int maxPlayers = getConfigInt("lobby.max-players", 0);
            inv.setItem(15, item(Material.TOTEM_OF_UNDYING,
                    ChatColor.YELLOW + "Joueurs max dans la file : " + (maxPlayers <= 0 ? "illimité" : String.valueOf(maxPlayers)),
                    ChatColor.GRAY + "Clic gauche : +1   Clic droit : -1",
                    ChatColor.GRAY + "0 = illimité"));

            boolean hotColdOn = getConfigBoolean("hotcold.enabled", true);
            int warning = getConfigInt("hotcold.warning-seconds", 300);
            inv.setItem(19, item(hotColdOn ? Material.LIME_DYE : Material.GRAY_DYE,
                    ChatColor.LIGHT_PURPLE + "Radar Chaud/Froid : " + (hotColdOn ? "Activé" : "Désactivé"),
                    ChatColor.GRAY + "Clique pour activer/désactiver"));
            inv.setItem(20, item(Material.CLOCK,
                    ChatColor.LIGHT_PURPLE + "Délai avant activation : " + formatMinSec(warning),
                    ChatColor.GRAY + "Clic gauche : +30s   Clic droit : -30s",
                    ChatColor.GRAY + "Shift + clic : +5min / -5min"));

            boolean autoWhistleOn = getConfigBoolean("decoys.auto-whistle.enabled", true);
            int interval = getConfigInt("decoys.auto-whistle.interval-seconds", 60);
            inv.setItem(22, item(autoWhistleOn ? Material.LIME_DYE : Material.GRAY_DYE,
                    ChatColor.AQUA + "Sifflet auto : " + (autoWhistleOn ? "Activé" : "Désactivé"),
                    ChatColor.GRAY + "Clique pour activer/désactiver"));
            inv.setItem(23, item(Material.GOAT_HORN,
                    ChatColor.AQUA + "Intervalle sifflet auto : " + interval + "s",
                    ChatColor.GRAY + "Clic gauche : +10s   Clic droit : -10s",
                    ChatColor.GRAY + "Shift + clic : +60s / -60s"));

            int fireworkCount = getConfigInt("decoys.firework-count", 8);
            inv.setItem(28, item(Material.FIREWORK_ROCKET,
                    ChatColor.GOLD + "Feux d'artifice donnés : " + fireworkCount,
                    ChatColor.GRAY + "Clic gauche : +1   Clic droit : -1",
                    ChatColor.GRAY + "Shift + clic : +5 / -5"));

            int whistleCount = getConfigInt("decoys.whistle-count", 3);
            inv.setItem(29, item(Material.GOAT_HORN,
                    ChatColor.AQUA + "Sifflets-leurres donnés : " + whistleCount,
                    ChatColor.GRAY + "Clic gauche : +1   Clic droit : -1",
                    ChatColor.GRAY + "Shift + clic : +5 / -5"));
        };
        refresh[0].run();

        holder.actions.put(10, (p, click) -> {
            int step = click.isShiftClick() ? 30 : 5;
            if (click.isRightClick()) step = -step;
            int current = getConfigInt("hide-time-seconds", 20);
            setConfigInt("hide-time-seconds", Math.max(0, current + step));
            refresh[0].run();
        });
        holder.actions.put(11, (p, click) -> {
            int step = click.isShiftClick() ? 300 : 30;
            if (click.isRightClick()) step = -step;
            int current = getConfigInt("seek-time-seconds", 300);
            setConfigInt("seek-time-seconds", Math.max(0, current + step));
            refresh[0].run();
        });
        holder.actions.put(13, (p, click) -> {
            int step = click.isRightClick() ? -1 : 1;
            int current = getConfigInt("lobby.min-players", 2);
            setConfigInt("lobby.min-players", Math.max(2, current + step));
            refresh[0].run();
        });
        holder.actions.put(15, (p, click) -> {
            int step = click.isRightClick() ? -1 : 1;
            int current = getConfigInt("lobby.max-players", 0);
            setConfigInt("lobby.max-players", Math.max(0, current + step));
            refresh[0].run();
        });
        holder.actions.put(19, (p, click) -> {
            toggleConfigBoolean("hotcold.enabled");
            refresh[0].run();
        });
        holder.actions.put(20, (p, click) -> {
            int step = click.isShiftClick() ? 300 : 30;
            if (click.isRightClick()) step = -step;
            int current = getConfigInt("hotcold.warning-seconds", 300);
            setConfigInt("hotcold.warning-seconds", Math.max(0, current + step));
            refresh[0].run();
        });
        holder.actions.put(22, (p, click) -> {
            toggleConfigBoolean("decoys.auto-whistle.enabled");
            refresh[0].run();
        });
        holder.actions.put(23, (p, click) -> {
            int step = click.isShiftClick() ? 60 : 10;
            if (click.isRightClick()) step = -step;
            int current = getConfigInt("decoys.auto-whistle.interval-seconds", 60);
            setConfigInt("decoys.auto-whistle.interval-seconds", Math.max(5, current + step));
            refresh[0].run();
        });
        holder.actions.put(28, (p, click) -> {
            int step = click.isShiftClick() ? 5 : 1;
            if (click.isRightClick()) step = -step;
            int current = getConfigInt("decoys.firework-count", 8);
            setConfigInt("decoys.firework-count", Math.max(0, current + step));
            refresh[0].run();
        });
        holder.actions.put(29, (p, click) -> {
            int step = click.isShiftClick() ? 5 : 1;
            if (click.isRightClick()) step = -step;
            int current = getConfigInt("decoys.whistle-count", 3);
            setConfigInt("decoys.whistle-count", Math.max(0, current + step));
            refresh[0].run();
        });

        inv.setItem(35, item(Material.ARROW, ChatColor.GRAY + "Retour"));
        holder.actions.put(35, (p, click) -> openMain(p));

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
