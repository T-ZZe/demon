package fr.demonpact;

import java.util.UUID;

/** Un pacte conclu entre un joueur et un démon. */
public final class Pact {
    private final UUID owner;
    private final String demonId;
    private final String contractKey;
    private final PowerType power;
    private UUID serial;
    private final long since;

    public Pact(UUID owner, String demonId, String contractKey, PowerType power, UUID serial, long since) {
        this.owner = owner;
        this.demonId = demonId;
        this.contractKey = contractKey;
        this.power = power;
        this.serial = serial;
        this.since = since;
    }

    public UUID owner() { return owner; }
    public String demonId() { return demonId; }
    public String contractKey() { return contractKey; }
    public PowerType power() { return power; }
    /** Numéro de série de l'objet valide : toute copie portant un autre numéro est inerte. */
    public UUID serial() { return serial; }
    public void serial(UUID s) { this.serial = s; }
    public long since() { return since; }
}
