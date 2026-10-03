from core import Box, Model, Mat
from lib import *
from shapes import prism, lathe

STONE = '#a3a6ad'
RED = '#c0281e'
DARK = '#4b3426'
GOLD = '#ecb92f'
ROOF = '#46556b'


def build(name='omikuji_box'):
    Y0 = 0.4
    B = []

    def bx(x1, y1, z1, x2, y2, z2, mat, **kw):
        B.append(Box((x1, y1 + Y0, z1), (x2, y2 + Y0, z2), mat, **kw))

    gm = gold(GOLD)
    stone = Mat(STONE, base=b_bricks(STONE, '#6c7078', bw=2.6, bh=1.3), bevel=0.08)
    stone_top = Mat(STONE, base=b_bricks('#b3b6bd', '#7a7e86', bw=2.0, bh=1.0), bevel=0.06)
    deck = wood('#9a6b43', 0.72, 'u', planks=1.0)
    dark = wood(DARK, 0.7, 'u', planks=1.0)
    bx(0.8, 0, 0.8, 15.2, 1.6, 15.2, {'up': stone_top, '*': stone})
    bx(6.0, 0, 15.2, 10.0, 0.6, 16.0, {'up': stone_top, '*': stone})
    bx(1.5, 1.6, 1.5, 14.5, 2.2, 14.5, {'up': deck, '*': deck})

    # 기둥 4 + 보
    for px in (3.0, 13.0):
        for pz in (3.0, 13.0):
            bx(px - 0.85, 2.2, pz - 0.85, px + 0.85, 2.7, pz + 0.85, stone)
            B.extend(prism(px, pz, 2.7 + Y0, 10.4 + Y0, 0.58, lacquer(RED, shape=('cylY', px, pz), bevel=0.05), sides=8, top=False))
            bx(px - 0.7, 9.0, pz - 0.7, px + 0.7, 9.3, pz + 0.7, gm)
            bx(px - 0.7, 3.0, pz - 0.7, px + 0.7, 3.3, pz + 0.7, gm)
    for z in (3.0, 13.0):
        bx(2.2, 10.2, z - 0.55, 13.8, 11.1, z + 0.55, dark)
        bx(2.2, 10.1, z - 0.62, 13.8, 10.3, z + 0.62, gm)
    for x in (3.0, 13.0):
        bx(x - 0.55, 10.2, 2.2, x + 0.55, 11.1, 13.8, dark)
    bx(2.8, 7.4, 2.75, 13.2, 7.9, 3.25, dark)           # 뒤 인방
    bx(2.75, 7.4, 2.8, 3.25, 7.9, 13.2, dark)
    bx(12.75, 7.4, 2.8, 13.25, 7.9, 13.2, dark)

    # 지붕
    trim = wood(DARK, 0.7, 'u', planks=1.0)
    tile_top = Mat(ROOF, base=b_tiles('#5a6a82', '#37445a', rh=1.25, tw=1.5), bevel=0.05)
    tile_side = Mat(ROOF, base=b_wood('#2e3849', 0.8, 'u'), bevel=0.05)
    bx(0.3, 11.1, 0.3, 15.7, 11.7, 15.7, trim)
    y = 11.7
    for hw in (7.0, 5.4, 3.6):
        bx(8 - hw, y, 8 - hw, 8 + hw, y + 0.8, 8 + hw, {'up': tile_top, '*': tile_side})
        y += 0.8
    bx(7.5, y, 7.5, 8.5, y + 0.4, 8.5, gm)
    B.extend(prism(8, 8, y + 0.4 + Y0, y + 1.2 + Y0, 0.42, gold(GOLD, shape=('cylY', 8, 8)), sides=8))
    for sx in (0.0, 15.3):
        for sz in (0.0, 15.3):
            bx(sx + 0.05, 11.7, sz + 0.05, sx + 0.65, 12.3, sz + 0.65, gm)

    # 제단
    for lx in (5.3, 10.7):
        for lz in (5.3, 10.7):
            bx(lx - 0.45, 2.2, lz - 0.45, lx + 0.45, 3.7, lz + 0.45, dark)
    bx(4.3, 3.6, 4.3, 11.7, 4.2, 11.7, {'up': Mat('#8c1b17', base=b_cloth('#8c1b17'), bevel=0.04), '*': dark})
    bx(4.2, 4.15, 4.2, 11.8, 4.35, 11.8, {'up': Mat('#8c1b17', base=b_cloth('#8c1b17'), bevel=0.04), '*': gm})

    # 팔각 뽑기통 + 막대
    gmc = gold(GOLD, shape=('cylY', 8, 8))
    B.extend(prism(8, 8, 4.35 + Y0, 9.6 + Y0, 2.5, lacquer(RED, shape=('cylY', 8, 8), bevel=0.04), sides=8))
    B.extend(prism(8, 8, 4.35 + Y0, 4.95 + Y0, 2.58, gmc, sides=8, top=False))
    B.extend(prism(8, 8, 9.0 + Y0, 9.6 + Y0, 2.58, gmc, sides=8))
    B.extend(prism(8, 8, 6.4 + Y0, 6.7 + Y0, 2.54, gmc, sides=8, top=False))
    bx(7.45, 9.6, 7.45, 8.55, 9.75, 8.55, Mat('#151515', bevel=0))
    stick = wood('#e4c789', 0.85, 'v')
    bx(7.7, 9.7, 7.7, 8.3, 10.9, 8.3, stick)
    bx(7.68, 10.3, 7.68, 8.32, 10.8, 8.32, Mat('#b8281f', bevel=0.02))
    bx(7.66, 10.1, 7.66, 8.34, 10.22, 8.34, gm)

    # 방울(스즈)과 줄: 앞쪽 보에서
    bx(7.82, 8.6, 12.6, 8.18, 10.2, 13.0, Mat('#d9d0b8', base=b_cloth('#d9d0b8', 0.3), bevel=0))
    bx(7.5, 8.9, 12.55, 8.5, 9.5, 13.05, Mat('#b8281f', base=b_cloth('#b8281f', 0.3), bevel=0))
    B.extend(lathe(8, 12.8, [(7.5 + Y0, 7.9 + Y0, 0.45), (7.9 + Y0, 8.5 + Y0, 0.62), (8.5 + Y0, 8.9 + Y0, 0.4)],
                   lambda i, a, b, c: gold(GOLD, shape=('sphere', (8, 8.2 + Y0, 12.8), (1, 1, 1))), sides=8))

    # 새전함
    bx(10.6, 2.2, 9.6, 13.8, 4.4, 12.6, dark)
    bx(10.4, 4.2, 9.4, 14.0, 4.6, 12.8, gm)
    for i in range(5):
        bx(10.55 + i * 0.7, 4.6, 9.6, 10.75 + i * 0.7, 4.85, 12.6, dark)
    bx(11.0, 4.62, 9.6, 13.4, 4.64, 10.3, Mat('#0f0f10', bevel=0))
    # 앞 등롱
    return Model(name, B, density=6, gui=(17, 30, 0), ground_scale=0.28)
