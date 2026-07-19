package fr.merci.cachecache;

import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Snowball;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerToggleFlightEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Vector;

public class GameListener implements Listener {
    private final GameManager gameManager;
    private final GameMenu gameMenu;

    public GameListener(GameManager gameManager, GameMenu gameMenu) {
        this.gameManager = gameManager;
        this.gameMenu = gameMenu;
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        Player player = event.getPlayer();
        if (gameManager.getState() == GameManager.State.WAITING) return;
        if (!gameManager.isHider(player.getUniqueId())) return;

        Action action = event.getAction();
        boolean rightClick = action == Action.RIGHT_CLICK_AIR || action == Action.RIGHT_CLICK_BLOCK;
        boolean leftClick = action == Action.LEFT_CLICK_AIR || action == Action.LEFT_CLICK_BLOCK;
        if (!rightClick && !leftClick) return;

        ItemStack hand = player.getInventory().getItemInMainHand();
        if (hand.getType() == Material.AIR) return;

        // Pistolet Transformeur (canne à pêche, Prop Hunt uniquement) :
        //  - Clic droit simple             : ouvre le menu de choix de bloc (paginé + recherche)
        //  - Accroupi (shift) + clic droit : copie instantanément le bloc visé, sans passer par le menu
        //  - Clic gauche                   : redevient normal (annule le déguisement en cours)
        if (gameManager.isTransformerTool(hand)) {
            event.setCancelled(true);
            if (leftClick) {
                if (gameManager.isDisguised(player.getUniqueId())) {
                    gameManager.undisguise(player);
                    player.sendMessage(ChatColor.GRAY + "Tu redeviens toi-même !");
                }
            } else if (player.isSneaking()) {
                gameManager.quickDisguiseOnTarget(player);
            } else {
                gameMenu.openBlockPicker(player);
            }
            gameManager.snapHandToEmpty(player);
            return;
        }

        // Les autres objets (plume, feu d'artifice, sifflet) restent clic droit uniquement.
        if (!rightClick) return;

        if (hand.getType() == Material.FEATHER) {
            gameManager.cycleSize(player);
            event.setCancelled(true);
            gameManager.snapHandToEmpty(player);
        } else if (hand.getType() == Material.FIREWORK_ROCKET) {
            gameManager.throwDecoy(player, hand, "firework");
            event.setCancelled(true);
            gameManager.snapHandToEmpty(player);
        } else if (hand.getType() == Material.GOAT_HORN) {
            gameManager.throwDecoy(player, hand, "whistle");
            event.setCancelled(true);
            gameManager.snapHandToEmpty(player);
        }
    }

    /**
     * Empêche de casser un bloc par accident en cliquant gauche avec le Pistolet
     * Transformeur en main (le clic gauche sert désormais à annuler le
     * déguisement, cf. onInteract) : sans ce filet, viser un bloc à casse
     * instantanée (fleur, torche, redstone...) le casserait quand même, car
     * annuler PlayerInteractEvent ne bloque pas la casse de bloc vanilla.
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onBlockBreak(BlockBreakEvent event) {
        if (!gameManager.isHider(event.getPlayer().getUniqueId())) return;
        ItemStack hand = event.getPlayer().getInventory().getItemInMainHand();
        if (gameManager.isTransformerTool(hand)) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onProjectileHit(ProjectileHitEvent event) {
        if (!(event.getEntity() instanceof Snowball snowball)) return;
        String type = snowball.getPersistentDataContainer().get(gameManager.getDecoyKey(), PersistentDataType.STRING);
        if (type == null) return;
        gameManager.triggerDecoy(snowball, type);
    }

    /** Résout le "vrai" attaquant, que le coup vienne de l'épée (mêlée) ou d'une flèche tirée à l'arc. */
    private Player resolveAttacker(Entity damager) {
        if (damager instanceof Player p) return p;
        if (damager instanceof Projectile projectile && projectile.getShooter() instanceof Player p) return p;
        return null;
    }

    @EventHandler
    public void onDamage(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim)) return;
        Player attacker = resolveAttacker(event.getDamager());
        if (attacker == null) return;
        if (gameManager.getState() != GameManager.State.SEEKING) return;
        if (!gameManager.isSeeker(attacker.getUniqueId())) return;
        if (!gameManager.isHider(victim.getUniqueId())) return;
        event.setCancelled(true);
        gameManager.markFound(victim);
    }

    /**
     * Filet de sécurité global : pendant une partie (cache ou recherche), aucun
     * participant ne peut mourir (chute, noyade, feu, coup, etc.). Le seul moyen
     * de "perdre" en tant que souris, c'est d'être trouvé (cf. markFound
     * ci-dessus) ; personne ne doit jamais voir l'écran de mort.
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onAnyDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        GameManager.State state = gameManager.getState();
        if (state != GameManager.State.HIDING && state != GameManager.State.SEEKING) return;
        if (gameManager.isSeeker(player.getUniqueId()) || gameManager.isHider(player.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    /** Double saut des chats et souris : intercepte la tentative de vol déclenchée par un double-tap espace. */
    @EventHandler
    public void onToggleFlight(PlayerToggleFlightEvent event) {
        Player player = event.getPlayer();
        if (!event.isFlying()) return;
        if (!gameManager.isDoubleJumpEligible(player.getUniqueId())) return;
        event.setCancelled(true);
        gameManager.performDoubleJump(player);
    }

    @EventHandler
    public void onMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        GameManager.State state = gameManager.getState();

        if (state == GameManager.State.HIDING && gameManager.isSeeker(player.getUniqueId())) {
            var frozen = gameManager.getFrozenLocation(player.getUniqueId());
            if (frozen != null && event.getTo() != null
                    && (event.getTo().getX() != frozen.getX() || event.getTo().getY() != frozen.getY() || event.getTo().getZ() != frozen.getZ())) {
                event.setTo(frozen);
            }
        }

        // Ré-arme le double saut dès que le joueur retouche le sol.
        if (player.isOnGround() && gameManager.isDoubleJumpEligible(player.getUniqueId())) {
            gameManager.rearmDoubleJump(player);
        }

        // Rend les blocs de déguisement solides : personne (à part la souris qui se
        // cache dedans) ne doit pouvoir marcher dans la case qu'ils occupent, sinon
        // ça se traverse comme si de rien n'était. On bloque juste le déplacement
        // (comme un vrai bloc) sans toucher aux coups d'épée, qui continuent de
        // viser directement le joueur invisible en dessous.
        if (event.getTo() != null && gameManager.isBlockedByDisguise(player, event.getTo())) {
            event.setTo(event.getFrom());
            return;
        }

        // Permet de marcher SUR un bloc de déguisement (le BlockDisplay n'a aucune
        // collision vanilla à lui tout seul, donc sans ça tout le monde tombe au
        // travers au lieu de pouvoir se tenir dessus comme sur un vrai bloc).
        if (event.getTo() != null) {
            Double supportY = gameManager.getDisguiseSupportY(player, event.getFrom(), event.getTo());
            if (supportY != null) {
                Location landed = event.getTo().clone();
                landed.setY(supportY);
                event.setTo(landed);
                player.setFallDistance(0f);
                Vector velocity = player.getVelocity();
                if (velocity.getY() < 0) {
                    velocity.setY(0);
                    player.setVelocity(velocity);
                }
            }
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        if (gameManager.isDisguised(player.getUniqueId())) {
            gameManager.undisguise(player);
        }
        gameManager.removeFromLobbyQuietly(player.getUniqueId());
    }
}
