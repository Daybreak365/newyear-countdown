"""캡슐토이 아이템: 바닐라 느낌의 16x16 픽셀아트(검은 윤곽선 + 3톤 음영 + 반사점). 6색 x (기본 / 좌우 흔들 / 열림)."""
import json
import math
import os
import numpy as np
from PIL import Image

RGB = ['#E8453C', '#F5A623', '#F8D347', '#5CC96B', '#4DA8E8', '#B06AE8']


def hx(c):
    c = c.lstrip('#')
    return np.array([int(c[i:i + 2], 16) for i in (0, 2, 4)], dtype=float)


def mix(a, b, t):
    return a * (1 - t) + b * t


def capsule(color, tilt=0.0, opened=False):
    S = 16
    im = np.zeros((S, S, 4), dtype=np.uint8)
    c = hx(color)
    pal_top = (mix(c, hx('#ffffff'), 0.5), c, mix(c, hx('#000000'), 0.28))
    pal_bot = (hx('#ffffff'), hx('#e6e8f0'), hx('#aeb3c4'))
    line = mix(c, hx('#1c1c2c'), 0.72)
    line_w = hx('#4a4e62')
    ang = math.radians(tilt)
    ca, sa = math.cos(ang), math.sin(ang)

    def put(x, y, col):
        if 0 <= x < S and 0 <= y < S:
            im[y, x] = (*[int(round(v)) for v in col], 255)

    def draw_half(top, cx, cy, rot, r_out=6.6, r_in=5.6):
        cs, sn = math.cos(rot), math.sin(rot)
        for y in range(S):
            for x in range(S):
                dx, dy = x + 0.5 - cx, y + 0.5 - cy
                # 역회전해서 캡슐 좌표계로
                u, v = dx * cs + dy * sn, -dx * sn + dy * cs
                d = math.hypot(u, v)
                inside_half = (v <= 0.0) if top else (v >= 0.0)
                if opened:     # 열린 상태: 각 반쪽은 이음매 위(아래)만, 안쪽이 보이는 컵 모양
                    pass
                if d > r_out or not inside_half:
                    continue
                if d > r_in or abs(v) < 0.55 and d > r_in - 0.6:
                    put(x, y, line if top else line_w)
                    continue
                pal = pal_top if top else pal_bot
                # 음영: 왼쪽 위가 밝고 오른쪽 아래가 어둡다
                lit = (-u * 0.6 - v * 0.8) / r_in            # -1(어두움) ~ 1(밝음), v 는 아래가 +
                if top:
                    lit = (-u * 0.55 + (-v) * 0.9) / r_in
                col = pal[0] if lit > 0.45 else (pal[1] if lit > -0.35 else pal[2])
                # 반사점
                if top and 1.2 < -u + 0.0 < 3.6 and 1.2 < -v < 3.4 and (-u - 1.2) + (-v - 1.2) < 2.6:
                    col = pal_top[0]
                if top and abs(u + 2.6) < 0.7 and abs(v + 3.1) < 0.7:
                    col = hx('#ffffff')
                put(x, y, col)
        return

    if not opened:
        draw_half(True, 8, 8, ang)
        draw_half(False, 8, 8, ang)
        # 이음매 띠(윤곽선 색으로 한 줄)
        for x in range(S):
            for y in range(S):
                dx, dy = x + 0.5 - 8, y + 0.5 - 8
                u, v = dx * ca + dy * sa, -dx * sa + dy * ca
                if abs(v) < 0.5 and math.hypot(u, v) < 6.2:
                    put(x, y, mix(line_w, hx('#c8ccd8'), 0.35))
    else:
        draw_half(False, 8.0, 10.4, 0.0, 5.4, 4.5)
        draw_half(True, 6.2, 5.8, math.radians(-26), 5.4, 4.5)
        gold = hx('#ffe36a')
        for (x, y) in ((11, 4), (10, 5), (11, 5), (12, 5), (11, 6), (14, 2), (14, 8), (2, 9)):
            put(x, y, gold)
        for (x, y) in ((11, 3), (9, 5), (13, 5), (11, 7)):
            put(x, y, hx('#fff7c0'))
    return Image.fromarray(im, 'RGBA')


def write(assets):
    tdir = os.path.join(assets, 'textures', 'item')
    mdir = os.path.join(assets, 'models', 'item')
    os.makedirs(tdir, exist_ok=True)
    os.makedirs(mdir, exist_ok=True)

    def model(name):
        with open(os.path.join(mdir, name + '.json'), 'w') as fh:
            json.dump({'parent': 'minecraft:item/generated', 'textures': {'layer0': f'newyearcountdown:item/{name}'}}, fh, indent=1)

    for i, col in enumerate(RGB):
        for suffix, img in (('', capsule(col)), ('_a', capsule(col, 16)), ('_b', capsule(col, -16)), ('_open', capsule(col, 0, True))):
            name = f'capsule_c{i}{suffix}'
            img.save(os.path.join(tdir, name + '.png'))
            model(name)
    cap = {'parent': 'newyearcountdown:item/capsule_c0', 'overrides': []}
    for code in range(24):
        c, s = divmod(code, 4)
        m = [f'capsule_c{c}', f'capsule_c{c}_a', f'capsule_c{c}_b', f'capsule_c{c}_open'][s]
        cap['overrides'].append({'predicate': {'newyearcountdown:capsule': round(code / 24, 5)}, 'model': f'newyearcountdown:item/{m}'})
    with open(os.path.join(mdir, 'capsule.json'), 'w') as fh:
        json.dump(cap, fh, indent=1)


if __name__ == '__main__':
    out = '/tmp/claude-0/s/caps'
    os.makedirs(out, exist_ok=True)
    W = Image.new('RGBA', (16 * 8 * 4, 16 * 8 * 6), (110, 110, 120, 255))
    for i, col in enumerate(RGB):
        for j, im in enumerate((capsule(col), capsule(col, 16), capsule(col, -16), capsule(col, 0, True))):
            W.alpha_composite(im.resize((128, 128), Image.NEAREST), (j * 128, i * 128))
    W.save(out + '/sheet.png')
