package fr.demonpact;

import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataType;

/** Vérification et création de têtes de joueurs authentiques. */
public final class Heads {
    private final DemonPactPlugin plugin;

    public Heads(DemonPactPlugin plugin) { this.plugin = plugin; }

    /**
     * Une tête est authentique si elle appartient à un vrai joueur déjà connecté au serveur
     * (UUID + nom concordants). Les têtes à texture libre, ou au nom inventé, sont refusées.
     */
    public boolean isGenuine(ItemStack it, Player sacrificer) {
        if (it == null || it.getType() != Material.PLAYER_HEAD || !it.hasItemMeta()) return false;
        if (!(it.getItemMeta() instanceof SkullMeta meta)) return false;
        OfflinePlayer op = meta.getOwningPlayer();
        if (op == null || op.getName() == null) return false;
        if (!op.isOnline() && !op.hasPlayedBefore()) return false;
        if (!plugin.getConfig().getBoolean("heads.allow-own-head", false)
                && sacrificer != null && op.getUniqueId().equals(sacrificer.getUniqueId())) return false;
        if (plugin.getConfig().getBoolean("heads.require-plugin-tag", false)) {
            Byte tag = meta.getPersistentDataContainer().get(plugin.items().headKey(), PersistentDataType.BYTE);
            return tag != null && tag == 1;
        }
        return true;
    }

    /** Crée la tête d'un joueur tué (marquée par le plugin). */
    public ItemStack create(Player victim, String killerName) {
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) head.getItemMeta();
        meta.setOwningPlayer(victim);
        meta.displayName(Txt.item("&4Tête de &c" + victim.getName()));
        meta.lore(Txt.items(java.util.List.of("&7Tué par &f" + killerName, "&8Sacrifice démoniaque")));
        meta.getPersistentDataContainer().set(plugin.items().headKey(), PersistentDataType.BYTE, (byte) 1);
        head.setItemMeta(meta);
        return head;
    }
}
