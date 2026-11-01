package fr.demonpact;

/**
 * Les 36 pouvoirs du plugin avec leurs valeurs par défaut.
 * Chaque valeur peut être surchargée dans config.yml (section powers).
 * Unités : cooldown/durée en secondes, portée en blocs, force/dégâts selon le pouvoir.
 */
public enum PowerType {
    // AERON
    AIR_DASH("AERON", "Air Dash", "Propulsion rapide dans la direction du regard.", 8, 0, 0, 2.4, 0),
    AIR_JUMP("AERON", "Double Saut", "Un second saut, même en plein air.", 6, 0, 0, 1.0, 0),
    TORNADO("AERON", "Tornade", "Une tornade repousse violemment les ennemis proches.", 25, 3, 6, 1.6, 4),
    // IGNIS
    FIRE_DASH("IGNIS", "Fire Dash", "Charge enflammée qui brûle tout sur son passage.", 10, 4, 2.5, 2.0, 4),
    FLAME_BURST("IGNIS", "Explosion de Flammes", "Une déflagration de feu autour de vous.", 20, 5, 6, 1.3, 6),
    FIRE_RESISTANCE("IGNIS", "Résistance au Feu", "Vous devenez insensible aux flammes.", 60, 45, 0, 0, 0),
    // VOLTAR
    LIGHTNING_STRIKE("VOLTAR", "Coup de Foudre", "La foudre s'abat sur l'adversaire visé.", 15, 0, 30, 0, 9),
    ELECTRIC_DASH("VOLTAR", "Dash Électrique", "Charge électrique qui paralyse les ennemis touchés.", 12, 0, 2.5, 2.2, 4),
    ELECTRIC_FIELD("VOLTAR", "Champ Électrique", "Un champ d'arcs électriques foudroie les ennemis proches.", 40, 8, 5, 0, 2),
    // CHRONOS
    TIME_STOP("CHRONOS", "Arrêt du Temps", "Le temps s'arrête dans une petite zone.", 60, 4, 6, 0, 0),
    SLOW_ENEMIES("CHRONOS", "Ralentissement", "Ralentit les ennemis proches.", 25, 8, 8, 3, 0),
    HASTE_SELF("CHRONOS", "Accélération", "Vous vous déplacez et frappez plus vite.", 30, 8, 0, 2, 0),
    // UMBRA
    INVISIBILITY("UMBRA", "Voile d'Ombre", "Invisibilité temporaire.", 45, 15, 0, 0, 0),
    SHORT_TELEPORT("UMBRA", "Pas d'Ombre", "Téléportation courte dans la direction du regard.", 8, 0, 8, 0, 0),
    DARK_ZONE("UMBRA", "Zone d'Obscurité", "Plonge une zone dans les ténèbres.", 45, 8, 6, 0, 0),
    // SANGUIS
    LIFESTEAL("SANGUIS", "Vol de Vie", "Vos attaques vous soignent pendant un moment.", 40, 10, 0, 0.4, 0),
    SHOCKWAVE("SANGUIS", "Onde de Choc", "Une onde sanglante repousse et blesse.", 18, 0, 5, 1.2, 5),
    REGEN("SANGUIS", "Régénération Sanguine", "Régénération temporaire.", 40, 8, 0, 1, 0),
    // TERRAX
    SEISMIC_WAVE("TERRAX", "Onde Sismique", "Une fissure traverse le sol devant vous.", 15, 0, 12, 0.8, 5),
    STONE_WALL("TERRAX", "Mur de Pierre", "Un mur de pierre temporaire surgit devant vous.", 25, 10, 5, 0, 0),
    DAMAGE_RESIST("TERRAX", "Peau de Roche", "Réduit fortement les dégâts subis.", 50, 12, 0, 1, 0),
    // AQUARIS
    WATER_PRISON("AQUARIS", "Prison d'Eau", "Emprisonne une cible dans une bulle d'eau.", 35, 5, 20, 0, 1),
    WAVE("AQUARIS", "Vague", "Une vague repousse les ennemis devant vous.", 20, 0, 10, 1.8, 3),
    WATER_BREATHING("AQUARIS", "Respiration des Abysses", "Respiration aquatique permanente (passif).", 0, 0, 0, 0, 0, true),
    // NOX
    SUMMON_SERVANTS("NOX", "Serviteurs de la Mort", "Invoque des serviteurs temporaires.", 60, 30, 20, 3, 0),
    FEAR_AURA("NOX", "Aura de Peur", "Les ennemis proches fuient devant vous.", 35, 8, 7, 0, 0),
    CURSE("NOX", "Malédiction", "Maudit un adversaire.", 40, 10, 25, 0, 0),
    // PHANTOM
    CLONES("PHANTOM", "Clones", "Crée des clones qui trompent vos adversaires.", 30, 12, 0, 3, 0),
    TELEPORT("PHANTOM", "Téléportation Illusoire", "Vous vous téléportez en laissant un leurre.", 15, 6, 14, 0, 0),
    CONFUSION("PHANTOM", "Confusion", "Désoriente les adversaires proches.", 30, 8, 7, 0, 0),
    // GRAVITAS
    LEVITATE_ZONE("GRAVITAS", "Lévitation", "Fait léviter les ennemis d'une zone.", 30, 4, 7, 0, 0),
    GRAVITY_CRUSH("GRAVITAS", "Écrasement Gravitationnel", "Écrase les ennemis au sol.", 25, 0, 7, 2.0, 6),
    LOW_GRAVITY("GRAVITAS", "Gravité Réduite", "Vous flottez et sautez bien plus haut.", 40, 20, 0, 2, 0),
    // VOID
    RIFT("VOID", "Faille Dimensionnelle", "Ouvre une faille qui aspire les ennemis.", 40, 6, 7, 0.35, 2),
    VOID_PROJECTILE("VOID", "Projectile du Néant", "Lance un projectile du Néant.", 12, 0, 40, 1.6, 8),
    VOID_TELEPORT("VOID", "Saut du Néant", "Téléportation dans la zone ciblée.", 25, 0, 40, 0, 0);

    private final String demon;
    private final String label;
    private final String description;
    private final double cooldown;
    private final double duration;
    private final double range;
    private final double strength;
    private final double damage;
    private final boolean passive;

    PowerType(String demon, String label, String description, double cooldown, double duration,
              double range, double strength, double damage) {
        this(demon, label, description, cooldown, duration, range, strength, damage, false);
    }

    PowerType(String demon, String label, String description, double cooldown, double duration,
              double range, double strength, double damage, boolean passive) {
        this.demon = demon;
        this.label = label;
        this.description = description;
        this.cooldown = cooldown;
        this.duration = duration;
        this.range = range;
        this.strength = strength;
        this.damage = damage;
        this.passive = passive;
    }

    public String demon() { return demon; }
    public String label() { return label; }
    public String description() { return description; }
    public double cooldown() { return cooldown; }
    public double duration() { return duration; }
    public double range() { return range; }
    public double strength() { return strength; }
    public double damage() { return damage; }
    public boolean passive() { return passive; }

    public static PowerType of(String s) {
        try {
            return valueOf(s.toUpperCase());
        } catch (Exception e) {
            return null;
        }
    }
}
