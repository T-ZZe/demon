package fr.demonpact;

/** Pouvoir configuré (valeurs effectives après lecture de config.yml). */
public record Power(PowerType type, String name, String description, double cooldown, double duration,
                    double range, double strength, double damage, String particle, String sound, boolean enabled) {
}
