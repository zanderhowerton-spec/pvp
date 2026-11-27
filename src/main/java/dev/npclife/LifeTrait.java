package dev.npclife;

import net.citizensnpcs.api.exception.NPCLoadException;
import net.citizensnpcs.api.trait.Trait;
import net.citizensnpcs.api.trait.TraitName;
import net.citizensnpcs.api.util.DataKey;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.util.ArrayList;
import java.util.List;

/** Per-NPC persistent state: personality, wander centre/radius, return-home distance, group, sequence, hotbar. */
@TraitName("npclife")
public class LifeTrait extends Trait {
    private boolean enabled;
    private String personalityName = "default";
    private Personality p = Personality.base("default");
    private boolean hasCenter;
    private String cWorld;
    private double cx, cy, cz;
    private double radius = 100;
    private double homeDist = -1;                // -1 = use config return-distance, 0 = off, >0 = custom
    private String group;
    private boolean army;
    private String team, style, kit, combatMode;
    private double skill = 0.5;
    private String seqName;
    private boolean seqLoop, seqCombine, hasSo;
    private String soWorld;
    private double sox, soy, soz;
    private final List<Material> hotbar = new ArrayList<>();
    private final Brain brain = new Brain(this);

    public LifeTrait() { super("npclife"); }

    Brain brain() { return brain; }
    boolean isEnabled() { return enabled; }
    Personality personality() { return p; }
    String personalityName() { return personalityName; }
    List<Material> hotbar() { return hotbar; }
    String group() { return group; }
    void setGroup(String g) { this.group = g; }
    boolean isArmy() { return army; }
    void setArmy(boolean v) { this.army = v; }
    String team() { return team; }
    void setTeam(String t) { this.team = t == null ? null : t.toLowerCase(java.util.Locale.ROOT); }
    double skill() { return skill; }
    void setSkill(double v) { this.skill = Math.max(0, Math.min(1, v)); }
    String style() { return style; }
    void setStyle(String s) { this.style = s; }
    String kit() { return kit; }
    void setKit(String k) { this.kit = k; }

    /** Combat is on for army NPCs by default (combat.army-default); /npcx combat on|off|auto overrides per NPC. */
    boolean combatOn() {
        if ("on".equals(combatMode)) return true;
        if ("off".equals(combatMode)) return false;
        return army && NPCLifePlugin.get().getConfig().getBoolean("combat.army-default", true);
    }
    void setCombatMode(String m) { this.combatMode = m; }

    /** Re-applies this NPC's kit a few ticks after every spawn/respawn (Citizens doesn't keep the live inventory). */
    private void applyKitLater() {
        if (kit == null) return;
        final String k = kit;
        Bukkit.getScheduler().runTaskLater(NPCLifePlugin.get(), () -> {
            if (!getNPC().isSpawned()) return;
            Kit kk = NPCLifePlugin.get().kits().get(k);
            if (kk != null) KitManager.apply(getNPC(), kk);
        }, 5L);
    }

    void setEnabled(boolean on) {
        this.enabled = on;
        if (on) { ensureCenter(); if (getNPC().isSpawned()) NPCLifePlugin.get().brains().register(this); }
        else brain.shutdown();
    }

    void applyPersonality(Personality np) {
        this.p = np; this.personalityName = np.name;
        if (np.radius > 0) this.radius = np.radius;
    }

    void setRadius(double r) { this.radius = Math.max(1, r); }

    double effectiveRadius() {
        double cap = NPCLifePlugin.get().getConfig().getDouble("max-wander-radius", 100);
        return Math.min(radius, cap);
    }

    /** Distance from the centre after which the NPC is sent home (0 = disabled). */
    double homeDistance() {
        if (homeDist < 0) return NPCLifePlugin.get().getConfig().getDouble("return-distance", 40);
        return homeDist;
    }
    void setHomeDistance(double d) { this.homeDist = d; }

    Location center() {
        if (!hasCenter) return null;
        World w = Bukkit.getWorld(cWorld);
        return w == null ? null : new Location(w, cx, cy, cz);
    }

    void setCenter(Location l) { hasCenter = true; cWorld = l.getWorld().getName(); cx = l.getX(); cy = l.getY(); cz = l.getZ(); }

    void ensureCenter() {
        if (!hasCenter && getNPC().isSpawned()) setCenter(getNPC().getEntity().getLocation());
    }

    // ---- sequences
    private Location seqOrigin() {
        if (!hasSo) return null;
        World w = Bukkit.getWorld(soWorld);
        return w == null ? null : new Location(w, sox, soy, soz);
    }

    void playSequence(String name, boolean loop, boolean combine) {
        seqName = name; seqLoop = loop; seqCombine = combine;
        if (getNPC().isSpawned()) {
            Location l = getNPC().getEntity().getLocation();
            hasSo = true; soWorld = l.getWorld().getName(); sox = l.getX(); soy = l.getY(); soz = l.getZ();
        }
        resumeSequence();
    }

    void stopSequence() { seqName = null; brain.stopSequence(); }

    void resumeSequence() {
        if (seqName == null || !getNPC().isSpawned()) return;
        List<String> steps = NPCLifePlugin.get().sequences().get(seqName);
        if (steps == null) return;
        Location o = seqOrigin();
        brain.startSequence(steps, seqLoop, seqCombine, o != null ? o : getNPC().getEntity().getLocation());
    }

    @Override public void onAttach() {
        if (getNPC().isSpawned()) { ensureCenter(); NPCLifePlugin.get().brains().register(this); }
    }
    @Override public void onSpawn() { ensureCenter(); NPCLifePlugin.get().brains().register(this); applyKitLater(); }
    @Override public void onDespawn() { brain.shutdown(); brain.clearSequenceState(); NPCLifePlugin.get().brains().unregister(this); }
    @Override public void onRemove() { brain.shutdown(); brain.clearSequenceState(); NPCLifePlugin.get().brains().unregister(this); }

    @Override
    public void save(DataKey key) {
        YamlConfiguration y = new YamlConfiguration();
        y.set("enabled", enabled);
        y.set("radius", radius);
        y.set("home", homeDist);
        y.set("group", group);
        y.set("army", army); y.set("team", team); y.set("skill", skill); y.set("style", style); y.set("kit", kit); y.set("combat", combatMode);
        y.set("personality", personalityName);
        if (hasCenter) { y.set("center.world", cWorld); y.set("center.x", cx); y.set("center.y", cy); y.set("center.z", cz); }
        y.set("seq.name", seqName); y.set("seq.loop", seqLoop); y.set("seq.combine", seqCombine);
        if (hasSo) { y.set("seq.origin.world", soWorld); y.set("seq.origin.x", sox); y.set("seq.origin.y", soy); y.set("seq.origin.z", soz); }
        List<String> hb = new ArrayList<>();
        for (Material m : hotbar) hb.add(m.name());
        y.set("hotbar", hb);
        p.write(y.createSection("p"));
        key.setString("state", y.saveToString());
    }

    @Override
    public void load(DataKey key) throws NPCLoadException {
        if (!key.keyExists("state")) return;
        YamlConfiguration y = new YamlConfiguration();
        try { y.loadFromString(key.getString("state")); } catch (InvalidConfigurationException ex) { return; }
        enabled = y.getBoolean("enabled", false);
        radius = y.getDouble("radius", 100);
        homeDist = y.getDouble("home", -1);
        group = y.getString("group", null);
        army = y.getBoolean("army", false); team = y.getString("team", null); skill = y.getDouble("skill", 0.5);
        style = y.getString("style", null); kit = y.getString("kit", null); combatMode = y.getString("combat", null);
        personalityName = y.getString("personality", "default");
        if (y.isConfigurationSection("center")) {
            hasCenter = true; cWorld = y.getString("center.world");
            cx = y.getDouble("center.x"); cy = y.getDouble("center.y"); cz = y.getDouble("center.z");
        }
        seqName = y.getString("seq.name", null);
        seqLoop = y.getBoolean("seq.loop", false);
        seqCombine = y.getBoolean("seq.combine", false);
        if (y.isConfigurationSection("seq.origin")) {
            hasSo = true; soWorld = y.getString("seq.origin.world");
            sox = y.getDouble("seq.origin.x"); soy = y.getDouble("seq.origin.y"); soz = y.getDouble("seq.origin.z");
        }
        hotbar.clear();
        for (String s : y.getStringList("hotbar")) { Material m = Material.matchMaterial(s); if (m != null) hotbar.add(m); }
        if (y.isConfigurationSection("p")) p = Personality.read(personalityName, y.getConfigurationSection("p"));
    }
}
