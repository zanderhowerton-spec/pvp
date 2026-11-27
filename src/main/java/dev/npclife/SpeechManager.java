package dev.npclife;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Random;

/**
 * Speeches: the army turns to face the speaker and cheers - jumping, swinging at the air, waving - but every NPC starts at a
 * different moment, has its own energy level and rhythm, and takes the odd breather, so it never looks like a synchronized loop.
 */
final class SpeechManager {
    private static final class Fan {
        final LifeTrait t;
        long startAt, nextJump, nextSwing, nextWave, pauseUntil;
        double energy;
        boolean started;
        Fan(LifeTrait t) { this.t = t; }
    }

    private static final class Speech {
        final Player speaker;
        final long endAt;
        final List<Fan> fans = new ArrayList<>();
        Speech(Player speaker, long endAt) { this.speaker = speaker; this.endAt = endAt; }
    }

    private final NPCLifePlugin plugin;
    private final List<Speech> speeches = new ArrayList<>();
    private final Random rng = new Random();
    private BukkitTask task;

    SpeechManager(NPCLifePlugin plugin) { this.plugin = plugin; }

    int start(Player speaker, List<LifeTrait> audience, int seconds, double intensity) {
        stop(speaker);
        long now = plugin.brains().now();
        Speech sp = new Speech(speaker, now + 20L * seconds);
        for (LifeTrait t : audience) {
            if (!t.getNPC().isSpawned() || t.getNPC().getEntity() == speaker) continue;
            Fan f = new Fan(t);
            f.startAt = now + rng.nextInt(80);                                   // reacts within 0-4 seconds, everyone differently
            f.energy = intensity * (0.6 + rng.nextDouble() * 0.8);
            sp.fans.add(f);
        }
        if (sp.fans.isEmpty()) return 0;
        speeches.add(sp);
        if (task == null) task = Bukkit.getScheduler().runTaskTimer(plugin, this::step, 2L, 2L);
        return sp.fans.size();
    }

    int stop(Player speaker) {
        int n = 0;
        for (Iterator<Speech> it = speeches.iterator(); it.hasNext();) {
            Speech s = it.next();
            if (speaker == null || s.speaker == speaker) { end(s); it.remove(); n++; }
        }
        if (speeches.isEmpty() && task != null) { task.cancel(); task = null; }
        return n;
    }

    private void end(Speech s) {
        for (Fan f : s.fans) {
            if (!f.started) continue;
            f.t.brain().stopLook();
            f.t.brain().scriptEnd();
        }
    }

    private void step() {
        long now = plugin.brains().now();
        for (Iterator<Speech> it = speeches.iterator(); it.hasNext();) {
            Speech s = it.next();
            if (!s.speaker.isOnline() || now >= s.endAt) { end(s); it.remove(); continue; }
            for (Fan f : s.fans) tick(s, f, now);
        }
        if (speeches.isEmpty() && task != null) { task.cancel(); task = null; }
    }

    private void tick(Speech s, Fan f, long now) {
        if (!f.t.getNPC().isSpawned()) return;
        Brain b = f.t.brain();
        if (b.combat().engaged()) return;                                          // a fight beats a speech
        if (!f.started) {
            if (now < f.startAt) return;
            f.started = true;
            b.scriptBegin();
            b.manualLookEntity(s.speaker);                                         // follows the speaker with a natural turn
            f.nextJump = now + 10 + rng.nextInt(50);
            f.nextSwing = now + 6 + rng.nextInt(30);
            f.nextWave = now + 30 + rng.nextInt(100);
            return;
        }
        if (now < f.pauseUntil) return;
        double e = Math.max(0.3, f.energy);
        if (now >= f.nextJump) {
            if (b.grounded()) b.hop();
            f.nextJump = now + (long) ((12 + rng.nextInt(34)) / e);
        }
        if (now >= f.nextSwing) {
            b.swing(false);
            if (rng.nextDouble() < 0.25) {                                          // sometimes a quick flurry
                Bukkit.getScheduler().runTaskLater(plugin, () -> b.swing(false), 4L);
                Bukkit.getScheduler().runTaskLater(plugin, () -> b.swing(false), 8L);
            }
            f.nextSwing = now + (long) ((6 + rng.nextInt(20)) / e);
        }
        if (now >= f.nextWave) {
            b.wave();
            f.nextWave = now + (long) ((60 + rng.nextInt(100)) / e);
        }
        if (rng.nextDouble() < 0.006) f.pauseUntil = now + 20 + rng.nextInt(50);    // a breather, out of sync with everyone else
    }
}
