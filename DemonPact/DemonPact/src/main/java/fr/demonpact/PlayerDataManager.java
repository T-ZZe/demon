package fr.demonpact;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Stockage YAML des pactes et des sacrifices de vie (players.yml). */
public final class PlayerDataManager {
    private final DemonPactPlugin plugin;
    private final File file;
    private final Map<UUID, PlayerData> data = new HashMap<>();
    private boolean dirty;

    public PlayerDataManager(DemonPactPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "players.yml");
    }

    public void load() {
        data.clear();
        if (!file.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection root = y.getConfigurationSection("players");
        if (root == null) return;
        for (String key : root.getKeys(false)) {
            try {
                UUID id = UUID.fromString(key);
                ConfigurationSection s = root.getConfigurationSection(key);
                if (s == null) continue;
                PlayerData pd = new PlayerData(id);
                pd.name(s.getString("name", ""));
                pd.lostHealth(s.getDouble("lost-health", 0));
                pd.lastReclaim(s.getLong("last-reclaim", 0));
                for (Map<?, ?> m : s.getMapList("pacts")) {
                    PowerType pt = PowerType.of(String.valueOf(m.get("power")));
                    if (pt == null) continue;
                    Object since = m.get("since");
                    pd.pacts().add(new Pact(id, String.valueOf(m.get("demon")), String.valueOf(m.get("contract")), pt,
                            UUID.fromString(String.valueOf(m.get("serial"))),
                            since == null ? System.currentTimeMillis() : Long.parseLong(String.valueOf(since))));
                }
                data.put(id, pd);
            } catch (Exception ex) {
                plugin.getLogger().warning("Données illisibles pour " + key + " : " + ex.getMessage());
            }
        }
    }

    public PlayerData get(UUID id) {
        return data.computeIfAbsent(id, PlayerData::new);
    }

    public boolean has(UUID id) { return data.containsKey(id); }

    public void markDirty() { dirty = true; }

    /** Sauvegarde immédiate (appelée à chaque changement de pacte). */
    public void saveNow() {
        YamlConfiguration y = new YamlConfiguration();
        for (PlayerData pd : data.values()) {
            if (pd.pacts().isEmpty() && pd.lostHealth() <= 0) continue;
            String p = "players." + pd.uuid();
            y.set(p + ".name", pd.name());
            y.set(p + ".lost-health", pd.lostHealth());
            y.set(p + ".last-reclaim", pd.lastReclaim());
            List<Map<String, Object>> list = new ArrayList<>();
            for (Pact pact : pd.pacts()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("demon", pact.demonId());
                m.put("contract", pact.contractKey());
                m.put("power", pact.power().name());
                m.put("serial", pact.serial().toString());
                m.put("since", pact.since());
                list.add(m);
            }
            y.set(p + ".pacts", list);
        }
        try {
            if (!plugin.getDataFolder().exists()) plugin.getDataFolder().mkdirs();
            y.save(file);
            dirty = false;
        } catch (IOException e) {
            plugin.getLogger().severe("Impossible de sauvegarder players.yml : " + e.getMessage());
        }
    }

    /** Sauvegarde périodique si des changements en attente. */
    public void saveIfDirty() {
        if (dirty) saveNow();
    }
}
