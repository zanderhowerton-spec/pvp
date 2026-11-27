package dev.npclife;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.util.function.Predicate;

/** Inventory helpers for NPCs: find items, put them in hand, consume them, manage the offhand. */
final class Gear {
    static boolean isAir(ItemStack it) { return it == null || it.getType().isAir(); }

    static int findSlot(PlayerInventory inv, Predicate<ItemStack> p) {
        ItemStack[] c = inv.getStorageContents();
        for (int i = 0; i < c.length; i++) if (!isAir(c[i]) && p.test(c[i])) return i;
        return -1;
    }

    static int findSlot(PlayerInventory inv, Material m) { return findSlot(inv, it -> it.getType() == m); }

    static int count(PlayerInventory inv, Predicate<ItemStack> p) {
        int n = 0;
        for (ItemStack it : inv.getStorageContents()) if (!isAir(it) && p.test(it)) n += it.getAmount();
        ItemStack off = inv.getItemInOffHand();
        if (!isAir(off) && p.test(off)) n += off.getAmount();
        return n;
    }

    static int count(PlayerInventory inv, Material m) { return count(inv, it -> it.getType() == m); }

    /** Makes the item in storage slot {@code slot} the main-hand item (swapping it into the hotbar if needed). */
    static boolean hold(PlayerInventory inv, int slot) {
        if (slot < 0 || slot > 35) return false;
        if (slot <= 8) { inv.setHeldItemSlot(slot); return true; }
        int target = -1;
        for (int i = 0; i < 9; i++) if (isAir(inv.getItem(i))) { target = i; break; }
        if (target < 0) target = inv.getHeldItemSlot() == 8 ? 7 : 8;
        ItemStack a = inv.getItem(slot), b = inv.getItem(target);
        inv.setItem(target, a);
        inv.setItem(slot, b);
        inv.setHeldItemSlot(target);
        return true;
    }

    static boolean holdMaterial(PlayerInventory inv, Material m) {
        int s = findSlot(inv, m);
        return s >= 0 && hold(inv, s);
    }

    static void consume(PlayerInventory inv, int slot, int n) {
        ItemStack it = inv.getItem(slot);
        if (isAir(it)) return;
        if (it.getAmount() <= n) inv.setItem(slot, null);
        else { it.setAmount(it.getAmount() - n); inv.setItem(slot, it); }
    }

    static boolean consumeMaterial(PlayerInventory inv, Material m) {
        int s = findSlot(inv, m);
        if (s < 0) return false;
        consume(inv, s, 1);
        return true;
    }

    static boolean offhandIs(PlayerInventory inv, Material m) { return inv.getItemInOffHand().getType() == m; }

    /** Cheap solid blocks NPCs may bridge with. */
    static boolean placeable(ItemStack it) {
        if (isAir(it)) return false;
        Material m = it.getType();
        if (!m.isBlock() || !m.isSolid()) return false;
        String n = m.name();
        return n.endsWith("_PLANKS") || n.equals("COBBLESTONE") || n.equals("STONE") || n.equals("DIRT") || n.equals("NETHERRACK")
                || n.endsWith("_WOOL") || n.equals("COBBLED_DEEPSLATE") || n.endsWith("TERRACOTTA") || n.endsWith("_CONCRETE")
                || n.equals("END_STONE") || n.equals("STONE_BRICKS") || n.equals("BLACKSTONE");
    }

    static int placeableSlot(PlayerInventory inv) { return findSlot(inv, Gear::placeable); }
}
