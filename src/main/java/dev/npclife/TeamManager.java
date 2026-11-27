package dev.npclife;

import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.npc.NPC;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Teams ("groups") for players and NPCs, plus which teams are hostile to each other. Players' membership is stored here
 * (teams.yml); an NPC's team lives on its LifeTrait. The combat stages use hostile(a, b) to decide who fights whom.
 */
final class TeamManager {
    private final NPCLifePlugin plugin;
    private final Map<String, Set<UUID>> players = new TreeMap<>();
    private final Set<String> hostile = new HashSet<>();

    TeamManager(NPCLifePlugin plugin) { this.plugin = plugin; }

    private File file() { return new File(plugin.getDataFolder(), "teams.yml"); }

    void load() {
        players.clear(); hostile.clear();
        if (!file().exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file());
        ConfigurationSection t = y.getConfigurationSection("teams");
        if (t != null) for (String name : t.getKeys(false)) {
            Set<UUID> set = new LinkedHashSet<>();
            for (String u : t.getStringList(name + ".players")) { try { set.add(UUID.fromString(u)); } catch (IllegalArgumentException ex) { /* skip */ } }
            players.put(name.toLowerCase(Locale.ROOT), set);
        }
        hostile.addAll(y.getStringList("hostile"));
    }

    private void save() {
        YamlConfiguration y = new YamlConfiguration();
        for (Map.Entry<String, Set<UUID>> en : players.entrySet()) {
            List<String> ids = new ArrayList<>();
            for (UUID u : en.getValue()) ids.add(u.toString());
            y.set("teams." + en.getKey() + ".players", ids);
        }
        y.set("hostile", new ArrayList<>(hostile));
        try { plugin.getDataFolder().mkdirs(); y.save(file()); }
        catch (IOException ex) { plugin.getLogger().warning("Could not save teams: " + ex); }
    }

    private static String key(String a, String b) { return a.compareTo(b) < 0 ? a + "|" + b : b + "|" + a; }

    boolean exists(String name) { return players.containsKey(name.toLowerCase(Locale.ROOT)); }
    Set<String> names() { return players.keySet(); }
    Set<UUID> playersOf(String team) { return players.getOrDefault(team.toLowerCase(Locale.ROOT), Set.of()); }

    boolean create(String name) {
        String k = name.toLowerCase(Locale.ROOT);
        if (players.containsKey(k)) return false;
        players.put(k, new LinkedHashSet<>());
        save();
        return true;
    }

    boolean delete(String name) {
        String k = name.toLowerCase(Locale.ROOT);
        if (players.remove(k) == null) return false;
        hostile.removeIf(h -> h.startsWith(k + "|") || h.endsWith("|" + k));
        for (NPC n : CitizensAPI.getNPCRegistry()) {                       // un-team NPCs that were in it
            if (!n.hasTrait(LifeTrait.class)) continue;
            LifeTrait t = n.getOrAddTrait(LifeTrait.class);
            if (k.equalsIgnoreCase(t.team())) t.setTeam(null);
        }
        save();
        return true;
    }

    void addPlayer(UUID id, String team) {
        for (Set<UUID> s : players.values()) s.remove(id);                  // one team per player
        players.computeIfAbsent(team.toLowerCase(Locale.ROOT), x -> new LinkedHashSet<>()).add(id);
        save();
    }

    void removePlayer(UUID id) { for (Set<UUID> s : players.values()) s.remove(id); save(); }

    String teamOfPlayer(UUID id) {
        for (Map.Entry<String, Set<UUID>> en : players.entrySet()) if (en.getValue().contains(id)) return en.getKey();
        return null;
    }

    /** Team of any entity: a real player (teams.yml), or an NPC (its trait). null = no team. */
    String teamOf(Entity e) {
        if (e == null) return null;
        NPC n = CitizensAPI.getNPCRegistry().getNPC(e);
        if (n != null) return n.hasTrait(LifeTrait.class) ? lower(n.getOrAddTrait(LifeTrait.class).team()) : null;
        return teamOfPlayer(e.getUniqueId());
    }

    private static String lower(String s) { return s == null ? null : s.toLowerCase(Locale.ROOT); }

    boolean areHostile(String a, String b) {
        if (a == null || b == null || a.equalsIgnoreCase(b)) return false;
        return hostile.contains(key(a.toLowerCase(Locale.ROOT), b.toLowerCase(Locale.ROOT)));
    }

    void setHostile(String a, String b, boolean on) {
        String k = key(a.toLowerCase(Locale.ROOT), b.toLowerCase(Locale.ROOT));
        if (on) hostile.add(k); else hostile.remove(k);
        save();
    }

    List<String> enemiesOf(String team) {
        List<String> out = new ArrayList<>();
        String t = team.toLowerCase(Locale.ROOT);
        for (String h : hostile) {
            String[] p = h.split("\\|");
            if (p.length == 2) { if (p[0].equals(t)) out.add(p[1]); else if (p[1].equals(t)) out.add(p[0]); }
        }
        return out;
    }

    String playerNames(String team) {
        List<String> out = new ArrayList<>();
        for (UUID u : playersOf(team)) { String n = Bukkit.getOfflinePlayer(u).getName(); out.add(n == null ? u.toString().substring(0, 8) : n); }
        return out.isEmpty() ? "none" : String.join(", ", out);
    }
}
