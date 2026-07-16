package fr.merci.cachecache;

import org.bukkit.Material;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Snowball;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

public class GameListener implements Listener {
    private final GameManager gameManager;

    public GameListener(GameManager gameManager) {
        this.gameManager = gameManager;
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        Player player = event.getPlayer();
        if (gameManager.getState() == GameManager.State.WAITING) return;
        if (!gameManager.isHider(player.getUniqueId())) return;
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;

        ItemStack hand = player.getInventory().getItemInMainHand();
        if (hand.getType() == Material.AIR) return;

        // Pistolet Transformeur (canne à pêche, Prop Hunt uniquement) : se déguiser en visant un bloc,
        // ou redevenir normal si déjà déguisé
        if (gameManager.isTransformerTool(hand)) {
            gameManager.useTransformer(player);
            event.setCancelled(true);
            gameManager.snapHandToEmpty(player);
            return;
        }

        // Clic droit simple avec les objets spéciaux
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

    @EventHandler
    public void onMove(PlayerMoveEvent event) {
        if (gameManager.getState() != GameManager.State.HIDING) return;
        Player player = event.getPlayer();
        if (!gameManager.isSeeker(player.getUniqueId())) return;
        var frozen = gameManager.getFrozenLocation(player.getUniqueId());
        if (frozen == null || event.getTo() == null) return;
        if (event.getTo().getX() != frozen.getX() || event.getTo().getY() != frozen.getY() || event.getTo().getZ() != frozen.getZ()) {
            event.setTo(frozen);
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
