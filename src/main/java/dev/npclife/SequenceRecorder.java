package dev.npclife;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Statistic;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerAnimationType;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerStatisticIncrementEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.event.player.PlayerToggleSprintEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;

import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Records a PLAYER's movement/actions into sequence steps. Positions are stored relative to where recording started,
 * so a sequence replays from wherever the NPC stands. Steps run one after another (a WALK finishes before the next
 * step), so a jump you made mid-walk is replayed when the NPC reaches that waypoint.
 */
final class SequenceRecorder implements Listener {
    private static final class Rec {
        final String name;
        final List<String> steps = new ArrayList<>();
        final Location origin;
        Location lastWp, lastSample;
        float lastYaw, lastPitch;
        long still;
        Rec(String name, Location o) {
            this.name = name; origin = o.clone(); lastWp = o.clone(); lastSample = o.clone();
            lastYaw = o.getYaw(); lastPitch = o.getPitch();
        }
    }

    private final Map<UUID, Rec> recs = new HashMap<>();
    private final BukkitTask task;

    SequenceRecorder(NPCLifePlugin plugin) {
        Bukkit.getPluginManager().registerEvents(this, plugin);
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::sample, 10L, 10L);
    }

    void stop() { task.cancel(); }
    boolean isRecording(Player p) { return recs.containsKey(p.getUniqueId()); }

    void start(Player p, String name) {
        Rec r = new Rec(name.toLowerCase(Locale.ROOT), p.getLocation());
        r.steps.add(String.format(Locale.ROOT, "LOOK %.0f %.0f", p.getLocation().getYaw(), p.getLocation().getPitch()));
        recs.put(p.getUniqueId(), r);
    }

    Map.Entry<String, List<String>> finish(Player p) {
        Rec r = recs.remove(p.getUniqueId());
        if (r == null) return null;
        if (r.still >= 20) r.steps.add(String.format(Locale.ROOT, "WAIT %.1f", r.still / 20.0));
        return new AbstractMap.SimpleEntry<>(r.name, r.steps);
    }

    private void sample() {
        for (Map.Entry<UUID, Rec> en : recs.entrySet()) {
            Player p = Bukkit.getPlayer(en.getKey());
            if (p == null) continue;
            Rec r = en.getValue();
            Location l = p.getLocation();
            double moved = l.getWorld() == r.lastSample.getWorld() ? l.distance(r.lastSample) : 99;
            r.lastSample = l;
            if (moved < 0.4) r.still += 10;
            else { if (r.still >= 20) r.steps.add(String.format(Locale.ROOT, "WAIT %.1f", r.still / 20.0)); r.still = 0; }
            if (l.getWorld() == r.lastWp.getWorld() && l.distance(r.lastWp) >= 2.5) {
                r.steps.add(String.format(Locale.ROOT, "WALK %.2f %.2f %.2f", l.getX() - r.origin.getX(), l.getY() - r.origin.getY(), l.getZ() - r.origin.getZ()));
                r.lastWp = l;
            }
            if (Math.abs(HeadUtil.wrap(l.getYaw() - r.lastYaw)) > 30 || Math.abs(l.getPitch() - r.lastPitch) > 20) {
                r.steps.add(String.format(Locale.ROOT, "LOOK %.0f %.0f", l.getYaw(), l.getPitch()));
                r.lastYaw = l.getYaw(); r.lastPitch = l.getPitch();
            }
        }
    }

    private void add(Player p, String step) { Rec r = recs.get(p.getUniqueId()); if (r != null) r.steps.add(step); }

    @EventHandler public void onSneak(PlayerToggleSneakEvent e) { add(e.getPlayer(), "SNEAK " + (e.isSneaking() ? "on" : "off")); }
    @EventHandler public void onSprint(PlayerToggleSprintEvent e) { add(e.getPlayer(), "SPRINT " + (e.isSprinting() ? "on" : "off")); }
    @EventHandler public void onJump(PlayerStatisticIncrementEvent e) { if (e.getStatistic() == Statistic.JUMP) add(e.getPlayer(), "JUMP"); }
    @EventHandler public void onSwing(PlayerAnimationEvent e) { if (e.getAnimationType() == PlayerAnimationType.ARM_SWING) add(e.getPlayer(), "SWING"); }
    @EventHandler public void onHeld(PlayerItemHeldEvent e) {
        ItemStack it = e.getPlayer().getInventory().getItem(e.getNewSlot());
        add(e.getPlayer(), "HOLD " + (it == null ? "AIR" : it.getType().name()));
    }
    @EventHandler public void onQuit(PlayerQuitEvent e) { recs.remove(e.getPlayer().getUniqueId()); }
}
