package dev.npclife;

import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.ai.Navigator;
import net.citizensnpcs.api.npc.NPC;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** /npcx combat ... : switch combat on/off, inspect, force targets, and run built-in tests. */
final class CombatCommand {
    private final NPCLifePlugin pl;
    private final LifeCommand lc;

    CombatCommand(NPCLifePlugin pl, LifeCommand lc) { this.pl = pl; this.lc = lc; }

    private void help(CommandSender s) {
        for (String l : new String[]{
                "combat on|off|auto   <gray>- fighting on/off for the selected NPCs (auto = army NPCs fight)</gray>",
                "combat info   <gray>- state, target, health, confidence, items of the first selected NPC</gray>",
                "combat target <player|npcId> | stop | style <name> | movemode hybrid|velocity|navigator",
                "combat test clutch|web|heal   <gray>- drop an NPC from 45 blocks / trap it in a web / drop its health</gray>",
                "combat selftest   <gray>- checks which Paper/Citizens features this server build supports</gray>"})
            LifeCommand.say(s, "<white>/npcx " + l);
    }

    void handle(CommandSender s, String[] a) {
        if (a.length < 2) { help(s); return; }
        String sub = a[1].toLowerCase(Locale.ROOT);
        try {
            switch (sub) {
                case "selftest" -> selftest(s);
                case "on", "off", "auto" -> {
                    List<LifeTrait> ts = lc.targets(s);
                    for (LifeTrait t : ts) t.setCombatMode(sub.equals("auto") ? null : sub);
                    if (!ts.isEmpty()) LifeCommand.say(s, "Combat <white>" + sub + "</white> for " + ts.size() + " NPC(s).");
                }
                case "info", "debug" -> info(s);
                case "stop" -> { for (LifeTrait t : lc.targets(s)) t.brain().combat().giveUp(); LifeCommand.say(s, "Stopped."); }
                case "target" -> target(s, a);
                case "style" -> {
                    if (a.length < 3) { LifeCommand.err(s, "/npcx combat style " + String.join("|", ArmyCommand.STYLES)); return; }
                    for (LifeTrait t : lc.targets(s)) t.setStyle(a[2].toLowerCase(Locale.ROOT));
                    LifeCommand.say(s, "Style set.");
                }
                case "movemode" -> {
                    if (a.length < 3) { LifeCommand.err(s, "/npcx combat movemode hybrid|velocity|navigator"); return; }
                    pl.getConfig().set("combat.move-mode", a[2].toLowerCase(Locale.ROOT));
                    LifeCommand.say(s, "Move mode: <white>" + a[2] + "</white> (put combat.move-mode in config.yml to keep it).");
                }
                case "test" -> test(s, a);
                default -> help(s);
            }
        } catch (NumberFormatException ex) { LifeCommand.err(s, "Invalid number."); }
    }

    private void target(CommandSender s, String[] a) {
        if (a.length < 3) { LifeCommand.err(s, "/npcx combat target <player|npcId>"); return; }
        Entity victim = Bukkit.getPlayerExact(a[2]);
        if (victim == null) {
            try {
                NPC n = CitizensAPI.getNPCRegistry().getById(Integer.parseInt(a[2]));
                if (n != null && n.isSpawned()) victim = n.getEntity();
            } catch (NumberFormatException ex) { /* not an id */ }
        }
        if (victim == null) { LifeCommand.err(s, "No such player/NPC."); return; }
        int n = 0;
        for (LifeTrait t : lc.targets(s)) { if (t.getNPC().getEntity() != victim) { t.brain().combat().forceTarget(victim); n++; } }
        LifeCommand.say(s, n + " NPC(s) will go after <white>" + a[2] + "</white> (even without an enemy team).");
    }

    private void info(CommandSender s) {
        List<LifeTrait> ts = lc.targets(s);
        if (ts.isEmpty()) return;
        LifeTrait t = ts.get(0);
        Combat c = t.brain().combat();
        if (!t.getNPC().isSpawned() || !(t.getNPC().getEntity() instanceof Player p)) { LifeCommand.err(s, "NPC isn't spawned as a player."); return; }
        PlayerInventory inv = p.getInventory();
        LifeCommand.say(s, "<yellow>NPC #" + t.getNPC().getId() + "</yellow> combat=" + (t.combatOn() ? "<green>on" : "<red>off") + "</> team=" + t.team()
                + " style=" + t.style() + " skill=" + String.format(Locale.ROOT, "%.2f", t.skill())
                + (c.prof == null ? "" : " mistake-rate=" + String.format(Locale.ROOT, "%.0f%%", c.prof.mistake * 100)));
        LifeCommand.say(s, "state=<white>" + c.state + "</white> target=<white>" + (c.target == null ? "none" : c.target.getName()) + "</white> hp="
                + String.format(Locale.ROOT, "%.1f/%.0f", p.getHealth(), p.getMaxHealth()) + " confidence=" + String.format(Locale.ROOT, "%.2f", c.confidence)
                + " shield=" + (c.shieldUp ? "up" : "down") + " token=" + c.tokenHeld);
        LifeCommand.say(s, "items: totem " + Gear.count(inv, Material.TOTEM_OF_UNDYING) + ", shield " + Gear.count(inv, Material.SHIELD) + ", gapple "
                + Gear.count(inv, Material.GOLDEN_APPLE) + ", pearl " + Gear.count(inv, Material.ENDER_PEARL) + ", wind " + Gear.count(inv, Material.WIND_CHARGE)
                + ", web " + Gear.count(inv, Material.COBWEB) + ", water " + Gear.count(inv, Material.WATER_BUCKET) + ", blocks "
                + Gear.count(inv, Gear::placeable) + ", arrows " + Gear.count(inv, Weapons::isArrow));
    }

    private void test(CommandSender s, String[] a) {
        if (a.length < 3) { help(s); return; }
        List<LifeTrait> ts = lc.targets(s);
        for (LifeTrait t : ts) {
            if (!t.getNPC().isSpawned() || !(t.getNPC().getEntity() instanceof Player p)) continue;
            switch (a[2].toLowerCase(Locale.ROOT)) {
                case "clutch" -> {
                    if (Gear.findSlot(p.getInventory(), Material.WATER_BUCKET) < 0) p.getInventory().addItem(new ItemStack(Material.WATER_BUCKET));
                    Location up = p.getLocation().clone().add(0, 45, 0);
                    t.getNPC().teleport(up, PlayerTeleportEvent.TeleportCause.PLUGIN);
                }
                case "web" -> {
                    Block b = p.getLocation().getBlock();
                    if (b.getType().isAir()) { b.setType(Material.COBWEB); Cleanup.track(b, 20 * 60); }
                }
                case "heal" -> p.setHealth(Math.max(1.0, p.getMaxHealth() * 0.3));
                default -> { LifeCommand.err(s, "clutch | web | heal"); return; }
            }
        }
        LifeCommand.say(s, "Test <white>" + a[2] + "</white> started on " + ts.size() + " NPC(s). Watch with /npcx combat info.");
    }

    private void selftest(CommandSender s) {
        check(s, "Player#attack (real weapon damage, crits, knockback)", CombatUtil.attackAvailable(), "falls back to plain damage numbers");
        boolean canNav = false;
        try { Navigator.class.getMethod("canNavigateTo", Location.class); canNav = true; } catch (Throwable ignored) { /* missing */ }
        check(s, "Navigator#canNavigateTo (unreachable-target / bridging detection)", canNav, "NPCs can't tell when a target is unreachable");
        check(s, "Citizens flag USING_HELD_ITEM (eating / healing / bow pose)", MetaUtil.exists("USING_HELD_ITEM"), "falls back to PlayerAnimation");
        check(s, "Citizens flag USING_OFFHAND_ITEM (visible shield raise)", MetaUtil.exists("USING_OFFHAND_ITEM"), "shield still blocks (simulated) but isn't raised visibly");
        check(s, "Citizens flag RESPAWN_DELAY (dead NPCs stay dead)", MetaUtil.exists("RESPAWN_DELAY"), "NPCs may respawn on their own");
        check(s, "Item-use animations", AnimUtil.available(), "no draw/eat poses");
        LifeCommand.say(s, "Head control: <white>" + HeadUtil.describe());
        LifeCommand.say(s, "Teams: " + pl.teams().names().size() + ", kits: " + pl.kits().names().size());
        LifeCommand.say(s, "<gray>Features that need a live fight (pearl landing, wind-charge launch, real shield blocking) are verified by watching NPCs: use /npcx combat test and /npcx combat info.");
    }

    private void check(CommandSender s, String what, boolean ok, String ifMissing) {
        LifeCommand.say(s, (ok ? "<green>OK</green> " : "<red>MISSING</red> ") + what + (ok ? "" : " - " + ifMissing));
    }

    List<String> tab(String[] a) {
        List<String> out = new ArrayList<>();
        if (a.length == 2) out.addAll(List.of("on", "off", "auto", "info", "target", "stop", "style", "movemode", "test", "selftest"));
        else if (a.length == 3) {
            switch (a[1].toLowerCase(Locale.ROOT)) {
                case "style" -> out.addAll(List.of(ArmyCommand.STYLES));
                case "movemode" -> out.addAll(List.of("hybrid", "velocity", "navigator"));
                case "test" -> out.addAll(List.of("clutch", "web", "heal"));
                case "target" -> { for (Player p : Bukkit.getOnlinePlayers()) out.add(p.getName()); }
                default -> { }
            }
        }
        String last = a[a.length - 1].toLowerCase(Locale.ROOT);
        out.removeIf(x -> !x.toLowerCase(Locale.ROOT).startsWith(last));
        return out;
    }
}
