package dev.npclife;

import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.trait.SkinTrait;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;

/** /npcx army ... : spawn 1-100 NPCs at once, give them a kit/team/skill, select them, respawn the dead. */
final class ArmyCommand {
    static final String[] STYLES = {"aggressive", "defensive", "cowardly", "confident", "tactical", "unpredictable"};

    private final NPCLifePlugin pl;
    private final LifeCommand lc;
    private final Random rng = new Random();

    ArmyCommand(NPCLifePlugin pl, LifeCommand lc) { this.pl = pl; this.lc = lc; }

    void handle(CommandSender s, String[] a) {
        if (a.length < 2) { help(s); return; }
        try {
            switch (a[1].toLowerCase(Locale.ROOT)) {
                case "spawn" -> spawn(s, a);
                case "respawn" -> respawn(s, a);
                case "remove" -> remove(s, a);
                case "select" -> select(s, a);
                case "list" -> list(s);
                case "kit" -> kit(s, a);
                case "vulnerable" -> vulnerable(s, a);
                case "names" -> names(s, a);
                case "skill" -> skill(s, a);
                case "route" -> pl.march().routeCommand(s, a);
                case "march" -> pl.march().marchCommand(s, a, this, lc);
                case "speech" -> speech(s, a);
                default -> help(s);
            }
        } catch (NumberFormatException ex) { LifeCommand.err(s, "Invalid number."); }
    }

    private void help(CommandSender s) {
        for (String l : new String[]{
                "army spawn <1-100> <team> [kit=name|a:60,b:40] [mix=20/50/30] [style=random|aggressive|...] [formation=grid|circle|line] [spacing=2] [skin=true|false]",
                "army select [team] | army select near <radius> [team]   <gray>- select the whole army, one team, or everyone near you</gray>",
                "army kit save|apply|list|delete <name> [team]   <gray>- kits come from YOUR inventory (armor, hotbar, offhand, everything)</gray>",
                "army respawn [team]   <gray>- bring back every dead army NPC at its spawn point, with its kit</gray>",
                "army remove <team|all> | army list | army vulnerable on|off | army names [reload]",
                "army route create|add|undo|list|show|delete <name> [x y z]   <gray>- waypoints (add = where you stand)</gray>",
                "army march <route|here|x y z> [loop] [formation=column|line|wedge|blob|box] [spacing=2] [speed=walk|jog|sprint] [team=..] [all] [keephome]",
                "army march stop|pause|resume|status",
                "army speech [seconds] [radius=40] [intensity=low|normal|high] [team=..]   <gray>- the army faces you and cheers (army speech stop)</gray>",
                "army skill set <0-100> | mix <20/50/30> | style <name|random>   <gray>- applies to the selected NPCs</gray>",
                "team create|delete|list|info|add|remove|hostile   <gray>- put players and NPCs in teams, mark teams as enemies</gray>"})
            LifeCommand.say(s, "<white>/npcx " + l);
    }

    // ------------------------------------------------------------------ helpers
    List<LifeTrait> armyMembers(String team) {
        List<LifeTrait> out = new ArrayList<>();
        for (NPC n : CitizensAPI.getNPCRegistry()) {
            if (!n.hasTrait(LifeTrait.class)) continue;
            LifeTrait t = n.getOrAddTrait(LifeTrait.class);
            if (t.isArmy() && (team == null || team.equalsIgnoreCase(t.team()))) out.add(t);
        }
        return out;
    }

    private static Map<String, String> options(String[] a, int from) {
        Map<String, String> m = new HashMap<>();
        for (int i = from; i < a.length; i++) {
            int eq = a[i].indexOf('=');
            if (eq > 0) m.put(a[i].substring(0, eq).toLowerCase(Locale.ROOT), a[i].substring(eq + 1));
        }
        return m;
    }

    private static double[] parseMix(String s) {
        double[] w = {20, 50, 30};
        String[] p = s.split("/");
        try { for (int i = 0; i < 3 && i < p.length; i++) w[i] = Math.max(0, Double.parseDouble(p[i].trim())); }
        catch (NumberFormatException ex) { return new double[]{20, 50, 30}; }
        return w[0] + w[1] + w[2] <= 0 ? new double[]{20, 50, 30} : w;
    }

    /** beginner / average / expert weights -> a skill value 0..1 (never exactly perfect). */
    private double rollSkill(double[] w) {
        double r = rng.nextDouble() * (w[0] + w[1] + w[2]);
        if (r < w[0]) return 0.10 + rng.nextDouble() * 0.25;
        if (r < w[0] + w[1]) return 0.40 + rng.nextDouble() * 0.25;
        return 0.75 + rng.nextDouble() * 0.23;
    }

    private String rollStyle(String requested) {
        if (requested != null && !requested.equalsIgnoreCase("random"))
            for (String st : STYLES) if (st.equalsIgnoreCase(requested)) return st;
        return STYLES[rng.nextInt(STYLES.length)];
    }

    private Set<String> usedNames() {
        Set<String> u = new HashSet<>();
        for (NPC n : CitizensAPI.getNPCRegistry()) u.add(n.getName().toLowerCase(Locale.ROOT));
        for (Player p : Bukkit.getOnlinePlayers()) u.add(p.getName().toLowerCase(Locale.ROOT));
        return u;
    }

    private Location standable(World w, double x, double z, int y0) {
        int bx = (int) Math.floor(x), bz = (int) Math.floor(z);
        if (!w.isChunkLoaded(bx >> 4, bz >> 4)) return null;
        int[] order = {0, 1, -1, 2, -2, 3, -3, 4, -4, 5, -5, 6, -6};
        for (int dy : order) {
            int y = y0 + dy;
            if (y < w.getMinHeight() + 1 || y > w.getMaxHeight() - 3) continue;
            Block floor = w.getBlockAt(bx, y - 1, bz), feet = w.getBlockAt(bx, y, bz), head = w.getBlockAt(bx, y + 1, bz);
            if (!floor.getType().isSolid() || !feet.isPassable() || feet.isLiquid() || !head.isPassable() || head.isLiquid()) continue;
            return new Location(w, bx + 0.5, y, bz + 0.5);
        }
        return null;
    }

    private List<Location> formation(Player p, int n, String type, double spacing) {
        World w = p.getWorld();
        Location c = p.getLocation();
        Vector f = c.getDirection().setY(0);
        if (f.lengthSquared() < 0.01) f = new Vector(0, 0, 1);
        f.normalize();
        Vector right = new Vector(-f.getZ(), 0, f.getX());
        List<Location> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            double fo, ro;
            switch (type) {
                case "circle" -> {
                    double r = Math.max(5, n * spacing / (2 * Math.PI)), ang = 2 * Math.PI * i / n;
                    fo = Math.cos(ang) * r; ro = Math.sin(ang) * r;
                }
                case "line" -> { fo = 5; ro = (i - (n - 1) / 2.0) * spacing; }
                default -> {
                    int cols = Math.max(5, (int) Math.ceil(Math.sqrt(n)));
                    fo = 5 + (i / cols) * spacing; ro = ((i % cols) - (cols - 1) / 2.0) * spacing;
                }
            }
            double x = c.getX() + f.getX() * fo + right.getX() * ro, z = c.getZ() + f.getZ() * fo + right.getZ() * ro;
            Location spot = standable(w, x, z, c.getBlockY());
            if (spot == null) spot = new Location(w, x, c.getY(), z);
            Vector look = c.toVector().subtract(spot.toVector());
            spot.setYaw((float) Math.toDegrees(Math.atan2(-look.getX(), look.getZ())));   // face the player
            out.add(spot);
        }
        return out;
    }

    // ------------------------------------------------------------------ spawn
    private void spawn(CommandSender s, String[] a) {
        if (!(s instanceof Player p)) { LifeCommand.err(s, "Players only (the army spawns around you)."); return; }
        if (a.length < 4) { LifeCommand.err(s, "/npcx army spawn <1-100> <team> [kit=..] [mix=20/50/30] [style=..] [formation=..] [spacing=..]"); return; }
        int max = pl.getConfig().getInt("army-max", 100);
        int count = Integer.parseInt(a[2]);
        if (count < 1 || count > max) { LifeCommand.err(s, "Count must be 1-" + max + "."); return; }
        String team = a[3].toLowerCase(Locale.ROOT);
        Map<String, String> opt = options(a, 4);

        final String kitSpec = opt.get("kit");
        if (kitSpec != null && pl.kits().pick(kitSpec) == null) { LifeCommand.err(s, "No such kit. Save one first: /npcx army kit save <name>"); return; }
        final double[] mix = parseMix(opt.getOrDefault("mix", pl.getConfig().getString("army-default-mix", "20/50/30")));
        final String style = opt.get("style");
        double spacing = 2;
        try { spacing = Math.max(1, Double.parseDouble(opt.getOrDefault("spacing", "2"))); } catch (NumberFormatException ex) { /* keep default */ }
        final boolean skins = Boolean.parseBoolean(opt.getOrDefault("skin", "true"));
        final boolean vulnerable = pl.getConfig().getBoolean("army-vulnerable", true);
        final int perTick = Math.max(1, pl.getConfig().getInt("army-spawn-per-tick", 4));

        if (!pl.teams().exists(team)) { pl.teams().create(team); LifeCommand.say(s, "Created team <white>" + team + "</white>."); }
        final List<NameManager.Pick> names = pl.names().take(count, usedNames());
        final List<Location> spots = formation(p, count, opt.getOrDefault("formation", "grid").toLowerCase(Locale.ROOT), spacing);
        final List<Integer> ids = new ArrayList<>();
        final int total = count;

        LifeCommand.say(s, "Spawning <white>" + count + "</white> NPCs for team <white>" + team + "</white>...");
        new BukkitRunnable() {
            int i = 0;
            @Override public void run() {
                for (int k = 0; k < perTick && i < total; k++, i++) {
                    NameManager.Pick pk = names.get(i);
                    NPC npc = CitizensAPI.getNPCRegistry().createNPC(EntityType.PLAYER, pk.name());
                    LifeTrait t = npc.getOrAddTrait(LifeTrait.class);
                    t.setArmy(true);
                    t.setTeam(team);
                    t.setSkill(rollSkill(mix));
                    t.setStyle(rollStyle(style));
                    t.setKit(pl.kits().pick(kitSpec));
                    npc.setProtected(!vulnerable);
                    MetaUtil.set(npc, "RESPAWN_DELAY", -1);          // dead NPCs stay dead until /npcx army respawn
                    if (skins && pk.real()) {
                        try { npc.getOrAddTrait(SkinTrait.class).setSkinName(pk.name()); } catch (Throwable ex) { /* skin lookup is optional */ }
                    }
                    npc.spawn(spots.get(i));
                    ids.add(npc.getId());
                }
                if (i >= total) {
                    cancel();
                    lc.setSelection(s, new LinkedHashSet<>(ids));
                    LifeCommand.say(s, "Spawned <white>" + ids.size() + "</white> NPCs and selected them all. Next: <white>/npcx army kit apply <kit></white>");
                }
            }
        }.runTaskTimer(pl, 1L, 1L);
    }

    // ------------------------------------------------------------------ respawn / remove / select / list
    private void respawn(CommandSender s, String[] a) {
        String team = a.length > 2 ? a[2] : null;
        final List<LifeTrait> dead = new ArrayList<>();
        for (LifeTrait t : armyMembers(team)) if (!t.getNPC().isSpawned()) dead.add(t);
        if (dead.isEmpty()) { LifeCommand.say(s, "Nobody to respawn."); return; }
        final int perTick = Math.max(1, pl.getConfig().getInt("army-spawn-per-tick", 4));
        final Set<Integer> ids = new LinkedHashSet<>();
        new BukkitRunnable() {
            int i = 0;
            @Override public void run() {
                for (int k = 0; k < perTick && i < dead.size(); k++, i++) {
                    LifeTrait t = dead.get(i);
                    Location at = t.center() != null ? t.center() : t.getNPC().getStoredLocation();
                    if (at != null && t.getNPC().spawn(at)) ids.add(t.getNPC().getId());   // kit is re-applied on spawn
                }
                if (i >= dead.size()) {
                    cancel();
                    lc.setSelection(s, new LinkedHashSet<>(ids));
                    LifeCommand.say(s, "Respawned <white>" + ids.size() + "</white> NPC(s) with their kits.");
                }
            }
        }.runTaskTimer(pl, 1L, 1L);
    }

    private void remove(CommandSender s, String[] a) {
        if (a.length < 3) { LifeCommand.err(s, "/npcx army remove <team|all>"); return; }
        String team = a[2].equalsIgnoreCase("all") ? null : a[2];
        List<LifeTrait> list = armyMembers(team);
        for (LifeTrait t : list) t.getNPC().destroy();
        lc.setSelection(s, new LinkedHashSet<>());
        LifeCommand.say(s, "Removed <white>" + list.size() + "</white> NPC(s).");
    }

    private void select(CommandSender s, String[] a) {
        if (a.length > 2 && a[2].equalsIgnoreCase("near")) {            // /npcx army select near <radius> [team]
            if (!(s instanceof Player p)) { LifeCommand.err(s, "Players only."); return; }
            double r = a.length > 3 ? Double.parseDouble(a[3]) : 30;
            Set<Integer> near = new LinkedHashSet<>();
            for (LifeTrait t : armyMembers(a.length > 4 ? a[4] : null)) {
                if (!t.getNPC().isSpawned()) continue;
                org.bukkit.entity.Entity e = t.getNPC().getEntity();
                if (e.getWorld() == p.getWorld() && e.getLocation().distance(p.getLocation()) <= r) near.add(t.getNPC().getId());
            }
            lc.setSelection(s, near);
            LifeCommand.say(s, "Selected <white>" + near.size() + "</white> army NPC(s) within " + (long) r + " blocks.");
            return;
        }
        List<LifeTrait> list = armyMembers(a.length > 2 ? a[2] : null);
        Set<Integer> ids = new LinkedHashSet<>();
        for (LifeTrait t : list) ids.add(t.getNPC().getId());
        lc.setSelection(s, ids);
        LifeCommand.say(s, "Selected <white>" + ids.size() + "</white> army NPC(s).");
    }

    private void list(CommandSender s) {
        Map<String, int[]> counts = new TreeMap<>();
        for (LifeTrait t : armyMembers(null)) {
            int[] c = counts.computeIfAbsent(t.team() == null ? "(no team)" : t.team(), x -> new int[2]);
            c[0]++;
            if (t.getNPC().isSpawned()) c[1]++;
        }
        if (counts.isEmpty()) { LifeCommand.say(s, "No army NPCs." + (pl.leftCount() > 0 ? " " + pl.leftCount() + " left for good after dying this session." : "")); return; }
        for (Map.Entry<String, int[]> en : counts.entrySet())
            LifeCommand.say(s, "<white>" + en.getKey() + "</white>: " + en.getValue()[1] + " alive / " + en.getValue()[0] + " total");
        if (pl.leftCount() > 0) LifeCommand.say(s, pl.leftCount() + " left for good after dying this session.");
    }

    private void vulnerable(CommandSender s, String[] a) {
        boolean on = a.length < 3 || !(a[2].equalsIgnoreCase("off") || a[2].equalsIgnoreCase("false"));
        pl.getConfig().set("army-vulnerable", on);
        for (LifeTrait t : armyMembers(null)) t.getNPC().setProtected(!on);
        LifeCommand.say(s, "Army NPCs are now <white>" + (on ? "vulnerable (they can die)" : "invulnerable") + "</white>. Put army-vulnerable in config.yml to keep it after a restart.");
    }

    private void names(CommandSender s, String[] a) {
        if (a.length > 2 && a[2].equalsIgnoreCase("reload")) pl.names().load();
        LifeCommand.say(s, "names.txt has <white>" + pl.names().size() + "</white> usable names (extra names are generated automatically).");
    }

    // ------------------------------------------------------------------ kits
    private void kit(CommandSender s, String[] a) {
        if (a.length < 3) { LifeCommand.err(s, "/npcx army kit save|apply|list|delete <name> [team]"); return; }
        KitManager km = pl.kits();
        String sub = a[2].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "list" -> LifeCommand.say(s, "Kits: <white>" + (km.names().isEmpty() ? "none" : String.join(", ", km.names())));
            case "save" -> {
                if (!(s instanceof Player p)) { LifeCommand.err(s, "Players only: the kit is copied from your inventory."); return; }
                if (a.length < 4) { LifeCommand.err(s, "/npcx army kit save <name>"); return; }
                km.saveFrom(p, a[3]);
                LifeCommand.say(s, "Saved kit <white>" + a[3].toLowerCase(Locale.ROOT) + "</white> from your inventory (armor, hotbar, offhand, everything).");
            }
            case "delete" -> {
                if (a.length < 4 || !km.delete(a[3])) LifeCommand.err(s, "No such kit.");
                else LifeCommand.say(s, "Deleted.");
            }
            case "apply" -> {
                if (a.length < 4) { LifeCommand.err(s, "/npcx army kit apply <kit|kitA:60,kitB:40> [team]"); return; }
                if (km.pick(a[3]) == null) { LifeCommand.err(s, "No such kit."); return; }
                List<LifeTrait> targets = a.length > 4 ? armyMembers(a[4]) : lc.targets(s);
                int n = 0;
                for (LifeTrait t : targets) {
                    String name = km.pick(a[3]);
                    t.setKit(name);
                    if (t.getNPC().isSpawned()) KitManager.apply(t.getNPC(), km.get(name));
                    n++;
                }
                LifeCommand.say(s, "Applied kit to <white>" + n + "</white> NPC(s). It is re-applied whenever they respawn.");
            }
            default -> LifeCommand.err(s, "/npcx army kit save|apply|list|delete");
        }
    }

    // ------------------------------------------------------------------ skill / style (used by the combat stages)
    private void skill(CommandSender s, String[] a) {
        if (a.length < 4) { LifeCommand.err(s, "/npcx army skill set <0-100> | mix <20/50/30> | style <name|random>"); return; }
        List<LifeTrait> ts = lc.targets(s);
        if (ts.isEmpty()) return;
        switch (a[2].toLowerCase(Locale.ROOT)) {
            case "set" -> { double v = Math.max(0, Math.min(100, Double.parseDouble(a[3]))) / 100.0; ts.forEach(t -> t.setSkill(v)); }
            case "mix" -> { double[] w = parseMix(a[3]); ts.forEach(t -> t.setSkill(rollSkill(w))); }
            case "style" -> ts.forEach(t -> t.setStyle(rollStyle(a[3])));
            default -> { LifeCommand.err(s, "set | mix | style"); return; }
        }
        LifeCommand.say(s, "Updated " + ts.size() + " NPC(s).");
    }

    // ------------------------------------------------------------------ speech
    private void speech(CommandSender s, String[] a) {
        if (!(s instanceof Player p)) { LifeCommand.err(s, "Players only (the army turns to face YOU)."); return; }
        if (a.length > 2 && a[2].equalsIgnoreCase("stop")) {
            int n = pl.speech().stop(p);
            LifeCommand.say(s, n > 0 ? "Speech over - the army goes back to what it was doing." : "No speech is running.");
            return;
        }
        Map<String, String> opt = options(a, 2);
        int seconds = 30;
        for (int i = 2; i < a.length; i++) if (a[i].matches("\\d+")) seconds = Integer.parseInt(a[i]);
        double radius = 40;
        try {
            seconds = Integer.parseInt(opt.getOrDefault("seconds", String.valueOf(seconds)));
            radius = Double.parseDouble(opt.getOrDefault("radius", "40"));
        } catch (NumberFormatException ex) { LifeCommand.err(s, "Invalid number."); return; }
        String in = opt.getOrDefault("intensity", "normal").toLowerCase(Locale.ROOT);
        double intensity = in.equals("low") ? 0.6 : in.equals("high") ? 1.6 : 1.0;

        List<LifeTrait> audience = new ArrayList<>();
        Set<Integer> sel = lc.selection(s);
        if (!opt.containsKey("team") && sel != null && !sel.isEmpty()) audience = lc.targets(s);
        else for (LifeTrait t : armyMembers(opt.get("team"))) {
            if (!t.getNPC().isSpawned()) continue;
            org.bukkit.entity.Entity e = t.getNPC().getEntity();
            if (e.getWorld() == p.getWorld() && e.getLocation().distance(p.getLocation()) <= radius) audience.add(t);
        }
        int n = pl.speech().start(p, audience, seconds, intensity);
        if (n == 0) LifeCommand.err(s, "Nobody within " + (long) radius + " blocks to hear you (spawn an army, or select NPCs).");
        else LifeCommand.say(s, "<white>" + n + "</white> NPC(s) turn to listen for " + seconds + "s. <gray>/npcx army speech stop</gray> to end it.");
    }

    // ------------------------------------------------------------------ tab completion
    List<String> tab(String[] a) {
        List<String> out = new ArrayList<>();
        if (a.length == 2) out.addAll(List.of("spawn", "respawn", "remove", "select", "list", "kit", "vulnerable", "names", "skill", "route", "march", "speech"));
        else if (a.length == 3) {
            switch (a[1].toLowerCase(Locale.ROOT)) {
                case "march" -> { out.addAll(pl.march().routeNames()); out.addAll(List.of("here", "stop", "pause", "resume", "status")); }
                case "route" -> out.addAll(List.of("create", "add", "undo", "list", "show", "delete"));
                case "speech" -> out.add("stop");
                case "kit" -> out.addAll(List.of("save", "apply", "list", "delete"));
                case "skill" -> out.addAll(List.of("set", "mix", "style"));
                case "vulnerable" -> out.addAll(List.of("on", "off"));
                case "remove", "select", "respawn" -> { out.addAll(pl.teams().names()); if (a[1].equalsIgnoreCase("remove")) out.add("all"); if (a[1].equalsIgnoreCase("select")) out.add("near"); }
                default -> { }
            }
        } else if (a.length == 4 && a[1].equalsIgnoreCase("kit")) out.addAll(pl.kits().names());
        else if (a.length == 4 && a[1].equalsIgnoreCase("spawn")) out.addAll(pl.teams().names());
        else if (a.length >= 5 && a[1].equalsIgnoreCase("spawn")) out.addAll(Arrays.asList("kit=", "mix=", "style=", "formation=", "spacing=", "skin="));
        String last = a[a.length - 1].toLowerCase(Locale.ROOT);
        out.removeIf(x -> !x.toLowerCase(Locale.ROOT).startsWith(last));
        return out;
    }
}
