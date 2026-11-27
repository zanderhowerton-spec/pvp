package dev.npclife;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/** Removes blocks NPCs placed in fights (webs, bridges) after a while, so battlefields don't stay trashed. */
final class Cleanup {
    private static final class Entry {
        final Location loc; final Material expect; final long due;
        Entry(Location loc, Material expect, long due) { this.loc = loc; this.expect = expect; this.due = due; }
    }

    private static final List<Entry> list = new ArrayList<>();

    static void track(Block b, long ttlTicks) {
        list.add(new Entry(b.getLocation(), b.getType(), NPCLifePlugin.get().brains().now() + ttlTicks));
    }

    static void tick(long now) {
        for (Iterator<Entry> it = list.iterator(); it.hasNext();) {
            Entry e = it.next();
            if (e.due > now) continue;
            if (e.loc.getWorld() == null) { it.remove(); continue; }
            if (!e.loc.getWorld().isChunkLoaded(e.loc.getBlockX() >> 4, e.loc.getBlockZ() >> 4)) continue;
            Block b = e.loc.getBlock();
            if (b.getType() == e.expect) b.setType(Material.AIR);
            it.remove();
        }
    }

    static void flush() {
        for (Entry e : list) {
            if (e.loc.getWorld() == null || !e.loc.getWorld().isChunkLoaded(e.loc.getBlockX() >> 4, e.loc.getBlockZ() >> 4)) continue;
            Block b = e.loc.getBlock();
            if (b.getType() == e.expect) b.setType(Material.AIR);
        }
        list.clear();
    }
}
