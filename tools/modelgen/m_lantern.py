import numpy as np
from core import Box, Model, Mat, rgb
from lib import *
from shapes import prism, lathe


def paper_base(top, bottom, glow):
    top, bottom, glow = rgb(top), rgb(bottom), rgb(glow)

    def f(ctx):
        U, V = uv(ctx)
        Y = ctx.P[..., 1]
        t = np.clip((Y - 6.4) / 8.2, 0, 1)[..., None]        # 아래 0 → 위 1
        c = bottom * (1 - t) + top * t
        halo = np.clip(1 - (Y - 6.4) / 3.2, 0, 1)[..., None] ** 1.4
        c = c * (1 - halo * 0.75) + glow * halo * 0.75
        n = lownoise(ctx, 0.5)
        return c * (0.97 + 0.06 * n[..., None])
    return f


def wish_marks(ctx, col):
    """소원 글씨 느낌의 붓 자국과 낙관 (정면 면에만)."""
    if ctx.N[2] <= 0.2:
        return col
    X, Y = ctx.P[..., 0], ctx.P[..., 1]
    ink = np.zeros(X.shape)
    for i, (x, y1, y2) in enumerate([(6.5, 13.2, 10.0), (7.7, 13.2, 8.8), (8.9, 13.2, 10.2), (10.1, 12.8, 11.0)]):
        # 세로 줄: 짧은 가로 획을 이어 붙여 글씨처럼
        yy = y1
        j = 0
        while yy > y2:
            L = 0.55 + 0.25 * ((i * 7 + j * 3) % 3)
            ink = np.maximum(ink, np.clip(1 - np.maximum(np.abs(X - x) - L / 2, 0) / 0.08, 0, 1) * np.clip(1 - np.abs(Y - yy) / 0.2, 0, 1))
            yy -= 0.62
            j += 1
    seal = np.clip(1 - np.maximum(np.abs(X - 8.0) - 0.5, np.abs(Y - 8.2) - 0.5) / 0.06, 0, 1)
    col = col * (1 - ink[..., None] * 0.8) + rgb('#2a140a') * ink[..., None] * 0.8
    col = col * (1 - seal[..., None] * 0.9) + rgb('#c7261b') * seal[..., None] * 0.9
    return col


def build(name='wish_lantern'):
    Y0 = -1.6
    B = []

    def bx(x1, y1, z1, x2, y2, z2, mat, **kw):
        B.append(Box((x1, y1 + Y0, z1), (x2, y2 + Y0, z2), mat, **kw))

    gm = gold('#e8b830')
    bamboo = wood('#c9a65a', 0.8, 'u', planks=0.0)
    bamboo_v = wood('#c9a65a', 0.8, 'v')
    darkw = wood('#4b3426', 0.75, 'u')
    # 불꽃과 연료
    flame_o = Mat('#ff9a1f', base=b_vgrad('#ffd84a', '#ff7a12'), emit=0.25, noise=0.0, bevel=0.0)
    flame_y = Mat('#ffd84a', base=b_vgrad('#fff3a8', '#ffc83a'), emit=0.3, noise=0.0, bevel=0.0)
    flame_w = Mat('#fff6c9', emit=0.3, noise=0.0, bevel=0.0)
    bx(7.2, 3.6, 7.2, 8.8, 4.4, 8.8, darkw)
    bx(7.15, 4.4, 7.15, 8.85, 5.9, 8.85, flame_o)
    bx(7.45, 4.9, 7.45, 8.55, 6.8, 8.55, flame_y)
    bx(7.72, 5.4, 7.72, 8.28, 7.4, 8.28, flame_w)
    # 아래 대나무 테두리(받침 원판) + 세로 살 4개
    B.extend(prism(8, 8, 3.1 + Y0, 3.7 + Y0, 3.9, bamboo, sides=8, bottom=True))
    for (sx, sz) in ((4.6, 4.6), (10.8, 4.6), (4.6, 10.8), (10.8, 10.8)):
        bx(sx, 3.7, sz, sx + 0.6, 6.7, sz + 0.6, bamboo_v)
    bx(4.1, 3.3, 7.6, 11.9, 3.55, 8.4, bamboo)
    bx(7.6, 3.3, 4.1, 8.4, 3.55, 11.9, bamboo)

    paper = lambda i, y1, y2, a: Mat('#f2a24a', base=paper_base('#e0701c', '#ffc95a', '#fff1b8'),
                                      shape=('cylY', 8, 8), ambient=0.82, diffuse=0.2, spec=0.0, bevel=0.18, emit=0.08).overlay(wish_marks)
    layers = [(6.6, 7.8, 3.55), (7.8, 13.0, 3.8), (13.0, 14.2, 3.3), (14.2, 15.0, 2.4)]
    B.extend(lathe(8, 8, [(y1 + Y0, y2 + Y0, a) for y1, y2, a in layers], paper, sides=8))
    # 대나무 띠(가로 살)
    for (yy, a) in ((6.45, 3.62), (12.9, 3.45)):
        B.extend(prism(8, 8, yy + Y0, yy + 0.3 + Y0, a, bamboo, sides=8, top=False, side_faces=True))
    # 꼭대기 매듭과 술
    bx(7.3, 15.0, 7.3, 8.7, 15.6, 8.7, gm)
    bx(7.8, 15.6, 7.8, 8.2, 16.5, 8.2, Mat('#b8281f', base=b_cloth('#b8281f', 0.3), bevel=0))
    return Model(name, B, density=8, gui=(18, 30, 0), ground_scale=0.34)
