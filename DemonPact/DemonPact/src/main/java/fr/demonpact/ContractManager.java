package fr.demonpact;

import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

import net.kyori.adventure.title.Title;

/** Propositions aléatoires de contrats, négociation chronométrée, acceptation et refus. */
public final class ContractManager {

    /** Contrat proposé à un joueur. */
    public static final class Offer {
        public final UUID player;
        public final Demon demon;
        public final DemonContract contract;
        public final long expiresAt;
        public final DemonSpawner.Session session;
        public boolean resolved;
        public BukkitTask task;

        Offer(UUID player, Demon demon, DemonContract contract, long expiresAt, DemonSpawner.Session session) {
            this.player = player;
            this.demon = demon;
            this.contract = contract;
            this.expiresAt = expiresAt;
            this.session = session;
        }
    }

    private final DemonPactPlugin plugin;
    private final Map<UUID, Offer> offers = new HashMap<>();
    private final Map<String, String> lastPick = new HashMap<>();
    private final Random rnd = new Random();

    public ContractManager(DemonPactPlugin plugin) { this.plugin = plugin; }

    public Offer offerOf(UUID id) { return offers.get(id); }

    // ------------------------------------------------------------------ Proposition

    public void startOffer(DemonSpawner.Session s, Player p) {
        if (s == null || s.ending || !p.isOnline()) return;
        Demon d = s.demon;
        DemonContract c = pick(d, p);
        DemonManager dm = plugin.demons();
        if (c == null) {
            dm.say(d, dm.format("Tu n'as plus rien à m'offrir, {player}. Je m'en vais.", p, null, d), p, false);
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> plugin.spawner().dismiss(s, "nothing"), 60L);
            return;
        }
        int seconds = plugin.getConfig().getInt("negotiation.time-seconds", 300);
        final Offer o = new Offer(p.getUniqueId(), d, c, System.currentTimeMillis() + seconds * 1000L, s);
        offers.put(p.getUniqueId(), o);
        s.offered.add(p.getUniqueId());
        s.offer = o;
        s.target = p.getUniqueId();
        lastPick.put(d.id(), c.key());

        Power power = plugin.powers().power(c.power());
        dm.say(d, dm.format(d.line("propose"), p, c, d), p, false);
        dm.say(d, dm.format(d.line("sacrifice." + c.primary().name()), p, c, d), p, false);
        p.sendMessage(Txt.c(plugin.prefix() + "&7Contrat : &f" + c.describeCosts() + " &8→ " + d.color() + power.name()));
        p.sendMessage(Txt.c(plugin.prefix() + "&7Vous avez &f" + Txt.mmss(seconds)
                + " &7pour accepter ou refuser. &8(clic droit sur le démon ou /demon contract)"));
        Fx.sound(p.getLocation(), "entity.wither.ambient", 1f, 0.6f);

        long delay = plugin.getConfig().getLong("negotiation.open-gui-delay-ticks", 40L);
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (!o.resolved && p.isOnline()) plugin.gui().open(p, o);
        }, delay);

        o.task = new BukkitRunnable() {
            @Override
            public void run() {
                if (o.resolved) { cancel(); return; }
                Player pl = Bukkit.getPlayer(o.player);
                if (pl == null) { cancel(); playerLeft(o); return; }
                if (System.currentTimeMillis() >= o.expiresAt) { cancel(); expire(o); return; }
                plugin.gui().refresh(pl, o);
            }
        }.runTaskTimer(plugin, 20L, 20L);
    }

    private DemonContract pick(Demon d, Player p) {
        List<DemonContract> ok = new ArrayList<>();
        for (DemonContract c : d.contracts()) {
            if (plugin.pacts().canTake(p.getUniqueId(), c.power()) != null) continue;
            double hp = heartsNeeded(c);
            if (hp > 0 && !plugin.pacts().canLose(p, hp)) continue;
            ok.add(c);
        }
        if (ok.isEmpty()) return null;
        String last = lastPick.get(d.id());
        if (ok.size() > 1) ok.removeIf(c -> c.key().equals(last));
        return ok.get(rnd.nextInt(ok.size()));
    }

    // ------------------------------------------------------------------ Coûts

    public double heartsNeeded(DemonContract c) {
        double hp = 0;
        for (DemonContract.Cost cost : c.costs()) {
            if (cost.type() == DemonContract.CostType.HEARTS) hp += cost.amount() * plugin.pacts().heartHealth();
        }
        return hp;
    }

    public int countHeads(Player p) {
        int n = 0;
        PlayerInventory inv = p.getInventory();
        for (int i = 0; i < 36; i++) {
            ItemStack it = inv.getItem(i);
            if (plugin.heads().isGenuine(it, p)) n += it.getAmount();
        }
        return n;
    }

    public int countItems(Player p, org.bukkit.Material mat) {
        int n = 0;
        PlayerInventory inv = p.getInventory();
        ItemStack ref = new ItemStack(mat);
        for (int i = 0; i < 36; i++) {
            ItemStack it = inv.getItem(i);
            if (it != null && it.isSimilar(ref)) n += it.getAmount();
        }
        return n;
    }

    /** Retourne un message d'erreur si le joueur ne peut pas payer, sinon null. */
    public String checkCosts(Player p, DemonContract c) {
        double hp = heartsNeeded(c);
        if (hp > 0 && !plugin.pacts().canLose(p, hp)) {
            return "Ce sacrifice vous laisserait moins de "
                    + (int) (plugin.getConfig().getDouble("sacrifice.min-max-health", 6.0) / 2) + " cœurs de vie maximale.";
        }
        for (DemonContract.Cost cost : c.costs()) {
            switch (cost.type()) {
                case HEADS -> {
                    int have = countHeads(p);
                    if (have < cost.amount()) return "Il vous manque des têtes de joueurs (" + have + "/" + cost.amount() + ").";
                }
                case ITEMS -> {
                    int have = countItems(p, cost.material());
                    if (have < cost.amount()) {
                        return "Il vous manque des ressources : " + Txt.pretty(cost.material().name()) + " (" + have + "/" + cost.amount() + ").";
                    }
                }
                default -> { }
            }
        }
        return null;
    }

    private void pay(Player p, DemonContract c) {
        double hp = heartsNeeded(c);
        for (DemonContract.Cost cost : c.costs()) {
            switch (cost.type()) {
                case HEADS -> removeHeads(p, cost.amount());
                case ITEMS -> removeItems(p, cost.material(), cost.amount());
                default -> { }
            }
        }
        if (hp > 0) plugin.pacts().addLostHealth(p, hp);
    }

    private void removeHeads(Player p, int amount) {
        PlayerInventory inv = p.getInventory();
        for (int i = 0; i < 36 && amount > 0; i++) {
            ItemStack it = inv.getItem(i);
            if (!plugin.heads().isGenuine(it, p)) continue;
            int take = Math.min(amount, it.getAmount());
            amount -= take;
            it.setAmount(it.getAmount() - take);
            inv.setItem(i, it.getAmount() <= 0 ? null : it);
        }
    }

    private void removeItems(Player p, org.bukkit.Material mat, int amount) {
        PlayerInventory inv = p.getInventory();
        ItemStack ref = new ItemStack(mat);
        for (int i = 0; i < 36 && amount > 0; i++) {
            ItemStack it = inv.getItem(i);
            if (it == null || !it.isSimilar(ref)) continue;
            int take = Math.min(amount, it.getAmount());
            amount -= take;
            it.setAmount(it.getAmount() - take);
            inv.setItem(i, it.getAmount() <= 0 ? null : it);
        }
    }

    // ------------------------------------------------------------------ Décisions

    public void accept(Player p) {
        Offer o = offers.get(p.getUniqueId());
        if (o == null || o.resolved) {
            p.sendMessage(Txt.c(plugin.prefix() + "&cAucun contrat ne vous attend."));
            return;
        }
        if (System.currentTimeMillis() >= o.expiresAt) { expire(o); return; }
        String err = plugin.pacts().canTake(p.getUniqueId(), o.contract.power());
        if (err == null) err = checkCosts(p, o.contract);
        if (err != null) {
            p.sendMessage(Txt.c(plugin.prefix() + "&c" + err));
            Fx.sound(p.getLocation(), "entity.villager.no", 1f, 0.8f);
            plugin.gui().refresh(p, o);
            return;
        }
        o.resolved = true;
        if (o.task != null) o.task.cancel();
        offers.remove(p.getUniqueId());
        double hp = heartsNeeded(o.contract);
        pay(p, o.contract);
        Pact pact = plugin.pacts().grant(p, o.demon, o.contract);
        plugin.gui().close(p);
        signature(p, o.demon, o.contract, pact, hp);
        DemonManager dm = plugin.demons();
        dm.say(o.demon, dm.format(o.demon.line("accept"), p, o.contract, o.demon), p, false);
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> plugin.spawner().dismiss(o.session, "accepted"), 80L);
    }

    public void refuse(Player p) {
        Offer o = offers.get(p.getUniqueId());
        if (o == null || o.resolved) return;
        finish(o);
        plugin.gui().close(p);
        p.sendMessage(Txt.c(plugin.prefix() + "&7Vous avez refusé le pacte. Aucun sacrifice n'a été effectué."));
        DemonManager dm = plugin.demons();
        dm.say(o.demon, dm.format(o.demon.line("refuse"), p, o.contract, o.demon), p, false);
        follow(o.session, plugin.getConfig().getString("negotiation.on-refuse", "DESPAWN"));
    }

    private void expire(Offer o) {
        finish(o);
        Player p = Bukkit.getPlayer(o.player);
        if (p != null) {
            plugin.gui().close(p);
            p.sendMessage(Txt.c(plugin.prefix() + "&7Le temps de négociation est écoulé."));
        }
        DemonManager dm = plugin.demons();
        dm.say(o.demon, dm.format(o.demon.line("timeout"), p, o.contract, o.demon), p, false);
        follow(o.session, plugin.getConfig().getString("negotiation.on-timeout", "NEXT_PLAYER"));
    }

    private void playerLeft(Offer o) {
        finish(o);
        DemonManager dm = plugin.demons();
        dm.say(o.demon, dm.format(o.demon.line("timeout"), null, o.contract, o.demon), null, false);
        follow(o.session, plugin.getConfig().getString("negotiation.on-timeout", "NEXT_PLAYER"));
    }

    /** Déconnexion d'un joueur ayant un contrat en attente. */
    public void onQuit(Player p) {
        Offer o = offers.get(p.getUniqueId());
        if (o != null && !o.resolved) {
            if (o.task != null) o.task.cancel();
            playerLeft(o);
        }
    }

    private void finish(Offer o) {
        o.resolved = true;
        if (o.task != null) o.task.cancel();
        offers.remove(o.player);
        if (o.session != null) o.session.offer = null;
    }

    /** Suite après un refus ou une expiration : disparaître, ou se tourner vers un autre joueur. */
    private void follow(DemonSpawner.Session s, String mode) {
        if (s == null || s.ending) return;
        if ("NEXT_PLAYER".equalsIgnoreCase(mode)) {
            Player next = plugin.spawner().randomCandidate(s.offered);
            if (next != null) {
                plugin.getServer().getScheduler().runTaskLater(plugin, () -> startOffer(s, next), 60L);
                return;
            }
        }
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> plugin.spawner().dismiss(s, "declined"), 60L);
    }

    /** Annule les contrats liés à une session (renvoi du démon). */
    public void cancelSession(DemonSpawner.Session s) {
        Iterator<Offer> it = offers.values().iterator();
        List<Offer> toClose = new ArrayList<>();
        while (it.hasNext()) {
            Offer o = it.next();
            if (o.session == s) toClose.add(o);
        }
        for (Offer o : toClose) {
            finish(o);
            Player p = Bukkit.getPlayer(o.player);
            if (p != null) plugin.gui().close(p);
        }
    }

    // ------------------------------------------------------------------ Effets de signature

    private void signature(Player p, Demon d, DemonContract c, Pact pact, double hp) {
        Location l = p.getLocation();
        Power power = plugin.powers().power(c.power());
        l.getWorld().strikeLightningEffect(l);
        Fx.sound(l, "entity.wither.spawn", 0.8f, 1.4f);
        Fx.sound(l, "block.end_portal.spawn", 0.6f, 1.6f);
        Fx.spawn(l.clone().add(0, 1, 0), "explosion_emitter", 1, 0, 0, 0, 0);
        Color col = d.element().color;
        new BukkitRunnable() {
            int t = 0;
            @Override
            public void run() {
                if (!p.isOnline() || t >= 30) { cancel(); return; }
                Location base = p.getLocation();
                for (int i = 0; i < 3; i++) {
                    double a = t * 0.5 + i * 2.1;
                    double r = 1.6 - t * 0.04;
                    Location pt = base.clone().add(Math.cos(a) * r, t * 0.08, Math.sin(a) * r);
                    Fx.dust(pt, col, 1.6f, 3, 0.05);
                    Fx.spawn(pt, d.element().particle, 2, 0.05, 0.05, 0.05, 0.01);
                    Fx.dust(pt, Color.fromRGB(10, 0, 10), 1.3f, 2, 0.05);
                }
                t++;
            }
        }.runTaskTimer(plugin, 0L, 1L);
        p.showTitle(Title.title(Txt.c(d.color() + "&lPACTE SCELLÉ"), Txt.c("&7" + power.name() + " &8| &c" + c.describeCosts())));
        p.sendMessage(Txt.c(plugin.prefix() + "&4Pacte conclu avec " + d.tag() + "&4 : " + d.color() + power.name()));
        if (hp > 0) {
            p.sendMessage(Txt.c(plugin.prefix() + "&cVous perdez définitivement &4" + (int) (hp / 2) + " ❤&c. Vie maximale actuelle : &f"
                    + (int) (plugin.pacts().currentMax(p) / 2) + " ❤"));
        }
        p.sendMessage(Txt.c(plugin.prefix() + "&7Votre objet de pacte vous a été remis. Clic droit pour utiliser le pouvoir."));
    }

    public void shutdown() {
        for (Offer o : new ArrayList<>(offers.values())) {
            if (o.task != null) o.task.cancel();
        }
        offers.clear();
    }
}
