package dev.npclife;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;

/** Finds a standable spot (solid floor, two free blocks above) in a column. */
final class Ground {
    static Location standable(World w, double x, double z, int y0) {
        int bx = (int) Math.floor(x), bz = (int) Math.floor(z);
        if (!w.isChunkLoaded(bx >> 4, bz >> 4)) return null;
        int[] order = {0, 1, -1, 2, -2, 3, -3, 4, -4, 5, -5, 6, -6};
        for (int dy : order) {
            int y = y0 + dy;
            if (y < w.getMinHeight() + 1 || y > w.getMaxHeight() - 3) continue;
            Block floor = w.getBlockAt(bx, y - 1, bz), feet = w.getBlockAt(bx, y, bz), head = w.getBlockAt(bx, y + 1, bz);
            if (!floor.getType().isSolid() || !feet.isPassable() || feet.isLiquid() || !head.isPassable() || head.isLiquid()) continue;
            return new Location(w, bx + 0.5, y, bz + 0.5);
        }
        return null;
    }
}
