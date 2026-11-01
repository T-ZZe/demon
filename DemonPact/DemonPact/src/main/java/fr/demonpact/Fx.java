package fr.demonpact;

import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Registry;
import org.bukkit.block.data.BlockData;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** Effets visuels et sonores : les particules et sons sont résolus par leur clé (configurable). */
public final class Fx {
    private static final Map<String, Particle> CACHE = new HashMap<>();

    private Fx() {}

    public static Particle particle(String key) {
        if (key == null || key.isBlank()) return null;
        String k = key.toLowerCase(Locale.ROOT).trim();
        if (k.startsWith("minecraft:")) k = k.substring(10);
        if (CACHE.containsKey(k)) return CACHE.get(k);
        Particle p = null;
        try {
            p = Registry.PARTICLE_TYPE.get(NamespacedKey.minecraft(k));
        } catch (Exception ignored) {
            // clé invalide : on ignore
        }
        CACHE.put(k, p);
        return p;
    }

    /** Particule sans donnée additionnelle (les types nécessitant une donnée sont ignorés). */
    public static void spawn(Location l, String key, int count, double dx, double dy, double dz, double speed) {
        if (l == null || l.getWorld() == null) return;
        Particle p = particle(key);
        if (p == null || p.getDataType() != Void.class) return;
        l.getWorld().spawnParticle(p, l, count, dx, dy, dz, speed);
    }

    public static void dust(Location l, Color color, float size, int count, double spread) {
        if (l == null || l.getWorld() == null) return;
        l.getWorld().spawnParticle(Particle.DUST, l, count, spread, spread, spread, 0, new Particle.DustOptions(color, size));
    }

    public static void block(Location l, Material mat, int count, double dx, double dy, double dz) {
        if (l == null || l.getWorld() == null || mat == null || !mat.isBlock()) return;
        BlockData data = mat.createBlockData();
        l.getWorld().spawnParticle(Particle.BLOCK, l, count, dx, dy, dz, 0, data);
    }

    public static void sound(Location l, String key, float volume, float pitch) {
        if (l == null || l.getWorld() == null || key == null || key.isBlank()) return;
        l.getWorld().playSound(l, key, volume, pitch);
    }

    /** Anneau de particules horizontal. */
    public static void ring(Location c, double r, int points, String key) {
        for (int i = 0; i < points; i++) {
            double a = 2 * Math.PI * i / points;
            spawn(c.clone().add(Math.cos(a) * r, 0, Math.sin(a) * r), key, 1, 0, 0, 0, 0);
        }
    }

    public static void ringDust(Location c, double r, int points, Color color, float size) {
        for (int i = 0; i < points; i++) {
            double a = 2 * Math.PI * i / points;
            dust(c.clone().add(Math.cos(a) * r, 0, Math.sin(a) * r), color, size, 1, 0);
        }
    }
}
