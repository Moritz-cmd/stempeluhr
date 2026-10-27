package de.shyrshi.stempeluhr;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Location;
import org.bukkit.event.player.PlayerMoveEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InaktivitaetTest {

    private static final long START = 1_800_000_000_000L;
    private final AtomicLong jetzt = new AtomicLong(START);
    private ServerMock server;
    private Stempeluhr plugin;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(Stempeluhr.class);
        plugin.setUhr(jetzt::get);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private void bewegen(PlayerMock p, boolean position) {
        Location von = p.getLocation();
        Location nach = von.clone();
        if (position) nach.add(1, 0, 0);
        else nach.setYaw(nach.getYaw() + 90);
        server.getPluginManager().callEvent(new PlayerMoveEvent(p, von, nach));
    }

    private static List<String> nachrichten(PlayerMock p) {
        List<String> liste = new ArrayList<>();
        Component c;
        while ((c = p.nextComponentMessage()) != null) {
            liste.add(PlainTextComponentSerializer.plainText().serialize(c));
        }
        return liste;
    }

    @Test
    void stempeltExaktNachFuenfMinutenEinmalAus() {
        PlayerMock p = server.addPlayer("Anton");
        StempelDaten.Eintrag e = plugin.daten().get(p.getUniqueId());
        nachrichten(p);

        jetzt.addAndGet(299_000);
        plugin.pruefeInaktivitaet();
        assertTrue(e.istEingestempelt());

        jetzt.addAndGet(1_000);
        plugin.pruefeInaktivitaet();
        assertFalse(e.istEingestempelt());
        assertEquals(300_000, e.letzteSchicht);
        assertEquals(300_000, e.gesamt);
        assertTrue(nachrichten(p).stream().anyMatch(n -> n.contains("Arbeitszeit betrug du wirst ausgestochen")));

        jetzt.addAndGet(60_000);
        plugin.pruefeInaktivitaet();
        assertEquals(300_000, e.gesamt);
    }

    @Test
    void positionsaenderungSetztFristZurueckAberKameradrehungNicht() {
        PlayerMock p = server.addPlayer("Anton");
        StempelDaten.Eintrag e = plugin.daten().get(p.getUniqueId());
        jetzt.addAndGet(299_000);
        bewegen(p, true);
        jetzt.addAndGet(299_000);
        bewegen(p, false);
        plugin.pruefeInaktivitaet();
        assertTrue(e.istEingestempelt());

        jetzt.addAndGet(1_000);
        plugin.pruefeInaktivitaet();
        assertFalse(e.istEingestempelt());
        assertEquals(599_000, e.letzteSchicht);
    }

    @Test
    void erneutesEinstempelnErfordertBewegungUndStartetNeueFrist() {
        PlayerMock p = server.addPlayer("Anton");
        StempelDaten.Eintrag e = plugin.daten().get(p.getUniqueId());
        jetzt.addAndGet(300_000);
        plugin.pruefeInaktivitaet();
        nachrichten(p);

        p.performCommand("einstempeln");
        assertFalse(e.istEingestempelt());
        assertTrue(nachrichten(p).stream().anyMatch(n -> n.contains("Bewege dich zuerst")));

        bewegen(p, true);
        p.performCommand("einstempeln");
        assertTrue(e.istEingestempelt());
        assertEquals(START + 300_000, e.schichtStart);

        jetzt.addAndGet(299_000);
        plugin.pruefeInaktivitaet();
        assertTrue(e.istEingestempelt());
        jetzt.addAndGet(1_000);
        plugin.pruefeInaktivitaet();
        assertFalse(e.istEingestempelt());
        assertEquals(600_000, e.gesamt);
    }

    @Test
    void verspaeteteBewegungVerhindertTimeoutNicht() {
        PlayerMock p = server.addPlayer("Anton");
        StempelDaten.Eintrag e = plugin.daten().get(p.getUniqueId());
        jetzt.addAndGet(301_000);

        bewegen(p, true);

        assertFalse(e.istEingestempelt());
        assertEquals(300_000, e.letzteSchicht);
        assertTrue(nachrichten(p).stream().anyMatch(n -> n.contains("Arbeitszeit betrug du wirst ausgestochen")));
    }

    @Test
    void reconnectNachTimeoutStempeltNichtAutomatischEin() {
        PlayerMock p = server.addPlayer("Anton");
        StempelDaten.Eintrag e = plugin.daten().get(p.getUniqueId());
        jetzt.addAndGet(300_000);
        plugin.pruefeInaktivitaet();

        p.disconnect();
        p.reconnect();

        assertFalse(e.istEingestempelt());
        assertTrue(nachrichten(p).stream().anyMatch(n -> n.contains("Bewege dich")));
    }
}
