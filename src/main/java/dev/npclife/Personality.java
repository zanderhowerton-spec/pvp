package dev.npclife;

import org.bukkit.configuration.ConfigurationSection;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Random;

final class Personality {
    String name;
    double thinkMin = 0.6, thinkMax = 1.8;   // seconds between decision rounds
    double awareness = 12;                   // blocks
    double walkMin = 4, walkMax = 16;        // wander hop length
    double lookSpeed = 14;                   // max head degrees/tick
    double speedMod = 1.0;                   // walking speed multiplier
    double workBias = 0;                    // 0..1: chance to prefer containers when interacting
    double radius = -1;                      // >0: sets the NPC's wander radius when applied
    double npcAffinity = 0.5;                // 0 = prefers players, 1 = prefers NPCs
    final EnumMap<ActionType, ActionConfig> actions = new EnumMap<>(ActionType.class);

    ActionConfig cfg(ActionType t) { return actions.get(t); }

    static Personality base(String name) {
        Personality p = new Personality();
        p.name = name;
        for (ActionType t : ActionType.values()) p.actions.put(t, t.defaults());
        return p;
    }

    Personality copy() {
        Personality p = base(name);
        p.thinkMin = thinkMin; p.thinkMax = thinkMax; p.awareness = awareness; p.walkMin = walkMin;
        p.walkMax = walkMax; p.lookSpeed = lookSpeed; p.speedMod = speedMod; p.npcAffinity = npcAffinity; p.radius = radius; p.workBias = workBias;
        for (ActionType t : ActionType.values()) p.actions.put(t, actions.get(t).copy());
        return p;
    }

    void write(ConfigurationSection s) {
        s.set("think-min", thinkMin); s.set("think-max", thinkMax); s.set("awareness", awareness);
        s.set("walk-min", walkMin); s.set("walk-max", walkMax); s.set("look-speed", lookSpeed);
        s.set("speed", speedMod); s.set("npc-affinity", npcAffinity); s.set("radius", radius); s.set("work-bias", workBias);
        ConfigurationSection a = s.createSection("actions");
        for (ActionType t : ActionType.values())
            actions.get(t).write(a.createSection(t.name().toLowerCase(Locale.ROOT)));
    }

    static Personality read(String name, ConfigurationSection s) {
        Personality p = base(name);
        p.thinkMin = s.getDouble("think-min", p.thinkMin); p.thinkMax = s.getDouble("think-max", p.thinkMax);
        p.awareness = s.getDouble("awareness", p.awareness);
        p.walkMin = s.getDouble("walk-min", p.walkMin); p.walkMax = s.getDouble("walk-max", p.walkMax);
        p.lookSpeed = s.getDouble("look-speed", p.lookSpeed); p.speedMod = s.getDouble("speed", p.speedMod);
        p.npcAffinity = s.getDouble("npc-affinity", p.npcAffinity); p.radius = s.getDouble("radius", p.radius); p.workBias = s.getDouble("work-bias", p.workBias);
        ConfigurationSection a = s.getConfigurationSection("actions");
        if (a != null) for (ActionType t : ActionType.values()) {
            ConfigurationSection x = a.getConfigurationSection(t.name().toLowerCase(Locale.ROOT));
            if (x != null) p.actions.get(t).read(x);
        }
        return p;
    }

    /** A fresh, different-every-time personality. */
    static Personality random(Random r, String name) {
        Personality p = base(name);
        double activity = 0.4 + r.nextDouble() * 1.6;
        for (ActionType t : ActionType.values()) {
            ActionConfig c = p.cfg(t);
            switch (t) {
                case FLEE, APPROACH, INTERACT -> c.probability = r.nextDouble() * 40;
                default -> c.probability = clamp(c.probability * activity * (0.25 + r.nextDouble() * 2.5), 0, 95);
            }
            double cd = 0.4 + r.nextDouble() * 2.2, du = 0.6 + r.nextDouble() * 1.6;
            c.cdMin *= cd; c.cdMax *= cd; c.durMin *= du; c.durMax *= du;
            c.enabled = t == ActionType.SNEAK_SPAM ? r.nextDouble() > 0.5 : r.nextDouble() > 0.1;
        }
        p.thinkMin = 0.4 + r.nextDouble();
        p.thinkMax = p.thinkMin + 0.5 + r.nextDouble() * 1.5;
        p.walkMin = 2 + r.nextDouble() * 10;
        p.walkMax = p.walkMin + 4 + r.nextDouble() * 35;
        p.lookSpeed = 6 + r.nextDouble() * 20;
        p.awareness = 8 + r.nextDouble() * 12;
        p.speedMod = 0.85 + r.nextDouble() * 0.3;
        p.npcAffinity = r.nextDouble();
        return p;
    }

    static double clamp(double v, double lo, double hi) { return Math.max(lo, Math.min(hi, v)); }
}
