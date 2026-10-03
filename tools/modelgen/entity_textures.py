"""가챠 머신 렌더러(GachaRenderer)가 쓰는 캡슐 전용 텍스처 생성."""
import os
import numpy as np
from PIL import Image


def write(assets):
    d = os.path.join(assets, 'textures', 'entity')
    os.makedirs(d, exist_ok=True)
    S = 64
    y, x = np.mgrid[0:S, 0:S]
    u, v = (x + .5) / S, (y + .5) / S
    base = 0.80 + 0.20 * (1 - v)                                   # 위가 밝고 아래가 살짝 어두움 (틴트가 곱해진다)
    spot = np.exp(-(((u - 0.30) / 0.16) ** 2 + ((v - 0.26) / 0.20) ** 2))   # 왼쪽 위 반사점
    rim = np.exp(-((v - 0.93) / 0.06) ** 2) * 0.12
    val = np.clip(base + 0.22 * spot - rim, 0, 1)
    img = np.stack([val, val, val, np.ones_like(val)], -1)
    Image.fromarray((img * 255).astype(np.uint8), 'RGBA').save(os.path.join(d, 'capsule_shell.png'))
    seam = np.ones((8, 8, 4)); seam[..., :3] = 0.62
    Image.fromarray((seam * 255).astype(np.uint8), 'RGBA').save(os.path.join(d, 'capsule_seam.png'))
