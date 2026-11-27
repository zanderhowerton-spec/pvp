package dev.npclife;

import net.citizensnpcs.api.npc.NPC;

import java.lang.reflect.Method;

/** Sets a Citizens NPC.Metadata flag by name via reflection (so a renamed constant can't break compilation). */
final class MetaUtil {
    static boolean set(NPC npc, String keyName, Object value) {
        try {
            Class<?> meta = Class.forName("net.citizensnpcs.api.npc.NPC$Metadata");
            Object key = null;
            for (Object o : meta.getEnumConstants()) if (((Enum<?>) o).name().equals(keyName)) key = o;
            if (key == null) return false;
            Class<?> storeType = Class.forName("net.citizensnpcs.api.npc.MetadataStore");
            Object store = npc.data();
            Method found = null;
            for (Method m : storeType.getMethods()) {
                if (m.getParameterCount() != 2 || !m.getParameterTypes()[0].isAssignableFrom(meta) || m.getParameterTypes()[1] != Object.class) continue;
                if (m.getName().equals("setPersistent")) { found = m; break; }
                if (m.getName().equals("set") && found == null) found = m;
            }
            if (found == null) return false;
            found.invoke(store, key, value);
            return true;
        } catch (Throwable ex) { return false; }
    }

    static boolean exists(String keyName) {
        try {
            Class<?> meta = Class.forName("net.citizensnpcs.api.npc.NPC$Metadata");
            for (Object o : meta.getEnumConstants()) if (((Enum<?>) o).name().equals(keyName)) return true;
        } catch (Throwable ex) { /* not available */ }
        return false;
    }
}
