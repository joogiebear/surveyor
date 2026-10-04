package xyz.joogiebear.surveyor.paper;

import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import xyz.joogiebear.surveyor.core.region.Flag;

final class ProtectionListener implements Listener {

    private final Protection protection;

    ProtectionListener(Protection protection) {
        this.protection = protection;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (blocked(event.getPlayer(), event.getBlock(), Flag.BUILD)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (blocked(event.getPlayer(), event.getBlock(), Flag.BUILD)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent event) {
        if (blocked(event.getPlayer(), event.getBlock(), Flag.BUILD)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent event) {
        if (blocked(event.getPlayer(), event.getBlock(), Flag.BUILD)) {
            event.setCancelled(true);
        }
    }

    // The API cannot say reliably which blocks react to a click, so every right-click counts. The one
    // exception is sneak-placing a block, which is a build and is checked by onPlace.
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        Block block = event.getClickedBlock();
        if (block == null) {
            return;
        }
        Player player = event.getPlayer();
        boolean placing = player.isSneaking() && event.getItem() != null && event.getItem().getType().isBlock();
        boolean physical = event.getAction() == Action.PHYSICAL;
        boolean click = event.getAction() == Action.RIGHT_CLICK_BLOCK && !placing;
        if ((physical || click) && blocked(player, block, Flag.INTERACT)) {
            event.setUseInteractedBlock(Event.Result.DENY);
            event.setUseItemInHand(Event.Result.DENY);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        if (blocked(event.getPlayer(), event.getRightClicked().getLocation(), Flag.INTERACT)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim)) {
            return;
        }
        Player attacker = event.getDamager() instanceof Player p ? p
                : event.getDamager() instanceof Projectile projectile && projectile.getShooter() instanceof Player p ? p
                : null;
        if (attacker != null && blocked(attacker, victim.getLocation(), Flag.PVP)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSpawn(CreatureSpawnEvent event) {
        switch (event.getSpawnReason()) {
            case CUSTOM, COMMAND, SPAWNER_EGG, DISPENSE_EGG -> {
                return;
            }
            default -> {
            }
        }
        if (!protection.resolve(null, event.getLocation(), Flag.MOB_SPAWNING)) {
            event.setCancelled(true);
        }
    }

    // Cancels only the step that crosses into a region, so a player already inside one is never trapped.
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Location from = event.getFrom();
        Location to = event.getTo();
        if (from.getWorld().equals(to.getWorld())
                && from.getBlockX() == to.getBlockX() && from.getBlockY() == to.getBlockY()
                && from.getBlockZ() == to.getBlockZ()) {
            return;
        }
        Player player = event.getPlayer();
        if (protection.resolve(player.getUniqueId(), from, Flag.ENTRY) && protection.denies(player, to, Flag.ENTRY)) {
            event.setCancelled(true);
            protection.notifyDenied(player, Flag.ENTRY);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        protection.forget(event.getPlayer().getUniqueId());
    }

    private boolean blocked(Player player, Block block, Flag flag) {
        return blocked(player, block.getLocation(), flag);
    }

    private boolean blocked(Player player, Location where, Flag flag) {
        if (protection.denies(player, where, flag)) {
            protection.notifyDenied(player, flag);
            return true;
        }
        return false;
    }
}
