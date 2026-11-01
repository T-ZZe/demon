package fr.demonpact;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/** Charge les 12 démons depuis config.yml et dialogues.yml, et gère la parole des démons. */
public final class DemonManager {
    private static final String[] DIALOGUE_KEYS = {"appear", "propose", "accept", "refuse", "timeout"};

    private final DemonPactPlugin plugin;
    private final Map<String, Demon> demons = new LinkedHashMap<>();

    public DemonManager(DemonPactPlugin plugin) { this.plugin = plugin; }

    public void load() {
        demons.clear();
        File f = new File(plugin.getDataFolder(), "dialogues.yml");
        YamlConfiguration dlg = YamlConfiguration.loadConfiguration(f);
        ConfigurationSection root = plugin.getConfig().getConfigurationSection("demons");
        if (root == null) {
            plugin.getLogger().severe("Section 'demons' absente de config.yml !");
            return;
        }
        for (String id : root.getKeys(false)) {
            try {
                Demon d = parse(id.toUpperCase(), root.getConfigurationSection(id), dlg.getConfigurationSection(id.toUpperCase()));
                demons.put(d.id(), d);
            } catch (Exception ex) {
                plugin.getLogger().warning("Démon '" + id + "' invalide : " + ex.getMessage());
            }
        }
        plugin.getLogger().info(demons.size() + " démons chargés.");
    }

    private Demon parse(String id, ConfigurationSection s, ConfigurationSection dlg) {
        if (s == null) throw new IllegalArgumentException("section vide");
        Map<String, List<String>> dialogues = new HashMap<>();
        if (dlg != null) {
            for (String k : DIALOGUE_KEYS) dialogues.put(k, dlg.getStringList(k));
            for (DemonContract.CostType t : DemonContract.CostType.values()) {
                dialogues.put("sacrifice." + t.name(), dlg.getStringList("sacrifice." + t.name()));
            }
        }
        List<DemonContract> contracts = new ArrayList<>();
        ConfigurationSection cs = s.getConfigurationSection("contracts");
        if (cs != null) {
            for (String key : cs.getKeys(false)) {
                ConfigurationSection c = cs.getConfigurationSection(key);
                if (c == null) continue;
                PowerType power = PowerType.of(c.getString("power", ""));
                if (power == null) throw new IllegalArgumentException("pouvoir inconnu dans le contrat " + key);
                List<DemonContract.Cost> costs = new ArrayList<>();
                for (Map<?, ?> m : c.getMapList("costs")) {
                    DemonContract.CostType type = DemonContract.CostType.valueOf(String.valueOf(m.get("type")).toUpperCase());
                    int amount = Math.max(1, Integer.parseInt(String.valueOf(m.get("amount"))));
                    Material mat = null;
                    if (type == DemonContract.CostType.ITEMS) {
                        mat = Material.matchMaterial(String.valueOf(m.get("material")));
                        if (mat == null) throw new IllegalArgumentException("matériau inconnu dans le contrat " + key);
                    }
                    costs.add(new DemonContract.Cost(type, amount, mat));
                }
                if (costs.isEmpty()) throw new IllegalArgumentException("contrat sans sacrifice : " + key);
                contracts.add(new DemonContract(key, id, power, costs, c.getStringList("side-effects")));
            }
        }
        if (contracts.isEmpty()) throw new IllegalArgumentException("aucun contrat");
        ConfigurationSection item = s.getConfigurationSection("item");
        Material itemMat = Material.matchMaterial(item == null ? "NETHER_STAR" : item.getString("material", "NETHER_STAR"));
        if (itemMat == null || itemMat.isAir()) itemMat = Material.NETHER_STAR;
        Material helmet = s.contains("helmet") ? Material.matchMaterial(s.getString("helmet", "")) : null;
        return new Demon(id, s.getString("name", id), s.getString("title", "Démon"),
                s.getString("color", "&c"), s.getString("glow-color", "red"), s.getString("rarity", "RARE"),
                s.getInt("power-level", 5), Element.of(s.getString("element", "VOID")),
                s.getString("entity", "zombie"), helmet, s.getDouble("scale", 1.0), s.getBoolean("hover", false),
                itemMat, item == null ? "&5Objet démoniaque" : item.getString("name", "&5Objet démoniaque"),
                item == null ? List.of() : item.getStringList("lore"), contracts, dialogues);
    }

    public Demon get(String id) { return id == null ? null : demons.get(id.toUpperCase()); }

    public List<Demon> all() { return new ArrayList<>(demons.values()); }

    public Demon random() {
        List<Demon> l = all();
        return l.isEmpty() ? null : l.get(ThreadLocalRandom.current().nextInt(l.size()));
    }

    /** Fait parler un démon : joueurs proches de l'autel + joueur concerné. */
    public void say(Demon d, String text, Player target, boolean global) {
        String msg = d.tag() + " &8» " + d.color() + "&o" + text;
        Location c = plugin.spawner().center();
        double radius = plugin.getConfig().getDouble("negotiation.chat-radius", 60);
        for (Player p : plugin.getServer().getOnlinePlayers()) {
            boolean near = c != null && c.getWorld() != null && p.getWorld().equals(c.getWorld())
                    && p.getLocation().distanceSquared(c) <= radius * radius;
            if (global || near || (target != null && p.getUniqueId().equals(target.getUniqueId()))) {
                p.sendMessage(Txt.c(msg));
            }
        }
    }

    /** Remplace les placeholders d'une ligne de dialogue. */
    public String format(String line, Player p, DemonContract c, Demon d) {
        String power = c == null ? "" : plugin.powers().power(c.power()).name();
        String cost = c == null ? "" : c.describeCosts();
        return Txt.fill(line, "player", p == null ? "mortel" : p.getName(), "demon", d.name(), "power", power, "cost", cost);
    }
}
