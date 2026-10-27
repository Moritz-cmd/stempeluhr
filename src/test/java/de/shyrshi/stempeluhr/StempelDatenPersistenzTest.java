package de.shyrshi.stempeluhr;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class StempelDatenPersistenzTest {

    private ServerMock server;
    private Stempeluhr plugin;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(Stempeluhr.class);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void ladefehlerBewahrtDateiUndSpeichertNicht() throws Exception {
        StempelDaten daten = plugin.daten();
        UUID uuid = UUID.randomUUID();
        daten.get(uuid).gesamt = 12_345;
        Path datei = plugin.getDataFolder().toPath().resolve("daten.yml");
        Files.createDirectories(datei.getParent());
        byte[] defekt = "spieler: [\n".getBytes(StandardCharsets.UTF_8);
        Files.write(datei, defekt);

        assertThrows(IllegalStateException.class, daten::laden);
        assertEquals(12_345, daten.get(uuid).gesamt,
                "Ein fehlgeschlagener Ladevorgang darf den Speicherstand nicht leeren");

        daten.get(uuid).gesamt = 67_890;
        daten.speichern();
        assertArrayEquals(defekt, Files.readAllBytes(datei),
                "Ein späteres Speichern darf die defekte Datei nicht überschreiben");
    }

    @Test
    void zweiteSpeicherungBehältVorherigeVersionAlsBackup() throws Exception {
        StempelDaten daten = plugin.daten();
        UUID uuid = UUID.randomUUID();
        StempelDaten.Eintrag eintrag = daten.get(uuid);
        Path ordner = plugin.getDataFolder().toPath();
        Path datei = ordner.resolve("daten.yml");
        Path backup = ordner.resolve("daten.yml.bak");

        eintrag.gesamt = 60_000;
        daten.speichern();
        String vorher = Files.readString(datei);

        eintrag.gesamt = 120_000;
        daten.speichern();

        assertEquals(vorher, Files.readString(backup));
        assertEquals(60_000, YamlConfiguration.loadConfiguration(backup.toFile())
                .getLong("spieler." + uuid + ".gesamt"));
        assertEquals(120_000, YamlConfiguration.loadConfiguration(datei.toFile())
                .getLong("spieler." + uuid + ".gesamt"));
    }
}
