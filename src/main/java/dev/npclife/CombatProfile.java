package dev.npclife;

import java.util.Random;

/** Everything about HOW an NPC fights, derived from its skill (0..1) and combat style. */
final class CombatProfile {
    double skill;
    String style = "confident";
    int reactionTicks, thinkTicks, patienceTicks;
    double aimSpeed, aimNoise, moveScale;
    double aggression, healAt, retreatAt, chaseLimit, shieldUse, utility, confidenceBase;
    boolean unpredictable;
    double mistake, fuzz;

    /** Can this player do the technique at all? (beginners can't w-tap, jump-crit, wind-charge, ...) */
    boolean has(double minSkill) { return skill + fuzz >= minSkill; }

    static double clamp(double v, double lo, double hi) { return Math.max(lo, Math.min(hi, v)); }

    static CombatProfile of(double skill, String style, Random rng) {
        CombatProfile p = new CombatProfile();
        p.skill = clamp(skill, 0, 1);
        p.style = style == null ? "confident" : style;
        p.reactionTicks = (int) clamp(Math.round(14 - 11 * p.skill + (rng.nextDouble() - 0.5) * 2), 3, 16);
        p.thinkTicks = (int) clamp(Math.round(7 - 4 * p.skill), 3, 7);
        p.aimSpeed = 6 + 22 * p.skill + (rng.nextDouble() - 0.5) * 3;
        p.aimNoise = 0.6 + (1 - p.skill) * 5.5;
        // Mistake rate: ~30% for a total beginner (skill 0.1), ~12% for an average player (0.5), ~1% for an expert (0.95+).
        p.mistake = Math.max(0.01, 0.30 * Math.pow((1 - p.skill) / 0.9, 1.5));
        p.fuzz = (rng.nextDouble() - 0.5) * 0.10;                  // every player unlocks techniques at a slightly different level
        p.moveScale = 0.92 + rng.nextDouble() * 0.12;
        int patience;
        switch (p.style) {
            case "aggressive":   p.aggression = 0.85; p.healAt = 0.30; p.retreatAt = 0.12; p.chaseLimit = 55; p.shieldUse = 0.25; p.utility = 0.55; patience = 14; p.confidenceBase = 0.7; break;
            case "defensive":    p.aggression = 0.30; p.healAt = 0.55; p.retreatAt = 0.30; p.chaseLimit = 22; p.shieldUse = 0.85; p.utility = 0.50; patience = 6;  p.confidenceBase = 0.45; break;
            case "cowardly":     p.aggression = 0.15; p.healAt = 0.60; p.retreatAt = 0.45; p.chaseLimit = 14; p.shieldUse = 0.60; p.utility = 0.60; patience = 4;  p.confidenceBase = 0.3; break;
            case "tactical":     p.aggression = 0.50; p.healAt = 0.45; p.retreatAt = 0.22; p.chaseLimit = 30; p.shieldUse = 0.65; p.utility = 0.85; patience = 9;  p.confidenceBase = 0.55; break;
            case "unpredictable":p.aggression = 0.55; p.healAt = 0.40; p.retreatAt = 0.20; p.chaseLimit = 32; p.shieldUse = 0.50; p.utility = 0.60; patience = 8;  p.confidenceBase = 0.5; p.unpredictable = true; break;
            default:             p.aggression = 0.70; p.healAt = 0.38; p.retreatAt = 0.18; p.chaseLimit = 40; p.shieldUse = 0.45; p.utility = 0.50; patience = 10; p.confidenceBase = 0.6; break;
        }
        p.patienceTicks = patience * 20;
        p.utility *= 0.6 + 0.4 * p.skill;                      // better players use utility more reliably
        p.healAt += (p.skill - 0.5) * 0.06;
        return p;
    }

    /** Unpredictable players change their whole attitude every few seconds. */
    void reroll(Random rng) {
        aggression = 0.15 + rng.nextDouble() * 0.8;
        healAt = 0.25 + rng.nextDouble() * 0.35;
        retreatAt = 0.10 + rng.nextDouble() * 0.25;
        shieldUse = rng.nextDouble();
        utility = (0.3 + rng.nextDouble() * 0.6) * (0.6 + 0.4 * skill);
    }
}
