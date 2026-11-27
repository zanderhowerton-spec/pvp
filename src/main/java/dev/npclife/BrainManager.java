package dev.npclife;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * ONE repeating task drives every NPC. Per tick each brain does only cheap work (head easing, expiry checks);
 * decisions happen every ~0.6-3 s per NPC. Brains with no real player nearby are skipped entirely.
 */
final class BrainManager {
    private final NPCLifePlugin plugin;
    private final Set<LifeTrait> traits = new LinkedHashSet<>();
    private BukkitTask task;
    private long tick;

    BrainManager(NPCLifePlugin plugin) { this.plugin = plugin; }

    void start() { task = Bukkit.getScheduler().runTaskTimer(plugin, this::run, 1L, 1L); }

    void stop() {
        if (task != null) task.cancel();
        Cleanup.flush();
        for (LifeTrait t : new ArrayList<>(traits)) t.brain().shutdown();
        traits.clear();
    }

    List<Entity> members(String group, LifeTrait self) {
        List<Entity> out = new ArrayList<>();
        Entity me = self.getNPC().isSpawned() ? self.getNPC().getEntity() : null;
        if (me == null) return out;
        for (LifeTrait o : traits) {
            if (o == self || !group.equalsIgnoreCase(o.group()) || !o.getNPC().isSpawned()) continue;
            Entity oe = o.getNPC().getEntity();
            if (oe.getWorld() == me.getWorld() && oe.getLocation().distanceSquared(me.getLocation()) <= 40 * 40) out.add(oe);
        }
        return out;
    }

    List<Entity> shopkeepers(LifeTrait self, double range) {
        List<Entity> out = new ArrayList<>();
        Entity me = self.getNPC().isSpawned() ? self.getNPC().getEntity() : null;
        if (me == null) return out;
        for (LifeTrait o : traits) {
            if (o == self || !"shopkeeper".equalsIgnoreCase(o.personalityName()) || !o.getNPC().isSpawned()) continue;
            Entity oe = o.getNPC().getEntity();
            if (oe.getWorld() == me.getWorld() && oe.getLocation().distanceSquared(me.getLocation()) <= range * range) out.add(oe);
        }
        return out;
    }

    // ---- combat support
    private final List<Entity> pool = new ArrayList<>();

    List<Entity> pool() { return pool; }
    long now() { return tick; }

    private void rebuildPool() {
        pool.clear();
        for (Player p : Bukkit.getOnlinePlayers()) if (p.getGameMode() != GameMode.SPECTATOR) pool.add(p);
        for (LifeTrait t : traits) if (t.team() != null && t.getNPC().isSpawned()) pool.add(t.getNPC().getEntity());
    }

    private static boolean sameTeam(LifeTrait a, LifeTrait b) { return a.team() != null && a.team().equalsIgnoreCase(b.team()); }

    int attackersOf(Entity target, Combat self) {
        int n = 0;
        for (LifeTrait o : traits) { Combat c = o.brain().combat(); if (c != self && c.target == target && c.state == Combat.State.ENGAGE) n++; }
        return n;
    }

    int tokensOn(Entity target, Combat self) {
        int n = 0;
        for (LifeTrait o : traits) { Combat c = o.brain().combat(); if (c != self && c.target == target && c.tokenHeld) n++; }
        return n;
    }

    List<Entity> alliesNear(Combat self, double r) {
        List<Entity> out = new ArrayList<>();
        Entity me = self.t.getNPC().isSpawned() ? self.t.getNPC().getEntity() : null;
        if (me == null || self.t.team() == null) return out;
        for (LifeTrait o : traits) {
            if (o == self.t || !sameTeam(self.t, o) || !o.getNPC().isSpawned()) continue;
            Entity e = o.getNPC().getEntity();
            if (e.getWorld() == me.getWorld() && e.getLocation().distanceSquared(me.getLocation()) <= r * r) out.add(e);
        }
        return out;
    }

    /** Each attacker of one target gets its own angle around it, so a group spreads out instead of stacking. */
    double slotAngle(Combat self) {
        if (self.target == null) return 0;
        List<Integer> ids = new ArrayList<>();
        ids.add(self.t.getNPC().getId());
        for (LifeTrait o : traits) {
            Combat c = o.brain().combat();
            if (c != self && c.target == self.target && o.getNPC().isSpawned() && sameTeam(self.t, o)) ids.add(o.getNPC().getId());
        }
        java.util.Collections.sort(ids);
        int idx = ids.indexOf(self.t.getNPC().getId()), n = ids.size();
        double base = (self.target.getUniqueId().hashCode() & 0xFFFF) / 65535.0 * Math.PI * 2 + (tick / 400.0) * 0.5;
        return base + idx * (Math.PI * 2 / n);
    }

    void register(LifeTrait t) { traits.add(t); }
    void unregister(LifeTrait t) { traits.remove(t); }

    private void run() {
        tick++;
        if (traits.isEmpty()) return;
        if (tick % 20 == 1) { refreshNear(); rebuildPool(); }
        if (tick % 20 == 5) Cleanup.tick(tick);
        for (LifeTrait t : new ArrayList<>(traits)) {
            try { t.brain().tick(tick); }
            catch (Throwable ex) {
                plugin.getLogger().warning("Brain error for NPC " + t.getNPC().getId() + ": " + ex);
                t.setEnabled(false);
            }
        }
    }

    private void refreshNear() {
        double range = plugin.getConfig().getDouble("active-range", 128);
        double r2 = range * range;
        List<Location> locs = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) locs.add(p.getLocation());
        for (LifeTrait t : traits) {
            Entity e = t.getNPC().isSpawned() ? t.getNPC().getEntity() : null;
            boolean near = false;
            if (e != null) {
                Location l = e.getLocation();
                for (Location p : locs)
                    if (p.getWorld() == l.getWorld() && p.distanceSquared(l) <= r2) { near = true; break; }
            }
            t.brain().near = near;
        }
    }
}
