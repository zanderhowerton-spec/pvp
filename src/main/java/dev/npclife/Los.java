package dev.npclife;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.Openable;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.util.Vector;

/** Line of sight where trapdoors always count as full blocks and closed doors/gates block too. */
final class Los {
    static boolean sees(Entity from, Entity to) {
        World w = from.getWorld();
        if (w != to.getWorld()) return false;
        Location a = from instanceof LivingEntity la ? la.getEyeLocation() : from.getLocation().add(0, 1.6, 0);
        Location b = to instanceof LivingEntity lb ? lb.getEyeLocation() : to.getLocation().add(0, 1.0, 0);
        Vector d = b.toVector().subtract(a.toVector());
        double len = d.length();
        if (len < 0.5) return true;
        d.multiply(1.0 / len);
        Block startB = a.getBlock(), endB = b.getBlock(), last = null;
        for (double st = 0.3; st < len; st += 0.3) {
            int x = (int) Math.floor(a.getX() + d.getX() * st), y = (int) Math.floor(a.getY() + d.getY() * st), z = (int) Math.floor(a.getZ() + d.getZ() * st);
            if (!w.isChunkLoaded(x >> 4, z >> 4)) return false;
            Block blk = w.getBlockAt(x, y, z);
            if (blk.equals(last) || blk.equals(startB) || blk.equals(endB)) continue;
            last = blk;
            if (blocksSight(blk)) return false;
        }
        return true;
    }

    static boolean blocksSight(Block b) {
        Material m = b.getType();
        if (m.isAir()) return false;
        if (Tag.TRAPDOORS.isTagged(m)) return true;
        if (Tag.DOORS.isTagged(m) || Tag.FENCE_GATES.isTagged(m)) return !(b.getBlockData() instanceof Openable o && o.isOpen());
        return m.isOccluding();
    }
}
