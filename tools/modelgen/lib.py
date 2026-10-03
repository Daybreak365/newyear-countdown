"""재질/문양 라이브러리."""
import math
import numpy as np
from PIL import Image, ImageDraw, ImageFont
from core import Mat, rgb

FONT_BOLD = '/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf'


def font(px):
    return ImageFont.truetype(FONT_BOLD, max(4, int(px)))


def uv(ctx):
    y, x = np.mgrid[0:ctx.h, 0:ctx.w]
    return (x + 0.5) / ctx.k, (y + 0.5) / ctx.k


def lownoise(ctx, scale, shape=None):
    """scale(단위) 크기로 뭉친 부드러운 노이즈 (h,w) 0~1."""
    h, w = shape or (ctx.h, ctx.w)
    gh, gw = max(2, int(h / (scale * ctx.k)) + 3), max(2, int(w / (scale * ctx.k)) + 3)
    g = ctx.rng.random((gh, gw))
    im = Image.fromarray((g * 255).astype(np.uint8)).resize((w, h), Image.BICUBIC)
    return np.asarray(im, dtype=np.float64) / 255.0


def mix(a, b, t):
    t = np.asarray(t)
    if t.ndim == 2:
        t = t[..., None]
    return a * (1 - t) + b * t


# ------------------------------------------------------------------ 바탕 패턴
def b_wood(c, dark=0.78, along='u', freq=1.7, planks=None):
    c = rgb(c)

    def f(ctx):
        U, V = uv(ctx)
        across, t = (V, U) if along == 'u' else (U, V)
        n = lownoise(ctx, 2.5)
        g = 0.5 + 0.5 * np.sin(across * freq * 6.283 + 3.0 * n + t * 0.35)
        fine = ctx.rng.random((ctx.h, ctx.w))
        sh = 1 - (1 - dark) * (g ** 2 * 0.8 + 0.2 * fine)
        col = c * sh[..., None]
        if planks:
            line = (np.abs(((across / planks) % 1.0) - 0.0) < 0.5 / (planks * ctx.k)) | (np.abs(((across / planks) % 1.0) - 1.0) < 0.5 / (planks * ctx.k))
            col = np.where(line[..., None], col * 0.62, col)
        return col
    return f


def b_bricks(c, mortar='#55585c', bw=3.2, bh=1.6, var=0.10, mw=0.14):
    c, mortar = rgb(c), rgb(mortar)

    def f(ctx):
        U, V = uv(ctx)
        row = np.floor(V / bh)
        off = (row % 2) * bw / 2
        col_i = np.floor((U + off) / bw)
        rnd = np.sin(row * 12.9898 + col_i * 78.233 + (ctx.seed % 97)) * 43758.5453
        rnd = rnd - np.floor(rnd)
        tone = 1 + (rnd - 0.5) * 2 * var
        base = c * tone[..., None] * (0.94 + 0.12 * lownoise(ctx, 1.2)[..., None])
        fx = ((U + off) / bw) % 1.0 * bw
        fy = (V / bh) % 1.0 * bh
        edge = (fx < mw) | (fy < mw)
        hi = ((fx > mw) & (fx < mw * 2.2)) | ((fy > mw) & (fy < mw * 2.2))
        base = np.where(hi[..., None], base * 1.08, base)
        return np.where(edge[..., None], mortar, base)
    return f


def b_tiles(c, dark, rh=1.3, tw=1.5, edge='#1d2430'):
    """기와(비늘) 패턴: 줄마다 반원 비늘."""
    c, dark, edge = rgb(c), rgb(dark), rgb(edge)

    def f(ctx):
        U, V = uv(ctx)
        row = np.floor(V / rh)
        off = (row % 2) * tw / 2
        fx = ((U + off) / tw) % 1.0
        fy = (V / rh) % 1.0
        arc = 0.18 * (1 - np.cos((fx - 0.5) * 2 * np.pi)) / 2 + 0.78   # 비늘 아래 곡선
        cell = np.floor((U + off) / tw)
        rnd = np.sin(row * 12.9898 + cell * 78.233) * 43758.5453
        rnd = rnd - np.floor(rnd)
        shade = 1 - 0.25 * fy ** 2
        base = mix(dark, c, shade[..., None].repeat(3, -1)[..., 0]) * (0.93 + 0.14 * rnd[..., None])
        line = np.abs(fy - arc) < 0.05
        under = fy > arc
        base = np.where(under[..., None], base * 0.62, base)
        base = np.where(line[..., None], edge, base)
        ridge = (fx < 0.04) | (fx > 0.96)
        return np.where(ridge[..., None], base * 0.7, base)
    return f


def b_cloth(c, weave=0.9, vgrad=0.0):
    c = rgb(c)

    def f(ctx):
        U, V = uv(ctx)
        w = 0.5 + 0.5 * np.sin(U * 6.283 / weave * 3) * np.sin(V * 6.283 / weave * 3)
        col = c * (0.94 + 0.10 * w)[..., None]
        if vgrad:
            col = col * (1 - vgrad * (V / max(ctx.hu, 1e-6)))[..., None]
        return col
    return f


def b_paper(c, fibre=0.04):
    c = rgb(c)

    def f(ctx):
        n = lownoise(ctx, 0.6)
        return c * (1 - fibre + fibre * 2 * n)[..., None]
    return f


def b_wool(c, dark=0.82):
    """뭉게뭉게 곱슬 양털."""
    c = rgb(c)

    def f(ctx):
        n1 = lownoise(ctx, 0.9)
        n2 = lownoise(ctx, 0.45)
        curl = np.abs(np.sin((n1 * 6.0 + n2 * 2.0) * 3.14159))
        sh = 1 - (1 - dark) * (1 - curl) * 0.9
        return c * sh[..., None] * (0.96 + 0.08 * n2[..., None])
    return f


def b_vgrad(top, bottom):
    top, bottom = rgb(top), rgb(bottom)

    def f(ctx):
        U, V = uv(ctx)
        t = np.clip(V / max(ctx.hu, 1e-6), 0, 1)
        return mix(top, bottom, t)
    return f


def b_brushed(c, amt=0.05):
    c = rgb(c)

    def f(ctx):
        r = ctx.rng.random((ctx.h, 1))
        return c * (1 - amt + 2 * amt * r)[..., None] * np.ones((1, ctx.w, 1))
    return f


# ------------------------------------------------------------------ 재질 프리셋
def flat(c, **kw):
    return Mat(c, **kw)


def wood(c, dark=0.78, along='u', planks=None, **kw):
    return Mat(c, base=b_wood(c, dark, along, planks=planks), **kw)


def lacquer(c, shape=None, **kw):
    kw.setdefault('spec', 0.35); kw.setdefault('noise', 0.012)
    return Mat(c, base=b_vgrad(rgb(c) * 1.05, rgb(c) * 0.86), shape=shape, **kw)


def gold(c='#e8b830', shape=None, **kw):
    kw.setdefault('spec', 0.9); kw.setdefault('shin', 18); kw.setdefault('noise', 0.015)
    return Mat(c, base=b_brushed(c, 0.04), shape=shape, **kw)


def bronze(c='#b8863f', shape=None, **kw):
    kw.setdefault('spec', 0.7); kw.setdefault('shin', 16); kw.setdefault('noise', 0.02)
    return Mat(c, base=b_brushed(c, 0.05), shape=shape, **kw)


def plastic(c, shape=None, **kw):
    kw.setdefault('spec', 0.8); kw.setdefault('shin', 34); kw.setdefault('noise', 0.006); kw.setdefault('bevel', 0.04)
    return Mat(c, shape=shape, **kw)


# ------------------------------------------------------------------ 데칼 (PIL, 얼굴 좌표 = 단위*k)
def d_text(txt, cx, cy, size, color, stroke=0, stroke_color=None, anchor='mm'):
    def fn(d, ctx, k):
        f = font(size * k)
        d.text((cx * k, cy * k), txt, font=f, fill=color, anchor=anchor, stroke_width=int(stroke * k), stroke_fill=stroke_color)
    return fn


def d_rect(x1, y1, x2, y2, color, r=0):
    def fn(d, ctx, k):
        if r:
            d.rounded_rectangle([x1 * k, y1 * k, x2 * k, y2 * k], radius=r * k, fill=color)
        else:
            d.rectangle([x1 * k, y1 * k, x2 * k, y2 * k], fill=color)
    return fn


def d_ellipse(x1, y1, x2, y2, fill=None, outline=None, width=0.1):
    def fn(d, ctx, k):
        d.ellipse([x1 * k, y1 * k, x2 * k, y2 * k], fill=fill, outline=outline, width=max(1, int(width * k)))
    return fn


def d_line(pts, color, width=0.1):
    def fn(d, ctx, k):
        d.line([(x * k, y * k) for x, y in pts], fill=color, width=max(1, int(width * k)), joint='curve')
    return fn


def d_poly(pts, color):
    def fn(d, ctx, k):
        d.polygon([(x * k, y * k) for x, y in pts], fill=color)
    return fn


def d_many(*fns):
    def fn(d, ctx, k):
        for f in fns:
            f(d, ctx, k)
    return fn


def rgba(c, a=255):
    c = c.lstrip('#')
    return (int(c[0:2], 16), int(c[2:4], 16), int(c[4:6], 16), a)


# ------------------------------------------------------------------ SDF 오버레이 (3D 위치 기반: 면이 달라도 문양이 이어진다)
def ov_disc(cx, cy, r, color, soft=0.06, front=True):
    """z 방향(정면)에서 본 원형 문양. (x,y) 평면 기준."""
    col = rgb(color)

    def fn(ctx, base):
        X, Y = ctx.P[..., 0], ctx.P[..., 1]
        d = np.sqrt((X - cx) ** 2 + (Y - cy) ** 2) - r
        a = np.clip(0.5 - d / soft, 0, 1)
        if front:
            a = a * (1.0 if ctx.N[2] > 0.2 else 0.0)
        return mix(base, col, a)
    return fn


def ov_ring(cx, cy, r, w, color, soft=0.05):
    col = rgb(color)

    def fn(ctx, base):
        X, Y = ctx.P[..., 0], ctx.P[..., 1]
        d = np.abs(np.sqrt((X - cx) ** 2 + (Y - cy) ** 2) - r) - w / 2
        a = np.clip(0.5 - d / soft, 0, 1) * (1.0 if ctx.N[2] > 0.2 else 0.0)
        return mix(base, col, a)
    return fn


def ov_front(fn_xy):
    """fn_xy(X,Y)->(alpha(h,w), color(3)) 정면 평면 문양."""
    def fn(ctx, base):
        if ctx.N[2] <= 0.2:
            return base
        a, c = fn_xy(ctx.P[..., 0], ctx.P[..., 1])
        return mix(base, c, a)
    return fn


def ov_band(y1, y2, color, soft=0.05, axis=1):
    col = rgb(color)

    def fn(ctx, base):
        Y = ctx.P[..., axis]
        a = np.clip((Y - y1) / soft + 0.5, 0, 1) * np.clip((y2 - Y) / soft + 0.5, 0, 1)
        return mix(base, col, a)
    return fn


# ------------------------------------------------------------------ 이미지 오버레이 (정면 평면에 PIL 그림을 (x,y) 모델 좌표로 붙인다)
def art(w_u, h_u, ppu=28):
    """w_u x h_u 단위 크기 RGBA 캔버스와 Draw 를 돌려준다. 좌표는 (단위*ppu), y 는 아래로 증가."""
    img = Image.new('RGBA', (int(w_u * ppu), int(h_u * ppu)), (0, 0, 0, 0))
    return img, ImageDraw.Draw(img), ppu


def ov_image(img, x0, y1, ppu, front=True, min_nz=0.2):
    """img 의 좌상단이 모델 좌표 (x0, y1) (y1 = 이미지 윗변의 y)에 오도록 붙인다. 면 법선이 +z 를 향할 때만."""
    arr = np.asarray(img, dtype=np.float64) / 255.0
    H, W = arr.shape[:2]

    def fn(ctx, base):
        if front and ctx.N[2] <= min_nz:
            return base
        X, Y = ctx.P[..., 0], ctx.P[..., 1]
        ix = np.floor((X - x0) * ppu).astype(int)
        iy = np.floor((y1 - Y) * ppu).astype(int)
        ok = (ix >= 0) & (ix < W) & (iy >= 0) & (iy < H)
        ixc, iyc = np.clip(ix, 0, W - 1), np.clip(iy, 0, H - 1)
        px = arr[iyc, ixc]
        a = (px[..., 3] * ok)[..., None]
        return base * (1 - a) + px[..., :3] * a
    return fn


def heart_pts(cx, cy, s):
    pts = []
    for t in np.linspace(0, 2 * np.pi, 60):
        x = 16 * np.sin(t) ** 3
        y = 13 * np.cos(t) - 5 * np.cos(2 * t) - 2 * np.cos(3 * t) - np.cos(4 * t)
        pts.append((cx + x * s / 34, cy - y * s / 34))
    return pts
