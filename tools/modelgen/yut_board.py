"""윷판 블록: 16x16 윗면 텍스처(29점)와 얇은 판 블록 모델. 점 좌표 공식은 YutBoardBlockEntity.java 와 같아야 한다."""
import json
import math
import os
import numpy as np
from PIL import Image, ImageDraw, ImageFont

FONT = '/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf'
X0, X1, Z0, Z1 = 3.0, 13.0, 1.0, 11.0        # 말판 정사각형(단위). 그 아래(z>11.6)가 던지는 자리


def points():
    """29점: 바깥 20 (시계 방향, 0번 = 오른쪽 아래 출발/도착점) + 대각선 8 + 중앙."""
    corners = [(X1, Z1), (X1, Z0), (X0, Z0), (X0, Z1)]
    pts = []
    for i in range(4):
        a, b = corners[i], corners[(i + 1) % 4]
        for k in range(5):
            t = k / 5.0
            pts.append((a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t))
    cx, cz = (X0 + X1) / 2, (Z0 + Z1) / 2
    for c in corners:
        for f in (1 / 3.0, 2 / 3.0):
            pts.append((c[0] + (cx - c[0]) * f, c[1] + (cz - c[1]) * f))
    pts.append((cx, cz))
    return pts


def draw(path, S=256):
    u = S / 16.0
    img = Image.new('RGBA', (S, S), (0, 0, 0, 255))
    rng = np.random.default_rng(5)
    base = np.array([226, 189, 133], dtype=float)
    arr = np.zeros((S, S, 3))
    for y in range(S):
        row = 0.93 + 0.07 * np.sin(y / 9.0 + rng.random() * 0.5) + (rng.random() - 0.5) * 0.03
        arr[y, :, :] = base * row
    arr += rng.normal(0, 2.0, arr.shape)
    img = Image.fromarray(np.clip(arr, 0, 255).astype(np.uint8), 'RGB').convert('RGBA')
    d = ImageDraw.Draw(img)
    # 테두리
    d.rectangle([0, 0, S - 1, S - 1], outline=(90, 58, 36, 255), width=int(0.5 * u))
    d.rectangle([int(0.5 * u), int(0.5 * u), S - 1 - int(0.5 * u), S - 1 - int(0.5 * u)], outline=(232, 184, 48, 255), width=int(0.12 * u))
    ink = (59, 42, 28, 255)
    w = int(0.26 * u)
    sq = [(X0, Z0), (X1, Z0), (X1, Z1), (X0, Z1), (X0, Z0)]
    d.line([(x * u, z * u) for x, z in sq], fill=ink, width=w, joint='curve')
    d.line([(X0 * u, Z0 * u), (X1 * u, Z1 * u)], fill=ink, width=w)
    d.line([(X1 * u, Z0 * u), (X0 * u, Z1 * u)], fill=ink, width=w)
    pts = points()
    for i, (x, z) in enumerate(pts):
        big = i in (0, 5, 10, 15) or i == 28
        r = (0.78 if big else 0.46) * u
        fill = (232, 184, 48, 255) if big else (248, 236, 208, 255)
        if i == 0:
            fill = (214, 70, 56, 255)
        d.ellipse([x * u - r, z * u - r, x * u + r, z * u + r], fill=fill, outline=ink, width=int(0.14 * u))
        if big:
            r2 = r * 0.5
            d.ellipse([x * u - r2, z * u - r2, x * u + r2, z * u + r2], outline=ink, width=int(0.08 * u))
    # 출발점 화살표(시계 방향 진행 안내)
    ax, az = X1 + 1.5, Z1 - 1.6
    d.polygon([((ax) * u, (az - 0.8) * u), ((ax + 0.55) * u, (az + 0.2) * u), ((ax - 0.55) * u, (az + 0.2) * u)], fill=(150, 40, 30, 255))
    # 던지는 자리: 붉은 천 + 금테 + 흐린 윷가락 윤곽
    d.rounded_rectangle([2.0 * u, 12.2 * u, 14.0 * u, 15.4 * u], radius=int(0.5 * u), fill=(150, 36, 30, 255), outline=(232, 184, 48, 255), width=int(0.14 * u))
    for k in range(4):
        z = 12.75 + k * 0.7
        d.rounded_rectangle([5.0 * u, z * u, 11.0 * u, (z + 0.42) * u], radius=int(0.2 * u), outline=(232, 190, 90, 150), width=int(0.06 * u))
    f = ImageFont.truetype(FONT, int(0.7 * u))
    d.text((8 * u, 12.0 * u), '', font=f)
    f2 = ImageFont.truetype(FONT, int(0.8 * u))
    d.text((3.6 * u, 14.0 * u), '2027', font=f2, fill=(246, 214, 102, 255), anchor='mm')
    d.text((12.4 * u, 14.0 * u), 'YUT', font=f2, fill=(246, 214, 102, 255), anchor='mm')
    img.save(path)


def write(assets):
    tdir = os.path.join(assets, 'textures', 'block')
    os.makedirs(tdir, exist_ok=True)
    draw(os.path.join(tdir, 'yut_board.png'))
    mdir = os.path.join(assets, 'models', 'block')
    os.makedirs(mdir, exist_ok=True)
    side = lambda v1, v2: {'uv': [0, v1, 16, v2], 'texture': '#side'}
    model = {
        'textures': {'top': 'newyearcountdown:block/yut_board', 'side': 'minecraft:block/dark_oak_planks', 'particle': 'minecraft:block/dark_oak_planks'},
        'elements': [{
            'from': [0, 0, 0], 'to': [16, 2, 16],
            'faces': {
                'up': {'uv': [0, 0, 16, 16], 'texture': '#top'},
                'down': {'uv': [0, 0, 16, 16], 'texture': '#side'},
                'north': side(14, 16), 'south': side(14, 16), 'east': side(14, 16), 'west': side(14, 16),
            },
        }],
    }
    with open(os.path.join(mdir, 'yut_board.json'), 'w') as fh:
        json.dump(model, fh, indent=1)


if __name__ == '__main__':
    draw('/tmp/claude-0/s/yut_board.png')
