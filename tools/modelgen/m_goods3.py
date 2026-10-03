"""기념품 모델 3: 귀여운 양 인형(설치 가능), 샴페인, 떡국, 방패연, 윷 세트, 덕담 카드."""
import numpy as np
from core import Box, Model, Mat, rgb
from lib import *
from shapes import prism, prism_z, lathe, sphere, sphere_layers, profile_layers, ring
from m_goods1 import heart, sparkle

GOLD = '#f0b92a'


# ------------------------------------------------------------------ 귀여운 양 인형 (치비 비율: 큰 머리 + 폭신한 몸)
def sheep(squish=False):
    B = []
    dh = -0.5 if squish else 0.0
    wool = lambda c: Mat('#fbf8f1', base=b_wool('#fbf8f1', 0.84), shape=('sphere', c, (1, 1, 1)), spec=0.0, bevel=0.0, noise=0.008, ambient=0.84, diffuse=0.3)
    skin_c = '#f6d9c2'
    brown = Mat('#6b4a3a', base=b_cloth('#6b4a3a', 0.4), bevel=0.05, noise=0.01)
    # 다리 (짧고 통통, 발끝은 진한 갈색)
    for lx in (5.2, 9.2):
        for lz in (5.0, 8.6):
            B.append(Box((lx, 0.0, lz), (lx + 1.7, 1.9, lz + 1.7), brown))
            B.append(Box((lx - 0.05, 0.0, lz - 0.05), (lx + 1.75, 0.5, lz + 1.75), Mat('#3f2b22', bevel=0.04, noise=0.01)))
    # 몸통
    bc = (8.0, 4.9, 7.2)
    B.extend(lathe(8, 7.2, sphere_layers(4.1, 0.4, bc[1], squash=0.92), lambda i, *a: wool(bc), sides=16))
    for (bx, by, bz, r) in ((4.2, 5.2, 6.2, 1.7), (11.8, 5.2, 6.2, 1.7), (8.0, 8.4, 5.6, 1.7), (8.0, 3.8, 3.4, 1.6), (5.6, 2.9, 4.6, 1.3), (10.4, 2.9, 4.6, 1.3)):
        B.extend(sphere(bx, by, bz, r, lambda i, *a, _c=(bx, by, bz): wool(_c), step=0.45, sides=8))
    # 머리 (큰 둥근 얼굴)
    hc = (8.0, 8.2 + dh, 10.0)
    img, d, ppu = art(7.6, 7.6, 44)
    def X(x): return (x - 4.2) * ppu
    def Y(y): return (12.4 - y) * ppu
    ey = 8.4 + dh
    if squish:     # 행복하게 눈 감은 ^ ^
        for ex in (6.2, 9.8):
            d.arc([X(ex - 0.8), Y(ey + 0.5), X(ex + 0.8), Y(ey - 0.7)], 200, 340, fill=rgba('#2a1a14'), width=int(0.28 * ppu))
    else:
        for ex in (6.2, 9.8):
            d.ellipse([X(ex - 0.82), Y(ey + 1.0), X(ex + 0.82), Y(ey - 1.0)], fill=rgba('#241612'))
            d.ellipse([X(ex - 0.5), Y(ey + 0.85), X(ex + 0.05), Y(ey + 0.3)], fill=rgba('#ffffff'))
            d.ellipse([X(ex + 0.1), Y(ey - 0.2), X(ex + 0.5), Y(ey - 0.6)], fill=rgba('#ffffff'))
    for bx in (4.9, 11.1):
        d.ellipse([X(bx - 0.75), Y(ey - 0.85), X(bx + 0.75), Y(ey - 1.55)], fill=(255, 130, 150, 190))
    d.ellipse([X(7.65), Y(ey - 0.85), X(8.35), Y(ey - 1.35)], fill=rgba('#d98a8a'))
    d.arc([X(7.0), Y(ey - 1.3), X(8.0), Y(ey - 2.1)], 10, 170, fill=rgba('#6b3a30'), width=int(0.14 * ppu))
    d.arc([X(8.0), Y(ey - 1.3), X(9.0), Y(ey - 2.1)], 10, 170, fill=rgba('#6b3a30'), width=int(0.14 * ppu))
    face = Mat(skin_c, base=b_cloth(skin_c, 0.5), shape=('sphere', hc, (1, 1, 1)), spec=0.08, bevel=0.0, noise=0.008, ambient=0.86, diffuse=0.28).overlay(ov_image(img, 4.2, 12.4, ppu))
    B.extend(lathe(8, 10.0, sphere_layers(3.7, 0.4, hc[1], squash=0.95), lambda i, *a: face, sides=16))
    # 귀 (양옆, 분홍 안쪽)
    ear = Mat('#e9bba3', base=b_cloth('#e9bba3', 0.4), bevel=0.05, noise=0.01)
    pink = Mat('#f2a0a8', bevel=0.0, noise=0.01)
    for sx, ang in ((2.2, 22.5), (12.6, -22.5)):
        B.append(Box((sx, 8.2 + dh, 9.2), (sx + 1.9, 9.6 + dh, 10.8), ear, rot=('z', ang, (sx + (0.2 if ang > 0 else 1.7), 9.0 + dh, 0))))
        B.append(Box((sx + 0.35, 8.5 + dh, 10.7), (sx + 1.55, 9.3 + dh, 10.9), pink, rot=('z', ang, (sx + (0.2 if ang > 0 else 1.7), 9.0 + dh, 0))))
    # 머리 위 곱슬 앞머리 + 옆머리
    for (px, py, pz, r) in ((6.0, 11.6, 10.4, 1.5), (8.0, 12.0, 10.6, 1.6), (10.0, 11.6, 10.4, 1.5), (4.8, 10.6, 9.6, 1.2), (11.2, 10.6, 9.6, 1.2), (8.0, 11.2, 12.2, 1.1)):
        B.extend(sphere(px, py + dh, pz, r, lambda i, *a, _c=(px, py + dh, pz): wool(_c), step=0.45, sides=8))
    # 빨간 리본(작은 나비 매듭) + 금방울
    red = Mat('#d92f2a', base=b_cloth('#d92f2a', 0.35), bevel=0.06, noise=0.01)
    B.append(Box((5.9, 3.6 + dh, 12.5), (8.0, 4.8 + dh, 13.1), red, rot=('z', 22.5, (8.0, 4.2 + dh, 0))))
    B.append(Box((8.0, 3.6 + dh, 12.5), (10.1, 4.8 + dh, 13.1), red, rot=('z', -22.5, (8.0, 4.2 + dh, 0))))
    B.append(Box((7.4, 3.7 + dh, 12.4), (8.6, 4.7 + dh, 13.3), Mat('#b8201c', bevel=0.06, noise=0.01)))
    gc = (8.0, 2.9 + dh, 13.2)
    B.extend(sphere(gc[0], gc[1], gc[2], 0.75, lambda *a: gold(GOLD, shape=('sphere', gc, (1, 1, 1)), spec=1.0), step=0.4, sides=8))
    if squish:
        heart(B, 2.4, 12.0, 9.5, 2.0)
        heart(B, 13.6, 12.6, 9.5, 1.7)
        heart(B, 8.0, 14.6, 9.5, 1.5)
    return Model('sheep_plush' + ('_squish' if squish else ''), B, density=6, gui=(18, 28, 0), ground_scale=0.5, kind='item')


# ------------------------------------------------------------------ 새해 축하 샴페인
def champagne(state=0):
    """state: 0 기본 / 1 흔드는 중(거품) / 2 뻥! (코르크 날아가고 거품이 앞으로 쏴아아)"""
    B = []
    glass = '#1d3f2a'
    gl = lambda: Mat(glass, base=b_vgrad('#2a5a3a', '#12301e'), shape=('cylY', 8, 8), spec=1.0, shin=22, bevel=0.0, noise=0.006, ambient=0.72, diffuse=0.44)
    img, d, ppu = art(5.8, 5.4, 44)
    d.rectangle([0, 0, 5.8 * ppu, 5.4 * ppu], fill=rgba('#f7efd8'))
    d.rectangle([0, 0, 5.8 * ppu, 0.45 * ppu], fill=rgba('#c9972a'))
    d.rectangle([0, 4.95 * ppu, 5.8 * ppu, 5.4 * ppu], fill=rgba('#c9972a'))
    d.text((2.9 * ppu, 1.55 * ppu), '2027', font=font(1.7 * ppu), fill=rgba('#8c1d18'), anchor='mm')
    d.line([(0.7 * ppu, 2.5 * ppu), (5.1 * ppu, 2.5 * ppu)], fill=rgba('#c9972a'), width=int(0.08 * ppu))
    d.text((2.9 * ppu, 3.2 * ppu), 'CHAMPAGNE', font=font(0.62 * ppu), fill=rgba('#5a3a0a'), anchor='mm')
    d.text((2.9 * ppu, 4.15 * ppu), 'NEW YEAR', font=font(0.5 * ppu), fill=rgba('#8c6a1a'), anchor='mm')
    label = ov_image(img, 5.1, 7.2, ppu)
    prof = profile_layers([(0.4, 2.7), (1.0, 3.0), (7.2, 3.0), (8.4, 2.6), (9.8, 1.9), (11.2, 1.35), (12.2, 1.2), (13.4, 1.15)], 0.5)

    def mats(i, y1, y2, a):
        ym = (y1 + y2) / 2
        if ym > 8.8:
            m = Mat('#e8b830', base=b_vgrad('#ffe27a', '#c9941a'), shape=('cylY', 8, 8), spec=1.0, shin=14, bevel=0.0, noise=0.01, ambient=0.72)
            if int(ym * 2) % 3 == 0:
                m = m.with_(color='#c9941a')
            return m
        m = gl()
        if 1.8 < ym < 7.1:
            m = m.overlay(label)
        return m
    B.extend(lathe(8, 8, prof, mats, sides=16))
    wire = gold('#d9dde3', spec=1.0, bevel=0.0)
    cork = Mat('#d8b078', base=b_wood('#d8b078', 0.82, 'v'), shape=('cylY', 8, 8), bevel=0.0, spec=0.1)
    if state < 2:
        B.extend(prism(8, 8, 13.4, 14.8, 1.2, cork, sides=8))
        B.extend(prism(8, 8, 14.8, 15.2, 1.45, Mat('#e0b97f', base=b_wood('#e0b97f', 0.85, 'u'), bevel=0.0), sides=8))
        B.append(Box((6.6, 15.2, 7.85), (9.4, 15.4, 8.15), wire))
        B.append(Box((7.85, 15.2, 6.6), (8.15, 15.4, 9.4), wire))
        for sx in (-1, 1):
            B.append(Box((8 + sx * 1.2 - 0.12, 13.0, 8 - 1.2), (8 + sx * 1.2 + 0.12, 15.2, 8 + 1.2), wire))
    if state == 1:
        for (bx_, by_, bz_, r) in ((4.6, 9.8, 8.6, 0.55), (11.6, 10.6, 8.4, 0.5), (5.2, 13.0, 9.0, 0.4), (10.8, 12.4, 8.8, 0.45), (3.4, 6.4, 9.0, 0.4)):
            B.append(Box((bx_ - r, by_ - r, bz_ - r), (bx_ + r, by_ + r, bz_ + r), Mat('#f4fbff', emit=0.2, noise=0.0, bevel=0.0), rot=('y', 45, (bx_, 0, bz_))))
        sparkle(B, 4.0, 12.0, 9.4, 0.8, '#ffffff')
    if state == 2:
        foam = lambda c: Mat('#ffffff', base=b_wool('#ffffff', 0.9), shape=('sphere', c, (1, 1, 1)), spec=0.5, bevel=0.0, noise=0.01, ambient=0.86, diffuse=0.25)
        # 병목에서 앞(+z)으로 쏴아아: 점점 퍼지는 거품 줄기
        for i, (fy, fz, r) in enumerate(((14.0, 8.0, 1.2), (14.4, 9.6, 1.4), (14.6, 11.4, 1.5), (14.5, 13.2, 1.3), (14.0, 14.8, 1.0))):
            B.extend(sphere(8, fy, fz, r, lambda i_, *a, _c=(8, fy, fz): foam(_c), step=0.5, sides=8))
        for (dx, dy, dz) in ((6.4, 14.6, 10.4), (9.8, 15.0, 11.0), (7.0, 13.6, 13.6), (9.2, 14.0, 14.4), (5.8, 15.4, 12.2), (10.6, 14.2, 12.6)):
            B.append(Box((dx - 0.3, dy - 0.3, dz - 0.3), (dx + 0.3, dy + 0.3, dz + 0.3), Mat('#dff1ff', emit=0.2, noise=0.0, bevel=0.0)))
        B.extend(prism(12.6, 8, 14.8, 15.6, 0.55, cork, sides=8))
        sparkle(B, 3.2, 14.6, 9.4, 0.9, '#fff3b0')
    return Model('champagne' + ('_shake' if state == 1 else '_pop' if state == 2 else ''), B, density=7, gui=(18, -28, 0), ground_scale=0.5, kind='item')


# ------------------------------------------------------------------ 떡국 한 그릇
def tteokguk():
    B = []
    cer = lambda: Mat('#f4f1ea', base=b_vgrad('#fbf9f4', '#dcd8cf'), shape=('cylY', 8, 8), spec=0.8, shin=22, bevel=0.0, noise=0.004, ambient=0.78, diffuse=0.36)
    blue = Mat('#2c5fa8', shape=('cylY', 8, 8), spec=0.6, bevel=0.0, noise=0.004, ambient=0.78, diffuse=0.36)
    # 그릇: 바닥(굽) + 벽(속 빈 링)
    B.extend(prism(8, 8, 0.4, 1.4, 2.6, cer(), sides=16, top=False, bottom=True))
    layers = profile_layers([(1.4, 3.0), (2.6, 4.4), (4.4, 5.6), (6.2, 6.3)], 0.5)
    for (y1, y2, a) in layers:
        m = blue if (y2 - 1.4) < 0.9 or y1 > 5.5 else cer()
        B.extend(ring(8, 8, y1, y2, a, 0.55, m, sides=16))
    B.extend(prism(8, 8, 1.4, 1.5, 3.4, cer(), sides=16, top=True, bottom=False))
    # 국물 + 고명
    broth = Mat('#f2e6c8', base=b_vgrad('#f7edd4', '#e9d9b0'), bevel=0.0, noise=0.02, spec=0.4, ambient=0.9, diffuse=0.2)
    B.extend(prism(8, 8, 1.5, 5.3, 5.4, broth.with_(shape=None), sides=16, top=True, bottom=False, side_faces=False))
    cake = Mat('#fffdf7', base=b_paper('#fffdf7', 0.03), spec=0.5, bevel=0.0, ambient=0.9, diffuse=0.2, shape=('sphere', (8, 5.0, 8), (1, 0.4, 1)))
    for (cx, cz) in ((5.8, 6.4), (8.6, 5.4), (10.4, 7.8), (6.4, 9.6), (9.2, 10.2), (8.0, 8.0), (11.2, 9.8)):
        B.extend(prism(cx, cz, 5.25, 5.75, 1.05, cake, sides=8, top=True))
    egg = Mat('#ffd74a', bevel=0.0, noise=0.03, spec=0.2)
    for (x1, z1, x2, z2) in ((5.0, 8.4, 7.4, 8.9), (8.8, 8.8, 11.0, 9.3), (7.0, 6.2, 9.4, 6.7)):
        B.append(Box((x1, 5.7, z1), (x2, 5.9, z2), egg))
    for (x1, z1) in ((6.6, 7.4), (9.4, 6.6), (10.0, 9.2), (5.6, 7.8)):
        B.append(Box((x1, 5.7, z1), (x1 + 0.9, 5.85, z1 + 0.3), Mat('#2d2d2d', bevel=0.0, noise=0.05)))     # 김 가루
    for (x1, z1) in ((7.8, 9.4), (8.4, 7.0), (6.0, 6.0), (10.2, 8.2), (7.0, 10.0)):
        B.append(Box((x1, 5.7, z1), (x1 + 0.45, 5.95, z1 + 0.45), Mat('#4fb04a', bevel=0.0, noise=0.05)))   # 대파
    # 김
    steam = Mat('#ffffff', emit=0.2, noise=0.0, bevel=0.0, alpha=0.7)
    for (sx, sy, sz, s) in ((7.0, 8.2, 8.0, 0.55), (9.0, 9.4, 7.4, 0.5), (8.0, 10.6, 8.4, 0.45)):
        B.append(Box((sx - s, sy - s, sz - s), (sx + s, sy + s, sz + s), steam, rot=('y', 45, (sx, 0, sz))))
    # 숟가락
    sp = gold('#d9dde3', spec=1.0, bevel=0.05)
    B.append(Box((11.2, 5.0, 11.2), (11.8, 5.4, 15.6), sp, rot=('y', -22.5, (11.5, 0, 11.4))))
    return Model('tteokguk', B, density=7, gui=(26, 28, 0), ground_scale=0.5, kind='item')


# ------------------------------------------------------------------ 방패연
def kite(lift=False):
    B = []
    img, d, ppu = art(16, 16, 32)
    cx, cy = 8 * ppu, 8 * ppu
    R = 7.0 * ppu
    def tri(pts, col):
        d.polygon([(cx + x * ppu, cy + y * ppu) for x, y in pts], fill=rgba(col))
    # 다이아몬드 바탕(흰 종이) + 가장자리 색 삼각형 + 가운데 원
    tri([(0, -7), (7, 0), (0, 7), (-7, 0)], '#f8f4ea')
    tri([(0, -7), (7, 0), (0, -3.2), (-7, 0)], '#d62f2a') if False else None
    tri([(0, -7), (-3.5, -3.5), (3.5, -3.5)], '#d62f2a')
    tri([(7, 0), (3.5, -3.5), (3.5, 3.5)], '#2c5fa8')
    tri([(0, 7), (-3.5, 3.5), (3.5, 3.5)], '#d62f2a')
    tri([(-7, 0), (-3.5, -3.5), (-3.5, 3.5)], '#2c5fa8')
    d.line([(cx, cy - R), (cx, cy + R)], fill=rgba('#6b4a2a'), width=int(0.22 * ppu))
    d.line([(cx - R, cy), (cx + R, cy)], fill=rgba('#6b4a2a'), width=int(0.22 * ppu))
    d.line([(cx - R * .5, cy - R * .5), (cx + R * .5, cy + R * .5)], fill=rgba('#6b4a2a'), width=int(0.12 * ppu))
    d.line([(cx - R * .5, cy + R * .5), (cx + R * .5, cy - R * .5)], fill=rgba('#6b4a2a'), width=int(0.12 * ppu))
    d.ellipse([cx - 1.7 * ppu, cy - 1.7 * ppu, cx + 1.7 * ppu, cy + 1.7 * ppu], fill=rgba('#d62f2a'), outline=rgba('#6b4a2a'), width=int(0.16 * ppu))
    d.ellipse([cx - 0.9 * ppu, cy - 0.9 * ppu, cx + 0.9 * ppu, cy + 0.9 * ppu], fill=(0, 0, 0, 0))
    d.text((cx, cy - 4.8 * ppu), '2027', font=font(1.0 * ppu), fill=rgba('#8c1d18'), anchor='mm') if False else None
    paper = Mat('#f8f4ea', base=b_paper('#f8f4ea', 0.04), bevel=0.0, noise=0.0).overlay(ov_image(img, 0, 16, ppu))
    ky = 9.6
    B.append(Box((1.0, ky - 7.0, 7.4), (15.0, ky + 7.0, 7.7), {'south': paper, '*': Mat('#8a6a3a', bevel=0.0)}, faces=['south'], rot=None) if False else
             Box((8 - 5.0, ky - 5.0, 7.4), (8 + 5.0, ky + 5.0, 7.7), {'south': paper, 'north': Mat('#efe6cf', base=b_paper('#efe6cf', 0.04), bevel=0.0), '*': Mat('#8a6a3a', bevel=0.0)}, rot=('z', 45, (8, ky, 0))))
    # 대나무 살 (정면 십자)
    bam = wood('#c9a65a', 0.8, 'u')
    B.append(Box((7.85, ky - 7.0, 7.7), (8.15, ky + 7.0, 7.95), bam))
    B.append(Box((0.9, ky - 0.15, 7.7), (15.1, ky + 0.15, 7.95), bam))
    # 꼬리 리본 (파랑/흰/빨강)
    for i, (col, off) in enumerate((('#2c5fa8', -1.2), ('#f4f1ea', 0.0), ('#d62f2a', 1.2))):
        rib = Mat(col, base=b_cloth(col, 0.4), bevel=0.05, noise=0.01)
        for k in range(3):
            B.append(Box((8 + off - 0.4 + (0.5 if k % 2 else -0.5), 1.4 - k * 0.0 + (2 - k) * 0.0 + 1.6 - 1.6 * k * 0.0 + (0.9 * (2 - k)) - 0.9 * 2, 7.5), (8 + off + 0.4 + (0.5 if k % 2 else -0.5), 1.4 + 0.9 * (2 - k) + 0.0, 7.7), rib)) if False else None
    for i, (col, off) in enumerate((('#2c5fa8', -1.5), ('#f4f1ea', 0.0), ('#d62f2a', 1.5))):
        rib = Mat(col, base=b_cloth(col, 0.4), bevel=0.05, noise=0.01)
        B.append(Box((8 + off - 0.35, 0.2 + (0.6 if lift else 0.0), 7.5), (8 + off + 0.35, ky - 4.4, 7.6), rib))
    # 얼레(실패)와 연줄
    B.append(Box((7.95, ky - 4.6, 7.95), (8.05, ky - 3.2, 8.05), Mat('#3a3a3a', bevel=0.0)))
    spool = wood('#8a5a34', 0.7, 'v')
    B.extend(prism(13.2, 12.0, 0.2, 2.8, 1.3, spool, sides=8))
    B.append(Box((12.6, 2.8, 11.6), (13.8, 3.0, 12.4), Mat('#c9a65a', bevel=0.0)))
    if lift:
        sparkle(B, 2.4, 14.6, 8.0, 1.0)
        sparkle(B, 14.0, 15.0, 8.0, 0.8)
    return Model('kite' + ('_fly' if lift else ''), B, density=7, gui=(14, -24, 0), ground_scale=0.5, kind='item')


# ------------------------------------------------------------------ 윷 세트
def yut(thrown=False):
    B = []
    rnd = wood('#8a5a34', 0.7, 'u')
    flat = Mat('#e8c98a', base=b_wood('#f0d6a0', 0.82, 'u'), bevel=0.06, noise=0.01)
    mark = Mat('#e8c98a', base=b_wood('#f0d6a0', 0.82, 'u'), bevel=0.06, noise=0.01)
    # 판(붉은 천)
    cloth = Mat('#b8281f', base=b_cloth('#b8281f', 0.5), bevel=0.08, noise=0.01)
    B.append(Box((0.8, 0.4, 3.0), (15.2, 1.2, 13.0), {'up': cloth, '*': cloth}))
    B.append(Box((0.8, 0.4, 3.0), (15.2, 0.6, 13.0), gold(GOLD, bevel=0.05)))
    # 막대 4개: (중심x, 중심z, y축 회전, 위로 보이는 면: 평평/둥근)  평평면은 연한 나무 + 칼자국
    if not thrown:
        specs = [(8.0, 4.6, 0, True, 0.0), (8.4, 6.9, 22.5, False, 0.0), (7.6, 9.2, -22.5, True, 0.0), (8.2, 11.4, 0, False, 0.0)]
    else:
        specs = [(5.0, 6.0, 22.5, True, 5.2), (10.6, 5.0, -22.5, False, 7.4), (6.4, 10.4, -45, True, 9.0), (11.0, 10.2, 45, False, 6.2)]
    for i, (cx, cz, ang, flat_up, lift) in enumerate(specs):
        carve = Mat('#f0d6a0', base=b_wood('#f0d6a0', 0.82, 'u'), bevel=0.06, noise=0.01).decal(
            d_many(*[d_rect(1.2 + k * 1.6, 0.2, 1.7 + k * 1.6, 1.5, rgba('#7a5230')) for k in range(i % 3 + 1)]))
        top, bot = (carve, rnd) if flat_up else (rnd, carve)
        rot = ('y', ang, (cx, 0, cz)) if ang else None
        y0 = 1.2 + lift
        B.append(Box((cx - 4.5, y0, cz - 0.85), (cx + 4.5, y0 + 1.2, cz + 0.85), {'up': top, 'down': bot, '*': rnd}, rot=rot))
    if thrown:
        sparkle(B, 3.0, 13.6, 12.0, 1.2)
        sparkle(B, 13.6, 14.0, 12.0, 0.9)
        sparkle(B, 8.4, 14.6, 12.0, 0.8)
    return Model('yut_set' + ('_thrown' if thrown else ''), B, density=8, gui=(30, 20, 0), ground_scale=0.5, kind='item')


# ------------------------------------------------------------------ 새해 덕담 카드
def card(opened=False):
    B = []
    W, H = 10.0, 13.0
    img, d, ppu = art(W, H, 40)
    d.rectangle([0, 0, W * ppu, H * ppu], fill=rgba('#c8201f'))
    d.rectangle([0.5 * ppu, 0.5 * ppu, (W - 0.5) * ppu, (H - 0.5) * ppu], outline=rgba('#f0b92a'), width=int(0.16 * ppu))
    d.rectangle([0.85 * ppu, 0.85 * ppu, (W - 0.85) * ppu, (H - 0.85) * ppu], outline=rgba('#f6d66a'), width=int(0.06 * ppu))
    d.text((W / 2 * ppu, 3.0 * ppu), '2027', font=font(2.3 * ppu), fill=rgba('#f6d66a'), anchor='mm')
    # 귀여운 양 얼굴 엠블럼
    cx, cy = W / 2 * ppu, 7.6 * ppu
    for (ox, oy, r) in ((-1.6, -1.2, 1.1), (1.6, -1.2, 1.1), (-2.0, 0.4, 1.1), (2.0, 0.4, 1.1), (0, -1.9, 1.2), (-1.0, 1.8, 1.0), (1.0, 1.8, 1.0)):
        d.ellipse([cx + (ox - r) * ppu, cy + (oy - r) * ppu, cx + (ox + r) * ppu, cy + (oy + r) * ppu], fill=rgba('#fbf8f1'))
    d.ellipse([cx - 1.6 * ppu, cy - 1.6 * ppu, cx + 1.6 * ppu, cy + 1.7 * ppu], fill=rgba('#f6d9c2'))
    for ex in (-0.75, 0.75):
        d.ellipse([cx + (ex - 0.28) * ppu, cy - 0.45 * ppu, cx + (ex + 0.28) * ppu, cy + 0.3 * ppu], fill=rgba('#241612'))
    d.arc([cx - 0.5 * ppu, cy + 0.3 * ppu, cx + 0.5 * ppu, cy + 1.0 * ppu], 10, 170, fill=rgba('#6b3a30'), width=int(0.1 * ppu))
    d.text((W / 2 * ppu, 11.4 * ppu), 'HAPPY NEW YEAR', font=font(0.78 * ppu), fill=rgba('#f6d66a'), anchor='mm')
    silk = Mat('#c8201f', base=b_paper('#c8201f', 0.03), bevel=0.06, noise=0.0)
    front = silk.overlay(ov_image(img, 3.0, 1.0 + H, ppu))
    inner = Mat('#fbf6e8', base=b_paper('#fbf6e8', 0.03), bevel=0.05, noise=0.0)
    if not opened:
        B.append(Box((3.0, 1.0, 7.0), (3.0 + W, 1.0 + H, 7.7), {'south': front, 'north': inner, '*': silk}))
        B.append(Box((5.0, 0.4, 6.4), (11.0, 1.6, 8.6), gold(GOLD, bevel=0.06)))
    else:
        i2, d2, p2 = art(W, H, 40)
        d2.rectangle([0, 0, W * p2, H * p2], fill=rgba('#fbf6e8'))
        d2.text((W / 2 * p2, 1.4 * p2), 'HAPPY 2027', font=font(1.2 * p2), fill=rgba('#c8201f'), anchor='mm')
        for k in range(5):
            d2.line([(1.2 * p2, (3.0 + k * 1.5) * p2), ((W - 1.2) * p2, (3.0 + k * 1.5) * p2)], fill=rgba('#c9b890'), width=int(0.07 * p2))
        for (hx, hy, s) in ((W - 2.2, H - 1.6, 0.9),):
            d2.polygon([(hx * p2 + x * s * p2 * 0.06, hy * p2 - y * s * p2 * 0.06) for x, y in heart_pts(0, 0, 34)], fill=rgba('#e85a6b'))
        page = inner.overlay(ov_image(i2, 1.0, 1.0 + H, p2))
        B.append(Box((1.0, 1.0, 7.0), (1.0 + W - 0.2, 1.0 + H, 7.5), {'south': page, '*': inner}))
        B.append(Box((11.0, 1.0, 7.0), (11.0 + 4.0, 1.0 + H, 7.5), {'south': front, '*': silk}) if False else
                 Box((11.0, 1.0, 7.5), (11.0 + 4.0, 1.0 + H, 8.1), {'south': silk.overlay(ov_image(img, 11.0 - 6.0, 1.0 + H, ppu)), '*': silk}, rot=('y', -22.5, (11.0, 0, 7.8))))
        sparkle(B, 2.0, 15.0, 8.0, 1.0)
        sparkle(B, 14.4, 14.6, 8.0, 0.8)
    return Model('greeting_card' + ('_open' if opened else ''), B, density=8, gui=(16, -26, 0), ground_scale=0.5, kind='item')
