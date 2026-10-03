"""생성한 모델을 GUI 시점으로 소프트웨어 렌더링해서 PNG 로 확인하는 도구 (게임 없이 눈으로 검수용)."""
import numpy as np
from PIL import Image
from core import CORNERS, UVC, NORMALS, rot_matrix

SHADE = {'up': 1.0, 'down': 0.55, 'north': 0.82, 'south': 0.82, 'east': 0.66, 'west': 0.66}


def view_matrix(rot, scale):
    rx, ry, rz = rot
    # rotationXYZ: Rx * Ry * Rz
    return rot_matrix('x', rx) @ rot_matrix('y', ry) @ rot_matrix('z', rz) * scale


def render(model, size=256, rot=None, scale=None, trans=None, tint=None, bg=(70, 74, 88)):
    if not hasattr(model, 'atlas'):
        model.write('/tmp/_prev_assets')
    rot = rot or model.gui
    scale = scale if scale is not None else model.gui_scale
    trans = trans or model.gui_trans
    M = view_matrix(rot, scale)
    atlas = model.atlas
    A = atlas.shape[0]
    zbuf = np.full((size, size), -1e9)
    img = np.zeros((size, size, 3)); img[:] = np.array(bg) / 255
    px_per_unit = size / 16.0 / 16.0 * 16  # 1 블록(16단위) = 16 GUI px 의 size/16 배
    quads = []
    for bi, box in enumerate(model.boxes):
        for f in box.faces:
            x, y, w, h = model.pos[(bi, f)]
            pts = []
            for ci, sel in enumerate(CORNERS[f]):
                p = box.xform(box.corner(sel))
                u, v = UVC[ci]
                pts.append((p, (x + u * w) / A, (y + v * h) / A))
            n = M @ box.xform_n(NORMALS[f])
            quads.append((bi, f, pts, n, box))
    trans_quads = []
    for bi, f, pts, n, box in quads:
        if n[2] <= 1e-6:
            continue
        P = np.array([(M @ ((p - 8) / 16.0)) for p, _, _ in pts])  # block units
        sx = size / 2 + (P[:, 0] + trans[0] / 16.0) * 16 * size / 16.0
        sy = size / 2 - (P[:, 1] + trans[1] / 16.0) * 16 * size / 16.0
        z = P[:, 2]
        uv = np.array([(u, v) for _, u, v in pts])
        shade = 0.5 + 0.5 * SHADE[f] * (0.6 + 0.4 * abs(n[2])) if False else SHADE[f]
        for tri in ((0, 1, 2), (0, 2, 3)):
            raster(img, zbuf, atlas, sx[list(tri)], sy[list(tri)], z[list(tri)], uv[list(tri)], shade, box.tint, tint, size)
    return (np.clip(img, 0, 1) * 255).astype(np.uint8)


def raster(img, zbuf, atlas, sx, sy, z, uv, shade, tintflag, tint, size):
    x0, x1 = int(max(0, np.floor(sx.min()))), int(min(size - 1, np.ceil(sx.max())))
    y0, y1 = int(max(0, np.floor(sy.min()))), int(min(size - 1, np.ceil(sy.max())))
    if x1 < x0 or y1 < y0:
        return
    xs, ys = np.meshgrid(np.arange(x0, x1 + 1) + 0.5, np.arange(y0, y1 + 1) + 0.5)
    d = (sy[1] - sy[2]) * (sx[0] - sx[2]) + (sx[2] - sx[1]) * (sy[0] - sy[2])
    if abs(d) < 1e-9:
        return
    l0 = ((sy[1] - sy[2]) * (xs - sx[2]) + (sx[2] - sx[1]) * (ys - sy[2])) / d
    l1 = ((sy[2] - sy[0]) * (xs - sx[2]) + (sx[0] - sx[2]) * (ys - sy[2])) / d
    l2 = 1 - l0 - l1
    inside = (l0 >= -1e-4) & (l1 >= -1e-4) & (l2 >= -1e-4)
    if not inside.any():
        return
    zz = l0 * z[0] + l1 * z[1] + l2 * z[2]
    u = l0 * uv[0, 0] + l1 * uv[1, 0] + l2 * uv[2, 0]
    v = l0 * uv[0, 1] + l1 * uv[1, 1] + l2 * uv[2, 1]
    ax = np.clip((u * atlas.shape[1]).astype(int), 0, atlas.shape[1] - 1)
    ay = np.clip((v * atlas.shape[0]).astype(int), 0, atlas.shape[0] - 1)
    tex = atlas[ay, ax]
    col = tex[..., :3].copy()
    if tintflag and tint is not None:
        col = col * np.array(tint)
    col = col * shade
    a = tex[..., 3]
    sub = zbuf[y0:y1 + 1, x0:x1 + 1]
    ok = inside & (zz > sub) & (a > 0.02)
    region = img[y0:y1 + 1, x0:x1 + 1]
    alpha = a[..., None]
    blend = region * (1 - alpha) + col * alpha
    region[ok] = blend[ok]
    sub[ok & (a > 0.95)] = zz[ok & (a > 0.95)]


def sheet(models, path, size=256, cols=4, **kw):
    rows = (len(models) + cols - 1) // cols
    W = Image.new('RGB', (cols * size, rows * size), (70, 74, 88))
    for i, m in enumerate(models):
        t = kw.get('tints', {}).get(m.name)
        im = Image.fromarray(render(m, size, tint=t))
        W.paste(im, ((i % cols) * size, (i // cols) * size))
    W.save(path)
