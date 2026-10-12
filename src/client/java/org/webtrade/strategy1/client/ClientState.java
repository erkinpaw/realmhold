package org.webtrade.strategy1.client;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import org.webtrade.strategy1.game.Faction;
import org.webtrade.strategy1.hex.Hex;
import org.webtrade.strategy1.network.Payloads;

import java.util.HashMap;
import java.util.Map;

/** The strategic map as last heard from the server, and the HUD's latest. */
public final class ClientState {

    public static Payloads.MapSync map;
    public static final Map<Long, Payloads.ProvinceInfo> PROVINCES = new HashMap<>();
    public static final Map<Integer, Payloads.FactionInfo> FACTIONS = new HashMap<>();
    public static Payloads.Hud hud;
    public static final Map<Integer, Payloads.StructureInfo> STRUCTURES = new java.util.LinkedHashMap<>();
    /** Goes up with every new map (the map screen redraws its picture then). */
    public static int revision;

    private ClientState() {
    }

    public static void accept(Payloads.MapSync m) {
        map = m;
        PROVINCES.clear();
        for (Payloads.ProvinceInfo p : m.provinces()) PROVINCES.put(p.key(), p);
        FACTIONS.clear();
        for (Payloads.FactionInfo f : m.factions()) FACTIONS.put(f.id(), f);
        revision++;
    }

    public static void structures(Payloads.StructureSync s) {
        STRUCTURES.clear();
        for (Payloads.StructureInfo i : s.list()) STRUCTURES.put(i.id(), i);
    }

    public static void clear() {
        STRUCTURES.clear();
        map = null;
        hud = null;
        PROVINCES.clear();
        FACTIONS.clear();
        revision++;
    }

    public static int me() {
        return map == null ? -1 : map.me();
    }

    public static Payloads.FactionInfo mine() {
        return FACTIONS.get(me());
    }

    public static Payloads.ProvinceInfo province(Hex h) {
        return PROVINCES.get(h.key());
    }

    /** The realm's colour (RGB), or -1 for free land. */
    public static int color(int faction) {
        Payloads.FactionInfo f = FACTIONS.get(faction);
        return f == null || !f.alive() ? -1 : f.color();
    }

    public static MutableComponent name(int faction) {
        Payloads.FactionInfo f = FACTIONS.get(faction);
        if (f == null) return Component.translatable("strategy1.owner.free").withColor(0xB0B0B0);
        return Faction.name(f.nameIndex(), f.playerName(), f.color());
    }

    public static boolean atWar(int a, int b) {
        if (a == b || a < 0 || b < 0) return false;
        if (a == Faction.NEUTRAL || b == Faction.NEUTRAL) return true;
        Payloads.FactionInfo f = FACTIONS.get(a);
        if (f == null) return false;
        for (int w : f.wars()) if (w == b) return true;
        return false;
    }

    public static boolean isCapital(Payloads.ProvinceInfo p) {
        Payloads.FactionInfo f = FACTIONS.get(p.owner());
        return f != null && f.alive() && f.capital() == p.key();
    }
}
