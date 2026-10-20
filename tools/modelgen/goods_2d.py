"""굿즈 아이템: 바닐라 느낌의 16x16 픽셀아트 (어두운 윤곽선 + 영역별 밝은/어두운 가장자리 음영).
영역(region)을 도형으로 그리고 → 각 영역의 왼쪽 위 가장자리는 밝게, 오른쪽 아래는 어둡게 → 전체에 윤곽선을 두른다."""
import json
import math
import os
import numpy as np
from PIL import Image

S = 16
OUT = (43, 29, 20)


def hx(c):
    c = c.lstrip('#')
    return np.array([int(c[i:i + 2], 16) for i in (0, 2, 4)], dtype=float)


def lit(c, k):
    return np.clip(hx(c) * k if k <= 1 else hx(c) + (255 - hx(c)) * (k - 1), 0, 255)


class Sprite:
    def __init__(self):
        self.regions = []      # (mask(bool SxS), base color, shade:bool)
        self.dots = []         # (x, y, color) 마지막에 찍는 세부 픽셀

    @staticmethod
    def blank():
        return np.zeros((S, S), dtype=bool)

    def ellipse(self, cx, cy, rx, ry, color, shade=True):
        m = self.blank()
        for y in range(S):
            for x in range(S):
                if ((x + 0.5 - cx) / rx) ** 2 + ((y + 0.5 - cy) / ry) ** 2 <= 1.0:
                    m[y, x] = True
        self.regions.append((m, color, shade))
        return m

    def rect(self, x0, y0, x1, y1, color, shade=True):
        m = self.blank()
        m[y0:y1 + 1, x0:x1 + 1] = True
        self.regions.append((m, color, shade))
        return m

    def poly(self, pts, color, shade=True):
        m = self.blank()
        n = len(pts)
        for y in range(S):
            for x in range(S):
                px, py = x + 0.5, y + 0.5
                inside = False
                j = n - 1
                for i in range(n):
                    xi, yi = pts[i]; xj, yj = pts[j]
                    if (yi > py) != (yj > py) and px < (xj - xi) * (py - yi) / (yj - yi + 1e-9) + xi:
                        inside = not inside
                    j = i
                m[y, x] = inside
        self.regions.append((m, color, shade))
        return m

    def line(self, x0, y0, x1, y1, color, width=1):
        m = self.blank()
        n = int(max(abs(x1 - x0), abs(y1 - y0)) * 2) + 1
        for i in range(n + 1):
            t = i / n
            x, y = x0 + (x1 - x0) * t, y0 + (y1 - y0) * t
            for dy in range(width):
                for dx in range(width):
                    xx, yy = int(math.floor(x)) + dx, int(math.floor(y)) + dy
                    if 0 <= xx < S and 0 <= yy < S:
                        m[yy, xx] = True
        self.regions.append((m, color, False))
        return m

    def dot(self, x, y, color):
        self.dots.append((x, y, color))

    def render(self, outline=True):
        img = np.zeros((S, S, 4), dtype=float)
        union = self.blank()
        for mask, color, shade in self.regions:
            base = hx(color)
            for y in range(S):
                for x in range(S):
                    if not mask[y, x]:
                        continue
                    col = base
                    if shade:
                        up = y == 0 or not mask[y - 1, x]
                        left = x == 0 or not mask[y, x - 1]
                        down = y == S - 1 or not mask[y + 1, x]
                        right = x == S - 1 or not mask[y, x + 1]
                        if (up or left) and not (down or right):
                            col = lit(color, 1.35)
                        elif down or right:
                            col = lit(color, 0.72)
                    img[y, x] = (*col, 255)
            union |= mask
        for (x, y, c) in self.dots:
            if 0 <= x < S and 0 <= y < S:
                img[y, x] = (*hx(c), 255)
                union[y, x] = True
        if outline:
            solid = img[..., 3] > 0
            for y in range(S):
                for x in range(S):
                    if solid[y, x]:
                        continue
                    for dy, dx in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                        yy, xx = y + dy, x + dx
                        if 0 <= yy < S and 0 <= xx < S and solid[yy, xx]:
                            img[y, x] = (*OUT, 255)
                            break
        return Image.fromarray(np.clip(img, 0, 255).astype(np.uint8), 'RGBA')


def shift(img, dx, dy):
    out = Image.new('RGBA', (S, S), (0, 0, 0, 0))
    out.paste(img, (dx, dy))
    return out


GOLD, GOLD2, RED, RED2 = '#f0b92a', '#ffd95a', '#d3322b', '#e8574d'


def pouch(state=0):
    s = Sprite()
    s.ellipse(8, 10.2, 5.2, 4.4, RED)
    s.poly([(4.5, 2.4), (11.5, 2.4), (10.4, 6.2), (5.6, 6.2)], RED2)       # 위로 벌어진 주름 주머니 입구
    s.rect(5, 6, 10, 7, GOLD, False)                                         # 조임 끈
    for (x, y) in ((7, 3), (9, 3), (8, 4)):
        s.dot(x, y, '#7a1a16' if state != 2 else '#4a0f0c')
    s.ellipse(8, 10.4, 2.0, 2.0, GOLD2)                                      # 금화 문양
    s.dot(8, 10, '#7a4a08'); s.dot(7, 10, '#7a4a08')
    s.line(8, 7, 8, 8, GOLD, 1)
    if state == 2:   # 열림: 금화가 튀어나옴
        s.ellipse(4.5, 1.2, 1.4, 1.1, GOLD2); s.ellipse(11.5, 0.9, 1.4, 1.1, GOLD2); s.ellipse(8, 0.6, 1.2, 1.0, GOLD2)
    return s.render()


def envelope(state=0):
    s = Sprite()
    s.rect(3, 4, 12, 14, RED)
    s.rect(3, 12, 12, 13, GOLD, False)
    if state == 0:
        s.poly([(3, 4), (12.99, 4), (8, 9.4)], RED2)
        s.ellipse(8, 9.2, 1.6, 1.6, GOLD2)
        s.dot(8, 9, '#7a4a08')
    else:  # 열림: 뚜껑이 위로 젖혀지고 지폐가 보임
        s.rect(5, 1, 10, 5, '#f3e08a', False)
        s.rect(6, 2, 9, 3, '#c9b25a', False)
        s.poly([(3, 4), (12.99, 4), (8, 0.2)], RED2)
        s.rect(5, 7, 10, 10, '#c43a2f', True)
        s.ellipse(8, 9, 1.6, 1.6, GOLD2)
    return s.render()


def calendar(state=0):
    s = Sprite()
    s.rect(2, 4, 13, 14, '#f4f1e8')
    s.rect(2, 4, 13, 6, RED)
    for x in (4, 7, 10):
        s.rect(x, 1, x + 1, 5, '#cfd3da', False)
    for ry, y in enumerate((8, 10, 12)):
        for rx, x in enumerate((4, 6, 8, 10, 12)):
            s.dot(x - 1 if x == 12 else x, y, '#c43a2f' if rx == 0 else '#8a8a96')
    s.dot(8, 10, GOLD)
    s.dot(9, 10, GOLD)
    if state:   # 윗장이 넘어가는 중
        s.poly([(8, 4), (14.5, 1.5), (14.5, 5.5), (13, 6)], '#ffffff', False)
    return s.render()


def firecracker(state=0):
    s = Sprite()
    s.line(3, 13, 10, 6, RED, 4)
    s.line(5, 12, 11, 6, '#9c1f1a', 1) if False else None
    s.line(4, 11, 5, 10, GOLD, 1)
    s.line(7, 8, 8, 7, GOLD, 1)
    s.ellipse(2.4, 3.2, 2.0, 2.0, '#cfd3da')          # 키링
    s.ellipse(2.4, 3.2, 0.9, 0.9, '#000000') if False else None
    s.dot(2, 3, '#00000000')
    if state == 0 or state == 1:
        s.line(11, 5, 13, 2, '#5a4a3a', 1)
    if state == 1:     # 불붙음
        s.ellipse(13.4, 1.6, 1.5, 1.5, '#ffb02e'); s.dot(13, 1, '#fff3a0'); s.dot(14, 0, '#ffe46a'); s.dot(12, 0, '#ffe46a')
    if state == 2:     # 터짐: 찢어진 종이와 색종이
        s.poly([(9, 7), (13, 3), (14, 6)], RED2, False)
        s.poly([(8, 8), (11, 11), (13, 9)], RED, False)
        for (x, y, c) in ((12, 1, '#4da8e8'), (14, 4, '#5cc96b'), (10, 1, GOLD2), (14, 9, '#b06ae8'), (7, 4, '#ffffff')):
            s.dot(x, y, c)
    return s.render()


def champagne(state=0):
    s = Sprite()
    s.rect(5, 8, 10, 14, '#1f4d2e')
    s.poly([(5, 8), (10.99, 8), (9.2, 5), (6.8, 5)], '#1f4d2e')
    s.rect(7, 3, 8, 7, '#1f4d2e')
    s.rect(7, 2, 8, 7, GOLD, True)                      # 금박 목
    s.rect(5, 10, 10, 13, '#f6efd8', False)             # 라벨
    s.dot(7, 11, RED); s.dot(8, 11, RED)
    s.rect(5, 10, 10, 10, GOLD, False)
    if state == 3:
        # 코르크가 날아가고 거품이 뿜어져 나옴
        s.regions = [r for r in s.regions if not (r[1] == GOLD and r[0][2, 7])]
        s.rect(7, 4, 8, 7, GOLD, True)
        s.ellipse(8, 2.4, 2.4, 2.0, '#ffffff'); s.ellipse(5.4, 1.4, 1.2, 1.2, '#ffffff'); s.ellipse(10.6, 1.2, 1.3, 1.2, '#ffffff')
        s.rect(12, 0, 13, 1, '#d8b078', False)
        s.dot(3, 3, '#cfeaff'); s.dot(13, 4, '#cfeaff'); s.dot(11, 3, '#cfeaff')
    else:
        s.rect(7, 0, 8, 2, '#d8b078', True)             # 코르크
    return s.render()


def badge(shine=False):
    s = Sprite()
    s.poly([(8, 0.6), (10, 3), (13, 2.6), (12.6, 5.8), (15.2, 8), (12.6, 10.2), (13, 13.4), (10, 13), (8, 15.4), (6, 13), (3, 13.4), (3.4, 10.2), (0.8, 8), (3.4, 5.8), (3, 2.6), (6, 3)], '#e0a420')
    s.ellipse(8, 8, 5.0, 5.0, GOLD2)
    s.ellipse(8, 8, 3.6, 3.6, '#f6d36a', False)
    # 2027 -> "27" 도트 글씨
    f2 = ['111', '001', '111', '100', '111']
    f7 = ['111', '001', '010', '010', '010']
    for ry in range(5):
        for rx in range(3):
            if f2[ry][rx] == '1': s.dot(5 + rx, 6 + ry, '#8a3a0c')
            if f7[ry][rx] == '1': s.dot(9 + rx, 6 + ry, '#8a3a0c')
    if shine:
        for (x, y) in ((1, 1), (14, 1), (1, 14), (14, 13)):
            s.dot(x, y, '#ffffff'); s.dot(x - 1, y, '#fff3a0'); s.dot(x + 1, y, '#fff3a0'); s.dot(x, y - 1, '#fff3a0'); s.dot(x, y + 1, '#fff3a0')
    return s.render()


def tteokguk():
    s = Sprite()
    s.ellipse(8, 9.4, 6.8, 5.6, '#f4f1ea')                       # 그릇
    s.rect(1, 8, 14, 9, '#2c5fa8', False)                         # 파란 띠
    s.ellipse(8, 7.4, 6.0, 2.6, '#f3e6c0', False)                 # 국물
    for (x, y) in ((4, 6), (7, 5), (10, 6), (6, 8), (9, 8), (12, 7)):
        s.ellipse(x + 0.5, y + 0.5, 1.2, 0.8, '#ffffff', False)  # 떡
    for (x, y) in ((5, 7), (11, 8)):
        s.dot(x, y, '#ffd74a')
    for (x, y) in ((8, 6), (3, 7), (13, 6)):
        s.dot(x, y, '#4fb04a')
    s.dot(6, 6, '#2d2d2d'); s.dot(10, 7, '#2d2d2d')
    s.dot(6, 2, '#e9eef2'); s.dot(7, 1, '#e9eef2'); s.dot(10, 2, '#e9eef2'); s.dot(11, 0, '#e9eef2')   # 김
    s.dot(6, 3, '#e9eef2'); s.dot(10, 3, '#e9eef2')
    return s.render()


def kite(fly=False):
    s = Sprite()
    s.poly([(8, 1), (14, 7), (8, 13), (2, 7)], '#f4f1e8')
    s.poly([(8, 1), (11, 4), (5, 4)], '#d3322b', False)
    s.poly([(14, 7), (11, 4), (11, 10)], '#2c5fa8', False)
    s.poly([(8, 13), (11, 10), (5, 10)], '#d3322b', False)
    s.poly([(2, 7), (5, 4), (5, 10)], '#2c5fa8', False)
    s.line(8, 1, 8, 13, '#8a6a3a', 1)
    s.line(2, 7, 14, 7, '#8a6a3a', 1)
    s.ellipse(8, 7, 1.7, 1.7, '#d3322b', False)
    for i, c in enumerate(('#2c5fa8', '#ffffff', '#d3322b')):
        s.dot(6 + i * 2, 14, c); s.dot(6 + i * 2, 15, c)
    img = s.render()
    if fly:
        img = shift(img, 0, -1)
        px = img.load()
        for (x, y) in ((1, 1), (14, 2), (13, 13)):
            px[x, y] = (255, 243, 160, 255)
    return img


def yut():
    s = Sprite()
    for i, (x, h) in enumerate(((2, 13), (5, 12), (8, 13), (11, 12))):
        light = i % 2 == 0
        s.rect(x, 14 - h + 1, x + 2, 14, '#e8c98a' if light else '#7a4a2a')
        if light:
            for k in range(2 if i == 0 else 3):
                s.dot(x + 1, 14 - h + 3 + k * 3, '#7a4a2a')
    return s.render()


def card(opened=False):
    s = Sprite()
    if not opened:
        s.rect(3, 2, 12, 14, '#c8201f')
        s.rect(4, 3, 11, 13, '#d3322b', False)
        s.poly([(8, 5), (9, 7.4), (11.4, 7.4), (9.4, 9), (10.2, 11.6), (8, 10), (5.8, 11.6), (6.6, 9), (4.6, 7.4), (7, 7.4)], GOLD2, False)
    else:
        s.rect(1, 2, 8, 14, '#fbf6e8')
        for y in (5, 7, 9, 11):
            s.rect(2, y, 7, y, '#c9b890', False)
        s.rect(8, 2, 14, 14, '#c8201f')
        s.dot(11, 8, GOLD2); s.dot(10, 7, GOLD2); s.dot(12, 7, GOLD2); s.dot(11, 6, GOLD2); s.dot(11, 9, GOLD2)
    return s.render()


def write(assets):
    tdir = os.path.join(assets, 'textures', 'item')
    mdir = os.path.join(assets, 'models', 'item')
    os.makedirs(tdir, exist_ok=True)
    os.makedirs(mdir, exist_ok=True)

    def save(name, img):
        img.save(os.path.join(tdir, name + '.png'))
        with open(os.path.join(mdir, name + '.json'), 'w') as fh:
            json.dump({'parent': 'minecraft:item/generated', 'textures': {'layer0': f'newyearcountdown:item/{name}'}}, fh, indent=1)

    def with_overrides(name, preds):
        p = os.path.join(mdir, name + '.json')
        d = json.load(open(p))
        d['overrides'] = [{'predicate': {'newyearcountdown:fx': v}, 'model': f'newyearcountdown:item/{m}'} for v, m in preds]
        json.dump(d, open(p, 'w'), indent=1)

    p0 = pouch(0)
    save('lucky_pouch', p0)
    save('lucky_pouch_a', shift(p0, -1, 0)); save('lucky_pouch_b', shift(p0, 1, 0)); save('lucky_pouch_open', pouch(2))
    with_overrides('lucky_pouch', [(0.3, 'lucky_pouch_a'), (0.6, 'lucky_pouch_b'), (0.9, 'lucky_pouch_open')])
    save('red_envelope', envelope(0)); save('red_envelope_open', envelope(1))
    with_overrides('red_envelope', [(0.9, 'red_envelope_open')])
    save('mini_calendar', calendar(0)); save('mini_calendar_flip', calendar(1))
    with_overrides('mini_calendar', [(0.9, 'mini_calendar_flip')])
    fc = firecracker(0)
    save('firecracker_keychain', fc)
    save('firecracker_keychain_lit_a', shift(firecracker(1), -1, 0)); save('firecracker_keychain_lit_b', shift(firecracker(1), 1, 0))
    save('firecracker_keychain_burst', firecracker(2))
    with_overrides('firecracker_keychain', [(0.3, 'firecracker_keychain_lit_a'), (0.6, 'firecracker_keychain_lit_b'), (0.9, 'firecracker_keychain_burst')])
    ch = champagne(0)
    save('champagne', ch)
    save('champagne_a', shift(ch, -1, 0)); save('champagne_b', shift(ch, 1, 0)); save('champagne_pop', champagne(3))
    with_overrides('champagne', [(0.3, 'champagne_a'), (0.6, 'champagne_b'), (0.9, 'champagne_pop')])
    save('golden_badge', badge(False)); save('golden_badge_shine', badge(True))
    with_overrides('golden_badge', [(0.9, 'golden_badge_shine')])
    save('tteokguk', tteokguk())
    save('kite', kite(False)); save('kite_fly', kite(True))
    with_overrides('kite', [(0.9, 'kite_fly')])
    save('yut_set', yut())
    save('greeting_card', card(False)); save('greeting_card_open', card(True))
    with_overrides('greeting_card', [(0.9, 'greeting_card_open')])


if __name__ == '__main__':
    sheet = [pouch(0), pouch(2), envelope(0), envelope(1), calendar(0), calendar(1), firecracker(0), firecracker(1), firecracker(2),
             champagne(0), champagne(3), badge(False), badge(True), tteokguk(), kite(False), kite(True), yut(), card(False), card(True)]
    cols = 7
    W = Image.new('RGBA', (cols * 136, ((len(sheet) + cols - 1) // cols) * 136), (110, 110, 120, 255))
    for i, im in enumerate(sheet):
        W.alpha_composite(im.resize((128, 128), Image.NEAREST), ((i % cols) * 136 + 4, (i // cols) * 136 + 4))
    os.makedirs('/tmp/claude-0/s', exist_ok=True)
    W.save('/tmp/claude-0/s/goods2d.png')
