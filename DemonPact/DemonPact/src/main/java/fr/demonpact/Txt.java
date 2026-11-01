package fr.demonpact;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import java.util.ArrayList;
import java.util.List;

/** Utilitaires de texte : codes couleur &amp; / §, placeholders, formatage du temps. */
public final class Txt {
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();

    private Txt() {}

    /** Convertit une chaîne avec codes couleur (&amp; ou §) en Component. */
    public static Component c(String s) {
        return LEGACY.deserialize(s == null ? "" : s.replace('§', '&'));
    }

    /** Idem, sans italique (pour les noms et lores d'objets). */
    public static Component item(String s) {
        return c(s).decoration(TextDecoration.ITALIC, false);
    }

    public static List<Component> items(List<String> lines) {
        List<Component> out = new ArrayList<>();
        for (String l : lines) out.add(item(l));
        return out;
    }

    public static String plain(Component c) {
        return PlainTextComponentSerializer.plainText().serialize(c);
    }

    /** Retire les codes couleur. */
    public static String strip(String s) {
        return s == null ? "" : s.replaceAll("[&§][0-9a-fA-Fk-oK-OrR]", "");
    }

    /** Remplace {cle} par valeur, paires clé/valeur à la suite. */
    public static String fill(String s, Object... kv) {
        if (s == null) return "";
        for (int i = 0; i + 1 < kv.length; i += 2) {
            s = s.replace("{" + kv[i] + "}", String.valueOf(kv[i + 1]));
        }
        return s;
    }

    public static String mmss(long seconds) {
        if (seconds < 0) seconds = 0;
        return String.format("%d:%02d", seconds / 60, seconds % 60);
    }

    public static String pretty(String enumName) {
        String s = enumName.toLowerCase().replace('_', ' ');
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
