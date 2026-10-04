"""컬링 아이템: 스톤(빨강/노랑), 브룸, 하우스(과녁 매트). goods_2d 와 같은 16x16 바닐라풍 픽셀아트."""
import json
import os
from goods_2d import Sprite, OUT

GRANITE = '#8d8f96'
GRANITE_D = '#6b6d74'


def stone(handle):
    s = Sprite()
    s.ellipse(8, 11.2, 6.6, 3.6, GRANITE_D)          # 아래 몸통
    s.rect(2, 8, 13, 11, GRANITE)                     # 옆면
    s.ellipse(8, 8.2, 6.6, 3.0, '#a7a9b0')            # 윗면
    s.ellipse(8, 8.2, 3.6, 1.7, handle)               # 손잡이 받침(팀 색)
    s.rect(7, 4, 8, 7, handle)                        # 손잡이 기둥
    s.rect(7, 4, 12, 5, handle)                       # 손잡이
    s.dot(4, 10, '#b9bbc2'); s.dot(11, 12, '#5d5f66'); s.dot(6, 12, '#5d5f66')
    return s.render()


def broom():
    s = Sprite()
    s.line(13.5, 1.5, 5, 10, '#8a5a2b', width=2)      # 자루
    s.poly([(1, 11), (6, 8), (9, 11), (4, 15)], '#2f3a4a')   # 패드
    s.poly([(2, 12), (6, 9.5), (7.5, 11), (4, 14)], '#4a5a70', shade=False)
    s.dot(13, 2, '#c9a24a'); s.dot(14, 1, '#c9a24a')
    return s.render()


def house():
    s = Sprite()
    s.ellipse(8, 8, 7.4, 7.4, '#2f62c8')
    s.ellipse(8, 8, 5.2, 5.2, '#f2f2f2', shade=False)
    s.ellipse(8, 8, 3.2, 3.2, '#c8201f')
    s.ellipse(8, 8, 1.2, 1.2, '#f2f2f2', shade=False)
    return s.render()


def write(assets):
    tdir = os.path.join(assets, 'textures', 'item')
    mdir = os.path.join(assets, 'models', 'item')
    for name, img in (('curling_stone_red', stone('#c8201f')), ('curling_stone_yellow', stone('#e8c020')),
                      ('curling_broom', broom()), ('curling_house', house())):
        img.save(os.path.join(tdir, name + '.png'))
        with open(os.path.join(mdir, name + '.json'), 'w') as fh:
            json.dump({'parent': 'minecraft:item/generated', 'textures': {'layer0': f'newyearcountdown:item/{name}'}}, fh, indent=1)
    # 하우스 블록: 바닥 무늬는 블록 엔티티 렌더러가 그린다 (블록 모델은 부서질 때 파티클용)
    bdir = os.path.join(assets, 'models', 'block')
    os.makedirs(bdir, exist_ok=True)
    with open(os.path.join(bdir, 'curling_house.json'), 'w') as fh:
        json.dump({'textures': {'particle': 'minecraft:block/blue_wool'}}, fh, indent=1)
    sdir = os.path.join(assets, 'blockstates')
    with open(os.path.join(sdir, 'curling_house.json'), 'w') as fh:
        json.dump({'variants': {'': {'model': 'newyearcountdown:block/curling_house'}}}, fh, indent=1)


if __name__ == '__main__':
    from PIL import Image
    imgs = [stone('#c8201f'), stone('#e8c020'), broom(), house()]
    sheet = Image.new('RGBA', (16 * 4 * 8, 16 * 8), (60, 60, 60, 255))
    for i, im in enumerate(imgs):
        sheet.paste(im.resize((128, 128), Image.NEAREST), (i * 128, 0), im.resize((128, 128), Image.NEAREST))
    sheet.save('/tmp/claude-0/s/curling_items.png')
