package dev.npclife;

import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Sequences are lists of text steps stored in plugins/NPCLife/sequences.yml (hand-editable). */
final class SequenceManager {
    private final NPCLifePlugin plugin;
    private final Map<String, List<String>> map = new TreeMap<>();

    SequenceManager(NPCLifePlugin plugin) { this.plugin = plugin; }

    private File file() { return new File(plugin.getDataFolder(), "sequences.yml"); }

    void load() {
        map.clear();
        if (!file().exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file());
        for (String k : y.getKeys(false)) map.put(k.toLowerCase(Locale.ROOT), new ArrayList<>(y.getStringList(k)));
    }

    List<String> get(String name) {
        List<String> l = map.get(name.toLowerCase(Locale.ROOT));
        return l == null ? null : new ArrayList<>(l);
    }

    void put(String name, List<String> steps) { map.put(name.toLowerCase(Locale.ROOT), new ArrayList<>(steps)); save(); }
    boolean delete(String name) { boolean r = map.remove(name.toLowerCase(Locale.ROOT)) != null; if (r) save(); return r; }
    Set<String> names() { return map.keySet(); }

    private void save() {
        YamlConfiguration y = new YamlConfiguration();
        for (Map.Entry<String, List<String>> en : map.entrySet()) y.set(en.getKey(), en.getValue());
        try { plugin.getDataFolder().mkdirs(); y.save(file()); }
        catch (IOException ex) { plugin.getLogger().warning("Could not save sequences: " + ex); }
    }
}
