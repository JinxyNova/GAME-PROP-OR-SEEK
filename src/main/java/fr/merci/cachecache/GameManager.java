package fr.merci.cachecache;

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
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.*;

public class GameManager {

    public enum State { WAITING, HIDING, SEEKING }

    // Mode de jeu choisi au lancement de la partie
    public enum Mode { PROP_HUNT, HIDE_AND_SEEK }

    // Distance max (en blocs) que le pistolet transformeur peut viser
    private static final int TRANSFORMER_RANGE = 20;

    // Temps d'immobilité (en secondes) avant qu'un déguisement soit considéré "figé"
    private static final int FREEZE_DELAY_SECONDS = 3;

    // Paliers de taille disponibles pour les souris (1.0 = taille normale)
    private static final float[] SIZES = {0.5f, 0.75f, 1.0f, 1.25f, 1.5f};

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

    private BukkitTask mainTask;
    private int hideSeconds;
    private int seekSeconds;

    public GameManager(CacheCachePlugin plugin) {
        this.plugin = plugin;
        this.decoyKey = new NamespacedKey(plugin, "decoy");
        this.transformerKey = new NamespacedKey(plugin, "transformer_tool");
        reloadSettings();
    }

    public void reloadSettings() {
        hideSeconds = plugin.getConfig().getInt("hide-time-seconds", 20);
        seekSeconds = plugin.getConfig().getInt("seek-time-seconds", 300);
    }

    public NamespacedKey getDecoyKey() { return decoyKey; }
    public NamespacedKey getTransformerKey() { return transformerKey; }
    public State getState() { return state; }
    public Mode getMode() { return mode; }
    public boolean isHider(UUID uuid) { return hiders.contains(uuid) && !found.contains(uuid); }
    public boolean isSeeker(UUID uuid) { return seekers.contains(uuid); }
    public int getSeekerCount() { return seekers.size(); }
    public int getHiderCount() { return hiders.size(); }

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

    // ---------------------------------------------------------------------
    // Démarrage / fin de partie
    // ---------------------------------------------------------------------

    /**
     * Lance une partie.
     * @param requestedSeekers nombre de chats souhaité (0 ou moins = valeur par défaut : 1)
     * @param requestedHiders nombre max de souris souhaité (0 ou moins = pas de limite : tous les joueurs restants)
     */
    public void start(CommandSender sender, Mode chosenMode, int requestedSeekers, int requestedHiders) {
        if (state != State.WAITING) {
            sender.sendMessage(ChatColor.RED + "Une partie est déjà en cours !");
            return;
        }
        List<Player> online = new ArrayList<>(Bukkit.getOnlinePlayers());
        if (online.size() < 2) {
            sender.sendMessage(ChatColor.RED + "Il faut au moins 2 joueurs pour lancer une partie !");
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
        seekers.clear();
        hiders.clear();
        found.clear();
        disguises.clear();
        frozenLoc.clear();
        scales.clear();
        lastMoveLoc.clear();
        stillSeconds.clear();
        frozenHiders.clear();

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
            p.sendMessage(ChatColor.GREEN + "Le redimensionneur change ta taille, le feu d'artifice et le sifflet sont des leurres.");
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
                for (UUID seekerId : seekers) {
                    Player seekerPlayer = Bukkit.getPlayer(seekerId);
                    if (seekerPlayer == null) continue;
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
                    seekerPlayer.sendActionBar(net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacySection().deserialize(msg));
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
                            p.sendActionBar(net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacySection()
                                    .deserialize(ChatColor.YELLOW + "Tu bouges, tu n'es plus figé..."));
                        }
                    } else {
                        int seconds = stillSeconds.getOrDefault(id, 0) + 1;
                        stillSeconds.put(id, seconds);
                        if (seconds >= FREEZE_DELAY_SECONDS && frozenHiders.add(id)) {
                            p.sendActionBar(net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacySection()
                                    .deserialize(ChatColor.AQUA + "Figé comme un vrai bloc !"));
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
        endGame(false);
        sender.sendMessage(ChatColor.GRAY + "Partie arrêtée.");
    }

    public void forceStop() {
        if (state != State.WAITING) endGame(false);
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
        BlockDisplay display = player.getWorld().spawn(player.getLocation(), BlockDisplay.class, d -> {
            d.setBlock(data);
            d.setTransformation(buildTransformation(scale));
        });
        display.addPassenger(player);
        disguises.put(id, display);
        player.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, Integer.MAX_VALUE, 0, false, false));
        lastMoveLoc.put(id, player.getLocation());
        stillSeconds.put(id, 0);
        frozenHiders.remove(id);
    }

    public void undisguise(Player player) {
        UUID id = player.getUniqueId();
        BlockDisplay display = disguises.remove(id);
        if (display != null) {
            display.eject();
            display.remove();
        }
        player.removePotionEffect(PotionEffectType.INVISIBILITY);
        lastMoveLoc.remove(id);
        stillSeconds.remove(id);
        frozenHiders.remove(id);
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
        return new Transformation(
                new Vector3f(-0.5f * scale, 0f, -0.5f * scale),
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
        for (int i = 0; i < SIZES.length; i++) {
            if (Math.abs(SIZES[i] - current) < 0.01f) { idx = i; break; }
        }
        float next = SIZES[(idx + 1) % SIZES.length];
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
        ItemStack resize = named(Material.FEATHER, 1, ChatColor.AQUA + "Redimensionneur",
                ChatColor.GRAY + "Clic droit pour changer de taille");
        ItemStack firework = named(Material.FIREWORK_ROCKET, 2, ChatColor.GOLD + "Leurre : Feu d'artifice",
                ChatColor.GRAY + "Clic droit pour lancer");
        ItemStack horn = named(Material.GOAT_HORN, 1, ChatColor.AQUA + "Leurre : Sifflet",
                ChatColor.GRAY + "Clic droit pour lancer");

        player.getInventory().setItem(0, resize);
        player.getInventory().setItem(1, firework);
        player.getInventory().setItem(2, horn);

        if (mode == Mode.PROP_HUNT) {
            player.getInventory().setItem(3, buildTransformerTool());
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
