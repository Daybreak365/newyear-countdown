import numpy as np
from core import Box, Model, Mat, rgb
from lib import *
from shapes import prism, lathe, sphere_layers

RGB = ['#E8453C', '#F5A623', '#F8D347', '#5CC96B', '#4DA8E8', '#B06AE8']
R = 5.4
CY = 8.0


def shell_mats(color):
    c = rgb(color)
    light = np.minimum(c * 1.18 + 0.08, 1.0)

    def top(ctx):
        # 윗부분이 살짝 더 맑고(밝고) 아래로 갈수록 진한 사출 성형 플라스틱 느낌
        t = np.clip((ctx.P[..., 1] - CY) / R, 0, 1)[..., None]
        return c * (1 - t * 0.0) * (0.92 + 0.12 * t) + light * 0.0
    return top


def build(idx, opened=False):
    color = RGB[idx]
    name = f'capsule_c{idx}' + ('_open' if opened else '')
    B = []
    lift = 2.6 if opened else 0.0
    layers = sphere_layers(R, 0.6, CY)
    n = len(layers)
    low = [l for l in layers if (l[0] + l[1]) / 2 < CY]
    up = [l for l in layers if (l[0] + l[1]) / 2 >= CY]

    def sph(dy):
        return ('sphere', (8, CY + dy, 8), (1, 1, 1))

    # 아래 반구(흰색 진주빛) + 앞면 스티커
    sticker = lambda ctx, base: ov_disc(8, CY - 2.7, 1.25, '#f0b429', soft=0.07)(ctx, ov_ring(8, CY - 2.7, 1.25, 0.22, '#9b6a0a')(ctx, base))
    inner = Mat('#3b3f48', noise=0.01, bevel=0.0, base=b_vgrad('#6b7280', '#2c2f36'))

    def low_mat(i, y1, y2, a):
        m = plastic('#f6f6f8', shape=sph(0), spec=0.85, ambient=0.78, diffuse=0.34, bevel=0.0, noise=0.004).overlay(sticker)
        if i == len(low) - 1:
            return {'up': inner, '*': m}
        return m
    B.extend(lathe(8, 8, low, low_mat, sides=16))
    # 이음매 띠
    seam = plastic('#b9bdc6', shape=sph(0), spec=0.5, bevel=0.0, noise=0.0)
    B.extend(prism(8, 8, CY - 0.18, CY + 0.18, R + 0.14, seam, sides=16, top=False, bottom=False))
    # 위 반구 (색)
    up_l = [(y1 + lift, y2 + lift, a) for y1, y2, a in up]

    def up_mat(i, y1, y2, a):
        m = plastic(color, shape=sph(lift), spec=0.8, shin=40, ambient=0.8, diffuse=0.38, bevel=0.0, noise=0.004, alpha=0.97)
        if opened and i == 0:
            return {'down': inner, '*': m}
        return m
    for k, (y1, y2, a) in enumerate(up_l):
        B.extend(lathe(8, 8, [(y1, y2, a)], lambda i, *_a, _k=k: up_mat(_k, 0, 0, 0), sides=16)) if False else None
    B.extend(lathe(8, 8, up_l, lambda i, y1, y2, a: up_mat(i, y1, y2, a), sides=16))
    if opened:
        # 터져 나오는 빛(가운데 별) + 반짝이
        star = Mat('#fff3b0', emit=0.35, noise=0.0, bevel=0.0, base=b_vgrad('#ffffff', '#ffd45a'))
        glow = Mat('#ffd45a', emit=0.3, noise=0.0, bevel=0.0)
        y = CY + 0.4
        B.append(Box((7.0, y, 7.0), (9.0, y + 2.0, 9.0), star))
        B.append(Box((6.2, y + 0.7, 7.5), (9.8, y + 1.3, 8.5), glow))
        B.append(Box((7.5, y + 0.7, 6.2), (8.5, y + 1.3, 9.8), glow))
        B.append(Box((7.0, y + 0.55, 7.0), (9.0, y + 1.45, 9.0), star, rot=('y', 45, (8, 0, 8))))
        for (sx, sy, sz, s) in ((4.6, 12.3, 8.0, 0.5), (11.4, 12.9, 8.6, 0.6), (6.0, 14.6, 7.2, 0.45), (10.4, 14.2, 8.4, 0.4)):
            B.append(Box((sx - s, sy - s, sz - s), (sx + s, sy + s, sz + s), star, rot=('y', 45, (sx, 0, sz))))
    m = Model(name, B, density=7, gui=(22, 30, 0), ground_scale=0.34, kind='item')
    return m
