package dev.npclife;

import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

/** Weapon classification and rough stats for 1.21.11 (swords, axes, spears, bows). */
final class Weapons {
    enum Kind { NONE, SWORD, AXE, SPEAR, BOW, OTHER }

    static Kind kind(ItemStack it) {
        if (it == null || it.getType().isAir()) return Kind.NONE;
        String n = it.getType().name();
        if (n.endsWith("_SWORD")) return Kind.SWORD;
        if (n.endsWith("_AXE")) return Kind.AXE;
        if (n.endsWith("_SPEAR")) return Kind.SPEAR;
        if (n.equals("BOW")) return Kind.BOW;
        return Kind.OTHER;
    }

    static int tier(String n) {
        if (n.startsWith("NETHERITE")) return 6;
        if (n.startsWith("DIAMOND")) return 5;
        if (n.startsWith("IRON")) return 4;
        if (n.startsWith("COPPER") || n.startsWith("STONE")) return 3;
        if (n.startsWith("GOLDEN")) return 2;
        return 1;
    }

    /** Ticks until the weapon is fully charged again (attack speed 1.6 sword = 12.5 ticks, axes slower). */
    static int cooldownTicks(ItemStack it) {
        Kind k = kind(it);
        String n = it == null ? "" : it.getType().name();
        switch (k) {
            case SWORD: return 13;
            case AXE: return n.startsWith("WOODEN") || n.startsWith("STONE") ? 25 : n.startsWith("IRON") ? 22 : 20;
            case SPEAR: return 14 + tier(n);
            default: return 10;
        }
    }

    static double reach(Kind k, double base) { return k == Kind.SPEAR ? base + 1.0 : base; }
    static double minReach(Kind k) { return k == Kind.SPEAR ? 1.6 : 0; }

    static double preferredDistance(Kind k) {
        switch (k) {
            case SPEAR: return 3.5;
            case AXE: return 2.6;
            case BOW: return 14;
            default: return 2.7;
        }
    }

    /** Only used if Paper's Player#attack isn't available on this build. */
    static double fallbackDamage(ItemStack it) {
        Kind k = kind(it);
        int t = it == null ? 1 : tier(it.getType().name());
        switch (k) {
            case SWORD: return 3 + t;
            case AXE: return t >= 6 ? 10 : t >= 3 ? 9 : 7;
            case SPEAR: return 2 + t * 0.7;
            default: return 1;
        }
    }

    static int bestSlot(PlayerInventory inv, Kind k) {
        ItemStack[] c = inv.getStorageContents();
        int best = -1, bestTier = -1;
        for (int i = 0; i < c.length; i++) {
            if (kind(c[i]) != k) continue;
            int t = tier(c[i].getType().name());
            if (t > bestTier) { bestTier = t; best = i; }
        }
        return best;
    }

    static boolean isArrow(ItemStack it) {
        if (it == null) return false;
        String n = it.getType().name();
        return n.equals("ARROW") || n.equals("TIPPED_ARROW") || n.equals("SPECTRAL_ARROW");
    }
}
