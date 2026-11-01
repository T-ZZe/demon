package fr.demonpact;

import org.bukkit.Material;

import java.util.ArrayList;
import java.util.List;

/** Un contrat proposé par un démon : un pouvoir contre un ou plusieurs sacrifices. */
public final class DemonContract {
    public enum CostType { HEARTS, HEADS, ITEMS }

    public record Cost(CostType type, int amount, Material material) {}

    private final String key;
    private final String demonId;
    private final PowerType power;
    private final List<Cost> costs;
    private final List<String> sideEffects;

    public DemonContract(String key, String demonId, PowerType power, List<Cost> costs, List<String> sideEffects) {
        this.key = key;
        this.demonId = demonId;
        this.power = power;
        this.costs = List.copyOf(costs);
        this.sideEffects = List.copyOf(sideEffects);
    }

    public String key() { return key; }
    public String demonId() { return demonId; }
    public String id() { return demonId + "." + key; }
    public PowerType power() { return power; }
    public List<Cost> costs() { return costs; }
    public List<String> sideEffects() { return sideEffects; }

    public CostType primary() {
        return costs.isEmpty() ? CostType.HEARTS : costs.get(0).type();
    }

    public static String describe(Cost c) {
        return switch (c.type()) {
            case HEARTS -> c.amount() + (c.amount() > 1 ? " cœurs permanents" : " cœur permanent");
            case HEADS -> c.amount() + (c.amount() > 1 ? " têtes de joueurs" : " tête de joueur");
            case ITEMS -> c.amount() + "x " + Txt.pretty(c.material() == null ? "inconnu" : c.material().name());
        };
    }

    public String describeCosts() {
        List<String> parts = new ArrayList<>();
        for (Cost c : costs) parts.add(describe(c));
        return String.join(" + ", parts);
    }
}
