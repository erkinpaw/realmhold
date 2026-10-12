package org.webtrade.strategy1.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import org.webtrade.strategy1.Strategy1;

import java.util.ArrayList;
import java.util.List;

/** What goes between the server's game and the player's map, HUD and orders. */
public final class Payloads {

    private Payloads() {
    }

    public record FactionInfo(int id, int nameIndex, String playerName, int color, int age, long capital, boolean player,
                              boolean alive, int[] res, int reserve, int army, int[] wars, int truceUntil, int peasants) {
        void write(FriendlyByteBuf b) {
            b.writeVarInt(id);
            b.writeVarInt(nameIndex + 1);
            b.writeUtf(playerName == null ? "" : playerName);
            b.writeInt(color);
            b.writeByte(age);
            b.writeLong(capital);
            b.writeBoolean(player);
            b.writeBoolean(alive);
            b.writeVarIntArray(res);
            b.writeVarInt(reserve);
            b.writeVarInt(army);
            b.writeVarIntArray(wars);
            b.writeVarInt(truceUntil);
            b.writeVarInt(peasants);
        }

        static FactionInfo read(FriendlyByteBuf b) {
            return new FactionInfo(b.readVarInt(), b.readVarInt() - 1, b.readUtf(), b.readInt(), b.readByte(), b.readLong(), b.readBoolean(),
                    b.readBoolean(), b.readVarIntArray(), b.readVarInt(), b.readVarInt(), b.readVarIntArray(), b.readVarInt(), b.readVarInt());
        }
    }

    public record ProvinceInfo(long key, String name, int terrain, int deposit, int owner, int eco, int fort, int garrison) {
        void write(FriendlyByteBuf b) {
            b.writeLong(key);
            b.writeUtf(name);
            b.writeByte(terrain);
            b.writeByte(deposit);
            b.writeVarInt(owner);
            b.writeByte(eco);
            b.writeByte(fort);
            b.writeVarInt(garrison);
        }

        static ProvinceInfo read(FriendlyByteBuf b) {
            return new ProvinceInfo(b.readLong(), b.readUtf(), b.readByte(), b.readByte(), b.readVarInt(), b.readByte(), b.readByte(), b.readVarInt());
        }
    }

    /** The whole map, as the player's realm sees it. */
    public record MapSync(int me, int turn, List<FactionInfo> factions, List<ProvinceInfo> provinces) implements CustomPacketPayload {
        public static final Type<MapSync> TYPE = new Type<>(Strategy1.id("map_sync"));
        public static final StreamCodec<FriendlyByteBuf, MapSync> CODEC = StreamCodec.of((b, m) -> {
            b.writeVarInt(m.me + 1);
            b.writeVarInt(m.turn);
            b.writeVarInt(m.factions.size());
            for (FactionInfo f : m.factions) f.write(b);
            b.writeVarInt(m.provinces.size());
            for (ProvinceInfo p : m.provinces) p.write(b);
        }, b -> {
            int me = b.readVarInt() - 1, turn = b.readVarInt();
            int nf = b.readVarInt();
            List<FactionInfo> fs = new ArrayList<>(nf);
            for (int i = 0; i < nf; i++) fs.add(FactionInfo.read(b));
            int np = b.readVarInt();
            List<ProvinceInfo> ps = new ArrayList<>(np);
            for (int i = 0; i < np; i++) ps.add(ProvinceInfo.read(b));
            return new MapSync(me, turn, fs, ps);
        });

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Once a second: the turn's clock, the army, and every province being taken (by whom, how far along). */
    public record Hud(int turnLeft, int army, int armyMax, int peasants, int idle, long[] keys, int[] by, int[] progress, int mode, int wave,
                      int monsters, int clock, int[] market, int[] ranking, int goal, int caravans) implements CustomPacketPayload {
        public static final Type<Hud> TYPE = new Type<>(Strategy1.id("hud"));
        public static final StreamCodec<FriendlyByteBuf, Hud> CODEC = StreamCodec.of((b, h) -> {
            b.writeVarIntArray(h.market);
            b.writeVarIntArray(h.ranking);
            b.writeVarInt(h.goal);
            b.writeVarInt(h.caravans);
            b.writeVarInt(h.mode);
            b.writeVarInt(h.wave);
            b.writeVarInt(h.monsters);
            b.writeInt(h.clock);
            b.writeVarInt(h.turnLeft);
            b.writeVarInt(h.army);
            b.writeVarInt(h.armyMax);
            b.writeVarInt(h.peasants);
            b.writeVarInt(h.idle);
            b.writeLongArray(h.keys);
            b.writeVarIntArray(h.by);
            b.writeVarIntArray(h.progress);
        }, b -> {
            int[] market = b.readVarIntArray(), ranking = b.readVarIntArray();
            int goal = b.readVarInt(), caravans = b.readVarInt();
            int mode = b.readVarInt(), wave = b.readVarInt(), monsters = b.readVarInt(), clock = b.readInt();
            return new Hud(b.readVarInt(), b.readVarInt(), b.readVarInt(), b.readVarInt(), b.readVarInt(), b.readLongArray(), b.readVarIntArray(), b.readVarIntArray(),
                    mode, wave, monsters, clock, market, ranking, goal, caravans);
        });

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Into or out of the strategist's view. */
    public record SetRts(boolean on) implements CustomPacketPayload {
        public static final Type<SetRts> TYPE = new Type<>(Strategy1.id("set_rts"));
        public static final StreamCodec<FriendlyByteBuf, SetRts> CODEC = StreamCodec.of((b, r) -> b.writeBoolean(r.on), b -> new SetRts(b.readBoolean()));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** The strategist's eye over a far point at once. */
    public record Jump(double x, double z) implements CustomPacketPayload {
        public static final Type<Jump> TYPE = new Type<>(Strategy1.id("jump"));
        public static final StreamCodec<FriendlyByteBuf, Jump> CODEC = StreamCodec.of((b, j) -> {
            b.writeDouble(j.x);
            b.writeDouble(j.z);
        }, b -> new Jump(b.readDouble(), b.readDouble()));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * An order to chosen soldiers (by entity id): go to a point, attack an entity, hold where they are.
     */
    public record UnitOrder(int[] units, int order, double x, double y, double z, int target) implements CustomPacketPayload {
        public static final int MOVE = 0, ATTACK = 1, HOLD = 2, ATTACK_STRUCTURE = 3, BOARD = 4, POST = 5;
        public static final Type<UnitOrder> TYPE = new Type<>(Strategy1.id("unit_order"));
        public static final StreamCodec<FriendlyByteBuf, UnitOrder> CODEC = StreamCodec.of((b, o) -> {
            b.writeVarIntArray(o.units);
            b.writeVarInt(o.order);
            b.writeDouble(o.x);
            b.writeDouble(o.y);
            b.writeDouble(o.z);
            b.writeVarInt(o.target + 1);
        }, b -> new UnitOrder(b.readVarIntArray(), b.readVarInt(), b.readDouble(), b.readDouble(), b.readDouble(), b.readVarInt() - 1));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** A ruler's decision from the map. */
    public record Action(int action, long province, int arg) implements CustomPacketPayload {
        public static final int ECO = 1, FORT = 2, GARRISON = 3, RECRUIT = 4, AGE = 5, WAR = 6, PEACE = 7, DONATE = 8, FOUND = 9,
                CAPITAL = 10, DISMISS = 11;
        public static final Type<Action> TYPE = new Type<>(Strategy1.id("action"));
        public static final StreamCodec<FriendlyByteBuf, Action> CODEC = StreamCodec.of((b, a) -> {
            b.writeVarInt(a.action);
            b.writeLong(a.province);
            b.writeVarInt(a.arg);
        }, b -> new Action(b.readVarInt(), b.readLong(), b.readVarInt()));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** A structure as the players see it: kind, holder, box, strength, how far built, archers on it. */
    public record StructureInfo(int id, int type, int owner, int[] box, int hp, int maxHp, int built, int archers, int posts, int engine) {
        void write(FriendlyByteBuf b) {
            b.writeVarInt(engine);
            b.writeVarInt(id);
            b.writeVarInt(type);
            b.writeVarInt(owner);
            b.writeVarIntArray(box);
            b.writeVarInt(hp);
            b.writeVarInt(maxHp);
            b.writeVarInt(built);
            b.writeVarInt(archers);
            b.writeVarInt(posts);
        }

        static StructureInfo read(FriendlyByteBuf b) {
            int engine = b.readVarInt();
            return new StructureInfo(b.readVarInt(), b.readVarInt(), b.readVarInt(), b.readVarIntArray(), b.readVarInt(), b.readVarInt(), b.readVarInt(),
                    b.readVarInt(), b.readVarInt(), engine);
        }

        public net.minecraft.world.phys.AABB aabb() {
            return new net.minecraft.world.phys.AABB(box[0], box[1], box[2], box[3], box[4], box[5]);
        }
    }

    public record StructureSync(List<StructureInfo> list) implements CustomPacketPayload {
        public static final Type<StructureSync> TYPE = new Type<>(Strategy1.id("structures"));
        public static final StreamCodec<FriendlyByteBuf, StructureSync> CODEC = StreamCodec.of((b, m) -> {
            b.writeVarInt(m.list.size());
            for (StructureInfo i : m.list) i.write(b);
        }, b -> {
            int n = b.readVarInt();
            List<StructureInfo> l = new ArrayList<>(n);
            for (int i = 0; i < n; i++) l.add(StructureInfo.read(b));
            return new StructureSync(l);
        });

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Put up a structure: of a kind, at a point (to a second one, for walls and paths), turned. */
    public record Build(int kind, int x, int y, int z, int x2, int z2, int rot) implements CustomPacketPayload {
        public static final Type<Build> TYPE = new Type<>(Strategy1.id("build"));
        public static final StreamCodec<FriendlyByteBuf, Build> CODEC = StreamCodec.of((b, o) -> {
            b.writeVarInt(o.kind);
            b.writeInt(o.x);
            b.writeInt(o.y);
            b.writeInt(o.z);
            b.writeInt(o.x2);
            b.writeInt(o.z2);
            b.writeByte(o.rot);
        }, b -> new Build(b.readVarInt(), b.readInt(), b.readInt(), b.readInt(), b.readInt(), b.readInt(), b.readByte()));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Something done with a structure: make a unit there, take an archer down, pull it down. */
    public record StructureAction(int id, int action, int arg) implements CustomPacketPayload {
        public static final int MAKE = 1, UNMAN = 2, DEMOLISH = 3, UPGRADE = 4, SELL = 5, BUY = 6, CARAVAN = 7, AIM = 8;
        /** What MAKE makes: soldiers 0–4 (barracks), siege 10–11 (workshop), ships 20–21 (port). */
        public static final int CATAPULT = 10, TREBUCHET = 11, TRANSPORT = 20, WARSHIP = 21;
        public static final Type<StructureAction> TYPE = new Type<>(Strategy1.id("structure_action"));
        public static final StreamCodec<FriendlyByteBuf, StructureAction> CODEC = StreamCodec.of((b, o) -> {
            b.writeVarInt(o.id);
            b.writeVarInt(o.action);
            b.writeVarInt(o.arg);
        }, b -> new StructureAction(b.readVarInt(), b.readVarInt(), b.readVarInt()));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Asks for the map again (on opening it). */
    public record RequestSync() implements CustomPacketPayload {
        public static final Type<RequestSync> TYPE = new Type<>(Strategy1.id("request_sync"));
        public static final StreamCodec<FriendlyByteBuf, RequestSync> CODEC = StreamCodec.of((b, r) -> {
        }, b -> new RequestSync());

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }
}
