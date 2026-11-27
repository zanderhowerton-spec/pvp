package dev.npclife;

import net.citizensnpcs.api.ai.Navigator;
import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.EnderPearl;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.WindCharge;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.potion.PotionType;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Utility behaviour for fights: healing, totem/offhand management, cobwebs (place + escape), ender pearls, wind charges,
 * water-bucket clutches, bow shooting and bridging. Every action has a reaction delay, a cooldown, and checks whether it
 * actually makes sense before it happens.
 */
final class CombatUtil {
    private static final class GroundInfo {
        final Block ground; final double dist; final boolean liquid, lava;
        GroundInfo(Block ground, double dist, boolean liquid, boolean lava) { this.ground = ground; this.dist = dist; this.liquid = liquid; this.lava = lava; }
    }

    private final Combat c;

    // healing
    private boolean healing, healUseOn, healMeta;
    private long healStart, healEnd, healCd;
    private int healHeldSlot, healBefore;
    private ItemStack healItem;
    // clutch / falling
    private boolean falling, clutchIntent, clutchDecided, windCushion, placedWater;
    private double fallStartY;
    private Block waterBlock;
    private long pickupAt, clutchReadyAt;
    // web
    private long webReact, webBusyUntil, webCd;
    // throwing
    private long pearlCd, windCd, throwUntil;
    // offhand
    private long offhandNext, lastOffhandSwap;
    // bow
    private boolean drawing;
    private long drawStart, nextShot;
    private int drawTicks;
    // bridging
    boolean bridging;
    private Vector bridgeDir;
    private Location bridgeGoal;
    private long bridgeNext, bridgeStartAt, reachCheckAt, bridgeReachAt;
    private int bridgePlaced;
    private final Set<Block> placed = new HashSet<>();
    private boolean reachable = true;

    CombatUtil(Combat c) { this.c = c; }

    private static NPCLifePlugin plugin() { return NPCLifePlugin.get(); }
    private PlayerInventory inv() { return c.me.getInventory(); }
    private void later(long ticks, Runnable r) { Bukkit.getScheduler().runTaskLater(plugin(), r, Math.max(1, ticks)); }
    private double hpFrac() { return c.me.getHealth() / Math.max(1.0, c.me.getMaxHealth()); }
    private static float yawOf(Vector d) { return (float) Math.toDegrees(Math.atan2(-d.getX(), d.getZ())); }
    private static float pitchOf(Vector d) { return (float) Math.toDegrees(-Math.asin(Math.max(-1, Math.min(1, d.getY())))); }

    private void sound(Block b) {
        b.getWorld().playSound(b.getLocation().add(0.5, 0.5, 0.5), b.getBlockData().getSoundGroup().getPlaceSound(), 1f, 0.9f);
    }

    // ------------------------------------------------------------------ top-level hooks used by Combat
    void passive() { fallTick(); webTick(); offhandTick(); }

    boolean busy() {
        return (falling && (clutchIntent || windCushion)) || pickupAt > 0 || webReact > 0 || webBusyUntil > c.now || healing || bridging;
    }

    boolean usingItem() { return healing || drawing; }
    boolean healing() { return healing; }
    boolean throwing() { return c.now < throwUntil; }

    void tick() { healTick(); }

    void think(double dist, double frac, double tFrac, boolean targetTrapped) {
        long now = c.now;
        if (c.state == Combat.State.HEAL && !healing && now >= healCd) startHeal();
        if (healing || throwing()) return;
        tryWeb(dist, frac);
        tryWind(dist, frac);
        tryPearl(dist, frac);
    }

    void reset() {
        healing = false; drawing = false; bridging = false; falling = false; clutchIntent = false; windCushion = false;
        webReact = 0; placed.clear();
        try { AnimUtil.setUsingHeldItem(c.brain.npc(), false); } catch (Throwable ignored) { /* npc may be gone */ }
    }

    void stopUse() {
        if (healing) healing = false;
        cancelDraw();
        if (bridging) { bridging = false; c.brain.setManualSneak(false); }
        AnimUtil.setUsingHeldItem(c.brain.npc(), false);
    }

    int alliesNear() { return plugin().brains().alliesNear(c, 15).size(); }
    List<Entity> nearbyAllies(double r) { return plugin().brains().alliesNear(c, r); }

    // ------------------------------------------------------------------ healing
    private static double healValue(ItemStack it) {
        if (it == null) return 0;
        switch (it.getType()) {
            case GOLDEN_APPLE: return 4;
            case ENCHANTED_GOLDEN_APPLE: return 8;
            case POTION: {
                if (!(it.getItemMeta() instanceof PotionMeta pm)) return 0;
                PotionType pt = pm.getBasePotionType();
                if (pt == null) return 0;
                String n = pt.name();
                return n.equals("STRONG_HEALING") ? 8 : n.equals("HEALING") ? 4 : 0;
            }
            default: return 0;
        }
    }

    private int healSlot() {
        PlayerInventory inv = inv();
        int pot = Gear.findSlot(inv, it -> it.getType() == Material.POTION && healValue(it) > 0);
        int gap = Gear.findSlot(inv, Material.GOLDEN_APPLE);
        int egap = Gear.findSlot(inv, Material.ENCHANTED_GOLDEN_APPLE);
        int[] order = hpFrac() < 0.3 ? new int[]{egap, gap, pot} : new int[]{pot, gap, egap};
        for (int s : order) if (s >= 0) return s;
        return -1;
    }

    boolean hasHealing() { return healSlot() >= 0; }

    boolean healSafe(double dist) { return !(dist < 3.2 && c.now - c.lastHurtTick < 8); }

    private void startHeal() {
        PlayerInventory inv = inv();
        int s = healSlot();
        if (s < 0) return;
        Gear.hold(inv, s);
        healHeldSlot = inv.getHeldItemSlot();
        healItem = inv.getItemInMainHand().clone();
        healBefore = inv.getItemInMainHand().getAmount();
        healing = true;
        healUseOn = false;
        healStart = c.now + 3 + c.prof.reactionTicks / 3 + c.rng.nextInt(4);
        healEnd = healStart + 32;
        c.itemSwapUntil = healEnd + 2;
    }

    private void healTick() {
        if (!healing) return;
        long now = c.now;
        if (!healUseOn && now >= healStart) {
            healUseOn = true;
            healMeta = AnimUtil.setUsingHeldItem(c.brain.npc(), true);
        }
        if (healUseOn && !healMeta && now % 12 == 0) AnimUtil.play(c.me, "START_USE_MAINHAND_ITEM");
        if (c.target != null && Combat.dist(c.me, c.target) < 3.2 && now - c.lastHurtTick < 6 && hpFrac() > 0.2) { abortHeal(); return; }
        if (now >= healEnd) finishHeal();
    }

    void onHurtWhileHealing(double dmg) { if (dmg >= 4 && hpFrac() > 0.25) abortHeal(); }

    private void abortHeal() {
        AnimUtil.setUsingHeldItem(c.brain.npc(), false);
        AnimUtil.play(c.me, "STOP_USE_ITEM");
        healing = false;
        healCd = c.now + 40;
        c.restoreWeaponLater(6);
    }

    private void finishHeal() {
        AnimUtil.setUsingHeldItem(c.brain.npc(), false);
        AnimUtil.play(c.me, "STOP_USE_ITEM");
        PlayerInventory inv = inv();
        ItemStack cur = inv.getItem(healHeldSlot);
        boolean consumed = Gear.isAir(cur) || cur.getType() != healItem.getType() || cur.getAmount() < healBefore;   // vanilla already ate it?
        if (!consumed) { applyHeal(healItem); Gear.consume(inv, healHeldSlot, 1); }
        healing = false;
        healCd = c.now + 60 + c.rng.nextInt(40);
        c.restoreWeaponLater(8 + c.rng.nextInt(6));
    }

    private void applyHeal(ItemStack it) {
        Player p = c.me;
        switch (it.getType()) {
            case GOLDEN_APPLE -> { eff(p, "regeneration", 100, 1); eff(p, "absorption", 2400, 0); }
            case ENCHANTED_GOLDEN_APPLE -> { eff(p, "regeneration", 400, 1); eff(p, "absorption", 2400, 3); eff(p, "resistance", 6000, 0); eff(p, "fire_resistance", 6000, 0); }
            case POTION -> {
                if (it.getItemMeta() instanceof PotionMeta pm && pm.getBasePotionType() != null)
                    for (PotionEffect pe : pm.getBasePotionType().getPotionEffects()) p.addPotionEffect(pe);
            }
            default -> { }
        }
    }

    private static void eff(Player p, String key, int ticks, int amp) {
        PotionEffectType t = PotionEffectType.getByKey(NamespacedKey.minecraft(key));
        if (t != null) p.addPotionEffect(new PotionEffect(t, ticks, amp));
    }

    // ------------------------------------------------------------------ totem / offhand
    void afterPop() { offhandNext = c.now + c.prof.reactionTicks; lastOffhandSwap = c.now - 100; }

    private void offhandTick() {
        long now = c.now;
        if (now < offhandNext || usingItem()) return;
        offhandNext = now + 10 + c.rng.nextInt(6);
        PlayerInventory inv = inv();
        ItemStack off = inv.getItemInOffHand();
        boolean haveTotem = off.getType() == Material.TOTEM_OF_UNDYING || Gear.findSlot(inv, Material.TOTEM_OF_UNDYING) >= 0;
        boolean haveShield = off.getType() == Material.SHIELD || Gear.findSlot(inv, Material.SHIELD) >= 0;
        if (!haveTotem && !haveShield) return;
        double frac = hpFrac();
        boolean fighting = c.target != null;
        boolean wantTotem = haveTotem && (!haveShield || frac <= 0.6 || !fighting || ("tactical".equals(c.prof.style) && frac < 0.85));
        final Material want = wantTotem ? Material.TOTEM_OF_UNDYING : Material.SHIELD;
        if (off.getType() == want || now < lastOffhandSwap + 40) return;
        final int slot = Gear.findSlot(inv, want);
        if (slot < 0) return;
        lastOffhandSwap = now;
        if (c.rng.nextDouble() < c.prof.mistake * 0.5) return;                    // forgot / got it wrong this time, tries again later
        if (want != Material.SHIELD) c.lowerShield();
        later(c.prof.reactionTicks + c.rng.nextInt(8), () -> {            // swapping takes a human moment, never instant
            if (!c.me.isValid()) return;
            PlayerInventory i2 = c.me.getInventory();
            ItemStack a = i2.getItem(slot);
            if (Gear.isAir(a) || a.getType() != want) return;
            ItemStack old = i2.getItemInOffHand();
            i2.setItem(slot, Gear.isAir(old) ? null : old);
            i2.setItemInOffHand(a);
        });
    }

    // ------------------------------------------------------------------ falling: water bucket clutch / wind cushion
    private GroundInfo scanGround(Player p, int max) {
        Location l = p.getLocation();
        World w = l.getWorld();
        int x = l.getBlockX(), z = l.getBlockZ(), y0 = (int) Math.floor(l.getY());
        if (!w.isChunkLoaded(x >> 4, z >> 4)) return null;
        for (int i = 0; i <= max; i++) {
            int y = y0 - i;
            if (y < w.getMinHeight()) return null;
            Block b = w.getBlockAt(x, y, z);
            Material m = b.getType();
            double d = l.getY() - (y + 1);
            if (m == Material.LAVA) return new GroundInfo(b, d, false, true);
            if (b.isLiquid() || m == Material.COBWEB || m == Material.SLIME_BLOCK) return new GroundInfo(b, d, true, false);
            if (m.isSolid()) return new GroundInfo(b, d, false, false);
        }
        return null;
    }

    private void fallTick() {
        Player p = c.me;
        long now = c.now;
        if (pickupAt > 0 && now >= pickupAt) pickupWater();
        boolean ground = p.isOnGround() || p.isInWater() || p.isClimbing();
        if (ground) {
            if (falling) {
                falling = false; clutchIntent = false; windCushion = false; clutchDecided = false;
                if (placedWater && pickupAt == 0) pickupAt = now + 6 + c.rng.nextInt(8);
            }
            return;
        }
        Vector v = p.getVelocity();
        if (!falling) {
            if (v.getY() < -0.35) { falling = true; fallStartY = p.getLocation().getY(); clutchDecided = false; clutchIntent = false; }
            else return;
        }
        if (v.getY() > 0.05) { falling = false; return; }                        // launched upward again
        double fallen = fallStartY - p.getLocation().getY();
        GroundInfo g = scanGround(p, 70);
        if (!clutchDecided && fallen >= 4) {
            clutchDecided = true;
            double expected = fallen + (g == null ? 99 : g.dist);
            boolean dangerous = expected >= 10 || (expected - 3) >= p.getHealth() * 0.6;
            if (dangerous && g != null && !g.liquid && !g.lava) {
                double attempt = Math.max(0.45, Math.min(0.97, Combat.cfg("clutch-chance", 0.8) + (c.prof.skill - 0.5) * 0.2));
                PlayerInventory inv = inv();
                if (Gear.findSlot(inv, Material.WATER_BUCKET) >= 0 && c.rng.nextDouble() < attempt * (1 - c.prof.mistake * 0.5)) {
                    clutchIntent = true;
                    Gear.holdMaterial(inv, Material.WATER_BUCKET);                // equip early: it takes a moment
                    clutchReadyAt = now + c.prof.reactionTicks / 2;
                } else if (Gear.findSlot(inv, Material.WIND_CHARGE) >= 0 && c.prof.skill > 0.35 && c.rng.nextDouble() < 0.5) windCushion = true;
            }
        }
        if (clutchIntent && !placedWater && g != null && now >= clutchReadyAt) {
            if (g.dist <= 4.3 && c.rng.nextDouble() < 0.30 + 0.65 * c.prof.skill) placeWater(g);   // the less skilled may mistime it
            if (g.dist < 0.7 && !placedWater) clutchIntent = false;                              // too late
        }
        if (windCushion && g != null && g.dist <= 3.0 && now >= windCd) {
            windCushion = false;
            launchWind(new Vector(p.getLocation().getX(), g.ground.getY() + 1.0, p.getLocation().getZ()));
        }
        if (placedWater && waterBlock != null && (p.isInWater() || Math.abs(p.getLocation().getY() - waterBlock.getY()) < 1.2)) p.setFallDistance(0f);
    }

    private void placeWater(GroundInfo g) {
        Block above = g.ground.getRelative(BlockFace.UP);
        if (!(above.getType().isAir() || above.isPassable())) return;
        PlayerInventory inv = inv();
        int s = Gear.findSlot(inv, Material.WATER_BUCKET);
        if (s < 0) return;
        above.setType(Material.WATER);
        inv.setItem(s, new ItemStack(Material.BUCKET));
        placedWater = true;
        waterBlock = above;
        c.me.swingMainHand();
        above.getWorld().playSound(above.getLocation().add(0.5, 0.5, 0.5), "item.bucket.empty", SoundCategory.BLOCKS, 1f, 1f);
    }

    private void pickupWater() {
        if (waterBlock != null && waterBlock.getType() == Material.WATER) {
            waterBlock.setType(Material.AIR);
            waterBlock.getWorld().playSound(waterBlock.getLocation().add(0.5, 0.5, 0.5), "item.bucket.fill", SoundCategory.BLOCKS, 1f, 1f);
        }
        PlayerInventory inv = inv();
        int s = Gear.findSlot(inv, Material.BUCKET);
        if (s >= 0) inv.setItem(s, new ItemStack(Material.WATER_BUCKET));
        placedWater = false;
        pickupAt = 0;
        waterBlock = null;
        if (c.target != null) c.restoreWeaponLater(3);
    }

    // ------------------------------------------------------------------ cobwebs
    static boolean inWeb(Entity e) {
        Block f = e.getLocation().getBlock();
        return f.getType() == Material.COBWEB || f.getRelative(BlockFace.UP).getType() == Material.COBWEB;
    }

    private static Block webBlock(Entity e) {
        Block f = e.getLocation().getBlock();
        if (f.getType() == Material.COBWEB) return f;
        Block u = f.getRelative(BlockFace.UP);
        return u.getType() == Material.COBWEB ? u : null;
    }

    private boolean hasBlade(PlayerInventory inv) {
        return Weapons.bestSlot(inv, Weapons.Kind.SWORD) >= 0 || Weapons.bestSlot(inv, Weapons.Kind.AXE) >= 0 || Gear.findSlot(inv, Material.SHEARS) >= 0;
    }

    private void webTick() {
        long now = c.now;
        if (!inWeb(c.me)) { webReact = 0; return; }
        if (now < webBusyUntil) return;
        if (webReact == 0) { webReact = now + c.prof.reactionTicks + c.rng.nextInt(7); return; }      // takes a moment to notice
        if (now < webReact) return;
        webReact = 0;
        PlayerInventory inv = inv();
        boolean enemyNear = c.target != null && Combat.dist(c.me, c.target) < 9;
        boolean water = Gear.findSlot(inv, Material.WATER_BUCKET) >= 0;
        webBusyUntil = now + 22;
        if (water && (enemyNear || c.prof.skill > 0.5) && c.rng.nextDouble() < 0.5 + c.prof.skill * 0.45) escapeWithWater();
        else if (hasBlade(inv)) cutWeb();
        // otherwise it just walks out of the web, slowly
    }

    private void escapeWithWater() {
        final Player p = c.me;
        final PlayerInventory inv = p.getInventory();
        final Block web = webBlock(p);
        if (web == null) return;
        if (!Gear.holdMaterial(inv, Material.WATER_BUCKET)) return;
        later(3 + c.rng.nextInt(4), () -> {
            if (!p.isValid() || web.getType() != Material.COBWEB) return;
            web.setType(Material.WATER);                                          // flowing water destroys cobwebs
            int s2 = Gear.findSlot(inv, Material.WATER_BUCKET);
            if (s2 >= 0) inv.setItem(s2, new ItemStack(Material.BUCKET));
            p.swingMainHand();
            web.getWorld().playSound(web.getLocation().add(0.5, 0.5, 0.5), "item.bucket.empty", SoundCategory.BLOCKS, 1f, 1f);
            later(14, () -> {
                if (web.getType() == Material.WATER) web.setType(Material.AIR);
                int b = Gear.findSlot(inv, Material.BUCKET);
                if (b >= 0) inv.setItem(b, new ItemStack(Material.WATER_BUCKET));
                c.restoreWeaponLater(2);
            });
        });
    }

    private void cutWeb() {
        final Block web = webBlock(c.me);
        if (web == null) return;
        later(8 + c.rng.nextInt(5), () -> {
            if (web.getType() == Material.COBWEB) { web.setType(Material.AIR); c.me.swingMainHand(); }
            Block up = web.getRelative(BlockFace.UP);
            if (up.getType() == Material.COBWEB) up.setType(Material.AIR);
        });
    }

    private void tryWeb(double dist, double frac) {
        long now = c.now;
        if (now < webCd || webBusyUntil > now || c.target == null || !c.prof.has(0.3)) return;
        PlayerInventory inv = inv();
        int slot = Gear.findSlot(inv, Material.COBWEB);
        if (slot < 0 || dist < 2.6 || dist > 5.8 || inWeb(c.target) || !c.visible) return;
        boolean retreating = c.state == Combat.State.RETREAT || c.state == Combat.State.HEAL;
        boolean chasing = c.state == Combat.State.PURSUE;
        double p = c.prof.utility * (retreating ? 0.5 : chasing ? 0.35 : 0.06) * (0.5 + 0.5 * c.prof.skill);
        if (c.rng.nextDouble() >= p) return;
        final Block b = c.target.getLocation().getBlock();
        if (!b.getType().isAir() || c.me.getLocation().getBlock().equals(b)) return;      // never trap itself
        webCd = now + 160 + c.rng.nextInt(120);
        Gear.hold(inv, slot);
        c.itemSwapUntil = now + 14;
        later(3 + c.prof.reactionTicks / 3 + c.rng.nextInt(3), () -> {
            if (!c.me.isValid() || !b.getType().isAir()) return;
            b.setType(Material.COBWEB);
            Gear.consumeMaterial(c.me.getInventory(), Material.COBWEB);
            c.me.swingMainHand();
            sound(b);
            Cleanup.track(b, (long) (Combat.cfg("cleanup-seconds", 60) * 20));
            c.restoreWeaponLater(6);
        });
    }

    // ------------------------------------------------------------------ wind charges
    private void tryWind(double dist, double frac) {
        long now = c.now;
        PlayerInventory inv = inv();
        if (now < windCd || c.target == null || !c.prof.has(0.4) || c.me.getCooldown(Material.WIND_CHARGE) > 0 || Gear.findSlot(inv, Material.WIND_CHARGE) < 0) return;
        double u = c.prof.utility * (0.5 + 0.5 * c.prof.skill);
        Vector me = c.me.getLocation().toVector(), enemy = c.target.getLocation().toVector();
        Vector toE = enemy.clone().subtract(me).setY(0);
        if (toE.lengthSquared() < 0.01) return;
        toE.normalize();
        Combat.State st = c.state;
        if ((st == Combat.State.RETREAT || st == Combat.State.HEAL) && dist < 4.5 && c.rng.nextDouble() < u * 0.55) {
            // defensive: explode between us - the blast throws us backwards and up
            launchWind(new Vector(me.getX() + toE.getX() * 1.1, me.getY() - 0.1, me.getZ() + toE.getZ() * 1.1));
        } else if (st == Combat.State.ENGAGE && dist < 5.5 && edgeNear(c.target, toE) && c.rng.nextDouble() < u * 0.25) {
            launchWind(new Vector(enemy.getX(), enemy.getY(), enemy.getZ()));      // offensive: knock them off an edge
        } else if (enemy.getY() - me.getY() > 3.5 && enemy.getY() - me.getY() < 9 && dist < 9 && !reachable && c.rng.nextDouble() < u * 0.4) {
            launchWind(new Vector(me.getX() + toE.getX() * 0.5, me.getY() - 0.1, me.getZ() + toE.getZ() * 0.5));   // reposition: get up onto a ledge
        }
    }

    private boolean edgeNear(Entity t, Vector away) {
        Location l = t.getLocation();
        World w = l.getWorld();
        for (int i = 1; i <= 2; i++) {
            int x = (int) Math.floor(l.getX() + away.getX() * i * 1.2), z = (int) Math.floor(l.getZ() + away.getZ() * i * 1.2);
            if (!w.isChunkLoaded(x >> 4, z >> 4)) return false;
            boolean ground = false;
            for (int dy = 1; dy <= 4 && !ground; dy++) ground = w.getBlockAt(x, l.getBlockY() - dy, z).getType().isSolid();
            if (!ground) return true;
        }
        return false;
    }

    private void launchWind(Vector point) {
        PlayerInventory inv = inv();
        int slot = Gear.findSlot(inv, Material.WIND_CHARGE);
        if (slot < 0) return;
        windCd = c.now + 30 + c.rng.nextInt(20);
        throwUntil = c.now + 14;
        Gear.hold(inv, slot);
        later(2 + c.prof.reactionTicks / 4 + c.rng.nextInt(3), () -> {
            if (!c.me.isValid()) return;
            Vector dir = point.clone().subtract(c.me.getEyeLocation().toVector());
            if (dir.lengthSquared() < 0.01) return;
            dir.normalize();
            c.forceAim(yawOf(dir), pitchOf(dir), 3);
            c.me.launchProjectile(WindCharge.class, dir.clone().multiply(1.5));
            Gear.consume(c.me.getInventory(), c.me.getInventory().getHeldItemSlot(), 1);
            c.me.setCooldown(Material.WIND_CHARGE, 10);
            c.me.swingMainHand();
            c.restoreWeaponLater(8);
        });
    }

    /** Fallback when explosion knockback doesn't move a server-side NPC: give the launch ourselves. */
    void windHit(Location at) {
        final Player p = c.me;
        if (p == null) return;
        if (falling) p.setFallDistance(0f);
        later(2, () -> {
            if (!p.isValid() || p.getWorld() != at.getWorld() || p.getLocation().distance(at) > 3.0) return;
            Vector v = p.getVelocity();
            if (v.length() > 0.45) return;                                         // vanilla already launched us
            Vector away = p.getLocation().toVector().subtract(at.toVector()).setY(0);
            if (away.lengthSquared() < 0.01) away = new Vector(0, 0, 0); else away.normalize().multiply(0.7);
            away.setY(0.75);
            p.setVelocity(v.add(away));
            if (falling) p.setFallDistance(0f);
        });
    }

    // ------------------------------------------------------------------ ender pearls
    private Location safeSpot(World w, double x, double z, int y0) {
        int bx = (int) Math.floor(x), bz = (int) Math.floor(z);
        if (!w.isChunkLoaded(bx >> 4, bz >> 4)) return null;
        for (int dy = 3; dy >= -8; dy--) {
            int y = y0 + dy;
            Block floor = w.getBlockAt(bx, y - 1, bz), feet = floor.getRelative(BlockFace.UP), head = feet.getRelative(BlockFace.UP);
            Material fm = floor.getType();
            if (!fm.isSolid() || fm == Material.MAGMA_BLOCK || fm == Material.CACTUS) continue;
            if (!feet.isPassable() || feet.isLiquid() || !head.isPassable() || head.isLiquid()) continue;
            return new Location(w, bx + 0.5, y, bz + 0.5);
        }
        return null;
    }

    private Location landingNear(Location goal) {
        Vector back = c.me.getLocation().toVector().subtract(goal.toVector()).setY(0);
        if (back.lengthSquared() < 0.01) return null;
        back.normalize().multiply(2.0);
        return safeSpot(goal.getWorld(), goal.getX() + back.getX(), goal.getZ() + back.getZ(), goal.getBlockY());
    }

    private Location escapeSpot() {
        Location here = c.me.getLocation();
        Vector away = here.toVector().subtract(c.target.getLocation().toVector()).setY(0);
        if (away.lengthSquared() < 0.01) return null;
        away.normalize();
        for (int k = 0; k < 6; k++) {
            Vector d = away.clone().rotateAroundY((k - 2.5) * 0.35);
            double dist = 14 + c.rng.nextInt(9);
            Location spot = safeSpot(here.getWorld(), here.getX() + d.getX() * dist, here.getZ() + d.getZ() * dist, here.getBlockY());
            if (spot == null) continue;
            Vector dir = spot.toVector().add(new Vector(0, 1, 0)).subtract(c.me.getEyeLocation().toVector());
            if (here.getWorld().rayTraceBlocks(c.me.getEyeLocation(), dir.clone().normalize(), dir.length(), FluidCollisionMode.NEVER, true) == null) return spot;
        }
        return null;
    }

    private void tryPearl(double dist, double frac) {
        long now = c.now;
        PlayerInventory inv = inv();
        if (now < pearlCd || c.target == null || !c.prof.has(0.35) || c.me.getCooldown(Material.ENDER_PEARL) > 0 || Gear.findSlot(inv, Material.ENDER_PEARL) < 0) return;
        double hp = c.me.getHealth(), u = c.prof.utility * (0.5 + 0.5 * c.prof.skill), dmg = Combat.cfg("pearl-damage", 5);
        if (c.state == Combat.State.PURSUE && dist > Combat.cfg("pearl-min-distance", 14) && dist < 45 && c.targetMovingAway()
                && hp > dmg + 4 && c.rng.nextDouble() < u * 0.5) {                                      // keep up the pressure on someone running away
            Location spot = landingNear(c.target.getLocation());
            if (spot != null) throwPearl(spot);
        } else if (c.state == Combat.State.RETREAT && frac < 0.35 && dist < 5 && hp > dmg + 1.5 && c.rng.nextDouble() < u * 0.45) {   // pearl out of trouble
            Location spot = escapeSpot();
            if (spot != null) throwPearl(spot);
        }
    }

    private boolean tryPearlTo(Location goal) {
        long now = c.now;
        PlayerInventory inv = inv();
        if (now < pearlCd || !c.prof.has(0.35) || c.state != Combat.State.PURSUE || Gear.findSlot(inv, Material.ENDER_PEARL) < 0) return false;
        double u = c.prof.utility * (0.5 + 0.5 * c.prof.skill);
        if (c.me.getHealth() <= Combat.cfg("pearl-damage", 5) + 4 || c.rng.nextDouble() >= u * 0.6) return false;
        Location spot = landingNear(goal);
        if (spot == null) return false;
        throwPearl(spot);
        return true;
    }

    private void throwPearl(Location spot) {
        PlayerInventory inv = inv();
        int slot = Gear.findSlot(inv, Material.ENDER_PEARL);
        if (slot < 0) return;
        pearlCd = c.now + 120 + c.rng.nextInt(60);
        throwUntil = c.now + 18;
        Gear.hold(inv, slot);
        later(2 + c.prof.reactionTicks / 3 + c.rng.nextInt(3), () -> {
            if (!c.me.isValid()) return;
            Vector eye = c.me.getEyeLocation().toVector();
            Vector dir = Ballistics.solve(eye, spot.toVector().add(new Vector(0, 0.3, 0)), 1.5, 0.03, 0.99);
            if (dir == null) { c.restoreWeaponLater(2); return; }
            double err = (1 - c.prof.skill) * 0.035 + (c.rng.nextDouble() < c.prof.mistake ? 0.12 : 0);   // now and then a bad throw
            dir.add(new Vector((c.rng.nextDouble() - 0.5) * err, (c.rng.nextDouble() - 0.5) * err, (c.rng.nextDouble() - 0.5) * err)).normalize();
            c.forceAim(yawOf(dir), pitchOf(dir), 3);
            c.me.launchProjectile(EnderPearl.class, dir.clone().multiply(1.5));
            Gear.consume(c.me.getInventory(), c.me.getInventory().getHeldItemSlot(), 1);
            c.me.setCooldown(Material.ENDER_PEARL, 20);
            c.me.swingMainHand();
            c.restoreWeaponLater(12);
        });
    }

    /** Fallback: a server-side NPC may not get teleported by vanilla pearl code, so do it (with the 5 damage) if needed. */
    void pearlLanded(Location at, Projectile pearl) {
        final Player p = c.me;
        if (p == null) return;
        final Location dest = at.clone().add(0, 0.1, 0);
        later(1, () -> {
            if (!p.isValid()) return;
            if (p.getWorld() == dest.getWorld() && p.getLocation().distance(dest) < 4.5) return;     // vanilla already teleported us
            Location safe = dest.clone();
            for (int i = 0; i < 3 && !safe.getBlock().isPassable(); i++) safe.add(0, 1, 0);
            safe.setYaw(p.getLocation().getYaw());
            safe.setPitch(p.getLocation().getPitch());
            c.brain.npc().teleport(safe, PlayerTeleportEvent.TeleportCause.ENDER_PEARL);
            p.damage(Combat.cfg("pearl-damage", 5));
            p.getWorld().playSound(safe, "entity.player.teleport", SoundCategory.PLAYERS, 1f, 1f);
            if (pearl.isValid()) pearl.remove();
        });
    }

    // ------------------------------------------------------------------ bow
    void ranged() {
        Player p = c.me;
        long now = c.now;
        PlayerInventory inv = p.getInventory();
        if (c.target == null || Gear.count(inv, Weapons::isArrow) == 0) { cancelDraw(); return; }
        double dist = Combat.dist(p, c.target);
        if (dist < 5.0 || !c.visible) { cancelDraw(); return; }                     // too close: weapon choice goes back to melee
        if (!drawing) {
            if (now < nextShot) return;
            drawing = true;
            drawStart = now;
            drawTicks = 16 + c.rng.nextInt(7);
            if (!AnimUtil.setUsingHeldItem(c.brain.npc(), true)) AnimUtil.play(p, "START_USE_MAINHAND_ITEM");
            return;
        }
        if (now - drawStart >= drawTicks) shoot();
    }

    private void cancelDraw() {
        if (!drawing) return;
        drawing = false;
        AnimUtil.setUsingHeldItem(c.brain.npc(), false);
        AnimUtil.play(c.me, "STOP_USE_ITEM");
    }

    private void shoot() {
        Player p = c.me;
        long now = c.now;
        double power = Math.min(1.0, (now - drawStart) / 20.0), speed = 3.0 * power;
        drawing = false;
        AnimUtil.setUsingHeldItem(c.brain.npc(), false);
        AnimUtil.play(p, "STOP_USE_ITEM");
        Vector eye = p.getEyeLocation().toVector();
        double flight = Combat.dist(p, c.target) / Math.max(0.5, speed * 0.85);
        Vector lead = c.targetVel().multiply(flight * (0.4 + 0.6 * c.prof.skill));
        Vector aim = c.target.getLocation().toVector().add(new Vector(0, c.target.getHeight() * 0.65, 0)).add(lead);
        Vector dir = Ballistics.solve(eye, aim, speed, 0.05, 0.99);
        nextShot = now + 14 + c.rng.nextInt(16) + (int) ((1 - c.prof.skill) * 14);
        if (dir == null) return;
        double spread = (1 - c.prof.skill) * 0.05 + 0.008;
        dir.add(new Vector((c.rng.nextDouble() - 0.5) * spread, (c.rng.nextDouble() - 0.5) * spread, (c.rng.nextDouble() - 0.5) * spread)).normalize();
        Arrow a = p.launchProjectile(Arrow.class, dir.multiply(speed));
        a.setCritical(power >= 0.99);
        a.setPickupStatus(AbstractArrow.PickupStatus.DISALLOWED);
        int s = Gear.findSlot(p.getInventory(), Weapons::isArrow);
        if (s >= 0) Gear.consume(p.getInventory(), s, 1);
    }

    // ------------------------------------------------------------------ bridging
    boolean tryBridgeOrPearl(Location goal) {
        long now = c.now;
        if (bridging) { bridgeTick(); return true; }
        if (now >= reachCheckAt) { reachCheckAt = now + 30 + c.rng.nextInt(20); reachable = c.brain.canReach(goal); }
        if (reachable) return false;
        PlayerInventory inv = inv();
        double dist = c.me.getLocation().distance(goal);
        boolean riskOk = hpFrac() > c.prof.retreatAt + 0.15 && now - c.lastHurtTick > 40;
        if (c.prof.has(0.3) && Gear.count(inv, Gear::placeable) >= 6 && riskOk && dist < 40 && c.rng.nextDouble() < 0.35 + 0.5 * c.prof.skill) {
            Location edge = findGapEdge(c.me.getLocation(), goal);
            if (edge != null) {
                if (c.me.getLocation().distance(edge) > 1.8) {
                    Navigator nav = c.brain.navigator();
                    nav.getLocalParameters().distanceMargin(0.8);
                    nav.setTarget(edge);
                    return true;
                }
                startBridge(goal);
                return true;
            }
        }
        return tryPearlTo(goal);
    }

    private Location groundNear(World w, int bx, int bz, int y0) {
        int[] order = {0, -1, -2, -3, 1};
        for (int dy : order) {
            int y = y0 + dy;
            Block floor = w.getBlockAt(bx, y - 1, bz), feet = floor.getRelative(BlockFace.UP), head = feet.getRelative(BlockFace.UP);
            if (floor.getType().isSolid() && feet.isPassable() && head.isPassable()) return new Location(w, bx + 0.5, y, bz + 0.5);
        }
        return null;
    }

    /** Walks the straight line to the goal: where does ground stop, and does it resume on the other side? */
    private Location findGapEdge(Location from, Location goal) {
        World w = from.getWorld();
        Vector d = goal.toVector().subtract(from.toVector()).setY(0);
        double len = d.length();
        if (len < 3) return null;
        d.normalize();
        Location lastGround = null, edge = null;
        for (double s = 0; s <= Math.min(len, 40); s += 1.0) {
            int bx = (int) Math.floor(from.getX() + d.getX() * s), bz = (int) Math.floor(from.getZ() + d.getZ() * s);
            if (!w.isChunkLoaded(bx >> 4, bz >> 4)) return null;
            Location g = groundNear(w, bx, bz, from.getBlockY());
            if (g != null) {
                if (edge != null) return edge;                                          // ground resumes after the gap: bridgeable
                lastGround = g;
            } else if (edge == null && lastGround != null) edge = lastGround;
        }
        return null;
    }

    private void startBridge(Location goal) {
        Vector d = goal.toVector().subtract(c.me.getLocation().toVector()).setY(0);
        bridgeDir = Math.abs(d.getX()) > Math.abs(d.getZ()) ? new Vector(Math.signum(d.getX()), 0, 0) : new Vector(0, 0, Math.signum(d.getZ()));
        bridgeGoal = goal.clone();
        bridging = true;
        bridgePlaced = 0;
        placed.clear();
        bridgeStartAt = c.now;
        bridgeNext = c.now + 4;
        bridgeReachAt = c.now + 25;
        c.brain.setManualSneak(true);
    }

    private void stopBridge() {
        bridging = false;
        c.brain.setManualSneak(false);
        c.restoreWeaponLater(4);
    }

    void bridgeTick() {
        Player p = c.me;
        long now = c.now;
        PlayerInventory inv = p.getInventory();
        if (c.target == null || now - c.lastHurtTick < 12 || hpFrac() < 0.35 || bridgePlaced >= 48 || Gear.placeableSlot(inv) < 0 || now - bridgeStartAt > 20 * 45) { stopBridge(); return; }
        if (now >= bridgeReachAt) {
            bridgeReachAt = now + 25;
            if (c.brain.canReach(c.target.getLocation())) { stopBridge(); return; }          // a path exists now: stop building
        }
        int dx = (int) Math.signum(bridgeDir.getX()), dz = (int) Math.signum(bridgeDir.getZ());
        Block below = p.getLocation().getBlock().getRelative(BlockFace.DOWN);
        Block ahead = below.getRelative(dx, 0, dz);
        c.forceAim(yawOf(bridgeDir), 78f, 2);                                                  // looks down the bridge like a real bridger
        if (!ahead.getType().isSolid()) {
            if (now >= bridgeNext) {
                int slot = Gear.placeableSlot(inv);
                Gear.hold(inv, slot);
                ItemStack held = inv.getItemInMainHand();
                if (Gear.isAir(held)) return;
                ahead.setType(held.getType());
                Gear.consume(inv, inv.getHeldItemSlot(), 1);
                p.swingMainHand();
                sound(ahead);
                placed.add(ahead);
                Cleanup.track(ahead, (long) (Combat.cfg("cleanup-seconds", 60) * 20));
                bridgePlaced++;
                bridgeNext = now + 5 + c.rng.nextInt(4) + (int) ((1 - c.prof.skill) * 5);
            }
        } else {
            if (bridgePlaced > 0 && !placed.contains(ahead)) { stopBridge(); return; }          // reached natural ground on the other side
            Vector v = p.getVelocity();
            v.setX(dx * 0.1);
            v.setZ(dz * 0.1);
            p.setVelocity(v);
        }
    }

    // ------------------------------------------------------------------ movement helpers
    /** Stops fighting movement from walking into lava, off cliffs, or into walls (jumping 1-block steps). */
    Vector safeMove(Vector wish) {
        if (wish.lengthSquared() < 1e-4) return wish;
        Location here = c.me.getLocation();
        World w = here.getWorld();
        Vector n = wish.clone().normalize();
        int nx = (int) Math.floor(here.getX() + n.getX() * 1.0), nz = (int) Math.floor(here.getZ() + n.getZ() * 1.0), y = here.getBlockY();
        if (!w.isChunkLoaded(nx >> 4, nz >> 4)) return new Vector(0, 0, 0);
        Block feet = w.getBlockAt(nx, y, nz);
        Material fm = feet.getType();
        if (fm == Material.LAVA || fm == Material.FIRE || fm == Material.CACTUS || fm == Material.MAGMA_BLOCK) { c.strafeDir = -c.strafeDir; return new Vector(0, 0, 0); }
        if (!feet.isPassable()) {
            Block up = feet.getRelative(BlockFace.UP);
            if (up.isPassable() && up.getRelative(BlockFace.UP).isPassable() && c.grounded()) c.hop();
            else { c.strafeDir = -c.strafeDir; return new Vector(0, 0, 0); }
        }
        boolean ground = false;
        for (int i = 1; i <= 4 && !ground; i++) {
            Block g = w.getBlockAt(nx, y - i, nz);
            ground = g.getType().isSolid() || g.isLiquid();
        }
        if (!ground && c.rng.nextDouble() > c.prof.mistake * 0.03) { c.strafeDir = -c.strafeDir; return new Vector(0, 0, 0); }   // won't step off a cliff (except by mistake)
        return wish;
    }

    Location retreatGoal() {
        List<Entity> al = plugin().brains().alliesNear(c, 25);
        if (!al.isEmpty()) {
            double x = 0, z = 0;
            for (Entity e : al) { x += e.getLocation().getX(); z += e.getLocation().getZ(); }
            Location any = al.get(0).getLocation();
            return new Location(any.getWorld(), x / al.size(), any.getY(), z / al.size());
        }
        Location home = c.t.center();
        return home != null && home.getWorld() == c.me.getWorld() && home.distance(c.me.getLocation()) < 60 ? home : null;
    }

    void returnHome() {
        Location home = c.t.center();
        if (home == null || c.me == null || home.getWorld() != c.me.getWorld() || home.distance(c.me.getLocation()) > 80) return;
        Navigator nav = c.brain.navigator();
        nav.getLocalParameters().speedModifier(1.0f);
        nav.getLocalParameters().distanceMargin(2.0);
        nav.getLocalParameters().range((float) Math.max(30.0, home.distance(c.me.getLocation()) * 1.5));
        nav.setTarget(home);
    }

    // ------------------------------------------------------------------ attacking
    private static java.lang.reflect.Method attackM;
    private static boolean attackLooked;

    /** Paper's Player#attack does the real thing (weapon damage, enchants, crits, sprint knockback). Reflection keeps compiling if it moves. */
    boolean nativeAttack(Player p, Entity t) {
        if (!attackLooked) {
            attackLooked = true;
            try { attackM = Player.class.getMethod("attack", Entity.class); } catch (Throwable ex) { attackM = null; }
        }
        try {
            if (attackM != null) { attackM.invoke(p, t); return true; }
        } catch (Throwable ex) { attackM = null; }
        if (t instanceof org.bukkit.entity.Damageable d) { d.damage(Weapons.fallbackDamage(p.getInventory().getItemInMainHand()), p); return true; }
        return false;
    }

    static boolean attackAvailable() {
        try { Player.class.getMethod("attack", Entity.class); return true; } catch (Throwable ex) { return false; }
    }
}
