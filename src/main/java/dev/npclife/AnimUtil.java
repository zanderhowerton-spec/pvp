package dev.npclife;

import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import net.citizensnpcs.api.npc.NPC;

import java.lang.reflect.Method;

/** Plays Citizens' PlayerAnimation (item-use poses etc.) via reflection, since it is not part of the public API. */
final class AnimUtil {
    private static Class<?> cls;
    private static Method play;
    private static boolean withRadius, tried;

    private static void init() {
        if (tried) return;
        tried = true;
        try {
            Plugin c = Bukkit.getPluginManager().getPlugin("Citizens");
            cls = Class.forName("net.citizensnpcs.util.PlayerAnimation", true, c.getClass().getClassLoader());
            try { play = cls.getMethod("play", Player.class); }
            catch (NoSuchMethodException ex) { play = cls.getMethod("play", Player.class, int.class); withRadius = true; }
        } catch (Throwable ex) { play = null; }
    }

    static boolean available() { init(); return play != null; }

    static boolean play(Entity e, String name) {
        init();
        if (play == null || !(e instanceof Player p)) return false;
        try {
            Object constant = cls.getField(name).get(null);
            if (withRadius) play.invoke(constant, p, 64); else play.invoke(constant, p);
            return true;
        } catch (Throwable ex) { return false; }
    }

    /**
     * Sets the same Citizens flag that "/npc useitem" toggles (NPC.Metadata.USING_HELD_ITEM), found by reflection.
     * Returns false if this Citizens build doesn't have it (callers then fall back to PlayerAnimation).
     */
    static boolean setUsingHeldItem(NPC npc, boolean on) {
        try {
            Class<?> meta = Class.forName("net.citizensnpcs.api.npc.NPC$Metadata");
            Object key = null;
            for (Object o : meta.getEnumConstants()) if (((Enum<?>) o).name().equals("USING_HELD_ITEM")) key = o;
            if (key == null) return false;
            Class<?> storeType = Class.forName("net.citizensnpcs.api.npc.MetadataStore");
            Object store = npc.data();
            Method found = null;
            for (Method m : storeType.getMethods()) {
                if (m.getParameterCount() != 2 || !m.getParameterTypes()[0].isAssignableFrom(meta) || m.getParameterTypes()[1] != Object.class) continue;
                if (m.getName().equals("set")) { found = m; break; }               // non-persistent: a crash can't leave it on
                if (m.getName().equals("setPersistent") && found == null) found = m;
            }
            if (found == null) return false;
            found.invoke(store, key, on);
            return true;
        } catch (Throwable ex) { return false; }
    }
}
