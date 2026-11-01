package fr.demonpact;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Données persistantes d'un joueur (identifié par UUID). */
public final class PlayerData {
    private final UUID uuid;
    private String name = "";
    private double lostHealth;
    private long lastReclaim;
    private final List<Pact> pacts = new ArrayList<>();

    public PlayerData(UUID uuid) { this.uuid = uuid; }

    public UUID uuid() { return uuid; }
    public String name() { return name; }
    public void name(String n) { this.name = n == null ? "" : n; }
    public double lostHealth() { return lostHealth; }
    public void lostHealth(double v) { this.lostHealth = Math.max(0, v); }
    public long lastReclaim() { return lastReclaim; }
    public void lastReclaim(long t) { this.lastReclaim = t; }
    public List<Pact> pacts() { return pacts; }
}
