package dev.npclife;

import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.npc.NPC;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** /npcx team ... : put players and NPCs into teams and mark teams as enemies, so NPCs know who to fight. */
final class TeamCommand {
    private final NPCLifePlugin pl;
    private final LifeCommand lc;

    TeamCommand(NPCLifePlugin pl, LifeCommand lc) { this.pl = pl; this.lc = lc; }

    private void help(CommandSender s) {
        for (String l : new String[]{
                "team create <name> | delete <name> | list | info <name>",
                "team add <name> me|<player>|selected|army|near <radius>   <gray>(remove works the same way)</gray>",
                "team hostile <teamA> <teamB> [true|false]   <gray>- teams that are enemies fight each other</gray>"})
            LifeCommand.say(s, "<white>/npcx " + l);
    }

    void handle(CommandSender s, String[] a) {
        if (a.length < 2) { help(s); return; }
        TeamManager tm = pl.teams();
        String sub = a[1].toLowerCase(Locale.ROOT);
        String name = a.length > 2 ? a[2].toLowerCase(Locale.ROOT) : null;
        try {
            switch (sub) {
                case "list" -> {
                    if (tm.names().isEmpty()) { LifeCommand.say(s, "No teams yet: /npcx team create <name>"); return; }
                    for (String t : tm.names()) LifeCommand.say(s, "<white>" + t + "</white>: " + tm.playersOf(t).size() + " player(s), " + countNpcs(t) + " NPC(s)"
                            + (tm.enemiesOf(t).isEmpty() ? "" : ", enemies: " + String.join(", ", tm.enemiesOf(t))));
                }
                case "create" -> {
                    if (name == null) { LifeCommand.err(s, "/npcx team create <name>"); return; }
                    if (tm.create(name)) LifeCommand.say(s, "Created team <white>" + name + "</white>."); else LifeCommand.err(s, "That team already exists.");
                }
                case "delete" -> {
                    if (name == null || !tm.delete(name)) LifeCommand.err(s, "No such team."); else LifeCommand.say(s, "Deleted team " + name + ".");
                }
                case "info" -> {
                    if (name == null || !tm.exists(name)) { LifeCommand.err(s, "No such team."); return; }
                    LifeCommand.say(s, "<white>" + name + "</white>: players: " + tm.playerNames(name) + " | NPCs: " + countNpcs(name)
                            + " | enemies: " + (tm.enemiesOf(name).isEmpty() ? "none" : String.join(", ", tm.enemiesOf(name))));
                }
                case "add", "remove" -> {
                    if (name == null || a.length < 4) { LifeCommand.err(s, "/npcx team " + sub + " <team> me|<player>|selected|army|near <radius>"); return; }
                    if (!tm.exists(name)) { LifeCommand.err(s, "No such team. Create it first."); return; }
                    int n = change(s, a, name, sub.equals("add"));
                    LifeCommand.say(s, (sub.equals("add") ? "Added " : "Removed ") + "<white>" + n + "</white> member(s) " + (sub.equals("add") ? "to " : "from ") + name + ".");
                }
                case "hostile" -> {
                    if (a.length < 4) { LifeCommand.err(s, "/npcx team hostile <teamA> <teamB> [true|false]"); return; }
                    String b = a[3].toLowerCase(Locale.ROOT);
                    if (!tm.exists(name) || !tm.exists(b)) { LifeCommand.err(s, "Both teams must exist."); return; }
                    boolean on = a.length < 5 || !a[4].equalsIgnoreCase("false");
                    tm.setHostile(name, b, on);
                    LifeCommand.say(s, "<white>" + name + "</white> and <white>" + b + "</white> are now " + (on ? "<red>enemies</red>." : "neutral."));
                }
                default -> help(s);
            }
        } catch (NumberFormatException ex) { LifeCommand.err(s, "Invalid number."); }
    }

    private int countNpcs(String team) {
        int n = 0;
        for (NPC npc : CitizensAPI.getNPCRegistry())
            if (npc.hasTrait(LifeTrait.class) && team.equalsIgnoreCase(npc.getOrAddTrait(LifeTrait.class).team())) n++;
        return n;
    }

    private int applyPlayer(Player p, String team, boolean add) {
        UUID id = p.getUniqueId();
        if (add) { pl.teams().addPlayer(id, team); return 1; }
        if (team.equalsIgnoreCase(pl.teams().teamOfPlayer(id))) { pl.teams().removePlayer(id); return 1; }
        return 0;
    }

    private int applyNpc(LifeTrait t, String team, boolean add) {
        if (add) { t.setTeam(team); return 1; }
        if (team.equalsIgnoreCase(t.team())) { t.setTeam(null); return 1; }
        return 0;
    }

    private int change(CommandSender s, String[] a, String team, boolean add) {
        String who = a[3].toLowerCase(Locale.ROOT);
        int n = 0;
        if (who.equals("me")) {
            if (s instanceof Player p) n += applyPlayer(p, team, add); else LifeCommand.err(s, "Players only.");
        } else if (who.equals("selected")) {
            for (LifeTrait t : lc.targets(s)) n += applyNpc(t, team, add);
        } else if (who.equals("army")) {
            for (NPC npc : CitizensAPI.getNPCRegistry()) {
                if (!npc.hasTrait(LifeTrait.class)) continue;
                LifeTrait t = npc.getOrAddTrait(LifeTrait.class);
                if (t.isArmy()) n += applyNpc(t, team, add);
            }
        } else if (who.equals("near")) {
            if (!(s instanceof Player me)) { LifeCommand.err(s, "Players only."); return 0; }
            double r = a.length > 4 ? Double.parseDouble(a[4]) : 30;
            Location here = me.getLocation();
            for (Player other : me.getWorld().getPlayers())
                if (other.getLocation().distance(here) <= r) n += applyPlayer(other, team, add);
            for (NPC npc : CitizensAPI.getNPCRegistry()) {
                if (!npc.isSpawned() || !npc.hasTrait(LifeTrait.class)) continue;
                Entity e = npc.getEntity();
                if (e.getWorld() == me.getWorld() && e.getLocation().distance(here) <= r) n += applyNpc(npc.getOrAddTrait(LifeTrait.class), team, add);
            }
        } else {
            Player target = Bukkit.getPlayerExact(a[3]);
            if (target == null) LifeCommand.err(s, "Player not online."); else n += applyPlayer(target, team, add);
        }
        return n;
    }

    List<String> tab(String[] a) {
        List<String> out = new ArrayList<>();
        if (a.length == 2) out.addAll(List.of("create", "delete", "list", "info", "add", "remove", "hostile"));
        else if (a.length == 3 && !a[1].equalsIgnoreCase("create") && !a[1].equalsIgnoreCase("list")) out.addAll(pl.teams().names());
        else if (a.length == 4 && (a[1].equalsIgnoreCase("add") || a[1].equalsIgnoreCase("remove"))) {
            out.addAll(List.of("me", "selected", "army", "near"));
            for (Player p : Bukkit.getOnlinePlayers()) out.add(p.getName());
        } else if (a.length == 4 && a[1].equalsIgnoreCase("hostile")) out.addAll(pl.teams().names());
        else if (a.length == 5 && a[1].equalsIgnoreCase("hostile")) out.addAll(List.of("true", "false"));
        String last = a[a.length - 1].toLowerCase(Locale.ROOT);
        out.removeIf(x -> !x.toLowerCase(Locale.ROOT).startsWith(last));
        return out;
    }
}
