package dev.npclife;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/** Built-in personalities (code) + custom ones saved in plugins/NPCLife/personalities.yml. */
final class PersonalityManager {
    private final NPCLifePlugin plugin;
    private final Map<String, Personality> map = new TreeMap<>();
    private final java.util.Set<String> builtin = new java.util.HashSet<>();

    PersonalityManager(NPCLifePlugin plugin) { this.plugin = plugin; }

    void load() {
        map.clear(); builtin.clear();
        add(Personality.base("default"));

        Personality e = Personality.base("energetic");
        set(e, ActionType.JUMP, 45, 2, 6); set(e, ActionType.SPRINT, 30, 6, 15);
        set(e, ActionType.WALK, 70, 0.5, 3); set(e, ActionType.IDLE, 5, 5, 15);
        set(e, ActionType.LOOK_AROUND, 50, 3, 8); set(e, ActionType.INTERACT, 8, 30, 90);
        e.walkMin = 8; e.walkMax = 28; e.thinkMax = 1.2; e.lookSpeed = 18; add(e);

        Personality l = Personality.base("lazy");
        set(l, ActionType.IDLE, 70, 1, 4); l.cfg(ActionType.IDLE).durMin = 4; l.cfg(ActionType.IDLE).durMax = 14;
        set(l, ActionType.WALK, 20, 8, 25); set(l, ActionType.JUMP, 3, 20, 60); set(l, ActionType.SPRINT, 1, 30, 90);
        set(l, ActionType.LOOK_AROUND, 30, 4, 10); set(l, ActionType.WATCH, 25, 6, 14);
        l.walkMin = 2; l.walkMax = 8; l.thinkMin = 1.2; l.thinkMax = 3; l.lookSpeed = 8; l.speedMod = 0.9; add(l);

        Personality n = Personality.base("nervous");
        set(n, ActionType.LOOK_AROUND, 85, 1, 4); n.cfg(ActionType.LOOK_AROUND).durMin = 0.6; n.cfg(ActionType.LOOK_AROUND).durMax = 1.8;
        set(n, ActionType.SNEAK, 30, 8, 20); set(n, ActionType.SNEAK_SPAM, 8, 20, 60);
        set(n, ActionType.TURN_AROUND, 25, 5, 15); set(n, ActionType.FLEE, 70, 6, 20);
        set(n, ActionType.WALK, 35, 2, 8); set(n, ActionType.JUMP, 8, 8, 20); set(n, ActionType.SPRINT, 12, 10, 25);
        set(n, ActionType.WATCH, 35, 4, 10); set(n, ActionType.INTERACT, 2, 60, 120); set(n, ActionType.IDLE, 15, 4, 10);
        n.lookSpeed = 24; n.awareness = 14; add(n);

        Personality c = Personality.base("curious");
        set(c, ActionType.INTERACT, 40, 10, 30); set(c, ActionType.WATCH, 65, 3, 8);
        set(c, ActionType.APPROACH, 25, 10, 30); set(c, ActionType.LOOK_AROUND, 55, 3, 8);
        set(c, ActionType.WALK, 45, 2, 8); set(c, ActionType.SWING, 10, 15, 40);
        c.npcAffinity = 0.2; c.walkMin = 4; c.walkMax = 14; add(c);

        Personality s = Personality.base("social");
        set(s, ActionType.WATCH, 60, 3, 8); set(s, ActionType.APPROACH, 45, 8, 25);
        set(s, ActionType.IDLE, 30, 4, 12); set(s, ActionType.WALK, 30, 3, 10);
        set(s, ActionType.JUMP, 12, 6, 16); set(s, ActionType.SWING, 8, 20, 60); set(s, ActionType.TURN_AROUND, 15, 8, 20);
        s.npcAffinity = 0.9; add(s);

        Personality x = Personality.base("explorer");
        set(x, ActionType.WALK, 85, 0, 3); x.cfg(ActionType.WALK).durMin = 10; x.cfg(ActionType.WALK).durMax = 40;
        set(x, ActionType.LOOK_AROUND, 55, 3, 8); set(x, ActionType.JUMP, 25, 4, 10);
        set(x, ActionType.SPRINT, 20, 8, 20); set(x, ActionType.INTERACT, 15, 20, 60);
        set(x, ActionType.IDLE, 8, 5, 12); set(x, ActionType.TURN_AROUND, 18, 8, 20);
        x.walkMin = 15; x.walkMax = 45; add(x);

        Personality sk = Personality.base("shopkeeper");
        set(sk, ActionType.INTERACT, 75, 4, 12); sk.cfg(ActionType.INTERACT).durMin = 4; sk.cfg(ActionType.INTERACT).durMax = 10;
        set(sk, ActionType.IDLE, 55, 1, 4); sk.cfg(ActionType.IDLE).durMin = 3; sk.cfg(ActionType.IDLE).durMax = 10;
        set(sk, ActionType.WALK, 4, 20, 60); set(sk, ActionType.LOOK_AROUND, 35, 3, 9);
        set(sk, ActionType.WATCH, 30, 4, 10); set(sk, ActionType.TURN_AROUND, 3, 20, 60);
        set(sk, ActionType.SNEAK, 2, 30, 90); set(sk, ActionType.SNEAK_SPAM, 1, 60, 180);
        set(sk, ActionType.GREET, 30, 15, 40); set(sk, ActionType.SWAP_ITEM, 3, 20, 60);
        for (ActionType off : new ActionType[]{ActionType.JUMP, ActionType.SPRINT, ActionType.SWING, ActionType.APPROACH, ActionType.FLEE, ActionType.BROWSE}) {
            sk.cfg(off).probability = 0; sk.cfg(off).enabled = false;
        }
        sk.walkMin = 1.5; sk.walkMax = 4; sk.radius = 6; sk.awareness = 10; sk.lookSpeed = 10; sk.speedMod = 0.9; sk.workBias = 0.85; add(sk);

        Personality cu = Personality.base("customer");
        set(cu, ActionType.BROWSE, 75, 4, 12); cu.cfg(ActionType.BROWSE).durMin = 8; cu.cfg(ActionType.BROWSE).durMax = 22;
        set(cu, ActionType.WALK, 30, 3, 10); set(cu, ActionType.LOOK_AROUND, 60, 3, 8); set(cu, ActionType.WATCH, 35, 4, 10);
        set(cu, ActionType.IDLE, 10, 5, 15); set(cu, ActionType.JUMP, 3, 15, 40); set(cu, ActionType.SPRINT, 3, 30, 90);
        set(cu, ActionType.SNEAK, 2, 30, 90); set(cu, ActionType.TURN_AROUND, 6, 15, 40);
        for (ActionType off : new ActionType[]{ActionType.INTERACT, ActionType.GREET, ActionType.SNEAK_SPAM, ActionType.APPROACH, ActionType.FLEE}) {
            cu.cfg(off).probability = 0;
        }
        cu.radius = 40; cu.walkMin = 5; cu.walkMax = 20; cu.awareness = 12; add(cu);

        File f = new File(plugin.getDataFolder(), "personalities.yml");
        if (f.exists()) {
            YamlConfiguration y = YamlConfiguration.loadConfiguration(f);
            for (String k : y.getKeys(false)) {
                ConfigurationSection sec = y.getConfigurationSection(k);
                if (sec != null && !builtin.contains(k.toLowerCase(Locale.ROOT)))
                    map.put(k.toLowerCase(Locale.ROOT), Personality.read(k.toLowerCase(Locale.ROOT), sec));
            }
        }
    }

    private void add(Personality p) { map.put(p.name, p); builtin.add(p.name); }

    private static void set(Personality p, ActionType t, double prob, double cdMin, double cdMax) {
        ActionConfig c = p.cfg(t); c.probability = prob; c.cdMin = cdMin; c.cdMax = cdMax;
    }

    Personality get(String name) {
        Personality p = map.get(name.toLowerCase(Locale.ROOT));
        return p == null ? null : p.copy();
    }

    java.util.Set<String> names() { return map.keySet(); }

    boolean save(String name, Personality p) {
        String k = name.toLowerCase(Locale.ROOT);
        if (builtin.contains(k)) return false;
        Personality cp = p.copy(); cp.name = k; map.put(k, cp);
        File f = new File(plugin.getDataFolder(), "personalities.yml");
        YamlConfiguration y = f.exists() ? YamlConfiguration.loadConfiguration(f) : new YamlConfiguration();
        y.set(k, null);
        cp.write(y.createSection(k));
        try { plugin.getDataFolder().mkdirs(); y.save(f); } catch (IOException ex) { plugin.getLogger().warning("Could not save personalities: " + ex); }
        return true;
    }
}
