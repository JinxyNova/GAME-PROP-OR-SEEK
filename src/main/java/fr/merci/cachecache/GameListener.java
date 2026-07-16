package fr.merci.cachecache;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.entity.Snowball;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
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
            return;
        }

        // Clic droit simple avec les objets spéciaux
        if (hand.getType() == Material.FEATHER) {
            gameManager.cycleSize(player);
            event.setCancelled(true);
        } else if (hand.getType() == Material.FIREWORK_ROCKET) {
            gameManager.throwDecoy(player, hand, "firework");
            event.setCancelled(true);
        } else if (hand.getType() == Material.GOAT_HORN) {
            gameManager.throwDecoy(player, hand, "whistle");
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

    @EventHandler
    public void onDamage(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim)) return;
        if (!(event.getDamager() instanceof Player attacker)) return;
        if (gameManager.getState() != GameManager.State.SEEKING) return;
        if (!gameManager.isSeeker(attacker.getUniqueId())) return;
        if (!gameManager.isHider(victim.getUniqueId())) return;
        event.setCancelled(true);
        gameManager.markFound(victim);
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
