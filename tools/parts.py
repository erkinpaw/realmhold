"""
Makes the block models of the engines' parts (the towers' ballista and catapult, the field catapult and trebuchet):
beams of wood, wheels, a cup, a bolt, each a box with its texture laid on at the world's own scale (one texel a
sixteenth of a block, the bark along the beam), so nothing looks stretched as a scaled whole block does. They're the
states of one unseen block, strategy1:part, shown by display entities that turn and move them.
Run: python tools/parts.py  (writes the blockstate, the models and registry/Parts.java).
"""
import json
import math
import os

ROOT = os.path.join(os.path.dirname(__file__), '..', 'src', 'main')
ASSETS = os.path.join(ROOT, 'resources', 'assets', 'strategy1')
JAVA = os.path.join(ROOT, 'java', 'org', 'webtrade', 'strategy1', 'registry', 'Parts.java')


def b(tex, axis, x0, y0, z0, x1, y1, z1):
    """A box (blocks, the machine's frame): a log laid along its axis ('x', 'y', 'z'), or any block (axis None)."""
    return tex, axis, (x0, y0, z0), (x1, y1, z1)


# name, its origin in the machine's frame (None: worked out so it fits a model), what it's made of
PARTS = [
    # the towers' ballista (from the middle of the tower's top, +z its front)
    ('ballista_base', (0, 0, 0), [
        b('spruce_log', 'y', -0.15, 0, -0.15, 0.15, 0.55, 0.15),
        b('stripped_spruce_log', 'z', -0.12, 0.55, -0.95, 0.12, 0.79, 0.95)]),
    # its bow's arms, from where they're set in the stock
    ('ballista_arm_left', (0, 0, 0), [b('spruce_log', 'x', -0.95, -0.06, -0.06, 0, 0.06, 0.06)]),
    ('ballista_arm_right', (0, 0, 0), [b('spruce_log', 'x', 0, -0.06, -0.06, 0.95, 0.06, 0.06)]),
    # the bolt and its iron head, from its back end
    ('ballista_bolt', (0, 0, 0), [
        b('stripped_dark_oak_log', 'z', -0.04, -0.04, 0, 0.04, 0.04, 1.45),
        b('iron_block', None, -0.07, -0.07, 1.43, 0.07, 0.07, 1.57)]),
    # the towers' catapult: its frame (two beams along, one across, the uprights and the crossbar, the axle)
    ('catapult_frame', None, [
        b('spruce_log', 'z', -0.62, 0, -1.55, -0.42, 0.2, 0.85),
        b('spruce_log', 'z', 0.42, 0, -1.55, 0.62, 0.2, 0.85),
        b('spruce_log', 'x', -0.42, 0.02, -1.45, 0.42, 0.18, -1.27),
        b('spruce_log', 'y', -0.62, 0.2, 0.42, -0.44, 1.32, 0.6),
        b('spruce_log', 'y', 0.44, 0.2, 0.42, 0.62, 1.32, 0.6),
        b('spruce_log', 'x', -0.62, 1.32, 0.42, 0.62, 1.5, 0.6),
        b('stripped_spruce_log', 'x', -0.42, 0.35, -0.12, 0.42, 0.49, 0.02)]),
    # its arm from the axle, and the cup at its end (open to -y)
    ('catapult_arm', (0, 0, 0), [
        b('stripped_oak_log', 'z', -0.08, -0.08, 0, 0.08, 0.08, 1.5),
        b('spruce_planks', None, -0.21, -0.14, 1.1, 0.21, -0.06, 1.52),
        b('spruce_planks', None, -0.21, -0.42, 1.1, -0.16, -0.14, 1.52),
        b('spruce_planks', None, 0.16, -0.42, 1.1, 0.21, -0.14, 1.52),
        b('spruce_planks', None, -0.16, -0.42, 1.1, 0.16, -0.14, 1.15),
        b('spruce_planks', None, -0.16, -0.42, 1.47, 0.16, -0.14, 1.52)]),
    # the field catapult (from the machine's foot): the cart, its wheels, the uprights and crossbar, a stone at hand
    ('siege_catapult', None, [
        b('spruce_planks', None, -0.9, 0.35, -1.1, 0.9, 0.6, 1.1),
        b('dark_oak_log', 'x', -1.15, 0, -0.95, -0.9, 0.7, -0.25),
        b('dark_oak_log', 'x', 0.9, 0, -0.95, 1.15, 0.7, -0.25),
        b('dark_oak_log', 'x', -1.15, 0, 0.35, -0.9, 0.7, 1.05),
        b('dark_oak_log', 'x', 0.9, 0, 0.35, 1.15, 0.7, 1.05),
        b('oak_log', 'y', -0.75, 0.6, 0.1, -0.55, 1.5, 0.3),
        b('oak_log', 'y', 0.55, 0.6, 0.1, 0.75, 1.5, 0.3),
        b('oak_log', 'x', -0.75, 1.5, 0.1, 0.75, 1.7, 0.3),
        b('cobblestone', None, -0.25, 0.6, -1.1, 0.25, 0.9, -0.6)]),
    # its arm (from its foot, tilted up as it's set)
    ('siege_catapult_arm', (0, 0, 0), [b('stripped_spruce_log', 'z', 0, 0, 0, 0.2, 0.2, 2.0)]),
    # the trebuchet: the cart and wheels; the frame with its counterweight; a brace; half its arm
    ('trebuchet_base', None, [
        b('spruce_planks', None, -1.2, 0.25, -1.5, 1.2, 0.55, 1.5),
        b('dark_oak_log', 'x', -1.45, 0, -1.3, -1.2, 0.8, -0.5),
        b('dark_oak_log', 'x', 1.2, 0, -1.3, 1.45, 0.8, -0.5),
        b('dark_oak_log', 'x', -1.45, 0, 0.6, -1.2, 0.8, 1.4),
        b('dark_oak_log', 'x', 1.2, 0, 0.6, 1.45, 0.8, 1.4)]),
    ('trebuchet_frame', None, [
        b('oak_log', 'y', -1.05, 0.55, -0.15, -0.8, 3.3, 0.15),
        b('oak_log', 'y', 0.8, 0.55, -0.15, 1.05, 3.3, 0.15),
        b('oak_log', 'x', -1.05, 3.3, -0.12, 1.05, 3.5, 0.12),
        b('cobblestone', None, -0.5, 1.9, 0.8, 0.5, 2.9, 1.8),
        b('dark_oak_planks', None, -0.42, 2.9, 0.88, 0.42, 3.0, 1.72),
        b('dark_oak_log', 'y', -0.06, 3.0, 1.24, 0.06, 3.3, 1.36)]),
    ('trebuchet_brace', (0, 0, 0), [b('oak_log', 'z', 0, 0, 0, 0.25, 0.25, 1.6)]),
    ('trebuchet_arm', (0, 0, 0), [b('stripped_oak_log', 'z', 0, 0, 0, 0.25, 0.25, 2.0)]),
]

FACES = ('down', 'up', 'north', 'south', 'west', 'east')


def cuts(a, c):
    """[a, c] cut where it crosses a whole block (in sixteenths), so each piece's texture lies within one tile."""
    out, x = [], a
    while x < c - 1e-6:
        nxt = min(c, (math.floor(x / 16 + 1e-6) + 1) * 16)
        out.append((x, nxt))
        x = nxt
    return out


def span(a, c):
    """A piece's place on its tile (0..16)."""
    base = math.floor(a / 16 + 1e-6) * 16
    return a - base, c - base


def r(v):
    return round(v, 3)


def faces(tex, axis, f, t):
    (x0, y0, z0), (x1, y1, z1) = f, t
    ux, uy, uz = span(x0, x1), span(y0, y1), span(z0, z1)
    vy = (16 - uy[1], 16 - uy[0])
    side, end = '#' + tex, '#' + tex + '_top'
    out = {}
    for face in FACES:
        rot = 0
        if face in ('up', 'down'):
            uv = (ux[0], uz[0], ux[1], uz[1])
            if axis == 'x':
                uv, rot = (uz[0], ux[0], uz[1], ux[1]), 90
        elif face in ('north', 'south'):
            uv = (ux[0], vy[0], ux[1], vy[1])
            if axis == 'x':
                uv, rot = (vy[0], ux[0], vy[1], ux[1]), 90
        else:
            uv = (uz[0], vy[0], uz[1], vy[1])
            if axis == 'z':
                uv, rot = (vy[0], uz[0], vy[1], uz[1]), 90
        ends = axis == 'y' and face in ('up', 'down') or axis == 'z' and face in ('north', 'south') or axis == 'x' and face in ('west', 'east')
        e = {'uv': [r(v) for v in uv], 'texture': end if ends else side}
        if rot:
            e['rotation'] = rot
        out[face] = e
    return out


def model(origin, boxes):
    textures, elements = {}, []
    for tex, axis, f, t in boxes:
        textures[tex] = 'minecraft:block/' + tex
        if axis:
            textures[tex + '_top'] = 'minecraft:block/' + tex + '_top'
        a = [(f[i] - origin[i]) * 16 for i in range(3)]
        c = [(t[i] - origin[i]) * 16 for i in range(3)]
        for v in a + c:
            assert -16 - 1e-6 <= v <= 32 + 1e-6, (tex, a, c)
        for xa, xc in cuts(a[0], c[0]):
            for ya, yc in cuts(a[1], c[1]):
                for za, zc in cuts(a[2], c[2]):
                    elements.append({'from': [r(xa), r(ya), r(za)], 'to': [r(xc), r(yc), r(zc)],
                                     'faces': faces(tex, axis, (xa, ya, za), (xc, yc, zc))})
    textures['particle'] = textures[boxes[0][0]]
    return {'ambientocclusion': False, 'textures': textures, 'elements': elements}


def fit(boxes):
    """An origin that puts the part within what a model may hold (a block either side of its own)."""
    lo = [min(bx[2][i] for bx in boxes) for i in range(3)]
    hi = [max(bx[3][i] for bx in boxes) for i in range(3)]
    o = []
    for i in range(3):
        assert hi[i] - lo[i] <= 3, (boxes, i)
        o.append(r(lo[i] + 1 if hi[i] - lo[i] > 2 else (lo[i] + hi[i]) / 2 - 0.5))
    return tuple(o)


def main():
    os.makedirs(os.path.join(ASSETS, 'models', 'block', 'part'), exist_ok=True)
    os.makedirs(os.path.join(ASSETS, 'blockstates'), exist_ok=True)
    variants, consts, origins = {}, [], []
    for i, (name, origin, boxes) in enumerate(PARTS):
        origin = origin if origin is not None else fit(boxes)
        with open(os.path.join(ASSETS, 'models', 'block', 'part', name + '.json'), 'w') as f:
            json.dump(model(origin, boxes), f, separators=(',', ':'))
        variants['part=%d' % i] = {'model': 'strategy1:block/part/' + name}
        consts.append('    public static final int %s = %d;' % (name.upper(), i))
        origins.append('            {%sF, %sF, %sF}' % tuple(r(v) for v in origin))
    with open(os.path.join(ASSETS, 'blockstates', 'part.json'), 'w') as f:
        json.dump({'variants': variants}, f, indent=1)
    with open(JAVA, 'w') as f:
        f.write('''package org.webtrade.strategy1.registry;

import net.minecraft.world.level.block.state.BlockState;

/** The engines' parts as the states of {@link ModContent#PART} (made by tools/parts.py: run it again to change them). */
public final class Parts {

%s

    public static final int COUNT = %d;

    /** Where each part's model stands in its machine's frame (blocks): where to put its display. */
    private static final float[][] AT = {
%s};

    private Parts() {
    }

    public static BlockState state(int part) {
        return ModContent.PART.defaultBlockState().setValue(PartBlock.PART, part);
    }

    public static org.joml.Vector3f at(int part) {
        return new org.joml.Vector3f(AT[part][0], AT[part][1], AT[part][2]);
    }
}
''' % ('\n'.join(consts), len(PARTS), ',\n'.join(origins)))


if __name__ == '__main__':
    main()
