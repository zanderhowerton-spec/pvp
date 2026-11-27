package dev.npclife;

import org.bukkit.configuration.ConfigurationSection;

/** Per-action tuning. Times are seconds. Probability is 0-100 and is rolled on every "think". */
final class ActionConfig {
    boolean enabled = true;
    double probability, cdMin, cdMax, durMin, durMax;

    ActionConfig copy() {
        ActionConfig c = new ActionConfig();
        c.enabled = enabled; c.probability = probability;
        c.cdMin = cdMin; c.cdMax = cdMax; c.durMin = durMin; c.durMax = durMax;
        return c;
    }

    void write(ConfigurationSection s) {
        s.set("enabled", enabled); s.set("probability", probability);
        s.set("cooldown-min", cdMin); s.set("cooldown-max", cdMax);
        s.set("duration-min", durMin); s.set("duration-max", durMax);
    }

    void read(ConfigurationSection s) {
        enabled = s.getBoolean("enabled", enabled);
        probability = s.getDouble("probability", probability);
        cdMin = s.getDouble("cooldown-min", cdMin); cdMax = s.getDouble("cooldown-max", cdMax);
        durMin = s.getDouble("duration-min", durMin); durMax = s.getDouble("duration-max", durMax);
    }
}
