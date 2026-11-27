package dev.npclife;

import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.npc.NPC;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;

final class LifeCommand implements TabExecutor {
    private static final List<String> SUBS = List.of("select", "behavior", "personality", "look", "jump", "sprint",
            "sneak", "eat", "army", "team", "combat", "sprintjump", "use", "group", "sequence", "wander", "interact", "swing", "wave", "hold", "hotbar", "stop", "debug", "reload", "help");
    private static final List<String> BEH = List.of("enable", "disable", "randomize", "set", "personality", "info", "save",
            "probability", "cooldown", "duration", "toggle", "think", "awareness", "lookspeed", "speed", "affinity", "walkrange");

    private final NPCLifePlugin pl;
    private final Map<String, Set<Integer>> selections = new HashMap<>();
    private final Random rng = new Random();

    private final ArmyCommand army;
    private final TeamCommand teamCmd;
    private final CombatCommand combatCmd;

    LifeCommand(NPCLifePlugin pl) { this.pl = pl; this.army = new ArmyCommand(pl, this); this.teamCmd = new TeamCommand(pl, this); this.combatCmd = new CombatCommand(pl, this); }

    /** The sender's multi-selection (null = none). */
    Set<Integer> selection(CommandSender s) { return selections.get(key(s)); }

    void setSelection(CommandSender s, Set<Integer> ids) { selections.put(key(s), ids); }

    // ------------------------------------------------------------------ messaging / targets
    static void say(CommandSender s, String mm) { s.sendMessage(MiniMessage.miniMessage().deserialize("<gray>[<aqua>NPCLife</aqua>]</gray> " + mm)); }
    static void err(CommandSender s, String m) { say(s, "<red>" + m); }
    private static String key(CommandSender s) { return s instanceof Player p ? p.getUniqueId().toString() : "console"; }

    List<LifeTrait> targets(CommandSender s) {
        List<LifeTrait> out = new ArrayList<>();
        Set<Integer> sel = selections.get(key(s));
        if (sel != null && !sel.isEmpty()) {
            for (int id : sel) { NPC n = CitizensAPI.getNPCRegistry().getById(id); if (n != null) out.add(n.getOrAddTrait(LifeTrait.class)); }
        } else {
            NPC n = CitizensAPI.getDefaultNPCSelector().getSelected(s);
            if (n != null) out.add(n.getOrAddTrait(LifeTrait.class));
        }
        if (out.isEmpty()) err(s, "No NPC selected. Use <white>/npc sel</white> or <white>/npcx select all|near <radius>|ids 1,2,3</white>.");
        return out;
    }

    private static double[] range(String s) {
        String[] p = s.split("-");
        double a = Double.parseDouble(p[0]), b = p.length > 1 ? Double.parseDouble(p[1]) : a;
        return new double[]{Math.min(a, b), Math.max(a, b)};
    }

    private static boolean flag(String[] a, int i, boolean current) {
        if (a.length <= i) return !current;
        String v = a[i].toLowerCase(Locale.ROOT);
        if (v.equals("on") || v.equals("true") || v.equals("yes")) return true;
        if (v.equals("off") || v.equals("false") || v.equals("no")) return false;
        return !current;
    }

    // ------------------------------------------------------------------ dispatch
    @Override
    public boolean onCommand(CommandSender s, Command c, String label, String[] a) {
        if (!s.hasPermission("npclife.use")) { err(s, "No permission."); return true; }
        if (a.length == 0) { help(s); return true; }
        try {
            switch (a[0].toLowerCase(Locale.ROOT)) {
                case "select" -> select(s, a);
                case "behavior", "behaviour", "b" -> behavior(s, a);
                case "personality" -> personality(s, a);
                case "look", "camera" -> look(s, a);
                case "jump" -> jump(s, a);
                case "sprint" -> sprint(s, a);
                case "sneak" -> sneak(s, a);
                case "sprintjump" -> sprintJump(s, a);
                case "eat" -> targets(s).forEach(t -> t.brain().eatNow());
                case "army" -> army.handle(s, a);
                case "team" -> teamCmd.handle(s, a);
                case "combat" -> combatCmd.handle(s, a);
                case "use" -> use(s, a);
                case "group" -> group(s, a);
                case "sequence", "seq" -> sequence(s, a);
                case "wander" -> wander(s, a);
                case "interact" -> { for (LifeTrait t : targets(s)) if (!t.brain().force(ActionType.INTERACT, -1)) err(s, "NPC " + t.getNPC().getId() + ": no door/lever/button/chest within 3 blocks."); }
                case "swing" -> targets(s).forEach(t -> t.brain().swing(false));
                case "wave" -> targets(s).forEach(t -> t.brain().wave());
                case "hold" -> {
                    if (a.length < 2) { err(s, "/npcx hold <MATERIAL>"); return true; }
                    Material m = Material.matchMaterial(a[1]);
                    if (m == null) { err(s, "Unknown material."); return true; }
                    targets(s).forEach(t -> t.brain().hold(m));
                }
                case "hotbar" -> hotbar(s, a);
                case "stop" -> targets(s).forEach(t -> t.brain().stopAll());
                case "debug" -> {
                    say(s, "Head control: " + HeadUtil.describe() + " | item-use poses: " + (AnimUtil.available() ? "available" : "unavailable"));
                    for (LifeTrait t : targets(s))
                        say(s, "#" + t.getNPC().getId() + " " + (t.isEnabled() ? "<green>on" : "<red>off") + "</> personality=" + t.personalityName()
                                + " active: " + t.brain().describe());
                }
                case "reload" -> { pl.reload(); say(s, "Reloaded config and personalities."); }
                default -> help(s);
            }
        } catch (NumberFormatException ex) { err(s, "Invalid number."); }
        catch (IllegalArgumentException ex) { err(s, String.valueOf(ex.getMessage())); }
        return true;
    }

    private void help(CommandSender s) {
        say(s, "<yellow>Commands</yellow> (alias /npcx):");
        for (String l : new String[]{
                "select all | near <radius> | ids 1,2,3 | clear   <gray>- multi-NPC selection</gray>",
                "behavior enable|disable|randomize|info",
                "behavior set <personality>   <gray>(energetic lazy nervous curious social explorer default + saved)</gray>",
                "behavior probability <action> <0-100> | cooldown <action> <min-max> | duration <action> <min-max>",
                "behavior toggle <action> [on|off] | think <min-max> | awareness <n> | lookspeed <n> | speed <n> | walkrange <min-max> | affinity <0-1>",
                "behavior save <name>   <gray>- save this NPC's setup as a personality</gray>",
                "personality list|info <name>",
                "look <x> <y> <z> | rotate <yaw> <pitch> | player <name> | npc <id> | stop",
                "jump now|toggle|hop <sec>|auto [on|off]   sprint on|off|toggle|auto   sneak on|off|toggle|spam [sec]|auto",
                "wander radius <n> | center [here|x y z] | now | info",
                "army spawn|select|kit|respawn|remove|list ...   <gray>- spawn 1-100 NPCs as an army (see /npcx army)</gray>",
                "team create|add|hostile|list|info ...   <gray>- teams for players and NPCs (see /npcx team)</gray>",
                "combat on|off|info|target|test|selftest ...   <gray>- PvP AI (see /npcx combat)</gray>",
                "army route ... | army march <route|here|x y z> ... | army speech [seconds]   <gray>- march the army, give speeches</gray>",
                "eat   <gray>- eat now (also happens automatically when the NPC gets hungry)</gray>",
                "sprintjump [on|off]   <gray>- sprint + jump continuously while travelling</gray>",
                "use continuous|once [sec]|stop   <gray>- hold right-click on the held item (eat/drink/block/bow pose)</gray>",
                "group set <name> | clear | list   <gray>- group members seek each other out</gray>",
                "sequence list|show <n>|create <n>|add <n> <step>|delete <n>|record <n>|save|play <n> [loop] [combine]|stop",
                "wander home <blocks|off|default>   <gray>- return-to-spawn distance</gray>",
                "interact | swing | wave | hold <item> | hotbar <item,item,...> | stop | debug | reload",
                "<gray>Actions: " + Arrays.stream(ActionType.values()).map(x -> x.name().toLowerCase(Locale.ROOT)).collect(Collectors.joining(", ")) + "</gray>"})
            say(s, "<white>/npcx " + l);
    }

    // ------------------------------------------------------------------ selection
    private void select(CommandSender s, String[] a) {
        if (a.length < 2) { err(s, "/npcx select all|near <radius>|ids <list>|clear"); return; }
        Set<Integer> set = new LinkedHashSet<>();
        switch (a[1].toLowerCase(Locale.ROOT)) {
            case "clear" -> { selections.remove(key(s)); say(s, "Selection cleared (using Citizens' selected NPC)."); return; }
            case "all" -> { for (NPC n : CitizensAPI.getNPCRegistry()) if (n.isSpawned() && n.hasTrait(LifeTrait.class)) set.add(n.getId()); }
            case "near" -> {
                if (!(s instanceof Player p)) { err(s, "Players only."); return; }
                double r = a.length > 2 ? Double.parseDouble(a[2]) : 30;
                for (NPC n : CitizensAPI.getNPCRegistry())
                    if (n.isSpawned() && n.getEntity().getWorld() == p.getWorld() && n.getEntity().getLocation().distance(p.getLocation()) <= r) set.add(n.getId());
            }
            case "ids" -> { if (a.length > 2) for (String x : a[2].split(",")) set.add(Integer.parseInt(x.trim())); }
            default -> { err(s, "Unknown selection type."); return; }
        }
        selections.put(key(s), set);
        say(s, "Selected <white>" + set.size() + "</white> NPC(s): " + set);
    }

    // ------------------------------------------------------------------ behavior
    private void behavior(CommandSender s, String[] a) {
        if (a.length < 2) { err(s, "/npcx behavior <" + String.join("|", BEH) + ">"); return; }
        List<LifeTrait> ts = targets(s);
        if (ts.isEmpty()) return;
        String sub = a[1].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "enable" -> { ts.forEach(t -> t.setEnabled(true)); say(s, "Autonomous behavior <green>enabled</green> for " + ts.size() + " NPC(s)."); }
            case "disable" -> { ts.forEach(t -> t.setEnabled(false)); say(s, "Autonomous behavior <red>disabled</red> for " + ts.size() + " NPC(s)."); }
            case "randomize" -> {
                for (LifeTrait t : ts) t.applyPersonality(Personality.random(rng, "random-" + Integer.toString(rng.nextInt(0xFFFF), 16)));
                say(s, "Randomized " + ts.size() + " NPC(s). Use <white>/npcx behavior enable</white> to start them.");
            }
            case "set", "personality" -> {
                if (a.length < 3) { err(s, "Available: " + String.join(", ", pl.personalities().names())); return; }
                if (pl.personalities().get(a[2]) == null) { err(s, "Unknown personality. Available: " + String.join(", ", pl.personalities().names())); return; }
                for (LifeTrait t : ts) t.applyPersonality(pl.personalities().get(a[2]));
                say(s, "Applied personality <white>" + a[2].toLowerCase(Locale.ROOT) + "</white> to " + ts.size() + " NPC(s).");
            }
            case "info" -> info(s, ts.get(0));
            case "save" -> {
                if (a.length < 3) { err(s, "/npcx behavior save <name>"); return; }
                if (pl.personalities().save(a[2], ts.get(0).personality())) say(s, "Saved personality <white>" + a[2] + "</white>.");
                else err(s, "That name is a built-in personality; choose another.");
            }
            case "probability", "cooldown", "duration", "toggle" -> {
                if (a.length < 3) { err(s, "/npcx behavior " + sub + " <action> ..."); return; }
                ActionType ty = ActionType.parse(a[2]);
                if (ty == null) { err(s, "Unknown action. See /npcx help."); return; }
                for (LifeTrait t : ts) {
                    ActionConfig c = t.personality().cfg(ty);
                    switch (sub) {
                        case "probability" -> { if (a.length < 4) { err(s, "Missing value 0-100."); return; } c.probability = Personality.clamp(Double.parseDouble(a[3]), 0, 100); }
                        case "cooldown" -> { if (a.length < 4) { err(s, "Missing range, e.g. 5-15"); return; } double[] r = range(a[3]); c.cdMin = r[0]; c.cdMax = r[1]; }
                        case "duration" -> { if (a.length < 4) { err(s, "Missing range, e.g. 1-3"); return; } double[] r = range(a[3]); c.durMin = r[0]; c.durMax = r[1]; }
                        default -> c.enabled = flag(a, 3, c.enabled);
                    }
                }
                ActionConfig c0 = ts.get(0).personality().cfg(ty);
                say(s, ty.name().toLowerCase(Locale.ROOT) + ": " + (c0.enabled ? "on" : "off") + ", " + fmt(c0.probability) + "%, cooldown "
                        + fmt(c0.cdMin) + "-" + fmt(c0.cdMax) + "s, duration " + fmt(c0.durMin) + "-" + fmt(c0.durMax) + "s");
            }
            case "think", "walkrange" -> {
                if (a.length < 3) { err(s, "Missing range."); return; }
                double[] r = range(a[2]);
                for (LifeTrait t : ts) {
                    if (sub.equals("think")) { t.personality().thinkMin = Math.max(0.2, r[0]); t.personality().thinkMax = Math.max(0.2, r[1]); }
                    else { t.personality().walkMin = r[0]; t.personality().walkMax = r[1]; }
                }
                say(s, "Updated " + sub + ".");
            }
            case "awareness", "lookspeed", "speed", "affinity" -> {
                if (a.length < 3) { err(s, "Missing value."); return; }
                double v = Double.parseDouble(a[2]);
                for (LifeTrait t : ts) {
                    Personality p = t.personality();
                    switch (sub) {
                        case "awareness" -> p.awareness = Personality.clamp(v, 2, 64);
                        case "lookspeed" -> p.lookSpeed = Personality.clamp(v, 1, 60);
                        case "speed" -> p.speedMod = Personality.clamp(v, 0.3, 2.0);
                        default -> p.npcAffinity = Personality.clamp(v, 0, 1);
                    }
                }
                say(s, "Updated " + sub + ".");
            }
            default -> err(s, "Unknown behavior subcommand.");
        }
    }

    private static String fmt(double d) { return d == Math.rint(d) ? String.valueOf((long) d) : String.format(Locale.ROOT, "%.1f", d); }

    private void info(CommandSender s, LifeTrait t) {
        Personality p = t.personality();
        say(s, "<yellow>NPC #" + t.getNPC().getId() + "</yellow> " + (t.isEnabled() ? "<green>enabled" : "<red>disabled") + "</> personality=<white>" + t.personalityName() + "</white>");
        say(s, "think " + fmt(p.thinkMin) + "-" + fmt(p.thinkMax) + "s, awareness " + fmt(p.awareness) + ", walk " + fmt(p.walkMin) + "-" + fmt(p.walkMax)
                + ", lookSpeed " + fmt(p.lookSpeed) + ", speed x" + fmt(p.speedMod) + ", npcAffinity " + fmt(p.npcAffinity));
        Location c = t.center();
        say(s, "wander radius " + fmt(t.effectiveRadius()) + (c == null ? "" : " centre " + c.getBlockX() + "," + c.getBlockY() + "," + c.getBlockZ() + " " + c.getWorld().getName()));
        for (ActionType ty : ActionType.values()) {
            ActionConfig c0 = p.cfg(ty);
            say(s, (c0.enabled ? "<green>" : "<red>") + ty.name().toLowerCase(Locale.ROOT) + "</> " + fmt(c0.probability) + "% cd " + fmt(c0.cdMin) + "-" + fmt(c0.cdMax)
                    + "s dur " + fmt(c0.durMin) + "-" + fmt(c0.durMax) + "s");
        }
    }

    private void personality(CommandSender s, String[] a) {
        if (a.length < 2 || a[1].equalsIgnoreCase("list")) { say(s, "Personalities: <white>" + String.join(", ", pl.personalities().names())); return; }
        if (a[1].equalsIgnoreCase("info") && a.length > 2) {
            Personality p = pl.personalities().get(a[2]);
            if (p == null) { err(s, "Unknown personality."); return; }
            for (ActionType ty : ActionType.values()) { ActionConfig c = p.cfg(ty); say(s, ty.name().toLowerCase(Locale.ROOT) + " " + fmt(c.probability) + "% cd " + fmt(c.cdMin) + "-" + fmt(c.cdMax) + "s"); }
            return;
        }
        err(s, "/npcx personality list | info <name>   (assign with /npcx behavior set <name>)");
    }

    // ------------------------------------------------------------------ look
    private void look(CommandSender s, String[] a) {
        if (a.length < 2) { err(s, "/npcx look <x y z | rotate yaw pitch | player name | npc id | stop>"); return; }
        List<LifeTrait> ts = targets(s);
        if (ts.isEmpty()) return;
        String sub = a[1].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "stop" -> ts.forEach(t -> t.brain().stopLook());
            case "rotate" -> {
                if (a.length < 4) { err(s, "/npcx look rotate <yaw> <pitch>  (yaw 0=south, 90=west, 180=north, -90=east)"); return; }
                float y = Float.parseFloat(a[2]), p = Float.parseFloat(a[3]);
                ts.forEach(t -> t.brain().manualLookAngles(y, p));
            }
            case "player" -> {
                Player target = a.length > 2 ? Bukkit.getPlayerExact(a[2]) : (s instanceof Player p ? p : null);
                if (target == null) { err(s, "Player not found."); return; }
                ts.forEach(t -> t.brain().manualLookEntity(target));
            }
            case "npc" -> {
                NPC n = a.length > 2 ? CitizensAPI.getNPCRegistry().getById(Integer.parseInt(a[2])) : null;
                if (n == null || !n.isSpawned()) { err(s, "NPC not found/spawned."); return; }
                Entity e = n.getEntity();
                for (LifeTrait t : ts) if (t.getNPC() != n) t.brain().manualLookEntity(e);
            }
            default -> {
                if (a.length < 4) { err(s, "/npcx look <x> <y> <z>"); return; }
                for (LifeTrait t : ts) {
                    if (!t.getNPC().isSpawned()) continue;
                    Location base = t.getNPC().getEntity().getLocation();
                    t.brain().manualLookAt(new Location(base.getWorld(), coord(a[1], base.getX()), coord(a[2], base.getY()), coord(a[3], base.getZ())));
                }
            }
        }
        say(s, "Look command sent.");
    }

    private static double coord(String s, double rel) {
        return s.startsWith("~") ? rel + (s.length() > 1 ? Double.parseDouble(s.substring(1)) : 0) : Double.parseDouble(s);
    }

    // ------------------------------------------------------------------ jump / sprint / sneak
    private void jump(CommandSender s, String[] a) {
        List<LifeTrait> ts = targets(s);
        if (ts.isEmpty()) return;
        String sub = a.length > 1 ? a[1].toLowerCase(Locale.ROOT) : "now";
        switch (sub) {
            case "now" -> ts.forEach(t -> t.brain().jumpNow());
            case "toggle" -> { for (LifeTrait t : ts) t.brain().setManualHop(!t.brain().manualHop()); say(s, "Continuous hopping toggled."); }
            case "hop" -> { double sec = a.length > 2 ? Double.parseDouble(a[2]) : 5; ts.forEach(t -> t.brain().force(ActionType.JUMP, sec)); }
            case "auto" -> { for (LifeTrait t : ts) { ActionConfig c = t.personality().cfg(ActionType.JUMP); c.enabled = flag(a, 2, c.enabled); } say(s, "Autonomous jumping updated."); }
            default -> err(s, "/npcx jump now|toggle|hop <sec>|auto [on|off]");
        }
    }

    private void sprint(CommandSender s, String[] a) {
        List<LifeTrait> ts = targets(s);
        if (ts.isEmpty()) return;
        String sub = a.length > 1 ? a[1].toLowerCase(Locale.ROOT) : "toggle";
        switch (sub) {
            case "on", "off", "toggle" -> { for (LifeTrait t : ts) t.brain().setManualSprint(sub.equals("toggle") ? !t.brain().manualSprint() : sub.equals("on")); }
            case "auto" -> { for (LifeTrait t : ts) { ActionConfig c = t.personality().cfg(ActionType.SPRINT); c.enabled = flag(a, 2, c.enabled); } }
            default -> { err(s, "/npcx sprint on|off|toggle|auto [on|off]"); return; }
        }
        say(s, "Sprint updated.");
    }

    private void sneak(CommandSender s, String[] a) {
        List<LifeTrait> ts = targets(s);
        if (ts.isEmpty()) return;
        String sub = a.length > 1 ? a[1].toLowerCase(Locale.ROOT) : "toggle";
        switch (sub) {
            case "on", "off", "toggle" -> { for (LifeTrait t : ts) t.brain().setManualSneak(sub.equals("toggle") ? !t.brain().manualSneak() : sub.equals("on")); }
            case "spam" -> { double sec = a.length > 2 ? Double.parseDouble(a[2]) : 4; ts.forEach(t -> t.brain().force(ActionType.SNEAK_SPAM, sec)); }
            case "auto" -> { for (LifeTrait t : ts) { ActionConfig c = t.personality().cfg(ActionType.SNEAK); c.enabled = flag(a, 2, c.enabled); } }
            default -> { err(s, "/npcx sneak on|off|toggle|spam [sec]|auto [on|off]"); return; }
        }
        say(s, "Sneak updated.");
    }

    // ------------------------------------------------------------------ wander
    private void wander(CommandSender s, String[] a) {
        if (a.length < 2) { err(s, "/npcx wander radius <n> | center [here|x y z] | now | info"); return; }
        List<LifeTrait> ts = targets(s);
        if (ts.isEmpty()) return;
        switch (a[1].toLowerCase(Locale.ROOT)) {
            case "radius" -> {
                if (a.length < 3) { err(s, "/npcx wander radius <blocks>"); return; }
                double r = Double.parseDouble(a[2]);
                double cap = pl.getConfig().getDouble("max-wander-radius", 100);
                ts.forEach(t -> t.setRadius(r));
                say(s, "Radius set to " + fmt(Math.min(r, cap)) + (r > cap ? " <gray>(capped by max-wander-radius in config.yml)</gray>" : "") + ".");
            }
            case "center" -> {
                for (LifeTrait t : ts) {
                    if (!t.getNPC().isSpawned()) continue;
                    Location here = t.getNPC().getEntity().getLocation();
                    if (a.length >= 5) t.setCenter(new Location(here.getWorld(), Double.parseDouble(a[2]), Double.parseDouble(a[3]), Double.parseDouble(a[4])));
                    else t.setCenter(here);
                }
                say(s, "Wander centre set.");
            }
            case "home" -> {
                String v = a.length > 2 ? a[2].toLowerCase(Locale.ROOT) : "default";
                double d = v.equals("off") ? 0 : v.equals("default") ? -1 : Double.parseDouble(v);
                ts.forEach(t -> t.setHomeDistance(d));
                say(s, "Return-home distance: " + (d == 0 ? "off" : d < 0 ? "config default" : fmt(d) + " blocks") + ".");
            }
            case "now" -> { for (LifeTrait t : ts) if (!t.brain().force(ActionType.WALK, -1)) err(s, "NPC " + t.getNPC().getId() + ": no valid destination found."); }
            case "info" -> ts.forEach(t -> { Location c = t.center(); say(s, "#" + t.getNPC().getId() + " radius " + fmt(t.effectiveRadius()) + ", return-home " + (t.homeDistance() > 0 ? fmt(t.homeDistance()) : "off") + (c == null ? " (no centre yet)" : " centre " + c.getBlockX() + "," + c.getBlockY() + "," + c.getBlockZ())); });
            default -> err(s, "Unknown wander subcommand.");
        }
    }

    // ------------------------------------------------------------------ sprintjump / use / group
    private void sprintJump(CommandSender s, String[] a) {
        List<LifeTrait> ts = targets(s);
        if (ts.isEmpty()) return;
        for (LifeTrait t : ts) t.brain().setSprintJump(flag(a, 1, t.brain().sprintJump()));
        say(s, "Sprint-jump updated. NPCs keep sprint-jumping around until you toggle it off or run /npcx stop.");
    }

    private void use(CommandSender s, String[] a) {
        List<LifeTrait> ts = targets(s);
        if (ts.isEmpty()) return;
        if (!AnimUtil.available()) { err(s, "Citizens' PlayerAnimation class wasn't found, so item-use poses aren't available on this Citizens build."); return; }
        String sub = a.length > 1 ? a[1].toLowerCase(Locale.ROOT) : "once";
        switch (sub) {
            case "continuous", "on", "hold" -> ts.forEach(t -> t.brain().setUseContinuous(true));
            case "off", "stop" -> ts.forEach(t -> t.brain().stopUse());
            case "once" -> { double sec = a.length > 2 ? Double.parseDouble(a[2]) : 3; ts.forEach(t -> t.brain().useFor(sec)); }
            default -> { err(s, "/npcx use continuous | once [sec] | stop"); return; }
        }
        say(s, "Use-item updated. The NPC needs something in hand: <white>/npcx hold <item></white>.");
    }

    private void group(CommandSender s, String[] a) {
        if (a.length >= 2 && a[1].equalsIgnoreCase("list")) {
            Map<String, Integer> counts = new java.util.TreeMap<>();
            for (NPC n : CitizensAPI.getNPCRegistry())
                if (n.hasTrait(LifeTrait.class)) { LifeTrait lt = n.getOrAddTrait(LifeTrait.class); if (lt.group() != null) counts.merge(lt.group(), 1, Integer::sum); }
            say(s, "Groups: <white>" + (counts.isEmpty() ? "none" : counts.toString()));
            return;
        }
        List<LifeTrait> ts = targets(s);
        if (ts.isEmpty()) return;
        if (a.length >= 3 && a[1].equalsIgnoreCase("set")) { ts.forEach(t -> t.setGroup(a[2].toLowerCase(Locale.ROOT))); say(s, "Group set for " + ts.size() + " NPC(s)."); }
        else if (a.length >= 2 && a[1].equalsIgnoreCase("clear")) { ts.forEach(t -> t.setGroup(null)); say(s, "Group cleared."); }
        else err(s, "/npcx group set <name> | clear | list");
    }

    // ------------------------------------------------------------------ sequences
    private void sequence(CommandSender s, String[] a) {
        if (a.length < 2) { err(s, "/npcx sequence list|show|create|add|delete|record|save|play|stop"); return; }
        SequenceManager sm = pl.sequences();
        String sub = a[1].toLowerCase(Locale.ROOT);
        String name = a.length > 2 ? a[2].toLowerCase(Locale.ROOT) : null;
        switch (sub) {
            case "list" -> say(s, "Sequences: <white>" + (sm.names().isEmpty() ? "none" : String.join(", ", sm.names())));
            case "show" -> {
                List<String> l = name == null ? null : sm.get(name);
                if (l == null) { err(s, "No such sequence."); return; }
                for (int i = 0; i < l.size(); i++) say(s, "<gray>" + (i + 1) + ".</gray> " + l.get(i).replace("<", "\\<"));
            }
            case "create" -> { if (name == null) { err(s, "Name?"); return; } if (sm.get(name) == null) sm.put(name, new ArrayList<>()); say(s, "Sequence <white>" + name + "</white> ready. Add steps with /npcx sequence add " + name + " <step>"); }
            case "delete" -> { if (name == null || !sm.delete(name)) err(s, "No such sequence."); else say(s, "Deleted."); }
            case "add" -> {
                if (name == null || a.length < 4) { err(s, "/npcx sequence add <name> <step...>   e.g. WAIT 2-5 | WALK 10 0 5 | ?30 JUMP | IFNEAR WAVE"); return; }
                List<String> l = sm.get(name);
                if (l == null) l = new ArrayList<>();
                l.add(String.join(" ", Arrays.copyOfRange(a, 3, a.length)));
                sm.put(name, l);
                say(s, "Added step " + l.size() + ".");
            }
            case "record" -> {
                if (!(s instanceof Player p)) { err(s, "Players only."); return; }
                if (name == null) { err(s, "/npcx sequence record <name>"); return; }
                pl.recorder().start(p, name);
                say(s, "<red>Recording</red> <white>" + name + "</white>: walk, look, jump, sneak, sprint, swing and switch items. Finish with <white>/npcx sequence save</white>.");
            }
            case "save", "finish" -> {
                if (!(s instanceof Player p)) { err(s, "Players only."); return; }
                Map.Entry<String, List<String>> r = pl.recorder().finish(p);
                if (r == null) { err(s, "You are not recording."); return; }
                sm.put(r.getKey(), r.getValue());
                say(s, "Saved <white>" + r.getKey() + "</white> with " + r.getValue().size() + " steps. Play it with /npcx sequence play " + r.getKey());
            }
            case "play" -> {
                if (name == null || sm.get(name) == null) { err(s, "No such sequence."); return; }
                boolean loop = false, combine = false;
                for (int i = 3; i < a.length; i++) {
                    if (a[i].equalsIgnoreCase("loop")) loop = true;
                    if (a[i].equalsIgnoreCase("combine")) combine = true;
                }
                List<LifeTrait> ts = targets(s);
                for (LifeTrait t : ts) t.playSequence(name, loop, combine);
                if (!ts.isEmpty()) say(s, "Playing <white>" + name + "</white> on " + ts.size() + " NPC(s)" + (loop ? " (loop)" : "") + (combine ? " (autonomy stays active)" : "") + ".");
            }
            case "stop" -> targets(s).forEach(LifeTrait::stopSequence);
            default -> err(s, "Unknown sequence subcommand.");
        }
    }

    private void hotbar(CommandSender s, String[] a) {
        if (a.length < 2) { err(s, "/npcx hotbar DIAMOND_SWORD,APPLE,TORCH  (or 'clear')"); return; }
        List<LifeTrait> ts = targets(s);
        List<Material> mats = new ArrayList<>();
        if (!a[1].equalsIgnoreCase("clear"))
            for (String x : a[1].split(",")) { Material m = Material.matchMaterial(x.trim()); if (m == null) { err(s, "Unknown material: " + x); return; } mats.add(m); }
        for (LifeTrait t : ts) { t.hotbar().clear(); t.hotbar().addAll(mats); }
        say(s, "Hotbar set (" + mats.size() + " items). The SWAP_ITEM action cycles through them.");
    }

    // ------------------------------------------------------------------ tab completion
    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String label, String[] a) {
        if (a.length >= 2 && a[0].equalsIgnoreCase("army")) return army.tab(a);
        if (a.length >= 2 && a[0].equalsIgnoreCase("team")) return teamCmd.tab(a);
        if (a.length >= 2 && a[0].equalsIgnoreCase("combat")) return combatCmd.tab(a);
        List<String> out = new ArrayList<>();
        if (a.length == 1) out.addAll(SUBS);
        else if (a.length == 2) {
            switch (a[0].toLowerCase(Locale.ROOT)) {
                case "select" -> out.addAll(List.of("all", "near", "ids", "clear"));
                case "behavior", "b" -> out.addAll(BEH);
                case "personality" -> out.addAll(List.of("list", "info"));
                case "look" -> out.addAll(List.of("rotate", "player", "npc", "stop"));
                case "jump" -> out.addAll(List.of("now", "toggle", "hop", "auto"));
                case "sprint" -> out.addAll(List.of("on", "off", "toggle", "auto"));
                case "sneak" -> out.addAll(List.of("on", "off", "toggle", "spam", "auto"));
                case "wander" -> out.addAll(List.of("radius", "center", "home", "now", "info"));
                case "use" -> out.addAll(List.of("continuous", "once", "stop"));
                case "group" -> out.addAll(List.of("set", "clear", "list"));
                case "sequence", "seq" -> out.addAll(List.of("list", "show", "create", "add", "delete", "record", "save", "play", "stop"));
                case "sprintjump" -> out.addAll(List.of("on", "off"));
                default -> { }
            }
        } else if (a.length == 3 && a[0].equalsIgnoreCase("behavior")) {
            switch (a[1].toLowerCase(Locale.ROOT)) {
                case "set", "personality" -> out.addAll(pl.personalities().names());
                case "probability", "cooldown", "duration", "toggle" -> { for (ActionType t : ActionType.values()) out.add(t.name().toLowerCase(Locale.ROOT)); }
                default -> { }
            }
        } else if (a.length == 3 && a[0].equalsIgnoreCase("personality") && a[1].equalsIgnoreCase("info")) out.addAll(pl.personalities().names());
        String last = a[a.length - 1].toLowerCase(Locale.ROOT);
        return out.stream().filter(x -> x.toLowerCase(Locale.ROOT).startsWith(last)).collect(Collectors.toList());
    }
}
