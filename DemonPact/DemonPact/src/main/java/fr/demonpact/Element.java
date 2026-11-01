package fr.demonpact;

import org.bukkit.Color;
import org.bukkit.Material;

/** Élément d'un démon : détermine particules, sons et animation d'invocation. */
public enum Element {
    WIND("cloud", "poof", Color.fromRGB(190, 235, 255), Material.WHITE_CONCRETE_POWDER, "entity.phantom.flap"),
    FIRE("flame", "lava", Color.fromRGB(255, 110, 20), Material.MAGMA_BLOCK, "entity.blaze.shoot"),
    LIGHTNING("electric_spark", "end_rod", Color.fromRGB(255, 240, 80), Material.YELLOW_CONCRETE, "entity.lightning_bolt.thunder"),
    TIME("end_rod", "enchant", Color.fromRGB(230, 200, 90), Material.GOLD_BLOCK, "block.bell.use"),
    SHADOW("squid_ink", "smoke", Color.fromRGB(35, 10, 55), Material.BLACK_CONCRETE, "entity.enderman.stare"),
    BLOOD("crimson_spore", "damage_indicator", Color.fromRGB(170, 0, 20), Material.NETHER_WART_BLOCK, "entity.witch.ambient"),
    EARTH("poof", "ash", Color.fromRGB(120, 85, 50), Material.COARSE_DIRT, "block.gravel.break"),
    WATER("splash", "bubble_pop", Color.fromRGB(40, 110, 220), Material.PRISMARINE_BRICKS, "entity.drowned.ambient"),
    DEATH("soul", "sculk_soul", Color.fromRGB(100, 220, 200), Material.SOUL_SOIL, "entity.wither.ambient"),
    ILLUSION("witch", "end_rod", Color.fromRGB(220, 80, 220), Material.AMETHYST_BLOCK, "entity.illusioner.cast_spell"),
    GRAVITY("reverse_portal", "crit", Color.fromRGB(110, 70, 160), Material.DEEPSLATE, "block.anvil.land"),
    VOID("portal", "reverse_portal", Color.fromRGB(70, 0, 110), Material.CRYING_OBSIDIAN, "entity.enderman.teleport");

    public final String particle;
    public final String particle2;
    public final Color color;
    public final Material floorFx;
    public final String sound;

    Element(String particle, String particle2, Color color, Material floorFx, String sound) {
        this.particle = particle;
        this.particle2 = particle2;
        this.color = color;
        this.floorFx = floorFx;
        this.sound = sound;
    }

    public static Element of(String s) {
        try {
            return valueOf(s.toUpperCase());
        } catch (Exception e) {
            return VOID;
        }
    }
}
