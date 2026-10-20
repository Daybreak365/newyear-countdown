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


def lens(txt=None, w=28, h=20):
    """속이 뚫린 렌즈: 금 프레임만 그리고 유리 부분은 완전 투명(캐릭터 눈이 보인다). 갑옷 레이어는 반투명이 안 되므로 알파 0/255 만 쓴다."""
    W, H = w * SS, h * SS
    img = Image.new('RGBA', (W, H), (0, 0, 0, 0))
    r = 7 * SS
    mask = Image.new('L', (W, H), 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, W - 1, H - 1], radius=r, fill=255)
    hole = Image.new('L', (W, H), 0)
    t = 3 * SS
    ImageDraw.Draw(hole).rounded_rectangle([t, t, W - 1 - t, H - 1 - t], radius=r - t // 2, fill=255)
    ring = Image.eval(mask, lambda v: v)
    ring.paste(0, (0, 0), hole)                     # 프레임 = 바깥 - 안쪽
    fr = gold_gradient(W, H)
    img.paste(fr, (0, 0), ring)
    d = ImageDraw.Draw(img)
    # 프레임 안팎 하이라이트/그림자
    d.rounded_rectangle([1 * SS, 1 * SS, W - 2 * SS, H - 2 * SS], radius=r - SS, outline=(255, 250, 200, 255), width=SS)
    d.rounded_rectangle([t - SS // 2, t - SS // 2, W - 1 - t + SS // 2, H - 1 - t + SS // 2], radius=r - t // 2, outline=(120, 76, 8, 255), width=SS)
    # 2027 숫자: 속이 빈 두꺼운 윤곽선 글자 (눈이 글자 안/사이로 보인다)
    if txt:
        f = ImageFont.truetype(FONT, int(15 * SS))
        mask_t = Image.new('L', (W, H), 0)
        ImageDraw.Draw(mask_t).text((W / 2, H / 2 + 1 * SS), txt, font=f, fill=255, anchor='mm')
        grow = mask_t.filter(ImageFilter.MaxFilter(int(2.2 * SS) // 2 * 2 + 1))
        edge = Image.fromarray(np.clip(np.array(grow, dtype=int) - np.array(mask_t, dtype=int), 0, 255).astype(np.uint8), 'L')
        grow2 = mask_t.filter(ImageFilter.MaxFilter(int(3.8 * SS) // 2 * 2 + 1))
        shadow = Image.fromarray(np.clip(np.array(grow2, dtype=int) - np.array(grow, dtype=int), 0, 255).astype(np.uint8), 'L')
        sh = Image.new('RGBA', (W, H), (120, 70, 4, 255)); sh.putalpha(shadow)
        gd = gold_gradient(W, H, '#fff6b8', '#ffd23a', '#e09a10'); gd.putalpha(edge)
        img = Image.alpha_composite(img, sh)
        img = Image.alpha_composite(img, gd)
    out = img.resize((w, h), Image.LANCZOS)
    a = np.array(out)
    a[..., 3] = np.where(a[..., 3] > 110, 255, 0)   # 알파를 0/255 로 이진화 (컷아웃)
    return Image.fromarray(a, 'RGBA')


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
    sheet.alpha_composite(lens('20'), (66, 86))
    sheet.alpha_composite(lens('27'), (98, 86))
    d = ImageDraw.Draw(sheet)
    bridge = gold_gradient(8, 8).resize((8, 8))
    sheet.alpha_composite(bridge.resize((8, 4)), (92, 94))
    # 옆면: 앞(렌즈)에서 귀까지만 가는 다리. 오른쪽 면(x 0~64)은 앞이 오른쪽, 왼쪽 면(x 128~192)은 앞이 왼쪽.
    # 머리 뒤통수(x 192~256)에는 아무것도 그리지 않는다.
    arm_len = 34
    for (x0, front_left) in ((0, False), (128, True)):
        ax = x0 if front_left else x0 + 64 - arm_len
        sheet.alpha_composite(gold_gradient(arm_len, 6), (ax, 93))
        hx_ = x0 if front_left else x0 + 58
        d.rectangle([hx_, 89, hx_ + 5, 103], fill=(166, 112, 10, 255))          # 힌지
        d.rectangle([hx_ + 1, 90, hx_ + 4, 92], fill=(255, 236, 140, 255))
        ex = x0 + arm_len - 6 if front_left else x0 + 64 - arm_len     # 귀 쪽 끝: 아래로 살짝 굽는 귀걸이
        d.rectangle([ex, 99, ex + 5, 106], fill=(214, 156, 28, 255))
    sheet.save(path)


if __name__ == '__main__':
    build('/tmp/claude-0/s/armor_new.png')
