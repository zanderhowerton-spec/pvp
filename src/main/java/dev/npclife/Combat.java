package dev.npclife;

import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.ai.Navigator;
import net.citizensnpcs.api.npc.NPC;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.Objects;
import java.util.Random;

/**
 * One NPC's fighting brain: target choice, state machine (engage / retreat / heal / pursue / hold), human-like aim,
 * strafing and spacing, attack timing with misses, shield use, weapon choice. Utility items (healing, webs, pearls,
 * wind charges, water clutch, bridging) live in CombatUtil.
 */
final class Combat {
    enum State { IDLE, ENGAGE, RETREAT, HEAL, PURSUE, HOLD }

    final Brain brain;
    final LifeTrait t;
    final Random rng = new Random();
    final CombatUtil util;

    CombatProfile prof;
    private double profSkill = -1;
    private String profStyle;

    State state = State.IDLE;
    long now;
    Player me;
    Entity target, revenge, avoid;
    long revengeUntil, avoidUntil, nextThink, nextScan, lastSeenTarget, visAt, decisionUntil, retreatUntil, rerollAt;
    boolean visible = true;
    Location lastKnown;
    double confidence = 0.5;

    // aim
    float yaw, pitch, vyaw, vpitch;
    private boolean aimInit;
    private double noiseYaw, noisePitch;
    long aimOvUntil;
    float ovYaw, ovPitch;
    private final Vector[] ring = new Vector[32];
    private int ringHead, ringCount;

    // movement
    private final Vector vel = new Vector(0, 0, 0);
    int strafeDir = 1;
    private double strafeStrength = 0.8, prefNoise;
    private long strafeUntil, pauseUntil, backoffUntil, wtapUntil, navAt, prefUntil;
    long lastHurtTick = -1000, lastHitTick = -1000;

    // attack
    long nextAttack, lastSwing, itemSwapUntil, lastSwitch, reactionUntil;
    int combo;
    private boolean wasInRange;
    boolean tokenHeld;
    private long tokenUntil;
    private boolean spearPref;
    private long rolledFor = -1;

    // shield
    boolean shieldUp;
    long shieldUntil, shieldNextAllowed, shieldDisabledUntil, shieldReactAt;
    private int shieldReactHold;
    private boolean prevTargetAir;

    Combat(Brain brain, LifeTrait t) {
        this.brain = brain;
        this.t = t;
        this.util = new CombatUtil(this);
    }

    // ------------------------------------------------------------------ small helpers
    static double cfg(String k, double d) { return NPCLifePlugin.get().getConfig().getDouble("combat." + k, d); }
    static boolean cfgB(String k, boolean d) { return NPCLifePlugin.get().getConfig().getBoolean("combat." + k, d); }
    static String cfgS(String k, String d) { return NPCLifePlugin.get().getConfig().getString("combat." + k, d); }
    private static double clamp(double v, double lo, double hi) { return Math.max(lo, Math.min(hi, v)); }
    private static double clamp01(double v) { return clamp(v, 0, 1); }
    private static float wrap(float a) { return HeadUtil.wrap(a); }

    static double dist(Entity a, Entity b) {
        return a.getWorld() == b.getWorld() ? a.getLocation().distance(b.getLocation()) : Double.MAX_VALUE;
    }

    static boolean isNpc(Entity e) { return CitizensAPI.getNPCRegistry().isNPC(e); }

    static Combat of(Entity e) {
        if (e == null) return null;
        NPC n = CitizensAPI.getNPCRegistry().getNPC(e);
        if (n == null || !n.hasTrait(LifeTrait.class)) return null;
        return n.getOrAddTrait(LifeTrait.class).brain().combat();
    }

    static Vector dirOf(float yaw, float pitch) {
        double yr = Math.toRadians(yaw), pr = Math.toRadians(pitch);
        return new Vector(-Math.sin(yr) * Math.cos(pr), -Math.sin(pr), Math.cos(yr) * Math.cos(pr));
    }

    static boolean blocking(Entity x) {
        if (x instanceof LivingEntity le && le.isBlocking()) return true;
        Combat c = of(x);
        return c != null && c.shieldUp;
    }

    static boolean shieldDisabled(Entity x) {
        if (x instanceof Player p && p.getCooldown(Material.SHIELD) > 0) return true;
        Combat c = of(x);
        return c != null && c.now < c.shieldDisabledUntil;
    }

    static boolean airborne(Entity x) {
        if (x.isOnGround() || x.isInWater()) return false;
        return !(x instanceof LivingEntity le && le.isClimbing());
    }

    boolean engaged() { return target != null; }
    boolean grounded() { return brain.grounded(); }
    void hop() { brain.hop(); }
    void forceAim(float y, float p, int ticks) { ovYaw = y; ovPitch = p; aimOvUntil = now + ticks; }

    // ------------------------------------------------------------------ main tick (called by Brain every tick)
    /** Returns true while this NPC is fighting (or doing a combat emergency action) and Brain should stand aside. */
    boolean tick(long nowTick, Entity ent) {
        if (!(ent instanceof Player pl)) return false;
        now = nowTick;
        me = pl;
        if (!t.combatOn()) { if (target != null) dropTarget(); return false; }
        if (prof == null || t.skill() != profSkill || !Objects.equals(t.style(), profStyle)) {
            profSkill = t.skill();
            profStyle = t.style();
            prof = CombatProfile.of(profSkill, profStyle, rng);
        }
        if (!aimInit) { Location l = pl.getLocation(); yaw = l.getYaw(); pitch = l.getPitch(); aimInit = true; }
        util.passive();
        if (now >= nextThink) { nextThink = now + prof.thinkTicks + rng.nextInt(3); think(); }
        if (target == null) return util.busy();
        record();
        util.tick();
        aim();
        shieldTick();
        attackTick();
        move();
        return true;
    }

    void reset() {
        target = null; state = State.IDLE; shieldUp = false; tokenHeld = false; ringCount = 0;
        util.reset();
    }

    // ------------------------------------------------------------------ deciding
    private void think() {
        validateTarget();
        if (target == null || now >= nextScan) { nextScan = now + 12 + rng.nextInt(14); pickTarget(); }
        if (target == null) { if (state != State.IDLE) endFight(); return; }
        if (state == State.IDLE) beginFight();
        double frac = me.getHealth() / Math.max(1.0, me.getMaxHealth());
        double dist = dist(me, target);
        boolean trapped = CombatUtil.inWeb(target);
        boolean disabled = shieldDisabled(target);
        double tFrac = target instanceof LivingEntity le ? le.getHealth() / Math.max(1.0, le.getMaxHealth()) : 1.0;
        confidence = clamp01(confidence + (frac - tFrac) * 0.03 + (prof.confidenceBase - confidence) * 0.01);
        if (prof.unpredictable && now >= rerollAt) { rerollAt = now + 80 + rng.nextInt(160); prof.reroll(rng); }
        tokenThink();
        if (rng.nextDouble() < prof.mistake * 0.03) pauseUntil = now + 10 + rng.nextInt(20);   // brain-freeze: beginners hesitate more

        boolean healingNow = util.healing();
        boolean losing = confidence < 0.28 || (frac < 0.4 && tFrac > frac + 0.25);
        State next;
        if (healingNow && util.healSafe(dist)) next = State.HEAL;
        else if (frac <= prof.healAt && util.hasHealing() && !healingNow)
            next = (dist >= cfg("heal-safe-distance", 6.5) || disabled || trapped) ? State.HEAL : State.RETREAT;   // create space first
        else if ((frac <= prof.retreatAt && prof.aggression < 0.85) || (losing && rng.nextDouble() > prof.aggression) || now < retreatUntil)
            next = State.RETREAT;
        else if (dist > 9) next = farDecision(dist, frac);
        else next = State.ENGAGE;
        if (target == null) return;                          // gave up inside farDecision
        if (next != state) { if (next == State.ENGAGE) prefUntil = 0; state = next; }
        util.think(dist, frac, tFrac, trapped);
        chooseWeapon(dist);
    }

    /** The target is away from melee range: chase, hold position, or give up - differently for every NPC. */
    private State farDecision(double dist, double frac) {
        if (now < decisionUntil && (state == State.PURSUE || state == State.HOLD)) return state;
        if (dist > prof.chaseLimit * 1.15 || (!visible && now - lastSeenTarget > prof.patienceTicks)) { giveUp(); return State.IDLE; }
        double chase = prof.aggression * 0.5 + confidence * 0.3 + (frac > 0.6 ? 0.1 : 0) + util.alliesNear() * 0.05
                - (dist / prof.chaseLimit) * 0.45 - (frac < 0.35 ? 0.3 : 0) + (rng.nextDouble() - 0.5) * 0.25;
        decisionUntil = now + 50 + rng.nextInt(110);
        if (rng.nextDouble() < Math.max(0.05, chase)) return State.PURSUE;
        if (rng.nextDouble() < 0.35) { giveUp(); return State.IDLE; }     // loses interest and walks back to the group
        return State.HOLD;
    }

    private void tokenThink() {
        if (tokenHeld && now < tokenUntil) return;
        tokenHeld = false;
        int held = NPCLifePlugin.get().brains().tokensOn(target, this);
        boolean bold = prof.aggression > 0.8 && rng.nextDouble() < 0.5;
        if (held < (int) cfg("max-attackers", 3) || bold) { tokenHeld = true; tokenUntil = now + 40 + rng.nextInt(80); }
    }

    private void validateTarget() {
        if (target == null) return;
        if (!target.isValid() || target.isDead() || target.getWorld() != me.getWorld()) { dropTarget(); return; }
        if (target instanceof Player pl && !isNpc(pl) && !pl.isOnline()) { dropTarget(); return; }
        if (now - visAt >= 6) { visAt = now; visible = Los.sees(me, target); }
        if (visible) { lastSeenTarget = now; lastKnown = target.getLocation().clone(); }
        if (dist(me, target) > prof.chaseLimit * 1.4 || (!visible && now - lastSeenTarget > prof.patienceTicks)) giveUp();
    }

    private void pickTarget() {
        String myTeam = t.team();
        TeamManager tm = NPCLifePlugin.get().teams();
        double view = cfg("view-distance", 40);
        Location here = me.getLocation();
        Entity best = null;
        double bestScore = Double.MAX_VALUE;
        for (Entity c : NPCLifePlugin.get().brains().pool()) {
            if (c == me || !c.isValid() || c.isDead() || c.getWorld() != me.getWorld()) continue;
            if (c == avoid && now < avoidUntil) continue;
            String ct = tm.teamOf(c);
            boolean hostile = myTeam != null && ct != null && tm.areHostile(myTeam, ct);
            if (!hostile && c == revenge && now < revengeUntil && !(myTeam != null && myTeam.equalsIgnoreCase(ct))) hostile = true;
            if (!hostile) continue;
            if (c instanceof Player pl && !isNpc(pl)) {
                if (pl.getGameMode() == GameMode.SPECTATOR) continue;
                if (pl.getGameMode() == GameMode.CREATIVE && !cfgB("attack-creative", false)) continue;
            }
            double d = c.getLocation().distance(here);
            if (d > view) continue;
            double score = d;
            if (c == target) score -= 4;
            if (c == revenge && now < revengeUntil) score -= 5;
            if (c instanceof LivingEntity le && le.getHealth() < 8) score -= 2;
            score += 2.5 * NPCLifePlugin.get().brains().attackersOf(c, this);   // spread out: don't all pick the same victim
            score += rng.nextDouble() * 3 * (1 - prof.skill);                   // imperfect choices
            if (score < bestScore) { bestScore = score; best = c; }
        }
        if (best == null || best == target) return;
        if (target == null && !Los.sees(me, best) && best.getLocation().distance(here) > 14) return;
        target = best;
        ringCount = 0;
        lastSeenTarget = now;
        lastKnown = best.getLocation().clone();
        visible = true;
        visAt = now;
        tokenHeld = false;
        reactionUntil = now + prof.reactionTicks + rng.nextInt(6);
    }

    private void beginFight() {
        brain.combatBegin();
        state = State.ENGAGE;
        lastSeenTarget = now;
        spearPref = rng.nextBoolean();
        prefUntil = 0;
        confidence = prof.confidenceBase;
    }

    void endFight() {
        state = State.IDLE;
        lowerShield();
        brain.setManualSprint(false);
        brain.setManualSneak(false);
        util.stopUse();
        target = null;
        tokenHeld = false;
        brain.combatEnd();
    }

    void giveUp() {
        avoid = target;
        avoidUntil = now + 20 * 15;
        dropTarget();
        util.returnHome();
    }

    void dropTarget() {
        target = null;
        ringCount = 0;
        if (state != State.IDLE) endFight();
    }

    // ------------------------------------------------------------------ aim (smooth, delayed, never perfect)
    private void record() {
        ring[ringHead] = target.getLocation().toVector();
        ringHead = (ringHead + 1) % ring.length;
        if (ringCount < ring.length) ringCount++;
    }

    private Vector lagged(int ticks) {
        if (ringCount == 0) return target.getLocation().toVector();
        int idx = Math.min(ticks, ringCount - 1);
        return ring[((ringHead - 1 - idx) % ring.length + ring.length) % ring.length];
    }

    double targetSpeed() {                      // blocks per second
        if (ringCount < 5) return 0;
        Vector a = lagged(0), b = lagged(4);
        return Math.hypot(a.getX() - b.getX(), a.getZ() - b.getZ()) / 4.0 * 20.0;
    }

    Vector targetVel() {                        // blocks per tick, horizontal
        if (ringCount < 5) return new Vector(0, 0, 0);
        Vector a = lagged(0), b = lagged(4);
        return new Vector((a.getX() - b.getX()) / 4.0, 0, (a.getZ() - b.getZ()) / 4.0);
    }

    boolean targetMovingAway() {
        if (ringCount < 20) return false;
        Vector p = me.getLocation().toVector();
        return lagged(0).distance(p) > lagged(19).distance(p) + 2.0;
    }

    private void aim() {
        float ty, tp;
        if (now < aimOvUntil) { ty = ovYaw; tp = ovPitch; }
        else {
            Vector at;
            if (visible || lastKnown == null) at = lagged(prof.reactionTicks).clone().add(new Vector(0, target.getHeight() * 0.62, 0));
            else at = lastKnown.toVector().add(new Vector(0, 1.0, 0));
            Vector d = at.subtract(me.getEyeLocation().toVector());
            ty = (float) Math.toDegrees(Math.atan2(-d.getX(), d.getZ()));
            tp = (float) Math.toDegrees(-Math.atan2(d.getY(), Math.hypot(d.getX(), d.getZ())));
            noiseYaw = clamp(noiseYaw + (rng.nextDouble() - 0.5) * 0.5, -prof.aimNoise, prof.aimNoise);
            noisePitch = clamp(noisePitch + (rng.nextDouble() - 0.5) * 0.4, -prof.aimNoise * 0.6, prof.aimNoise * 0.6);
            ty += (float) noiseYaw;
            tp += (float) noisePitch;
        }
        float dy = wrap(ty - yaw), dp = tp - pitch, max = (float) prof.aimSpeed;
        float desY = Math.max(-max, Math.min(max, dy * 0.35f));
        float desP = Math.max(-max * 0.7f, Math.min(max * 0.7f, dp * 0.35f));
        vyaw += (desY - vyaw) * 0.45f;            // eased: no sudden head flicks, no instant 180s
        vpitch += (desP - vpitch) * 0.45f;
        yaw = wrap(yaw + vyaw);
        pitch = (float) clamp(pitch + vpitch, -85, 85);
        HeadUtil.apply(me, yaw, pitch, brain.navigator().isNavigating());
    }

    // ------------------------------------------------------------------ attacking
    void chooseWeapon(double dist) {
        if (util.healing() || util.throwing() || now < itemSwapUntil) return;
        PlayerInventory inv = me.getInventory();
        Weapons.Kind cur = Weapons.kind(inv.getItemInMainHand());
        boolean arrows = Gear.count(inv, Weapons::isArrow) > 0;
        int bow = Gear.findSlot(inv, Material.BOW);
        int axe = Weapons.bestSlot(inv, Weapons.Kind.AXE), sword = Weapons.bestSlot(inv, Weapons.Kind.SWORD), spear = Weapons.bestSlot(inv, Weapons.Kind.SPEAR);
        boolean shieldBreak = prof.has(0.25) && blocking(target) && !shieldDisabled(target) && axe >= 0 && rng.nextDouble() < (0.35 + prof.skill * 0.5) * (1 - prof.mistake);
        Weapons.Kind want;
        if (bow >= 0 && arrows && dist > cfg("bow-min-distance", 9) && visible && state != State.RETREAT) want = Weapons.Kind.BOW;
        else if (shieldBreak || (cur == Weapons.Kind.AXE && blocking(target) && !shieldDisabled(target))) want = Weapons.Kind.AXE;
        else if (spear >= 0 && (sword < 0 || spearPref)) want = Weapons.Kind.SPEAR;
        else if (sword >= 0) want = Weapons.Kind.SWORD;
        else if (axe >= 0) want = Weapons.Kind.AXE;
        else if (spear >= 0) want = Weapons.Kind.SPEAR;
        else return;
        if (want == cur) return;
        if (now < lastSwitch + 25 && want != Weapons.Kind.AXE) return;       // no constant swapping
        int slot = want == Weapons.Kind.BOW ? bow : Weapons.bestSlot(inv, want);
        if (slot < 0) return;
        Gear.hold(inv, slot);
        lastSwitch = now;
        itemSwapUntil = now + 3 + prof.reactionTicks / 3 + rng.nextInt(4);
    }

    void restoreWeaponLater(long delay) {
        Bukkit.getScheduler().runTaskLater(NPCLifePlugin.get(), () -> {
            if (me != null && me.isValid() && target != null) { itemSwapUntil = 0; lastSwitch = 0; chooseWeapon(dist(me, target)); }
        }, delay);
    }

    private void attackTick() {
        if (util.healing() || util.throwing() || util.bridging) return;
        ItemStack held = me.getInventory().getItemInMainHand();
        Weapons.Kind k = Weapons.kind(held);
        if (k == Weapons.Kind.BOW) { util.ranged(); return; }
        if (k == Weapons.Kind.NONE || k == Weapons.Kind.OTHER || now < itemSwapUntil) return;
        double reach = Weapons.reach(k, cfg("reach", 3.0));
        Location eye = me.getEyeLocation();
        BoundingBox bb = target.getBoundingBox();
        Vector near = new Vector(clamp(eye.getX(), bb.getMinX(), bb.getMaxX()), clamp(eye.getY(), bb.getMinY(), bb.getMaxY()), clamp(eye.getZ(), bb.getMinZ(), bb.getMaxZ()));
        double d = near.distance(eye.toVector());
        boolean inRange = d <= reach + 0.7;
        if (inRange && !wasInRange) reactionUntil = Math.max(reactionUntil, now + prof.reactionTicks / 2 + rng.nextInt(4));   // takes a moment to react
        wasInRange = inRange;
        if (!inRange || d < Weapons.minReach(k)) return;
        // skilled players jump-crit just before the weapon is charged
        if (prof.has(0.5) && grounded() && now >= nextAttack - 6 && now < nextAttack - 1 && d < 3.8 && !shieldUp && rng.nextDouble() < 0.18) hop();
        boolean ready = now >= nextAttack;
        boolean early = !ready && now >= nextAttack - 4 && rng.nextDouble() < (1 - prof.skill) * 0.10;     // impatient swing: weak hit
        if (!ready && !early) return;
        if (now < reactionUntil) return;
        if (!tokenHeld && prof.aggression < 0.8 && d > 2.2) return;                 // wait for a turn instead of dogpiling
        if (blocking(target) && k != Weapons.Kind.AXE && inShieldArc(target) && rng.nextDouble() > 0.12) return;   // don't waste hits on a shield
        if (rolledFor != nextAttack) {                                              // one roll per swing: mistimed?
            rolledFor = nextAttack;
            if (rng.nextDouble() < prof.mistake * 0.6) { nextAttack = now + 4 + rng.nextInt(9); rolledFor = nextAttack; return; }
        }
        swing(held, d, reach);
    }

    private boolean inShieldArc(Entity def) {
        Vector face = def.getLocation().getDirection().setY(0);
        Vector toMe = me.getLocation().toVector().subtract(def.getLocation().toVector()).setY(0);
        if (face.lengthSquared() < 1e-4 || toMe.lengthSquared() < 1e-4) return true;
        return Math.toDegrees(face.angle(toMe)) <= 55;
    }

    private void swing(ItemStack held, double d, double reach) {
        me.swingMainHand();
        lastSwing = now;
        nextAttack = now + Weapons.cooldownTicks(held) + rng.nextInt(1 + (int) ((1 - prof.skill) * 5));
        // does the swing actually connect? based on where the head REALLY points, not on where it "should"
        Location eye = me.getEyeLocation();
        RayTraceResult r = target.getBoundingBox().expand(0.12).rayTrace(eye.toVector(), dirOf(yaw, pitch), reach + 0.3);
        boolean geometric = r != null && r.getHitPosition().distance(eye.toVector()) <= reach + 0.05 && Los.sees(me, target);
        double hard = 1 - prof.skill * 0.85;                                        // good players barely lose accuracy to movement/spacing
        double miss = prof.mistake + hard * (Math.min(0.18, targetSpeed() * 0.04) + (d > reach - 0.4 ? 0.10 : 0)
                + (airborne(target) ? 0.06 : 0) + (airborne(me) ? 0.05 : 0));
        miss = Math.min(0.6, miss * cfg("miss-scale", 1.0));
        if (!geometric || rng.nextDouble() < miss) { combo = 0; return; }
        if (prof.has(0.3) && !me.isSprinting() && rng.nextDouble() < 0.6 + prof.skill * 0.3) brain.setManualSprint(true);   // sprint hit: extra knockback
        util.nativeAttack(me, target);
        lastHitTick = now;
        combo++;
        confidence = clamp01(confidence + 0.025);
        if (prof.has(0.45) && rng.nextDouble() < 0.35 + prof.skill * 0.4) backoffUntil = now + 3 + rng.nextInt(5);   // knockback management
        if (prof.has(0.3)) wtapUntil = now + 2;                                                       // W-tap: release sprint for a moment to reset it
    }

    // ------------------------------------------------------------------ shield
    private void shieldTick() {
        boolean has = Gear.offhandIs(me.getInventory(), Material.SHIELD) && now >= shieldDisabledUntil && !util.usingItem();
        if (shieldUp && (now >= shieldUntil || !has)) lowerShield();
        if (!has) return;
        if (shieldReactAt > 0 && now >= shieldReactAt) {
            shieldReactAt = 0;
            if (!shieldUp && now >= shieldNextAllowed) raiseShield(shieldReactHold);
        }
        double dist = dist(me, target);
        boolean air = airborne(target);
        if (air && !prevTargetAir && dist < 8 && shieldReactAt == 0 && !shieldUp) {          // opponent jumped / was launched
            double chance = clamp(cfg("shield-airborne-chance", 0.8) + (prof.skill - 0.5) * 0.2
                    + ("defensive".equals(prof.style) ? 0.08 : "aggressive".equals(prof.style) ? -0.12 : 0), 0.3, 0.97) * (1 - prof.mistake * 0.3);
            if (rng.nextDouble() < chance) {
                shieldReactAt = now + prof.reactionTicks + rng.nextInt(7) + (int) ((1 - prof.skill) * 6);   // everybody reacts at a different moment
                shieldReactHold = 10 + rng.nextInt(18);
            }
        }
        prevTargetAir = air;
        if (!shieldUp && shieldReactAt == 0 && now >= shieldNextAllowed && dist < Weapons.reach(Weapons.Kind.SWORD, cfg("reach", 3.0)) + 1.5 && enemySwungRecently()) {
            if (rng.nextDouble() < prof.shieldUse * 0.55 * (0.5 + prof.skill)) {
                shieldReactAt = now + prof.reactionTicks / 2 + rng.nextInt(3);
                shieldReactHold = 8 + rng.nextInt(14);
            }
        }
        if (!shieldUp && shieldReactAt == 0 && now >= shieldNextAllowed && (state == State.RETREAT || state == State.HEAL) && dist < 5 && rng.nextDouble() < 0.04 * prof.shieldUse) {
            shieldReactAt = now + prof.reactionTicks;
            shieldReactHold = 12 + rng.nextInt(16);
        }
    }

    private boolean enemySwungRecently() {
        long swung = -1000;
        Combat c = of(target);
        if (c != null) swung = c.lastSwing;
        else if (target instanceof Player p) swung = CombatListener.lastSwing.getOrDefault(p.getUniqueId(), -1000L);
        return now - swung <= 8;
    }

    void raiseShield(int hold) {
        shieldUp = true;
        shieldUntil = now + hold;
        MetaUtil.set(brain.npc(), "USING_OFFHAND_ITEM", true);     // raises the shield visibly (and really, if Citizens makes it block)
        brain.setManualSprint(false);
    }

    void lowerShield() {
        if (!shieldUp) return;
        shieldUp = false;
        MetaUtil.set(brain.npc(), "USING_OFFHAND_ITEM", false);
        shieldNextAllowed = now + 8 + rng.nextInt(18);              // never holds it up constantly
    }

    /** Called by the damage listener: simulated blocking for when Citizens' shield doesn't block for real. */
    boolean shieldAbsorbs(Entity damager, EntityDamageByEntityEvent ev) {
        if (me == null || !shieldUp || now < shieldDisabledUntil || damager == null || me.isBlocking()) return false;
        Vector face = me.getLocation().getDirection().setY(0);
        Vector to = damager.getLocation().toVector().subtract(me.getLocation().toVector()).setY(0);
        if (face.lengthSquared() < 1e-4 || to.lengthSquared() < 1e-4 || Math.toDegrees(face.angle(to)) > 50) return false;
        me.getWorld().playSound(me.getLocation(), "item.shield.block", SoundCategory.PLAYERS, 1f, 0.9f + rng.nextFloat() * 0.2f);
        boolean axe = ev.getDamager() == damager && damager instanceof LivingEntity le && le.getEquipment() != null
                && Weapons.kind(le.getEquipment().getItemInMainHand()) == Weapons.Kind.AXE;
        if (axe) {                                                   // axes disable a shield for 5 seconds
            shieldDisabledUntil = now + 100;
            lowerShield();
            me.setCooldown(Material.SHIELD, 100);
            confidence = clamp01(confidence - 0.06);
            me.getWorld().playSound(me.getLocation(), "item.shield.break", SoundCategory.PLAYERS, 1f, 1f);
        }
        me.setVelocity(me.getVelocity().add(to.normalize().multiply(-0.22)));
        return true;
    }

    /** Command: go after this entity regardless of teams. */
    void forceTarget(Entity e) {
        long n = NPCLifePlugin.get().brains().now();
        revenge = e;
        revengeUntil = n + 20 * 120;
        avoid = null;
        if (target != e) { target = e; ringCount = 0; lastSeenTarget = n; lastKnown = e.getLocation().clone(); visible = true; visAt = n; }
    }

    // ------------------------------------------------------------------ events
    void onHurt(Entity damager, double dmg) {
        long n = NPCLifePlugin.get().brains().now();
        lastHurtTick = n;
        confidence = clamp01(confidence - Math.min(0.12, dmg * 0.015));
        if (damager == null || me == null) return;
        if (util.healing() && dist(me, damager) < 3.5) util.onHurtWhileHealing(dmg);
        if (cfgB("revenge", true) && damager != target) {
            String mt = t.team(), dt = NPCLifePlugin.get().teams().teamOf(damager);
            if (mt == null || !mt.equalsIgnoreCase(dt)) { revenge = damager; revengeUntil = n + 20 * 12; }
        }
    }

    void onDealt(double dmg) { confidence = clamp01(confidence + Math.min(0.08, dmg * 0.01)); }

    void onTotem() {
        lastPop = NPCLifePlugin.get().brains().now();
        confidence = clamp01(confidence - 0.3);
        if (!"aggressive".equals(prof == null ? "" : prof.style)) retreatUntil = lastPop + 40 + rng.nextInt(80);   // cautious after a pop
        util.afterPop();
    }

    // ------------------------------------------------------------------ movement
    private double pref() {
        if (now >= prefUntil) { prefUntil = now + 40 + rng.nextInt(80); prefNoise = (rng.nextDouble() - 0.5) * 0.7; }
        double base = Weapons.preferredDistance(Weapons.kind(me.getInventory().getItemInMainHand()));
        return Math.max(1.8, base + (0.5 - prof.aggression) * 0.8 + prefNoise);
    }

    private void pathTo(Location goal, boolean sprint) {
        Navigator nav = brain.navigator();
        vel.zero();
        if (now >= navAt || !nav.isNavigating()) {
            navAt = now + 8 + rng.nextInt(6);
            if (!util.tryBridgeOrPearl(goal)) {
                nav.getLocalParameters().speedModifier(sprint ? 1.3f : 1.0f);
                nav.getLocalParameters().distanceMargin(2.0);
                nav.getLocalParameters().range((float) Math.max(30.0, me.getLocation().distance(goal) * 1.5));
                nav.setTarget(goal);
            }
        }
        brain.setManualSprint(sprint && !shieldUp);
    }

    private void move() {
        if (util.bridging) { util.bridgeTick(); return; }
        Navigator nav = brain.navigator();
        Location here = me.getLocation(), tl = target.getLocation();
        Vector toT = new Vector(tl.getX() - here.getX(), 0, tl.getZ() - here.getZ());
        double dist = toT.length();
        if (dist < 0.05) return;
        boolean far = dist > 8 || !visible;
        String mode = cfgS("move-mode", "hybrid");
        if (state == State.PURSUE || (state == State.ENGAGE && far)) {
            pathTo(visible || lastKnown == null ? tl : lastKnown, dist > 10);
            return;
        }
        if (state == State.RETREAT && dist > 7) {
            Location g = util.retreatGoal();
            if (g != null) { pathTo(g, true); return; }
        }
        if ("navigator".equals(mode) && state == State.ENGAGE) {            // fallback: strafe by re-targeting the pathfinder
            if (now >= navAt) {
                navAt = now + 6 + rng.nextInt(4);
                Vector away = here.toVector().subtract(tl.toVector()).setY(0).normalize().rotateAroundY(Math.toRadians(25 * strafeDir));
                Location goal = tl.clone().add(away.multiply(pref()));
                nav.getLocalParameters().distanceMargin(0.8);
                nav.setTarget(goal);
            }
            if (now >= strafeUntil) { strafeUntil = now + 15 + rng.nextInt(40); if (rng.nextBoolean()) strafeDir = -strafeDir; }
            return;
        }
        if (nav.isNavigating()) nav.cancelNavigation();

        Vector dir = toT.clone().multiply(1.0 / dist);
        Vector right = new Vector(-dir.getZ(), 0, dir.getX());
        double fwd = 0, side = 0;
        boolean sprint = false;
        if (now >= strafeUntil) {                                           // new strafe segment: varied, never a fixed loop
            strafeUntil = now + 8 + rng.nextInt(36);
            if (rng.nextDouble() < 0.55) strafeDir = -strafeDir;
            strafeStrength = 0.55 + rng.nextDouble() * 0.45;
            if (rng.nextDouble() < 0.10 + (1 - prof.skill) * 0.12) pauseUntil = now + 3 + rng.nextInt(9);   // hesitation
        }
        side = now < pauseUntil ? 0 : strafeDir * strafeStrength;
        Vector forwardDir = dir;
        switch (state) {
            case RETREAT -> { fwd = -1; side = strafeDir * 0.3; sprint = !shieldUp; }
            case HEAL -> { fwd = dist < 7 ? -0.6 : 0; side = strafeDir * 0.2; }
            case HOLD -> { fwd = 0; side = strafeDir * 0.25; }
            default -> {
                double pref = pref();
                boolean ready = now >= nextAttack - 4;
                if (tokenHeld || dist < 4.2) {
                    if (dist > pref + 0.55) {
                        fwd = 1;
                        sprint = dist > pref + 2.2;
                        if (dist > pref + 1.5) {                              // approach from our own angle so teammates spread out
                            double ang = NPCLifePlugin.get().brains().slotAngle(this);
                            Vector goal = new Vector(tl.getX() + Math.cos(ang) * pref, 0, tl.getZ() + Math.sin(ang) * pref);
                            Vector toGoal = goal.subtract(new Vector(here.getX(), 0, here.getZ()));
                            if (toGoal.lengthSquared() > 0.01) forwardDir = dir.clone().multiply(0.35).add(toGoal.normalize().multiply(0.65)).normalize();
                        }
                    } else if (dist < pref - 0.55) fwd = -0.9;
                    else fwd = ready && prof.skill > 0.35 ? 0.35 : 0;
                    if (now < backoffUntil) fwd = -0.85;
                } else {                                                      // waiting for a turn: circle at a distance
                    double ring = 4.6 + (brain.npc().getId() % 5) * 0.4;
                    if (dist > ring + 1.0) { fwd = 0.9; sprint = dist > ring + 3; }
                    else if (dist < ring - 0.8) fwd = -0.7;
                }
                if (dist < 1.6) side = strafeDir;
            }
        }
        if (shieldUp) side *= 0.5;
        if (now < wtapUntil) sprint = false;

        Vector wish = forwardDir.clone().multiply(fwd).add(right.clone().multiply(side));
        if (wish.lengthSquared() > 1) wish.normalize();
        for (Entity a : util.nearbyAllies(1.7)) {                              // never stand inside a teammate
            Vector away = here.toVector().subtract(a.getLocation().toVector()).setY(0);
            if (away.lengthSquared() > 0.0004) wish.add(away.normalize().multiply(0.6));
        }
        wish = util.safeMove(wish);

        double base = sprint ? 0.2806 : 0.2158;
        if (util.healing()) base *= 0.35; else if (shieldUp) base *= 0.65;
        base *= prof.moveScale;
        vel.add(wish.multiply(base).subtract(vel).multiply(0.4));               // momentum: speed ramps up and down
        brain.setManualSprint(sprint && !shieldUp && !util.healing());
        long kb = (long) (8 - 3 * prof.skill);
        if (now - lastHurtTick < kb) return;                                    // let knockback play out, don't cancel it
        Vector v = me.getVelocity();
        if (grounded()) { v.setX(vel.getX()); v.setZ(vel.getZ()); }
        else { v.setX(v.getX() + (vel.getX() - v.getX()) * 0.06); v.setZ(v.getZ() + (vel.getZ() - v.getZ()) * 0.06); }
        me.setVelocity(v);
    }
}
