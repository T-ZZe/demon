package fr.demonpact;

import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Ageable;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * La Demon Stell : structure, runes, animation d'attente, animation d'invocation propre à chaque élément,
 * apparition et renvoi du démon.
 */
public final class DemonSpawner {

    /** Une invocation en cours (animation, démon présent, négociation). */
    public static final class Session {
        public final Demon demon;
        public UUID target;
        public final Set<UUID> offered = new HashSet<>();
        public ContractManager.Offer offer;
        public LivingEntity entity;
        public boolean animating;
        public boolean active;
        public boolean ending;

        Session(Demon demon) { this.demon = demon; }
    }

    private static final Material RUNE_DIM = Material.OBSIDIAN;
    private static final Material RUNE_LIT = Material.CRYING_OBSIDIAN;
    private static final Material[] FLOOR = {
            Material.BLACKSTONE, Material.POLISHED_BLACKSTONE, Material.CRACKED_POLISHED_BLACKSTONE_BRICKS,
            Material.BLACKSTONE, Material.POLISHED_BLACKSTONE_BRICKS
    };

    private final DemonPactPlugin plugin;
    private final NamespacedKey demonKey;
    private final Random rnd = new Random();
    private final List<int[]> runes = new ArrayList<>();
    private Location center;
    private int lit;
    private Session session;
    private BukkitTask idleTask;
    private int idleTick;

    public DemonSpawner(DemonPactPlugin plugin) {
        this.plugin = plugin;
        this.demonKey = new NamespacedKey(plugin, "demon_entity");
    }

    public NamespacedKey demonKey() { return demonKey; }

    public Location center() { return center == null ? null : center.clone(); }

    public Session session() { return session; }

    public int radius() { return Math.max(5, Math.min(16, plugin.getConfig().getInt("stell.radius", 8))); }

    // ------------------------------------------------------------------ Chargement / structure

    public void load() {
        FileConfiguration c = plugin.getConfig();
        center = null;
        String w = c.getString("stell.world", "");
        if (w != null && !w.isBlank()) {
            World world = Bukkit.getWorld(w);
            if (world != null) {
                center = new Location(world, c.getInt("stell.x"), c.getInt("stell.y"), c.getInt("stell.z"));
            } else {
                plugin.getLogger().warning("Monde de la Demon Stell introuvable : " + w);
            }
        }
        computeRunes();
        startIdle();
    }

    /** Définit la position de l'autel (niveau des pieds) ; construit la structure si configuré. */
    public void setCenter(Location l, boolean build) {
        if (session != null) dismissNow();
        center = new Location(l.getWorld(), l.getBlockX(), l.getBlockY(), l.getBlockZ());
        FileConfiguration c = plugin.getConfig();
        c.set("stell.world", l.getWorld().getName());
        c.set("stell.x", l.getBlockX());
        c.set("stell.y", l.getBlockY());
        c.set("stell.z", l.getBlockZ());
        plugin.saveConfig();
        computeRunes();
        if (build) build();
    }

    private void computeRunes() {
        runes.clear();
        int radius = radius();
        Set<Long> seen = new HashSet<>();
        List<int[]> list = new ArrayList<>();
        addRing(list, seen, 3.0);
        addRing(list, seen, radius - 2.0);
        for (int k = 0; k < 8; k++) {
            double a = k * Math.PI / 4;
            for (double r = 1.5; r <= radius - 2.5; r += 1.0) {
                addRune(list, seen, (int) Math.round(Math.cos(a) * r), (int) Math.round(Math.sin(a) * r));
            }
        }
        list.sort((p, q) -> {
            int c = Double.compare(Math.atan2(p[1], p[0]), Math.atan2(q[1], q[0]));
            return c != 0 ? c : Double.compare(Math.hypot(p[0], p[1]), Math.hypot(q[0], q[1]));
        });
        runes.addAll(list);
        lit = 0;
    }

    private void addRing(List<int[]> list, Set<Long> seen, double r) {
        int n = (int) Math.ceil(2 * Math.PI * r * 1.3);
        for (int i = 0; i < n; i++) {
            double a = 2 * Math.PI * i / n;
            addRune(list, seen, (int) Math.round(Math.cos(a) * r), (int) Math.round(Math.sin(a) * r));
        }
    }

    private void addRune(List<int[]> list, Set<Long> seen, int dx, int dz) {
        if (Math.abs(dx) <= 1 && Math.abs(dz) <= 1) return;
        long key = ((long) dx << 32) ^ (dz & 0xffffffffL);
        if (seen.add(key)) list.add(new int[]{dx, dz});
    }

    private Block runeBlock(int[] o) {
        return center.getWorld().getBlockAt(center.getBlockX() + o[0], center.getBlockY() - 1, center.getBlockZ() + o[1]);
    }

    /** Construit l'autel : plateforme circulaire en pierre noire, runes, piliers et lanternes d'âmes. */
    public void build() {
        if (center == null || center.getWorld() == null) return;
        World w = center.getWorld();
        int cx = center.getBlockX(), cy = center.getBlockY(), cz = center.getBlockZ();
        int radius = radius();
        int clearH = Math.max(7, plugin.getConfig().getInt("stell.clear-height", 8));
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                int d2 = dx * dx + dz * dz;
                if (d2 > radius * radius) continue;
                Material floor;
                if (dx == 0 && dz == 0) floor = Material.GILDED_BLACKSTONE;
                else if (Math.abs(dx) <= 1 && Math.abs(dz) <= 1) floor = Material.POLISHED_BLACKSTONE;
                else if (d2 > (radius - 1) * (radius - 1)) floor = Material.POLISHED_BLACKSTONE_BRICKS;
                else floor = FLOOR[Math.floorMod(dx * 31 + dz * 17, FLOOR.length)];
                w.getBlockAt(cx + dx, cy - 1, cz + dz).setType(floor, false);
                for (int y = 0; y < clearH; y++) {
                    Block b = w.getBlockAt(cx + dx, cy + y, cz + dz);
                    if (!b.getType().isAir()) b.setType(Material.AIR, false);
                }
                for (int y = 2; y <= 5; y++) {
                    Block b = w.getBlockAt(cx + dx, cy - y, cz + dz);
                    if (b.getType().isAir() || b.isLiquid() || b.isPassable()) b.setType(Material.BLACKSTONE, false);
                }
            }
        }
        for (int[] o : runes) runeBlock(o).setType(RUNE_DIM, false);
        int pr = radius - 3;
        for (int sx = -1; sx <= 1; sx += 2) {
            for (int sz = -1; sz <= 1; sz += 2) {
                int px = cx + sx * pr, pz = cz + sz * pr;
                w.getBlockAt(px, cy, pz).setType(Material.CHISELED_POLISHED_BLACKSTONE, false);
                for (int y = 1; y <= 3; y++) w.getBlockAt(px, cy + y, pz).setType(Material.POLISHED_BLACKSTONE_BRICKS, false);
                w.getBlockAt(px, cy + 4, pz).setType(Material.SOUL_LANTERN, false);
            }
        }
        lit = 0;
    }

    /** Vrai si la position se trouve sur l'autel (protection contre la destruction). */
    public boolean isInStell(Location l) {
        if (center == null || l.getWorld() == null || !l.getWorld().equals(center.getWorld())) return false;
        int r = radius() + 1;
        int dx = l.getBlockX() - center.getBlockX(), dz = l.getBlockZ() - center.getBlockZ();
        int dy = l.getBlockY() - center.getBlockY();
        return dx * dx + dz * dz <= r * r && dy >= -6 && dy <= 9;
    }

    // ------------------------------------------------------------------ Invocation

    public boolean isDemonEntity(Entity e) {
        return e != null && e.getPersistentDataContainer().has(demonKey, PersistentDataType.STRING);
    }

    /** Joueur éligible au hasard (permission, capacité de pacte, proximité de l'autel). */
    public Player randomCandidate(Set<UUID> exclude) {
        if (center == null || center.getWorld() == null) return null;
        double radius = plugin.getConfig().getDouble("stell.target-radius", 40);
        List<Player> list = new ArrayList<>();
        for (Player p : center.getWorld().getPlayers()) {
            if (exclude != null && exclude.contains(p.getUniqueId())) continue;
            if (!p.hasPermission("demon.use") || p.getGameMode() == GameMode.SPECTATOR) continue;
            if (!plugin.pacts().hasCapacity(p.getUniqueId())) continue;
            if (radius > 0 && p.getLocation().distanceSquared(center) > radius * radius) continue;
            list.add(p);
        }
        return list.isEmpty() ? null : list.get(rnd.nextInt(list.size()));
    }

    /** Lance l'invocation. Retourne un message d'erreur, ou null en cas de succès. */
    public String summon(Demon d, Player explicitTarget) {
        if (center == null || center.getWorld() == null) return "La Demon Stell n'est pas définie. Utilisez /demon setstell.";
        if (session != null) return "Un démon est déjà présent sur la Demon Stell.";
        Player target = explicitTarget;
        if (target != null) {
            if (!plugin.pacts().hasCapacity(target.getUniqueId())) return target.getName() + " possède déjà le maximum de pactes.";
        } else {
            target = randomCandidate(null);
            if (target == null) return "Aucun joueur éligible près de la Demon Stell.";
        }
        Session s = new Session(d);
        s.target = target.getUniqueId();
        session = s;
        startAnimation(s);
        return null;
    }

    private List<Player> playersNear(Location l, double radius) {
        List<Player> out = new ArrayList<>();
        if (l.getWorld() == null) return out;
        for (Player p : l.getWorld().getPlayers()) {
            if (p.getLocation().distanceSquared(l) <= radius * radius) out.add(p);
        }
        return out;
    }

    private void startAnimation(Session s) {
        s.animating = true;
        final int dur = Math.max(40, plugin.getConfig().getInt("stell.animation-ticks", 100));
        final Element el = s.demon.element();
        final World w = center.getWorld();
        final Location base = center.clone().add(0.5, 0, 0.5);
        final int radius = radius();
        final boolean thunder = plugin.getConfig().getBoolean("stell.lightning-during-summon", true);
        double chat = plugin.getConfig().getDouble("negotiation.chat-radius", 60);
        for (Player p : playersNear(base, chat)) {
            p.sendMessage(Txt.c("&4&l⚠ &cLes runes de la Demon Stell s'embrasent... &7Quelque chose approche."));
        }
        new BukkitRunnable() {
            int t = 0;

            @Override
            public void run() {
                if (session != s || s.ending) { cancel(); return; }
                double prog = (double) t / dur;
                if (t == 0) {
                    Fx.sound(base, "block.beacon.activate", 2f, 0.5f);
                    Fx.sound(base, "entity.elder_guardian.curse", 1.5f, 0.6f);
                }
                if (t % 20 == 10) Fx.sound(base, "block.respawn_anchor.charge", 1.5f, 0.5f + (float) prog);
                if (t % 25 == 0) Fx.sound(base, el.sound, 1.5f, 0.6f);
                int target = (int) (runes.size() * Math.min(1.0, prog / 0.75));
                while (lit < target) lightRune(lit++, base, el);
                if (t % 3 == 0) floorWave(base, prog * radius, el);
                swirl(base, t, dur);
                elementPattern(el, base, t, dur, radius, prog);
                if (thunder && (t == (int) (dur * 0.55) || t == (int) (dur * 0.75) || t == (int) (dur * 0.9))) {
                    w.strikeLightningEffect(base);
                    if (!runes.isEmpty()) {
                        int[] o = runes.get(rnd.nextInt(runes.size()));
                        w.strikeLightningEffect(base.clone().add(o[0], 0, o[1]));
                    }
                }
                t++;
                if (t >= dur) {
                    cancel();
                    finishSummon(s);
                }
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }

    private void lightRune(int i, Location base, Element el) {
        if (i < 0 || i >= runes.size()) return;
        int[] o = runes.get(i);
        runeBlock(o).setType(RUNE_LIT, false);
        Location l = base.clone().add(o[0], 0.25, o[1]);
        Fx.dust(l, el.color, 1.5f, 6, 0.2);
        Fx.spawn(l, "soul_fire_flame", 2, 0.1, 0.2, 0.1, 0.01);
        if (i % 6 == 0) Fx.sound(l, "block.amethyst_block.chime", 0.7f, 0.5f);
    }

    /** Anneau de blocs d'aspect différent qui s'étend depuis le centre (effet purement visuel côté client). */
    private void floorWave(Location base, double radius, Element el) {
        if (radius < 1) return;
        List<Player> near = playersNear(base, 48);
        if (near.isEmpty()) return;
        World w = base.getWorld();
        Set<Block> blocks = new LinkedHashSet<>();
        int n = (int) Math.max(8, radius * 7);
        for (int i = 0; i < n; i++) {
            double a = 2 * Math.PI * i / n;
            Block b = w.getBlockAt((int) Math.floor(base.getX() + Math.cos(a) * radius),
                    center.getBlockY() - 1, (int) Math.floor(base.getZ() + Math.sin(a) * radius));
            if (!b.getType().isAir() && b.getType() != RUNE_LIT) blocks.add(b);
        }
        BlockData fx = el.floorFx.createBlockData();
        for (Block b : blocks) for (Player p : near) p.sendBlockChange(b.getLocation(), fx);
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            for (Block b : blocks) {
                BlockData real = b.getBlockData();
                for (Player p : near) if (p.isOnline()) p.sendBlockChange(b.getLocation(), real);
            }
        }, 6L);
    }

    /** Spirale rouge / violette / noire commune à toutes les invocations. */
    private void swirl(Location base, int t, int dur) {
        double prog = (double) t / dur;
        double r = Math.max(0.5, 4.0 * (1 - prog));
        Color[] cols = {Color.fromRGB(190, 10, 20), Color.fromRGB(130, 30, 200), Color.fromRGB(12, 0, 16)};
        for (int arm = 0; arm < 3; arm++) {
            double a = t * 0.35 + arm * 2 * Math.PI / 3;
            double h = 0.2 + ((t * 0.12) + arm) % 3.0;
            Location l = base.clone().add(Math.cos(a) * r, h, Math.sin(a) * r);
            Fx.dust(l, cols[arm], 1.6f, 2, 0.05);
        }
        Fx.spawn(base.clone().add(0, prog * 3, 0), "portal", 6, 0.3, 0.5, 0.3, 0.4);
    }

    private double rr(double a, double b) { return a + rnd.nextDouble() * (b - a); }

    /** Animation d'invocation propre à l'élément du démon. */
    private void elementPattern(Element el, Location base, int t, int dur, int radius, double prog) {
        World w = base.getWorld();
        switch (el) {
            case WIND -> {
                for (int i = 0; i < 4; i++) {
                    double a = t * 0.6 + i * Math.PI / 2;
                    double h = (t * 0.08 + i * 0.7) % 3.5;
                    double r = 0.6 + h * 0.7;
                    Fx.spawn(base.clone().add(Math.cos(a) * r, h, Math.sin(a) * r), "cloud", 1, 0.02, 0.02, 0.02, 0.01);
                }
                if (t % 10 == 0) Fx.ring(base.clone().add(0, 0.3, 0), 1 + prog * (radius - 2), 28, "poof");
            }
            case FIRE -> {
                Fx.spawn(base.clone().add(0, 0.2, 0), "flame", 10, 0.4, 0.1, 0.4, 0.12);
                Fx.spawn(base.clone().add(0, prog * 3, 0), "lava", 2, 0.3, 0.3, 0.3, 0);
                if (t % 4 == 0) Fx.ring(base.clone().add(0, 0.2, 0), 1 + prog * (radius - 2), 30, "flame");
            }
            case LIGHTNING -> {
                for (int i = 0; i < 3; i++) {
                    double a = rr(0, 2 * Math.PI), r = rr(0, radius * 0.8);
                    Fx.spawn(base.clone().add(Math.cos(a) * r, 0.2, Math.sin(a) * r), "electric_spark", 6, 0.1, 1.5, 0.1, 0.3);
                }
                if (t % 14 == 7 && !runes.isEmpty()) {
                    int[] o = runes.get(rnd.nextInt(runes.size()));
                    w.strikeLightningEffect(base.clone().add(o[0], 0, o[1]));
                }
            }
            case TIME -> {
                double a1 = -t * 0.16, a2 = -t * 0.02;
                for (double r = 0.5; r < radius - 2; r += 0.6) {
                    Fx.spawn(base.clone().add(Math.cos(a1) * r, 0.15, Math.sin(a1) * r), "end_rod", 1, 0, 0, 0, 0);
                    if (r < (radius - 2) * 0.65) {
                        Fx.spawn(base.clone().add(Math.cos(a2) * r, 0.15, Math.sin(a2) * r), "end_rod", 1, 0, 0, 0, 0);
                    }
                }
                if (t % 5 == 0) Fx.ring(base.clone().add(0, 0.1, 0), radius - 2.5, 12, "enchant");
            }
            case SHADOW -> {
                double r = radius * (1 - prog) + 0.6;
                for (int i = 0; i < 10; i++) {
                    double a = rr(0, 2 * Math.PI);
                    Location l = base.clone().add(Math.cos(a) * r, rr(0.1, 2), Math.sin(a) * r);
                    Fx.spawn(l, "squid_ink", 1, 0, 0, 0, 0);
                    Fx.spawn(l, "smoke", 1, 0.05, 0.05, 0.05, 0.01);
                }
            }
            case BLOOD -> {
                for (int i = 0; i < 4; i++) {
                    double a = rr(0, 2 * Math.PI), r = rr(0.5, radius - 2);
                    Location l = base.clone().add(Math.cos(a) * r, rr(0.1, 2.5), Math.sin(a) * r);
                    Fx.dust(l, Color.fromRGB(170, 0, 20), 1.5f, 2, 0.1);
                    Fx.spawn(l, "crimson_spore", 2, 0.1, 0.1, 0.1, 0);
                }
                if (t % 8 == 0) Fx.spawn(base.clone().add(0, 1, 0), "damage_indicator", 6, 0.4, 0.5, 0.4, 0.05);
            }
            case EARTH -> {
                if (t % 4 == 0) {
                    for (int i = 0; i < 3; i++) {
                        double a = rr(0, 2 * Math.PI), r = rr(1, radius - 2);
                        Fx.block(base.clone().add(Math.cos(a) * r, 0.2, Math.sin(a) * r), Material.STONE, 14, 0.3, 0.4, 0.3);
                    }
                    Fx.sound(base, "block.stone.break", 1f, 0.5f);
                }
                Fx.spawn(base.clone().add(0, 0.2, 0), "poof", 3, 0.6, 0.1, 0.6, 0.02);
            }
            case WATER -> {
                for (int i = 0; i < 5; i++) {
                    double a = t * 0.45 + i * 2 * Math.PI / 5;
                    double r = 1.0 + ((t + i * 9) % 40) * 0.07;
                    double h = ((t + i * 7) % 30) * 0.1;
                    Location l = base.clone().add(Math.cos(a) * r, h, Math.sin(a) * r);
                    Fx.spawn(l, "splash", 2, 0.05, 0.05, 0.05, 0.02);
                    Fx.spawn(l, "bubble_pop", 1, 0.05, 0.05, 0.05, 0.01);
                }
            }
            case DEATH -> {
                if (lit > 0) {
                    for (int i = 0; i < 3; i++) {
                        int[] o = runes.get(rnd.nextInt(lit));
                        Fx.spawn(base.clone().add(o[0], 0.3, o[1]), "soul", 1, 0.05, 0.5, 0.05, 0.03);
                    }
                }
                Fx.spawn(base.clone().add(0, 1, 0), "sculk_soul", 2, 0.3, 0.8, 0.3, 0.02);
            }
            case ILLUSION -> {
                for (int i = 0; i < 3; i++) {
                    double a = rr(0, 2 * Math.PI), r = rr(0.5, radius - 2);
                    Fx.spawn(base.clone().add(Math.cos(a) * r, rr(0.2, 2.5), Math.sin(a) * r), "witch", 3, 0.2, 0.2, 0.2, 0);
                }
                if (t % 6 == 0) Fx.ring(base.clone().add(rr(-1, 1), rr(0.2, 2), rr(-1, 1)), rr(1, radius - 2), 20, "end_rod");
            }
            case GRAVITY -> {
                for (int i = 0; i < 8; i++) {
                    double a = rr(0, 2 * Math.PI), r = rr(0, radius - 2);
                    double y = 6 * (1 - prog) * rnd.nextDouble() + 0.2;
                    Fx.spawn(base.clone().add(Math.cos(a) * r, y, Math.sin(a) * r), "reverse_portal", 1, 0, -0.2, 0, 0.05);
                }
                if (t % 6 == 0) Fx.ringDust(base.clone().add(0, 0.3, 0), (radius - 2) * (1 - prog) + 0.5, 24, Element.GRAVITY.color, 1.5f);
            }
            case VOID -> {
                double r = 3 * (1 - prog) + 0.4;
                for (int i = 0; i < 6; i++) {
                    double a = t * 0.5 + i * Math.PI / 3;
                    Location l = base.clone().add(Math.cos(a) * r, 0.3 + (t % 20) * 0.1, Math.sin(a) * r);
                    Fx.spawn(l, "portal", 3, 0.05, 0.05, 0.05, 0.2);
                    Fx.dust(l, Color.fromRGB(20, 0, 30), 1.6f, 1, 0.05);
                }
                if (t == dur - 2) Fx.spawn(base.clone().add(0, 1, 0), "sonic_boom", 1, 0, 0, 0, 0);
            }
        }
    }

    private void finishSummon(Session s) {
        if (session != s || s.ending) return;
        s.animating = false;
        Demon d = s.demon;
        Location spawn = center.clone().add(0.5, d.hover() ? 1.0 : 0.0, 0.5);
        LivingEntity ent = spawnEntity(d, spawn);
        if (ent == null) {
            plugin.getLogger().severe("Impossible de faire apparaître le démon " + d.id());
            dismiss(s, "error");
            return;
        }
        s.entity = ent;
        s.active = true;
        Location eye = spawn.clone().add(0, 1, 0);
        Fx.spawn(eye, "explosion_emitter", 1, 0, 0, 0, 0);
        Fx.spawn(eye, d.element().particle, 80, 1, 1, 1, 0.3);
        Fx.sound(spawn, "entity.wither.spawn", 2f, 0.6f);
        Fx.sound(spawn, "entity.ender_dragon.growl", 1.5f, 0.8f);
        Player target = Bukkit.getPlayer(s.target);
        DemonManager dm = plugin.demons();
        boolean global = plugin.getConfig().getBoolean("negotiation.announce-global", true);
        if (global) {
            Bukkit.broadcast(Txt.c("&4&l⚠ " + d.tag() + " &7(" + d.color() + d.title() + "&7) &cest apparu sur la Demon Stell !"));
        }
        dm.say(d, dm.format(d.line("appear"), target, null, d), target, global);
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (session != s || s.ending) return;
            Player tp = Bukkit.getPlayer(s.target);
            if (tp != null) plugin.contracts().startOffer(s, tp);
            else dismiss(s, "absent");
        }, 70L);
    }

    private LivingEntity spawnEntity(Demon d, Location loc) {
        EntityType type = null;
        try {
            type = Registry.ENTITY_TYPE.get(NamespacedKey.minecraft(d.entity().toLowerCase()));
        } catch (Exception ignored) {
            // type invalide : repli sur zombie
        }
        if (type == null || !type.isAlive() || type == EntityType.PLAYER
                || type == EntityType.ENDER_DRAGON || type == EntityType.WITHER) {
            type = EntityType.ZOMBIE;
        }
        Entity e = loc.getWorld().spawnEntity(loc, type);
        if (!(e instanceof LivingEntity le)) {
            e.remove();
            return null;
        }
        le.customName(Txt.c(d.tag() + " &8| &7" + d.title()));
        le.setCustomNameVisible(true);
        le.setInvulnerable(true);
        le.setPersistent(false);
        le.setRemoveWhenFarAway(false);
        le.setSilent(true);
        le.setCollidable(false);
        le.setGravity(!d.hover());
        le.setGlowing(true);
        if (le instanceof Mob m) m.setAI(false);
        if (le instanceof Ageable a) a.setAdult();
        le.addPotionEffect(new PotionEffect(PotionEffectType.FIRE_RESISTANCE, PotionEffect.INFINITE_DURATION, 0, false, false));
        AttributeInstance scale = le.getAttribute(Attribute.SCALE);
        if (scale != null) scale.setBaseValue(Math.max(0.3, Math.min(4.0, d.scale())));
        EntityEquipment eq = le.getEquipment();
        if (eq != null && d.helmet() != null) {
            eq.setHelmet(new ItemStack(d.helmet()));
            eq.setHelmetDropChance(0f);
        }
        le.getPersistentDataContainer().set(demonKey, PersistentDataType.STRING, d.id());
        try {
            Scoreboard sb = Bukkit.getScoreboardManager().getMainScoreboard();
            Team team = sb.getTeam("dp_" + d.id());
            if (team == null) team = sb.registerNewTeam("dp_" + d.id());
            NamedTextColor nc = NamedTextColor.NAMES.value(d.glow().toLowerCase());
            team.color(nc == null ? NamedTextColor.RED : nc);
            team.addEntry(le.getUniqueId().toString());
        } catch (Exception ex) {
            plugin.getLogger().warning("Équipe de couleur impossible : " + ex.getMessage());
        }
        return le;
    }

    // ------------------------------------------------------------------ Renvoi

    public boolean despawn() {
        if (session == null) return false;
        dismiss(session, "admin");
        return true;
    }

    /** Fait disparaître le démon avec une animation puis libère l'autel. */
    public void dismiss(Session s, String reason) {
        if (s == null || session != s || s.ending) return;
        s.ending = true;
        s.animating = false;
        plugin.contracts().cancelSession(s);
        final LivingEntity ent = s.entity;
        final Element el = s.demon.element();
        if (ent != null && ent.isValid()) Fx.sound(ent.getLocation(), "entity.enderman.teleport", 1.5f, 0.5f);
        new BukkitRunnable() {
            int t = 0;

            @Override
            public void run() {
                if (center == null || center.getWorld() == null) { cancel(); finishDismiss(s); return; }
                int per = Math.max(1, runes.size() / 30 + 1);
                for (int k = 0; k < per && lit > 0; k++) {
                    lit--;
                    runeBlock(runes.get(lit)).setType(RUNE_DIM, false);
                }
                if (ent != null && ent.isValid()) {
                    Location l = ent.getLocation().add(0, ent.getHeight() / 2, 0);
                    Fx.spawn(l, el.particle, 10, 0.4, 0.6, 0.4, 0.05);
                    Fx.dust(l, el.color, 1.5f, 4, 0.4);
                }
                t++;
                if (t >= 30) {
                    cancel();
                    finishDismiss(s);
                }
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }

    private void finishDismiss(Session s) {
        LivingEntity ent = s.entity;
        if (ent != null) {
            if (ent.isValid()) {
                Fx.spawn(ent.getLocation().add(0, ent.getHeight() / 2, 0), "large_smoke", 40, 0.5, 0.8, 0.5, 0.05);
                Fx.sound(ent.getLocation(), "entity.generic.explode", 0.8f, 0.6f);
            }
            removeFromTeam(s.demon, ent);
            ent.remove();
        }
        if (center != null && center.getWorld() != null) {
            while (lit > 0) {
                lit--;
                runeBlock(runes.get(lit)).setType(RUNE_DIM, false);
            }
        }
        if (session == s) session = null;
    }

    private void removeFromTeam(Demon d, Entity e) {
        try {
            Team t = Bukkit.getScoreboardManager().getMainScoreboard().getTeam("dp_" + d.id());
            if (t != null) t.removeEntry(e.getUniqueId().toString());
        } catch (Exception ignored) {
            // sans importance
        }
    }

    private void dismissNow() {
        Session s = session;
        if (s == null) return;
        s.ending = true;
        plugin.contracts().cancelSession(s);
        finishDismiss(s);
    }

    /** Supprime les démons restés dans le monde (crash, rechargement). */
    public void purgeStray() {
        for (World w : Bukkit.getWorlds()) {
            for (Entity e : w.getEntities()) {
                if (isDemonEntity(e) && (session == null || session.entity == null || !session.entity.equals(e))) e.remove();
            }
        }
    }

    public void shutdown() {
        if (idleTask != null) idleTask.cancel();
        idleTask = null;
        dismissNow();
    }

    // ------------------------------------------------------------------ Animation d'attente

    private void startIdle() {
        if (idleTask != null) idleTask.cancel();
        idleTask = null;
        if (!plugin.getConfig().getBoolean("stell.idle-animation", true)) return;
        idleTask = new BukkitRunnable() {
            @Override
            public void run() {
                try {
                    idleStep();
                } catch (Exception ex) {
                    plugin.getLogger().warning("Animation d'attente : " + ex);
                }
            }
        }.runTaskTimer(plugin, 20L, 4L);
    }

    private void idleStep() {
        if (center == null || center.getWorld() == null) return;
        World w = center.getWorld();
        if (!w.isChunkLoaded(center.getBlockX() >> 4, center.getBlockZ() >> 4)) return;
        Session s = session;
        if (s != null && (s.animating || s.ending)) return;
        double view = plugin.getConfig().getDouble("stell.idle-view-distance", 64);
        List<Player> near = playersNear(center, view);
        if (near.isEmpty()) return;
        idleTick++;
        boolean busy = s != null && s.active;
        Location base = center.clone().add(0.5, 0, 0.5);
        double ang = idleTick * 0.22;
        Color[] cols = {Color.fromRGB(190, 10, 20), Color.fromRGB(130, 30, 200), Color.fromRGB(12, 0, 16)};
        for (int i = 0; i < 3; i++) {
            double a = ang + i * 2 * Math.PI / 3;
            double r = (busy ? 2.6 : 2.2) + Math.sin(idleTick * 0.1 + i) * 0.3;
            double y = ((idleTick * 0.07) + i * 0.6) % 2.4;
            Fx.dust(base.clone().add(Math.cos(a) * r, 0.15 + y, Math.sin(a) * r), cols[i], 1.4f, 2, 0.04);
        }
        if (idleTick % 3 == 0 && !runes.isEmpty()) {
            int[] o = runes.get(rnd.nextInt(runes.size()));
            Location rl = base.clone().add(o[0], 0.1, o[1]);
            Fx.spawn(rl, "enchant", 3, 0.1, 0.4, 0.1, 0.05);
            if (rnd.nextInt(6) == 0) Fx.spawn(rl.add(0, 0.2, 0), "soul_fire_flame", 1, 0.05, 0.1, 0.05, 0.01);
        }
        if (idleTick % 15 == 0 && lit == 0) flicker(near, base);
        if (idleTick % 80 == 0) {
            for (Player p : near) {
                if (p.getLocation().distanceSquared(center) <= 24 * 24) {
                    p.playSound(p.getLocation(), busy ? "entity.warden.heartbeat" : "block.sculk_sensor.clicking", 0.4f, 0.5f);
                }
            }
        }
        if (busy && s.entity != null && s.entity.isValid()) {
            Element el = s.demon.element();
            Location eye = s.entity.getLocation().add(0, s.entity.getHeight() / 2, 0);
            double oa = idleTick * 0.3;
            Fx.spawn(eye.clone().add(Math.cos(oa) * 1.4, 0, Math.sin(oa) * 1.4), el.particle, 2, 0.05, 0.05, 0.05, 0.01);
            Fx.spawn(eye.clone().add(Math.cos(oa + Math.PI) * 1.4, 0, Math.sin(oa + Math.PI) * 1.4), el.particle2, 2, 0.05, 0.05, 0.05, 0.01);
            Player t = s.target == null ? null : Bukkit.getPlayer(s.target);
            if (t != null && t.getWorld().equals(w)) {
                Vector v = t.getLocation().toVector().subtract(s.entity.getLocation().toVector());
                s.entity.setRotation((float) Math.toDegrees(Math.atan2(-v.getX(), v.getZ())), 0f);
            }
        }
    }

    /** Quelques runes vacillent (blocs envoyés au client uniquement). */
    private void flicker(List<Player> near, Location base) {
        if (runes.isEmpty()) return;
        BlockData litData = RUNE_LIT.createBlockData();
        List<Block> blocks = new ArrayList<>();
        for (int i = 0; i < 3; i++) blocks.add(runeBlock(runes.get(rnd.nextInt(runes.size()))));
        for (Block b : blocks) for (Player p : near) p.sendBlockChange(b.getLocation(), litData);
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            for (Block b : blocks) {
                BlockData real = b.getBlockData();
                for (Player p : near) if (p.isOnline()) p.sendBlockChange(b.getLocation(), real);
            }
        }, 12L);
    }
}
