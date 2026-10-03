"""기념품 모델 1: 안경, 복주머니, 양 인형, 미니 보신각 종."""
import numpy as np
from core import Box, Model, Mat, rgb
from lib import *
from shapes import prism, prism_z, lathe, sphere, sphere_layers, frame, profile_layers

GOLD = '#f0b92a'


def heart(B, cx, cy, cz, s, color='#ff5d73'):
    m = plastic(color, bevel=0.06, spec=0.7, noise=0.0, emit=0.15)
    d = s * 0.7
    B.append(Box((cx - d / 2, cy - d / 2 - s * 0.05, cz - 0.2), (cx + d / 2, cy + d / 2 - s * 0.05, cz + 0.2), m, rot=('z', 45, (cx, cy, 0))))
    for sx in (-1, 1):
        r = s * 0.42
        B.append(Box((cx + sx * s * 0.28 - r / 2, cy + s * 0.3 - r / 2, cz - 0.2), (cx + sx * s * 0.28 + r / 2, cy + s * 0.3 + r / 2, cz + 0.2), m,
                     rot=('z', 45, (cx + sx * s * 0.28, cy + s * 0.3, 0))))


def sparkle(B, cx, cy, cz, s, color='#fff3b0'):
    m = Mat(color, emit=0.4, noise=0.0, bevel=0.0)
    B.append(Box((cx - s, cy - s * 0.18, cz - 0.1), (cx + s, cy + s * 0.18, cz + 0.1), m))
    B.append(Box((cx - s * 0.18, cy - s, cz - 0.1), (cx + s * 0.18, cy + s, cz + 0.1), m))
    B.append(Box((cx - s * 0.4, cy - s * 0.4, cz - 0.08), (cx + s * 0.4, cy + s * 0.4, cz + 0.08), m, rot=('z', 45, (cx, cy, 0))))


# ------------------------------------------------------------------ 2027 안경
def glasses():
    B = []
    gm = gold(GOLD, bevel=0.08)
    for i, (x1, x2, txt) in enumerate(((1.0, 7.4, '20'), (8.6, 15.0, '27'))):
        img, d, ppu = art(6.4, 4.2, 36)
        d.text((3.2 * ppu, 2.15 * ppu), txt, font=font(2.7 * ppu), fill=rgba('#f6c945'), anchor='mm', stroke_width=int(0.12 * ppu), stroke_fill=rgba('#a8730d'))
        d.line([(0.6 * ppu, 3.5 * ppu), (2.2 * ppu, 0.6 * ppu)], fill=(255, 255, 255, 150), width=int(0.3 * ppu))
        d.line([(1.4 * ppu, 3.8 * ppu), (2.4 * ppu, 1.9 * ppu)], fill=(255, 255, 255, 110), width=int(0.18 * ppu))
        lens = Mat('#8fd0ff', base=b_vgrad('#cfeeff', '#6db4ee'), alpha=0.7, spec=0.9, noise=0.0, bevel=0.0).overlay(ov_image(img, x1 + 0.0, 10.4, ppu))
        B.append(Box((x1 + 0.3, 7.0, 7.55), (x2 - 0.3, 10.0, 8.25), {'south': lens, 'north': lens, '*': lens}))
        B.extend(frame(x1, 6.6, 7.2, x2, 10.4, 8.6, 0.7, gm))
        # 모서리 각진 장식
        for cx_, cy_ in ((x1 + 0.2, 6.8), (x2 - 0.2, 6.8)):
            B.append(Box((cx_ - 0.45, cy_ - 0.45, 7.1), (cx_ + 0.45, cy_ + 0.45, 8.7), gold('#fff0a8', bevel=0.05), rot=('z', 45, (cx_, cy_, 0))))
    B.append(Box((7.4, 8.6, 7.3), (8.6, 9.6, 8.5), gm))
    B.append(Box((7.5, 7.1, 7.5), (8.5, 8.0, 8.0), gm))
    for sx in (0.2, 15.0):
        B.append(Box((sx, 8.8, 0.6), (sx + 0.8, 9.7, 7.9), gm))
        hook = sx
        B.append(Box((hook, 6.8, 0.6), (hook + 0.8, 9.7, 1.5), gm))
    for sx in (-0.1, 15.0):
        pass
    return Model('party_glasses', B, density=9, gui=(22, -30, 0), ground_scale=0.5, kind='item')


# ------------------------------------------------------------------ 새해 복주머니
def pouch(opened=False):
    B = []
    squash = 0.84 if opened else 0.9
    red = '#c8201f'
    silk = lambda shape: Mat(red, base=b_cloth(red, 0.45), shape=shape, spec=0.35, shin=14, bevel=0.0, noise=0.01, ambient=0.72, diffuse=0.4)
    img, d, ppu = art(8, 8, 36)
    # 금박 동전 문양 + 구름
    cx, cy = 4 * ppu, 4 * ppu
    d.ellipse([cx - 1.9 * ppu, cy - 1.9 * ppu, cx + 1.9 * ppu, cy + 1.9 * ppu], fill=rgba('#f0b92a'), outline=rgba('#8a5a06'), width=int(0.12 * ppu))
    d.ellipse([cx - 1.45 * ppu, cy - 1.45 * ppu, cx + 1.45 * ppu, cy + 1.45 * ppu], outline=rgba('#8a5a06'), width=int(0.1 * ppu))
    d.rectangle([cx - 0.55 * ppu, cy - 0.55 * ppu, cx + 0.55 * ppu, cy + 0.55 * ppu], fill=rgba('#c8201f'), outline=rgba('#8a5a06'), width=int(0.08 * ppu))
    for (ox, oy) in ((-2.9, -0.6), (2.9, -0.6), (-2.5, 2.4), (2.5, 2.4)):
        d.ellipse([cx + (ox - 0.55) * ppu, cy + (oy - 0.55) * ppu, cx + (ox + 0.55) * ppu, cy + (oy + 0.55) * ppu], fill=rgba('#f6d66a'))
    emblem = ov_image(img, 4.0, 8.4, ppu)

    body = [(0.8, 1.6, 2.9), (1.6, 3.0, 3.9), (3.0, 5.2, 4.5), (5.2, 7.2, 4.1), (7.2, 8.8, 3.1), (8.8, 9.9, 1.9)]
    body = [(a * squash, b * squash, r * 1.12 + (0.25 if opened else 0)) for a, b, r in body]
    cyb = 4.8 * squash
    B.extend(lathe(8, 8, body, lambda i, y1, y2, a: silk(('sphere', (8, cyb, 8), (1.0, 1.0, 1.0))).overlay(ov_band(1.1 * squash, 1.45 * squash, '#f0b92a', 0.04)).overlay(emblem), sides=16))
    ny = 9.9 * squash
    B.extend(lathe(8, 8, [(ny, ny + 0.6, 1.6)], lambda *a: silk(('cylY', 8, 8)), sides=16))
    # 주름진 윗단(프릴)
    fr = [(ny + 0.6, ny + 0.95, 2.5), (ny + 0.95, ny + 1.3, 3.2), (ny + 1.3, ny + 1.6, 3.6)]
    frill = lambda i, y1, y2, a: Mat('#e0392d', base=b_cloth('#e0392d', 0.45), shape=('cylY', 8, 8), spec=0.3, bevel=0.0, ambient=0.78, diffuse=0.4)
    B.extend(lathe(8, 8, fr, frill, sides=16))
    for k in range(4):   # 프릴 끝 뾰족뾰족
        ang = k * 90 + 45
    B.extend(prism(8, 8, ny + 1.55, ny + 1.62, 3.0, Mat('#4a0b09', bevel=0.0, noise=0.01), sides=16, top=True))
    # 금색 조임 끈 + 매듭 + 술
    gm = gold(GOLD)
    B.extend(prism(8, 8, ny + 0.1, ny + 0.5, 1.8, gm, sides=16, top=True, bottom=False))
    # 앞면 매듭 장식 + 술 (몸통 표면 바깥에 얹는다)
    kz = 8 + 4.55 * (1.0 if not opened else 1.05)
    B.append(Box((7.1, 7.0 * squash, kz + 0.05), (8.9, 8.8 * squash, kz + 0.55), gm, rot=('z', 45, (8, 7.9 * squash, 0))))
    B.append(Box((7.65, 7.55 * squash, kz + 0.5), (8.35, 8.25 * squash, kz + 0.75), gold('#fff0a8')))
    B.append(Box((7.85, 4.4 * squash, kz - 0.35), (8.15, 7.0 * squash, kz + 0.1), gm))
    for sx in (-0.55, 0, 0.55):
        B.append(Box((8 + sx - 0.22, 1.6 * squash, kz - 0.6 + abs(sx) * 0.2), (8 + sx + 0.22, 4.6 * squash, kz - 0.2 + abs(sx) * 0.2), Mat('#c8201f', base=b_cloth('#c8201f', 0.3), bevel=0.03)))
    B.append(Box((7.3, 4.3 * squash, kz - 0.7), (8.7, 4.8 * squash, kz - 0.1), gm))
    if opened:
        coin = lambda: gold('#ffd54a', bevel=0.1)
        for (cx_, cy_, z_) in ((5.8, 11.3, 7.6), (10.4, 10.8, 8.4), (8.0, 12.4, 8.0)):
            B.extend(prism_z(cx_, cy_, z_ - 0.25, z_ + 0.25, 1.15, coin(), sides=8, front=True, back=True))
        sparkle(B, 3.6, 12.6, 8.8, 0.7)
        sparkle(B, 12.8, 13.0, 8.8, 0.6)
    return Model('lucky_pouch' + ('_open' if opened else ''), B, density=8, gui=(18, 28, 0), ground_scale=0.5, kind='item')


# ------------------------------------------------------------------ 양 인형
def sheep(squish=False):
    B = []
    k = 0.8 if squish else 1.0          # 누르면 납작
    w = 1.12 if squish else 1.0
    wool = lambda shape: Mat('#f7f3ea', base=b_wool('#f7f3ea', 0.8), shape=shape, spec=0.0, bevel=0.0, noise=0.01, ambient=0.8, diffuse=0.34)
    face_c = '#3b3a42'
    fur_dark = Mat(face_c, base=b_cloth(face_c, 0.4), bevel=0.04, noise=0.012, spec=0.1)
    cy = 5.4 * k
    # 다리 4
    for lx in (4.8, 9.4):
        for lz in (4.9, 9.0):
            B.append(Box((lx, 0.4, lz), (lx + 1.9 * w, 2.6 * k + 0.4, lz + 1.9 * w), fur_dark))
    # 몸통 (양털)
    body_layers = sphere_layers(4.9, 0.42, cy + 0.6, squash=0.9 * k)
    body_layers = [(y1, y2, a * w) for y1, y2, a in body_layers]
    B.extend(lathe(8, 7.4, body_layers, lambda i, y1, y2, a: wool(('sphere', (8, cy + 0.6, 7.4), (1.0, 1.0, 1.0))), sides=16))
    # 보송 뭉치
    for (bx_, by_, bz_, r) in ((4.2, 7.4, 6.0, 1.7), (11.8, 7.4, 6.2, 1.7), (8.0, 10.4, 6.6, 1.9), (5.2, 4.2, 4.2, 1.5), (10.8, 4.4, 4.4, 1.5)):
        B.extend(sphere(bx_, by_ * k + 0.2, bz_, r, lambda i, *a, _p=(bx_, by_ * k + 0.2, bz_): wool(('sphere', _p, (1, 1, 1))), step=0.45, sides=8))
    # 머리 (앞)
    hy = 4.2 * k
    B.append(Box((5.7, hy + 0.2, 10.4), (10.3, hy + 4.2 * k, 13.8), {'south': Mat(face_c, base=b_cloth(face_c, 0.4), bevel=0.04, spec=0.1).overlay(None) if False else fur_dark, '*': fur_dark}))
    img, d, ppu = art(4.6, 4.2 * k, 40)
    H = (4.2 * k) * ppu
    def eye(ex):
        d.ellipse([ex - 0.55 * ppu, H * 0.42 - 0.62 * ppu, ex + 0.55 * ppu, H * 0.42 + 0.62 * ppu], fill=rgba('#ffffff'))
        d.ellipse([ex - 0.36 * ppu, H * 0.42 - 0.34 * ppu, ex + 0.36 * ppu, H * 0.42 + 0.4 * ppu], fill=rgba('#101018'))
        d.ellipse([ex - 0.2 * ppu, H * 0.42 - 0.3 * ppu, ex + 0.02 * ppu, H * 0.42 - 0.08 * ppu], fill=rgba('#ffffff'))
    if squish:   # 눈 감고 활짝 (^ ^)
        for ex in (1.3 * ppu, 3.3 * ppu):
            d.arc([ex - 0.55 * ppu, H * 0.42 - 0.35 * ppu, ex + 0.55 * ppu, H * 0.42 + 0.55 * ppu], 200, 340, fill=rgba('#101018'), width=int(0.2 * ppu))
    else:
        eye(1.3 * ppu); eye(3.3 * ppu)
    d.ellipse([0.2 * ppu, H * 0.58, 0.95 * ppu, H * 0.58 + 0.5 * ppu], fill=(255, 140, 150, 170))
    d.ellipse([3.65 * ppu, H * 0.58, 4.4 * ppu, H * 0.58 + 0.5 * ppu], fill=(255, 140, 150, 170))
    d.ellipse([1.8 * ppu, H * 0.6, 2.8 * ppu, H * 0.6 + 0.7 * ppu], fill=rgba('#59565f'))
    d.ellipse([2.15 * ppu, H * 0.62, 2.45 * ppu, H * 0.62 + 0.28 * ppu], fill=rgba('#15151a'))
    d.arc([1.8 * ppu, H * 0.74, 2.3 * ppu, H * 0.74 + 0.45 * ppu], 20, 160, fill=rgba('#15151a'), width=int(0.08 * ppu))
    d.arc([2.3 * ppu, H * 0.74, 2.8 * ppu, H * 0.74 + 0.45 * ppu], 20, 160, fill=rgba('#15151a'), width=int(0.08 * ppu))
    B[-1].mat = {'south': fur_dark.overlay(ov_image(img, 5.7, hy + 0.2 + 4.2 * k, ppu)), '*': fur_dark}
    # 귀 (처진 귀) + 머리 위 곱슬
    for sx, rot in ((4.2, ('z', 22.5, (5.7, hy + 3.4 * k, 0))), (10.3, ('z', -22.5, (10.3, hy + 3.4 * k, 0)))):
        B.append(Box((sx, hy + 2.2 * k, 11.2), (sx + 1.5, hy + 3.5 * k, 12.6), fur_dark, rot=rot))
    B.extend(sphere(8, hy + 4.2 * k + 0.6, 12.0, 1.5, lambda i, *a: wool(('sphere', (8, hy + 4.2 * k + 0.6, 12.0), (1, 1, 1))), step=0.45, sides=8))
    # 빨간 목도리 + 방울
    scarf = Mat('#d22d27', base=b_cloth('#d22d27', 0.35), bevel=0.05, noise=0.01)
    B.append(Box((5.3, hy - 0.5, 9.4), (10.7, hy + 0.7, 13.6), scarf))
    B.append(Box((6.4, hy - 2.0, 13.4), (8.0, hy + 0.6, 13.9), scarf))
    gmat = gold(GOLD, shape=('sphere', (9.4, hy - 0.2, 14.0), (1, 1, 1)), spec=1.0)
    B.extend(sphere(9.4, hy - 0.2, 14.0, 0.8, lambda *a: gmat, step=0.5, sides=8))
    if squish:
        heart(B, 2.6, 11.6, 9.0, 2.0)
        heart(B, 13.4, 12.2, 9.0, 1.7)
        heart(B, 8.0, 14.0, 9.0, 1.5)
    # 꼬리
    B.extend(sphere(8, 5.2 * k, 2.2, 1.3, lambda *a: wool(('sphere', (8, 5.2 * k, 2.2), (1, 1, 1))), step=0.6, sides=8))
    return Model('sheep_plush' + ('_squish' if squish else ''), B, density=6, gui=(20, 28, 0), ground_scale=0.5, kind='item')


# ------------------------------------------------------------------ 미니 보신각 종
def mini_bell():
    B = []
    gm = gold(GOLD)
    prof = profile_layers([(1.5, 4.5), (2.3, 4.15), (3.0, 3.6), (5.4, 3.0), (7.6, 2.45), (9.2, 1.9), (10.2, 1.4), (10.8, 1.0)], 0.42)
    img, d, ppu = art(8, 3.4, 40)
    d.text((4.0 * ppu, 1.2 * ppu), '2027', font=font(1.7 * ppu), fill=rgba('#6a430a'), anchor='mm', stroke_width=int(0.05 * ppu), stroke_fill=rgba('#f6d66a'))
    d.line([(0.6 * ppu, 2.45 * ppu), (7.4 * ppu, 2.45 * ppu)], fill=rgba('#6a430a'), width=int(0.1 * ppu))
    d.line([(0.9 * ppu, 2.75 * ppu), (7.1 * ppu, 2.75 * ppu)], fill=rgba('#6a430a'), width=int(0.06 * ppu))
    txt = ov_image(img, 4.0, 6.0, ppu)

    def mats(i, y1, y2, a):
        m = bronze('#c4932f', shape=('cylY', 8, 8), bevel=0.0, spec=0.95, shin=18, ambient=0.72)
        ym = (y1 + y2) / 2
        if 2.15 < ym < 2.85 or 7.55 < ym < 8.1:
            m = bronze('#9a6a1e', shape=('cylY', 8, 8), bevel=0.0, spec=0.7, ambient=0.72)
        if 3.3 < ym < 5.0:
            m = m.overlay(txt)
        return m
    B.extend(lathe(8, 8, prof, mats, sides=16))
    wood_m = wood('#5b3a26', 0.7, 'v')
    B.extend(prism(8, 8, 10.8, 13.8, 0.75, wood_m.with_(shape=('cylY', 8, 8)), sides=8))
    B.extend(prism(8, 8, 13.8, 14.5, 1.0, Mat('#c8201f', base=b_cloth('#c8201f', 0.3), shape=('cylY', 8, 8), spec=0.4, bevel=0.0), sides=8))
    B.extend(prism(8, 8, 11.6, 12.2, 0.9, Mat('#c8201f', base=b_cloth('#c8201f', 0.3), shape=('cylY', 8, 8), spec=0.4, bevel=0.0), sides=8, top=False))
    B.append(Box((7.3, 0.2, 7.3), (8.7, 1.6, 8.7), gm))
    B.extend(sphere(8, 0.3, 8, 0.9, lambda *a: gold(GOLD, shape=('sphere', (8, 0.3, 8), (1, 1, 1))), step=0.5, sides=8))
    return Model('mini_bell', B, density=7, gui=(20, 28, 0), ground_scale=0.5, kind='item')
