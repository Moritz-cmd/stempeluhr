package de.shyrshi.stempeluhr;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.util.function.LongSupplier;

public class Stempeluhr extends JavaPlugin implements Listener {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final String PREFIX = "<dark_gray>[<gold>Stempeluhr</gold>]</dark_gray> ";

    private StempelDaten daten;

    /** Zeitquelle in ms. Im Plugin die Systemuhr, in Tests austauschbar. */
    private LongSupplier uhr = System::currentTimeMillis;

    void setUhr(LongSupplier uhr) {
        this.uhr = uhr;
    }

    StempelDaten daten() {
        return daten;
    }

    long jetzt() {
        return uhr.getAsLong();
    }

    // ---------------------------------------------------------------- Lifecycle

    @Override
    public void onEnable() {
        daten = new StempelDaten(this);
        daten.laden();
        getServer().getPluginManager().registerEvents(this, this);

        ArbeitszeitBefehl arbeitszeit = new ArbeitszeitBefehl(this);
        var befehl = getCommand("arbeitszeit");
        if (befehl != null) {
            befehl.setExecutor(arbeitszeit);
            befehl.setTabCompleter(arbeitszeit);
        }

        // Reload-Fall: bereits online Spieler direkt einstempeln
        for (Player p : Bukkit.getOnlinePlayers()) {
            StempelDaten.Eintrag e = daten.get(p.getUniqueId());
            if (!e.istEingestempelt()) einstempeln(p, e, uhr.getAsLong());
        }

        // Jede Minute ein "Lebenszeichen" speichern, damit bei einem Absturz
        // höchstens ~1 Minute Arbeitszeit verloren geht.
        Bukkit.getScheduler().runTaskTimer(this, this::lebenszeichen, 20L * 60, 20L * 60);
    }

    @Override
    public void onDisable() {
        // Server stoppt: alle noch eingestempelten Spieler sauber ausstempeln
        long jetzt = uhr.getAsLong();
        for (Player p : Bukkit.getOnlinePlayers()) {
            StempelDaten.Eintrag e = daten.get(p.getUniqueId());
            if (e.istEingestempelt()) ausstempeln(e, jetzt);
        }
        daten.speichern();
    }

    void lebenszeichen() {
        long jetzt = uhr.getAsLong();
        for (Player p : Bukkit.getOnlinePlayers()) {
            StempelDaten.Eintrag e = daten.get(p.getUniqueId());
            if (e.istEingestempelt()) e.lebenszeichen = jetzt;
        }
        daten.speichern();
    }

    // ---------------------------------------------------------------- Events

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player p = event.getPlayer();
        StempelDaten.Eintrag e = daten.get(p.getUniqueId());
        long jetzt = uhr.getAsLong();

        // Offene Schicht aus einem Absturz? -> beim letzten Lebenszeichen beenden
        if (e.istEingestempelt()) {
            long ende = e.lebenszeichen > e.schichtStart ? e.lebenszeichen : e.schichtStart;
            ausstempeln(e, ende);
            senden(p, "<yellow>Deine letzte Schicht wurde nach einem Serverabsturz automatisch beendet.");
        }

        if (e.letzteSchicht >= 0) {
            senden(p, "<gray>Willkommen zurück! Deine letzte Schicht dauerte <gold><dauer></gold>.",
                    Placeholder.unparsed("dauer", formatiere(e.letzteSchicht)));
        }

        einstempeln(p, e, jetzt);
        senden(p, "<green>Du wurdest eingestempelt. <gray>Beende die Schicht mit <white>/ausstempeln</white>.");
        daten.speichern();
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        StempelDaten.Eintrag e = daten.get(event.getPlayer().getUniqueId());
        if (e.istEingestempelt()) {
            ausstempeln(e, uhr.getAsLong());
            daten.speichern();
        }
    }

    // ---------------------------------------------------------------- Befehle

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command cmd,
                             @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage("Dieser Befehl ist nur für Spieler.");
            return true;
        }
        StempelDaten.Eintrag e = daten.get(p.getUniqueId());
        long jetzt = uhr.getAsLong();

        switch (cmd.getName()) {
            case "ausstempeln" -> {
                if (!e.istEingestempelt()) {
                    senden(p, "<red>Du bist gerade nicht eingestempelt. <gray>Nutze <white>/einstempeln</white>.");
                    return true;
                }
                long dauer = ausstempeln(e, jetzt);
                daten.speichern();
                senden(p, "<green>Ausgestempelt! <gray>Du hast <gold><dauer></gold> gearbeitet.",
                        Placeholder.unparsed("dauer", formatiere(dauer)));
                senden(p, "<gray>Gesamtarbeitszeit: <gold><gesamt></gold>",
                        Placeholder.unparsed("gesamt", formatiere(e.gesamt)));
            }
            case "einstempeln" -> {
                if (e.istEingestempelt()) {
                    senden(p, "<red>Du bist bereits eingestempelt (seit <gold><dauer></gold>).",
                            Placeholder.unparsed("dauer", formatiere(jetzt - e.schichtStart)));
                    return true;
                }
                einstempeln(p, e, jetzt);
                daten.speichern();
                senden(p, "<green>Du wurdest eingestempelt.");
            }
            case "schicht" -> {
                if (e.istEingestempelt()) {
                    senden(p, "<gray>Laufende Schicht: <gold><dauer></gold>",
                            Placeholder.unparsed("dauer", formatiere(jetzt - e.schichtStart)));
                } else {
                    senden(p, "<gray>Du bist aktuell <red>ausgestempelt</red>.");
                }
                if (e.letzteSchicht >= 0) {
                    senden(p, "<gray>Letzte Schicht: <gold><dauer></gold>",
                            Placeholder.unparsed("dauer", formatiere(e.letzteSchicht)));
                }
                senden(p, "<gray>Gesamtarbeitszeit: <gold><gesamt></gold>",
                        Placeholder.unparsed("gesamt", formatiere(e.gesamt)));
            }
            default -> {
                return false;
            }
        }
        return true;
    }

    // ---------------------------------------------------------------- Logik

    private void einstempeln(Player p, StempelDaten.Eintrag e, long zeitpunkt) {
        e.schichtStart = zeitpunkt;
        e.lebenszeichen = zeitpunkt;
    }

    /** Beendet die laufende Schicht und gibt ihre Dauer in ms zurück. */
    private long ausstempeln(StempelDaten.Eintrag e, long ende) {
        long dauer = Math.max(0, ende - e.schichtStart);
        e.letzteSchicht = dauer;
        e.gesamt += dauer;
        e.schichtStart = -1;
        e.lebenszeichen = -1;
        return dauer;
    }

    // ---------------------------------------------------------------- Hilfen

    private void senden(Player p, String nachricht,
                        net.kyori.adventure.text.minimessage.tag.resolver.TagResolver... resolver) {
        Component c = MM.deserialize(PREFIX + nachricht, resolver);
        p.sendMessage(c);
    }

    /** Formatiert Millisekunden als z. B. "2 Std 5 Min 12 Sek" */
    static String formatiere(long ms) {
        long sek = ms / 1000;
        long tage = sek / 86_400;
        long std = (sek % 86_400) / 3600;
        long min = (sek % 3600) / 60;
        long s = sek % 60;

        StringBuilder sb = new StringBuilder();
        if (tage > 0) sb.append(tage).append(tage == 1 ? " Tag " : " Tage ");
        if (tage > 0 || std > 0) sb.append(std).append(" Std ");
        if (tage > 0 || std > 0 || min > 0) sb.append(min).append(" Min ");
        sb.append(s).append(" Sek");
        return sb.toString();
    }
}
