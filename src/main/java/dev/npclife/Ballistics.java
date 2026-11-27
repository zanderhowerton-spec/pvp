package dev.npclife;

import org.bukkit.util.Vector;

/** Solves the launch direction for gravity projectiles (ender pearls g=0.03, arrows g=0.05, drag 0.99). */
final class Ballistics {
    /** Unit launch direction so a projectile fired at {@code speed} passes (nearly) through {@code to}; null if out of range. */
    static Vector solve(Vector from, Vector to, double speed, double gravity, double drag) {
        double dx = to.getX() - from.getX(), dz = to.getZ() - from.getZ(), dy = to.getY() - from.getY();
        double horiz = Math.max(0.01, Math.hypot(dx, dz));
        double heading = Math.atan2(dz, dx);
        double best = Double.MAX_VALUE, bestPitch = Double.NaN;
        for (double deg = -60; deg <= 70; deg += 1.0) {
            double th = Math.toRadians(deg);
            double vx = Math.cos(th) * speed, vy = Math.sin(th) * speed, x = 0, y = 0, miss = Double.MAX_VALUE;
            for (int t = 0; t < 140; t++) {
                double px = x, py = y;
                x += vx; y += vy; vx *= drag; vy = vy * drag - gravity;
                if (x >= horiz) {
                    double f = (horiz - px) / Math.max(1e-6, x - px);
                    miss = Math.abs(py + (y - py) * f - dy);
                    break;
                }
                if (y < dy - 90) break;
            }
            if (miss < best) { best = miss; bestPitch = deg; }
        }
        if (Double.isNaN(bestPitch) || best > 2.0) return null;
        double th = Math.toRadians(bestPitch);
        return new Vector(Math.cos(heading) * Math.cos(th), Math.sin(th), Math.sin(heading) * Math.cos(th)).normalize();
    }
}
