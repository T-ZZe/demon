package fr.demonpact;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Commande /demon et sous-commandes joueur, modérateur et administrateur. */
public final class DemonCommand implements TabExecutor {
    private final DemonPactPlugin plugin;
    private final Map<UUID, Long> abandonAsk = new HashMap<>();

    public DemonCommand(DemonPactPlugin plugin) { this.plugin = plugin; }

    private void msg(CommandSender s, String text) { s.sendMessage(Txt.c(plugin.prefix() + text)); }

    private boolean need(CommandSender s, String perm) {
        if (s.hasPermission(perm)) return true;
        msg(s, "&cVous n'avez pas la permission.");
        return false;
    }

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        String sub = a.length == 0 ? "info" : a[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "info", "help" -> { if (need(s, "demon.info")) info(s); }
            case "pact" -> { if (need(s, "demon.use") && player(s)) pact((Player) s); }
            case "power" -> { if (need(s, "demon.use") && player(s)) power((Player) s); }
            case "abandon" -> { if (need(s, "demon.use") && player(s)) abandon((Player) s, a); }
            case "item" -> { if (need(s, "demon.use") && player(s)) item((Player) s); }
            case "contract" -> { if (need(s, "demon.use") && player(s)) contract((Player) s); }
            case "stell" -> { if (need(s, "demon.moderator")) stell(s, a); }
            case "summon" -> { if (need(s, "demon.moderator")) summon(s, a); }
            case "despawn" -> {
                if (need(s, "demon.moderator")) {
                    if (plugin.spawner().despawn()) msg(s, "&7Le démon est renvoyé dans les ténèbres.");
                    else msg(s, "&cAucun démon sur la Demon Stell.");
                }
            }
            case "reload" -> {
                if (need(s, "demon.admin")) {
                    plugin.reloadAll();
                    msg(s, "&aConfiguration rechargée.");
                }
            }
            case "give" -> { if (need(s, "demon.admin")) give(s, a); }
            case "remove" -> { if (need(s, "demon.admin")) remove(s, a); }
            case "restore" -> { if (need(s, "demon.admin")) restore(s, a); }
            case "setstell" -> { if (need(s, "demon.admin") && player(s)) setStell((Player) s); }
            default -> msg(s, "&cSous-commande inconnue. &7Tapez &f/demon help&7.");
        }
        return true;
    }

    private boolean player(CommandSender s) {
        if (s instanceof Player) return true;
        msg(s, "&cCette commande est réservée aux joueurs.");
        return false;
    }

    // ------------------------------------------------------------------ Joueur

    private void info(CommandSender s) {
        Location c = plugin.spawner().center();
        String where = c == null ? "&cnon définie &7(un administrateur doit utiliser /demon setstell)"
                : "&f" + c.getWorld().getName() + " &7X:&f" + c.getBlockX() + " &7Y:&f" + c.getBlockY() + " &7Z:&f" + c.getBlockZ();
        String[] lines = {
                "&4&m          &r &c&l⛧ DEMON PACT ⛧ &4&m          ",
                "&c▸ Demon Stell : &7" + where,
                "&c▸ Invocation : &7un modérateur invoque un démon avec &f/demon stell&7. Les runes s'embrasent, le démon surgit.",
                "&c▸ Rencontre : &7le démon choisit un joueur proche de l'autel et lui propose un contrat tiré au hasard.",
                "&c▸ Contrat : &7une interface s'ouvre. &aÉmeraude &7= accepter, &cBarrière &7= refuser. Vous avez &f"
                        + Txt.mmss(plugin.getConfig().getInt("negotiation.time-seconds", 300))
                        + "&7. Clic droit sur le démon ou &f/demon contract &7pour rouvrir l'interface.",
                "&c▸ Pouvoirs : &7tenez l'objet de votre pacte et faites un &fclic droit&7. Chaque pouvoir a un temps de recharge.",
                "&c▸ Pacte actuel : &f/demon pact &7et &f/demon power&7. Abandon : &f/demon abandon&7. Objet perdu : &f/demon item&7.",
                "&8Les pouvoirs sont fixes : aucun niveau, aucune évolution.",
        };
        for (String l : lines) s.sendMessage(Txt.c(l));
        if (s.hasPermission("demon.moderator")) {
            s.sendMessage(Txt.c("&6Modération : &f/demon stell [joueur]&7, &f/demon summon <démon> [joueur]&7, &f/demon despawn"));
        }
        if (s.hasPermission("demon.admin")) {
            s.sendMessage(Txt.c("&6Admin : &f/demon setstell&7, &f/demon reload&7, &f/demon give <joueur> <démon> [contrat]&7, "
                    + "&f/demon remove <joueur> [pouvoir]&7, &f/demon restore <joueur>"));
        }
    }

    private void pact(Player p) {
        List<Pact> list = plugin.pacts().pactsOf(p.getUniqueId());
        if (list.isEmpty()) {
            msg(p, "&7Vous n'avez conclu aucun pacte.");
            return;
        }
        SimpleDateFormat fmt = new SimpleDateFormat("dd/MM/yyyy HH:mm");
        p.sendMessage(Txt.c("&4&m     &r &c&lVos pactes &4&m     "));
        int i = 1;
        for (Pact pact : list) {
            Demon d = plugin.demons().get(pact.demonId());
            Power pw = plugin.powers().power(pact.power());
            String col = d == null ? "&5" : d.color();
            p.sendMessage(Txt.c("&c" + i++ + ". " + col + "&l" + pact.demonId() + " &8| " + col + pw.name()
                    + " &8(" + fmt.format(new Date(pact.since())) + ")"));
            DemonContract c = d == null ? null : d.contract(pact.contractKey());
            if (c != null) p.sendMessage(Txt.c("   &7Sacrifice : &f" + c.describeCosts()));
        }
        double lost = plugin.data().get(p.getUniqueId()).lostHealth();
        p.sendMessage(Txt.c("&7Cœurs perdus : &c" + (int) (lost / 2) + " ❤ &7| Vie maximale : &f" + (int) (plugin.pacts().currentMax(p) / 2) + " ❤"));
    }

    private void power(Player p) {
        List<Pact> list = plugin.pacts().pactsOf(p.getUniqueId());
        if (list.isEmpty()) {
            msg(p, "&7Vous n'avez aucun pouvoir.");
            return;
        }
        for (Pact pact : list) {
            Power pw = plugin.powers().power(pact.power());
            Demon d = plugin.demons().get(pact.demonId());
            String col = d == null ? "&5" : d.color();
            p.sendMessage(Txt.c(col + "&l" + pw.name() + " &8— &7" + pw.description()));
            StringBuilder sb = new StringBuilder("&7");
            if (pw.type().passive()) sb.append("Passif. ");
            else {
                long rem = plugin.cooldowns().remaining(p.getUniqueId(), pact.power().name());
                sb.append("Recharge : &f").append(num(pw.cooldown())).append("s&7 (")
                        .append(rem > 0 ? "&c" + String.format(Locale.ROOT, "%.1f", rem / 1000.0) + "s restantes&7" : "&aprêt&7").append(") ");
            }
            if (pw.duration() > 0) sb.append("Durée : &f").append(num(pw.duration())).append("s&7 ");
            if (pw.range() > 0) sb.append("Portée : &f").append(num(pw.range())).append("&7 ");
            p.sendMessage(Txt.c(sb.toString()));
        }
        p.sendMessage(Txt.c("&8Utilisation : objet du pacte en main, clic droit."));
    }

    private String num(double v) { return v == Math.floor(v) ? String.valueOf((long) v) : String.format(Locale.ROOT, "%.1f", v); }

    private void abandon(Player p, String[] a) {
        if (!plugin.getConfig().getBoolean("pact.allow-abandon", true)) {
            msg(p, "&cL'abandon d'un pacte est désactivé.");
            return;
        }
        List<Pact> list = plugin.pacts().pactsOf(p.getUniqueId());
        if (list.isEmpty()) {
            msg(p, "&7Vous n'avez aucun pacte.");
            return;
        }
        boolean confirm = a.length > 1 && a[a.length - 1].equalsIgnoreCase("confirm");
        Pact target = null;
        if (list.size() == 1) target = list.get(0);
        else if (a.length > 1) {
            try {
                int idx = Integer.parseInt(a[1]);
                if (idx >= 1 && idx <= list.size()) target = list.get(idx - 1);
            } catch (NumberFormatException ignored) {
                // sélecteur invalide
            }
        }
        if (target == null) {
            msg(p, "&7Précisez le pacte : &f/demon abandon <numéro> &7(voir /demon pact).");
            return;
        }
        Long ask = abandonAsk.get(p.getUniqueId());
        if (!confirm || ask == null || System.currentTimeMillis() - ask > 30000) {
            abandonAsk.put(p.getUniqueId(), System.currentTimeMillis());
            msg(p, "&cAbandonner ce pacte supprime votre pouvoir &4sans rendre les sacrifices&c. Tapez &f/demon abandon"
                    + (list.size() > 1 ? " " + (list.indexOf(target) + 1) : "") + " confirm &cdans les 30 secondes.");
            return;
        }
        abandonAsk.remove(p.getUniqueId());
        plugin.pacts().remove(p.getUniqueId(), target);
        Fx.sound(p.getLocation(), "entity.wither.death", 0.6f, 1.2f);
        msg(p, "&7Le pacte est rompu. Votre pouvoir s'éteint.");
    }

    private void item(Player p) {
        if (!plugin.getConfig().getBoolean("items.allow-reclaim", true)) {
            msg(p, "&cLa récupération d'objet est désactivée.");
            return;
        }
        PlayerData pd = plugin.data().get(p.getUniqueId());
        long wait = plugin.getConfig().getLong("items.reclaim-cooldown-seconds", 60) * 1000;
        long left = pd.lastReclaim() + wait - System.currentTimeMillis();
        if (left > 0) {
            msg(p, "&cPatientez encore &f" + (left / 1000 + 1) + "s&c.");
            return;
        }
        int given = 0;
        for (Pact pact : new ArrayList<>(pd.pacts())) {
            if (hasValidItem(p, pact)) continue;
            if (plugin.pacts().reissue(p, pact)) given++;
        }
        if (given == 0) {
            msg(p, "&7Vous possédez déjà tous vos objets de pacte.");
            return;
        }
        pd.lastReclaim(System.currentTimeMillis());
        plugin.data().saveNow();
        msg(p, "&7Un nouvel objet de pacte vous est remis. &8L'ancien est désormais inerte.");
    }

    private boolean hasValidItem(Player p, Pact pact) {
        for (ItemStack it : p.getInventory().getContents()) {
            DemonItem.Info i = plugin.items().read(it);
            if (i != null && i.serial().equals(pact.serial())) return true;
        }
        return false;
    }

    private void contract(Player p) {
        ContractManager.Offer o = plugin.contracts().offerOf(p.getUniqueId());
        if (o == null || o.resolved) msg(p, "&7Aucun contrat ne vous attend.");
        else plugin.gui().open(p, o);
    }

    // ------------------------------------------------------------------ Modération

    private void stell(CommandSender s, String[] a) {
        Player target = null;
        if (a.length > 1) {
            target = Bukkit.getPlayerExact(a[1]);
            if (target == null) {
                msg(s, "&cJoueur introuvable.");
                return;
            }
        }
        Demon d = plugin.demons().random();
        if (d == null) {
            msg(s, "&cAucun démon chargé.");
            return;
        }
        String err = plugin.spawner().summon(d, target);
        if (err != null) msg(s, "&c" + err);
        else msg(s, "&7Invocation lancée... " + d.tag());
    }

    private void summon(CommandSender s, String[] a) {
        if (a.length < 2) {
            msg(s, "&cUsage : /demon summon <démon> [joueur]");
            return;
        }
        Demon d = plugin.demons().get(a[1]);
        if (d == null) {
            msg(s, "&cDémon inconnu. Disponibles : " + String.join(", ", plugin.demons().all().stream().map(Demon::id).toList()));
            return;
        }
        Player target = null;
        if (a.length > 2) {
            target = Bukkit.getPlayerExact(a[2]);
            if (target == null) {
                msg(s, "&cJoueur introuvable.");
                return;
            }
        }
        String err = plugin.spawner().summon(d, target);
        if (err != null) msg(s, "&c" + err);
        else msg(s, "&7Invocation de " + d.tag() + " &7lancée.");
    }

    // ------------------------------------------------------------------ Administration

    private void setStell(Player p) {
        plugin.spawner().setCenter(p.getLocation(), plugin.getConfig().getBoolean("stell.build-on-set", true));
        msg(p, "&7Demon Stell définie ici"
                + (plugin.getConfig().getBoolean("stell.build-on-set", true) ? " et construite." : ". &8(construction automatique désactivée)"));
    }

    private void give(CommandSender s, String[] a) {
        if (a.length < 3) {
            msg(s, "&cUsage : /demon give <joueur> <démon> [contrat]");
            return;
        }
        Player t = Bukkit.getPlayerExact(a[1]);
        Demon d = plugin.demons().get(a[2]);
        if (t == null || d == null) {
            msg(s, "&cJoueur ou démon introuvable.");
            return;
        }
        DemonContract c = null;
        if (a.length > 3) c = d.contract(a[3]);
        else {
            List<DemonContract> ok = new ArrayList<>();
            for (DemonContract x : d.contracts()) if (plugin.pacts().canTake(t.getUniqueId(), x.power()) == null) ok.add(x);
            if (!ok.isEmpty()) c = ok.get((int) (Math.random() * ok.size()));
        }
        if (c == null) {
            msg(s, "&cContrat introuvable ou aucun pouvoir disponible pour ce joueur.");
            return;
        }
        String err = plugin.pacts().canTake(t.getUniqueId(), c.power());
        if (err != null) {
            msg(s, "&c" + t.getName() + " : " + err);
            return;
        }
        plugin.pacts().grant(t, d, c);
        Fx.spawn(t.getLocation().add(0, 1, 0), d.element().particle, 50, 0.5, 1, 0.5, 0.1);
        msg(t, "&7Un pacte avec " + d.tag() + " &7vous est accordé : " + d.color() + plugin.powers().power(c.power()).name());
        msg(s, "&7Pacte accordé à &f" + t.getName() + "&7 (" + c.power() + ").");
    }

    private void remove(CommandSender s, String[] a) {
        if (a.length < 2) {
            msg(s, "&cUsage : /demon remove <joueur> [pouvoir]");
            return;
        }
        OfflinePlayer op = Bukkit.getOfflinePlayer(a[1]);
        if (!op.isOnline() && !op.hasPlayedBefore()) {
            msg(s, "&cJoueur inconnu.");
            return;
        }
        PowerType only = a.length > 2 ? PowerType.of(a[2]) : null;
        List<Pact> list = new ArrayList<>(plugin.pacts().pactsOf(op.getUniqueId()));
        int n = 0;
        for (Pact pact : list) {
            if (only != null && pact.power() != only) continue;
            plugin.pacts().remove(op.getUniqueId(), pact);
            n++;
        }
        msg(s, "&7" + n + " pacte(s) retiré(s) à &f" + a[1] + "&7. &8(la vie sacrifiée n'est pas rendue : /demon restore)");
    }

    private void restore(CommandSender s, String[] a) {
        if (a.length < 2) {
            msg(s, "&cUsage : /demon restore <joueur>");
            return;
        }
        OfflinePlayer op = Bukkit.getOfflinePlayer(a[1]);
        if (!op.isOnline() && !op.hasPlayedBefore()) {
            msg(s, "&cJoueur inconnu.");
            return;
        }
        plugin.pacts().restoreHealth(op.getUniqueId());
        msg(s, "&7Vie maximale de &f" + a[1] + " &7restaurée.");
    }

    // ------------------------------------------------------------------ Complétion

    @Override
    public List<String> onTabComplete(CommandSender s, Command cmd, String label, String[] a) {
        List<String> out = new ArrayList<>();
        if (a.length == 1) {
            List<String> subs = new ArrayList<>(List.of("help", "info", "pact", "power", "abandon", "item", "contract"));
            if (s.hasPermission("demon.moderator")) subs.addAll(List.of("stell", "summon", "despawn"));
            if (s.hasPermission("demon.admin")) subs.addAll(List.of("reload", "give", "remove", "restore", "setstell"));
            for (String x : subs) if (x.startsWith(a[0].toLowerCase(Locale.ROOT))) out.add(x);
            return out;
        }
        String sub = a[0].toLowerCase(Locale.ROOT);
        if (a.length == 2) {
            if (sub.equals("summon") && s.hasPermission("demon.moderator")) {
                for (Demon d : plugin.demons().all()) if (d.id().toLowerCase().startsWith(a[1].toLowerCase())) out.add(d.id());
            } else if (List.of("stell", "give", "remove", "restore").contains(sub) && s.hasPermission("demon.moderator")) {
                for (Player p : Bukkit.getOnlinePlayers()) if (p.getName().toLowerCase().startsWith(a[1].toLowerCase())) out.add(p.getName());
            } else if (sub.equals("abandon")) {
                out.add("confirm");
            }
        } else if (a.length == 3) {
            if (sub.equals("give") && s.hasPermission("demon.admin")) {
                for (Demon d : plugin.demons().all()) if (d.id().toLowerCase().startsWith(a[2].toLowerCase())) out.add(d.id());
            } else if (sub.equals("summon") && s.hasPermission("demon.moderator")) {
                for (Player p : Bukkit.getOnlinePlayers()) if (p.getName().toLowerCase().startsWith(a[2].toLowerCase())) out.add(p.getName());
            } else if (sub.equals("remove") && s.hasPermission("demon.admin")) {
                for (PowerType t : PowerType.values()) if (t.name().toLowerCase().startsWith(a[2].toLowerCase())) out.add(t.name());
            }
        } else if (a.length == 4 && sub.equals("give") && s.hasPermission("demon.admin")) {
            Demon d = plugin.demons().get(a[2]);
            if (d != null) for (DemonContract c : d.contracts()) if (c.key().startsWith(a[3].toLowerCase())) out.add(c.key());
        }
        return out;
    }
}
