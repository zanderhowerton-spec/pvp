package dev.npclife;

import net.citizensnpcs.api.ai.Navigator;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Marching: named routes (waypoints) and armies that walk them in formation. A virtual formation centre advances waypoint
 * to waypoint (and waits for stragglers); every NPC paths to its own slot around that centre, with its own pace and a slowly
 * wandering offset, so the column looks like people walking together rather than a rigid block.
 */
final class MarchManager {
    private static final class Slot {
        double fwd, right, pace = 1, jx, jz;
        long retargetAt, holdUntil, jitterAt, checkAt;
        int stuck;
        Vector last;
    }

    private static final class March {
        final World world;
        final List<LifeTrait> members;
        final List<Location> pts;
        final boolean loop, keepHome, sprint;
        final double speed;
        final UUID starter;
        final Map<Integer, Slot> slots = new HashMap<>();
        int idx;
        Vector center;
        double heading;
        boolean paused;
        long waitUntil, arrivedAt;

        March(World world, List<LifeTrait> members, List<Location> pts, boolean loop, boolean keepHome, boolean sprint, double speed, UUID starter) {
            this.world = world; this.members = members; this.pts = pts; this.loop = loop; this.keepHome = keepHome; this.sprint = sprint;
            this.speed = speed; this.starter = starter;
        }
    }

    private final NPCLifePlugin plugin;
    private final Map<String, List<Location>> routes = new TreeMap<>();
    private final List<March> marches = new ArrayList<>();
    private final Random rng = new Random();
    private BukkitTask task;

    MarchManager(NPCLifePlugin plugin) { this.plugin = plugin; }

    // ------------------------------------------------------------------ routes (routes.yml)
    private File file() { return new File(plugin.getDataFolder(), "routes.yml"); }

    void load() {
        routes.clear();
        if (!file().exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file());
        for (String name : y.getKeys(false)) {
            List<Location> pts = new ArrayList<>();
            for (String s : y.getStringList(name)) {
                String[] p = s.split(";");
                World w = p.length == 4 ? Bukkit.getWorld(p[0]) : null;
                if (w == null) continue;
                try { pts.add(new Location(w, Double.parseDouble(p[1]), Double.parseDouble(p[2]), Double.parseDouble(p[3]))); }
                catch (NumberFormatException ex) { /* skip */ }
            }
            routes.put(name.toLowerCase(Locale.ROOT), pts);
        }
    }

    private void saveRoutes() {
        YamlConfiguration y = new YamlConfiguration();
        for (Map.Entry<String, List<Location>> en : routes.entrySet()) {
            List<String> out = new ArrayList<>();
            for (Location l : en.getValue()) out.add(l.getWorld().getName() + ";" + l.getX() + ";" + l.getY() + ";" + l.getZ());
            y.set(en.getKey(), out);
        }
        try { plugin.getDataFolder().mkdirs(); y.save(file()); } catch (IOException ex) { plugin.getLogger().warning("Could not save routes: " + ex); }
    }

    List<String> routeNames() { return new ArrayList<>(routes.keySet()); }

    void routeCommand(CommandSender s, String[] a) {
        if (a.length < 3) { LifeCommand.err(s, "/npcx army route create|add|undo|list|show|delete <name> [x y z]"); return; }
        String sub = a[2].toLowerCase(Locale.ROOT);
        String name = a.length > 3 ? a[3].toLowerCase(Locale.ROOT) : null;
        switch (sub) {
            case "list" -> LifeCommand.say(s, "Routes: <white>" + (routes.isEmpty() ? "none" : String.join(", ", routes.keySet())));
            case "create" -> {
                if (name == null) { LifeCommand.err(s, "Name?"); return; }
                routes.putIfAbsent(name, new ArrayList<>());
                saveRoutes();
                LifeCommand.say(s, "Route <white>" + name + "</white> ready. Stand somewhere and run <white>/npcx army route add " + name + "</white> for each waypoint.");
            }
            case "add" -> {
                if (name == null || !routes.containsKey(name)) { LifeCommand.err(s, "No such route. /npcx army route create <name>"); return; }
                Location l;
                if (a.length >= 7) {
                    World w = s instanceof Player p ? p.getWorld() : Bukkit.getWorlds().get(0);
                    try { l = new Location(w, Double.parseDouble(a[4]), Double.parseDouble(a[5]), Double.parseDouble(a[6])); }
                    catch (NumberFormatException ex) { LifeCommand.err(s, "Invalid coordinates."); return; }
                } else if (s instanceof Player p) l = p.getLocation().clone();
                else { LifeCommand.err(s, "Give x y z."); return; }
                routes.get(name).add(l);
                saveRoutes();
                LifeCommand.say(s, "Waypoint " + routes.get(name).size() + " added to <white>" + name + "</white>.");
            }
            case "undo" -> {
                List<Location> r = name == null ? null : routes.get(name);
                if (r == null || r.isEmpty()) { LifeCommand.err(s, "Nothing to undo."); return; }
                r.remove(r.size() - 1);
                saveRoutes();
                LifeCommand.say(s, "Removed the last waypoint.");
            }
            case "show" -> {
                List<Location> r = name == null ? null : routes.get(name);
                if (r == null) { LifeCommand.err(s, "No such route."); return; }
                for (int i = 0; i < r.size(); i++) LifeCommand.say(s, (i + 1) + ". " + r.get(i).getWorld().getName() + " " + r.get(i).getBlockX() + " " + r.get(i).getBlockY() + " " + r.get(i).getBlockZ());
            }
            case "delete" -> {
                if (name == null || routes.remove(name) == null) LifeCommand.err(s, "No such route.");
                else { saveRoutes(); LifeCommand.say(s, "Deleted."); }
            }
            default -> LifeCommand.err(s, "create | add | undo | list | show | delete");
        }
    }

    // ------------------------------------------------------------------ /npcx army march
    void marchCommand(CommandSender s, String[] a, ArmyCommand army, LifeCommand lc) {
        if (a.length < 3) {
            LifeCommand.err(s, "/npcx army march <route | here | x y z> [loop] [formation=column|line|wedge|blob|box] [spacing=2] [speed=walk|jog|sprint] [team=..] [all] [keephome]   |   march stop|pause|resume|status");
            return;
        }
        String first = a[2].toLowerCase(Locale.ROOT);
        switch (first) {
            case "stop" -> { int n = stopAll(); LifeCommand.say(s, "Stopped " + n + " march(es). The army stands where it is."); return; }
            case "pause" -> { marches.forEach(m -> m.paused = true); LifeCommand.say(s, "Paused."); return; }
            case "resume" -> { marches.forEach(m -> m.paused = false); LifeCommand.say(s, "Marching on."); return; }
            case "status" -> {
                if (marches.isEmpty()) { LifeCommand.say(s, "No marches running."); return; }
                for (March m : marches) LifeCommand.say(s, m.members.size() + " NPC(s), waypoint " + (m.idx + 1) + "/" + m.pts.size() + (m.paused ? " (paused)" : "") + (m.loop ? " (loop)" : ""));
                return;
            }
            default -> { }
        }
        // destination
        List<Location> pts = new ArrayList<>();
        int optFrom;
        if (routes.containsKey(first)) { pts.addAll(routes.get(first)); optFrom = 3; }
        else if (first.equals("here")) {
            if (!(s instanceof Player p)) { LifeCommand.err(s, "Players only."); return; }
            pts.add(p.getLocation().clone()); optFrom = 3;
        } else if (a.length >= 5) {
            try {
                World w = s instanceof Player p ? p.getWorld() : Bukkit.getWorlds().get(0);
                pts.add(new Location(w, Double.parseDouble(a[2]), Double.parseDouble(a[3]), Double.parseDouble(a[4])));
                optFrom = 5;
            } catch (NumberFormatException ex) { LifeCommand.err(s, "Unknown route and not coordinates."); return; }
        } else { LifeCommand.err(s, "Unknown route. See /npcx army route list"); return; }
        if (pts.isEmpty()) { LifeCommand.err(s, "That route has no waypoints."); return; }

        Map<String, String> opt = new HashMap<>();
        boolean loop = false, all = false, keepHome = false;
        for (int i = optFrom; i < a.length; i++) {
            String t = a[i].toLowerCase(Locale.ROOT);
            int eq = t.indexOf('=');
            if (eq > 0) opt.put(t.substring(0, eq), t.substring(eq + 1));
            else if (t.equals("loop")) loop = true;
            else if (t.equals("all")) all = true;
            else if (t.equals("keephome")) keepHome = true;
        }
        if (Boolean.parseBoolean(opt.getOrDefault("loop", "false"))) loop = true;

        List<LifeTrait> members;
        if (opt.containsKey("team")) members = army.armyMembers(opt.get("team"));
        else if (all) members = army.armyMembers(null);
        else members = lc.targets(s);
        members = new ArrayList<>(members);
        members.removeIf(t -> !t.getNPC().isSpawned());
        if (members.isEmpty()) { LifeCommand.err(s, "Nobody to march (select NPCs, or use team=<name> / all)."); return; }

        String speedOpt = opt.getOrDefault("speed", "walk");
        double speed = speedOpt.equals("jog") ? 1.15 : 1.0;
        boolean sprint = speedOpt.equals("sprint") || speedOpt.equals("run");
        double spacing = 2;
        try { spacing = Math.max(1, Double.parseDouble(opt.getOrDefault("spacing", "2"))); } catch (NumberFormatException ex) { /* default */ }
        start(s, members, pts, loop, keepHome, sprint, speed, opt.getOrDefault("formation", "column"), spacing);
    }

    private void start(CommandSender s, List<LifeTrait> members, List<Location> pts, boolean loop, boolean keepHome, boolean sprint,
                       double speed, String formation, double spacing) {
        World w = members.get(0).getNPC().getEntity().getWorld();
        if (pts.get(0).getWorld() != w) { LifeCommand.err(s, "The NPCs and the route are in different worlds."); return; }
        for (March m : marches) m.members.removeIf(t -> members.contains(t));          // one march per NPC
        marches.removeIf(m -> m.members.isEmpty());
        March m = new March(w, members, pts, loop, keepHome, sprint, speed, s instanceof Player p ? p.getUniqueId() : null);
        double cx = 0, cz = 0, cy = 0;
        for (LifeTrait t : members) { Location l = t.getNPC().getEntity().getLocation(); cx += l.getX(); cz += l.getZ(); cy += l.getY(); }
        m.center = new Vector(cx / members.size(), cy / members.size(), cz / members.size());
        m.heading = Math.atan2(pts.get(0).getZ() - m.center.getZ(), pts.get(0).getX() - m.center.getX());
        m.arrivedAt = plugin.brains().now();
        List<LifeTrait> order = new ArrayList<>(members);
        Collections.shuffle(order, rng);
        double[][] off = offsets(order.size(), formation, spacing);
        for (int i = 0; i < order.size(); i++) {
            Slot sl = new Slot();
            sl.fwd = off[i][0]; sl.right = off[i][1];
            sl.pace = 0.94 + rng.nextDouble() * 0.12;
            m.slots.put(order.get(i).getNPC().getId(), sl);
            order.get(i).brain().scriptBegin();
        }
        marches.add(m);
        if (task == null) task = Bukkit.getScheduler().runTaskTimer(plugin, this::step, 5L, 5L);
        LifeCommand.say(s, "<white>" + members.size() + "</white> NPC(s) marching in <white>" + formation + "</white> formation over " + pts.size() + " waypoint(s)"
                + (loop ? " (looping)" : "") + ". <gray>/npcx army march stop</gray> to halt.");
    }

    private double[][] offsets(int n, String f, double sp) {
        double[][] o = new double[n][2];
        for (int i = 0; i < n; i++) {
            switch (f) {
                case "line" -> {
                    int row = i / 25, inRow = Math.min(25, n - row * 25);
                    o[i][0] = -row * sp; o[i][1] = ((i % 25) - (inRow - 1) / 2.0) * sp;
                }
                case "wedge" -> {
                    int r = 0, k = i;
                    while (k > r) { k -= r + 1; r++; }
                    o[i][0] = -r * sp; o[i][1] = (k - r / 2.0) * sp;
                }
                case "blob" -> {
                    double R = Math.sqrt(n) * sp * 0.8;
                    o[i][0] = (rng.nextDouble() - 0.5) * 2 * R; o[i][1] = (rng.nextDouble() - 0.5) * 2 * R;
                }
                case "box" -> {
                    int side = (int) Math.ceil(Math.sqrt(n));
                    o[i][0] = -(i / side) * sp; o[i][1] = ((i % side) - (side - 1) / 2.0) * sp;
                }
                default -> {
                    int cols = n >= 30 ? 4 : 3;
                    o[i][0] = -(i / cols) * sp; o[i][1] = ((i % cols) - (cols - 1) / 2.0) * sp;
                }
            }
        }
        return o;
    }

    int stopAll() {
        int n = marches.size();
        for (March m : new ArrayList<>(marches)) finish(m, false);
        marches.clear();
        if (task != null) { task.cancel(); task = null; }
        return n;
    }

    // ------------------------------------------------------------------ the march itself
    private void step() {
        long now = plugin.brains().now();
        for (Iterator<March> it = marches.iterator(); it.hasNext();) {
            March m = it.next();
            if (!advance(m, now)) { finish(m, true); it.remove(); }
        }
        if (marches.isEmpty() && task != null) { task.cancel(); task = null; }
    }

    private Vector slotVec(March m, Slot s) {
        double fx = Math.cos(m.heading), fz = Math.sin(m.heading);
        return new Vector(m.center.getX() + fx * s.fwd + (-fz) * s.right + s.jx, m.center.getY(), m.center.getZ() + fz * s.fwd + fx * s.right + s.jz);
    }

    private boolean advance(March m, long now) {
        for (Iterator<LifeTrait> it = m.members.iterator(); it.hasNext();) {
            LifeTrait t = it.next();
            if (!t.getNPC().isSpawned()) { t.brain().scriptEnd(); it.remove(); }          // fell in battle: leaves the column
        }
        if (m.members.isEmpty()) return false;
        Location wp = m.pts.get(m.idx);
        Vector toWp = new Vector(wp.getX() - m.center.getX(), 0, wp.getZ() - m.center.getZ());
        double dist = toWp.length();
        double lag = 0;
        for (LifeTrait t : m.members) {
            Slot s = m.slots.get(t.getNPC().getId());
            Vector sp = slotVec(m, s);
            Location here = t.getNPC().getEntity().getLocation();
            lag += Math.hypot(here.getX() - sp.getX(), here.getZ() - sp.getZ());
        }
        lag /= m.members.size();
        if (!m.paused && now >= m.waitUntil) {
            if (dist < 2.5) {
                if (lag < 6 || now - m.arrivedAt > 20 * 20) {                              // waits for stragglers (at most 20 s)
                    m.idx++;
                    m.arrivedAt = now;
                    if (m.idx >= m.pts.size()) { if (m.loop) m.idx = 0; else return false; }
                    m.waitUntil = now + 20 + rng.nextInt(40);
                }
            } else {
                double step = m.speed * (m.sprint ? 1.3 : 1.0) * 4.317 * 0.25;             // blocks per 5-tick update
                double factor = lag > 7 ? 0.0 : lag > 4 ? 0.5 : 1.0;                       // the column slows for stragglers
                double adv = Math.min(dist, step * factor);
                toWp.normalize();
                m.center.add(toWp.clone().multiply(adv));
                Location g = Ground.standable(m.world, m.center.getX(), m.center.getZ(), (int) Math.floor(m.center.getY()));
                if (g != null) m.center.setY(g.getY());
            }
        }
        if (dist > 0.5) {
            double target = Math.atan2(toWp.getZ(), toWp.getX()), d = target - m.heading;
            while (d > Math.PI) d -= 2 * Math.PI;
            while (d < -Math.PI) d += 2 * Math.PI;
            m.heading += d * 0.2;                                                          // gentle turns, not instant ones
        }
        for (LifeTrait t : m.members) drive(m, t, now);
        return true;
    }

    private void drive(March m, LifeTrait t, long now) {
        Slot s = m.slots.get(t.getNPC().getId());
        Entity e = t.getNPC().getEntity();
        Brain b = t.brain();
        if (b.combat().engaged()) return;                                                   // fighting comes first
        if (now >= s.jitterAt) {                                                           // nobody keeps a perfect formation
            s.jitterAt = now + 100 + rng.nextInt(100);
            s.jx = (rng.nextDouble() - 0.5) * 1.2;
            s.jz = (rng.nextDouble() - 0.5) * 1.2;
        }
        Vector sp = slotVec(m, s);
        Location here = e.getLocation();
        double d = Math.hypot(here.getX() - sp.getX(), here.getZ() - sp.getZ());
        Navigator nav = b.navigator();
        if (m.paused) { if (nav.isNavigating()) nav.cancelNavigation(); return; }
        if (now < s.holdUntil) return;
        boolean sprint = m.sprint || d > 9;
        if (d < 1.2) {
            if (nav.isNavigating()) nav.cancelNavigation();
            b.setManualSprint(false);
            s.stuck = 0;
            if (now >= s.retargetAt) {                                                      // ease the body round to the marching direction
                s.retargetAt = now + 20;
                float want = (float) Math.toDegrees(Math.atan2(-Math.cos(m.heading), Math.sin(m.heading)));
                e.setRotation(here.getYaw() + HeadUtil.wrap(want - here.getYaw()) * 0.25f, here.getPitch());
            }
            return;
        }
        if (now >= s.retargetAt || !nav.isNavigating()) {
            s.retargetAt = now + 8 + rng.nextInt(8);
            if (rng.nextDouble() < 0.03) { s.holdUntil = now + 10 + rng.nextInt(20); return; }   // a moment of hesitation
            Location goal = Ground.standable(m.world, sp.getX(), sp.getZ(), here.getBlockY());
            if (goal == null) goal = new Location(m.world, sp.getX(), here.getY(), sp.getZ());
            nav.getLocalParameters().speedModifier((float) (m.speed * s.pace * (sprint ? 1.3 : 1.0)));
            nav.getLocalParameters().distanceMargin(0.8);
            nav.getLocalParameters().range((float) Math.max(30.0, d * 1.5));
            nav.setTarget(goal);
            b.setManualSprint(sprint);
            if (now >= s.checkAt) {                                                         // stuck handling
                s.checkAt = now + 40;
                Vector cur = here.toVector();
                if (s.last != null && s.last.distance(cur) < 0.3 && d > 3) {
                    s.stuck++;
                    if (s.stuck >= 2) b.hop();
                    if (s.stuck >= 6 && d > 6 && plugin.getConfig().getBoolean("march-teleport-stragglers", true)) {
                        t.getNPC().teleport(goal, PlayerTeleportEvent.TeleportCause.PLUGIN);
                        s.stuck = 0;
                    }
                } else s.stuck = 0;
                s.last = cur;
            }
        }
    }

    private void finish(March m, boolean arrived) {
        for (LifeTrait t : m.members) {
            Brain b = t.brain();
            if (t.getNPC().isSpawned()) {
                Navigator nav = b.navigator();
                if (nav.isNavigating()) nav.cancelNavigation();
                b.setManualSprint(false);
                if (!m.keepHome) t.setCenter(t.getNPC().getEntity().getLocation());      // the army settles here: this is home now
            }
            b.scriptEnd();
        }
        if (arrived && m.starter != null) {
            Player p = Bukkit.getPlayer(m.starter);
            if (p != null) LifeCommand.say(p, "The army has arrived (" + m.members.size() + " NPCs).");
        }
    }
}
