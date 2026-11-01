package fr.demonpact;

import org.bukkit.Material;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/** Définition d'un démon (apparence, personnalité, contrats, objet de pacte). */
public final class Demon {
    private final String id;
    private final String name;
    private final String title;
    private final String color;
    private final String glow;
    private final String rarity;
    private final int powerLevel;
    private final Element element;
    private final String entity;
    private final Material helmet;
    private final double scale;
    private final boolean hover;
    private final Material itemMaterial;
    private final String itemName;
    private final List<String> itemLore;
    private final List<DemonContract> contracts;
    private final Map<String, List<String>> dialogues;

    public Demon(String id, String name, String title, String color, String glow, String rarity, int powerLevel,
                 Element element, String entity, Material helmet, double scale, boolean hover,
                 Material itemMaterial, String itemName, List<String> itemLore,
                 List<DemonContract> contracts, Map<String, List<String>> dialogues) {
        this.id = id;
        this.name = name;
        this.title = title;
        this.color = color;
        this.glow = glow;
        this.rarity = rarity;
        this.powerLevel = powerLevel;
        this.element = element;
        this.entity = entity;
        this.helmet = helmet;
        this.scale = scale;
        this.hover = hover;
        this.itemMaterial = itemMaterial;
        this.itemName = itemName;
        this.itemLore = itemLore;
        this.contracts = contracts;
        this.dialogues = dialogues;
    }

    public String id() { return id; }
    public String name() { return name; }
    public String title() { return title; }
    public String color() { return color; }
    public String glow() { return glow; }
    public String rarity() { return rarity; }
    public int powerLevel() { return powerLevel; }
    public Element element() { return element; }
    public String entity() { return entity; }
    public Material helmet() { return helmet; }
    public double scale() { return scale; }
    public boolean hover() { return hover; }
    public Material itemMaterial() { return itemMaterial; }
    public String itemName() { return itemName; }
    public List<String> itemLore() { return itemLore; }
    public List<DemonContract> contracts() { return contracts; }

    public DemonContract contract(String key) {
        for (DemonContract c : contracts) if (c.key().equalsIgnoreCase(key)) return c;
        return null;
    }

    /** Ligne de dialogue aléatoire pour une catégorie (appear, propose, accept, refuse, timeout, sacrifice.TYPE). */
    public String line(String key) {
        List<String> l = dialogues.get(key);
        if (l == null || l.isEmpty()) return "...";
        return l.get(ThreadLocalRandom.current().nextInt(l.size()));
    }

    /** Nom formaté avec la couleur du démon. */
    public String tag() { return color + "&l" + name; }
}
