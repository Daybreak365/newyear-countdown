"""2027 안경 착용 레이어(512x256, 64x32 모델의 8배). 머리 박스 UV: 오른쪽 면 x0..64 / 앞 64..128 / 왼쪽 128..192 / 뒤 192..256, y 64..128."""
import os
import numpy as np
from PIL import Image, ImageDraw, ImageFont, ImageFilter

FONT = '/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf'
SS = 4   # 슈퍼샘플링


def gold_gradient(w, h, top='#fff2a6', mid='#f0b92a', bot='#9c6a0a'):
    def c(s):
        s = s.lstrip('#'); return np.array([int(s[i:i + 2], 16) for i in (0, 2, 4)], dtype=float)
    t, m, b = c(top), c(mid), c(bot)
    ys = np.linspace(0, 1, h)[:, None]
    col = np.where(ys < 0.35, t + (m - t) * (ys / 0.35), m + (b - m) * ((ys - 0.35) / 0.65))
    arr = np.repeat(col[:, None, :], w, axis=1)
    return Image.fromarray(arr.astype(np.uint8), 'RGB').convert('RGBA')


def lens(txt, w=28, h=34):
    W, H = w * SS, h * SS
    img = Image.new('RGBA', (W, H), (0, 0, 0, 0))
    r = 7 * SS
    mask = Image.new('L', (W, H), 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, W - 1, H - 1], radius=r, fill=255)
    # 금 프레임
    fr = gold_gradient(W, H)
    img.paste(fr, (0, 0), mask)
    # 유리
    inner = Image.new('L', (W, H), 0)
    t = 4 * SS
    ImageDraw.Draw(inner).rounded_rectangle([t, t, W - 1 - t, H - 1 - t], radius=r - t // 2, fill=255)
    ys = np.linspace(0, 1, H)[:, None, None]
    xs = np.linspace(0, 1, W)[None, :, None]
    top = np.array([190, 235, 255.0]); bot = np.array([70, 150, 225.0])
    glass = top * (1 - ys) + bot * ys
    glass = glass * (1 - 0.18 * xs)
    g = Image.fromarray(np.broadcast_to(glass, (H, W, 3)).astype(np.uint8), 'RGB').convert('RGBA')
    g.putalpha(inner.point(lambda v: int(v * 0.86)))
    img = Image.alpha_composite(img, g)
    d = ImageDraw.Draw(img)
    # 반사 줄무늬
    d.line([(5 * SS, H - 8 * SS), (13 * SS, 7 * SS)], fill=(255, 255, 255, 150), width=3 * SS)
    d.line([(10 * SS, H - 7 * SS), (15 * SS, 14 * SS)], fill=(255, 255, 255, 90), width=2 * SS)
    # 숫자
    f = ImageFont.truetype(FONT, int(19 * SS))
    d.text((W / 2, H / 2 + 1 * SS), txt, font=f, fill='#fff3a8', anchor='mm', stroke_width=int(1.6 * SS), stroke_fill='#8a5a06')
    d.text((W / 2, H / 2), txt, font=f, fill='#f6c945', anchor='mm')
    # 프레임 하이라이트
    d.rounded_rectangle([1 * SS, 1 * SS, W - 2 * SS, H - 2 * SS], radius=r - SS, outline=(255, 250, 200, 200), width=SS)
    d.rounded_rectangle([t - SS // 2, t - SS // 2, W - 1 - t + SS // 2, H - 1 - t + SS // 2], radius=r - t // 2, outline=(120, 76, 8, 255), width=SS)
    return img.resize((w, h), Image.LANCZOS)


def star(d, cx, cy, R, fill):
    pts = []
    for i in range(10):
        a = -np.pi / 2 + i * np.pi / 5
        rr = R if i % 2 == 0 else R * 0.42
        pts.append((cx + rr * np.cos(a), cy + rr * np.sin(a)))
    d.polygon(pts, fill=fill)


def build(path):
    sheet = Image.new('RGBA', (512, 256), (0, 0, 0, 0))
    # 앞면: 렌즈 둘 + 브리지
    sheet.alpha_composite(lens('20'), (66, 79))
    sheet.alpha_composite(lens('27'), (98, 79))
    d = ImageDraw.Draw(sheet)
    bridge = gold_gradient(8, 8).resize((8, 8))
    sheet.alpha_composite(bridge, (92, 90))
    sheet.alpha_composite(bridge.resize((8, 6)), (92, 106))
    # 코받침
    d.ellipse([95, 112, 99, 118], fill=(240, 185, 42, 255))
    # 옆(오른쪽 x0..64, 왼쪽 x128..192): 다리(템플) + 힌지 + 별
    for (x0, hinge_x, flip) in ((0, 56, False), (128, 0, True)):
        arm = gold_gradient(64, 9)
        sheet.alpha_composite(arm, (x0, 88))
        # 귀 쪽 아래로 휘는 끝
        d.rectangle([x0 + (0 if not flip else 54), 95, x0 + (10 if not flip else 63), 106], fill=(214, 156, 28, 255))
        hx = x0 + hinge_x
        d.rectangle([hx, 84, hx + 7, 101], fill=(166, 112, 10, 255))
        d.rectangle([hx + 1, 85, hx + 6, 88], fill=(255, 236, 140, 255))
        star(d, x0 + 30, 92, 7, (255, 244, 170, 255))
    # 뒤쪽: 얇은 스트랩 + 작은 별
    strap = gold_gradient(64, 6, '#e8c860', '#c89a20', '#7a5208')
    sheet.alpha_composite(strap, (192, 90))
    for sx in (206, 224, 242):
        star(d, sx, 93, 4, (255, 238, 150, 255))
    sheet.save(path)


if __name__ == '__main__':
    build('/tmp/claude-0/s/armor_new.png')
