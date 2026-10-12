package org.webtrade.strategy1.game;

import com.mojang.serialization.Codec;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import org.webtrade.strategy1.Strategy1;
import org.webtrade.strategy1.hex.Hex;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** The whole strategic game, saved with the world: the map of provinces, the realms, whose realm each player rules. */
public final class StrategyData extends SavedData {

    public static final Codec<StrategyData> CODEC = CompoundTag.CODEC.xmap(StrategyData::load, StrategyData::save);
    public static final SavedDataType<StrategyData> TYPE = new SavedDataType<>(Strategy1.id("strategy"), StrategyData::new, CODEC, null);

    public final Map<Long, Province> provinces = new LinkedHashMap<>();
    public final Map<Integer, Faction> factions = new LinkedHashMap<>();
    public final Map<UUID, Integer> players = new HashMap<>();
    public final Map<Integer, org.webtrade.strategy1.build.Structure> structures = new LinkedHashMap<>();
    public int nextStructure = 1;
    /** The AI capitals' forts have been planned. */
    public boolean forts;
    /** The land's deposits have been planned. */
    public boolean deposits;
    public int turn;
    public int nextFaction = 1;
    public boolean generated;
    /** The hex the map was laid out round (the world spawn). */
    public long origin;

    public static StrategyData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(TYPE);
    }

    public Province province(Hex h) {
        return provinces.get(h.key());
    }

    public Province province(long key) {
        return provinces.get(key);
    }

    public Faction faction(int id) {
        return factions.get(id);
    }

    public Faction factionOf(UUID player) {
        Integer id = players.get(player);
        return id == null ? null : factions.get(id);
    }

    public int factionIdOf(UUID player) {
        return players.getOrDefault(player, -1);
    }

    /** Are two realms enemies (free land is everyone's enemy)? */
    public boolean hostile(int a, int b) {
        if (a == b || a < 0 || b < 0) return false;
        if (a == Faction.NEUTRAL || b == Faction.NEUTRAL) return true;
        Faction fa = factions.get(a);
        return fa != null && fa.wars.contains(b);
    }

    public List<Province> owned(int faction) {
        List<Province> out = new ArrayList<>();
        for (Province p : provinces.values()) if (p.owner == faction) out.add(p);
        return out;
    }

    /** Does the realm hold a province next to this hex? */
    public boolean borders(int faction, Hex h) {
        for (Hex n : h.neighbors()) {
            Province p = provinces.get(n.key());
            if (p != null && p.owner == faction) return true;
        }
        return false;
    }

    public boolean isCapital(Province p) {
        Faction f = factions.get(p.owner);
        return f != null && f.capital == p.key();
    }

    /** A rough measure of a realm's might, for the AI's choices. */
    public double strength(Faction f) {
        int prov = 0, garrison = 0;
        for (Province p : provinces.values()) {
            if (p.owner != f.id) continue;
            prov++;
            garrison += p.garrison;
        }
        return prov * 1.5 + garrison + f.reserve + f.army * 1.5 + f.age * 3;
    }

    public void touch() {
        setDirty();
        StrategyManager.mapChanged();
    }

    private CompoundTag save() {
        CompoundTag t = new CompoundTag();
        t.putInt("turn", turn);
        t.putInt("nextFaction", nextFaction);
        t.putBoolean("generated", generated);
        t.putLong("origin", origin);
        ListTag ps = new ListTag();
        for (Province p : provinces.values()) ps.add(p.save());
        t.put("provinces", ps);
        ListTag fs = new ListTag();
        for (Faction f : factions.values()) fs.add(f.save());
        t.put("factions", fs);
        ListTag pl = new ListTag();
        players.forEach((u, id) -> {
            CompoundTag e = new CompoundTag();
            e.putString("uuid", u.toString());
            e.putInt("faction", id);
            pl.add(e);
        });
        t.put("players", pl);
        t.putInt("nextStructure", nextStructure);
        t.putBoolean("forts", forts);
        t.putBoolean("deposits", deposits);
        ListTag ss = new ListTag();
        for (org.webtrade.strategy1.build.Structure s : structures.values()) ss.add(s.save());
        t.put("structures", ss);
        return t;
    }

    private static StrategyData load(CompoundTag t) {
        StrategyData d = new StrategyData();
        d.turn = t.getIntOr("turn", 0);
        d.nextFaction = t.getIntOr("nextFaction", 1);
        d.generated = t.getBooleanOr("generated", false);
        d.origin = t.getLongOr("origin", 0);
        ListTag ps = t.getListOrEmpty("provinces");
        for (int i = 0; i < ps.size(); i++) {
            Province p = Province.load(ps.getCompoundOrEmpty(i));
            d.provinces.put(p.key(), p);
        }
        ListTag fs = t.getListOrEmpty("factions");
        for (int i = 0; i < fs.size(); i++) {
            Faction f = Faction.load(fs.getCompoundOrEmpty(i));
            d.factions.put(f.id, f);
        }
        d.nextStructure = t.getIntOr("nextStructure", 1);
        d.forts = t.getBooleanOr("forts", false);
        d.deposits = t.getBooleanOr("deposits", false);
        ListTag ss = t.getListOrEmpty("structures");
        for (int i = 0; i < ss.size(); i++) {
            org.webtrade.strategy1.build.Structure s = org.webtrade.strategy1.build.Structure.load(ss.getCompoundOrEmpty(i));
            d.structures.put(s.id, s);
        }
        ListTag pl = t.getListOrEmpty("players");
        for (int i = 0; i < pl.size(); i++) {
            CompoundTag e = pl.getCompoundOrEmpty(i);
            try {
                d.players.put(UUID.fromString(e.getStringOr("uuid", "")), e.getIntOr("faction", -1));
            } catch (IllegalArgumentException ignored) {
            }
        }
        return d;
    }
}
