package org.webtrade.strategy1.game;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** A realm: an AI kingdom or a player's own. Its treasury, its age, its wars. */
public final class Faction {

    /** The owner of free land (and its bandits): at war with everyone, holds nothing. */
    public static final int NEUTRAL = 0;
    /** The highest age (I tribe, II feud, III kingdom, IV empire). */
    public static final int MAX_AGE = 4;

    public final int id;
    /** The AI kingdom's name (index into the names) or -1 for a player's realm. */
    public final int nameIndex;
    /** The player's name, for a player's realm. */
    public final String playerName;
    /** The ruling player (null for an AI kingdom). */
    public final UUID ruler;
    public final int color;

    public final int[] res = new int[Res.COUNT];
    public int age = 1;
    /** Troops in reserve (AI: its whole field army, counted; player: unused). */
    public int reserve;
    /** The player's soldiers walking the world (a player's realm). */
    public int army;
    /** Its siege engines and ships in the world. */
    public int siege, ships;
    /** Its peasants in the world. */
    public int peasants;
    /** Has the realm had its stock of everything for trying the game out. */
    public boolean boosted;
    /** Its wealth when the trading race began (-1: not measured yet). */
    public int startWealth = -1;
    public long capital;
    public boolean alive = true;
    /** At war with these realms. */
    public final Set<Integer> wars = new HashSet<>();
    /** The turn a war with a realm began, or a truce with it ends (after a peace). */
    public final Map<Integer, Integer> warSince = new HashMap<>(), truceUntil = new HashMap<>();
    /** The turn this realm last sent an army against a player's land. */
    public int lastRaid = -100;
    /** The turn the realm's peace offer was last turned down by this AI (per asking realm). */
    public final Map<Integer, Integer> refusedAt = new HashMap<>();

    public Faction(int id, int nameIndex, String playerName, UUID ruler, int color) {
        this.id = id;
        this.nameIndex = nameIndex;
        this.playerName = playerName;
        this.ruler = ruler;
        this.color = color;
    }

    public boolean player() {
        return ruler != null;
    }

    public MutableComponent name() {
        return name(nameIndex, playerName, color);
    }

    public static MutableComponent name(int nameIndex, String playerName, int color) {
        MutableComponent c = nameIndex >= 0 ? Component.translatable("strategy1.faction.ai." + nameIndex)
                : Component.translatable("strategy1.faction.player", playerName);
        return c.withColor(color);
    }

    public boolean atWar(int other) {
        return other == NEUTRAL || wars.contains(other);
    }

    public boolean canPay(int[] cost) {
        for (int i = 0; i < Res.COUNT; i++) if (res[i] < cost[i]) return false;
        return true;
    }

    public void pay(int[] cost) {
        for (int i = 0; i < Res.COUNT; i++) res[i] -= cost[i];
    }

    public void gain(int[] amount) {
        for (int i = 0; i < Res.COUNT; i++) res[i] += amount[i];
    }

    CompoundTag save() {
        CompoundTag t = new CompoundTag();
        t.putInt("id", id);
        t.putInt("nameIndex", nameIndex);
        t.putString("playerName", playerName == null ? "" : playerName);
        if (ruler != null) t.putString("ruler", ruler.toString());
        t.putInt("color", color);
        t.putIntArray("res", res);
        t.putInt("age", age);
        t.putInt("reserve", reserve);
        t.putInt("army", army);
        t.putInt("siege", siege);
        t.putInt("peasants", peasants);
        t.putInt("ships", ships);
        t.putBoolean("boosted", boosted);
        t.putInt("startWealth", startWealth);
        t.putLong("capital", capital);
        t.putBoolean("alive", alive);
        t.putInt("lastRaid", lastRaid);
        t.putIntArray("wars", wars.stream().mapToInt(Integer::intValue).toArray());
        t.put("warSince", map(warSince));
        t.put("truce", map(truceUntil));
        t.put("refused", map(refusedAt));
        return t;
    }

    private static ListTag map(Map<Integer, Integer> m) {
        ListTag l = new ListTag();
        m.forEach((k, v) -> l.add(new IntArrayTag(new int[]{k, v})));
        return l;
    }

    private static void readMap(CompoundTag t, String key, Map<Integer, Integer> into) {
        ListTag l = t.getListOrEmpty(key);
        for (int i = 0; i < l.size(); i++) {
            if (l.get(i) instanceof IntArrayTag a && a.getAsIntArray().length == 2) into.put(a.getAsIntArray()[0], a.getAsIntArray()[1]);
        }
    }

    static Faction load(CompoundTag t) {
        String ruler = t.getStringOr("ruler", "");
        Faction f = new Faction(t.getIntOr("id", 1), t.getIntOr("nameIndex", -1), t.getStringOr("playerName", ""),
                ruler.isEmpty() ? null : UUID.fromString(ruler), t.getIntOr("color", 0xFFFFFF));
        int[] r = t.getIntArray("res").orElse(new int[0]);
        System.arraycopy(r, 0, f.res, 0, Math.min(r.length, Res.COUNT));
        f.age = t.getIntOr("age", 1);
        f.reserve = t.getIntOr("reserve", 0);
        f.army = t.getIntOr("army", 0);
        f.siege = t.getIntOr("siege", 0);
        f.peasants = t.getIntOr("peasants", 0);
        f.ships = t.getIntOr("ships", 0);
        f.boosted = t.getBooleanOr("boosted", false);
        f.startWealth = t.getIntOr("startWealth", -1);
        f.capital = t.getLongOr("capital", 0);
        f.alive = t.getBooleanOr("alive", true);
        f.lastRaid = t.getIntOr("lastRaid", -100);
        for (int w : t.getIntArray("wars").orElse(new int[0])) f.wars.add(w);
        readMap(t, "warSince", f.warSince);
        readMap(t, "truce", f.truceUntil);
        readMap(t, "refused", f.refusedAt);
        return f;
    }
}
