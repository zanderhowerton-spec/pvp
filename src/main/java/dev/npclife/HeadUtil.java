package dev.npclife;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;

/**
 * Head rotation.
 * - Standing still: Entity#setRotation (Paper API). Vanilla body-follow logic makes the body trail the head
 *   once the head turns more than ~75 degrees, like a real player.
 * - While navigating: Citizens rewrites the body yaw every tick, so we additionally call Citizens' internal
 *   NMS.setHeadYaw (found by reflection, because it is not a public API). If it is missing, head-only motion
 *   while walking is skipped and the navigator wins - nothing breaks.
 */
final class HeadUtil {
    private static Method headYaw;
    private static boolean tried;

    private static void init() {
        if (tried) return;
        tried = true;
        try {
            Plugin c = Bukkit.getPluginManager().getPlugin("Citizens");
            Class<?> nms = Class.forName("net.citizensnpcs.util.NMS", true, c.getClass().getClassLoader());
            headYaw = nms.getMethod("setHeadYaw", Entity.class, float.class);
        } catch (Throwable ex) {
            headYaw = null;
        }
    }

    static String describe() { init(); return headYaw != null ? "independent head yaw available" : "head-only-while-walking unavailable (fallback: setRotation)"; }

    static float wrap(float a) { a %= 360f; if (a >= 180f) a -= 360f; if (a < -180f) a += 360f; return a; }

    static void apply(Entity e, float yaw, float pitch, boolean navigating) {
        init();
        if (navigating && headYaw != null) {
            try {
                float body = e.getLocation().getYaw();
                float rel = Math.max(-80f, Math.min(80f, wrap(yaw - body)));
                e.setRotation(body, pitch);
                headYaw.invoke(null, e, body + rel);
                return;
            } catch (Throwable ex) { headYaw = null; }
        }
        e.setRotation(yaw, pitch);
    }
}
