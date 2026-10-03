import numpy as np
from core import Box, Model, Mat, rgb
from lib import *
from shapes import prism, lathe, sphere_layers

CAPS = ['#e8453c', '#f5a623', '#f8d347', '#5cc96b', '#4da8e8', '#b06ae8']
RED = '#c0281e'
GOLD = '#ecb92f'


def capsule(B, cx, cy, cz, R, color, sides=8, step=0.6):
    """작은 캡슐 공: 위는 색, 아래는 흰색. 구 셰이딩이 구워져 있다."""
    layers = sphere_layers(R, step, cy)

    def mats(i, y1, y2, a):
        up = (y1 + y2) / 2 > cy - 0.02
        c = color if up else '#f2f2f2'
        return plastic(c, shape=('sphere', (cx, cy, cz), (1, 1, 1)), spec=0.7, bevel=0.0, noise=0.0)
    B.extend(lathe(cx, cz, layers, mats, sides=sides))


def build(name='gacha_machine'):
    B = []
    gm = gold(GOLD)
    dark = Mat('#26262d', base=b_brushed('#2a2a32', 0.04), spec=0.4, bevel=0.06)
    red = lacquer(RED, bevel=0.07, spec=0.5)
    white = plastic('#f4f1ea', bevel=0.06)

    B.append(Box((3.0, 0, 3.0), (13.0, 1.0, 13.0), dark))
    B.append(Box((3.5, 1.0, 3.5), (12.5, 6.5, 12.5), red))
    B.append(Box((3.25, 6.5, 3.25), (12.75, 7.2, 12.75), gm))
    for sx in (3.3, 12.2):
        for sz in (3.3, 12.2):
            B.append(Box((sx, 1.0, sz), (sx + 0.5, 6.5, sz + 0.5), gm))
    # 앞 패널, 다이얼, 손잡이
    B.append(Box((4.6, 2.8, 12.5), (11.4, 6.0, 12.8), white))
    dial = Mat('#25252c', spec=0.3, bevel=0.0).decal(
        d_many(d_ellipse(0.25, 0.25, 3.15, 3.15, fill=rgba('#3a3a44'), outline=rgba('#e8b830'), width=0.12),
               *[d_line([(1.7 + 1.25 * np.cos(a), 1.7 + 1.25 * np.sin(a)), (1.7 + 1.45 * np.cos(a), 1.7 + 1.45 * np.sin(a))], rgba('#e8b830'), 0.07)
                 for a in np.linspace(0, 2 * np.pi, 12, endpoint=False)]))
    B.append(Box((6.4, 3.0, 12.8), (9.6, 5.8, 13.0), {'south': dial, '*': dark}))
    hub = ('z', 22.5, (8.0, 4.4, 13.0))
    B.append(Box((6.6, 4.0, 13.0), (9.4, 4.8, 13.5), gm, rot=hub))
    B.append(Box((7.6, 3.0, 13.0), (8.4, 5.8, 13.5), gm, rot=hub))
    B.append(Box((7.3, 4.1, 13.4), (8.7, 4.7, 13.9), gold('#f5d26a')))
    # 배출구
    B.append(Box((5.6, 1.0, 12.5), (10.4, 2.4, 13.6), dark))
    B.append(Box((6.4, 1.3, 12.8), (9.6, 2.1, 13.62), Mat('#0a0a0d', bevel=0)))
    capsule(B, 8.0, 1.75, 13.0, 0.85, CAPS[3], step=0.85)
    # 마퀴 전구
    lamp = Mat('#fff0b0', emit=0.3, spec=0.0, bevel=0.0, noise=0.0)
    for i in range(5):
        x = 4.5 + i * 1.75
        B.append(Box((x, 6.62, 12.75), (x + 0.7, 7.05, 13.1), lamp))
    # 유리 돔: 틀 + 반사 막대 (유리면은 없애 캡슐이 보이게)
    glass = plastic('#bfe6ff', bevel=0.0, spec=1.0)
    for sx in (4.2, 11.4):
        for sz in (4.2, 11.4):
            B.append(Box((sx, 7.2, sz), (sx + 0.4, 13.3, sz + 0.4), glass))
    B.append(Box((4.0, 7.2, 4.0), (12.0, 7.6, 12.0), gm))
    B.append(Box((4.0, 13.0, 4.0), (12.0, 13.5, 12.0), gm))
    # 돔 안 캡슐
    spots = [(8, 8.9, 8)] + [(8 + 2.55 * np.sin(a), 8.9, 8 + 2.55 * np.cos(a)) for a in np.linspace(0, 6.283, 5, endpoint=False)]
    spots += [(8 + 1.6 * np.sin(a + 0.6), 11.15, 8 + 1.6 * np.cos(a + 0.6)) for a in np.linspace(0, 6.283, 3, endpoint=False)]
    spots += [(8, 12.0, 8)]
    for i, (x, y, z) in enumerate(spots):
        capsule(B, x, y, z, 1.6 if y < 11 else 1.4, CAPS[i % 6], sides=8, step=0.5)
    # 윗장식 + 2027 간판
    B.append(Box((3.8, 13.5, 3.8), (12.2, 14.0, 12.2), gm))
    B.append(Box((4.8, 14.0, 4.8), (11.2, 14.6, 11.2), red))
    sign = Mat('#1f1f26', bevel=0.06).decal(d_text('2027', 3.6, 0.8, 1.2, rgba('#f6c945'), stroke=0.04, stroke_color=rgba('#a87a10')))
    B.append(Box((4.2, 14.6, 6.4), (11.8, 16.0, 9.6), {'south': sign, 'north': sign, '*': dark}))
    return Model(name, B, density=7, gui=(20, 30, 0), ground_scale=0.34)
