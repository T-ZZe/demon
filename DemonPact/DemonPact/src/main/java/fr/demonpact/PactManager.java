package fr.demonpact;

import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffectType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Création, validation et suppression des pactes, objets de pacte et sacrifices de vie. */
public final class PactManager {
    private final DemonPactPlugin plugin;
    private final NamespacedKey healthKey;
    private final Map<UUID, List<ItemStack>> deathStash = new HashMap<>();

    public PactManager(DemonPactPlugin plugin) {
        this.plugin = plugin;
        this.healthKey = new NamespacedKey(plugin, "life_sacrifice");
    }

    public List<Pact> pactsOf(UUID id) { return plugin.data().get(id).pacts(); }

    public int maxActive() { return Math.max(1, plugin.getConfig().getInt("pact.max-active", 1)); }

    /** Retourne un message d'erreur si le joueur ne peut pas obtenir ce pouvoir, sinon null. */
    public String canTake(UUID id, PowerType power) {
        List<Pact> l = pactsOf(id);
        if (l.size() >= maxActive()) {
            return "Vous avez déjà conclu le nombre maximal de pactes (" + maxActive() + ").";
        }
        for (Pact p : l) if (p.power() == power) return "Vous possédez déjà ce pouvoir.";
        return null;
    }

    public boolean hasCapacity(UUID id) { return pactsOf(id).size() < maxActive(); }

    /** Enregistre le pacte, sauvegarde et donne l'objet. */
    public Pact grant(Player p, Demon d, DemonContract c) {
        PlayerData pd = plugin.data().get(p.getUniqueId());
        pd.name(p.getName());
        Pact pact = new Pact(p.getUniqueId(), d.id(), c.key(), c.power(), UUID.randomUUID(), System.currentTimeMillis());
        pd.pacts().add(pact);
        plugin.data().saveNow();
        giveItem(p, d, pact);
        return pact;
    }

    public void giveItem(Player p, Demon d, Pact pact) {
        ItemStack it = plugin.items().create(d, pact, p.getName());
        for (ItemStack rest : p.getInventory().addItem(it).values()) {
            Item drop = p.getWorld().dropItem(p.getLocation(), rest);
            drop.setOwner(p.getUniqueId());
        }
    }

    /** Retire l'objet existant, change le numéro de série (invalide toute copie) et redonne un objet neuf. */
    public boolean reissue(Player p, Pact pact) {
        Demon d = plugin.demons().get(pact.demonId());
        if (d == null) return false;
        removeItems(p, pact.serial());
        pact.serial(UUID.randomUUID());
        plugin.data().saveNow();
        giveItem(p, d, pact);
        return true;
    }

    /** Retourne le pacte correspondant à l'objet, uniquement si le joueur en est le propriétaire et que le numéro de série est à jour. */
    public Pact validate(Player p, DemonItem.Info info) {
        if (info == null || !info.owner().equals(p.getUniqueId())) return null;
        for (Pact pact : pactsOf(p.getUniqueId())) {
            if (pact.serial().equals(info.serial()) && pact.demonId().equalsIgnoreCase(info.demonId())) return pact;
        }
        return null;
    }

    public void remove(UUID id, Pact pact) {
        plugin.data().get(id).pacts().remove(pact);
        plugin.data().saveNow();
        Player p = Bukkit.getPlayer(id);
        if (p != null) {
            removeItems(p, pact.serial());
            if (pact.power() == PowerType.WATER_BREATHING) p.removePotionEffect(PotionEffectType.WATER_BREATHING);
        }
    }

    public void removeItems(Player p, UUID serial) {
        ItemStack[] contents = p.getInventory().getContents();
        boolean changed = false;
        for (int i = 0; i < contents.length; i++) {
            DemonItem.Info info = plugin.items().read(contents[i]);
            if (info != null && info.serial().equals(serial)) {
                contents[i] = null;
                changed = true;
            }
        }
        if (changed) p.getInventory().setContents(contents);
        ItemStack[] ender = p.getEnderChest().getContents();
        boolean ec = false;
        for (int i = 0; i < ender.length; i++) {
            DemonItem.Info info = plugin.items().read(ender[i]);
            if (info != null && info.serial().equals(serial)) {
                ender[i] = null;
                ec = true;
            }
        }
        if (ec) p.getEnderChest().setContents(ender);
    }

    // ------------------------------------------------------------------ Vie maximale

    public double currentMax(Player p) {
        AttributeInstance a = p.getAttribute(Attribute.MAX_HEALTH);
        return a == null ? 20.0 : a.getValue();
    }

    /** Vérifie que perdre hp points de vie max laisse au joueur au moins le minimum configuré. */
    public boolean canLose(Player p, double hp) {
        double min = plugin.getConfig().getDouble("sacrifice.min-max-health", 6.0);
        return currentMax(p) - hp >= min;
    }

    public double heartHealth() { return plugin.getConfig().getDouble("sacrifice.heart-health", 2.0); }

    public void addLostHealth(Player p, double hp) {
        PlayerData pd = plugin.data().get(p.getUniqueId());
        pd.name(p.getName());
        pd.lostHealth(pd.lostHealth() + hp);
        plugin.data().saveNow();
        applyHealth(p);
    }

    public void restoreHealth(UUID id) {
        PlayerData pd = plugin.data().get(id);
        pd.lostHealth(0);
        plugin.data().saveNow();
        Player p = Bukkit.getPlayer(id);
        if (p != null) applyHealth(p);
    }

    /** (Ré)applique le modificateur permanent de vie maximale du joueur. */
    public void applyHealth(Player p) {
        AttributeInstance a = p.getAttribute(Attribute.MAX_HEALTH);
        if (a == null) return;
        for (AttributeModifier m : new ArrayList<>(a.getModifiers())) {
            if (healthKey.equals(m.getKey())) a.removeModifier(m);
        }
        double lost = plugin.data().get(p.getUniqueId()).lostHealth();
        if (lost > 0) {
            a.addModifier(new AttributeModifier(healthKey, -lost, AttributeModifier.Operation.ADD_NUMBER, EquipmentSlotGroup.ANY));
        }
        double max = a.getValue();
        if (p.getHealth() > max) p.setHealth(max);
    }

    // ------------------------------------------------------------------ Objets conservés à la mort

    public void stash(UUID id, List<ItemStack> items) { deathStash.put(id, items); }

    public List<ItemStack> popStash(UUID id) { return deathStash.remove(id); }
}
