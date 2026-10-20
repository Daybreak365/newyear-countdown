"""윷판 (3x3 블록): 48x48 바닐라풍 픽셀아트 텍스처(16px = 1블록), 칸마다 한 조각씩 쓰는 얇은 판 블록 모델 9개와 blockstate.
점 좌표 공식은 YutBoardBlockEntity.java 와 같아야 한다 (48px 기준 정수 좌표)."""
import json
import os
import numpy as np
from PIL import Image

S = 48
X0, X1, Z0, Z1 = 9, 39, 3, 33        # 말판 정사각형 (픽셀). 그 아래 z >= 35 가 던지는 자리(붉은 양털)


def points():
    """29점: 바깥 20 (시계 방향, 0번 = 오른쪽 아래 출발점) + 대각선 8 + 중앙."""
    corners = [(X1, Z1), (X1, Z0), (X0, Z0), (X0, Z1)]
    pts = []
    for i in range(4):
        a, b = corners[i], corners[(i + 1) % 4]
        for k in range(5):
            t = k / 5.0
            pts.append((round(a[0] + (b[0] - a[0]) * t), round(a[1] + (b[1] - a[1]) * t)))
    cx, cz = (X0 + X1) // 2, (Z0 + Z1) // 2
    for c in corners:
        for f in (1 / 3.0, 2 / 3.0):
            pts.append((round(c[0] + (cx - c[0]) * f), round(c[1] + (cz - c[1]) * f)))
    pts.append((cx, cz))
    return pts


def hexc(c):
    c = c.lstrip('#')
    return tuple(int(c[i:i + 2], 16) for i in (0, 2, 4))


def draw(path):
    rng = np.random.default_rng(11)
    img = np.zeros((S, S, 4), dtype=np.uint8)
    img[..., 3] = 255
    # 참나무 판자 느낌: 4px 높이 판자, 판자마다 톤이 다르고 이음선이 어둡다
    tones = [hexc(c) for c in ('#b8945f', '#a98550', '#bf9b66', '#9c7b49', '#b28d58')]
    for y in range(S):
        row = y // 4
        base = np.array(tones[row % len(tones)], dtype=float)
        for x in range(S):
            v = base * (0.94 + 0.1 * rng.random())
            if y % 4 == 3:
                v = v * 0.78
            # 판자 끝 이음(세로 선)
            if (x + row * 13) % 24 == 0:
                v = v * 0.8
            img[y, x, :3] = np.clip(v, 0, 255)

    def px(x, y, c):
        if 0 <= x < S and 0 <= y < S:
            img[y, x, :3] = hexc(c) if isinstance(c, str) else c

    def rect(x0, y0, x1, y1, c):
        for yy in range(y0, y1 + 1):
            for xx in range(x0, x1 + 1):
                px(xx, yy, c)

    def line(x0, y0, x1, y1, c):
        n = max(abs(x1 - x0), abs(y1 - y0))
        for i in range(n + 1):
            t = i / n if n else 0
            px(round(x0 + (x1 - x0) * t), round(y0 + (y1 - y0) * t), c)

    # 테두리: 짙은 참나무 2px + 안쪽 밝은 선
    for k, c in enumerate(('#3b2a17', '#4e381d')):
        for i in range(S):
            for (a, b) in ((i, k), (i, S - 1 - k), (k, i), (S - 1 - k, i)):
                px(a, b, c)
    for i in range(2, S - 2):
        for (a, b) in ((i, 2), (i, S - 3), (2, i), (S - 3, i)):
            px(a, b, '#7a5a30')
    ink = '#2e1f10'
    for (xa, ya, xb, yb) in ((X0, Z0, X1, Z0), (X1, Z0, X1, Z1), (X1, Z1, X0, Z1), (X0, Z1, X0, Z0), (X0, Z0, X1, Z1), (X1, Z0, X0, Z1)):
        line(xa, ya, xb, yb, ink)
    pts = points()
    for i, (x, z) in enumerate(pts):
        big = i in (0, 5, 10, 15, 28)
        r = 3 if big else 2
        fill = '#f0c030' if big else '#f3e6c0'
        if i == 0:
            fill = '#d83a30'
        rect(x - r, z - r, x + r, z + r, ink)
        rect(x - r + 1, z - r + 1, x + r - 1, z + r - 1, fill)
        if big:
            px(x, z, ink)
    # 출발 화살표(▲)
    for k in range(3):
        rect(X1 + 3 - k, Z1 - 6 + k * 1, X1 + 3 + k, Z1 - 6 + k * 1, '#8a2a20')
    # 던지는 자리: 붉은 양털 + 금테
    for y in range(35, 46):
        for x in range(6, 42):
            v = np.array(hexc('#a32a24'), dtype=float) * (0.9 + 0.2 * rng.random())
            img[y, x, :3] = np.clip(v, 0, 255)
    for x in range(6, 42):
        px(x, 35, '#e8b830'); px(x, 45, '#e8b830')
    for y in range(35, 46):
        px(6, y, '#e8b830'); px(41, y, '#e8b830')
    # 양털 위 윷가락 자리 표시 4줄
    for k in range(4):
        zz = 37 + k * 2
        for x in range(14, 34):
            px(x, zz, '#c8503f')
    # 3x5 도트 글씨 "2027" (금색)
    font = {'2': ['111', '001', '111', '100', '111'], '0': ['111', '101', '101', '101', '111'], '7': ['111', '001', '010', '010', '010']}
    def text(s, x, y, c):
        for ci, ch in enumerate(s):
            for ry, row in enumerate(font[ch]):
                for rx, b in enumerate(row):
                    if b == '1':
                        px(x + ci * 4 + rx, y + ry, c)
    text('2027', 9, 38, '#f6d66a')
    img_o = Image.fromarray(img, 'RGBA')
    img_o.save(path)
    return img_o


def write(assets):
    tdir = os.path.join(assets, 'textures', 'block')
    os.makedirs(tdir, exist_ok=True)
    draw(os.path.join(tdir, 'yut_board.png'))
    mdir = os.path.join(assets, 'models', 'block')
    os.makedirs(mdir, exist_ok=True)
    side = lambda: {'uv': [0, 14, 16, 16], 'texture': '#side'}
    for part in range(9):
        px_, pz = part % 3, part // 3
        u0, v0 = px_ * 16 / 3.0, pz * 16 / 3.0
        model = {
            'textures': {'top': 'newyearcountdown:block/yut_board', 'side': 'minecraft:block/dark_oak_planks', 'particle': 'minecraft:block/dark_oak_planks'},
            'elements': [{
                'from': [0, 0, 0], 'to': [16, 2, 16],
                'faces': {
                    'up': {'uv': [round(u0, 4), round(v0, 4), round(u0 + 16 / 3.0, 4), round(v0 + 16 / 3.0, 4)], 'texture': '#top'},
                    'down': {'uv': [0, 0, 16, 16], 'texture': '#side'},
                    'north': side(), 'south': side(), 'east': side(), 'west': side(),
                },
            }],
        }
        with open(os.path.join(mdir, f'yut_board_{part}.json'), 'w') as fh:
            json.dump(model, fh, indent=1)
    variants = {}
    for facing, y in (('south', 0), ('west', 90), ('north', 180), ('east', 270)):
        for part in range(9):
            v = {'model': f'newyearcountdown:block/yut_board_{part}'}
            if y:
                v['y'] = y
            variants[f'facing={facing},part={part}'] = v
    sdir = os.path.join(assets, 'blockstates')
    os.makedirs(sdir, exist_ok=True)
    with open(os.path.join(sdir, 'yut_board.json'), 'w') as fh:
        json.dump({'variants': variants}, fh, indent=1)


if __name__ == '__main__':
    im = draw('/tmp/claude-0/s/yut_board.png')
    im.resize((S * 8, S * 8), Image.NEAREST).save('/tmp/claude-0/s/yut_board_big.png')
