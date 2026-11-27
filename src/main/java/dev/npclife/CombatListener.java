package dev.npclife;

import org.bukkit.entity.EnderPearl;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.WindCharge;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityResurrectEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerAnimationType;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Feeds real game events into the combat brains: hits, swings, totem pops, pearl/wind-charge impacts, teams. */
final class CombatListener implements Listener {
    /** Last arm swing of real players (tick), so NPCs can react to an incoming hit. */
    static final Map<UUID, Long> lastSwing = new HashMap<>();

    @EventHandler
    public void onSwing(PlayerAnimationEvent e) {
        if (e.getAnimationType() == PlayerAnimationType.ARM_SWING)
            lastSwing.put(e.getPlayer().getUniqueId(), NPCLifePlugin.get().brains().now());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) { lastSwing.remove(e.getPlayer().getUniqueId()); }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent e) {
        Entity damager = e.getDamager();
        if (damager instanceof Projectile pr && pr.getShooter() instanceof Entity shooter) damager = shooter;

        TeamManager tm = NPCLifePlugin.get().teams();
        Combat attacker = Combat.of(damager);
        if (attacker != null && !Combat.cfgB("friendly-fire", false)) {          // army never hurts its own team
            String dt = tm.teamOf(damager), vt = tm.teamOf(e.getEntity());
            if (dt != null && dt.equalsIgnoreCase(vt)) { e.setCancelled(true); return; }
        }
        Combat victim = Combat.of(e.getEntity());
        if (victim != null) {
            if (victim.shieldAbsorbs(damager, e)) { e.setCancelled(true); return; }
            victim.onHurt(damager, e.getFinalDamage());
        }
        if (attacker != null && e.getEntity() == attacker.target) attacker.onDealt(e.getFinalDamage());
    }

    @EventHandler
    public void onResurrect(EntityResurrectEvent e) {
        Combat c = Combat.of(e.getEntity());
        if (c != null && !e.isCancelled()) c.onTotem();
    }

    @EventHandler
    public void onProjectileHit(ProjectileHitEvent e) {
        Projectile pr = e.getEntity();
        if (!(pr.getShooter() instanceof Entity shooter)) return;
        Combat c = Combat.of(shooter);
        if (c == null) return;
        if (pr instanceof EnderPearl) c.util.pearlLanded(pr.getLocation(), pr);
        else if (pr instanceof WindCharge) c.util.windHit(pr.getLocation());
    }
}
