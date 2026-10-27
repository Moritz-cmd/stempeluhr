package de.shyrshi.stempeluhr;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Speichert die Stempeldaten aller Spieler in plugins/Stempeluhr/daten.yml.
 *
 * Pro Spieler:
 *  - schichtStart : Zeitstempel (ms) der laufenden Schicht, fehlt wenn ausgestempelt
 *  - lebenszeichen: zuletzt bekannter Zeitpunkt online (für Absturz-Wiederherstellung)
 *  - letzteSchicht: Dauer der zuletzt beendeten Schicht (ms)
 *  - gesamt       : Summe aller beendeten Schichten (ms)
 */
final class StempelDaten {

    static final class Eintrag {
        long schichtStart = -1;
        long lebenszeichen = -1;
        long letzteSchicht = -1;
        long gesamt = 0;

        boolean istEingestempelt() {
            return schichtStart > 0;
        }
    }

    private static final String DATEINAME = "daten.yml";

    private final JavaPlugin plugin;
    private final File datei;
    private final Map<UUID, Eintrag> eintraege = new HashMap<>();

    StempelDaten(JavaPlugin plugin) {
        this.plugin = plugin;
        this.datei = new File(plugin.getDataFolder(), DATEINAME);
    }

    Eintrag get(UUID uuid) {
        return eintraege.computeIfAbsent(uuid, k -> new Eintrag());
    }

    /** Alle bekannten Einträge (nur lesend), z. B. für die Rangliste. */
    Map<UUID, Eintrag> alle() {
        return Collections.unmodifiableMap(eintraege);
    }

    void laden() {
        eintraege.clear();
        if (!datei.exists()) return;

        YamlConfiguration yml = YamlConfiguration.loadConfiguration(datei);
        ConfigurationSection spieler = yml.getConfigurationSection("spieler");
        if (spieler == null) return;

        for (String key : spieler.getKeys(false)) {
            UUID uuid;
            try {
                uuid = UUID.fromString(key);
            } catch (IllegalArgumentException ex) {
                plugin.getLogger().warning("Ungültige UUID in " + DATEINAME + " übersprungen.");
                continue;
            }
            ConfigurationSection s = spieler.getConfigurationSection(key);
            if (s == null) continue;

            Eintrag e = new Eintrag();
            e.schichtStart = s.getLong("schichtStart", -1);
            e.lebenszeichen = s.getLong("lebenszeichen", -1);
            e.letzteSchicht = s.getLong("letzteSchicht", -1);
            e.gesamt = Math.max(0, s.getLong("gesamt", 0));   // manipulierte negative Werte abfangen
            eintraege.put(uuid, e);
        }
    }

    void speichern() {
        YamlConfiguration yml = new YamlConfiguration();
        for (Map.Entry<UUID, Eintrag> entry : eintraege.entrySet()) {
            String pfad = "spieler." + entry.getKey();
            Eintrag e = entry.getValue();
            if (e.istEingestempelt()) {
                yml.set(pfad + ".schichtStart", e.schichtStart);
                yml.set(pfad + ".lebenszeichen", e.lebenszeichen);
            }
            if (e.letzteSchicht >= 0) yml.set(pfad + ".letzteSchicht", e.letzteSchicht);
            yml.set(pfad + ".gesamt", e.gesamt);
        }
        try {
            Files.createDirectories(plugin.getDataFolder().toPath());
            yml.save(datei);
        } catch (IOException ex) {
            plugin.getLogger().log(Level.SEVERE, "Konnte " + DATEINAME + " nicht speichern", ex);
        }
    }
}
