package fr.demonpact;

import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.FluidCollisionMode;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Tameable;
import org.bukkit.entity.Zombie;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/** Exécution des 36 pouvoirs, sécurité (cooldowns, zones, propriétaire) et événements associés. */
public final class PowerManager implements Listener {

    private static final class Frozen {
        long until;
        boolean ai = true;
        boolean gravity = true;
        boolean projectile;
        Vector vel = new Vector();
        String particle = "end_rod";
        int n;
    }

    private final DemonPactPlugin plugin;
    private final Map<PowerType, Power> powers = new EnumMap<>(PowerType.class);
    private final Map<UUID, Long> noFall = new HashMap<>();
    private final Map<UUID, Long> lifesteal = new HashMap<>();
    private final Map<UUID, Frozen> frozen = new HashMap<>();
    private final Map<UUID, UUID> servants = new HashMap<>();
    private final Set<UUID> clones = new HashSet<>();
    private final Set<String> walls = new HashSet<>();
    private final NamespacedKey tempKey;
    private final NamespacedKey ownerKey;
    private final Random rnd = new Random();
    private Material wallMaterial = Material.STONE_BRICKS;
    private BukkitTask ticker;
    private BukkitTask passive;
    private int tickCount;

    public PowerManager(DemonPactPlugin plugin) {
        this.plugin = plugin;
        this.tempKey = new NamespacedKey(plugin, "temp_entity");
        this.ownerKey = new NamespacedKey(plugin, "temp_owner");
    }

    public NamespacedKey tempKey() { return tempKey; }

    public Power power(PowerType t) { return powers.get(t); }

    // ------------------------------------------------------------------ Chargement

    public void load() {
        powers.clear();
        for (PowerType t : PowerType.values()) {
            ConfigurationSection s = plugin.getConfig().getConfigurationSection("powers." + t.name());
            Demon d = plugin.demons().get(t.demon());
            Element el = d == null ? Element.VOID : d.element();
            if (s == null) {
                powers.put(t, new Power(t, t.label(), t.description(), t.cooldown(), t.duration(), t.range(),
                        t.strength(), t.damage(), el.particle, el.sound, true));
            } else {
                powers.put(t, new Power(t, s.getString("name", t.label()), s.getString("description", t.description()),
                        s.getDouble("cooldown", t.cooldown()), s.getDouble("duration", t.duration()),
                        s.getDouble("range", t.range()), s.getDouble("strength", t.strength()),
                        s.getDouble("damage", t.damage()), s.getString("particle", el.particle),
                        s.getString("sound", el.sound), s.getBoolean("enabled", true)));
            }
        }
        Material m = Material.matchMaterial(plugin.getConfig().getString("powers-settings.wall-material", "STONE_BRICKS"));
        wallMaterial = m == null || !m.isBlock() ? Material.STONE_BRICKS : m;
        if (ticker == null) {
            restoreLeftoverWalls();
            ticker = new BukkitRunnable() {
                @Override
                public void run() { tick(); }
            }.runTaskTimer(plugin, 2L, 2L);
            passive = new BukkitRunnable() {
                @Override
                public void run() { passiveTick(); }
            }.runTaskTimer(plugin, 40L, 40L);
        }
    }

    public void shutdown() {
        if (ticker != null) ticker.cancel();
        if (passive != null) passive.cancel();
        ticker = null;
        passive = null;
        for (UUID id : new ArrayList<>(frozen.keySet())) {
            Entity e = Bukkit.getEntity(id);
            release(e, frozen.get(id));
        }
        frozen.clear();
        for (UUID id : new ArrayList<>(servants.keySet())) {
            Entity e = Bukkit.getEntity(id);
            if (e != null) e.remove();
        }
        servants.clear();
        for (UUID id : new ArrayList<>(clones)) {
            Entity e = Bukkit.getEntity(id);
            if (e != null) e.remove();
        }
        clones.clear();
        restoreAllWalls();
    }

    // ------------------------------------------------------------------ Boucles

    private void tick() {
        long now = System.currentTimeMillis();
        tickCount++;
        Iterator<Map.Entry<UUID, Frozen>> it = frozen.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Frozen> en = it.next();
            Frozen f = en.getValue();
            Entity e = Bukkit.getEntity(en.getKey());
            if (e == null || !e.isValid() || now >= f.until) {
                release(e, f);
                it.remove();
                continue;
            }
            e.setVelocity(new Vector(0, 0, 0));
            if (++f.n % 5 == 0) Fx.spawn(e.getLocation().add(0, e.getHeight() + 0.2, 0), f.particle, 3, 0.2, 0.1, 0.2, 0);
        }
        noFall.values().removeIf(t -> t < now);
        lifesteal.values().removeIf(t -> t < now);
        if (tickCount % 5 == 0) servantAi();
    }

    private void release(Entity e, Frozen f) {
        if (e == null || f == null) return;
        if (f.projectile) {
            e.setGravity(f.gravity);
            e.setVelocity(f.vel);
        } else if (e instanceof Mob m) {
            m.setAI(f.ai);
        } else if (e instanceof Player p) {
            p.setFallDistance(0);
        }
    }

    private void passiveTick() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            for (Pact pact : plugin.pacts().pactsOf(p.getUniqueId())) {
                if (pact.power() == PowerType.WATER_BREATHING && power(pact.power()).enabled()
                        && !plugin.regionGuard().isDisabled(p.getLocation())) {
                    p.addPotionEffect(new PotionEffect(PotionEffectType.WATER_BREATHING, 400, 0, false, false, true));
                }
            }
        }
    }

    // ------------------------------------------------------------------ Utilisation

    public void use(Player p, Pact pact) {
        Power pw = powers.get(pact.power());
        Demon d = plugin.demons().get(pact.demonId());
        String col = d == null ? "&5" : d.color();
        if (pw == null || !pw.enabled()) {
            p.sendActionBar(Txt.c("&cCe pouvoir est désactivé."));
            return;
        }
        if (plugin.regionGuard().isDisabled(p.getLocation())) {
            p.sendActionBar(Txt.c("&cLes pouvoirs démoniaques sont inertes dans cette zone."));
            return;
        }
        if (frozen.containsKey(p.getUniqueId())) return;
        if (pw.type().passive()) {
            p.sendActionBar(Txt.c(col + pw.name() + " &7: pouvoir passif, toujours actif."));
            return;
        }
        CooldownManager cd = plugin.cooldowns();
        if (cd.spam(p.getUniqueId(), plugin.getConfig().getLong("powers-settings.anti-spam-ms", 500))) return;
        String key = pact.power().name();
        long rem = cd.remaining(p.getUniqueId(), key);
        if (rem > 0) {
            p.sendActionBar(Txt.c("&c" + pw.name() + " &7: recharge &f" + String.format(Locale.ROOT, "%.1f", rem / 1000.0) + "s"));
            Fx.sound(p.getLocation(), "block.note_block.bass", 0.5f, 0.6f);
            return;
        }
        boolean ok;
        try {
            ok = execute(p, pw);
        } catch (Exception ex) {
            plugin.getLogger().warning("Erreur du pouvoir " + pw.type() + " : " + ex);
            ok = false;
        }
        if (ok) {
            cd.set(p.getUniqueId(), key, (long) (pw.cooldown() * 1000));
            p.sendActionBar(Txt.c(col + pw.name() + " &7activé"));
        }
    }

    private boolean execute(Player p, Power pw) {
        return switch (pw.type()) {
            case AIR_DASH -> dash(p, pw, 0);
            case AIR_JUMP -> airJump(p, pw);
            case TORNADO -> tornado(p, pw);
            case FIRE_DASH -> dash(p, pw, 1);
            case FLAME_BURST -> flameBurst(p, pw);
            case FIRE_RESISTANCE -> fireResistance(p, pw);
            case LIGHTNING_STRIKE -> lightning(p, pw);
            case ELECTRIC_DASH -> dash(p, pw, 2);
            case ELECTRIC_FIELD -> electricField(p, pw);
            case TIME_STOP -> timeStop(p, pw);
            case SLOW_ENEMIES -> slowEnemies(p, pw);
            case HASTE_SELF -> hasteSelf(p, pw);
            case INVISIBILITY -> invisibility(p, pw);
            case SHORT_TELEPORT -> shortTeleport(p, pw);
            case DARK_ZONE -> darkZone(p, pw);
            case LIFESTEAL -> lifestealMode(p, pw);
            case SHOCKWAVE -> shockwave(p, pw);
            case REGEN -> regen(p, pw);
            case SEISMIC_WAVE -> seismic(p, pw);
            case STONE_WALL -> stoneWall(p, pw);
            case DAMAGE_RESIST -> damageResist(p, pw);
            case WATER_PRISON -> waterPrison(p, pw);
            case WAVE -> wave(p, pw);
            case WATER_BREATHING -> false;
            case SUMMON_SERVANTS -> summonServants(p, pw);
            case FEAR_AURA -> fearAura(p, pw);
            case CURSE -> curse(p, pw);
            case CLONES -> cloneSpell(p, pw);
            case TELEPORT -> phantomTeleport(p, pw);
            case CONFUSION -> confusion(p, pw);
            case LEVITATE_ZONE -> levitateZone(p, pw);
            case GRAVITY_CRUSH -> gravityCrush(p, pw);
            case LOW_GRAVITY -> lowGravity(p, pw);
            case RIFT -> rift(p, pw);
            case VOID_PROJECTILE -> voidProjectile(p, pw);
            case VOID_TELEPORT -> voidTeleport(p, pw);
        };
    }

    // ------------------------------------------------------------------ Aides

    private double rr(double a, double b) { return a + rnd.nextDouble() * (b - a); }

    private void potion(LivingEntity e, PotionEffectType t, double seconds, int amp) {
        e.addPotionEffect(new PotionEffect(t, (int) Math.max(1, seconds * 20), Math.max(0, amp), false, true, true));
    }

    private void potionHidden(LivingEntity e, PotionEffectType t, double seconds, int amp) {
        e.addPotionEffect(new PotionEffect(t, (int) Math.max(1, seconds * 20), Math.max(0, amp), false, false, true));
    }

    private void damage(Player p, LivingEntity t, double amount) {
        if (amount > 0) t.damage(amount, p);
    }

    private boolean isTemp(Entity e) {
        return e.getPersistentDataContainer().has(tempKey, PersistentDataType.BYTE);
    }

    private boolean isEnemy(Player owner, LivingEntity e) {
        if (e.equals(owner) || e instanceof ArmorStand || e.isDead() || !e.isValid()) return false;
        if (plugin.spawner().isDemonEntity(e) || isTemp(e)) return false;
        UUID so = servants.get(e.getUniqueId());
        if (so != null && so.equals(owner.getUniqueId())) return false;
        if (e instanceof Player t) {
            if (!plugin.getConfig().getBoolean("powers-settings.affect-players", true)) return false;
            if (t.getGameMode() == GameMode.CREATIVE || t.getGameMode() == GameMode.SPECTATOR) return false;
        }
        return !(e instanceof Tameable tm && tm.isTamed() && owner.equals(tm.getOwner()));
    }

    private List<LivingEntity> enemies(Player p, Location c, double r) {
        List<LivingEntity> out = new ArrayList<>();
        if (c.getWorld() == null) return out;
        for (LivingEntity e : c.getWorld().getNearbyLivingEntities(c, r)) if (isEnemy(p, e)) out.add(e);
        return out;
    }

    private Vector flat(Player p) {
        Vector d = p.getLocation().getDirection();
        d.setY(0);
        if (d.lengthSquared() < 1e-4) return new Vector(1, 0, 0);
        return d.normalize();
    }

    private Vector away(Location from, Location to, double str, double y) {
        Vector v = to.toVector().subtract(from.toVector());
        v.setY(0);
        if (v.lengthSquared() < 1e-4) v = new Vector(rr(-1, 1), 0, rr(-1, 1));
        if (v.lengthSquared() < 1e-4) v = new Vector(1, 0, 0);
        v.normalize().multiply(str);
        v.setY(y);
        return v;
    }

    private LivingEntity findTarget(Player p, double range) {
        Location eye = p.getEyeLocation();
        RayTraceResult r = p.getWorld().rayTrace(eye, eye.getDirection(), range, FluidCollisionMode.NEVER, true, 0.6,
                en -> en instanceof LivingEntity le && isEnemy(p, le));
        if (r != null && r.getHitEntity() instanceof LivingEntity le) return le;
        LivingEntity best = null;
        double bestDot = 0.93;
        for (LivingEntity le : enemies(p, p.getLocation(), range)) {
            Vector to = le.getEyeLocation().toVector().subtract(eye.toVector());
            if (to.lengthSquared() < 1e-4) continue;
            double dot = to.normalize().dot(eye.getDirection());
            if (dot > bestDot && p.hasLineOfSight(le)) {
                bestDot = dot;
                best = le;
            }
        }
        return best;
    }

    private Location ground(Location l) {
        World w = l.getWorld();
        int y = l.getBlockY() + 2;
        for (int i = 0; i < 7; i++) {
            Block b = w.getBlockAt(l.getBlockX(), y - i, l.getBlockZ());
            if (!b.isPassable()) return new Location(w, l.getX(), b.getY() + 1.0, l.getZ(), l.getYaw(), l.getPitch());
        }
        return l.clone();
    }

    /** Position sûre (pieds et tête libres) autour de la cible d'un rayon, ou null. */
    private Location safeSpot(Location eyeTarget, float yaw, float pitch) {
        Location base = eyeTarget.clone().subtract(0, 1.62, 0);
        for (int dy : new int[]{0, 1, -1, 2, -2}) {
            Location f = base.clone().add(0, dy, 0);
            f.setY(Math.floor(f.getY()));
            Block b1 = f.getBlock();
            Block b2 = f.clone().add(0, 1, 0).getBlock();
            if (b1.isPassable() && b2.isPassable() && !b1.isLiquid()) {
                f.setYaw(yaw);
                f.setPitch(pitch);
                return f;
            }
        }
        return null;
    }

    private Location rayDest(Player p, double range) {
        Location eye = p.getEyeLocation();
        Vector dir = eye.getDirection();
        RayTraceResult r = p.getWorld().rayTraceBlocks(eye, dir, range, FluidCollisionMode.NEVER, true);
        double dist = range;
        if (r != null) dist = Math.max(0, eye.toVector().distance(r.getHitPosition()) - 0.8);
        Location t = eye.clone().add(dir.clone().multiply(dist));
        return safeSpot(t, p.getLocation().getYaw(), p.getLocation().getPitch());
    }

    private void expandingRing(Location c, double maxR, int ticks, String particle, Color dust) {
        new BukkitRunnable() {
            int t = 0;

            @Override
            public void run() {
                t++;
                double r = maxR * t / ticks;
                int pts = (int) Math.max(12, r * 8);
                if (dust != null) Fx.ringDust(c, r, pts, dust, 1.5f);
                if (particle != null) Fx.ring(c, r, pts, particle);
                if (t >= ticks) cancel();
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }

    private void freeze(LivingEntity e, double seconds, String particle) {
        long until = System.currentTimeMillis() + (long) (seconds * 1000);
        Frozen f = frozen.get(e.getUniqueId());
        if (f != null) {
            f.until = Math.max(f.until, until);
            return;
        }
        f = new Frozen();
        f.until = until;
        f.particle = particle;
        if (e instanceof Mob m) {
            f.ai = m.hasAI();
            m.setAI(false);
            m.setTarget(null);
        }
        f.gravity = e.hasGravity();
        e.setVelocity(new Vector(0, 0, 0));
        frozen.put(e.getUniqueId(), f);
    }

    private void freezeProjectile(Projectile pr, double seconds) {
        if (frozen.containsKey(pr.getUniqueId())) return;
        Frozen f = new Frozen();
        f.projectile = true;
        f.until = System.currentTimeMillis() + (long) (seconds * 1000);
        f.vel = pr.getVelocity();
        f.gravity = pr.hasGravity();
        f.particle = "end_rod";
        pr.setGravity(false);
        pr.setVelocity(new Vector(0, 0, 0));
        frozen.put(pr.getUniqueId(), f);
    }

    // ------------------------------------------------------------------ Pouvoirs : AERON

    private boolean dash(Player p, Power pw, int kind) {
        Vector v = p.getLocation().getDirection().normalize().multiply(pw.strength());
        if (v.getY() < 0.2) v.setY(0.2);
        p.setVelocity(v);
        noFall.put(p.getUniqueId(), System.currentTimeMillis() + 6000);
        Fx.sound(p.getLocation(), pw.sound(), 1f, 1.2f);
        if (kind == 2) Fx.sound(p.getLocation(), "entity.lightning_bolt.thunder", 0.5f, 1.8f);
        final Set<UUID> hit = new HashSet<>();
        new BukkitRunnable() {
            int t = 0;

            @Override
            public void run() {
                if (!p.isOnline() || p.isDead() || t++ >= 10) { cancel(); return; }
                Location l = p.getLocation().add(0, 0.9, 0);
                Fx.spawn(l, pw.particle(), 8, 0.25, 0.4, 0.25, 0.02);
                if (kind == 1) {
                    Fx.spawn(l, "flame", 8, 0.3, 0.4, 0.3, 0.03);
                    Fx.spawn(l, "lava", 1, 0.2, 0.2, 0.2, 0);
                }
                if (kind == 2) Fx.spawn(l, "electric_spark", 10, 0.4, 0.5, 0.4, 0.1);
                if (kind > 0) {
                    for (LivingEntity e : enemies(p, l, Math.max(1.5, pw.range()))) {
                        if (!hit.add(e.getUniqueId())) continue;
                        if (kind == 1) e.setFireTicks((int) (pw.duration() * 20));
                        else potion(e, PotionEffectType.SLOWNESS, 2, 2);
                        damage(p, e, pw.damage());
                    }
                }
            }
        }.runTaskTimer(plugin, 0L, 1L);
        return true;
    }

    private boolean airJump(Player p, Power pw) {
        Vector v = p.getVelocity();
        Vector f = flat(p).multiply(0.25);
        p.setVelocity(new Vector(v.getX() * 0.5 + f.getX(), pw.strength(), v.getZ() * 0.5 + f.getZ()));
        noFall.put(p.getUniqueId(), System.currentTimeMillis() + 6000);
        Location l = p.getLocation().add(0, 0.1, 0);
        Fx.ring(l, 1.0, 16, pw.particle());
        Fx.spawn(l, "cloud", 12, 0.4, 0.1, 0.4, 0.05);
        Fx.sound(l, pw.sound(), 1f, 1.5f);
        return true;
    }

    private boolean tornado(Player p, Power pw) {
        Location c = p.getLocation();
        for (LivingEntity e : enemies(p, c, pw.range())) {
            e.setVelocity(away(c, e.getLocation(), pw.strength(), 0.7));
            damage(p, e, pw.damage());
        }
        Fx.sound(c, pw.sound(), 1.2f, 0.6f);
        final int ticks = (int) (pw.duration() * 20);
        new BukkitRunnable() {
            int t = 0;

            @Override
            public void run() {
                if (!p.isOnline() || t >= ticks) { cancel(); return; }
                Location base = p.getLocation();
                for (int i = 0; i < 3; i++) {
                    double h = ((t * 0.3) + i * 0.8) % 3.0;
                    double a = t * 0.6 + i * 2.1 + h * 2;
                    double r = 0.6 + h * 0.6;
                    Location l = base.clone().add(Math.cos(a) * r, h, Math.sin(a) * r);
                    Fx.spawn(l, pw.particle(), 2, 0.05, 0.05, 0.05, 0.01);
                    Fx.spawn(l, "poof", 1, 0, 0, 0, 0.01);
                }
                if (t % 5 == 0) {
                    for (LivingEntity e : enemies(p, base, pw.range() * 0.6)) e.setVelocity(away(base, e.getLocation(), 0.55, 0.25));
                }
                t++;
            }
        }.runTaskTimer(plugin, 0L, 1L);
        return true;
    }

    // ------------------------------------------------------------------ IGNIS

    private boolean flameBurst(Player p, Power pw) {
        Location c = p.getLocation().add(0, 0.3, 0);
        expandingRing(c, pw.range(), 8, "flame", null);
        expandingRing(c.clone().add(0, 0.6, 0), pw.range() * 0.7, 8, "lava", null);
        for (LivingEntity e : enemies(p, c, pw.range())) {
            e.setFireTicks((int) (pw.duration() * 20));
            e.setVelocity(away(c, e.getLocation(), pw.strength(), 0.4));
            damage(p, e, pw.damage());
        }
        Fx.spawn(c.clone().add(0, 1, 0), "explosion", 1, 0, 0, 0, 0);
        Fx.sound(c, "entity.generic.explode", 0.8f, 1.2f);
        Fx.sound(c, pw.sound(), 1.2f, 0.7f);
        return true;
    }

    private boolean fireResistance(Player p, Power pw) {
        potion(p, PotionEffectType.FIRE_RESISTANCE, pw.duration(), 0);
        p.setFireTicks(0);
        Fx.ring(p.getLocation().add(0, 0.2, 0), 1.2, 20, "flame");
        Fx.spawn(p.getLocation().add(0, 1, 0), "flame", 30, 0.4, 0.8, 0.4, 0.05);
        Fx.sound(p.getLocation(), "item.firecharge.use", 1f, 0.8f);
        return true;
    }

    // ------------------------------------------------------------------ VOLTAR

    private boolean lightning(Player p, Power pw) {
        LivingEntity t = findTarget(p, pw.range());
        if (t == null) {
            p.sendActionBar(Txt.c("&cAucune cible en vue."));
            return false;
        }
        Location l = t.getLocation();
        l.getWorld().strikeLightningEffect(l);
        Fx.spawn(l.clone().add(0, 1, 0), "electric_spark", 40, 0.5, 1, 0.5, 0.3);
        damage(p, t, pw.damage());
        potion(t, PotionEffectType.SLOWNESS, 1.5, 1);
        return true;
    }

    private boolean electricField(Player p, Power pw) {
        final int ticks = (int) (pw.duration() * 20);
        Fx.sound(p.getLocation(), "block.beacon.activate", 1f, 1.8f);
        new BukkitRunnable() {
            int t = 0;

            @Override
            public void run() {
                if (!p.isOnline() || p.isDead() || t >= ticks) { cancel(); return; }
                Location c = p.getLocation().add(0, 0.1, 0);
                if (t % 2 == 0) {
                    Fx.ring(c, pw.range(), 24, "electric_spark");
                    Fx.spawn(c.clone().add(rr(-pw.range(), pw.range()), rr(0, 2), rr(-pw.range(), pw.range())), "electric_spark", 3, 0.1, 0.1, 0.1, 0.05);
                }
                if (t % 20 == 0) {
                    Fx.sound(c, "entity.lightning_bolt.impact", 0.3f, 2f);
                    for (LivingEntity e : enemies(p, c, pw.range())) {
                        damage(p, e, pw.damage());
                        potion(e, PotionEffectType.SLOWNESS, 1.5, 1);
                        Fx.spawn(e.getLocation().add(0, 1, 0), "electric_spark", 10, 0.3, 0.5, 0.3, 0.2);
                    }
                }
                t++;
            }
        }.runTaskTimer(plugin, 0L, 1L);
        return true;
    }

    // ------------------------------------------------------------------ CHRONOS

    private boolean timeStop(Player p, Power pw) {
        Location c = p.getLocation();
        int max = plugin.getConfig().getInt("powers-settings.max-frozen", 40);
        int n = 0;
        for (LivingEntity e : enemies(p, c, pw.range())) {
            if (n++ >= max) break;
            freeze(e, pw.duration(), "end_rod");
        }
        for (Entity e : c.getWorld().getNearbyEntities(c, pw.range(), pw.range(), pw.range())) {
            if (e instanceof Projectile pr && !p.equals(pr.getShooter()) && n++ < max) freezeProjectile(pr, pw.duration());
        }
        Fx.sound(c, "block.bell.use", 2f, 0.5f);
        Fx.sound(c, pw.sound(), 1.5f, 0.5f);
        final int ticks = (int) Math.max(20, pw.duration() * 20);
        new BukkitRunnable() {
            int t = 0;

            @Override
            public void run() {
                if (t >= ticks) { cancel(); return; }
                double a1 = -t * 0.2, a2 = -t * 0.025;
                for (double r = 0.5; r < pw.range(); r += 0.6) {
                    Fx.spawn(c.clone().add(Math.cos(a1) * r, 0.2, Math.sin(a1) * r), "end_rod", 1, 0, 0, 0, 0);
                    if (r < pw.range() * 0.6) Fx.spawn(c.clone().add(Math.cos(a2) * r, 0.2, Math.sin(a2) * r), "end_rod", 1, 0, 0, 0, 0);
                }
                if (t % 10 == 0) Fx.ring(c.clone().add(0, 0.1, 0), pw.range(), 36, "enchant");
                if (t % 20 == 0) Fx.sound(c, "block.note_block.hat", 1f, 0.6f);
                t++;
            }
        }.runTaskTimer(plugin, 0L, 1L);
        return true;
    }

    private boolean slowEnemies(Player p, Power pw) {
        Location c = p.getLocation();
        for (LivingEntity e : enemies(p, c, pw.range())) {
            potion(e, PotionEffectType.SLOWNESS, pw.duration(), (int) pw.strength());
            potion(e, PotionEffectType.MINING_FATIGUE, pw.duration(), 1);
            Fx.spawn(e.getLocation().add(0, 1, 0), "end_rod", 10, 0.3, 0.5, 0.3, 0.02);
        }
        expandingRing(c.clone().add(0, 0.2, 0), pw.range(), 10, "end_rod", Color.fromRGB(230, 200, 90));
        Fx.sound(c, pw.sound(), 1.2f, 0.5f);
        return true;
    }

    private boolean hasteSelf(Player p, Power pw) {
        potion(p, PotionEffectType.SPEED, pw.duration(), (int) pw.strength());
        potion(p, PotionEffectType.HASTE, pw.duration(), 1);
        Fx.sound(p.getLocation(), "block.beacon.power_select", 1f, 1.8f);
        final int ticks = (int) (pw.duration() * 20);
        new BukkitRunnable() {
            int t = 0;

            @Override
            public void run() {
                if (!p.isOnline() || t >= ticks) { cancel(); return; }
                Location l = p.getLocation().add(0, 1, 0);
                Fx.dust(l, Color.fromRGB(230, 200, 90), 1.4f, 3, 0.3);
                Fx.spawn(l, "end_rod", 1, 0.2, 0.4, 0.2, 0);
                t += 2;
            }
        }.runTaskTimer(plugin, 0L, 2L);
        return true;
    }

    // ------------------------------------------------------------------ UMBRA

    private boolean invisibility(Player p, Power pw) {
        potionHidden(p, PotionEffectType.INVISIBILITY, pw.duration(), 0);
        Fx.spawn(p.getLocation().add(0, 1, 0), "squid_ink", 30, 0.4, 0.8, 0.4, 0.02);
        Fx.spawn(p.getLocation().add(0, 1, 0), "smoke", 20, 0.4, 0.8, 0.4, 0.02);
        Fx.sound(p.getLocation(), "entity.enderman.teleport", 0.8f, 0.5f);
        return true;
    }

    private boolean shortTeleport(Player p, Power pw) {
        Location dest = rayDest(p, pw.range());
        if (dest == null) {
            p.sendActionBar(Txt.c("&cDestination bloquée."));
            return false;
        }
        Location from = p.getLocation();
        Fx.spawn(from.clone().add(0, 1, 0), "squid_ink", 25, 0.3, 0.6, 0.3, 0.05);
        p.teleport(dest);
        noFall.put(p.getUniqueId(), System.currentTimeMillis() + 4000);
        Fx.spawn(dest.clone().add(0, 1, 0), "squid_ink", 25, 0.3, 0.6, 0.3, 0.05);
        Fx.spawn(dest.clone().add(0, 1, 0), "smoke", 15, 0.3, 0.6, 0.3, 0.02);
        Fx.sound(from, pw.sound(), 1f, 0.7f);
        Fx.sound(dest, pw.sound(), 1f, 0.7f);
        return true;
    }

    private boolean darkZone(Player p, Power pw) {
        final Location c = p.getLocation();
        final int ticks = (int) (pw.duration() * 20);
        Fx.sound(c, "entity.warden.sonic_charge", 1f, 0.6f);
        new BukkitRunnable() {
            int t = 0;

            @Override
            public void run() {
                if (t >= ticks) { cancel(); return; }
                for (int i = 0; i < 14; i++) {
                    double a = rr(0, 2 * Math.PI), r = Math.sqrt(rnd.nextDouble()) * pw.range();
                    Location l = c.clone().add(Math.cos(a) * r, rr(0, 3), Math.sin(a) * r);
                    Fx.spawn(l, "squid_ink", 1, 0, 0, 0, 0);
                    Fx.spawn(l, "smoke", 1, 0.1, 0.1, 0.1, 0.01);
                }
                Fx.ring(c.clone().add(0, 0.1, 0), pw.range(), 30, "large_smoke");
                if (t % 10 == 0) {
                    for (LivingEntity e : enemies(p, c, pw.range())) {
                        potion(e, PotionEffectType.DARKNESS, 3, 0);
                        potion(e, PotionEffectType.BLINDNESS, 1.5, 0);
                    }
                    if (p.isOnline() && p.getLocation().distanceSquared(c) <= pw.range() * pw.range()) {
                        potionHidden(p, PotionEffectType.SPEED, 2, 1);
                    }
                }
                t += 2;
            }
        }.runTaskTimer(plugin, 0L, 2L);
        return true;
    }

    // ------------------------------------------------------------------ SANGUIS

    private boolean lifestealMode(Player p, Power pw) {
        lifesteal.put(p.getUniqueId(), System.currentTimeMillis() + (long) (pw.duration() * 1000));
        Fx.ringDust(p.getLocation().add(0, 0.2, 0), 1.3, 24, Color.fromRGB(170, 0, 20), 1.6f);
        Fx.spawn(p.getLocation().add(0, 1, 0), "crimson_spore", 30, 0.4, 0.8, 0.4, 0);
        Fx.sound(p.getLocation(), "entity.witch.celebrate", 1f, 0.6f);
        return true;
    }

    private boolean shockwave(Player p, Power pw) {
        Location c = p.getLocation().add(0, 0.3, 0);
        expandingRing(c, pw.range(), 8, "damage_indicator", Color.fromRGB(170, 0, 20));
        for (LivingEntity e : enemies(p, c, pw.range())) {
            e.setVelocity(away(c, e.getLocation(), pw.strength(), 0.45));
            damage(p, e, pw.damage());
            potion(e, PotionEffectType.SLOWNESS, 2, 1);
        }
        Fx.sound(c, "entity.warden.sonic_boom", 0.8f, 1.4f);
        Fx.sound(c, pw.sound(), 1f, 0.6f);
        return true;
    }

    private boolean regen(Player p, Power pw) {
        potion(p, PotionEffectType.REGENERATION, pw.duration(), (int) pw.strength());
        Fx.spawn(p.getLocation().add(0, 1, 0), "heart", 8, 0.4, 0.6, 0.4, 0);
        Fx.ringDust(p.getLocation().add(0, 0.2, 0), 1.0, 20, Color.fromRGB(170, 0, 20), 1.4f);
        Fx.sound(p.getLocation(), "entity.generic.drink", 1f, 0.6f);
        return true;
    }

    // ------------------------------------------------------------------ TERRAX

    private boolean seismic(Player p, Power pw) {
        final Location start = p.getLocation();
        final Vector dir = flat(p);
        final Set<UUID> hit = new HashSet<>();
        Fx.sound(start, "entity.generic.explode", 0.7f, 0.5f);
        new BukkitRunnable() {
            int step = 0;

            @Override
            public void run() {
                step++;
                if (step > pw.range() || !p.isOnline()) { cancel(); return; }
                Location l = ground(start.clone().add(dir.clone().multiply(step)));
                Material below = l.clone().subtract(0, 1, 0).getBlock().getType();
                Fx.block(l.clone().add(0, 0.3, 0), below.isBlock() ? below : Material.STONE, 25, 0.5, 0.4, 0.5);
                Fx.spawn(l.clone().add(0, 0.5, 0), "poof", 4, 0.4, 0.2, 0.4, 0.02);
                Fx.sound(l, "block.stone.break", 0.7f, 0.6f);
                for (LivingEntity e : enemies(p, l, 1.8)) {
                    if (!hit.add(e.getUniqueId())) continue;
                    e.setVelocity(new Vector(0, 0.55 + pw.strength() * 0.5, 0));
                    damage(p, e, pw.damage());
                }
            }
        }.runTaskTimer(plugin, 0L, 1L);
        return true;
    }

    private String wallKey(Block b) {
        return b.getWorld().getName() + "," + b.getX() + "," + b.getY() + "," + b.getZ();
    }

    private boolean stoneWall(Player p, Power pw) {
        Vector dir = flat(p);
        Vector side = new Vector(-dir.getZ(), 0, dir.getX());
        Location mid = p.getLocation().add(dir.clone().multiply(3));
        int half = Math.max(1, (int) pw.range() / 2);
        List<Block> placed = new ArrayList<>();
        for (int i = -half; i <= half; i++) {
            for (int h = 0; h < 3; h++) {
                Location l = mid.clone().add(side.clone().multiply(i)).add(0, h, 0);
                Block b = l.getBlock();
                if (!b.getType().isAir()) continue;
                if (!b.getWorld().getNearbyEntities(BoundingBox.of(b), e -> e instanceof LivingEntity).isEmpty()) continue;
                b.setType(wallMaterial, false);
                placed.add(b);
                walls.add(wallKey(b) + "," + wallMaterial.name());
                Fx.block(b.getLocation().add(0.5, 0.5, 0.5), wallMaterial, 10, 0.3, 0.3, 0.3);
            }
        }
        if (placed.isEmpty()) {
            p.sendActionBar(Txt.c("&cPas de place pour le mur."));
            return false;
        }
        saveWalls();
        Fx.sound(mid, "block.stone.place", 1.5f, 0.5f);
        Fx.sound(mid, "entity.iron_golem.repair", 1f, 0.6f);
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            for (Block b : placed) {
                String key = wallKey(b) + "," + wallMaterial.name();
                if (walls.remove(key) && b.getType() == wallMaterial) {
                    Fx.block(b.getLocation().add(0.5, 0.5, 0.5), wallMaterial, 12, 0.3, 0.3, 0.3);
                    b.setType(Material.AIR, false);
                }
            }
            saveWalls();
        }, (long) (pw.duration() * 20));
        return true;
    }

    private File wallFile() { return new File(plugin.getDataFolder(), "walls.yml"); }

    private void saveWalls() {
        YamlConfiguration y = new YamlConfiguration();
        y.set("walls", new ArrayList<>(walls));
        try {
            y.save(wallFile());
        } catch (IOException e) {
            plugin.getLogger().warning("walls.yml : " + e.getMessage());
        }
    }

    private void restoreLeftoverWalls() {
        if (!wallFile().exists()) return;
        walls.addAll(YamlConfiguration.loadConfiguration(wallFile()).getStringList("walls"));
        restoreAllWalls();
    }

    private void restoreAllWalls() {
        for (String key : new ArrayList<>(walls)) {
            try {
                String[] p = key.split(",");
                World w = Bukkit.getWorld(p[0]);
                if (w == null) continue;
                Block b = w.getBlockAt(Integer.parseInt(p[1]), Integer.parseInt(p[2]), Integer.parseInt(p[3]));
                if (b.getType().name().equals(p[4])) b.setType(Material.AIR, false);
                walls.remove(key);
            } catch (Exception ignored) {
                walls.remove(key);
            }
        }
        saveWalls();
    }

    private boolean damageResist(Player p, Power pw) {
        potion(p, PotionEffectType.RESISTANCE, pw.duration(), (int) pw.strength());
        Fx.ringDust(p.getLocation().add(0, 0.2, 0), 1.2, 20, Color.fromRGB(120, 85, 50), 1.6f);
        Fx.block(p.getLocation().add(0, 1, 0), Material.STONE, 30, 0.4, 0.8, 0.4);
        Fx.sound(p.getLocation(), "block.stone.place", 1f, 0.5f);
        return true;
    }

    // ------------------------------------------------------------------ AQUARIS

    private boolean waterPrison(Player p, Power pw) {
        LivingEntity t = findTarget(p, pw.range());
        if (t == null) {
            p.sendActionBar(Txt.c("&cAucune cible en vue."));
            return false;
        }
        freeze(t, pw.duration(), "bubble_pop");
        Location tl = t.getLocation();
        List<Block> shell = new ArrayList<>();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 3; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    boolean outer = Math.abs(dx) == 1 || Math.abs(dz) == 1 || dy == -1 || dy == 3;
                    Block b = tl.getBlock().getRelative(dx, dy, dz);
                    if (outer && b.getType().isAir()) shell.add(b);
                }
            }
        }
        List<Player> viewers = new ArrayList<>();
        for (Player v : tl.getWorld().getPlayers()) if (v.getLocation().distanceSquared(tl) <= 1600) viewers.add(v);
        var water = Material.WATER.createBlockData();
        for (Block b : shell) for (Player v : viewers) v.sendBlockChange(b.getLocation(), water);
        Fx.sound(tl, "entity.player.splash.high_speed", 1.2f, 0.7f);
        Fx.spawn(tl.clone().add(0, 1, 0), "splash", 60, 0.8, 1, 0.8, 0.1);
        final int ticks = (int) (pw.duration() * 20);
        new BukkitRunnable() {
            int n = 0;

            @Override
            public void run() {
                n += 20;
                if (t.isValid() && n < ticks) damage(p, t, pw.damage());
                if (n >= ticks || !t.isValid()) {
                    for (Block b : shell) {
                        var real = b.getBlockData();
                        for (Player v : viewers) if (v.isOnline()) v.sendBlockChange(b.getLocation(), real);
                    }
                    cancel();
                }
            }
        }.runTaskTimer(plugin, 20L, 20L);
        return true;
    }

    private boolean wave(Player p, Power pw) {
        final Location start = p.getLocation().add(0, 0.5, 0);
        final Vector dir = flat(p);
        final Vector side = new Vector(-dir.getZ(), 0, dir.getX());
        for (LivingEntity e : enemies(p, start, pw.range())) {
            Vector to = e.getLocation().toVector().subtract(start.toVector()).setY(0);
            if (to.lengthSquared() < 1e-4 || to.normalize().dot(dir) < 0.35) continue;
            e.setVelocity(dir.clone().multiply(pw.strength()).setY(0.4));
            damage(p, e, pw.damage());
        }
        Fx.sound(start, "entity.player.splash.high_speed", 1.5f, 0.6f);
        new BukkitRunnable() {
            int t = 0;

            @Override
            public void run() {
                t++;
                double d = t * 1.3;
                if (d > pw.range()) { cancel(); return; }
                double w = d * 0.6;
                for (double s = -w; s <= w; s += 0.5) {
                    Location l = start.clone().add(dir.clone().multiply(d)).add(side.clone().multiply(s));
                    Fx.spawn(l, "splash", 3, 0.1, 0.4, 0.1, 0.1);
                    Fx.spawn(l, "bubble_pop", 1, 0.1, 0.3, 0.1, 0.02);
                    Fx.spawn(l, "nautilus", 1, 0.1, 0.3, 0.1, 0.2);
                }
            }
        }.runTaskTimer(plugin, 0L, 1L);
        return true;
    }

    // ------------------------------------------------------------------ NOX

    private boolean summonServants(Player p, Power pw) {
        int max = plugin.getConfig().getInt("powers-settings.max-servants", 6);
        int mine = 0;
        for (UUID o : servants.values()) if (o.equals(p.getUniqueId())) mine++;
        int count = Math.min((int) pw.strength(), max - mine);
        if (count <= 0) {
            p.sendActionBar(Txt.c("&cVos serviteurs sont déjà au maximum."));
            return false;
        }
        Location c = p.getLocation();
        for (int i = 0; i < count; i++) {
            double a = 2 * Math.PI * i / count;
            Location s = ground(c.clone().add(Math.cos(a) * 2, 0, Math.sin(a) * 2));
            Zombie z = c.getWorld().spawn(s, Zombie.class, e -> {
                e.setBaby(false);
                e.customName(Txt.c("&8Serviteur de &7" + p.getName()));
                e.setCustomNameVisible(true);
                e.setCanPickupItems(false);
                e.setPersistent(false);
                e.setRemoveWhenFarAway(true);
                e.getPersistentDataContainer().set(tempKey, PersistentDataType.BYTE, (byte) 1);
                e.getPersistentDataContainer().set(ownerKey, PersistentDataType.STRING, p.getUniqueId().toString());
                EntityEquipment eq = e.getEquipment();
                eq.setItemInMainHand(new ItemStack(Material.IRON_SWORD));
                eq.setItemInMainHandDropChance(0f);
                eq.setHelmet(new ItemStack(Material.LEATHER_HELMET));
                eq.setHelmetDropChance(0f);
                e.addPotionEffect(new PotionEffect(PotionEffectType.FIRE_RESISTANCE,
                        (int) (pw.duration() * 20) + 100, 0, false, false));
            });
            servants.put(z.getUniqueId(), p.getUniqueId());
            Fx.spawn(s.clone().add(0, 1, 0), "soul", 15, 0.3, 0.6, 0.3, 0.03);
            Fx.spawn(s.clone().add(0, 0.2, 0), "soul_fire_flame", 10, 0.3, 0.1, 0.3, 0.02);
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                servants.remove(z.getUniqueId());
                if (z.isValid()) {
                    Fx.spawn(z.getLocation().add(0, 1, 0), "poof", 15, 0.3, 0.6, 0.3, 0.03);
                    z.remove();
                }
            }, (long) (pw.duration() * 20));
        }
        Fx.sound(c, "entity.wither.spawn", 0.6f, 1.5f);
        Fx.sound(c, "entity.evoker.prepare_summon", 1f, 0.6f);
        return true;
    }

    private void servantAi() {
        for (Map.Entry<UUID, UUID> en : new ArrayList<>(servants.entrySet())) {
            Entity e = Bukkit.getEntity(en.getKey());
            Player owner = Bukkit.getPlayer(en.getValue());
            if (!(e instanceof Zombie z) || !z.isValid() || owner == null) {
                if (e != null && owner == null) e.remove();
                if (e == null || !e.isValid() || owner == null) servants.remove(en.getKey());
                continue;
            }
            LivingEntity cur = z.getTarget();
            if (cur == null || !cur.isValid() || cur.equals(owner) || servants.containsKey(cur.getUniqueId())) {
                LivingEntity best = enemies(owner, z.getLocation(), 16).stream()
                        .min(Comparator.comparingDouble(x -> x.getLocation().distanceSquared(z.getLocation()))).orElse(null);
                z.setTarget(best);
                if (best == null && z.getLocation().distanceSquared(owner.getLocation()) > 64) {
                    z.getPathfinder().moveTo(owner.getLocation(), 1.2);
                }
            }
        }
    }

    private boolean fearAura(Player p, Power pw) {
        final int ticks = (int) (pw.duration() * 20);
        Fx.sound(p.getLocation(), "entity.ghast.scream", 1f, 0.5f);
        new BukkitRunnable() {
            int t = 0;

            @Override
            public void run() {
                if (!p.isOnline() || p.isDead() || t >= ticks) { cancel(); return; }
                Location c = p.getLocation().add(0, 0.2, 0);
                Fx.ring(c, pw.range(), 30, "soul");
                Fx.spawn(c.clone().add(0, 1, 0), "sculk_soul", 3, 0.5, 0.8, 0.5, 0.02);
                for (LivingEntity e : enemies(p, c, pw.range())) {
                    Vector a = away(c, e.getLocation(), 0.5, 0.1);
                    if (e instanceof Player) {
                        e.setVelocity(a);
                        potion(e, PotionEffectType.DARKNESS, 2, 0);
                    } else if (e instanceof Mob m) {
                        m.setTarget(null);
                        m.getPathfinder().moveTo(e.getLocation().add(a.clone().setY(0).normalize().multiply(8)), 1.5);
                    }
                    potion(e, PotionEffectType.WEAKNESS, 2, 0);
                }
                t += 10;
            }
        }.runTaskTimer(plugin, 0L, 10L);
        return true;
    }

    private boolean curse(Player p, Power pw) {
        LivingEntity t = findTarget(p, pw.range());
        if (t == null) {
            p.sendActionBar(Txt.c("&cAucune cible en vue."));
            return false;
        }
        potion(t, PotionEffectType.WITHER, pw.duration(), 0);
        potion(t, PotionEffectType.WEAKNESS, pw.duration(), 1);
        potion(t, PotionEffectType.SLOWNESS, pw.duration(), 0);
        potion(t, PotionEffectType.GLOWING, pw.duration(), 0);
        Fx.sound(t.getLocation(), "entity.wither.hurt", 1f, 0.5f);
        final int ticks = (int) (pw.duration() * 20);
        new BukkitRunnable() {
            int n = 0;

            @Override
            public void run() {
                if (!t.isValid() || n >= ticks) { cancel(); return; }
                Location l = t.getLocation().add(0, 1, 0);
                Fx.spawn(l, "soul", 3, 0.4, 0.6, 0.4, 0.02);
                Fx.spawn(l, "smoke", 3, 0.4, 0.6, 0.4, 0.01);
                n += 10;
            }
        }.runTaskTimer(plugin, 0L, 10L);
        return true;
    }

    // ------------------------------------------------------------------ PHANTOM

    private ArmorStand spawnClone(Player p, Location loc, double seconds) {
        ArmorStand as = loc.getWorld().spawn(loc, ArmorStand.class, a -> {
            a.setArms(true);
            a.setBasePlate(false);
            a.setSilent(true);
            a.setCanPickupItems(false);
            a.customName(Txt.c("&f" + p.getName()));
            a.setCustomNameVisible(true);
            a.setRotation(p.getLocation().getYaw(), 0);
            a.getPersistentDataContainer().set(tempKey, PersistentDataType.BYTE, (byte) 1);
            a.getPersistentDataContainer().set(ownerKey, PersistentDataType.STRING, p.getUniqueId().toString());
            EntityEquipment eq = a.getEquipment();
            ItemStack skull = new ItemStack(Material.PLAYER_HEAD);
            SkullMeta sm = (SkullMeta) skull.getItemMeta();
            sm.setOwningPlayer(p);
            skull.setItemMeta(sm);
            eq.setHelmet(skull);
            ItemStack c = p.getInventory().getChestplate(), l = p.getInventory().getLeggings(), b = p.getInventory().getBoots();
            eq.setChestplate(c == null ? null : c.clone());
            eq.setLeggings(l == null ? null : l.clone());
            eq.setBoots(b == null ? null : b.clone());
            Material hand = p.getInventory().getItemInMainHand().getType();
            if (!hand.isAir() && plugin.items().read(p.getInventory().getItemInMainHand()) == null) eq.setItemInMainHand(new ItemStack(hand));
            eq.setHelmetDropChance(0f);
            eq.setChestplateDropChance(0f);
            eq.setLeggingsDropChance(0f);
            eq.setBootsDropChance(0f);
            eq.setItemInMainHandDropChance(0f);
            a.setDisabledSlots(EquipmentSlot.HAND, EquipmentSlot.OFF_HAND, EquipmentSlot.HEAD,
                    EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET);
        });
        clones.add(as.getUniqueId());
        Fx.spawn(loc.clone().add(0, 1, 0), "poof", 12, 0.3, 0.6, 0.3, 0.03);
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            clones.remove(as.getUniqueId());
            if (as.isValid()) {
                Fx.spawn(as.getLocation().add(0, 1, 0), "poof", 12, 0.3, 0.6, 0.3, 0.03);
                as.remove();
            }
        }, (long) (seconds * 20));
        return as;
    }

    private boolean cloneSpell(Player p, Power pw) {
        int count = Math.max(1, Math.min(6, (int) pw.strength()));
        Location c = p.getLocation();
        for (int i = 0; i < count; i++) {
            double a = 2 * Math.PI * i / count + rr(-0.3, 0.3);
            spawnClone(p, ground(c.clone().add(Math.cos(a) * 2.5, 0, Math.sin(a) * 2.5)), pw.duration());
        }
        Fx.sound(c, pw.sound(), 1f, 1.4f);
        Fx.ring(c.clone().add(0, 0.2, 0), 2.5, 24, "witch");
        return true;
    }

    private boolean phantomTeleport(Player p, Power pw) {
        Location from = p.getLocation();
        for (int attempt = 0; attempt < 12; attempt++) {
            double a = rr(0, 2 * Math.PI), d = rr(4, Math.max(5, pw.range()));
            Location g = ground(from.clone().add(Math.cos(a) * d, 0, Math.sin(a) * d));
            Block b1 = g.getBlock(), b2 = g.clone().add(0, 1, 0).getBlock();
            if (!b1.isPassable() || !b2.isPassable() || b1.isLiquid()) continue;
            g.setX(Math.floor(g.getX()) + 0.5);
            g.setZ(Math.floor(g.getZ()) + 0.5);
            g.setYaw(from.getYaw());
            g.setPitch(from.getPitch());
            spawnClone(p, from.clone(), pw.duration());
            Fx.spawn(from.clone().add(0, 1, 0), "witch", 30, 0.4, 0.8, 0.4, 0.1);
            p.teleport(g);
            noFall.put(p.getUniqueId(), System.currentTimeMillis() + 4000);
            Fx.spawn(g.clone().add(0, 1, 0), "witch", 30, 0.4, 0.8, 0.4, 0.1);
            Fx.spawn(g.clone().add(0, 1, 0), "end_rod", 15, 0.3, 0.6, 0.3, 0.05);
            Fx.sound(from, pw.sound(), 1f, 1.5f);
            Fx.sound(g, "entity.enderman.teleport", 1f, 1.5f);
            return true;
        }
        p.sendActionBar(Txt.c("&cAucun endroit libre."));
        return false;
    }

    private boolean confusion(Player p, Power pw) {
        final int ticks = (int) (pw.duration() * 20);
        Fx.sound(p.getLocation(), pw.sound(), 1.2f, 1.6f);
        new BukkitRunnable() {
            int t = 0;

            @Override
            public void run() {
                if (!p.isOnline() || t >= ticks) { cancel(); return; }
                Location c = p.getLocation();
                Fx.ring(c.clone().add(0, 0.2, 0), pw.range(), 30, "witch");
                for (LivingEntity e : enemies(p, c, pw.range())) {
                    potion(e, PotionEffectType.NAUSEA, 6, 0);
                    potion(e, PotionEffectType.SLOWNESS, 1.5, 0);
                    Fx.spawn(e.getLocation().add(0, 2, 0), "end_rod", 3, 0.3, 0.1, 0.3, 0.02);
                    if (e instanceof Player t2) {
                        t2.setRotation(t2.getLocation().getYaw() + (float) rr(-90, 90), t2.getLocation().getPitch());
                    } else if (e instanceof Mob m) {
                        m.setTarget(null);
                        m.getPathfinder().moveTo(e.getLocation().add(rr(-5, 5), 0, rr(-5, 5)), 1.0);
                    }
                }
                t += 10;
            }
        }.runTaskTimer(plugin, 0L, 10L);
        return true;
    }

    // ------------------------------------------------------------------ GRAVITAS

    private boolean levitateZone(Player p, Power pw) {
        Location c = p.getLocation();
        for (LivingEntity e : enemies(p, c, pw.range())) {
            potion(e, PotionEffectType.LEVITATION, pw.duration(), (int) pw.strength());
            Fx.spawn(e.getLocation(), "reverse_portal", 20, 0.3, 0.1, 0.3, 0.1);
        }
        expandingRing(c.clone().add(0, 0.2, 0), pw.range(), 10, "reverse_portal", Element.GRAVITY.color);
        Fx.sound(c, "block.beacon.power_select", 1.5f, 0.5f);
        return true;
    }

    private boolean gravityCrush(Player p, Power pw) {
        Location c = p.getLocation();
        for (LivingEntity e : enemies(p, c, pw.range())) {
            double extra = e.isOnGround() ? 1.0 : 1.3;
            e.setVelocity(new Vector(0, -pw.strength(), 0));
            damage(p, e, pw.damage() * extra);
            potion(e, PotionEffectType.SLOWNESS, 2, 3);
            Fx.block(e.getLocation().add(0, 0.2, 0), Material.DEEPSLATE, 25, 0.5, 0.2, 0.5);
        }
        expandingRing(c.clone().add(0, 0.2, 0), pw.range(), 6, "crit", Element.GRAVITY.color);
        Fx.spawn(c.clone().add(0, 0.5, 0), "explosion", 1, 0, 0, 0, 0);
        Fx.sound(c, "block.anvil.land", 1.5f, 0.5f);
        Fx.sound(c, "entity.iron_golem.damage", 1.5f, 0.5f);
        return true;
    }

    private boolean lowGravity(Player p, Power pw) {
        potion(p, PotionEffectType.SLOW_FALLING, pw.duration(), 0);
        potion(p, PotionEffectType.JUMP_BOOST, pw.duration(), (int) pw.strength());
        noFall.put(p.getUniqueId(), System.currentTimeMillis() + (long) (pw.duration() * 1000) + 3000);
        Fx.ring(p.getLocation().add(0, 0.2, 0), 1.2, 20, "reverse_portal");
        Fx.sound(p.getLocation(), "block.beacon.power_select", 1f, 1.5f);
        return true;
    }

    // ------------------------------------------------------------------ VOID

    private boolean rift(Player p, Power pw) {
        Location dest = rayDest(p, 8);
        final Location c = (dest == null ? p.getLocation().add(p.getLocation().getDirection().setY(0).normalize().multiply(4))
                : dest).clone().add(0, 1.2, 0);
        final int ticks = (int) (pw.duration() * 20);
        Fx.sound(c, "entity.warden.sonic_boom", 1f, 0.6f);
        new BukkitRunnable() {
            int t = 0;

            @Override
            public void run() {
                if (t >= ticks) { cancel(); return; }
                for (int arm = 0; arm < 2; arm++) {
                    double a = t * 0.5 + arm * Math.PI;
                    for (double r = 0.4; r < 2.0; r += 0.5) {
                        Location l = c.clone().add(Math.cos(a + r) * r, Math.sin(a * 0.5 + r) * 0.8, Math.sin(a + r) * r);
                        Fx.spawn(l, "portal", 2, 0.05, 0.05, 0.05, 0.1);
                        Fx.dust(l, Color.fromRGB(20, 0, 30), 1.4f, 1, 0.05);
                    }
                }
                for (double y = -1.6; y <= 1.6; y += 0.4) Fx.dust(c.clone().add(rr(-0.15, 0.15), y, rr(-0.15, 0.15)), Color.fromRGB(0, 0, 0), 1.6f, 1, 0.05);
                if (t % 2 == 0) {
                    for (LivingEntity e : enemies(p, c, pw.range())) {
                        Vector pull = c.toVector().subtract(e.getLocation().add(0, 0.5, 0).toVector());
                        if (pull.lengthSquared() > 0.5) e.setVelocity(pull.normalize().multiply(pw.strength()));
                    }
                }
                if (t % 20 == 0) {
                    Fx.sound(c, "block.portal.ambient", 1f, 0.5f);
                    for (LivingEntity e : enemies(p, c, 2.5)) damage(p, e, pw.damage());
                }
                t++;
            }
        }.runTaskTimer(plugin, 0L, 1L);
        return true;
    }

    private boolean voidProjectile(Player p, Power pw) {
        final Location pos = p.getEyeLocation().add(p.getEyeLocation().getDirection().multiply(0.6));
        final Vector dir = p.getEyeLocation().getDirection().normalize();
        final double speed = Math.max(0.5, pw.strength());
        Fx.sound(pos, "entity.enderman.scream", 0.8f, 0.5f);
        new BukkitRunnable() {
            double travelled = 0;

            @Override
            public void run() {
                for (int i = 0; i < 2; i++) {
                    pos.add(dir.clone().multiply(speed / 2));
                    travelled += speed / 2;
                    Fx.dust(pos, Color.fromRGB(15, 0, 25), 2f, 3, 0.1);
                    Fx.dust(pos, Color.fromRGB(130, 30, 200), 1.2f, 2, 0.15);
                    Fx.spawn(pos, "portal", 3, 0.1, 0.1, 0.1, 0.3);
                    boolean hitEntity = false;
                    for (LivingEntity e : pos.getWorld().getNearbyLivingEntities(pos, 1.0)) {
                        if (isEnemy(p, e)) { hitEntity = true; break; }
                    }
                    if (hitEntity || !pos.getBlock().isPassable() || travelled >= pw.range() || !p.isOnline()) {
                        explode(pos.clone());
                        cancel();
                        return;
                    }
                }
            }

            private void explode(Location l) {
                Fx.spawn(l, "explosion", 1, 0, 0, 0, 0);
                Fx.spawn(l, "portal", 60, 0.8, 0.8, 0.8, 0.5);
                Fx.ringDust(l, 1.5, 24, Color.fromRGB(70, 0, 110), 1.8f);
                Fx.sound(l, "entity.generic.explode", 1f, 0.5f);
                Fx.sound(l, "entity.enderman.teleport", 1f, 0.4f);
                if (!p.isOnline()) return;
                for (LivingEntity e : enemies(p, l, 3.5)) {
                    damage(p, e, pw.damage());
                    potion(e, PotionEffectType.DARKNESS, 4, 0);
                    potion(e, PotionEffectType.SLOWNESS, 2, 1);
                }
            }
        }.runTaskTimer(plugin, 0L, 1L);
        return true;
    }

    private boolean voidTeleport(Player p, Power pw) {
        Location dest = rayDest(p, pw.range());
        if (dest == null) {
            p.sendActionBar(Txt.c("&cZone invalide."));
            return false;
        }
        Location from = p.getLocation();
        Fx.spawn(from.clone().add(0, 1, 0), "reverse_portal", 80, 0.4, 0.9, 0.4, 0.5);
        Fx.ringDust(from.clone().add(0, 0.1, 0), 1.2, 20, Color.fromRGB(20, 0, 30), 1.8f);
        p.teleport(dest);
        noFall.put(p.getUniqueId(), System.currentTimeMillis() + 5000);
        Fx.spawn(dest.clone().add(0, 1, 0), "portal", 80, 0.4, 0.9, 0.4, 0.5);
        Fx.ringDust(dest.clone().add(0, 0.1, 0), 1.2, 20, Color.fromRGB(70, 0, 110), 1.8f);
        Fx.sound(from, pw.sound(), 1f, 0.5f);
        Fx.sound(dest, pw.sound(), 1f, 0.5f);
        return true;
    }

    // ------------------------------------------------------------------ Événements

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        Action a = e.getAction();
        if (a != Action.RIGHT_CLICK_AIR && a != Action.RIGHT_CLICK_BLOCK) return;
        ItemStack it = e.getItem();
        DemonItem.Info info = plugin.items().read(it);
        if (info == null) return;
        Player p = e.getPlayer();
        if (a == Action.RIGHT_CLICK_BLOCK && e.getClickedBlock() != null
                && e.getClickedBlock().getType().isInteractable() && !p.isSneaking()) return;
        e.setCancelled(true);
        if (frozen.containsKey(p.getUniqueId())) return;
        Pact pact = plugin.pacts().validate(p, info);
        if (pact == null) {
            p.sendActionBar(Txt.c("&4Cet objet ne vous obéit pas."));
            Fx.sound(p.getLocation(), "entity.wither.hurt", 0.4f, 0.5f);
            return;
        }
        use(p, pact);
    }

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        if (frozen.isEmpty() || !frozen.containsKey(e.getPlayer().getUniqueId())) return;
        Location f = e.getFrom(), t = e.getTo();
        if (t == null) return;
        if (f.getX() != t.getX() || f.getY() != t.getY() || f.getZ() != t.getZ()) {
            e.setTo(new Location(f.getWorld(), f.getX(), f.getY(), f.getZ(), t.getYaw(), t.getPitch()));
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent e) {
        if (frozen.containsKey(e.getPlayer().getUniqueId())
                && (e.getCause() == PlayerTeleportEvent.TeleportCause.ENDER_PEARL
                || e.getCause() == PlayerTeleportEvent.TeleportCause.CHORUS_FRUIT)) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onLaunch(ProjectileLaunchEvent e) {
        if (e.getEntity().getShooter() instanceof Player p && frozen.containsKey(p.getUniqueId())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onFall(EntityDamageEvent e) {
        if (e.getCause() == EntityDamageEvent.DamageCause.FALL && noFall.containsKey(e.getEntity().getUniqueId())) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onHit(EntityDamageByEntityEvent e) {
        Entity victim = e.getEntity();
        Entity damager = e.getDamager();
        Player attacker = damager instanceof Player pl ? pl
                : (damager instanceof Projectile pr && pr.getShooter() instanceof Player sp ? sp : null);
        if (attacker != null && frozen.containsKey(attacker.getUniqueId())) {
            e.setCancelled(true);
            return;
        }
        if (victim instanceof ArmorStand as && isTemp(as)) {
            e.setCancelled(true);
            String owner = as.getPersistentDataContainer().get(ownerKey, PersistentDataType.STRING);
            Fx.spawn(as.getLocation().add(0, 1, 0), "poof", 20, 0.3, 0.6, 0.3, 0.05);
            Fx.sound(as.getLocation(), "entity.illusioner.mirror_move", 1f, 1.2f);
            clones.remove(as.getUniqueId());
            as.remove();
            if (attacker != null && (owner == null || !owner.equals(attacker.getUniqueId().toString()))) {
                potion(attacker, PotionEffectType.NAUSEA, 5, 0);
                potion(attacker, PotionEffectType.BLINDNESS, 1.5, 0);
            }
            return;
        }
        UUID so = servants.get(damager.getUniqueId());
        if (so != null && victim.getUniqueId().equals(so)) e.setCancelled(true);
        UUID vo = servants.get(victim.getUniqueId());
        if (vo != null && attacker != null && attacker.getUniqueId().equals(vo)) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onLifesteal(EntityDamageByEntityEvent e) {
        if (lifesteal.isEmpty()) return;
        Player p = e.getDamager() instanceof Player pl ? pl
                : (e.getDamager() instanceof Projectile pr && pr.getShooter() instanceof Player sp ? sp : null);
        if (p == null || !lifesteal.containsKey(p.getUniqueId()) || p.isDead()) return;
        Power pw = powers.get(PowerType.LIFESTEAL);
        if (pw == null) return;
        double heal = e.getFinalDamage() * pw.strength();
        double max = plugin.pacts().currentMax(p);
        p.setHealth(Math.min(max, p.getHealth() + heal));
        Fx.spawn(p.getLocation().add(0, 1.2, 0), "heart", 2, 0.3, 0.3, 0.3, 0);
        Fx.spawn(e.getEntity().getLocation().add(0, 1, 0), "damage_indicator", 4, 0.3, 0.4, 0.3, 0.05);
    }

    @EventHandler(ignoreCancelled = true)
    public void onTarget(EntityTargetLivingEntityEvent e) {
        UUID owner = servants.get(e.getEntity().getUniqueId());
        if (owner == null || e.getTarget() == null) return;
        if (e.getTarget().getUniqueId().equals(owner) || servants.containsKey(e.getTarget().getUniqueId())) e.setCancelled(true);
    }

    @EventHandler
    public void onDeath(EntityDeathEvent e) {
        if (servants.remove(e.getEntity().getUniqueId()) != null) {
            e.getDrops().clear();
            e.setDroppedExp(0);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        for (Map.Entry<UUID, UUID> en : new ArrayList<>(servants.entrySet())) {
            if (en.getValue().equals(id)) {
                Entity s = Bukkit.getEntity(en.getKey());
                if (s != null) s.remove();
                servants.remove(en.getKey());
            }
        }
    }

    @EventHandler
    public void onStandInteract(PlayerInteractAtEntityEvent e) {
        if (e.getRightClicked() instanceof ArmorStand as && isTemp(as)) e.setCancelled(true);
    }

    @EventHandler
    public void onStandManipulate(PlayerArmorStandManipulateEvent e) {
        if (isTemp(e.getRightClicked())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onWallBreak(BlockBreakEvent e) {
        if (walls.contains(wallKey(e.getBlock()) + "," + e.getBlock().getType().name())) e.setDropItems(false);
    }

    @EventHandler(ignoreCancelled = true)
    public void onWallExplode(EntityExplodeEvent e) {
        e.blockList().removeIf(b -> walls.contains(wallKey(b) + "," + b.getType().name()));
    }

    @EventHandler(ignoreCancelled = true)
    public void onWallBlockExplode(BlockExplodeEvent e) {
        e.blockList().removeIf(b -> walls.contains(wallKey(b) + "," + b.getType().name()));
    }

    @EventHandler(ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent e) {
        for (Block b : e.getBlocks()) {
            if (walls.contains(wallKey(b) + "," + b.getType().name())) { e.setCancelled(true); return; }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent e) {
        for (Block b : e.getBlocks()) {
            if (walls.contains(wallKey(b) + "," + b.getType().name())) { e.setCancelled(true); return; }
        }
    }
}
