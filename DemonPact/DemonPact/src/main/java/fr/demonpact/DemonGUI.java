package fr.demonpact;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Interface « CONTRAT DÉMONIAQUE » : objets personnalisés, boutons Émeraude / Barrière. */
public final class DemonGUI implements Listener {
    private static final int SLOT_DEMON = 13;
    private static final int SLOT_POWER = 20;
    private static final int SLOT_PRICE = 22;
    private static final int SLOT_EFFECTS = 24;
    private static final int SLOT_TIME = 31;
    private static final int SLOT_ACCEPT = 38;
    private static final int SLOT_REFUSE = 42;

    /** Porteur d'inventaire servant à identifier l'interface. */
    public static final class Holder implements InventoryHolder {
        private final UUID player;
        private Inventory inventory;

        Holder(UUID player) { this.player = player; }

        public UUID player() { return player; }

        @Override
        public Inventory getInventory() { return inventory; }
    }

    private final DemonPactPlugin plugin;

    public DemonGUI(DemonPactPlugin plugin) { this.plugin = plugin; }

    public void open(Player p, ContractManager.Offer o) {
        Holder h = new Holder(p.getUniqueId());
        Inventory inv = Bukkit.createInventory(h, 45, Txt.c("&4&lCONTRAT DÉMONIAQUE"));
        h.inventory = inv;
        for (int i = 0; i < 45; i++) {
            boolean edge = i < 9 || i >= 36 || i % 9 == 0 || i % 9 == 8;
            inv.setItem(i, named(edge ? Material.RED_STAINED_GLASS_PANE : Material.BLACK_STAINED_GLASS_PANE, " ", List.of()));
        }
        fill(inv, p, o);
        p.openInventory(inv);
        Fx.sound(p.getLocation(), "block.portal.trigger", 0.5f, 0.6f);
    }

    public void refresh(Player p, ContractManager.Offer o) {
        Inventory top = p.getOpenInventory().getTopInventory();
        if (top.getHolder(false) instanceof Holder) fill(top, p, o);
    }

    public void close(Player p) {
        if (p.getOpenInventory().getTopInventory().getHolder(false) instanceof Holder) p.closeInventory();
    }

    private void fill(Inventory inv, Player p, ContractManager.Offer o) {
        Demon d = o.demon;
        DemonContract c = o.contract;
        Power power = plugin.powers().power(c.power());

        inv.setItem(SLOT_DEMON, named(d.itemMaterial(), d.tag() + " &8| &7" + d.title(), List.of(
                "&7Rareté : &f" + d.rarity(),
                "&7Niveau de puissance : &f" + d.powerLevel() + "/10",
                "&7Élément : &f" + Txt.pretty(d.element().name()),
                "",
                d.color() + "&o« Signe, " + p.getName() + ". »")));

        List<String> pl = new ArrayList<>();
        pl.add("&7" + power.description());
        pl.add("");
        pl.add(power.type().passive() ? "&7Type : &fPassif" : "&7Activation : &fClic droit sur l'objet");
        if (power.cooldown() > 0) pl.add("&7Recharge : &f" + fmt(power.cooldown()) + "s");
        if (power.duration() > 0) pl.add("&7Durée : &f" + fmt(power.duration()) + "s");
        if (power.range() > 0) pl.add("&7Portée : &f" + fmt(power.range()) + " blocs");
        pl.add("");
        pl.add("&8Pouvoir fixe : aucune évolution.");
        inv.setItem(SLOT_POWER, named(Material.ENCHANTED_BOOK, d.color() + "&l" + power.name(), pl));

        List<String> price = new ArrayList<>();
        for (DemonContract.Cost cost : c.costs()) {
            String need = DemonContract.describe(cost);
            switch (cost.type()) {
                case HEARTS -> {
                    double hp = cost.amount() * plugin.pacts().heartHealth();
                    boolean ok = plugin.pacts().canLose(p, hp);
                    price.add((ok ? "&a✔ " : "&c✘ ") + "&f" + need);
                    price.add("  &8Vie max : " + (int) (plugin.pacts().currentMax(p) / 2) + " ❤ → "
                            + (int) ((plugin.pacts().currentMax(p) - hp) / 2) + " ❤");
                }
                case HEADS -> {
                    int have = plugin.contracts().countHeads(p);
                    price.add((have >= cost.amount() ? "&a✔ " : "&c✘ ") + "&f" + need + " &8(" + have + "/" + cost.amount() + ")");
                }
                case ITEMS -> {
                    int have = plugin.contracts().countItems(p, cost.material());
                    price.add((have >= cost.amount() ? "&a✔ " : "&c✘ ") + "&f" + need + " &8(" + have + "/" + cost.amount() + ")");
                }
            }
        }
        price.add("");
        price.add("&8Le sacrifice n'est prélevé qu'à la signature.");
        inv.setItem(SLOT_PRICE, named(Material.REDSTONE, "&c&lPRIX DU PACTE", price));

        List<String> fx = new ArrayList<>();
        if (c.sideEffects().isEmpty()) fx.add("&7Aucun effet secondaire connu.");
        else fx.addAll(c.sideEffects());
        fx.add("");
        fx.add("&4Le pacte est définitif (sacrifices non remboursés).");
        inv.setItem(SLOT_EFFECTS, named(Material.NETHER_STAR, "&5&lEFFETS SECONDAIRES", fx));

        long left = Math.max(0, (o.expiresAt - System.currentTimeMillis()) / 1000);
        inv.setItem(SLOT_TIME, named(Material.CLOCK, "&e&lTEMPS RESTANT : &f" + Txt.mmss(left), List.of(
                "&7Sans réponse, le démon s'en ira.")));

        inv.setItem(SLOT_ACCEPT, named(Material.EMERALD, "&a&lACCEPTER LE PACTE", List.of(
                "&7Vous perdrez : &f" + c.describeCosts(),
                "&7Vous obtiendrez : " + d.color() + power.name())));
        inv.setItem(SLOT_REFUSE, named(Material.BARRIER, "&c&lREFUSER LE PACTE", List.of(
                "&7Aucun sacrifice ne sera effectué.")));
    }

    private static String fmt(double v) {
        return v == Math.floor(v) ? String.valueOf((long) v) : String.format(java.util.Locale.ROOT, "%.1f", v);
    }

    private static ItemStack named(Material m, String name, List<String> lore) {
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(Txt.item(name));
        if (!lore.isEmpty()) meta.lore(Txt.items(lore));
        it.setItemMeta(meta);
        return it;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onClick(InventoryClickEvent e) {
        if (!(e.getView().getTopInventory().getHolder(false) instanceof Holder)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (e.getClickedInventory() == null || e.getClickedInventory() != e.getView().getTopInventory()) return;
        int slot = e.getRawSlot();
        if (slot == SLOT_ACCEPT) {
            Bukkit.getScheduler().runTask(plugin, () -> plugin.contracts().accept(p));
        } else if (slot == SLOT_REFUSE) {
            Bukkit.getScheduler().runTask(plugin, () -> plugin.contracts().refuse(p));
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDrag(InventoryDragEvent e) {
        if (e.getView().getTopInventory().getHolder(false) instanceof Holder) e.setCancelled(true);
    }
}
