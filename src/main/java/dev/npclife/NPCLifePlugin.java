package dev.npclife;

import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.event.NPCCreateEvent;
import net.citizensnpcs.api.event.NPCSpawnEvent;
import net.citizensnpcs.api.npc.NPC;
import org.bukkit.entity.Player;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.util.concurrent.ThreadLocalRandom;
import net.citizensnpcs.api.trait.TraitInfo;
import org.bukkit.command.PluginCommand;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;

public final class NPCLifePlugin extends JavaPlugin implements Listener {
    private static NPCLifePlugin instance;
    private PersonalityManager personalities;
    private BrainManager brains;
    private SequenceManager sequences;
    private KitManager kits;
    private TeamManager teams;
    private NameManager names;
    private MarchManager march;
    private SpeechManager speech;
    private int leftCount;
    private SequenceRecorder recorder;
    private TraitInfo traitInfo;

    public static NPCLifePlugin get() { return instance; }
    PersonalityManager personalities() { return personalities; }
    BrainManager brains() { return brains; }
    SequenceManager sequences() { return sequences; }
    KitManager kits() { return kits; }
    TeamManager teams() { return teams; }
    NameManager names() { return names; }
    MarchManager march() { return march; }
    SpeechManager speech() { return speech; }
    int leftCount() { return leftCount; }
    SequenceRecorder recorder() { return recorder; }

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();
        if (getServer().getPluginManager().getPlugin("Citizens") == null) {
            getLogger().severe("Citizens is not installed - disabling.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        personalities = new PersonalityManager(this);
        personalities.load();
        kits = new KitManager(this);
        kits.load();
        teams = new TeamManager(this);
        teams.load();
        names = new NameManager(this);
        names.load();
        march = new MarchManager(this);
        march.load();
        speech = new SpeechManager(this);
        sequences = new SequenceManager(this);
        sequences.load();
        recorder = new SequenceRecorder(this);
        brains = new BrainManager(this);

        traitInfo = TraitInfo.create(LifeTrait.class);
        CitizensAPI.getTraitFactory().registerTrait(traitInfo);
        getServer().getPluginManager().registerEvents(this, this);
        getServer().getPluginManager().registerEvents(new CombatListener(), this);

        LifeCommand cmd = new LifeCommand(this);
        PluginCommand pc = getCommand("npclife");
        pc.setExecutor(cmd);
        pc.setTabCompleter(cmd);
        brains.start();
        // Existing NPCs: attach the trait once Citizens has loaded them (and again later as a safety net).
        getServer().getScheduler().runTaskLater(this, this::attachAll, 40L);
        getServer().getScheduler().runTaskLater(this, this::attachAll, 400L);
        getLogger().info("NPCLife enabled. Head control: " + HeadUtil.describe());
    }

    @Override
    public void onDisable() {
        if (march != null) march.stopAll();
        if (speech != null) speech.stop(null);
        if (recorder != null) recorder.stop();
        if (brains != null) brains.stop();
        try { if (traitInfo != null) CitizensAPI.getTraitFactory().deregisterTrait(traitInfo); } catch (Throwable ignored) { }
    }

    void reload() {
        reloadConfig();
        personalities.load();
        sequences.load();
        kits.load();
        teams.load();
        names.load();
        march.load();
    }

    private void attachAll() {
        for (NPC n : CitizensAPI.getNPCRegistry()) {
            LifeTrait lt = n.getOrAddTrait(LifeTrait.class);
            if (n.isSpawned()) { lt.ensureCenter(); brains.register(lt); }
        }
    }

    /** Gives every new NPC the (inert) trait so its spawn location becomes its wander centre. */
    @EventHandler
    public void onCreate(NPCCreateEvent e) {
        if (getConfig().getBoolean("auto-attach", true)) e.getNPC().getOrAddTrait(LifeTrait.class);
    }

    /** Covers NPCs that spawn later (chunk loads, /npc spawn): the trait records the spawn point as the home centre. */
    @EventHandler
    public void onNpcSpawn(NPCSpawnEvent e) {
        NPC n = e.getNPC();
        LifeTrait lt = n.getOrAddTrait(LifeTrait.class);
        getServer().getScheduler().runTask(this, () -> { if (n.isSpawned()) { lt.ensureCenter(); brains.register(lt); } });
    }

    /**
     * Army NPCs drop their whole kit (inventory, armor, offhand) when killed, and have a chance
     * (army-leave-chance, default 30%) to leave for good instead of waiting for /npcx army respawn.
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onNpcDeath(EntityDeathEvent e) {
        NPC n = CitizensAPI.getNPCRegistry().getNPC(e.getEntity());
        if (n == null || !n.hasTrait(LifeTrait.class)) return;
        LifeTrait t = n.getOrAddTrait(LifeTrait.class);
        if (!t.isArmy()) return;

        if (getConfig().getBoolean("army-drop-items", true)) {
            if (e instanceof PlayerDeathEvent pde) pde.setKeepInventory(false);
            if (e.getDrops().isEmpty() && e.getEntity() instanceof Player p) {       // make sure the kit really drops
                PlayerInventory inv = p.getInventory();
                for (ItemStack it : inv.getContents()) if (it != null && !it.getType().isAir()) e.getDrops().add(it.clone());
            }
        } else {
            e.getDrops().clear();
            e.setDroppedExp(0);
        }

        double chance = getConfig().getDouble("army-leave-chance", 30);
        if (ThreadLocalRandom.current().nextDouble() * 100.0 < chance) {
            leftCount++;
            getServer().getScheduler().runTaskLater(this, n::destroy, 2L);          // gone for good, can't be respawned
        }
    }
}
