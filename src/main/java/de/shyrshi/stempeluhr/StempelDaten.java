package de.shyrshi.stempeluhr;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
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
    private boolean speichernGesperrt;

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
        if (!datei.exists()) {
            eintraege.clear();
            speichernGesperrt = false;
            return;
        }

        // loadConfiguration(File) verschluckt Parsefehler und liefert dann eine
        // leere Konfiguration. Das würde beim nächsten Speichern alle Zeiten löschen.
        speichernGesperrt = true;
        YamlConfiguration yml = new YamlConfiguration();
        try {
            yml.load(datei);
        } catch (IOException | InvalidConfigurationException ex) {
            plugin.getLogger().log(Level.SEVERE,
                    "Konnte " + DATEINAME + " nicht laden; Speichern ist gesperrt.", ex);
            throw new IllegalStateException("Stempeldaten konnten nicht geladen werden", ex);
        }

        ConfigurationSection spieler = yml.getConfigurationSection("spieler");
        if (spieler == null && !yml.getKeys(false).isEmpty()) {
            plugin.getLogger().severe("Ungültige Struktur in " + DATEINAME + "; Speichern ist gesperrt.");
            throw new IllegalStateException("Ungültige Struktur in " + DATEINAME);
        }

        Map<UUID, Eintrag> geladen = new HashMap<>();
        if (spieler != null) {
            for (String key : spieler.getKeys(false)) {
                UUID uuid;
                try {
                    uuid = UUID.fromString(key);
                } catch (IllegalArgumentException ex) {
                    plugin.getLogger().warning("Ungültige UUID in " + DATEINAME + " übersprungen.");
                    continue;
                }
                ConfigurationSection s = spieler.getConfigurationSection(key);
                if (s == null) {
                    throw new IllegalStateException("Ungültiger Spielereintrag in " + DATEINAME + ": " + key);
                }
                if (!s.getKeys(false).contains("gesamt")) {
                    throw new IllegalStateException("Fehlende Gesamtzeit in " + DATEINAME + ": " + key);
                }

                Eintrag e = new Eintrag();
                e.schichtStart = leseZeit(s, "schichtStart", -1);
                e.lebenszeichen = leseZeit(s, "lebenszeichen", -1);
                e.letzteSchicht = leseZeit(s, "letzteSchicht", -1);
                e.gesamt = Math.max(0, leseZeit(s, "gesamt", 0));   // manipulierte negative Werte abfangen
                geladen.put(uuid, e);
            }
        }
        eintraege.clear();
        eintraege.putAll(geladen);
        speichernGesperrt = false;
    }

    /** Fehlende optionale Werte sind erlaubt, falsch typisierte Werte nicht. */
    private static long leseZeit(ConfigurationSection eintrag, String feld, long standard) {
        Object wert = eintrag.get(feld);
        if (wert == null && !eintrag.getKeys(false).contains(feld)) return standard;
        if (wert instanceof Byte || wert instanceof Short || wert instanceof Integer || wert instanceof Long) {
            return ((Number) wert).longValue();
        }
        throw new IllegalStateException("Ungültiger Zeitwert in " + DATEINAME + ": "
                + eintrag.getCurrentPath() + "." + feld);
    }

    void speichern() {
        if (speichernGesperrt) {
            plugin.getLogger().severe("Speichern von " + DATEINAME + " nach Ladefehler verhindert.");
            return;
        }

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
        Path temporaer = null;
        Path backupTemporaer = null;
        try {
            Path ordner = plugin.getDataFolder().toPath();
            Files.createDirectories(ordner);
            Path ziel = datei.toPath();
            Path backup = ordner.resolve(DATEINAME + ".bak");

            temporaer = Files.createTempFile(ordner, "daten-", ".tmp");
            yml.save(temporaer.toFile());

            // Die bisherige Version behalten, bevor die neue aktiviert wird.
            if (Files.exists(ziel)) {
                backupTemporaer = Files.createTempFile(ordner, "daten-backup-", ".tmp");
                Files.copy(ziel, backupTemporaer, StandardCopyOption.REPLACE_EXISTING);
                ersetzen(backupTemporaer, backup);
                backupTemporaer = null;
            }
            ersetzen(temporaer, ziel);
            temporaer = null;
        } catch (IOException ex) {
            plugin.getLogger().log(Level.SEVERE, "Konnte " + DATEINAME + " nicht speichern", ex);
        } finally {
            loeschen(temporaer);
            loeschen(backupTemporaer);
        }
    }

    private static void ersetzen(Path quelle, Path ziel) throws IOException {
        try {
            Files.move(quelle, ziel, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ex) {
            // Auf Dateisystemen ohne atomare Umbenennung bleibt die Sicherung erhalten.
            Files.move(quelle, ziel, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void loeschen(Path pfad) {
        if (pfad == null) return;
        try {
            Files.deleteIfExists(pfad);
        } catch (IOException ex) {
            plugin.getLogger().log(Level.WARNING, "Temporäre Stempeldaten-Datei konnte nicht gelöscht werden", ex);
        }
    }
}
