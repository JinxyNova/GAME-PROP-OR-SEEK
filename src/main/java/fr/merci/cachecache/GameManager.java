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
import org.bukkit.util.Transformation;
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

    // Paliers de taille par défaut (utilisés si "resize.sizes" est vide/absent dans le config.yml)
    private static final float[] DEFAULT_SIZES = {0.1f, 0.2f, 0.3f, 0.4f, 0.5f, 0.75f, 1.0f, 1.25f, 1.5f};

    // Nom de l'équipe scoreboard utilisée pour masquer les pseudos au-dessus des têtes
    private static final String HIDE_TEAM_NAME = "cachecache_hide";

    private final CacheCachePlugin plugin;
    private final NamespacedKey decoyKey;
    private final NamespacedKey transformerKey;

    private State state = State.WAITING;
    private Mode mode = Mode.PROP_HUNT;
    private final Set<UUID> seekers = new HashSet<>();
    private final Set<UUID> hiders = new HashSet<>();
    private final Set<UUID> found = new HashSet<>();
    private final Map<UUID, BlockDisplay> disguises = new HashMap<>();
    private final Map<UUID, Float> scales = new HashMap<>();
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

    private BukkitTask mainTask;
    private boolean hotColdAnnounced = false;

    // ---------------------------------------------------------------------
    // Paramètres rechargés depuis config.yml (cf. reloadSettings())
    // ---------------------------------------------------------------------
    private int hideSeconds;
    private int seekSeconds;

    private boolean lobbyEnabled;
    private int lobbyMinPlayers;
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
    }

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

    private Team getHiddenTeam() {
        Scoreboard board = Bukkit.getScoreboardManager().getMainScoreboard();
        Team team = board.getTeam(HIDE_TEAM_NAME);
        if (team == null) {
            team = board.registerNewTeam(HIDE_TEAM_NAME);
        }
        team.setOption(Team.Option.NAME_TAG_VISIBILITY, Team.OptionStatus.NEVER);
        return team;
    }

    private void applyNametagHiding(Collection<Player> players) {
        if (!hideNametags) return;
        Team team = getHiddenTeam();
        for (Player p : players) {
            team.addEntry(p.getName());
        }
    }

    private void clearNametagHiding() {
        Scoreboard board = Bukkit.getScoreboardManager().getMainScoreboard();
        Team team = board.getTeam(HIDE_TEAM_NAME);
        if (team == null) return;
        for (String entry : new ArrayList<>(team.getEntries())) {
            team.removeEntry(entry);
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
        if (state == State.WAITING) {
            state = State.LOBBY;
            lobbyMode = lobbyDefaultMode;
            lobbyRequestedSeekers = 0;
            lobbyRequestedHiders = 0;
        }
        lobbyPlayers.add(player.getUniqueId());
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

        Collections.shuffle(online);
        int total = online.size();

        // Le nombre de chats est borné entre 1 et (total - 1) pour garder au moins une souris
        int seekerCount = requestedSeekers > 0 ? requestedSeekers : 1;
        seekerCount = Math.max(1, Math.min(seekerCount, total - 1));

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
        lastMoveLoc.clear();
        stillSeconds.clear();
        frozenHiders.clear();
        clearNametagHiding();

        List<Player> chosenSeekers = online.subList(0, seekerCount);
        List<Player> chosenHiders = online.subList(seekerCount, seekerCount + hiderCount);
        List<Player> bystanders = online.subList(seekerCount + hiderCount, total);

        int freezeTicks = hideSeconds * 20 + 20;
        for (Player s : chosenSeekers) {
            seekers.add(s.getUniqueId());
            giveSeekerKit(s);
            frozenLoc.put(s.getUniqueId(), s.getLocation());
            s.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, freezeTicks, 1, false, false));
            s.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, freezeTicks, 250, false, false));
            s.addPotionEffect(new PotionEffect(PotionEffectType.JUMP_BOOST, freezeTicks, -10, false, false));
        }
        for (Player h : chosenHiders) {
            hiders.add(h.getUniqueId());
            giveHiderKit(h);
        }
        for (Player b : bystanders) {
            b.sendMessage(ChatColor.GRAY + "Trop de joueurs pour cette manche, tu regardes cette fois-ci !");
        }

        List<Player> participants = new ArrayList<>(chosenSeekers);
        participants.addAll(chosenHiders);
        applyNametagHiding(participants);

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
                p.setGameMode(GameMode.SURVIVAL);
            }
        }
        for (UUID id : seekers) {
            Player seekerPlayer = Bukkit.getPlayer(id);
            if (seekerPlayer != null) {
                seekerPlayer.removePotionEffect(PotionEffectType.BLINDNESS);
                seekerPlayer.removePotionEffect(PotionEffectType.SLOWNESS);
                seekerPlayer.removePotionEffect(PotionEffectType.JUMP_BOOST);
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

    public void disguise(Player player, BlockData data) {
        UUID id = player.getUniqueId();
        undisguise(player);
        float scale = scales.getOrDefault(id, 1.0f);

        // On aligne le déguisement sur la grille de blocs (coordonnées entières) et on
        // verrouille yaw/pitch à 0 : sans ça, le bloc hérite de l'orientation du regard
        // du joueur au moment de la transformation (rotation non voulue) et n'est pas
        // calé sur la même grille que les vrais blocs (donc visuellement "pas centré").
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
            d.setTransformation(buildTransformation(scale));
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
        lastMoveLoc.put(id, player.getLocation());
        stillSeconds.put(id, 0);
        frozenHiders.remove(id);
        startFollowTask();
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
        stopFollowTaskIfEmpty();
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
                }
            }
        }.runTaskTimer(plugin, 1L, 1L);
    }

    private void stopFollowTaskIfEmpty() {
        if (disguises.isEmpty() && followTask != null) {
            followTask.cancel();
            followTask = null;
        }
    }

    public boolean isDisguised(UUID id) { return disguises.containsKey(id); }

    /**
     * Utilise le Pistolet Transformeur : vise un bloc et se déguise dessus,
     * ou redevient normal si déjà déguisé. Retourne un message à afficher au joueur.
     */
    public void useTransformer(Player player) {
        UUID id = player.getUniqueId();
        if (isDisguised(id)) {
            undisguise(player);
            player.sendMessage(ChatColor.GRAY + "Tu redeviens toi-même !");
            return;
        }
        Block target = player.getTargetBlockExact(TRANSFORMER_RANGE, FluidCollisionMode.NEVER);
        if (target == null || target.getType().isAir() || !target.getType().isSolid()) {
            player.sendMessage(ChatColor.RED + "Vise un bloc valide (à " + TRANSFORMER_RANGE + " blocs max) !");
            return;
        }
        disguise(player, target.getBlockData());
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
        applyScale(player, next);

        BlockDisplay display = disguises.get(id);
        if (display != null) {
            display.setTransformation(buildTransformation(next));
        }
        player.sendMessage(ChatColor.AQUA + "Taille : " + next + "x");
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
