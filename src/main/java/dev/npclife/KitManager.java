package dev.npclife;

import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.trait.trait.Equipment;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;

/** Kits are saved from a player's inventory into plugins/NPCLife/kits.yml and applied to NPCs. */
final class KitManager {
    private final NPCLifePlugin plugin;
    private final Map<String, Kit> kits = new TreeMap<>();
    private final Random rng = new Random();

    KitManager(NPCLifePlugin plugin) { this.plugin = plugin; }

    private File file() { return new File(plugin.getDataFolder(), "kits.yml"); }

    void load() {
        kits.clear();
        if (!file().exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file());
        for (String name : y.getKeys(false)) {
            ConfigurationSection s = y.getConfigurationSection(name);
            if (s == null) continue;
            Kit k = new Kit();
            fill(k.storage, s.getList("storage"));
            fill(k.armor, s.getList("armor"));
            k.offhand = s.getItemStack("offhand");
            kits.put(name.toLowerCase(Locale.ROOT), k);
        }
    }

    private static void fill(ItemStack[] target, List<?> src) {
        if (src == null) return;
        for (int i = 0; i < target.length && i < src.size(); i++) target[i] = src.get(i) instanceof ItemStack it ? it : null;
    }

    private void save() {
        YamlConfiguration y = new YamlConfiguration();
        for (Map.Entry<String, Kit> en : kits.entrySet()) {
            Kit k = en.getValue();
            y.set(en.getKey() + ".storage", Arrays.asList(k.storage));
            y.set(en.getKey() + ".armor", Arrays.asList(k.armor));
            y.set(en.getKey() + ".offhand", k.offhand);
        }
        try { plugin.getDataFolder().mkdirs(); y.save(file()); }
        catch (IOException ex) { plugin.getLogger().warning("Could not save kits: " + ex); }
    }

    Kit saveFrom(Player p, String name) {
        PlayerInventory inv = p.getInventory();
        Kit k = new Kit();
        ItemStack[] st = inv.getStorageContents();
        for (int i = 0; i < 36 && i < st.length; i++) k.storage[i] = st[i] == null ? null : st[i].clone();
        ItemStack[] ar = inv.getArmorContents();
        for (int i = 0; i < 4 && i < ar.length; i++) k.armor[i] = ar[i] == null ? null : ar[i].clone();
        ItemStack off = inv.getItemInOffHand();
        k.offhand = off == null ? null : off.clone();
        kits.put(name.toLowerCase(Locale.ROOT), k);
        save();
        return k;
    }

    Kit get(String name) { return name == null ? null : kits.get(name.toLowerCase(Locale.ROOT)); }
    Set<String> names() { return kits.keySet(); }
    boolean delete(String name) { boolean r = kits.remove(name.toLowerCase(Locale.ROOT)) != null; if (r) save(); return r; }

    /** "knight" -> knight;  "knight:60,archer:40" -> weighted random pick. Returns null if no valid kit. */
    String pick(String spec) {
        if (spec == null || spec.isBlank()) return null;
        String[] parts = spec.split(",");
        String[] names = new String[parts.length];
        double[] w = new double[parts.length];
        double sum = 0;
        int n = 0;
        for (String part : parts) {
            String[] kv = part.split(":");
            String name = kv[0].trim().toLowerCase(Locale.ROOT);
            if (!kits.containsKey(name)) continue;
            double weight = 1;
            if (kv.length > 1) { try { weight = Double.parseDouble(kv[1].replace("%", "").trim()); } catch (NumberFormatException ex) { weight = 1; } }
            names[n] = name; w[n] = Math.max(0, weight); sum += w[n]; n++;
        }
        if (n == 0) return null;
        if (sum <= 0) return names[rng.nextInt(n)];
        double r = rng.nextDouble() * sum;
        for (int i = 0; i < n; i++) { r -= w[i]; if (r <= 0) return names[i]; }
        return names[n - 1];
    }

    /** Puts the kit into the live NPC inventory and mirrors armor/hands into Citizens' Equipment trait. */
    static void apply(NPC npc, Kit k) {
        if (!npc.isSpawned() || !(npc.getEntity() instanceof Player p)) return;
        PlayerInventory inv = p.getInventory();
        inv.clear();
        inv.setStorageContents(copy(k.storage));
        inv.setArmorContents(copy(k.armor));
        inv.setItemInOffHand(k.offhand == null ? new ItemStack(Material.AIR) : k.offhand.clone());
        inv.setHeldItemSlot(0);
        Equipment eq = npc.getOrAddTrait(Equipment.class);
        slot(eq, "BOOTS", k.armor[0]);
        slot(eq, "LEGGINGS", k.armor[1]);
        slot(eq, "CHESTPLATE", k.armor[2]);
        slot(eq, "HELMET", k.armor[3]);
        slot(eq, "OFF_HAND", k.offhand);
        slot(eq, "HAND", k.storage[0]);
    }

    private static void slot(Equipment eq, String name, ItemStack it) {
        try { eq.set(Equipment.EquipmentSlot.valueOf(name), it == null ? new ItemStack(Material.AIR) : it.clone()); }
        catch (IllegalArgumentException ex) { /* slot name not present on this Citizens build */ }
    }

    private static ItemStack[] copy(ItemStack[] src) {
        ItemStack[] out = new ItemStack[src.length];
        for (int i = 0; i < src.length; i++) out[i] = src[i] == null ? null : src[i].clone();
        return out;
    }
}
