"""기념품 모델 2: 세뱃돈 봉투, 미니 달력, 폭죽 키링, 사이다, 황금 배지."""
import datetime
import numpy as np
from core import Box, Model, Mat, rgb
from lib import *
from shapes import prism, prism_z, lathe, sphere, sphere_layers, frame, profile_layers
from m_goods1 import heart, sparkle

GOLD = '#f0b92a'


# ------------------------------------------------------------------ 세뱃돈 봉투
def envelope(opened=False):
    B = []
    red = '#c8201f'
    W, H = 9.6, 12.2
    x0, y0 = 3.2, 1.0
    img, d, ppu = art(W, H, 40)
    # 금 테두리와 모서리 장식
    d.rectangle([0.45 * ppu, 0.45 * ppu, (W - 0.45) * ppu, (H - 0.45) * ppu], outline=rgba('#f0b92a'), width=int(0.16 * ppu))
    d.rectangle([0.8 * ppu, 0.8 * ppu, (W - 0.8) * ppu, (H - 0.8) * ppu], outline=rgba('#f6d66a'), width=int(0.06 * ppu))
    for (cx_, cy_) in ((0.8, 0.8), (W - 0.8, 0.8), (0.8, H - 0.8), (W - 0.8, H - 0.8)):
        d.ellipse([(cx_ - 0.28) * ppu, (cy_ - 0.28) * ppu, (cx_ + 0.28) * ppu, (cy_ + 0.28) * ppu], fill=rgba('#f6d66a'))
    # 중앙 메달리온 + 2027
    mx, my = W / 2, H * 0.62
    d.ellipse([(mx - 2.5) * ppu, (my - 2.5) * ppu, (mx + 2.5) * ppu, (my + 2.5) * ppu], fill=rgba('#f0b92a'), outline=rgba('#8a5a06'), width=int(0.14 * ppu))
    d.ellipse([(mx - 2.0) * ppu, (my - 2.0) * ppu, (mx + 2.0) * ppu, (my + 2.0) * ppu], fill=rgba('#c8201f'), outline=rgba('#f6d66a'), width=int(0.1 * ppu))
    d.text((mx * ppu, (my - 0.45) * ppu), '20', font=font(1.55 * ppu), fill=rgba('#f6d66a'), anchor='mm')
    d.text((mx * ppu, (my + 0.9) * ppu), '27', font=font(1.55 * ppu), fill=rgba('#f6d66a'), anchor='mm')
    # 위쪽 구름무늬 줄
    for i in range(5):
        cx_ = 1.9 + i * 1.45
        d.arc([(cx_ - 0.6) * ppu, (H * 0.28 - 0.4) * ppu, (cx_ + 0.6) * ppu, (H * 0.28 + 0.8) * ppu], 180, 360, fill=rgba('#f0b92a'), width=int(0.1 * ppu))
    # 하단 구름
    for i in range(5):
        cx_ = 1.9 + i * 1.45
        d.arc([(cx_ - 0.6) * ppu, (H - 2.1) * ppu, (cx_ + 0.6) * ppu, (H - 0.8) * ppu], 180, 360, fill=rgba('#f0b92a'), width=int(0.1 * ppu))
    silk = Mat(red, base=b_cloth(red, 0.5), spec=0.25, bevel=0.1, noise=0.012)
    front = silk.overlay(ov_image(img, x0, y0 + H, ppu))
    back = Mat('#b01c1b', base=b_cloth('#b01c1b', 0.5), bevel=0.1, noise=0.012)
    B.append(Box((x0, y0, 7.4), (x0 + W, y0 + H, 8.6), {'south': front, 'north': back, '*': silk}))
    # 뚜껑(플랩): 닫힘 = 아래로 향한 계단 삼각형, 열림 = 위로 젖혀짐
    flap = Mat('#d42b27', base=b_cloth('#d42b27', 0.5), bevel=0.08, noise=0.012, spec=0.2)
    rows = [4.8, 3.9, 3.0, 2.1, 1.2, 0.5] if not opened else [4.4, 3.2, 2.0, 0.9]
    fy = y0 + H
    for i, hw in enumerate(rows):
        if not opened:
            yb, yt = fy - 0.85 * (i + 1) - 0.1, fy - 0.85 * i
        else:
            yb, yt = fy + 0.6 * i, fy + 0.6 * (i + 1) + 0.1
        B.append(Box((8 - hw, yb, 8.55), (8 + hw, yt, 8.85), flap))
    if not opened:
        B.extend(prism_z(8, fy - 4.9, 8.85, 9.2, 0.95, gold(GOLD, bevel=0.08), sides=16, front=True))
    else:
        for (bx1, ang, col, txt) in ((1.2, -22.5, '#e8d36a', '50000'), (9.4, 22.5, '#7fc47f', '10000')):
            bi, bd, bp = art(5.2, 2.6, 36)
            bd.rectangle([0.12 * bp, 0.12 * bp, 5.08 * bp, 2.48 * bp], outline=rgba('#3d3d2a'), width=int(0.08 * bp))
            bd.ellipse([0.5 * bp, 0.5 * bp, 1.9 * bp, 2.1 * bp], outline=rgba('#3d3d2a'), width=int(0.08 * bp))
            bd.text((3.7 * bp, 1.3 * bp), txt, font=font(0.9 * bp), fill=rgba('#35352a'), anchor='mm')
            bill = Mat(col, base=b_paper(col, 0.05), bevel=0.06).overlay(ov_image(bi, 0, 2.6, bp))
            cxb = bx1 + 2.6
            B.append(Box((bx1, fy - 0.2, 7.7 + (0.05 if ang < 0 else 0.25)), (bx1 + 5.2, fy + 2.4, 7.78 + (0.05 if ang < 0 else 0.25)), {'south': Mat(col, base=b_paper(col, 0.05), bevel=0.06), 'north': bill if False else Mat(col, base=b_paper(col, 0.05), bevel=0.06), '*': Mat(col)},
                         rot=('z', ang, (cxb, fy - 0.2, 0))))
            B[-1].mat['south'] = Mat(col, base=b_paper(col, 0.05), bevel=0.06)
    return Model('red_envelope' + ('_open' if opened else ''), B, density=8, gui=(18, -28, 0), ground_scale=0.5, kind='item')


# ------------------------------------------------------------------ 2027 미니 달력
def calendar(flip=False):
    B = []
    # 2027-01: 1월 1일은 금요일
    first = datetime.date(2027, 1, 1)
    start_col = (first.weekday() + 1) % 7   # 일요일 시작
    W, H = 11.6, 12.4
    ppu = 36
    img, d, _ = art(W, H, ppu)
    # 머리판
    d.rectangle([0, 0, W * ppu, 3.3 * ppu], fill=rgba('#c8201f'))
    d.rectangle([0, 3.0 * ppu, W * ppu, 3.3 * ppu], fill=rgba('#9b1814'))
    d.text((W / 2 * ppu, 1.55 * ppu), '2027', font=font(2.2 * ppu), fill=rgba('#ffffff'), anchor='mm')
    d.text((W / 2 * ppu, 2.65 * ppu), 'JANUARY', font=font(0.6 * ppu), fill=rgba('#ffd6a0'), anchor='mm')
    d.rectangle([0, 3.3 * ppu, W * ppu, H * ppu], fill=rgba('#fbf8f0'))
    cw, rh = (W - 0.8) / 7, 1.2
    days = 'SMTWTFS'
    for i, ch in enumerate(days):
        col = '#c8201f' if i == 0 else ('#2b6cc4' if i == 6 else '#555555')
        d.text(((0.4 + cw * (i + 0.5)) * ppu, 3.95 * ppu), ch, font=font(0.7 * ppu), fill=rgba(col), anchor='mm')
    d.line([(0.4 * ppu, 4.35 * ppu), ((W - 0.4) * ppu, 4.35 * ppu)], fill=rgba('#cfc8b8'), width=int(0.05 * ppu))
    for dd in range(1, 32):
        idx = start_col + dd - 1
        r, c = idx // 7, idx % 7
        cx_, cy_ = 0.4 + cw * (c + 0.5), 4.95 + rh * r
        col = '#c8201f' if c == 0 else ('#2b6cc4' if c == 6 else '#333333')
        if dd == 1:
            d.ellipse([(cx_ - 0.55) * ppu, (cy_ - 0.55) * ppu, (cx_ + 0.55) * ppu, (cy_ + 0.55) * ppu], fill=rgba('#f0b92a'))
            col = '#7a1210'
        d.text((cx_ * ppu, cy_ * ppu), str(dd), font=font(0.72 * ppu), fill=rgba(col), anchor='mm')
    x0, y0 = 2.2, 2.4
    paper = Mat('#fbf8f0', base=b_paper('#fbf8f0', 0.02), bevel=0.05, noise=0.0)
    front = paper.overlay(ov_image(img, x0, y0 + H, ppu))
    wood_m = wood('#6b4430', 0.72, 'u', planks=1.4)
    # 받침(트레이) + 본체
    B.append(Box((1.4, 0.8, 5.2), (14.6, 2.2, 9.6), wood_m))
    B.append(Box((x0, y0, 7.0), (x0 + W, y0 + H, 7.8), {'south': front, 'north': Mat('#e8e2d2', base=b_paper('#e8e2d2', 0.03), bevel=0.05), '*': paper}))
    B.append(Box((x0 - 0.2, y0 - 0.15, 6.8), (x0 + W + 0.2, y0 + 0.3, 8.0), wood_m))
    # 고리 6개
    ring = gold('#cfd3da', spec=1.0, bevel=0.05)
    for i in range(7):
        rx = x0 + 0.9 + i * (W - 1.8) / 6
        B.append(Box((rx - 0.28, y0 + H - 0.9, 7.8), (rx + 0.28, y0 + H + 0.9, 8.5), ring))
    B.append(Box((x0 + 0.6, y0 + H - 0.2, 7.55), (x0 + 0.9 + 0.1, y0 + H + 0.1, 7.75), ring))
    # 뒤 받침대
    B.append(Box((4.6, 2.2, 3.4), (11.4, 11.0, 4.0), wood_m, rot=('x', 22.5, (8, 2.2, 3.7))))
    if flip:
        sheet = Mat('#fbf8f0', base=b_paper('#fbf8f0', 0.02), bevel=0.05, noise=0.0)
        img2, d2, _ = art(W, 3.1, ppu)
        d2.rectangle([0, 0, W * ppu, 3.1 * ppu], fill=rgba('#c8201f'))
        d2.text((W / 2 * ppu, 1.5 * ppu), '2027', font=font(1.9 * ppu), fill=rgba('#ffffff'), anchor='mm')
        d2.text((W / 2 * ppu, 2.6 * ppu), 'JANUARY', font=font(0.5 * ppu), fill=rgba('#ffd6a0'), anchor='mm')
        B.append(Box((x0 + 0.1, y0 + H + 0.5, 8.6), (x0 + W - 0.1, y0 + H + 3.6, 8.75), {'south': sheet.overlay(ov_image(img2, x0, y0 + H + 3.6, ppu)), '*': sheet},
                     rot=('x', -45, (8, y0 + H + 0.5, 8.6))))
        sparkle(B, 13.8, 15.0, 9.4, 0.7)
    return Model('mini_calendar' + ('_flip' if flip else ''), B, density=8, gui=(20, -30, 0), ground_scale=0.5, kind='item')


# ------------------------------------------------------------------ 폭죽 키링
def firecracker(state=0):
    """state: 0 기본 / 1 불붙음(퓨즈에 불꽃) / 2 터짐(찢어진 종이 + 색종이)"""
    B = []
    silver = gold('#cfd3da', spec=1.0, bevel=0.05)
    # 키링 고리 + 사슬
    for k in range(8):   # 8 조각으로 둥근 고리
        ang = k * 45
        px, py = 8 + 2.15 * np.cos(np.radians(ang)), 14.0 + 2.15 * np.sin(np.radians(ang))
        tan = ang + 90
        r_ = tan % 180
        r_ = r_ - 180 if r_ > 90 else r_
        swap = abs(r_) > 45
        rr = r_ - 90 if r_ > 45 else (r_ + 90 if r_ < -45 else r_)
        hx, hy = (0.45, 1.05) if swap else (1.05, 0.45)
        B.append(Box((px - hx, py - hy, 7.7), (px + hx, py + hy, 8.3), silver, rot=('z', rr, (px, py, 0)) if rr else None))
    B.append(Box((7.45, 10.4, 7.7), (8.55, 11.9, 8.3), silver))
    B.append(Box((7.1, 9.9, 7.6), (8.9, 10.8, 8.4), silver))
    red = '#d2231b'
    tube_m = lambda cx: Mat(red, base=b_vgrad('#e8392e', '#b01b15'), shape=('cylY', cx, 8), spec=0.4, shin=14, bevel=0.0, noise=0.01, ambient=0.72)
    img, d, ppu = art(2.4, 6.0, 40)
    for i in range(3):
        d.polygon([(1.2 * ppu, (0.7 + i * 1.6) * ppu), (1.55 * ppu, (1.25 + i * 1.6) * ppu), (2.1 * ppu, (1.3 + i * 1.6) * ppu), (1.65 * ppu, (1.65 + i * 1.6) * ppu),
                   (1.8 * ppu, (2.2 + i * 1.6) * ppu), (1.2 * ppu, (1.9 + i * 1.6) * ppu), (0.6 * ppu, (2.2 + i * 1.6) * ppu), (0.75 * ppu, (1.65 + i * 1.6) * ppu),
                   (0.3 * ppu, (1.3 + i * 1.6) * ppu), (0.85 * ppu, (1.25 + i * 1.6) * ppu)], fill=rgba('#f6d66a')) if i == 1 else None
    d.rectangle([0, 0.35 * ppu, 2.4 * ppu, 0.6 * ppu], fill=rgba('#f6d66a'))
    d.rectangle([0, 5.2 * ppu, 2.4 * ppu, 5.45 * ppu], fill=rgba('#f6d66a'))
    heights = [(5.2, 10.0), (4.6, 9.4), (5.0, 9.8)] if state < 2 else [(5.6, 8.8), (5.0, 8.2), (5.4, 8.6)]
    for ti, (cx, (yb, yt)) in enumerate(zip((5.7, 8.0, 10.3), heights)):
        # 세 개의 통은 서로 맞닿은 정사각 기둥 대신 8각 기둥
        mats_ = tube_m(cx).overlay(ov_image(img, cx - 1.2, yb + 6.0 if False else yt + 0.35, ppu))
        B.extend(prism(cx, 8, yb, yt, 1.15, mats_, sides=8, top=False))
        B.append(Box((cx - 0.95, yt - 0.02, 7.05), (cx + 0.95, yt + 0.5, 8.95), Mat('#e8d9b0', base=b_paper('#e8d9b0', 0.06), bevel=0.05)))
        B.append(Box((cx - 0.95, yb - 0.45, 7.05), (cx + 0.95, yb + 0.02, 8.95), Mat('#e8d9b0', base=b_paper('#e8d9b0', 0.06), bevel=0.05)))
    # 금 리본 묶음
    B.append(Box((4.35, 6.7, 6.7), (11.65, 7.5, 9.3), gold(GOLD, bevel=0.08)))
    B.append(Box((7.2, 6.6, 9.2), (8.8, 7.6, 9.6), gold('#fff0a8')))
    # 심지(퓨즈)
    fuse = Mat('#3a2f26', base=b_cloth('#3a2f26', 0.3), bevel=0.0)
    B.append(Box((7.85, 10.0, 7.85), (8.15, 11.0, 8.15), fuse))
    if state == 1:
        flame = Mat('#ffd84a', emit=0.5, noise=0.0, bevel=0.0, base=b_vgrad('#fff7c4', '#ff9a1f'))
        B.append(Box((7.6, 10.9, 7.6), (8.4, 11.9, 8.4), flame))
        B.append(Box((7.3, 11.3, 7.3), (8.7, 12.2, 8.7), Mat('#ff7a12', emit=0.4, noise=0.0, bevel=0.0), rot=('y', 45, (8, 0, 8))))
        sparkle(B, 6.6, 12.8, 8.0, 0.9, '#ffe48a')
        sparkle(B, 9.6, 13.2, 8.0, 0.7, '#ffe48a')
    if state == 2:
        paper = Mat('#e8392e', base=b_paper('#e8392e', 0.05), bevel=0.05)
        for (px, ang, py) in ((4.6, -22.5, 8.8), (11.4, 22.5, 8.8), (8.0, 0, 9.6)):
            B.append(Box((px - 0.5, py, 7.6), (px + 0.5, py + 3.4, 8.4), paper, rot=('z', ang, (px, py, 0))))
        for (cx_, cy_, cz_, col) in ((3.4, 13.0, 9.0, '#f5a623'), (12.6, 12.6, 9.2, '#5cc96b'), (6.4, 14.4, 9.4, '#4da8e8'),
                                      (9.8, 13.8, 9.0, '#f8d347'), (2.4, 10.4, 9.0, '#b06ae8'), (13.6, 10.4, 9.0, '#e8453c'), (8.0, 15.2, 9.4, '#ffffff')):
            B.append(Box((cx_ - 0.4, cy_ - 0.4, cz_ - 0.1), (cx_ + 0.4, cy_ + 0.4, cz_ + 0.1), Mat(col, emit=0.2, noise=0.0, bevel=0.05), rot=('z', 22.5, (cx_, cy_, 0))))
        smoke = Mat('#cfcfd6', alpha=0.85, noise=0.03, bevel=0.0)
        for (sx, sy, sz, s) in ((6.0, 11.6, 8.0, 1.0), (10.0, 12.4, 8.0, 0.9), (8.0, 12.8, 8.0, 1.1)):
            B.append(Box((sx - s, sy - s, sz - s), (sx + s, sy + s, sz + s), smoke, rot=('y', 22.5, (sx, 0, sz))))
        sparkle(B, 8.0, 14.0, 9.6, 1.2, '#fff0a8')
    return Model('firecracker_keychain' + ('_lit' if state == 1 else '_burst' if state == 2 else ''), B, density=8, gui=(18, -28, 0), ground_scale=0.5, kind='item')


# ------------------------------------------------------------------ 새해 축하 사이다
def cider(state=0):
    """state: 0 기본 / 1 흔드는 중(거품) / 2 뻥! (마개 날아가고 거품 분수)"""
    B = []
    glass = '#2f8a4f'
    gl = lambda: Mat(glass, base=b_vgrad('#3da363', '#1f6a3a'), shape=('cylY', 8, 8), spec=1.0, shin=22, bevel=0.0, noise=0.006, ambient=0.7, diffuse=0.42)
    img, d, ppu = art(5.6, 5.0, 44)
    d.rectangle([0, 0, 5.6 * ppu, 5.0 * ppu], fill=rgba('#fbf6e6'))
    d.rectangle([0, 0, 5.6 * ppu, 0.5 * ppu], fill=rgba('#c8201f'))
    d.rectangle([0, 4.5 * ppu, 5.6 * ppu, 5.0 * ppu], fill=rgba('#c8201f'))
    d.text((2.8 * ppu, 1.75 * ppu), '2027', font=font(1.6 * ppu), fill=rgba('#c8201f'), anchor='mm')
    d.text((2.8 * ppu, 3.0 * ppu), 'CIDER', font=font(0.85 * ppu), fill=rgba('#8a5a06'), anchor='mm')
    d.line([(0.6 * ppu, 3.6 * ppu), (5.0 * ppu, 3.6 * ppu)], fill=rgba('#f0b92a'), width=int(0.1 * ppu))
    for sx in (0.9, 4.7):
        d.ellipse([(sx - 0.2) * ppu, 3.95 * ppu, (sx + 0.2) * ppu, 4.35 * ppu], fill=rgba('#f0b92a'))
    label = ov_image(img, 5.2, 7.4, ppu)
    prof = profile_layers([(0.4, 2.6), (1.0, 2.95), (7.4, 3.0), (8.6, 2.7), (9.8, 2.0), (10.8, 1.3), (11.6, 1.05), (13.2, 1.0)], 0.5)

    def mats(i, y1, y2, a):
        m = gl()
        ym = (y1 + y2) / 2
        if 2.2 < ym < 7.3:
            m = m.overlay(label)
        if ym > 10.2:
            m = Mat('#e8b830', base=b_vgrad('#ffe27a', '#c9941a'), shape=('cylY', 8, 8), spec=1.0, shin=14, bevel=0.0, noise=0.01, ambient=0.72)
            if int(ym * 2) % 3 == 0:
                m = m.with_(color='#c9941a')
        return m
    B.extend(lathe(8, 8, prof, mats, sides=16))
    gm = gold(GOLD)
    if state < 2:
        # 코르크 + 철사 망
        B.extend(prism(8, 8, 13.2, 14.5, 1.15, Mat('#d8b078', base=b_wood('#d8b078', 0.82, 'v'), shape=('cylY', 8, 8), bevel=0.0, spec=0.1), sides=8))
        B.extend(prism(8, 8, 14.5, 14.9, 1.35, Mat('#e0b97f', base=b_wood('#e0b97f', 0.85, 'u'), bevel=0.0), sides=8))
        wire = gold('#d9dde3', spec=1.0, bevel=0.0)
        B.append(Box((6.7, 14.9, 7.85), (9.3, 15.1, 8.15), wire))
        B.append(Box((7.85, 14.9, 6.7), (8.15, 15.1, 9.3), wire))
        for sx in (-1, 1):
            B.append(Box((8 + sx * 1.1 - 0.12, 13.0, 8 - 1.1), (8 + sx * 1.1 + 0.12, 14.9, 8 + 1.1), wire))
    if state == 1:
        for (bx_, by_, bz_, r) in ((4.6, 9.8, 8.6, 0.55), (11.6, 10.6, 8.4, 0.5), (5.2, 13.0, 9.0, 0.4), (10.8, 12.4, 8.8, 0.45), (3.4, 6.4, 9.0, 0.4)):
            B.append(Box((bx_ - r, by_ - r, bz_ - r), (bx_ + r, by_ + r, bz_ + r), Mat('#e8f6ff', emit=0.2, noise=0.0, bevel=0.0), rot=('y', 45, (bx_, 0, bz_))))
        sparkle(B, 4.0, 12.0, 9.4, 0.8, '#ffffff')
    if state == 2:
        foam = lambda c: Mat('#ffffff', base=b_wool('#ffffff', 0.9), shape=('sphere', c, (1, 1, 1)), spec=0.5, bevel=0.0, noise=0.01, ambient=0.85, diffuse=0.25)
        for (fx, fy, fz, r) in ((8.0, 14.0, 8.0, 1.6), (6.6, 14.9, 8.4, 1.0), (9.6, 14.8, 7.8, 1.0)):
            B.extend(sphere(fx, fy, fz, r, lambda i, *a, _c=(fx, fy, fz): foam(_c), step=0.5, sides=8))
        drop = Mat('#cfeaff', emit=0.2, noise=0.0, bevel=0.0)
        for (dx, dy, dz) in ((4.4, 14.0, 8.4), (11.6, 13.8, 8.0), (5.4, 15.4, 8.0), (10.8, 15.6, 8.2), (3.2, 12.2, 8.6), (12.8, 12.0, 8.4)):
            B.append(Box((dx - 0.3, dy - 0.45, dz - 0.3), (dx + 0.3, dy + 0.45, dz + 0.3), drop))
        B.extend(prism(12.6, 8, 14.8, 15.6, 0.55, Mat('#d8b078', base=b_wood('#d8b078', 0.82, 'v'), bevel=0.0), sides=8))
        sparkle(B, 3.0, 14.6, 9.2, 0.9, '#fff3b0')
    return Model('sparkling_cider' + ('_shake' if state == 1 else '_pop' if state == 2 else ''), B, density=7, gui=(18, -28, 0), ground_scale=0.5, kind='item')


# ------------------------------------------------------------------ 황금 2027 배지
def badge(shine=False):
    B = []
    gm = lambda **k: gold('#e8b830', bevel=0.1, **k)
    # 붉은 리본 꼬리
    rib = Mat('#c8201f', base=b_cloth('#c8201f', 0.4), bevel=0.06, noise=0.012)
    B.append(Box((4.4, 0.4, 6.7), (7.2, 6.4, 7.2), rib, rot=('z', 22.5, (5.8, 6.4, 0))))
    B.append(Box((8.8, 0.4, 6.7), (11.6, 6.4, 7.2), rib, rot=('z', -22.5, (10.2, 6.4, 0))))
    B.append(Box((5.6, 5.8, 6.9), (10.4, 7.2, 7.4), rib))
    cx, cy = 8.0, 9.2
    # 뒤쪽 팔각 별 + 16각 테
    star = gold('#d9a21e', bevel=0.08)
    B.append(Box((cx - 6.6, cy - 6.6, 7.4), (cx + 6.6, cy + 6.6, 8.2), star, faces=['south', 'east', 'west', 'up', 'down']))
    B.append(Box((cx - 6.6, cy - 6.6, 7.4), (cx + 6.6, cy + 6.6, 8.2), star, rot=('z', 45, (cx, cy, 0)), faces=['south', 'east', 'west', 'up', 'down']))
    B.extend(prism_z(cx, cy, 8.2, 9.0, 5.7, gold('#f2c640', bevel=0.1, spec=1.0), sides=16, front=True))
    B.extend(prism_z(cx, cy, 9.0, 9.35, 5.0, gold('#b98512', bevel=0.1), sides=16, front=True))
    img, d, ppu = art(8.0, 8.0, 56)
    # 월계수 + 2027 + 별
    pass
    for side in (-1, 1):
        for i in range(9):
            a = np.radians(200 + i * 14) if side < 0 else np.radians(340 - i * 14)
            lx, ly = 4 + 3.0 * np.cos(a), 4.2 - 3.0 * np.sin(a)
            d.ellipse([(lx - 0.28) * ppu, (ly - 0.17) * ppu, (lx + 0.28) * ppu, (ly + 0.17) * ppu], fill=rgba('#b98512'))
    d.text((4.0 * ppu, 4.35 * ppu), '2027', font=font(2.0 * ppu), fill=rgba('#7a1f0c'), anchor='mm', stroke_width=int(0.05 * ppu), stroke_fill=rgba('#fff0a8'))
    d.polygon([(4 + 0.9 * np.sin(np.radians(a)), 1.7 - 0.9 * np.cos(np.radians(a))) if False else (0, 0) for a in ()] or [(4.0 * ppu, 1.0 * ppu), (4.3 * ppu, 1.75 * ppu), (5.1 * ppu, 1.75 * ppu), (4.45 * ppu, 2.2 * ppu), (4.7 * ppu, 2.95 * ppu),
               (4.0 * ppu, 2.5 * ppu), (3.3 * ppu, 2.95 * ppu), (3.55 * ppu, 2.2 * ppu), (2.9 * ppu, 1.75 * ppu), (3.7 * ppu, 1.75 * ppu)], fill=rgba('#c8201f'))
    d.arc([1.5 * ppu, 4.6 * ppu, 6.5 * ppu, 7.0 * ppu], 20, 160, fill=rgba('#7a1f0c'), width=int(0.12 * ppu))
    face = Mat('#f2c23a', base=b_vgrad('#f9d85e', '#e3a91d'), spec=0.9, shin=16, bevel=0.0, noise=0.01).overlay(ov_image(img, cx - 4.0, cy + 4.0, ppu))
    B.extend(prism_z(cx, cy, 9.35, 9.55, 4.5, face, sides=16, front=True))
    for k in range(8):
        a = k * 45
        px, py = cx + 5.35 * np.cos(np.radians(a)), cy + 5.35 * np.sin(np.radians(a))
        B.append(Box((px - 0.28, py - 0.28, 9.0), (px + 0.28, py + 0.28, 9.5), gold('#fff0a8', bevel=0.05), rot=('z', 45, (px, py, 0))))
    if shine:
        sparkle(B, 1.6, 15.2, 9.8, 1.5)
        sparkle(B, 14.6, 14.2, 9.8, 1.2)
        sparkle(B, 14.0, 4.0, 9.8, 0.9)
        sparkle(B, 2.4, 6.0, 9.8, 0.8)
        sparkle(B, 8.0, 16.2, 9.9, 0.9)
    return Model('golden_badge' + ('_shine' if shine else ''), B, density=8, gui=(14, -26, 0), ground_scale=0.5, kind='item')
