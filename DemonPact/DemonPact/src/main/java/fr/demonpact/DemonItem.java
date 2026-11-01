package fr.demonpact;

import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Objets de pacte. Chaque objet porte (PDC) l'id du démon, l'UUID du propriétaire et un numéro de série.
 * Seul l'objet dont le numéro de série correspond au pacte actif est valide : toute copie est inerte.
 */
public final class DemonItem {
    public record Info(String demonId, UUID owner, UUID serial) {}

    private final DemonPactPlugin plugin;
    private final NamespacedKey kDemon;
    private final NamespacedKey kOwner;
    private final NamespacedKey kSerial;
    private final NamespacedKey kHead;

    public DemonItem(DemonPactPlugin plugin) {
        this.plugin = plugin;
        this.kDemon = new NamespacedKey(plugin, "demon_item");
        this.kOwner = new NamespacedKey(plugin, "owner");
        this.kSerial = new NamespacedKey(plugin, "serial");
        this.kHead = new NamespacedKey(plugin, "pvp_head");
    }

    public NamespacedKey headKey() { return kHead; }

    public ItemStack create(Demon d, Pact pact, String ownerName) {
        ItemStack it = new ItemStack(d.itemMaterial());
        ItemMeta meta = it.getItemMeta();
        Power power = plugin.powers().power(pact.power());
        meta.displayName(Txt.item(d.itemName()));
        List<String> lore = new ArrayList<>();
        for (String l : d.itemLore()) lore.add(Txt.fill(l, "owner", ownerName, "power", power.name()));
        lore.add("&8&m                              ");
        lore.add("&7Pouvoir : " + d.color() + power.name());
        lore.add("&7" + power.description());
        lore.add(power.type().passive() ? "&7Pouvoir &fpassif&7, toujours actif." : "&7Clic droit pour activer.");
        lore.add("&8Lié à &7" + ownerName);
        lore.add("&4Cet objet n'obéit qu'à son maître.");
        meta.lore(Txt.items(lore));
        meta.setMaxStackSize(1);
        meta.setEnchantmentGlintOverride(true);
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        pdc.set(kDemon, PersistentDataType.STRING, d.id());
        pdc.set(kOwner, PersistentDataType.STRING, pact.owner().toString());
        pdc.set(kSerial, PersistentDataType.STRING, pact.serial().toString());
        it.setItemMeta(meta);
        return it;
    }

    /** Lit les informations d'un objet de pacte, ou null si ce n'en est pas un. */
    public Info read(ItemStack it) {
        if (it == null || !it.hasItemMeta()) return null;
        PersistentDataContainer pdc = it.getItemMeta().getPersistentDataContainer();
        String d = pdc.get(kDemon, PersistentDataType.STRING);
        String o = pdc.get(kOwner, PersistentDataType.STRING);
        String s = pdc.get(kSerial, PersistentDataType.STRING);
        if (d == null || o == null || s == null) return null;
        try {
            return new Info(d, UUID.fromString(o), UUID.fromString(s));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public boolean isDemonItem(ItemStack it) { return read(it) != null; }
}
