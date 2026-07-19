package fr.merci.cachecache;

import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Firework;
import org.bukkit.entity.Player;
import org.bukkit.entity.Snowball;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.*;

public class GameManager {

    public enum State { WAITING, LOBBY, HIDING, SEEKING }

    // Mode de jeu choisi au lancement de la partie
    public enum Mode { PROP_HUNT, HIDE_AND_SEEK }

    // Distance max (en blocs) que le pistolet transformeur peut viser
    private static final int TRANSFORMER_RANGE = 20;

    // Temps d'immobilité (en secondes) avant qu'un déguisement soit considéré "figé"
    private static final int FREEZE_DELAY_SECONDS = 3;

    // Dernier slot de la barre d'objets (index 8) : jamais rempli par giveHiderKit
    // (qui utilise au maximum les slots 0 à 3), donc toujours sûr comme "main vide".
    private static final int EMPTY_HAND_SLOT = 8;

    // Paliers de taille par défaut (utilisés si "resize.sizes" est vide/absent dans le config.yml)
    private static final float[] DEFAULT_SIZES = {0.1f, 0.2f, 0.3f, 0.4f, 0.5f, 0.75f, 1.0f, 1.25f, 1.5f};

    // --- Ajustement automatique de la taille en fonction de la place au-dessus (cf. autoAdjustScale) ---
    // Ne jamais rétrécir en dessous de ça, même sous un très petit espace : évite les
    // soucis vanilla de collision/étouffement aux échelles extrêmes.
    private static final float MIN_AUTO_SCALE = 0.2f;
    // Hauteur standard d'un joueur (avant application de l'échelle)
    private static final double PLAYER_HEIGHT = 1.8;
    // Marge de sécurité pour ne pas coller pile sous le plafond détecté
    private static final double CLEARANCE_MARGIN = 0.08;
    // Nombre de ticks d'espace libre consécutifs avant d'autoriser à regrandir
    private static final int GROW_BACK_STABLE_TICKS = 6;

    // Deux équipes séparées (et non une seule partagée) : Minecraft affiche un
    // joueur invisible en TRANSLUCIDE (pas totalement invisible) aux yeux des
    // autres joueurs de sa PROPRE équipe. En mettant chats et souris dans la
    // même équipe, les souris déguisées redevenaient visibles (en fondu) pour
    // les chats malgré l'effet Invisibilité. Avec deux équipes distinctes,
    // les pseudos restent masqués des deux côtés, mais les souris restent
    // pleinement invisibles pour les chats.
    private static final String HIDE_TEAM_SEEKERS = "cachecache_hide_chats";
    private static final String HIDE_TEAM_HIDERS = "cachecache_hide_souris";

    private final CacheCachePlugin plugin;
    private final NamespacedKey decoyKey;
    private final NamespacedKey transformerKey;

    private State state = State.WAITING;
    private Mode mode = Mode.PROP_HUNT;
    private final Set<UUID> seekers = new HashSet<>();
    private final Set<UUID> hiders = new HashSet<>();
    // Chats de la manche précédente, pour éviter de les retirer au sort juste après (cf. startInternal)
    private Set<UUID> lastSeekers = new HashSet<>();
    private final Set<UUID> found = new HashSet<>();
    private final Map<UUID, BlockDisplay> disguises = new HashMap<>();
    // scales = taille "voulue" par le joueur (choisie via le redimensionneur, 1.0 par défaut).
    // C'est un PLAFOND : l'ajustement automatique (cf. autoAdjustScale) peut rétrécir
    // en dessous temporairement pour passer sous un bloc bas, mais ne dépassera jamais
    // cette valeur en revenant à la normale.
    private final Map<UUID, Float> scales = new HashMap<>();
    // Taille réellement appliquée en ce moment (peut être < scales pendant un passage sous un bloc bas)
    private final Map<UUID, Float> appliedScale = new HashMap<>();
    // Plafond de taille imposé par le bloc choisi comme déguisement (Prop Hunt uniquement,
    // cf. computeDisguiseScale) : une souris déguisée en bloc plein doit rester bien plus
    // petite que ce bloc (0.5 par ex.) pour pouvoir se faufiler sous un autre bloc bas ou
    // une dalle ailleurs sur la carte. Absent (pas de déguisement en cours) = pas de plafond
    // supplémentaire au-delà de scales.
    private final Map<UUID, Float> disguiseScale = new HashMap<>();
    // Hauteur RÉELLE (0..1, fraction de la case) du bloc actuellement imité, pour
    // savoir où poser les pieds de quelqu'un qui marche dessus (cf.
    // getDisguiseSupportY) : une dalle doit porter à 0.5, pas à 1.0 comme un bloc
    // plein. À ne pas confondre avec disguiseScale, qui est le plafond de taille
    // (déjà divisé par deux) imposé à la souris déguisée elle-même.
    private final Map<UUID, Float> disguiseHeight = new HashMap<>();
    // Nombre de ticks consécutifs avec assez de place au-dessus, avant d'autoriser à regrandir
    // (évite que la taille oscille en marchant sous un plafond irrégulier)
    private final Map<UUID, Integer> clearStreak = new HashMap<>();
    private final Map<UUID, Location> frozenLoc = new HashMap<>();

    // Suivi "figé" des déguisements (Prop Hunt uniquement)
    private final Map<UUID, Location> lastMoveLoc = new HashMap<>();
    private final Map<UUID, Integer> stillSeconds = new HashMap<>();
    private final Set<UUID> frozenHiders = new HashSet<>();
    private BukkitTask freezeTask;

    // ---------------------------------------------------------------------
    // Lobby / file d'attente
    // ---------------------------------------------------------------------
    private final Set<UUID> lobbyPlayers = new LinkedHashSet<>();
    private BukkitTask lobbyTask;
    private Mode lobbyMode = Mode.PROP_HUNT;
    private int lobbyRequestedSeekers = 0;
    private int lobbyRequestedHiders = 0;
    // Point de spawn de la file d'attente (défini via /cachecache setlobby),
    // chargé/sauvegardé dans config.yml sous lobby.spawn.*. Null tant qu'aucun
    // admin ne l'a défini : dans ce cas, les joueurs restent où ils sont en
    // rejoignant la file, ils passent juste en mode Aventure.
    private Location lobbySpawn;

    // Points de spawn "map" pour la souris et pour les chats (définis via
    // /cachecache setmap souris|chats), chargés/sauvegardés dans config.yml
    // sous maps.hiders.* / maps.seekers.*. Les souris y sont téléportées dès
    // le début de la manche, les chats seulement au début de la recherche
    // (une fois la période de cache écoulée), pour qu'ils n'apparaissent pas
    // dans la zone avant que les souris n'aient fini de se cacher.
    private Location mapHidersSpawn;
    private Location mapSeekersSpawn;

    private BukkitTask mainTask;
    private boolean hotColdAnnounced = false;

    // ---------------------------------------------------------------------
    // Paramètres rechargés depuis config.yml (cf. reloadSettings())
    // ---------------------------------------------------------------------
    private int hideSeconds;
    private int seekSeconds;

    private boolean lobbyEnabled;
    private int lobbyMinPlayers;
    private int lobbyMaxPlayers;
    private int lobbyCountdownSeconds;
    private boolean lobbyAutostart;
    private Mode lobbyDefaultMode;

    private boolean hotColdEnabled;
    private int hotColdWarningSeconds;

    private float[] resizeSizes = DEFAULT_SIZES.clone();
    private boolean resizeAllowedInPropHunt;

    private boolean hideNametags;
    private boolean showTimerActionbar;

    private int decoyFireworkCount;
    private int decoyWhistleCount;
    private boolean autoWhistleEnabled;
    private int autoWhistleIntervalSeconds;

    // Tâche qui fait "suivre" les BlockDisplay de déguisement à la position
    // réelle de leur joueur (au lieu de les monter dessus, cf. bug de blocage
    // dans les murs / téléportation forcée quand on était passager du display).
    private BukkitTask followTask;

    public GameManager(CacheCachePlugin plugin) {
        this.plugin = plugin;
        this.decoyKey = new NamespacedKey(plugin, "decoy");
        this.transformerKey = new NamespacedKey(plugin, "transformer_tool");
        reloadSettings();
    }

    public void reloadSettings() {
        hideSeconds = plugin.getConfig().getInt("hide-time-seconds", 20);
        seekSeconds = plugin.getConfig().getInt("seek-time-seconds", 300);

        lobbyEnabled = plugin.getConfig().getBoolean("lobby.enabled", true);
        lobbyMinPlayers = Math.max(2, plugin.getConfig().getInt("lobby.min-players", 2));
        lobbyMaxPlayers = Math.max(0, plugin.getConfig().getInt("lobby.max-players", 0));
        lobbyCountdownSeconds = Math.max(0, plugin.getConfig().getInt("lobby.countdown-seconds", 30));
        lobbyAutostart = plugin.getConfig().getBoolean("lobby.autostart", true);
        lobbyDefaultMode = "hideandseek".equalsIgnoreCase(plugin.getConfig().getString("lobby.default-mode", "prophunt"))
                ? Mode.HIDE_AND_SEEK : Mode.PROP_HUNT;

        hotColdEnabled = plugin.getConfig().getBoolean("hotcold.enabled", true);
        hotColdWarningSeconds = Math.max(0, plugin.getConfig().getInt("hotcold.warning-seconds", 300));

        resizeAllowedInPropHunt = plugin.getConfig().getBoolean("resize.allowed-in-prophunt", false);
        resizeSizes = loadSizes();

        hideNametags = plugin.getConfig().getBoolean("hide-nametags", true);
        showTimerActionbar = plugin.getConfig().getBoolean("show-timer-actionbar", true);

        decoyFireworkCount = Math.max(0, plugin.getConfig().getInt("decoys.firework-count", 8));
        decoyWhistleCount = Math.max(0, plugin.getConfig().getInt("decoys.whistle-count", 3));
        autoWhistleEnabled = plugin.getConfig().getBoolean("decoys.auto-whistle.enabled", true);
        autoWhistleIntervalSeconds = Math.max(5, plugin.getConfig().getInt("decoys.auto-whistle.interval-seconds", 60));

        lobbySpawn = loadNamedLocation("lobby.spawn");
        mapHidersSpawn = loadNamedLocation("maps.hiders");
        mapSeekersSpawn = loadNamedLocation("maps.seekers");
    }

    private Location loadNamedLocation(String path) {
        if (!plugin.getConfig().isSet(path + ".world")) return null;
        String worldName = plugin.getConfig().getString(path + ".world");
        World world = Bukkit.getWorld(worldName);
        if (world == null) return null;
        double x = plugin.getConfig().getDouble(path + ".x");
        double y = plugin.getConfig().getDouble(path + ".y");
        double z = plugin.getConfig().getDouble(path + ".z");
        float yaw = (float) plugin.getConfig().getDouble(path + ".yaw");
        float pitch = (float) plugin.getConfig().getDouble(path + ".pitch");
        return new Location(world, x, y, z, yaw, pitch);
    }

    private void saveNamedLocation(String path, Location location) {
        plugin.getConfig().set(path + ".world", location.getWorld().getName());
        plugin.getConfig().set(path + ".x", location.getX());
        plugin.getConfig().set(path + ".y", location.getY());
        plugin.getConfig().set(path + ".z", location.getZ());
        plugin.getConfig().set(path + ".yaw", location.getYaw());
        plugin.getConfig().set(path + ".pitch", location.getPitch());
    }

    /**
     * Définit le point de la file d'attente sur la position donnée et le
     * sauvegarde dans config.yml (persiste après redémarrage du serveur).
     */
    public boolean setLobbySpawn(Location location) {
        if (location == null || location.getWorld() == null) return false;
        saveNamedLocation("lobby.spawn", location);
        plugin.saveConfig();
        this.lobbySpawn = location.clone();
        return true;
    }

    public Location getLobbySpawn() { return lobbySpawn; }

    /**
     * Définit le point de spawn "map" des souris (forHiders = true) ou des
     * chats (forHiders = false), utilisé pour les téléporter dans la zone de
     * jeu au lancement d'une manche (cf. startInternal / beginSeeking).
     */
    public boolean setMapSpawn(boolean forHiders, Location location) {
        if (location == null || location.getWorld() == null) return false;
        String path = forHiders ? "maps.hiders" : "maps.seekers";
        saveNamedLocation(path, location);
        plugin.saveConfig();
        if (forHiders) {
            this.mapHidersSpawn = location.clone();
        } else {
            this.mapSeekersSpawn = location.clone();
        }
        return true;
    }

    public Location getMapHidersSpawn() { return mapHidersSpawn; }
    public Location getMapSeekersSpawn() { return mapSeekersSpawn; }

    private float[] loadSizes() {
        List<Double> raw = plugin.getConfig().getDoubleList("resize.sizes");
        if (raw == null || raw.isEmpty()) {
            return DEFAULT_SIZES.clone();
        }
        float[] arr = new float[raw.size()];
        for (int i = 0; i < raw.size(); i++) arr[i] = raw.get(i).floatValue();
        Arrays.sort(arr);
        return arr;
    }

    public float[] getResizeSizes() { return resizeSizes.clone(); }

    /**
     * Redéfinit les paliers de taille disponibles (cf. /cachecache setsizes),
     * sauvegardés dans config.yml (resize.sizes) pour survivre à un redémarrage.
     * @return null si la liste est acceptée, sinon un message d'erreur à afficher.
     */
    public String setResizeSizes(List<Float> sizes) {
        if (sizes == null || sizes.isEmpty()) {
            return "Il faut donner au moins une taille.";
        }
        for (float s : sizes) {
            if (s <= 0f || s > 10f) {
                return "Chaque taille doit être strictement positive (et raisonnable, max 10).";
            }
        }
        List<Double> toSave = new ArrayList<>();
        for (float s : sizes) toSave.add((double) s);
        plugin.getConfig().set("resize.sizes", toSave);
        plugin.saveConfig();
        resizeSizes = loadSizes();
        return null;
    }

    public NamespacedKey getDecoyKey() { return decoyKey; }
    public NamespacedKey getTransformerKey() { return transformerKey; }
    public State getState() { return state; }
    public Mode getMode() { return mode; }
    public boolean isHider(UUID uuid) { return hiders.contains(uuid) && !found.contains(uuid); }
    public boolean isSeeker(UUID uuid) { return seekers.contains(uuid); }
    public int getSeekerCount() { return seekers.size(); }
    public int getHiderCount() { return hiders.size(); }
    public int getLobbyCount() { return lobbyPlayers.size(); }

    public boolean isTransformerTool(ItemStack item) {
        if (item == null || item.getType() == Material.AIR || !item.hasItemMeta()) return false;
        return item.getItemMeta().getPersistentDataContainer().has(transformerKey, PersistentDataType.BYTE);
    }

    // --- Helper attributs (Registry, résistant aux changements de version) ---
    private Attribute attribute(String key) {
        Attribute a = Registry.ATTRIBUTE.get(NamespacedKey.minecraft(key));
        if (a == null) throw new IllegalStateException("Attribut Minecraft introuvable : " + key);
        return a;
    }

    private void sendActionBar(Player player, String legacyText) {
        player.sendActionBar(LegacyComponentSerializer.legacySection().deserialize(legacyText));
    }

    private String formatTime(int totalSeconds) {
        int m = Math.max(0, totalSeconds) / 60;
        int s = Math.max(0, totalSeconds) % 60;
        return m + ":" + (s < 10 ? "0" + s : String.valueOf(s));
    }

    // ---------------------------------------------------------------------
    // Pseudos masqués (équipe scoreboard, cf. option "hide-nametags")
    // ---------------------------------------------------------------------

    private Team getOrCreateTeam(String name) {
        Scoreboard board = Bukkit.getScoreboardManager().getMainScoreboard();
        Team team = board.getTeam(name);
        if (team == null) {
            team = board.registerNewTeam(name);
        }
        team.setOption(Team.Option.NAME_TAG_VISIBILITY, Team.OptionStatus.NEVER);
        return team;
    }

    private void applyNametagHiding(Collection<Player> seekerPlayers, Collection<Player> hiderPlayers) {
        if (!hideNametags) return;
        Team seekerTeam = getOrCreateTeam(HIDE_TEAM_SEEKERS);
        for (Player p : seekerPlayers) {
            seekerTeam.addEntry(p.getName());
        }
        Team hiderTeam = getOrCreateTeam(HIDE_TEAM_HIDERS);
        for (Player p : hiderPlayers) {
            hiderTeam.addEntry(p.getName());
        }
    }

    private void clearNametagHiding() {
        Scoreboard board = Bukkit.getScoreboardManager().getMainScoreboard();
        for (String teamName : new String[] { HIDE_TEAM_SEEKERS, HIDE_TEAM_HIDERS }) {
            Team team = board.getTeam(teamName);
            if (team == null) continue;
            for (String entry : new ArrayList<>(team.getEntries())) {
                team.removeEntry(entry);
            }
        }
    }

    // ---------------------------------------------------------------------
    // Lobby / file d'attente
    // ---------------------------------------------------------------------

    /**
     * Un joueur rejoint la file d'attente. Le lobby s'ouvre automatiquement
     * si aucune partie/lobby n'est en cours.
     */
    public void join(Player player) {
        if (!lobbyEnabled) {
            player.sendMessage(ChatColor.RED + "La file d'attente est désactivée sur ce serveur.");
            return;
        }
        if (state == State.HIDING || state == State.SEEKING) {
            player.sendMessage(ChatColor.RED + "Une partie est déjà en cours, attends la fin !");
            return;
        }
        if (lobbyPlayers.contains(player.getUniqueId())) {
            player.sendMessage(ChatColor.GRAY + "Tu es déjà dans la file d'attente.");
            return;
        }
        if (lobbyMaxPlayers > 0 && lobbyPlayers.size() >= lobbyMaxPlayers) {
            player.sendMessage(ChatColor.RED + "La file d'attente est complète (" + lobbyPlayers.size() + "/" + lobbyMaxPlayers + ").");
            return;
        }
        if (state == State.WAITING) {
            state = State.LOBBY;
            lobbyMode = lobbyDefaultMode;
            lobbyRequestedSeekers = 0;
            lobbyRequestedHiders = 0;
        }
        lobbyPlayers.add(player.getUniqueId());
        // En file d'attente, les joueurs passent en mode Aventure (pas de casse/pose
        // de blocs intempestive) et sont téléportés au point de lobby s'il a été
        // défini via /cachecache setlobby.
        player.setGameMode(GameMode.ADVENTURE);
        if (lobbySpawn != null) {
            player.teleport(lobbySpawn);
        }
        Bukkit.broadcastMessage(ChatColor.AQUA + player.getName() + ChatColor.GRAY
                + " a rejoint la file d'attente (" + lobbyPlayers.size() + "/" + lobbyMinPlayers + ")");
        maybeStartLobbyCountdown();
    }

    /** Un joueur quitte la file d'attente. */
    public void leave(Player player) {
        if (state != State.LOBBY || !lobbyPlayers.remove(player.getUniqueId())) {
            player.sendMessage(ChatColor.RED + "Tu n'es pas dans la file d'attente.");
            return;
        }
        Bukkit.broadcastMessage(ChatColor.GRAY + player.getName() + " a quitté la file d'attente ("
                + lobbyPlayers.size() + "/" + lobbyMinPlayers + ")");
        player.setGameMode(GameMode.SURVIVAL);
        cancelCountdownIfUnderfilled();
        if (lobbyPlayers.isEmpty()) {
            state = State.WAITING;
        }
    }

    /** Retire silencieusement un joueur de la file (déconnexion). */
    public void removeFromLobbyQuietly(UUID id) {
        if (state != State.LOBBY || !lobbyPlayers.remove(id)) return;
        cancelCountdownIfUnderfilled();
        if (lobbyPlayers.isEmpty()) {
            state = State.WAITING;
        }
    }

    private void cancelCountdownIfUnderfilled() {
        if (lobbyTask != null && lobbyPlayers.size() < lobbyMinPlayers) {
            lobbyTask.cancel();
            lobbyTask = null;
            Bukkit.broadcastMessage(ChatColor.RED + "Plus assez de joueurs, le lancement automatique est annulé.");
        }
    }

    /** Permet à un administrateur de préconfigurer le mode/les effectifs du prochain lancement automatique. */
    public void configureLobby(CommandSender sender, Mode chosenMode, int requestedSeekers, int requestedHiders) {
        if (state == State.HIDING || state == State.SEEKING) {
            sender.sendMessage(ChatColor.RED + "Une partie est déjà en cours !");
            return;
        }
        if (state == State.WAITING) state = State.LOBBY;
        lobbyMode = chosenMode;
        lobbyRequestedSeekers = requestedSeekers;
        lobbyRequestedHiders = requestedHiders;
        sender.sendMessage(ChatColor.GREEN + "File d'attente configurée : " + (chosenMode == Mode.PROP_HUNT ? "Prop Hunt" : "Cache-cache classique")
                + ", chats=" + (requestedSeekers > 0 ? requestedSeekers : "auto")
                + ", souris=" + (requestedHiders > 0 ? requestedHiders : "auto"));
        maybeStartLobbyCountdown();
    }

    private void maybeStartLobbyCountdown() {
        if (!lobbyAutostart || lobbyTask != null) return;
        if (lobbyPlayers.size() < lobbyMinPlayers) return;
        lobbyTask = new BukkitRunnable() {
            int left = lobbyCountdownSeconds;
            @Override
            public void run() {
                if (state != State.LOBBY) { cancel(); lobbyTask = null; return; }
                if (lobbyPlayers.size() < lobbyMinPlayers) {
                    Bukkit.broadcastMessage(ChatColor.RED + "Plus assez de joueurs, lancement automatique annulé.");
                    cancel();
                    lobbyTask = null;
                    return;
                }
                if (left <= 0) {
                    cancel();
                    lobbyTask = null;
                    startFromLobby();
                    return;
                }
                for (UUID id : lobbyPlayers) {
                    Player p = Bukkit.getPlayer(id);
                    if (p != null) sendActionBar(p, ChatColor.GOLD + "Départ dans " + left + "s...");
                }
                if (left <= 5 || left % 10 == 0) {
                    Bukkit.broadcastMessage(ChatColor.GOLD + "La partie démarre dans " + left
                            + " seconde" + (left > 1 ? "s" : "") + " !");
                }
                left--;
            }
        }.runTaskTimer(plugin, 0L, 20L);
    }

    private void startFromLobby() {
        startInternal(Bukkit.getConsoleSender(), lobbyMode, lobbyRequestedSeekers, lobbyRequestedHiders);
    }

    // ---------------------------------------------------------------------
    // Démarrage / fin de partie
    // ---------------------------------------------------------------------

    /**
     * Lance une partie.
     * @param requestedSeekers nombre de chats souhaité (0 ou moins = valeur par défaut : 1)
     * @param requestedHiders nombre max de souris souhaité (0 ou moins = pas de limite : tous les joueurs restants)
     */
    public void start(CommandSender sender, Mode chosenMode, int requestedSeekers, int requestedHiders) {
        if (state == State.HIDING || state == State.SEEKING) {
            sender.sendMessage(ChatColor.RED + "Une partie est déjà en cours !");
            return;
        }
        if (lobbyTask != null) {
            lobbyTask.cancel();
            lobbyTask = null;
        }
        startInternal(sender, chosenMode, requestedSeekers, requestedHiders);
    }

    private void startInternal(CommandSender sender, Mode chosenMode, int requestedSeekers, int requestedHiders) {
        List<Player> online;
        if (state == State.LOBBY && !lobbyPlayers.isEmpty()) {
            online = new ArrayList<>();
            for (UUID id : lobbyPlayers) {
                Player p = Bukkit.getPlayer(id);
                if (p != null) online.add(p);
            }
        } else {
            online = new ArrayList<>(Bukkit.getOnlinePlayers());
        }
        lobbyPlayers.clear();

        if (online.size() < 2) {
            sender.sendMessage(ChatColor.RED + "Il faut au moins 2 joueurs pour lancer une partie !");
            state = State.WAITING;
            return;
        }

        // Sortie du mode Aventure du lobby : on s'assure que tout le monde y
        // est bien (utile si la partie est lancée sans passer par la file
        // d'attente). Le jeu se joue entièrement en mode Aventure (pas de
        // casse/pose de blocs), pas en Survie.
        for (Player p : online) {
            p.setGameMode(GameMode.ADVENTURE);
        }

        Collections.shuffle(online);
        int total = online.size();

        // Le nombre de chats est borné entre 1 et (total - 1) pour garder au moins une souris
        int seekerCount = requestedSeekers > 0 ? requestedSeekers : 1;
        seekerCount = Math.max(1, Math.min(seekerCount, total - 1));

        // On évite de retomber sur les mêmes chats que la manche précédente
        // quand une alternative existe : après le shuffle ci-dessus (qui reste
        // la source du hasard), on repousse juste les anciens chats en fin de
        // liste pour qu'ils passent en priorité souris cette fois. À 2 joueurs,
        // ça garantit une vraie alternance (sinon un simple pile-ou-face peut
        // retomber plusieurs fois de suite sur la même personne et donner
        // l'impression que le tirage ne fonctionne pas).
        if (!lastSeekers.isEmpty() && total > seekerCount) {
            online.sort((a, b) -> {
                boolean aWasSeeker = lastSeekers.contains(a.getUniqueId());
                boolean bWasSeeker = lastSeekers.contains(b.getUniqueId());
                if (aWasSeeker == bWasSeeker) return 0;
                return aWasSeeker ? 1 : -1;
            });
        }

        int remainingAfterSeekers = total - seekerCount;
        // Le nombre de souris est borné par ce qu'il reste de joueurs ; sans précision, tout le monde joue
        int hiderCount = requestedHiders > 0 ? Math.min(requestedHiders, remainingAfterSeekers) : remainingAfterSeekers;
        hiderCount = Math.max(1, hiderCount);

        this.mode = chosenMode;
        hotColdAnnounced = false;
        seekers.clear();
        hiders.clear();
        found.clear();
        for (BlockDisplay display : disguises.values()) {
            if (display.isValid()) display.remove();
        }
        disguises.clear();
        if (followTask != null) {
            followTask.cancel();
            followTask = null;
        }
        frozenLoc.clear();
        scales.clear();
        disguiseScale.clear();
        disguiseHeight.clear();
        appliedScale.clear();
        lastMoveLoc.clear();
        stillSeconds.clear();
        frozenHiders.clear();
        clearNametagHiding();

        List<Player> chosenSeekers = online.subList(0, seekerCount);
        List<Player> chosenHiders = online.subList(seekerCount, seekerCount + hiderCount);
        lastSeekers = new HashSet<>();
        for (Player s : chosenSeekers) lastSeekers.add(s.getUniqueId());
        List<Player> bystanders = online.subList(seekerCount + hiderCount, total);

        int freezeTicks = hideSeconds * 20 + 20;
        for (Player s : chosenSeekers) {
            seekers.add(s.getUniqueId());
            giveSeekerKit(s);
            frozenLoc.put(s.getUniqueId(), s.getLocation());
            s.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, freezeTicks, 1, false, false));
            s.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, freezeTicks, 250, false, false));
            s.addPotionEffect(new PotionEffect(PotionEffectType.JUMP_BOOST, freezeTicks, -10, false, false));
            enableDoubleJump(s);
        }
        for (Player h : chosenHiders) {
            hiders.add(h.getUniqueId());
            giveHiderKit(h);
            enableDoubleJump(h);
            // Les souris sont téléportées dans la map dès le début de la manche.
            if (mapHidersSpawn != null) {
                h.teleport(mapHidersSpawn);
            }
        }
        for (Player b : bystanders) {
            b.sendMessage(ChatColor.GRAY + "Trop de joueurs pour cette manche, tu regardes cette fois-ci !");
        }

        applyNametagHiding(chosenSeekers, chosenHiders);

        String modeLabel = mode == Mode.PROP_HUNT ? "Prop Hunt" : "Cache-cache classique";
        String seekerNames = chosenSeekers.stream().map(Player::getName).reduce((a, b) -> a + ", " + b).orElse("");
        Bukkit.broadcastMessage(ChatColor.GOLD + "=== Cache-cache : " + modeLabel + " ===");
        Bukkit.broadcastMessage(ChatColor.YELLOW + seekerNames + ChatColor.GRAY
                + (chosenSeekers.size() > 1 ? " sont les chats ! " : " est le chat ! ")
                + "Les souris ont " + hideSeconds + " secondes pour se cacher.");
        for (Player p : chosenHiders) {
            if (mode == Mode.PROP_HUNT) {
                p.sendMessage(ChatColor.GREEN + "Utilise le " + ChatColor.AQUA + "Pistolet Transformeur"
                        + ChatColor.GREEN + " (clic droit en visant un bloc) pour te déguiser, et re-clic droit pour redevenir toi-même.");
                p.sendMessage(ChatColor.GRAY + "Reste immobile " + FREEZE_DELAY_SECONDS + " secondes pour devenir un vrai bloc figé !");
            } else {
                p.sendMessage(ChatColor.GREEN + "Cache-toi ! Tu restes un joueur normal dans ce mode.");
            }
            if (mode != Mode.PROP_HUNT || resizeAllowedInPropHunt) {
                p.sendMessage(ChatColor.GREEN + "Le redimensionneur change ta taille, le feu d'artifice et le sifflet sont des leurres.");
            } else {
                p.sendMessage(ChatColor.GREEN + "Le feu d'artifice et le sifflet sont des leurres.");
            }
        }

        state = State.HIDING;
        if (mode == Mode.PROP_HUNT) startFreezeTracking();
        runCountdown(hideSeconds, this::beginSeeking);
    }

    private void beginSeeking() {
        if (state != State.HIDING) return;
        state = State.SEEKING;
        frozenLoc.clear();
        // Les chats sont téléportés dans la map seulement maintenant, une fois
        // la période de cache écoulée (pour ne pas voir où les souris se planquent).
        if (mapSeekersSpawn != null) {
            for (UUID seekerId : seekers) {
                Player seekerPlayer = Bukkit.getPlayer(seekerId);
                if (seekerPlayer != null) seekerPlayer.teleport(mapSeekersSpawn);
            }
        }
        String names = seekers.stream()
                .map(Bukkit::getPlayer)
                .filter(Objects::nonNull)
                .map(Player::getName)
                .reduce((a, b) -> a + ", " + b).orElse("Les chats");
        Bukkit.broadcastMessage(ChatColor.RED + "C'est parti, " + names + " part" + (seekers.size() > 1 ? "ent" : "") + " à la recherche !");
        runSeekPhase();
    }

    private void runCountdown(int seconds, Runnable onEnd) {
        mainTask = new BukkitRunnable() {
            int left = seconds;
            @Override
            public void run() {
                if (left <= 0) {
                    cancel();
                    onEnd.run();
                    return;
                }
                if (left <= 5 || left % 10 == 0) {
                    Bukkit.broadcastMessage(ChatColor.GRAY + "" + left + "...");
                }
                left--;
            }
        }.runTaskTimer(plugin, 0L, 20L);
    }

    private void runSeekPhase() {
        mainTask = new BukkitRunnable() {
            int left = seekSeconds;
            @Override
            public void run() {
                if (state != State.SEEKING) { cancel(); return; }
                if (left <= 0) {
                    cancel();
                    endGame(false);
                    return;
                }

                boolean hotColdActive = hotColdEnabled && left <= hotColdWarningSeconds;
                if (hotColdActive && !hotColdAnnounced) {
                    hotColdAnnounced = true;
                    Bukkit.broadcastMessage(ChatColor.LIGHT_PURPLE + "Le radar chaud/froid des chats s'active !");
                }

                // Toutes les X secondes (config decoys.auto-whistle), un coup de
                // sifflet automatique retentit à la position réelle de chaque
                // souris encore en jeu (sans qu'elle ait besoin de le lancer).
                if (autoWhistleEnabled && left % autoWhistleIntervalSeconds == 0) {
                    autoWhistlePulse();
                }

                String timeStr = formatTime(left);

                if (showTimerActionbar) {
                    for (UUID hiderId : hiders) {
                        if (found.contains(hiderId)) continue;
                        Player h = Bukkit.getPlayer(hiderId);
                        if (h != null) sendActionBar(h, ChatColor.GREEN + "Temps restant : " + timeStr);
                    }
                }

                for (UUID seekerId : seekers) {
                    Player seekerPlayer = Bukkit.getPlayer(seekerId);
                    if (seekerPlayer == null) continue;

                    if (!hotColdActive) {
                        if (showTimerActionbar) {
                            sendActionBar(seekerPlayer, ChatColor.GRAY + "Temps restant : " + timeStr);
                        }
                        continue;
                    }

                    double nearest = Double.MAX_VALUE;
                    for (UUID id : hiders) {
                        if (found.contains(id)) continue;
                        Player h = Bukkit.getPlayer(id);
                        if (h == null || !h.getWorld().equals(seekerPlayer.getWorld())) continue;
                        double d = h.getLocation().distance(seekerPlayer.getLocation());
                        if (d < nearest) nearest = d;
                    }
                    String msg;
                    if (nearest < 5) msg = ChatColor.RED + "Ça brûle !!!";
                    else if (nearest < 12) msg = ChatColor.GOLD + "Ça chauffe...";
                    else if (nearest < 25) msg = ChatColor.YELLOW + "Tiède";
                    else msg = ChatColor.AQUA + "Tu gèles.";
                    sendActionBar(seekerPlayer, msg);
                }
                left--;
            }
        }.runTaskTimer(plugin, 0L, 20L);
    }

    // ---------------------------------------------------------------------
    // Suivi "figé" : un déguisement immobile depuis FREEZE_DELAY_SECONDS
    // devient indétectable comme un vrai bloc ; bouger le "dégèle" aussitôt.
    // ---------------------------------------------------------------------

    private void startFreezeTracking() {
        freezeTask = new BukkitRunnable() {
            @Override
            public void run() {
                if (state == State.WAITING) { cancel(); return; }
                for (UUID id : hiders) {
                    if (found.contains(id) || !isDisguised(id)) continue;
                    Player p = Bukkit.getPlayer(id);
                    if (p == null) continue;
                    Location current = p.getLocation();
                    Location last = lastMoveLoc.get(id);
                    boolean moved = last == null
                            || last.distanceSquared(current) > 0.01
                            || !Objects.equals(last.getWorld(), current.getWorld());
                    if (moved) {
                        lastMoveLoc.put(id, current);
                        stillSeconds.put(id, 0);
                        if (frozenHiders.remove(id)) {
                            sendActionBar(p, ChatColor.YELLOW + "Tu bouges, tu n'es plus figé...");
                        }
                    } else {
                        int seconds = stillSeconds.getOrDefault(id, 0) + 1;
                        stillSeconds.put(id, seconds);
                        if (seconds >= FREEZE_DELAY_SECONDS && frozenHiders.add(id)) {
                            sendActionBar(p, ChatColor.AQUA + "Figé comme un vrai bloc !");
                        }
                    }
                }
            }
        }.runTaskTimer(plugin, 20L, 20L);
    }

    private void stopFreezeTracking() {
        if (freezeTask != null) {
            freezeTask.cancel();
            freezeTask = null;
        }
        lastMoveLoc.clear();
        stillSeconds.clear();
        frozenHiders.clear();
    }

    public boolean isFrozen(UUID id) { return frozenHiders.contains(id); }

    private void endGame(boolean seekerWins) {
        stopFreezeTracking();
        if (mainTask != null) mainTask.cancel();
        if (seekerWins) {
            Bukkit.broadcastMessage(ChatColor.GOLD + "Le chat a gagné, tout le monde a été trouvé !");
        } else {
            Bukkit.broadcastMessage(ChatColor.GOLD + "Le temps est écoulé, les souris gagnent !");
        }
        for (UUID id : hiders) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) {
                undisguise(p);
                applyScale(p, 1.0f);
                disableDoubleJump(p);
                // Vide l'inventaire à chaque fin de partie : les objets du kit
                // (redimensionneur, leurres, pistolet transformeur...) ne doivent
                // pas rester dans les poches d'une souris une fois la manche finie.
                p.getInventory().clear();
                p.setGameMode(GameMode.ADVENTURE);
                // Retour au lobby à la fin de la partie.
                if (lobbySpawn != null) p.teleport(lobbySpawn);
            }
        }
        for (UUID id : seekers) {
            Player seekerPlayer = Bukkit.getPlayer(id);
            if (seekerPlayer != null) {
                seekerPlayer.removePotionEffect(PotionEffectType.BLINDNESS);
                seekerPlayer.removePotionEffect(PotionEffectType.SLOWNESS);
                seekerPlayer.removePotionEffect(PotionEffectType.JUMP_BOOST);
                disableDoubleJump(seekerPlayer);
                // Même chose côté chat : épée, arc et flèches sont retirés en fin de manche.
                seekerPlayer.getInventory().clear();
                seekerPlayer.setGameMode(GameMode.ADVENTURE);
                // Retour au lobby à la fin de la partie.
                if (lobbySpawn != null) seekerPlayer.teleport(lobbySpawn);
            }
        }
        clearNametagHiding();
        hiders.clear();
        found.clear();
        frozenLoc.clear();
        scales.clear();
        seekers.clear();
        state = State.WAITING;
    }

    public void stop(CommandSender sender) {
        if (state == State.WAITING) {
            sender.sendMessage(ChatColor.RED + "Aucune partie en cours.");
            return;
        }
        if (state == State.LOBBY) {
            cancelLobby();
            sender.sendMessage(ChatColor.GRAY + "File d'attente annulée.");
            return;
        }
        endGame(false);
        sender.sendMessage(ChatColor.GRAY + "Partie arrêtée.");
    }

    private void cancelLobby() {
        if (lobbyTask != null) {
            lobbyTask.cancel();
            lobbyTask = null;
        }
        Bukkit.broadcastMessage(ChatColor.GRAY + "La file d'attente a été annulée.");
        lobbyPlayers.clear();
        state = State.WAITING;
    }

    public void forceStop() {
        if (state == State.LOBBY) cancelLobby();
        else if (state != State.WAITING) endGame(false);
    }

    public Location getFrozenLocation(UUID id) {
        return frozenLoc.get(id);
    }

    public void markFound(Player hider) {
        UUID id = hider.getUniqueId();
        if (!hiders.contains(id) || found.contains(id)) return;
        found.add(id);
        undisguise(hider);
        applyScale(hider, 1.0f);
        disableDoubleJump(hider);
        hider.setGameMode(GameMode.SPECTATOR);
        Bukkit.broadcastMessage(ChatColor.YELLOW + hider.getName() + ChatColor.GRAY
                + " a été trouvé ! (" + found.size() + "/" + hiders.size() + ")");
        if (found.size() >= hiders.size()) {
            endGame(true);
        }
    }

    // ---------------------------------------------------------------------
    // Déguisement en bloc
    // ---------------------------------------------------------------------

    public void disguise(Player player, Block target) {
        disguiseInternal(player, target.getBlockData(), computeDisguiseHeight(target));
    }

    /**
     * Se déguise directement à partir d'un Material choisi dans le menu (cf.
     * GameMenu#openBlockPicker), sans avoir besoin de viser un bloc réellement
     * posé dans le monde. La taille (cf. estimateDisguiseHeight) est alors
     * estimée à partir du nom du bloc plutôt que de sa vraie bounding box,
     * puisqu'aucun bloc réel n'existe à cet endroit précis.
     */
    public void disguiseAsMaterial(Player player, Material material) {
        if (material == null || !material.isBlock() || !material.isItem()) {
            player.sendMessage(ChatColor.RED + "Bloc invalide.");
            return;
        }
        disguiseInternal(player, material.createBlockData(), estimateDisguiseHeight(material));
        player.sendMessage(ChatColor.GREEN + "Transformé en "
                + material.name().toLowerCase().replace('_', ' ') + " !");
    }

    private void disguiseInternal(Player player, BlockData data, float height) {
        UUID id = player.getUniqueId();
        undisguise(player);

        // Le déguisement est TOUJOURS un bloc à taille normale (échelle 1) : ce
        // n'est jamais lui qui rétrécit. Ce qui rétrécit, c'est la hitbox du
        // joueur (cf. cycleSize/applyScale), pour pouvoir se faufiler sous un
        // bloc de hauteur ou une dalle sans pour autant traverser les blocs.
        // On aligne donc toujours le bloc sur la grille (coordonnées entières)
        // et on verrouille yaw/pitch à 0, comme un vrai bloc posé.
        Location playerLoc = player.getLocation();
        Location spawnLoc = new Location(
                playerLoc.getWorld(),
                Math.floor(playerLoc.getX()),
                Math.floor(playerLoc.getY()),
                Math.floor(playerLoc.getZ()),
                0f, 0f
        );

        BlockDisplay display = player.getWorld().spawn(spawnLoc, BlockDisplay.class, d -> {
            d.setBlock(data);
            d.setTransformation(buildTransformation(1.0f));
        });
        // Important : on NE monte PLUS le joueur sur le display (addPassenger).
        // Le monter dessus verrouillait sa position sur celle (arrondie à la
        // grille) du display : s'il se trouvait près d'un mur, son corps se
        // retrouvait fusionné avec un bloc solide (dégâts d'étouffement en
        // boucle) et il était sans cesse "recollé" à cet endroit, impossible
        // à déplacer où on voulait. À la place, le display suit juste la
        // position du joueur à chaque tick (cf. startFollowTask), et le
        // joueur garde un déplacement 100% normal et libre.
        disguises.put(id, display);
        player.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, Integer.MAX_VALUE, 0, false, false));
        // Note : l'effet Invisibilité (contrairement à hidePlayer) laisse l'entité
        // "connue" du client des chats, donc les coups d'épée/flèche continuent de
        // toucher normalement. Le seul défaut de l'Invisibilité vanilla, c'est
        // qu'elle n'occulte pas l'objet tenu en main : on gère ça séparément en
        // gardant la main de la souris vide par défaut (cf. giveHiderKit /
        // snapHandToEmpty), plutôt qu'en cachant toute l'entité.
        lastMoveLoc.put(id, player.getLocation());
        stillSeconds.put(id, 0);
        frozenHiders.remove(id);

        // Hauteur réelle du bloc imité (0..1), utilisée pour savoir où poser les
        // pieds de quelqu'un qui marche dessus (cf. getDisguiseSupportY) : une
        // dalle (0.5) ou un tapis (0.0625) ne doit pas porter à la même hauteur
        // qu'un bloc plein (1.0).
        disguiseHeight.put(id, height);

        // La souris déguisée doit rester bien plus petite que le bloc qu'elle imite
        // (le bloc du déguisement, lui, reste toujours à échelle 1, cf. plus haut) :
        // ça lui permet de se faufiler sous un autre bloc bas ou une dalle ailleurs
        // sur la carte. Un bloc plein donne donc une taille 0.5, une dalle 0.25, etc.
        float blockCeiling = Math.max((float) (Math.min(height, 1.0) * 0.5), MIN_AUTO_SCALE);
        disguiseScale.put(id, blockCeiling);
        float initial = Math.min(scales.getOrDefault(id, 1.0f), blockCeiling);
        applyScale(player, initial);
        appliedScale.put(id, initial);
        clearStreak.put(id, 0);
        startFollowTask();
    }

    /**
     * Hauteur réelle (0..1, fraction de la case) du bloc visé, mesurée à partir
     * de sa vraie bounding box dans le monde. Sert à la fois à calculer le
     * plafond de taille de la souris déguisée (cf. disguiseInternal) et la
     * hauteur à laquelle quelqu'un doit se tenir en marchant dessus (cf.
     * getDisguiseSupportY).
     */
    private float computeDisguiseHeight(Block target) {
        double height = 1.0;
        try {
            BoundingBox box = target.getBoundingBox();
            if (box != null && box.getVolume() > 0) {
                height = box.getHeight();
            }
        } catch (Exception ignored) {
            // Certains blocs (data invalides, versions différentes...) peuvent lever :
            // on retombe alors sur l'hypothèse "bloc plein" (hauteur 1) par sécurité.
        }
        return (float) Math.max(0.0, Math.min(height, 1.0));
    }

    /**
     * Même idée que computeDisguiseHeight, mais à partir du nom du Material seul
     * (utilisé pour le menu de sélection, cf. disguiseAsMaterial), puisqu'aucun
     * bloc réel n'est disponible dans le monde pour mesurer sa vraie bounding box.
     */
    private float estimateDisguiseHeight(Material material) {
        String name = material.name();
        if (name.equals("SNOW") || name.contains("CARPET")) {
            return 0.0625f;
        } else if (name.contains("PRESSURE_PLATE")) {
            return 0.125f;
        } else if (name.contains("SLAB")) {
            return 0.5f;
        } else if (name.contains("BED")) {
            return 0.5625f;
        }
        return 1.0f;
    }

    public void undisguise(Player player) {
        UUID id = player.getUniqueId();
        BlockDisplay display = disguises.remove(id);
        if (display != null) {
            display.remove();
        }
        player.removePotionEffect(PotionEffectType.INVISIBILITY);
        lastMoveLoc.remove(id);
        stillSeconds.remove(id);
        frozenHiders.remove(id);
        disguiseScale.remove(id);
        disguiseHeight.remove(id);
        // On rend sa taille normale au joueur en sortant du déguisement : plus
        // besoin d'être rétréci pour se faufiler une fois qu'il redevient lui-même.
        float desired = scales.getOrDefault(id, 1.0f);
        if (appliedScale.getOrDefault(id, desired) != desired) {
            applyScale(player, desired);
        }
        appliedScale.remove(id);
        clearStreak.remove(id);
        stopFollowTaskIfEmpty();
    }

    /**
     * Repositionne la main de la souris sur un slot garanti vide (le tout dernier
     * slot de la barre, jamais utilisé par le kit). Appelé juste après avoir donné
     * le kit, et après chaque utilisation d'un objet, pour qu'un chat ne voie
     * jamais d'objet flotter dans la main d'une souris déguisée/invisible — seul
     * défaut restant de l'effet Invisibilité vanilla, qui ne masque pas l'objet
     * tenu en main.
     */
    public void snapHandToEmpty(Player player) {
        player.getInventory().setHeldItemSlot(EMPTY_HAND_SLOT);
    }

    // ---------------------------------------------------------------------
    // Suivi visuel des déguisements (le BlockDisplay suit le joueur, sans
    // que le joueur ne soit un passager)
    // ---------------------------------------------------------------------

    private void startFollowTask() {
        if (followTask != null) return;
        followTask = new BukkitRunnable() {
            @Override
            public void run() {
                if (disguises.isEmpty()) return;
                for (Map.Entry<UUID, BlockDisplay> entry : disguises.entrySet()) {
                    BlockDisplay display = entry.getValue();
                    if (display == null || !display.isValid()) continue;
                    Player p = Bukkit.getPlayer(entry.getKey());
                    if (p == null) continue;
                    // Le bloc du déguisement reste toujours à taille normale : il se
                    // cale donc toujours sur la grille (coordonnées entières), qu'importe
                    // la taille (hitbox) réelle du joueur en dessous.
                    Location loc = p.getLocation();
                    Location target = new Location(loc.getWorld(),
                            Math.floor(loc.getX()), Math.floor(loc.getY()), Math.floor(loc.getZ()), 0f, 0f);
                    Location current = display.getLocation();
                    if (!Objects.equals(current.getWorld(), target.getWorld())
                            || current.getBlockX() != target.getBlockX()
                            || current.getBlockY() != target.getBlockY()
                            || current.getBlockZ() != target.getBlockZ()) {
                        display.teleport(target);
                    }
                    autoAdjustScale(p);
                }
            }
        }.runTaskTimer(plugin, 1L, 1L);
    }

    /**
     * Ajuste automatiquement la taille (hitbox) de la souris déguisée en
     * fonction de la place réellement disponible au-dessus d'elle : rétrécit
     * tout de suite dès qu'un bloc bas ou une dalle approche (sécurité anti-
     * coincement), et ne regrandit vers sa taille "voulue" (cf. scales,
     * réglée par le redimensionneur) qu'une fois la place dégagée depuis
     * quelques ticks d'affilée, pour éviter que la taille n'oscille en
     * marchant sous un plafond irrégulier.
     */
    private void autoAdjustScale(Player player) {
        UUID id = player.getUniqueId();
        float desired = Math.min(scales.getOrDefault(id, 1.0f), disguiseScale.getOrDefault(id, 1.0f));
        float maxFit = computeMaxFittingScale(player, desired);
        float appliedNow = appliedScale.getOrDefault(id, desired);

        if (maxFit < appliedNow - 0.02f) {
            // Il faut rétrécir tout de suite : sécurité anti-coincement.
            appliedScale.put(id, maxFit);
            applyScale(player, maxFit);
            clearStreak.put(id, 0);
        } else if (maxFit > appliedNow + 0.02f) {
            int streak = clearStreak.getOrDefault(id, 0) + 1;
            clearStreak.put(id, streak);
            if (streak >= GROW_BACK_STABLE_TICKS) {
                appliedScale.put(id, maxFit);
                applyScale(player, maxFit);
                clearStreak.put(id, 0);
            }
        } else {
            clearStreak.put(id, 0);
        }
    }

    /** Distance verticale (en blocs) entre les pieds du joueur et le premier obstacle solide au-dessus. */
    private double measureClearance(Player player) {
        Location feet = player.getLocation();
        World world = feet.getWorld();
        Location rayStart = feet.clone().add(0, 0.05, 0);
        RayTraceResult result = world.rayTraceBlocks(rayStart, new Vector(0, 1, 0), 2.3, FluidCollisionMode.NEVER, true);
        if (result != null && result.getHitPosition() != null) {
            return Math.max(0.0, result.getHitPosition().getY() - feet.getY());
        }
        return 2.3; // rien détecté dans la marge testée : largement assez de place
    }

    /**
     * Plus grande taille qui tient dans la place disponible au-dessus du
     * joueur, sans jamais dépasser la taille "voulue", ni descendre sous
     * MIN_AUTO_SCALE (pour éviter les soucis vanilla de collision/étouffement
     * aux échelles extrêmes).
     */
    private float computeMaxFittingScale(Player player, float desired) {
        double clearance = measureClearance(player);
        double usable = Math.max(0.0, clearance - CLEARANCE_MARGIN);
        float maxScale = (float) (usable / PLAYER_HEIGHT);
        maxScale = Math.min(maxScale, desired);
        return Math.max(maxScale, MIN_AUTO_SCALE);
    }

    private void stopFollowTaskIfEmpty() {
        if (disguises.isEmpty() && followTask != null) {
            followTask.cancel();
            followTask = null;
        }
    }

    public boolean isDisguised(UUID id) { return disguises.containsKey(id); }

    /**
     * Vrai si "to" tombe dans la case exacte occupée par le déguisement d'une AUTRE
     * souris que "mover" (peu importe qui : chat ou souris). Utilisé pour rendre les
     * blocs de déguisement solides : personne ne doit pouvoir marcher au travers,
     * comme si c'était un vrai bloc, seule la souris déguisée reste libre de bouger
     * dans sa propre case. Cf. GameListener#onMove.
     */
    public boolean isBlockedByDisguise(Player mover, Location to) {
        if (to == null || to.getWorld() == null) return false;
        if (disguises.isEmpty()) return false;
        UUID moverId = mover.getUniqueId();
        for (Map.Entry<UUID, BlockDisplay> entry : disguises.entrySet()) {
            if (entry.getKey().equals(moverId)) continue;
            BlockDisplay display = entry.getValue();
            if (display == null || !display.isValid()) continue;
            Location d = display.getLocation();
            if (!Objects.equals(d.getWorld(), to.getWorld())) continue;
            if (d.getBlockX() == to.getBlockX() && d.getBlockY() == to.getBlockY() && d.getBlockZ() == to.getBlockZ()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Renvoie la hauteur (Y) sur laquelle "mover" doit être posé s'il marche
     * sur le dessus d'un bloc de déguisement (une AUTRE souris que "mover"),
     * ou null si aucun déguisement ne se trouve sous lui à cet endroit.
     * Comme le bloc de déguisement n'est qu'une entité visuelle (BlockDisplay),
     * il n'a aucune collision vanilla : sans ça, tout le monde tombe au travers
     * au lieu de pouvoir marcher dessus comme sur un vrai bloc. On ne "rattrape"
     * que ceux qui arrivaient d'au-dessus (ou déjà posés dessus) pour ne pas
     * bloquer quelqu'un qui marche simplement en dessous d'un déguisement.
     */
    public Double getDisguiseSupportY(Player mover, Location from, Location to) {
        if (to == null || to.getWorld() == null) return null;
        if (disguises.isEmpty()) return null;
        UUID moverId = mover.getUniqueId();
        for (Map.Entry<UUID, BlockDisplay> entry : disguises.entrySet()) {
            if (entry.getKey().equals(moverId)) continue;
            BlockDisplay display = entry.getValue();
            if (display == null || !display.isValid()) continue;
            Location d = display.getLocation();
            if (!Objects.equals(d.getWorld(), to.getWorld())) continue;
            if (d.getBlockX() != to.getBlockX() || d.getBlockZ() != to.getBlockZ()) continue;

            // Hauteur réelle du bloc imité (0.5 pour une dalle, 0.0625 pour un
            // tapis, 1.0 pour un bloc plein...), pas toujours +1 comme pour un
            // bloc plein : sans ça, marcher sur une souris déguisée en dalle
            // vous plaçait à la hauteur d'un bloc entier au-dessus, en plein
            // dans le vide.
            double topY = d.getBlockY() + disguiseHeight.getOrDefault(entry.getKey(), 1.0f);
            boolean wasAboveOrOnTop = from == null || from.getY() >= topY - 0.001;
            if (wasAboveOrOnTop && to.getY() < topY) {
                return topY;
            }
        }
        return null;
    }

    /**
     * Version "rapide" du pistolet transformeur (accroupi + clic droit) : vise
     * un bloc et se déguise directement dessus, sans passer par le menu.
     * Fonctionne aussi bien pour se déguiser la première fois que pour changer
     * de bloc alors qu'on est déjà déguisé — disguise() défait puis refait le
     * déguisement à chaque appel (cf. disguiseInternal), donc rappeler cette
     * méthode plusieurs fois de suite permet de "copier" un nouveau bloc à
     * chaque fois sans jamais avoir besoin de redevenir soi-même entre deux.
     */
    public void quickDisguiseOnTarget(Player player) {
        Block target = player.getTargetBlockExact(TRANSFORMER_RANGE, FluidCollisionMode.NEVER);
        if (target == null || target.getType().isAir() || !target.getType().isSolid()) {
            player.sendMessage(ChatColor.RED + "Vise un bloc valide (à " + TRANSFORMER_RANGE + " blocs max) !");
            return;
        }
        disguise(player, target);
        player.sendMessage(ChatColor.GREEN + "Transformé en "
                + target.getType().name().toLowerCase().replace('_', ' ') + " !");
    }

    private Transformation buildTransformation(float scale) {
        // Le modèle de bloc occupe l'espace local 0..1 sur chaque axe. Comme le
        // déguisement est maintenant calé sur la grille (coin du bloc, pas le joueur),
        // on centre le modèle réduit/agrandi dans la cellule (0..1) au lieu de le
        // centrer autour du joueur : à scale=1 on retombe exactement sur un vrai bloc.
        float offsetXZ = (1f - scale) / 2f;
        return new Transformation(
                new Vector3f(offsetXZ, 0f, offsetXZ),
                new Quaternionf(),
                new Vector3f(scale, scale, scale),
                new Quaternionf()
        );
    }

    // ---------------------------------------------------------------------
    // Taille de la souris
    // ---------------------------------------------------------------------

    public void applyScale(Player player, float scale) {
        AttributeInstance instance = player.getAttribute(attribute("scale"));
        if (instance != null) instance.setBaseValue(scale);
    }

    public void cycleSize(Player player) {
        UUID id = player.getUniqueId();
        float current = scales.getOrDefault(id, 1.0f);
        int idx = 0;
        for (int i = 0; i < resizeSizes.length; i++) {
            if (Math.abs(resizeSizes[i] - current) < 0.01f) { idx = i; break; }
        }
        float next = resizeSizes[(idx + 1) % resizeSizes.length];
        scales.put(id, next);
        // Rétrécit/agrandit la hitbox et le corps du joueur. C'est la taille
        // "normale" du joueur quand il a de la place ; l'ajustement automatique
        // (cf. autoAdjustScale) peut la rétrécir davantage tout seul en dessous
        // d'un bloc bas ou d'une dalle, puis revient à cette taille dès que
        // c'est de nouveau dégagé.
        // On applique tout de suite le plafond du déguisement en cours (s'il y
        // en a un) : sans ça, la hitbox appliquée dépassait un instant la taille
        // du bloc imité (le temps qu'autoAdjustScale la corrige au tick suivant),
        // ce qui provoquait un micro-clignotement de taille au moment du clic.
        float clamped = Math.min(next, disguiseScale.getOrDefault(id, Float.MAX_VALUE));
        applyScale(player, clamped);
        appliedScale.put(id, clamped);
        clearStreak.put(id, 0);
        // Le bloc du déguisement, lui, NE change PAS de taille : il reste un
        // vrai bloc à échelle 1, quelle que soit la taille du joueur en dessous.

        player.sendMessage(ChatColor.AQUA + "Taille normale : " + next + "x"
                + ChatColor.GRAY + " (rétrécit tout seul sous un bloc bas ou une dalle si besoin)");
    }

    // ---------------------------------------------------------------------
    // Double saut (chats et souris)
    // ---------------------------------------------------------------------
    // Astuce classique : setAllowFlight(true) fait apparaître l'invite "double
    // tap espace pour voler" côté client, même en survie. On intercepte cette
    // tentative de vol (PlayerToggleFlightEvent) dans GameListener, on l'annule,
    // et on remplace ça par une impulsion verticale (le "double saut"). Le vol
    // est ensuite désarmé jusqu'à ce que le joueur retouche le sol (cf.
    // rearmDoubleJump, appelé depuis onMove), pour n'autoriser qu'un seul saut
    // supplémentaire par saut normal, pas un vol continu.

    private void enableDoubleJump(Player player) {
        player.setAllowFlight(true);
        player.setFlying(false);
    }

    private void disableDoubleJump(Player player) {
        player.setAllowFlight(false);
        player.setFlying(false);
    }

    public boolean isDoubleJumpEligible(UUID id) {
        if (state != State.HIDING && state != State.SEEKING) return false;
        return isSeeker(id) || isHider(id);
    }

    /** Ré-arme le double saut une fois que le joueur retouche le sol. */
    public void rearmDoubleJump(Player player) {
        if (!isDoubleJumpEligible(player.getUniqueId())) return;
        if (!player.getAllowFlight()) {
            player.setAllowFlight(true);
        }
    }

    /** Consomme le double saut : impulsion verticale, puis vol désarmé jusqu'au prochain atterrissage. */
    public void performDoubleJump(Player player) {
        org.bukkit.util.Vector velocity = player.getVelocity();
        velocity.setY(0.9);
        player.setVelocity(velocity);
        player.setFallDistance(0f);
        player.setFlying(false);
        player.setAllowFlight(false);
    }

    // ---------------------------------------------------------------------
    // Leurres (feu d'artifice / sifflet)
    // ---------------------------------------------------------------------

    public void throwDecoy(Player player, ItemStack hand, String type) {
        Snowball snowball = player.launchProjectile(Snowball.class);
        snowball.setVelocity(player.getLocation().getDirection().multiply(1.3));
        snowball.getPersistentDataContainer().set(decoyKey, PersistentDataType.STRING, type);
        hand.setAmount(hand.getAmount() - 1);
        if (type.equals("firework")) {
            player.sendMessage(ChatColor.GOLD + "Feu d'artifice lancé !");
        } else {
            player.sendMessage(ChatColor.AQUA + "Sifflet lancé !");
        }
    }

    public void triggerDecoy(Snowball snowball, String type) {
        Location loc = snowball.getLocation();
        World world = loc.getWorld();
        if (world == null) return;
        if (type.equals("firework")) {
            Firework fw = world.spawn(loc, Firework.class);
            org.bukkit.inventory.meta.FireworkMeta meta = fw.getFireworkMeta();
            meta.addEffect(FireworkEffect.builder()
                    .withColor(Color.ORANGE, Color.RED)
                    .with(FireworkEffect.Type.BURST)
                    .withTrail()
                    .build());
            meta.setPower(0);
            fw.setFireworkMeta(meta);
            fw.detonate();
        } else if (type.equals("whistle")) {
            world.playSound(loc, Sound.BLOCK_NOTE_BLOCK_FLUTE, SoundCategory.MASTER, 3.0f, 2.0f);
            world.spawnParticle(Particle.NOTE, loc, 5);
        }
    }

    /**
     * Coup de sifflet automatique et périodique, joué directement à la
     * position réelle de chaque souris encore en jeu (pas de projectile,
     * contrairement au sifflet-leurre lancé manuellement).
     */
    private void autoWhistlePulse() {
        for (UUID id : hiders) {
            if (found.contains(id)) continue;
            Player p = Bukkit.getPlayer(id);
            if (p == null) continue;
            World world = p.getWorld();
            Location loc = p.getLocation();
            world.playSound(loc, Sound.BLOCK_NOTE_BLOCK_FLUTE, SoundCategory.MASTER, 3.0f, 2.0f);
            world.spawnParticle(Particle.NOTE, loc, 5);
        }
    }

    // ---------------------------------------------------------------------
    // Équipement
    // ---------------------------------------------------------------------

    private ItemStack named(Material material, int amount, String name, String... lore) {
        ItemStack item = new ItemStack(material, amount);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(name);
        if (lore.length > 0) meta.setLore(Arrays.asList(lore));
        item.setItemMeta(meta);
        return item;
    }

    private void giveHiderKit(Player player) {
        int slot = 0;
        boolean allowResize = mode != Mode.PROP_HUNT || resizeAllowedInPropHunt;
        if (allowResize) {
            ItemStack resize = named(Material.FEATHER, 1, ChatColor.AQUA + "Redimensionneur",
                    ChatColor.GRAY + "Clic droit pour changer de taille");
            player.getInventory().setItem(slot++, resize);
        }
        if (decoyFireworkCount > 0) {
            ItemStack firework = named(Material.FIREWORK_ROCKET, Math.min(decoyFireworkCount, 64),
                    ChatColor.GOLD + "Leurre : Feu d'artifice", ChatColor.GRAY + "Clic droit pour lancer");
            player.getInventory().setItem(slot++, firework);
        }
        if (decoyWhistleCount > 0) {
            ItemStack horn = named(Material.GOAT_HORN, Math.min(decoyWhistleCount, 64),
                    ChatColor.AQUA + "Leurre : Sifflet", ChatColor.GRAY + "Clic droit pour lancer");
            player.getInventory().setItem(slot++, horn);
        }

        // Le Pistolet Transformeur n'est donné qu'aux souris, et seulement en mode Prop Hunt
        if (mode == Mode.PROP_HUNT) {
            player.getInventory().setItem(slot++, buildTransformerTool());
        }

        scales.put(player.getUniqueId(), 1.0f);
        applyScale(player, 1.0f);
        // Main vide par défaut (cf. snapHandToEmpty) : évite qu'un objet du kit
        // reste visible en permanence dans la main du joueur une fois invisible.
        snapHandToEmpty(player);
    }

    private ItemStack buildTransformerTool() {
        ItemStack rod = new ItemStack(Material.FISHING_ROD);
        ItemMeta meta = rod.getItemMeta();
        meta.setDisplayName(ChatColor.LIGHT_PURPLE + "Pistolet Transformeur");
        meta.setLore(Arrays.asList(
                ChatColor.GRAY + "Clic droit en visant un bloc : te déguiser dessus",
                ChatColor.GRAY + "Clic droit une nouvelle fois : redevenir toi-même"
        ));
        meta.setUnbreakable(true);
        meta.getPersistentDataContainer().set(transformerKey, PersistentDataType.BYTE, (byte) 1);
        rod.setItemMeta(meta);
        return rod;
    }

    private void giveSeekerKit(Player player) {
        // Les chats n'ont jamais le Pistolet Transformeur : seulement l'épée et l'arc pour traquer les souris.
        ItemStack sword = new ItemStack(Material.WOODEN_SWORD);
        ItemMeta swordMeta = sword.getItemMeta();
        swordMeta.setDisplayName(ChatColor.RED + "Épée du Chat");
        swordMeta.setUnbreakable(true);
        swordMeta.addAttributeModifier(attribute("attack_damage"), new AttributeModifier(
                new NamespacedKey(plugin, "cachecache_damage"), 1000.0,
                AttributeModifier.Operation.ADD_NUMBER, EquipmentSlotGroup.MAINHAND));
        swordMeta.addAttributeModifier(attribute("entity_interaction_range"), new AttributeModifier(
                new NamespacedKey(plugin, "cachecache_reach"), 2.0,
                AttributeModifier.Operation.ADD_NUMBER, EquipmentSlotGroup.MAINHAND));
        sword.setItemMeta(swordMeta);

        ItemStack bow = new ItemStack(Material.BOW);
        ItemStack arrows = new ItemStack(Material.ARROW, 24);

        player.getInventory().setItem(0, sword);
        player.getInventory().setItem(1, bow);
        player.getInventory().setItem(2, arrows);
    }
}
