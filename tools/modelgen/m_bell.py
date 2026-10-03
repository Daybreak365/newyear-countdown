from core import Box, Model, Mat
from lib import *
from shapes import prism, lathe

STONE = '#a3a6ad'
RED = '#b8281f'
TEAL = '#2c6b66'
ROOF = '#46556b'
DARK = '#4b3426'
GOLD = '#ecb92f'


def build(name='bosingak_bell'):
    Y0 = 0.9
    B = []

    def bx(x1, y1, z1, x2, y2, z2, mat, **kw):
        B.append(Box((x1, y1 + Y0, z1), (x2, y2 + Y0, z2), mat, **kw))

    stone = Mat(STONE, base=b_bricks(STONE, '#6c7078', bw=2.6, bh=1.3), bevel=0.08)
    stone_top = Mat(STONE, base=b_bricks('#b3b6bd', '#7a7e86', bw=2.0, bh=1.0), bevel=0.06)
    gm = gold(GOLD)
    PT = 9.2   # 기둥 윗끝
    # 기단 + 앞 디딤돌
    bx(0.2, 0, 3.4, 15.8, 1.0, 12.6, {'up': stone_top, '*': stone})
    bx(5.2, 0, 12.6, 10.8, 0.5, 13.9, {'up': stone_top, '*': stone})
    bx(5.6, 0.5, 12.6, 10.4, 0.95, 13.2, {'up': stone_top, '*': stone})

    teal = wood(TEAL, 0.72, 'u', planks=1.6)
    teal_end = Mat(TEAL, base=b_wood(TEAL, 0.7, 'v'))
    for px in (1.8, 14.2):
        pm = lacquer(RED, shape=('cylY', px, 8), bevel=0.05)
        bx(px - 0.95, 1.0, 7.05, px + 0.95, 1.5, 8.95, stone)
        B.extend(prism(px, 8, 1.5 + Y0, PT + Y0, 0.62, pm, sides=8, top=False))
        bx(px - 0.72, 3.0, 7.28, px + 0.72, 3.28, 8.72, gm)
        bx(px - 0.72, 7.3, 7.28, px + 0.72, 7.58, 8.72, gm)
        bx(px - 1.5, PT - 0.5, 6.6, px + 1.5, PT + 0.05, 9.4, teal)
        bx(px - 1.1, PT - 0.85, 7.0, px + 1.1, PT - 0.5, 9.0, teal)
        bx(px - 0.5, PT - 0.15, 5.4, px + 0.5, PT + 0.45, 10.6, teal_end)
    bx(1.2, PT + 0.05, 7.25, 14.8, PT + 0.85, 8.75, teal)
    bx(1.2, PT, 7.2, 14.8, PT + 0.12, 8.8, gm)

    trim = wood(DARK, 0.7, 'u', planks=1.2)
    tile_top = Mat(ROOF, base=b_tiles('#5a6a82', '#37445a', rh=1.25, tw=1.5), bevel=0.05)
    tile_side = Mat(ROOF, base=b_wood('#2e3849', 0.8, 'u'), bevel=0.05)
    y = PT + 0.85
    bx(0.3, y, 4.0, 15.7, y + 0.55, 12.0, trim)
    y += 0.55
    for hw, hd in [(7.5, 3.5), (6.5, 3.0), (5.3, 2.4), (4.1, 1.8), (2.9, 1.2)]:
        bx(8 - hw, y, 8 - hd, 8 + hw, y + 0.7, 8 + hd, {'up': tile_top, '*': tile_side})
        y += 0.7
    bx(8 - 3.0, y, 8 - 0.55, 8 + 3.0, y + 0.5, 8 + 0.55, trim)
    bx(8 - 3.25, y + 0.05, 8 - 0.7, 8 - 2.75, y + 0.75, 8 + 0.7, gm)
    bx(8 + 2.75, y + 0.05, 8 - 0.7, 8 + 3.25, y + 0.75, 8 + 0.7, gm)
    for sx in (0.15, 15.35):
        bx(sx, PT + 1.4, 3.9, sx + 0.5, PT + 2.05, 12.1, gm)

    # 대종 (x=7 에 매달림)
    bz = 3.0 + Y0
    bx(7 - 0.14, 8.0, 7.86, 7 + 0.14, PT + 0.05, 8.14, Mat('#26262c', bevel=0))
    prof = [(0.0, 0.5, 1.95), (0.5, 0.9, 1.75), (0.9, 2.0, 1.55), (2.0, 3.0, 1.5), (3.0, 3.4, 1.65),
            (3.4, 4.3, 1.4), (4.3, 4.9, 1.1), (4.9, 5.3, 0.75)]
    layers = [(bz + y1, bz + y2, a) for y1, y2, a in prof]

    def mats(i, y1, y2, a):
        if i in (0, 4):
            return bronze('#8f6128', shape=('cylY', 7, 8), bevel=0.03)
        return bronze(shape=('cylY', 7, 8), bevel=0.03)
    B.extend(lathe(7, 8, layers, mats, sides=16))
    B.append(Box((7 - 0.45, bz + 5.3, 8 - 0.45), (7 + 0.45, bz + 5.75, 8 + 0.45), gm))
    B.append(Box((7 - 0.7, bz + 5.75, 8 - 0.25), (7 + 0.7, bz + 6.15, 8 + 0.25), gm))

    # 당목과 줄
    rope = Mat('#c9a56a', base=b_cloth('#c9a56a', 0.35), bevel=0.0)
    logm = wood('#5a3b26', 0.7, 'u')
    logend = Mat('#7a5436', base=b_vgrad('#8b6240', '#6e4a2e'))
    for rx in (10.2, 12.7):
        bx(rx - 0.1, 5.2, 7.9, rx + 0.1, PT + 0.05, 8.1, rope)
    B.append(Box((9.4, 4.2 + Y0, 7.45), (13.8, 5.2 + Y0, 8.55), {'east': logend, 'west': logend, '*': logm}))
    for lx in (9.65, 13.3):
        bx(lx, 4.15, 7.4, lx + 0.35, 5.25, 8.6, Mat('#2b2b30', spec=0.4, bevel=0.05))
    return Model(name, B, density=7, gui=(24, 28, 0), gui_scale=0.6, gui_trans=(0, 0, 0), ground_scale=0.28)
