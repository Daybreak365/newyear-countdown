"""박스 조합 도형 도우미: 다각 원판, 선반(lathe) 회전체, 구."""
import math
import numpy as np
from core import Box

import math


def prism(cx, cz, y1, y2, a, mat, sides=8, top=True, bottom=False, tint=False, name=None, side_faces=True):
    """y 축 정다각 기둥 (apothem = a). sides: 4 / 8 / 16.
    변마다 '길이 2a x 폭 변길이' 직사각 박스를 중심을 지나게 놓고 양 끝면만 바깥면으로 쓴다
    (합집합이 정다각형이 된다). 윗면은 겹치는 박스끼리 y 를 미세하게 달리해 z-fighting 을 막는다."""
    out = []
    if sides == 4:
        faces = (['north', 'east', 'south', 'west'] if side_faces else []) + (['up'] if top else []) + (['down'] if bottom else [])
        return [Box((cx - a, y1, cz - a), (cx + a, y2, cz + a), mat, faces=faces, tint=tint, name=name)]
    R = a / math.cos(math.pi / sides)
    b = R * math.sin(math.pi / sides)          # 변 길이의 절반
    for k in range(sides // 2):
        phi = k * 360.0 / sides
        if phi > 45:
            ang, swap = phi - 90, True
        else:
            ang, swap = phi, False
        if ang > 45:
            ang, swap = ang - 90, not swap
        hx, hz = (b, a) if swap else (a, b)
        faces = []
        if side_faces:
            faces += ['north', 'south'] if swap else ['east', 'west']
        eps = 0.004 * k
        if top:
            faces.append('up')
        if bottom:
            faces.append('down')
        rot = ('y', ang, (cx, 0, cz)) if ang else None
        out.append(Box((cx - hx, y1 + eps, cz - hz), (cx + hx, y2 - eps, cz + hz), mat, rot=rot, faces=faces, tint=tint, name=name))
    return out


def _dim(m, k):
    if isinstance(m, dict) or not hasattr(m, 'with_'):
        return m
    return m.with_(ambient=m.ambient * k, diffuse=m.diffuse * k, spec=m.spec * 0.4)


def lathe(cx, cz, layers, mat_fn, sides=8, tint=False, name=None, ledge_dim=0.86):
    """layers: [(y1, y2, a), ...] 아래→위. mat_fn(i, y1, y2, a) -> Mat. 보이는 턱(ledge) 면만 그린다.
    중간 층의 턱(윗면)은 빛 방향 때문에 띠처럼 튀므로 ledge_dim 만큼 어둡게 굽는다."""
    out = []
    n = len(layers)
    for i, (y1, y2, a) in enumerate(layers):
        up_ok = i == n - 1 or a > layers[i + 1][2] + 0.04
        dn_ok = i == 0 or a > layers[i - 1][2] + 0.04
        mat = mat_fn(i, y1, y2, a)
        if i < n - 1 and up_ok and not isinstance(mat, dict):
            mat = {'up': _dim(mat, ledge_dim), '*': mat}
        elif i < n - 1 and up_ok and isinstance(mat, dict) and 'up' not in mat:
            mat = dict(mat); mat['up'] = _dim(mat['*'], ledge_dim)
        out += prism(cx, cz, y1, y2, a, mat, sides=sides, top=up_ok, bottom=dn_ok, tint=tint, name=name)
    return out


def sphere_layers(R, step=0.9, y0=0.0, squash=1.0):
    """반지름 R 구를 슬라이스한 (y1,y2,a) 목록. y0=중심 높이."""
    layers = []
    n = max(2, int(round(2 * R / step)))
    h = 2 * R / n
    for i in range(n):
        ya, yb = -R + i * h, -R + (i + 1) * h
        ym = max(abs(ya), abs(yb)) if False else (abs(ya + yb) / 2)
        # 슬라이스 안쪽으로 들어가는 최대 반폭: 중간 높이 기준 약간 바깥쪽
        yy = min(abs(ya), abs(yb))
        a = math.sqrt(max(R * R - ((yy + ym) / 2) ** 2, 0.04))
        layers.append((y0 + ya * squash, y0 + yb * squash, a))
    return layers


def sphere(cx, cy, cz, R, mat_fn, step=0.9, sides=16, tint=False, squash=1.0, name=None):
    layers = sphere_layers(R, step, cy, squash)
    return lathe(cx, cz, layers, mat_fn, sides=sides, tint=tint, name=name)


def frame(x1, y1, z1, x2, y2, z2, t, mat, axis='z', name=None):
    """axis 방향으로 뚫린 사각 테두리(액자). t=테 두께."""
    if axis == 'z':
        return [Box((x1, y1, z1), (x2, y1 + t, z2), mat, name=name), Box((x1, y2 - t, z1), (x2, y2, z2), mat, name=name),
                Box((x1, y1 + t, z1), (x1 + t, y2 - t, z2), mat, name=name), Box((x2 - t, y1 + t, z1), (x2, y2 - t, z2), mat, name=name)]
    raise ValueError(axis)


def prism_z(cx, cy, z1, z2, a, mat, sides=16, front=True, back=False, name=None):
    """z 축(앞뒤)으로 두께가 있는 정다각 판 (배지/동전용). 앞면은 +z. 변마다 직사각 박스를 쓴다."""
    out = []
    if sides == 4:
        faces = ['north', 'east', 'south', 'west', 'up', 'down']
        faces = [f for f in faces if (f != 'south' or front) and (f != 'north' or back)]
        return [Box((cx - a, cy - a, z1), (cx + a, cy + a, z2), mat, faces=faces, name=name)]
    R = a / math.cos(math.pi / sides)
    b = R * math.sin(math.pi / sides)
    for k in range(sides // 2):
        phi = k * 360.0 / sides
        if phi > 45:
            ang, swap = phi - 90, True
        else:
            ang, swap = phi, False
        if ang > 45:
            ang, swap = ang - 90, not swap
        hx, hy = (b, a) if swap else (a, b)
        faces = ['up', 'down'] if swap else ['east', 'west']
        e = 0.004 * k
        if front:
            faces.append('south')
        if back:
            faces.append('north')
        rot = ('z', ang, (cx, cy, 0)) if ang else None
        out.append(Box((cx - hx, cy - hy, z1 + (e if back else 0)), (cx + hx, cy + hy, z2 - (e if front else 0)), mat, rot=rot, faces=faces, name=name))
    return out


def profile_layers(points, step=0.45, y_start=None):
    """제어점 [(y, 반경), ...] 을 선형 보간해 얇은 층(y1,y2,a) 목록으로 만든다(매끈한 회전체)."""
    ys = [p[0] for p in points]
    rs = [p[1] for p in points]
    out = []
    y = ys[0] if y_start is None else y_start
    top = ys[-1]
    while y < top - 1e-6:
        y2 = min(y + step, top)
        ym = (y + y2) / 2
        a = float(np.interp(ym, ys, rs))
        out.append((y, y2, a))
        y = y2
    return out


def ring(cx, cz, y1, y2, a_out, t, mat, sides=16, floor=None):
    """속이 빈 다각 링(그릇 벽). 변마다 얇은 판 하나를 중심에서 a_out 만큼 떨어뜨려 놓는다."""
    out = []
    R = a_out / math.cos(math.pi / sides)
    b = R * math.sin(math.pi / sides) + 0.02
    d = a_out - t / 2
    for k in range(sides):
        phi = k * 360.0 / sides
        phi0 = round(phi / 90.0) * 90 % 360
        r = phi - round(phi / 90.0) * 90
        vx, vz = {0: (1, 0), 90: (0, 1), 180: (-1, 0), 270: (0, -1)}[int(phi0)]
        px, pz = cx + vx * d, cz + vz * d
        if vx != 0:
            lo, hi = (px - t / 2, pz - b), (px + t / 2, pz + b)
        else:
            lo, hi = (px - b, pz - t / 2), (px + b, pz + t / 2)
        rot = ('y', r, (cx, 0, cz)) if r else None
        out.append(Box((lo[0], y1, lo[1]), (hi[0], y2, hi[1]), mat, rot=rot))
    return out
