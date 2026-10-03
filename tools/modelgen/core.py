"""
큐보이드 3D 아이템 모델 + 텍스처 아틀라스 생성기.

- 모델은 박스(Box)들의 목록이다. 각 박스의 면마다 아틀라스에 전용 영역을 배정하고,
  면 위 모든 픽셀의 3D 위치/법선을 알고 있으므로 구·원통 셰이딩, 광택 같은 조명을 텍스처에 구워 넣을 수 있다.
- 출력: models/item/<name>.json (바닐라 element 모델) + textures/item/model/<name>.png
좌표는 모델 단위(0~16 = 1블록), y 가 위, 정면은 남쪽(+Z).
"""
import json
import math
import zlib
import os
import numpy as np
from PIL import Image, ImageDraw

FACES = ['north', 'east', 'south', 'west', 'up', 'down']
NORMALS = {'north': (0, 0, -1), 'east': (1, 0, 0), 'south': (0, 0, 1), 'west': (-1, 0, 0), 'up': (0, 1, 0), 'down': (0, -1, 0)}
# 바닐라 CubeFace 꼭짓점 순서 (x,y,z 각각 0=min 1=max). 꼭짓점 i 의 UV: (u1,v1),(u1,v2),(u2,v2),(u2,v1)
CORNERS = {
    'down': [(0, 0, 1), (0, 0, 0), (1, 0, 0), (1, 0, 1)],
    'up': [(0, 1, 0), (0, 1, 1), (1, 1, 1), (1, 1, 0)],
    'north': [(1, 1, 0), (1, 0, 0), (0, 0, 0), (0, 1, 0)],
    'south': [(0, 1, 1), (0, 0, 1), (1, 0, 1), (1, 1, 1)],
    'west': [(0, 1, 0), (0, 0, 0), (0, 0, 1), (0, 1, 1)],
    'east': [(1, 1, 1), (1, 0, 1), (1, 0, 0), (1, 1, 0)],
}
UVC = [(0, 0), (0, 1), (1, 1), (1, 0)]
AXIS = {'x': 0, 'y': 1, 'z': 2}

LIGHT = np.array([-0.45, 0.75, 0.5]); LIGHT /= np.linalg.norm(LIGHT)
VIEW = np.array([-0.3, 0.45, 0.84]); VIEW /= np.linalg.norm(VIEW)
HALF = LIGHT + VIEW; HALF /= np.linalg.norm(HALF)


def rgb(c):
    if isinstance(c, str):
        c = c.lstrip('#')
        if len(c) == 3:
            c = ''.join(ch * 2 for ch in c)
        return np.array([int(c[i:i + 2], 16) for i in (0, 2, 4)], dtype=np.float64) / 255.0
    return np.array(c, dtype=np.float64)


def rot_matrix(axis, deg):
    a = math.radians(deg)
    c, s = math.cos(a), math.sin(a)
    if axis == 'x':
        return np.array([[1, 0, 0], [0, c, -s], [0, s, c]])
    if axis == 'y':
        return np.array([[c, 0, s], [0, 1, 0], [-s, 0, c]])
    return np.array([[c, -s, 0], [s, c, 0], [0, 0, 1]])


class Ctx:
    """면 하나를 칠할 때 넘겨주는 정보."""
    def __init__(self, w, h, face, P, N, k, seed):
        self.w, self.h, self.face, self.P, self.N, self.k, self.seed = w, h, face, P, N, k, seed
        self.rng = np.random.default_rng(seed)
        self.wu, self.hu = w / k, h / k   # 면 크기(모델 단위)


class Mat:
    """재질. base(ctx)->(h,w,3) 색, 이어서 셰이더/모서리 입체감/데칼을 얹는다."""
    def __init__(self, color, noise=0.03, bevel=0.10, base=None, shape=None, spec=0.0, shin=28.0,
                 ambient=0.66, diffuse=0.46, alpha=1.0, decals=None, glow=0.0, emit=0.0, overlays=None):
        self.color = rgb(color)
        self.noise, self.bevel, self.base, self.shape = noise, bevel, base, shape
        self.spec, self.shin, self.ambient, self.diffuse = spec, shin, ambient, diffuse
        self.alpha, self.decals, self.glow, self.emit = alpha, list(decals or []), glow, emit
        self.overlays = list(overlays or [])

    def with_(self, **kw):
        m = Mat.__new__(Mat)
        m.__dict__.update(self.__dict__)
        m.decals = list(self.decals)
        m.overlays = list(self.overlays)
        for key, v in kw.items():
            if key == 'color':
                v = rgb(v)
            setattr(m, key, v)
        return m

    def overlay(self, fn):
        return self.with_(overlays=self.overlays + [fn])

    def decal(self, fn):
        return self.with_(decals=self.decals + [fn])

    def render(self, ctx):
        h, w = ctx.h, ctx.w
        col = self.base(ctx) if self.base else np.broadcast_to(self.color, (h, w, 3)).copy()
        for ov in self.overlays:
            col = ov(ctx, col)
        if self.noise:
            n = ctx.rng.normal(0, self.noise, (h, w, 1))
            # 약간 뭉친 노이즈로 천/종이 같은 결을 낸다
            col = col * (1 + n)
        if self.shape:
            n = normals_for(self.shape, ctx)
            nd = np.clip((n * LIGHT).sum(-1, keepdims=True), 0, 1)
            sp = np.clip((n * HALF).sum(-1, keepdims=True), 0, 1) ** self.shin * self.spec
            rim = np.clip(1 - (n * VIEW).sum(-1, keepdims=True), 0, 1) ** 3 * 0.10
            col = col * (self.ambient + self.diffuse * nd - rim) + sp
        if self.bevel:
            col = col * bevel_map(ctx, self.bevel)
        if self.emit:
            col = col * (1 + self.emit)
        a = np.full((h, w, 1), self.alpha)
        out = np.concatenate([np.clip(col, 0, 1), a], axis=-1)
        if self.decals:
            ss = 4
            img = Image.fromarray((out * 255 + 0.5).astype(np.uint8), 'RGBA')
            for fn in self.decals:
                big = Image.new('RGBA', (w * ss, h * ss), (0, 0, 0, 0))
                d = ImageDraw.Draw(big)
                fn(d, ctx, ctx.k * ss)
                small = big.resize((w, h), Image.LANCZOS)
                img = Image.alpha_composite(img, small)
            out = np.asarray(img, dtype=np.float64) / 255.0
        return out


def normals_for(shape, ctx):
    P = ctx.P
    kind = shape[0]
    if kind == 'sphere':
        c = np.array(shape[1]); sx = shape[2] if len(shape) > 2 else (1, 1, 1)
        v = (P - c) / np.array(sx)
    elif kind == 'cylY':     # y 축 원통
        cx, cz = shape[1], shape[2]
        v = np.stack([P[..., 0] - cx, np.zeros(P.shape[:2]), P[..., 2] - cz], -1)
    elif kind == 'cylX':
        cy, cz = shape[1], shape[2]
        v = np.stack([np.zeros(P.shape[:2]), P[..., 1] - cy, P[..., 2] - cz], -1)
    elif kind == 'cylZ':
        cx, cy = shape[1], shape[2]
        v = np.stack([P[..., 0] - cx, P[..., 1] - cy, np.zeros(P.shape[:2])], -1)
    elif kind == 'bell':     # 종 모양: y 축 원통 + 약간의 위쪽 기울기
        cx, cz = shape[1], shape[2]
        v = np.stack([P[..., 0] - cx, np.full(P.shape[:2], shape[3] if len(shape) > 3 else 0.3), P[..., 2] - cz], -1)
    else:
        v = np.broadcast_to(np.array(ctx.N, dtype=np.float64), P.shape).copy()
    ln = np.linalg.norm(v, axis=-1, keepdims=True)
    ln[ln < 1e-6] = 1
    v = v / ln
    # 면 법선과 반대편을 보는 법선은 뒤집어 준다 (뒷면은 어차피 안 보임)
    flip = (v * np.array(ctx.N)).sum(-1, keepdims=True) < 0
    return np.where(flip, np.array(ctx.N) * 0.6 + v * 0.4, v)


def bevel_map(ctx, amt):
    h, w = ctx.h, ctx.w
    b = max(1, int(round(ctx.k * 0.07)))
    y, x = np.mgrid[0:h, 0:w]
    m = np.ones((h, w))
    top = np.clip((b - y) / b, 0, 1); left = np.clip((b - x) / b, 0, 1)
    bot = np.clip((y - (h - 1 - b)) / b, 0, 1); right = np.clip((x - (w - 1 - b)) / b, 0, 1)
    m += amt * (top + left) * 0.9 - amt * (bot + right) * 1.1
    return m[..., None]


class Box:
    def __init__(self, a, b, mat, rot=None, faces=None, tint=False, name=None):
        """a,b: (x,y,z) 두 모서리. mat: Mat 또는 {면이름|'*': Mat}. rot: (axis, angle, origin(xyz))."""
        self.a = tuple(min(p, q) for p, q in zip(a, b))
        self.b = tuple(max(p, q) for p, q in zip(a, b))
        self.mat, self.rot, self.tint, self.name = mat, rot, tint, name
        self.faces = list(faces) if faces else list(FACES)

    def mat_for(self, f):
        if isinstance(self.mat, dict):
            return self.mat.get(f, self.mat.get('*'))
        return self.mat

    def face_units(self, f):
        dx, dy, dz = (self.b[i] - self.a[i] for i in range(3))
        if f in ('north', 'south'):
            return dx, dy
        if f in ('east', 'west'):
            return dz, dy
        return dx, dz

    def corner(self, sel):
        return np.array([self.b[i] if sel[i] else self.a[i] for i in range(3)], dtype=np.float64)

    def xform(self, p):
        if not self.rot:
            return p
        axis, ang, org = self.rot
        o = np.array(org, dtype=np.float64)
        return (rot_matrix(axis, ang) @ (p - o).T).T + o if p.ndim == 2 else rot_matrix(axis, ang) @ (p - o) + o

    def xform_n(self, n):
        if not self.rot:
            return np.array(n, dtype=np.float64)
        return rot_matrix(self.rot[0], self.rot[1]) @ np.array(n, dtype=np.float64)


class Model:
    def __init__(self, name, boxes, density=8, gui=(30, 225, 0), gui_scale=0.625, gui_trans=(0, 0, 0),
                 hand=None, ground_scale=0.3, fixed_rot=(0, 0, 0), fixed_scale=0.5, fit=True, kind='block', roll=0.0):
        self.name, self.boxes, self.density = name, boxes, density
        self.gui, self.gui_scale, self.gui_trans = gui, gui_scale, gui_trans
        self.hand = hand or {}
        self.ground_scale, self.fixed_rot, self.fixed_scale = ground_scale, fixed_rot, fixed_scale
        self.fit, self.kind, self.roll = fit, kind, roll
        if fit:
            self.autofit()

    def like(self, base):
        """base 모델과 같은 GUI 배치를 쓴다 (상태 변형들이 크기가 튀지 않게)."""
        self.gui, self.gui_scale, self.gui_trans, self.fit = base.gui, base.gui_scale, base.gui_trans, False
        return self

    def display(self, roll=0.0):
        """roll: z 축 흔들림(도). 아이템 상태 애니메이션용으로 모든 시점에 더한다."""
        gx, gy, gz = self.gui
        if self.kind == 'item':
            tp = {'rotation': [0, 0, roll], 'translation': [0, 3, 1], 'scale': [0.5] * 3}
            fp = {'rotation': [0, -90, 25 + roll], 'translation': [1.13, 3.2, 1.13], 'scale': [0.68] * 3}
            fpl = {'rotation': [0, 90, -25 + roll], 'translation': [1.13, 3.2, 1.13], 'scale': [0.68] * 3}
            ground = {'rotation': [0, 0, 0], 'translation': [0, 2, 0], 'scale': [self.ground_scale] * 3}
            fixed = {'rotation': [0, 180, roll], 'translation': [0, 0, 0], 'scale': [0.9] * 3}
        else:
            tp = {'rotation': [75, 45, 0], 'translation': [0, 2.5, 0], 'scale': [0.375] * 3}
            fp = {'rotation': [0, 45, 0], 'translation': [0, 0, 0], 'scale': [0.4] * 3}
            fpl = {'rotation': [0, 225, 0], 'translation': [0, 0, 0], 'scale': [0.4] * 3}
            ground = {'rotation': [0, 0, 0], 'translation': [0, 3, 0], 'scale': [self.ground_scale] * 3}
            fixed = {'rotation': [0, 180, 0], 'translation': [0, 0, 0], 'scale': [0.5] * 3}
        return {
            'gui': {'rotation': [gx, gy, gz + roll], 'translation': list(self.gui_trans), 'scale': [self.gui_scale] * 3},
            'ground': ground, 'fixed': fixed,
            'thirdperson_righthand': tp, 'thirdperson_lefthand': tp,
            'firstperson_righthand': fp, 'firstperson_lefthand': fpl,
            'head': {'rotation': [0, 0, 0], 'translation': [0, 14, 0], 'scale': [0.9] * 3},
        }

    def write_variant(self, assets, name, parent, roll=0.0):
        """같은 형상(parent)에 표시 변환만 다른 변형 (흔들림 등). 텍스처는 공유한다."""
        mdir = os.path.join(assets, 'models', 'item')
        with open(os.path.join(mdir, name + '.json'), 'w') as fh:
            json.dump({'parent': f'newyearcountdown:item/{parent}', 'display': self.display(roll)}, fh, separators=(',', ':'))

    def autofit(self, target=15.2):
        """GUI 시점에서 모델이 16px 슬롯에 꽉 차도록 scale / translation 을 계산한다."""
        rx, ry, rz = self.gui
        R = rot_matrix('x', rx) @ rot_matrix('y', ry) @ rot_matrix('z', rz)
        pts = []
        for box in self.boxes:
            for sx in (0, 1):
                for sy in (0, 1):
                    for sz in (0, 1):
                        p = np.array([box.b[0] if sx else box.a[0], box.b[1] if sy else box.a[1], box.b[2] if sz else box.a[2]])
                        pts.append(R @ (box.xform(p) - 8.0))
        pts = np.array(pts)
        lo, hi = pts.min(0), pts.max(0)
        ext = max(hi[0] - lo[0], hi[1] - lo[1])
        s = min(target / ext, 3.9)
        c = (lo + hi) / 2
        self.gui_scale = round(float(s), 4)
        self.gui_trans = (round(float(-c[0] * s), 3), round(float(-c[1] * s), 3), 0)

    # ----------------------------------------------------------- 아틀라스 배치
    def layout(self, pad=2):
        items = []
        for bi, box in enumerate(self.boxes):
            for f in box.faces:
                wu, hu = box.face_units(f)
                w = max(2, int(round(wu * self.density))); h = max(2, int(round(hu * self.density)))
                items.append((bi, f, w, h))
        order = sorted(range(len(items)), key=lambda i: (-items[i][3], -items[i][2]))
        for size in (64, 128, 256, 512, 1024, 2048):
            pos = {}
            x = y = rowh = 0
            ok = True
            for i in order:
                bi, f, w, h = items[i]
                if w + 2 * pad > size:
                    ok = False; break
                if x + w + 2 * pad > size:
                    x = 0; y += rowh; rowh = 0
                if y + h + 2 * pad > size:
                    ok = False; break
                pos[(bi, f)] = (x + pad, y + pad, w, h)
                x += w + 2 * pad; rowh = max(rowh, h + 2 * pad)
            if ok:
                return size, pos
        raise RuntimeError(f'{self.name}: atlas too large')

    def bake(self):
        size, pos = self.layout()
        atlas = np.zeros((size, size, 4))
        k = self.density
        for (bi, f), (x, y, w, h) in pos.items():
            box = self.boxes[bi]
            mat = box.mat_for(f)
            c = [box.corner(s) for s in CORNERS[f]]
            c0, c1, c3 = c[0], c[1], c[3]
            s = (np.arange(w) + 0.5) / w; t = (np.arange(h) + 0.5) / h
            P = c0[None, None, :] + s[None, :, None] * (c3 - c0)[None, None, :] + t[:, None, None] * (c1 - c0)[None, None, :]
            P = box.xform(P.reshape(-1, 3)).reshape(h, w, 3)
            N = box.xform_n(NORMALS[f])
            ctx = Ctx(w, h, f, P, N, k, seed=zlib.crc32(f'{self.name}/{bi}/{f}'.encode()))
            tile = mat.render(ctx)
            atlas[y:y + h, x:x + w] = tile
            p = 2   # 가장자리 연장(밉맵/보간 번짐 방지)
            atlas[y - p:y, x:x + w] = tile[0:1]
            atlas[y + h:y + h + p, x:x + w] = tile[-1:]
            atlas[y - p:y + h + p, x - p:x] = atlas[y - p:y + h + p, x:x + 1]
            atlas[y - p:y + h + p, x + w:x + w + p] = atlas[y - p:y + h + p, x + w - 1:x + w]
        return size, pos, atlas

    # ----------------------------------------------------------- 출력
    def write(self, assets):
        size, pos, atlas = self.bake()
        tex_dir = os.path.join(assets, 'textures', 'item', 'model')
        os.makedirs(tex_dir, exist_ok=True)
        # 완전 투명 픽셀의 RGB 는 이웃 색으로 (밉맵에서 검은 테두리 방지) — 여기서는 그대로 둔다
        Image.fromarray((atlas * 255 + 0.5).astype(np.uint8), 'RGBA').save(os.path.join(tex_dir, self.name + '.png'))
        self.atlas = atlas
        self.size, self.pos = size, pos
        elements = []
        for bi, box in enumerate(self.boxes):
            el = {'from': [round(v, 4) for v in box.a], 'to': [round(v, 4) for v in box.b]}
            if box.name:
                el['name'] = box.name
            if box.rot:
                axis, ang, org = box.rot
                el['rotation'] = {'angle': ang, 'axis': axis, 'origin': [round(v, 4) for v in org]}
            faces = {}
            for f in box.faces:
                x, y, w, h = pos[(bi, f)]
                uv = [x * 16 / size, y * 16 / size, (x + w) * 16 / size, (y + h) * 16 / size]
                fd = {'uv': [round(v, 4) for v in uv], 'texture': '#0'}
                if box.tint:
                    fd['tintindex'] = 0
                faces[f] = fd
            el['faces'] = faces
            elements.append(el)
        tex = f'newyearcountdown:item/model/{self.name}'
        data = {
            'textures': {'0': tex, 'particle': tex},
            'gui_light': 'side',
            'elements': elements,
            'display': self.display(self.roll),
        }
        mdir = os.path.join(assets, 'models', 'item')
        os.makedirs(mdir, exist_ok=True)
        with open(os.path.join(mdir, self.name + '.json'), 'w') as fh:
            json.dump(data, fh, separators=(',', ':'))
        return data
