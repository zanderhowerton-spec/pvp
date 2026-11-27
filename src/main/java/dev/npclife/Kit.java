package dev.npclife;

import org.bukkit.inventory.ItemStack;

/** A saved loadout: 36 storage slots (hotbar first), 4 armor pieces (boots, leggings, chestplate, helmet), offhand. */
final class Kit {
    final ItemStack[] storage = new ItemStack[36];
    final ItemStack[] armor = new ItemStack[4];
    ItemStack offhand;
}
