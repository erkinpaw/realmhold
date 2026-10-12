package org.webtrade.strategy1.hex;

/**
 * A cell of the strategic map: a pointy-top hexagon in axial coordinates (q, r). The world's x runs with q, its z
 * down the rows of r. {@link #SIZE} is the distance from a hex's centre to any of its corners, in blocks.
 */
public record Hex(int q, int r) {

    /** Centre to corner, blocks. A hex is about 83 blocks across its flats and 96 corner to corner. */
    public static final int SIZE = 48;
    private static final double SQRT3 = Math.sqrt(3);

    /** The neighbour across edge i (edge i runs from corner i to corner i + 1). */
    private static final int[][] EDGE = {{1, 0}, {0, 1}, {-1, 1}, {-1, 0}, {0, -1}, {1, -1}};

    public static Hex of(long key) {
        return new Hex((int) (key >> 32), (int) key);
    }

    public long key() {
        return key(q, r);
    }

    public static long key(int q, int r) {
        return ((long) q << 32) ^ (r & 0xFFFFFFFFL);
    }

    public double centerX() {
        return SIZE * SQRT3 * (q + r / 2.0);
    }

    public double centerZ() {
        return SIZE * 1.5 * r;
    }

    /** The hex a point of the world lies in. */
    public static Hex at(double x, double z) {
        double fq = (SQRT3 / 3 * x - z / 3.0) / SIZE;
        double fr = (2.0 / 3 * z) / SIZE;
        double fs = -fq - fr;
        long rq = Math.round(fq), rr = Math.round(fr), rs = Math.round(fs);
        double dq = Math.abs(rq - fq), dr = Math.abs(rr - fr), ds = Math.abs(rs - fs);
        if (dq > dr && dq > ds) rq = -rr - rs;
        else if (dr > ds) rr = -rq - rs;
        return new Hex((int) rq, (int) rr);
    }

    public Hex neighbor(int edge) {
        int[] d = EDGE[Math.floorMod(edge, 6)];
        return new Hex(q + d[0], r + d[1]);
    }

    public Hex[] neighbors() {
        Hex[] out = new Hex[6];
        for (int i = 0; i < 6; i++) out[i] = neighbor(i);
        return out;
    }

    public int distance(Hex o) {
        int dq = q - o.q, dr = r - o.r;
        return (Math.abs(dq) + Math.abs(dr) + Math.abs(dq + dr)) / 2;
    }

    /** Corner i (0..5) of the hex, {x, z}: corner i is at 60·i − 30 degrees round the centre. */
    public double[] corner(int i) {
        double a = Math.toRadians(60 * Math.floorMod(i, 6) - 30);
        return new double[]{centerX() + SIZE * Math.cos(a), centerZ() + SIZE * Math.sin(a)};
    }

    /** Distance from the centre, horizontally. */
    public double distToCenter(double x, double z) {
        double dx = x - centerX(), dz = z - centerZ();
        return Math.sqrt(dx * dx + dz * dz);
    }
}
