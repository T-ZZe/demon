package fr.demonpact;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Gestion des cooldowns par joueur et par pouvoir, plus protection anti-spam. */
public final class CooldownManager {
    private final Map<UUID, Map<String, Long>> ready = new HashMap<>();
    private final Map<UUID, Long> lastUse = new HashMap<>();

    public long remaining(UUID id, String key) {
        Map<String, Long> m = ready.get(id);
        if (m == null) return 0;
        Long t = m.get(key);
        if (t == null) return 0;
        long r = t - System.currentTimeMillis();
        if (r <= 0) {
            m.remove(key);
            return 0;
        }
        return r;
    }

    public void set(UUID id, String key, long millis) {
        if (millis <= 0) return;
        ready.computeIfAbsent(id, k -> new HashMap<>()).put(key, System.currentTimeMillis() + millis);
    }

    public void clear(UUID id) {
        ready.remove(id);
        lastUse.remove(id);
    }

    /** Retourne true si le joueur a déjà utilisé un pouvoir il y a moins de minMillis. */
    public boolean spam(UUID id, long minMillis) {
        long now = System.currentTimeMillis();
        Long last = lastUse.get(id);
        if (last != null && now - last < minMillis) return true;
        lastUse.put(id, now);
        return false;
    }
}
