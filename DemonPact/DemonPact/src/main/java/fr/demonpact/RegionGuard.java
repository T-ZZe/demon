package fr.demonpact;

import org.bukkit.Location;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Zones où les pouvoirs sont désactivés : mondes, cuboïdes configurés, et régions WorldGuard
 * (via réflexion : aucune dépendance de compilation, le plugin reste optionnel).
 */
public final class RegionGuard {
    private record Cuboid(String world, int x1, int y1, int z1, int x2, int y2, int z2) {
        boolean contains(Location l) {
            return l.getWorld() != null && l.getWorld().getName().equalsIgnoreCase(world)
                    && l.getBlockX() >= x1 && l.getBlockX() <= x2
                    && l.getBlockY() >= y1 && l.getBlockY() <= y2
                    && l.getBlockZ() >= z1 && l.getBlockZ() <= z2;
        }
    }

    private final DemonPactPlugin plugin;
    private final Set<String> worlds = new HashSet<>();
    private final Set<String> regions = new HashSet<>();
    private final List<Cuboid> cuboids = new ArrayList<>();
    private boolean wgBroken;

    public RegionGuard(DemonPactPlugin plugin) { this.plugin = plugin; }

    public void load() {
        worlds.clear();
        regions.clear();
        cuboids.clear();
        wgBroken = false;
        for (String w : plugin.getConfig().getStringList("disabled-zones.worlds")) worlds.add(w.toLowerCase(Locale.ROOT));
        for (String r : plugin.getConfig().getStringList("disabled-zones.worldguard-regions")) regions.add(r.toLowerCase(Locale.ROOT));
        // Format : "monde:x1,y1,z1:x2,y2,z2"
        for (String s : plugin.getConfig().getStringList("disabled-zones.cuboids")) {
            try {
                String[] p = s.split(":");
                String[] a = p[1].split(",");
                String[] b = p[2].split(",");
                int ax = Integer.parseInt(a[0].trim()), ay = Integer.parseInt(a[1].trim()), az = Integer.parseInt(a[2].trim());
                int bx = Integer.parseInt(b[0].trim()), by = Integer.parseInt(b[1].trim()), bz = Integer.parseInt(b[2].trim());
                cuboids.add(new Cuboid(p[0].trim(), Math.min(ax, bx), Math.min(ay, by), Math.min(az, bz),
                        Math.max(ax, bx), Math.max(ay, by), Math.max(az, bz)));
            } catch (Exception e) {
                plugin.getLogger().warning("Cuboïde invalide dans disabled-zones : " + s);
            }
        }
    }

    public boolean isDisabled(Location l) {
        if (l == null || l.getWorld() == null) return false;
        if (worlds.contains(l.getWorld().getName().toLowerCase(Locale.ROOT))) return true;
        for (Cuboid c : cuboids) if (c.contains(l)) return true;
        return !regions.isEmpty() && inWorldGuardRegion(l);
    }

    private boolean inWorldGuardRegion(Location loc) {
        if (wgBroken || plugin.getServer().getPluginManager().getPlugin("WorldGuard") == null) return false;
        try {
            Class<?> wgClass = Class.forName("com.sk89q.worldguard.WorldGuard");
            Object wg = wgClass.getMethod("getInstance").invoke(null);
            Object platform = wgClass.getMethod("getPlatform").invoke(wg);
            Object container = platform.getClass().getMethod("getRegionContainer").invoke(platform);
            Object query = container.getClass().getMethod("createQuery").invoke(container);
            Class<?> adapter = Class.forName("com.sk89q.worldedit.bukkit.BukkitAdapter");
            Object wgLoc = adapter.getMethod("adapt", Location.class).invoke(null, loc);
            Method getRegions = null;
            for (Method m : query.getClass().getMethods()) {
                if (m.getName().equals("getApplicableRegions") && m.getParameterCount() == 1
                        && m.getParameterTypes()[0].isAssignableFrom(wgLoc.getClass())) {
                    getRegions = m;
                    break;
                }
            }
            if (getRegions == null) throw new NoSuchMethodException("getApplicableRegions");
            Object set = getRegions.invoke(query, wgLoc);
            for (Object region : (Iterable<?>) set) {
                String id = String.valueOf(region.getClass().getMethod("getId").invoke(region));
                if (regions.contains(id.toLowerCase(Locale.ROOT))) return true;
            }
        } catch (Exception | LinkageError e) {
            wgBroken = true;
            plugin.getLogger().warning("Compatibilité WorldGuard désactivée (API inattendue) : " + e);
        }
        return false;
    }
}
