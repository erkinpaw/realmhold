"""
Draws the strategist's icons: 20x20 pixel pictures of the buildings, soldiers, engines, ships and orders, with a dark
outline so they read on any panel. Run: python tools/icons.py [sheet.png]  (writes the sprites into the resources;
with a path, also a big sheet of them all to look at).
"""
import os
import sys
from PIL import Image, ImageDraw

OUT = os.path.join(os.path.dirname(__file__), '..', 'src', 'main', 'resources', 'assets', 'strategy1', 'textures', 'gui', 'sprites', 'icon')
N = 20

OUTLINE = (34, 24, 16, 255)
WOOD, WOOD_D, WOOD_L = (156, 104, 56), (108, 70, 36), (200, 150, 88)
STONE, STONE_D, STONE_L = (150, 150, 154), (100, 100, 108), (200, 200, 204)
ROOF, ROOF_D = (178, 62, 44), (124, 40, 28)
GOLD, GOLD_D = (246, 206, 48), (196, 146, 22)
IRON, IRON_D = (220, 222, 228), (146, 148, 160)
WHEAT, WHEAT_D = (236, 196, 76), (180, 138, 40)
GREEN, GREEN_D = (88, 158, 60), (54, 108, 40)
WATER, WATER_L = (58, 108, 200), (120, 168, 236)
FIRE1, FIRE2, FIRE3 = (255, 226, 90), (255, 150, 30), (214, 60, 20)
RED, RED_D = (204, 44, 40), (140, 24, 24)
BLUE, BLUE_D = (60, 96, 196), (36, 60, 140)
WHITE = (236, 232, 220)
DARK = (44, 32, 26)
SKIN = (232, 184, 140)
SOIL = (120, 82, 50)
ORE = (214, 168, 132)


class Pic:
    def __init__(self):
        self.im = Image.new('RGBA', (N, N), (0, 0, 0, 0))
        self.d = ImageDraw.Draw(self.im)

    def px(self, x, y, c):
        if 0 <= x < N and 0 <= y < N:
            self.im.putpixel((x, y), c + (255,) if len(c) == 3 else c)

    def rect(self, x0, y0, x1, y1, c):
        self.d.rectangle([x0, y0, x1, y1], fill=c)

    def line(self, x0, y0, x1, y1, c, w=1):
        self.d.line([x0, y0, x1, y1], fill=c, width=w)

    def poly(self, pts, c):
        self.d.polygon(pts, fill=c)

    def disc(self, cx, cy, r, c):
        self.d.ellipse([cx - r, cy - r, cx + r, cy + r], fill=c)

    def ring(self, cx, cy, r, c, w=1):
        self.d.ellipse([cx - r, cy - r, cx + r, cy + r], outline=c, width=w)

    def done(self):
        """The dark outline round everything drawn."""
        src = self.im.copy()
        for y in range(N):
            for x in range(N):
                if src.getpixel((x, y))[3] > 0:
                    continue
                for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                    nx, ny = x + dx, y + dy
                    if 0 <= nx < N and 0 <= ny < N and src.getpixel((nx, ny))[3] > 0 and src.getpixel((nx, ny))[:3] != OUTLINE[:3]:
                        self.im.putpixel((x, y), OUTLINE)
                        break
        return self.im


def bricks(p, x0, y0, x1, y1, base, dark, light, h=3, w=4):
    p.rect(x0, y0, x1, y1, base)
    row = 0
    for y in range(y0 + h - 1, y1 + 1, h):
        p.line(x0, y, x1, y, dark)
    for y in range(y0, y1 + 1, h):
        off = (row % 2) * (w // 2)
        for x in range(x0 + off, x1 + 1, w):
            p.line(x, y, x, min(y + h - 2, y1), dark)
        if y + 0 <= y1:
            p.line(x0, y, x1, y, light) if row == 0 else None
        row += 1


def planks(p, x0, y0, x1, y1, base, dark, vertical=True, step=3):
    p.rect(x0, y0, x1, y1, base)
    if vertical:
        for x in range(x0 + step, x1 + 1, step):
            p.line(x, y0, x, y1, dark)
    else:
        for y in range(y0 + step, y1 + 1, step):
            p.line(x0, y, x1, y, dark)


ICONS = {}


def icon(name):
    def reg(f):
        ICONS[name] = f
        return f
    return reg


# ------------------------------------------------------------------ buildings

@icon('keep')
def _():
    p = Pic()
    bricks(p, 4, 9, 15, 18, STONE, STONE_D, STONE_L)
    for x in (4, 7, 10, 13):
        p.rect(x, 7, x + 1, 8, STONE)
    bricks(p, 1, 5, 5, 18, STONE_L, STONE_D, WHITE)
    bricks(p, 14, 5, 18, 18, STONE_L, STONE_D, WHITE)
    for x in (1, 4, 14, 17):
        p.rect(x, 3, x + 1, 4, STONE_L)
    p.rect(8, 12, 11, 18, DARK)
    p.rect(9, 11, 10, 11, DARK)
    p.line(10, 1, 10, 6, WOOD_D)
    p.rect(11, 1, 14, 3, RED)
    return p.done()


@icon('house')
def _():
    p = Pic()
    planks(p, 4, 10, 15, 18, WOOD_L, WOOD, vertical=False, step=2)
    p.poly([(1, 11), (9.5, 2), (18, 11)], ROOF)
    p.line(2, 11, 9, 4, ROOF_D)
    p.rect(8, 13, 11, 18, WOOD_D)
    p.px(10, 15, GOLD)
    p.rect(13, 12, 14, 13, WATER_L)
    p.rect(5, 12, 6, 13, WATER_L)
    p.rect(14, 3, 15, 6, STONE_D)
    return p.done()


@icon('stockpile')
def _():
    p = Pic()

    def crate(x0, y0, x1, y1):
        p.rect(x0, y0, x1, y1, WOOD)
        p.rect(x0, y0, x1, y0, WOOD_L)
        p.line(x0, y0, x1, y1, WOOD_D)
        p.line(x0, y1, x1, y0, WOOD_D)
        p.d.rectangle([x0, y0, x1, y1], outline=WOOD_D)

    crate(1, 11, 9, 18)
    crate(10, 11, 18, 18)
    crate(5, 3, 13, 10)
    return p.done()


@icon('farm')
def _():
    p = Pic()
    p.rect(1, 14, 18, 18, SOIL)
    for x in range(2, 18, 3):
        p.line(x, 14, x, 18, (96, 64, 38))
    for i, x in enumerate((3, 6, 9, 12, 15)):
        top = 5 + (i % 2) * 2
        p.line(x, top + 3, x, 15, GREEN_D)
        p.rect(x - 1, top, x + 1, top + 3, WHEAT)
        p.px(x, top - 1, WHEAT_D)
        p.px(x - 1, top + 1, WHEAT_D)
    return p.done()


@icon('woodcutter')
def _():
    p = Pic()
    p.rect(4, 12, 14, 18, WOOD)
    for x in (6, 9, 12):
        p.line(x, 13, x, 18, WOOD_D)
    p.disc(9, 11, 5, WOOD_L)
    p.d.ellipse([4, 9, 14, 13], fill=WOOD_L)
    p.ring(9, 11, 2, WOOD)
    p.line(14, 2, 9, 10, WOOD_D, 2)
    p.poly([(12, 1), (18, 2), (18, 7), (14, 6)], IRON)
    p.line(18, 2, 18, 7, IRON_D)
    return p.done()


@icon('quarry')
def _():
    p = Pic()

    def block(x, y, c=STONE):
        p.rect(x, y, x + 5, y + 4, c)
        p.line(x, y, x + 5, y, STONE_L)
        p.line(x + 5, y + 1, x + 5, y + 4, STONE_D)
        p.line(x, y + 4, x + 5, y + 4, STONE_D)

    block(1, 14)
    block(7, 14)
    block(13, 14)
    block(4, 9)
    block(10, 9)
    block(7, 4, STONE_L)
    p.line(2, 2, 6, 7, WOOD_D, 1)
    p.poly([(0, 3), (3, 0), (5, 1), (1, 5)], IRON_D)
    return p.done()


def mine(ore, ore_d):
    p = Pic()
    p.rect(2, 5, 17, 18, STONE_D)
    p.rect(5, 8, 14, 18, DARK)
    p.rect(3, 6, 5, 18, WOOD)
    p.rect(14, 6, 16, 18, WOOD)
    p.rect(2, 4, 17, 6, WOOD_L)
    p.line(2, 6, 17, 6, WOOD_D)
    for (x, y) in ((6, 15), (9, 13), (11, 16), (7, 11)):
        p.rect(x, y, x + 2, y + 1, ore)
        p.px(x + 2, y + 1, ore_d)
    p.line(6, 18, 13, 18, IRON_D)
    return p.done()


@icon('iron_mine')
def _():
    return mine(ORE, (150, 96, 70))


@icon('gold_mine')
def _():
    return mine(GOLD, GOLD_D)


@icon('market')
def _():
    p = Pic()
    for i, x in enumerate(range(1, 19, 3)):
        p.rect(x, 3, x + 2, 7, RED if i % 2 == 0 else WHITE)
        p.px(x + 1, 8, RED if i % 2 == 0 else WHITE)
    p.rect(2, 8, 3, 18, WOOD_D)
    p.rect(16, 8, 17, 18, WOOD_D)
    p.rect(1, 12, 18, 14, WOOD)
    p.line(1, 12, 18, 12, WOOD_L)
    p.disc(6, 10, 1, GOLD)
    p.disc(9, 10, 1, GOLD)
    p.disc(13, 10, 1, (220, 50, 40))
    p.rect(4, 15, 15, 18, WOOD_D)
    return p.done()


@icon('barracks')
def _():
    p = Pic()
    planks(p, 2, 10, 17, 18, WOOD, WOOD_D)
    p.poly([(0, 11), (4, 5), (15, 5), (19, 11)], ROOF_D)
    p.line(4, 5, 15, 5, ROOF)
    p.rect(8, 13, 11, 18, DARK)
    # crossed swords over the door
    p.line(4, 2, 15, 13, IRON, 2)
    p.line(15, 2, 4, 13, IRON, 2)
    p.line(3, 1, 4, 2, WOOD_D)
    p.line(16, 1, 15, 2, WOOD_D)
    p.line(12, 13, 15, 10, GOLD_D)
    p.line(4, 10, 7, 13, GOLD_D)
    return p.done()


@icon('blacksmith')
def _():
    p = Pic()
    p.rect(3, 8, 16, 10, IRON_D)
    p.line(3, 8, 16, 8, IRON)
    p.poly([(0, 9), (3, 8), (3, 10)], IRON_D)
    p.rect(7, 11, 12, 13, (90, 92, 104))
    p.rect(4, 14, 15, 17, (90, 92, 104))
    p.line(4, 14, 15, 14, IRON_D)
    p.line(9, 1, 13, 6, WOOD_D, 2)
    p.rect(12, 1, 17, 4, IRON)
    p.line(17, 1, 17, 4, IRON_D)
    for (x, y) in ((5, 5), (7, 3), (4, 2)):
        p.px(x, y, FIRE1)
    p.px(6, 6, FIRE2)
    return p.done()


@icon('workshop')
def _():
    p = Pic()
    # a cogwheel and a hammer: where engines are made
    p.disc(8, 11, 6, IRON_D)
    for a in range(8):
        import math
        x = 8 + round(math.cos(a * math.pi / 4) * 7)
        y = 11 + round(math.sin(a * math.pi / 4) * 7)
        p.rect(x - 1, y - 1, x + 1, y + 1, IRON_D)
    p.disc(8, 11, 4, IRON)
    p.disc(8, 11, 2, DARK)
    p.line(12, 3, 18, 15, WOOD_D, 2)
    p.rect(10, 1, 16, 4, STONE)
    p.line(10, 1, 16, 1, STONE_L)
    return p.done()


@icon('port')
def _():
    p = Pic()
    p.rect(0, 16, 19, 18, WATER)
    for x in range(0, 20, 4):
        p.line(x, 16, x + 1, 16, WATER_L)
    p.ring(9, 2, 1, IRON_D)
    p.rect(9, 3, 10, 14, IRON_D)
    p.rect(5, 5, 14, 6, IRON_D)
    p.d.arc([3, 6, 16, 16], 20, 160, fill=IRON_D, width=2)
    p.poly([(2, 10), (5, 12), (2, 13)], IRON_D)
    p.poly([(17, 10), (14, 12), (17, 13)], IRON_D)
    p.line(9, 3, 9, 14, IRON)
    return p.done()


@icon('tower_wood')
def _():
    p = Pic()
    planks(p, 6, 7, 13, 18, WOOD, WOOD_D, vertical=True, step=2)
    p.rect(4, 4, 15, 6, WOOD_L)
    p.line(4, 6, 15, 6, WOOD_D)
    for x in (4, 7, 10, 13):
        p.rect(x, 2, x + 1, 3, WOOD_L)
    p.rect(9, 9, 10, 12, DARK)
    p.line(6, 18, 3, 18, WOOD_D)
    p.line(13, 18, 16, 18, WOOD_D)
    return p.done()


@icon('tower_stone')
def _():
    p = Pic()
    bricks(p, 5, 7, 14, 18, STONE, STONE_D, STONE_L)
    p.rect(3, 4, 16, 6, STONE_L)
    p.line(3, 6, 16, 6, STONE_D)
    for x in (3, 6, 9, 12, 15):
        p.rect(x, 2, x + 1, 3, STONE_L)
    p.rect(9, 9, 10, 12, DARK)
    p.line(10, 0, 10, 2, WOOD_D)
    p.rect(11, 0, 13, 1, RED)
    return p.done()


@icon('wall_wood')
def _():
    p = Pic()
    for i, x in enumerate(range(1, 18, 3)):
        top = 5 + (i % 2)
        p.rect(x, top + 2, x + 2, 18, WOOD if i % 2 == 0 else WOOD_L)
        p.poly([(x, top + 2), (x + 1, top), (x + 2, top + 2)], WOOD_L if i % 2 == 0 else WOOD)
        p.line(x + 2, top + 3, x + 2, 18, WOOD_D)
    p.rect(1, 11, 18, 12, WOOD_D)
    return p.done()


@icon('wall_stone')
def _():
    p = Pic()
    bricks(p, 0, 8, 19, 18, STONE, STONE_D, STONE_L)
    for x in (0, 5, 10, 15):
        p.rect(x, 5, x + 3, 7, STONE_L)
        p.line(x, 7, x + 3, 7, STONE_D)
    return p.done()


@icon('path')
def _():
    p = Pic()
    p.poly([(0, 19), (6, 19), (12, 10), (19, 6), (19, 2), (10, 6), (3, 13)], (196, 156, 96))
    p.line(0, 19, 3, 13, (150, 110, 64))
    p.line(10, 6, 19, 2, (150, 110, 64))
    for (x, y) in ((5, 15), (9, 11), (14, 7)):
        p.px(x, y, STONE_D)
    for (x, y) in ((2, 6), (4, 4), (15, 13), (17, 15), (12, 17)):
        p.line(x, y, x, y - 2, GREEN)
        p.px(x + 1, y - 1, GREEN_D)
    return p.done()


@icon('bridge')
def _():
    p = Pic()
    p.rect(0, 14, 19, 18, WATER)
    for x in range(1, 19, 5):
        p.line(x, 16, x + 2, 16, WATER_L)
    p.rect(1, 8, 18, 10, WOOD)
    p.line(1, 8, 18, 8, WOOD_L)
    p.d.arc([3, 9, 16, 22], 180, 360, fill=STONE_D, width=2)
    p.rect(1, 10, 3, 16, STONE)
    p.rect(16, 10, 18, 16, STONE)
    for x in (1, 6, 11, 17):
        p.line(x, 4, x, 7, WOOD_D)
    p.line(1, 4, 18, 4, WOOD_D)
    return p.done()


# ------------------------------------------------------------------ soldiers

@icon('militia')
def _():
    p = Pic()
    # a pitchfork and a leather cap: folk with what they had to hand
    p.line(3, 18, 14, 5, WOOD, 2)
    p.line(12, 6, 17, 2, IRON_D)
    for (x0, y0, x1, y1) in ((13, 4, 16, 0), (15, 6, 19, 3), (11, 4, 13, 1)):
        p.line(x0, y0, x1, y1, IRON)
    p.disc(6, 9, 4, (150, 100, 60))
    p.rect(2, 9, 10, 10, (110, 70, 40))
    p.rect(3, 10, 9, 13, SKIN)
    p.px(5, 11, DARK)
    p.px(7, 11, DARK)
    return p.done()


@icon('archer')
def _():
    p = Pic()
    p.d.arc([2, 1, 14, 19], 270, 90, fill=WOOD, width=2)
    p.line(8, 1, 8, 18, WHITE)
    p.line(2, 10, 18, 10, WOOD_L)
    p.poly([(16, 8), (19, 10), (16, 12)], IRON)
    p.poly([(1, 8), (3, 10), (1, 12)], RED)
    return p.done()


@icon('swordsman')
def _():
    p = Pic()
    p.line(3, 16, 15, 3, IRON, 2)
    p.line(4, 17, 15, 4, IRON_D)
    p.line(1, 13, 6, 18, GOLD_D, 2)
    p.line(1, 18, 3, 16, WOOD_D, 2)
    p.poly([(10, 9), (18, 9), (18, 14), (14, 19), (10, 14)], BLUE)
    p.line(14, 10, 14, 17, WHITE)
    p.line(11, 12, 17, 12, WHITE)
    return p.done()


@icon('knight')
def _():
    p = Pic()
    p.rect(5, 6, 14, 17, IRON)
    p.poly([(5, 6), (9.5, 2), (14, 6)], IRON)
    p.rect(5, 9, 14, 10, DARK)
    p.rect(9, 11, 10, 14, IRON_D)
    p.line(14, 6, 14, 17, IRON_D)
    p.poly([(10, 2), (16, 0), (18, 4), (13, 4)], RED)
    p.px(17, 5, RED_D)
    return p.done()


@icon('guard')
def _():
    p = Pic()
    p.line(14, 19, 14, 1, WOOD_D, 1)
    p.poly([(15, 2), (19, 4), (19, 8), (15, 7)], IRON)
    p.poly([(13, 1), (14, 0), (15, 1)], IRON)
    p.rect(2, 7, 11, 17, GOLD)
    p.poly([(2, 7), (6.5, 3), (11, 7)], GOLD)
    p.rect(2, 10, 11, 11, DARK)
    p.line(11, 7, 11, 17, GOLD_D)
    p.rect(6, 12, 7, 15, GOLD_D)
    return p.done()


@icon('peasant')
def _():
    p = Pic()
    p.line(14, 19, 5, 4, WOOD_D, 2)
    p.poly([(2, 3), (7, 1), (8, 3), (4, 6)], IRON_D)
    p.disc(13, 8, 3, SKIN)
    p.rect(8, 4, 18, 5, WHEAT)
    p.rect(10, 2, 16, 4, WHEAT)
    p.line(10, 4, 16, 4, WHEAT_D)
    return p.done()


# ------------------------------------------------------------------ engines and ships

@icon('catapult')
def _():
    p = Pic()
    p.rect(1, 13, 18, 14, WOOD)
    p.line(1, 13, 18, 13, WOOD_L)
    p.disc(4, 16, 2, WOOD_D)
    p.disc(15, 16, 2, WOOD_D)
    p.px(4, 16, IRON_D)
    p.px(15, 16, IRON_D)
    p.line(9, 13, 11, 7, WOOD_D, 2)
    p.line(14, 13, 11, 7, WOOD_D, 2)
    p.line(3, 11, 15, 3, WOOD_L, 2)
    p.disc(16, 3, 2, STONE)
    p.px(15, 2, STONE_L)
    return p.done()


@icon('trebuchet')
def _():
    p = Pic()
    p.rect(1, 17, 18, 18, WOOD)
    p.line(3, 17, 9, 4, WOOD_D, 2)
    p.line(15, 17, 10, 4, WOOD_D, 2)
    p.line(5, 11, 14, 11, WOOD_D)
    p.line(18, 0, 4, 7, WOOD_L, 2)
    p.rect(2, 8, 7, 12, STONE_D)
    p.line(2, 8, 7, 8, STONE)
    p.line(18, 1, 17, 5, WHITE)
    p.disc(17, 6, 1, STONE)
    return p.done()


@icon('ballista')
def _():
    p = Pic()
    p.line(10, 11, 6, 18, WOOD_D, 2)
    p.line(10, 11, 14, 18, WOOD_D, 2)
    p.d.arc([2, 1, 12, 19], 270, 90, fill=WOOD, width=2)
    p.line(7, 2, 14, 10, WHITE)
    p.line(7, 18, 14, 10, WHITE)
    p.rect(5, 9, 15, 11, WOOD_L)
    p.line(1, 10, 17, 10, IRON_D)
    p.poly([(17, 7), (19, 10), (17, 13)], IRON)
    return p.done()


@icon('engine_catapult')
def _():
    return ICONS['catapult']()


@icon('brazier')
def _():
    p = Pic()
    p.poly([(2, 10), (17, 10), (14, 14), (5, 14)], IRON_D)
    p.line(2, 10, 17, 10, IRON)
    p.line(6, 14, 4, 18, DARK, 2)
    p.line(13, 14, 15, 18, DARK, 2)
    p.poly([(4, 10), (6, 4), (8, 7), (10, 1), (12, 6), (14, 3), (15, 10)], FIRE3)
    p.poly([(6, 10), (8, 6), (10, 3), (12, 7), (13, 10)], FIRE2)
    p.poly([(8, 10), (10, 6), (11, 10)], FIRE1)
    return p.done()


def ship(sail, sail_d, hull, flag=None):
    p = Pic()
    p.rect(0, 17, 19, 18, WATER)
    p.poly([(1, 12), (18, 12), (15, 17), (4, 17)], hull)
    p.line(1, 12, 18, 12, WOOD_L)
    p.rect(9, 1, 10, 12, WOOD_D)
    p.poly([(4, 3), (15, 3), (16, 10), (3, 10)], sail)
    p.line(3, 10, 16, 10, sail_d)
    if flag:
        p.rect(11, 0, 14, 1, flag)
    return p


@icon('transport')
def _():
    p = ship(WHITE, (190, 184, 170), WOOD)
    return p.done()


@icon('warship')
def _():
    p = ship(RED, RED_D, WOOD_D, GOLD)
    p.line(9, 4, 9, 9, GOLD)
    p.line(6, 6, 13, 6, GOLD)
    p.px(4, 14, DARK)
    p.px(8, 14, DARK)
    p.px(12, 14, DARK)
    return p.done()


# ------------------------------------------------------------------ orders and tabs

@icon('hold')
def _():
    p = Pic()
    p.poly([(3, 2), (16, 2), (16, 10), (9.5, 18), (3, 10)], BLUE)
    p.line(3, 2, 16, 2, GOLD)
    p.line(9, 4, 9, 15, GOLD)
    p.line(10, 4, 10, 15, GOLD)
    p.line(5, 7, 14, 7, GOLD)
    return p.done()


@icon('unman')
def _():
    p = Pic()
    p.d.arc([0, 1, 9, 18], 270, 90, fill=WOOD, width=2)
    p.line(4, 1, 4, 18, WHITE)
    p.rect(12, 2, 15, 11, (90, 200, 90))
    p.poly([(9, 11), (18, 11), (13.5, 17)], (90, 200, 90))
    return p.done()


@icon('demolish')
def _():
    p = Pic()
    planks(p, 3, 9, 16, 18, WOOD_L, WOOD, vertical=False, step=2)
    p.poly([(1, 10), (9.5, 3), (18, 10)], ROOF)
    p.line(2, 2, 17, 17, RED, 3)
    p.line(17, 2, 2, 17, RED, 3)
    return p.done()


@icon('map')
def _():
    p = Pic()
    p.rect(2, 3, 17, 16, (232, 214, 170))
    p.rect(1, 2, 2, 17, WOOD)
    p.rect(17, 2, 18, 17, WOOD)
    p.line(4, 6, 9, 8, (176, 120, 70))
    p.line(9, 8, 14, 6, (176, 120, 70))
    p.poly([(5, 10), (8, 10), (9, 13), (6, 14)], GREEN)
    p.poly([(11, 10), (15, 11), (14, 14)], WATER_L)
    p.line(12, 4, 14, 5, RED)
    p.line(14, 4, 12, 5, RED)
    return p.done()


@icon('leave')
def _():
    p = Pic()
    planks(p, 4, 2, 13, 18, WOOD, WOOD_D)
    p.px(11, 10, GOLD)
    p.rect(13, 9, 17, 10, (90, 200, 90))
    p.poly([(16, 6), (19, 9.5), (16, 13)], (90, 200, 90))
    return p.done()


@icon('caravan')
def _():
    p = Pic()
    p.rect(2, 8, 15, 13, WOOD)
    p.line(2, 8, 15, 8, WOOD_L)
    p.disc(5, 15, 2, WOOD_D)
    p.disc(13, 15, 2, WOOD_D)
    p.line(15, 11, 19, 11, WOOD_D)
    p.disc(5, 6, 2, WHEAT)
    p.disc(9, 5, 2, (210, 190, 150))
    p.disc(13, 6, 2, GOLD)
    return p.done()


@icon('tab_economy')
def _():
    p = Pic()
    p.poly([(5, 8), (14, 8), (17, 17), (2, 17)], (210, 184, 130))
    p.rect(7, 5, 12, 8, (190, 164, 110))
    p.line(6, 8, 13, 8, WOOD_D)
    for (x, y) in ((8, 1), (10, 2), (12, 1)):
        p.line(x, y, x, 5, WHEAT_D)
        p.px(x, y, WHEAT)
    p.disc(14, 15, 2, GOLD)
    return p.done()


@icon('tab_military')
def _():
    p = Pic()
    p.line(2, 2, 15, 15, IRON, 2)
    p.line(17, 2, 4, 15, IRON, 2)
    p.line(12, 16, 16, 12, GOLD_D, 2)
    p.line(3, 12, 7, 16, GOLD_D, 2)
    p.line(16, 16, 18, 18, WOOD_D, 2)
    p.line(3, 16, 1, 18, WOOD_D, 2)
    return p.done()


@icon('tab_army')
def _():
    p = Pic()
    p.rect(4, 7, 15, 15, IRON)
    p.poly([(4, 7), (9.5, 2), (15, 7)], IRON)
    p.rect(4, 15, 15, 16, IRON_D)
    p.rect(9, 7, 10, 15, IRON_D)
    p.rect(1, 15, 18, 17, IRON_D)
    p.poly([(9, 2), (10, 0), (11, 2)], RED)
    return p.done()


def main():
    os.makedirs(OUT, exist_ok=True)
    pics = {}
    for name, f in ICONS.items():
        im = f()
        im.save(os.path.join(OUT, name + '.png'))
        pics[name] = im
    if len(sys.argv) > 1:
        cols = 8
        rows = (len(pics) + cols - 1) // cols
        S = 8
        sheet = Image.new('RGBA', (cols * (N * S + 16), rows * (N * S + 16)), (60, 46, 34, 255))
        for i, (name, im) in enumerate(pics.items()):
            x, y = (i % cols) * (N * S + 16) + 8, (i // cols) * (N * S + 16) + 8
            sheet.paste((112, 90, 64, 255), [x - 4, y - 4, x + N * S + 4, y + N * S + 4])
            big = im.resize((N * S, N * S), Image.NEAREST)
            sheet.alpha_composite(big, (x, y))
        sheet.save(sys.argv[1])
    print(len(pics), 'icons')


if __name__ == '__main__':
    main()
