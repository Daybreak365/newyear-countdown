"""모든 아이템 3D 모델과 텍스처를 생성해 src/main/resources 에 쓴다.

    python3 tools/modelgen/build.py            # 생성
    python3 tools/modelgen/build.py --preview  # 생성 + 미리보기 시트(/tmp/modelgen_preview/*.png)
"""
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
ASSETS = os.path.abspath(os.path.join(HERE, '..', '..', 'src', 'main', 'resources', 'assets', 'newyearcountdown'))

import m_bell, m_shrine, m_lantern, m_gacha, m_goods1, m_goods2, m_goods3, entity_textures, armor_texture, capsule_2d, yut_board, goods_2d

NS = 'newyearcountdown'
MDIR = os.path.join(ASSETS, 'models', 'item')


def dump(name, data):
    os.makedirs(MDIR, exist_ok=True)
    with open(os.path.join(MDIR, name + '.json'), 'w') as fh:
        json.dump(data, fh, separators=(',', ':'))


def add_overrides(name, preds):
    """이미 쓴 모델 JSON 에 overrides 를 붙인다. preds: [(임계값, 모델이름)] (오름차순, 뒤에 맞는 것이 우선)."""
    p = os.path.join(MDIR, name + '.json')
    with open(p) as fh:
        data = json.load(fh)
    data['overrides'] = [{'predicate': {f'{NS}:fx': round(v, 4)}, 'model': f'{NS}:item/{m}'} for v, m in preds]
    with open(p, 'w') as fh:
        json.dump(data, fh, separators=(',', ':'))


def main():
    preview = '--preview' in sys.argv
    entity_textures.write(ASSETS)
    d = os.path.join(ASSETS, 'textures', 'models', 'armor')
    os.makedirs(d, exist_ok=True)
    armor_texture.build(os.path.join(d, 'party_glasses_layer_1.png'))
    sheets = {}

    def put(m, group):
        m.write(ASSETS)
        sheets.setdefault(group, []).append(m)
        print(f'{m.name:30s} atlas {m.size:4d}  boxes {len(m.boxes):3d}')
        return m

    # ---- 구조물(블록 아이템) 4종
    for mod in (m_bell, m_shrine, m_lantern, m_gacha):
        put(mod.build(), 'structures')

    # ---- 캡슐: 바닐라 느낌 2D 픽셀아트 (6색 x 기본/좌우 흔들/열림)
    capsule_2d.write(ASSETS)

    # ---- 굿즈: 바닐라풍 16x16 2D 스프라이트 (안경만 3D 모델)
    goods_2d.write(ASSETS)
    put(m_goods1.glasses(), 'goods')
    yut_board.write(ASSETS)

    if preview:
        import preview as pv
        out = '/tmp/modelgen_preview'
        os.makedirs(out, exist_ok=True)
        for g, ms in sheets.items():
            pv.sheet(ms, os.path.join(out, g + '.png'), size=300, cols=4)
        print('preview ->', out)


if __name__ == '__main__':
    main()
