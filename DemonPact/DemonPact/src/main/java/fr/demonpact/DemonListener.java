package fr.demonpact;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/** Événements généraux : connexion, mort, têtes, protection des objets de pacte et de l'autel. */
public final class DemonListener implements Listener {
    private static final Set<InventoryType> BLOCKED = Set.of(
            InventoryType.WORKBENCH, InventoryType.CRAFTING, InventoryType.ANVIL, InventoryType.SMITHING,
            InventoryType.GRINDSTONE, InventoryType.ENCHANTING, InventoryType.FURNACE, InventoryType.BLAST_FURNACE,
            InventoryType.SMOKER, InventoryType.BREWING, InventoryType.LOOM, InventoryType.STONECUTTER,
            InventoryType.CARTOGRAPHY, InventoryType.BEACON, InventoryType.LECTERN);

    private final DemonPactPlugin plugin;

    public DemonListener(DemonPactPlugin plugin) { this.plugin = plugin; }

    // ------------------------------------------------------------------ Connexion / mort

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        if (plugin.data().has(p.getUniqueId())) {
            plugin.data().get(p.getUniqueId()).name(p.getName());
            plugin.pacts().applyHealth(p);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        plugin.contracts().onQuit(e.getPlayer());
        plugin.gui().close(e.getPlayer());
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDeath(PlayerDeathEvent e) {
        Player v = e.getEntity();
        Player k = v.getKiller();
        if (k != null && !k.equals(v) && plugin.getConfig().getBoolean("heads.drop-on-pvp-kill", true)) {
            e.getDrops().add(plugin.heads().create(v, k.getName()));
        }
        if (plugin.getConfig().getBoolean("items.keep-on-death", true) && !e.getKeepInventory()) {
            List<ItemStack> keep = new ArrayList<>();
            Iterator<ItemStack> it = e.getDrops().iterator();
            while (it.hasNext()) {
                ItemStack s = it.next();
                DemonItem.Info i = plugin.items().read(s);
                if (i != null && i.owner().equals(v.getUniqueId())) {
                    keep.add(s);
                    it.remove();
                }
            }
            if (!keep.isEmpty()) plugin.pacts().stash(v.getUniqueId(), keep);
        }
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) {
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            plugin.pacts().applyHealth(p);
            List<ItemStack> list = plugin.pacts().popStash(p.getUniqueId());
            if (list == null) return;
            for (ItemStack s : list) {
                for (ItemStack rest : p.getInventory().addItem(s).values()) {
                    Item drop = p.getWorld().dropItem(p.getLocation(), rest);
                    drop.setOwner(p.getUniqueId());
                }
            }
        }, 2L);
    }

    // ------------------------------------------------------------------ Démon sur l'autel

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteractDemon(PlayerInteractEntityEvent e) {
        if (!plugin.spawner().isDemonEntity(e.getRightClicked())) return;
        e.setCancelled(true);
        if (e.getHand() != EquipmentSlot.HAND) return;
        Player p = e.getPlayer();
        ContractManager.Offer o = plugin.contracts().offerOf(p.getUniqueId());
        if (o != null && !o.resolved) {
            plugin.gui().open(p, o);
        } else {
            p.sendMessage(Txt.c(plugin.prefix() + "&7Le démon ne vous accorde pas un regard. Ce contrat ne vous est pas destiné."));
            Fx.sound(p.getLocation(), "entity.wither.ambient", 0.6f, 0.6f);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDemonDamage(EntityDamageEvent e) {
        if (plugin.spawner().isDemonEntity(e.getEntity())) e.setCancelled(true);
    }

    @EventHandler
    public void onEntitiesLoad(EntitiesLoadEvent e) {
        DemonSpawner.Session s = plugin.spawner().session();
        for (Entity en : e.getEntities()) {
            boolean stray = plugin.spawner().isDemonEntity(en)
                    && (s == null || s.entity == null || !s.entity.equals(en));
            if (stray || en.getPersistentDataContainer().has(plugin.powers().tempKey(), PersistentDataType.BYTE)) en.remove();
        }
    }

    // ------------------------------------------------------------------ Protection de l'autel

    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        if (plugin.getConfig().getBoolean("stell.protect", true) && plugin.spawner().isInStell(e.getBlock().getLocation())
                && !e.getPlayer().hasPermission("demon.admin")) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        if (plugin.items().isDemonItem(e.getItemInHand())) {
            e.setCancelled(true);
            return;
        }
        if (plugin.getConfig().getBoolean("stell.protect", true) && plugin.spawner().isInStell(e.getBlock().getLocation())
                && !e.getPlayer().hasPermission("demon.admin")) e.setCancelled(true);
    }

    // ------------------------------------------------------------------ Protection des objets de pacte

    @EventHandler
    public void onCraft(PrepareItemCraftEvent e) {
        for (ItemStack s : e.getInventory().getMatrix()) {
            if (plugin.items().isDemonItem(s)) {
                e.getInventory().setResult(null);
                return;
            }
        }
    }

    @EventHandler
    public void onAnvil(PrepareAnvilEvent e) {
        if (plugin.items().isDemonItem(e.getInventory().getFirstItem())
                || plugin.items().isDemonItem(e.getInventory().getSecondItem())) e.setResult(null);
    }

    @EventHandler(ignoreCancelled = true)
    public void onClick(InventoryClickEvent e) {
        Inventory top = e.getView().getTopInventory();
        if (!BLOCKED.contains(top.getType())) return;
        boolean inTop = e.getClickedInventory() != null && e.getClickedInventory().equals(top);
        boolean bad = false;
        if (inTop) {
            if (plugin.items().isDemonItem(e.getCursor())) bad = true;
            if (e.getClick() == ClickType.NUMBER_KEY && e.getHotbarButton() >= 0
                    && plugin.items().isDemonItem(e.getWhoClicked().getInventory().getItem(e.getHotbarButton()))) bad = true;
            if (e.getClick() == ClickType.SWAP_OFFHAND
                    && plugin.items().isDemonItem(e.getWhoClicked().getInventory().getItemInOffHand())) bad = true;
        } else if (e.isShiftClick() && plugin.items().isDemonItem(e.getCurrentItem())) {
            bad = true;
        }
        if (bad) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onDrag(InventoryDragEvent e) {
        Inventory top = e.getView().getTopInventory();
        if (!BLOCKED.contains(top.getType()) || !plugin.items().isDemonItem(e.getOldCursor())) return;
        for (int slot : e.getRawSlots()) {
            if (slot < top.getSize()) {
                e.setCancelled(true);
                return;
            }
        }
    }
}
