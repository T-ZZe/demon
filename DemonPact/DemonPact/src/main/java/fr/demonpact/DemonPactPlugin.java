package fr.demonpact;

import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.File;

/** Point d'entrée du plugin DemonPact. */
public final class DemonPactPlugin extends JavaPlugin {
    private PlayerDataManager data;
    private CooldownManager cooldowns;
    private RegionGuard regionGuard;
    private DemonItem items;
    private Heads heads;
    private DemonManager demons;
    private PactManager pacts;
    private PowerManager powers;
    private DemonSpawner spawner;
    private ContractManager contracts;
    private DemonGUI gui;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        if (!new File(getDataFolder(), "dialogues.yml").exists()) saveResource("dialogues.yml", false);

        data = new PlayerDataManager(this);
        cooldowns = new CooldownManager();
        regionGuard = new RegionGuard(this);
        items = new DemonItem(this);
        heads = new Heads(this);
        demons = new DemonManager(this);
        pacts = new PactManager(this);
        powers = new PowerManager(this);
        spawner = new DemonSpawner(this);
        contracts = new ContractManager(this);
        gui = new DemonGUI(this);

        data.load();
        regionGuard.load();
        demons.load();
        powers.load();
        spawner.load();
        spawner.purgeStray();

        getServer().getPluginManager().registerEvents(powers, this);
        getServer().getPluginManager().registerEvents(gui, this);
        getServer().getPluginManager().registerEvents(new DemonListener(this), this);

        PluginCommand cmd = getCommand("demon");
        if (cmd != null) {
            DemonCommand dc = new DemonCommand(this);
            cmd.setExecutor(dc);
            cmd.setTabCompleter(dc);
        }

        // Sauvegarde périodique et réapplication de la vie maximale des joueurs déjà connectés (rechargement).
        new BukkitRunnable() {
            @Override
            public void run() { data.saveIfDirty(); }
        }.runTaskTimer(this, 1200L, 1200L);
        getServer().getOnlinePlayers().forEach(p -> {
            if (data.has(p.getUniqueId())) pacts.applyHealth(p);
        });
        getLogger().info("DemonPact activé : " + demons.all().size() + " démons.");
    }

    @Override
    public void onDisable() {
        if (contracts != null) contracts.shutdown();
        if (spawner != null) spawner.shutdown();
        if (powers != null) powers.shutdown();
        if (data != null) data.saveNow();
    }

    /** Recharge config.yml et dialogues.yml. */
    public void reloadAll() {
        reloadConfig();
        regionGuard.load();
        demons.load();
        powers.load();
        spawner.load();
    }

    public String prefix() { return getConfig().getString("prefix", "&4&l[&c&lDemonPact&4&l] &7"); }

    public PlayerDataManager data() { return data; }
    public CooldownManager cooldowns() { return cooldowns; }
    public RegionGuard regionGuard() { return regionGuard; }
    public DemonItem items() { return items; }
    public Heads heads() { return heads; }
    public DemonManager demons() { return demons; }
    public PactManager pacts() { return pacts; }
    public PowerManager powers() { return powers; }
    public DemonSpawner spawner() { return spawner; }
    public ContractManager contracts() { return contracts; }
    public DemonGUI gui() { return gui; }
}
