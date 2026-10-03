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

import m_bell, m_shrine, m_lantern, m_gacha, m_goods1, m_goods2, m_goods3, entity_textures, armor_texture, capsule_2d, yut_board

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

    # ---- 기념품
    put(m_goods1.glasses(), 'goods')

    pouch = put(m_goods1.pouch(), 'goods')
    put(m_goods1.pouch(True).like(pouch), 'goods_state')
    pouch.write_variant(ASSETS, 'lucky_pouch_a', 'lucky_pouch', roll=10)
    pouch.write_variant(ASSETS, 'lucky_pouch_b', 'lucky_pouch', roll=-10)
    add_overrides('lucky_pouch', [(0.3, 'lucky_pouch_a'), (0.6, 'lucky_pouch_b'), (0.9, 'lucky_pouch_open')])

    env = put(m_goods2.envelope(), 'goods')
    put(m_goods2.envelope(True).like(env), 'goods_state')
    add_overrides('red_envelope', [(0.9, 'red_envelope_open')])

    cal = put(m_goods2.calendar(), 'goods')
    put(m_goods2.calendar(True).like(cal), 'goods_state')
    add_overrides('mini_calendar', [(0.9, 'mini_calendar_flip')])

    fc = put(m_goods2.firecracker(0), 'goods')
    lit = put(m_goods2.firecracker(1).like(fc), 'goods_state')
    put(m_goods2.firecracker(2).like(fc), 'goods_state')
    lit.write_variant(ASSETS, 'firecracker_keychain_lit_a', 'firecracker_keychain_lit', roll=6)
    lit.write_variant(ASSETS, 'firecracker_keychain_lit_b', 'firecracker_keychain_lit', roll=-6)
    add_overrides('firecracker_keychain', [(0.3, 'firecracker_keychain_lit_a'), (0.6, 'firecracker_keychain_lit_b'), (0.9, 'firecracker_keychain_burst')])

    cid = put(m_goods3.champagne(0), 'goods')
    shk = put(m_goods3.champagne(1).like(cid), 'goods_state')
    put(m_goods3.champagne(2).like(cid), 'goods_state')
    shk.write_variant(ASSETS, 'champagne_a', 'champagne_shake', roll=8)
    shk.write_variant(ASSETS, 'champagne_b', 'champagne_shake', roll=-8)
    add_overrides('champagne', [(0.3, 'champagne_a'), (0.6, 'champagne_b'), (0.9, 'champagne_pop')])

    put(m_goods3.tteokguk(), 'goods')
    kt = put(m_goods3.kite(), 'goods')
    put(m_goods3.kite(True).like(kt), 'goods_state')
    add_overrides('kite', [(0.9, 'kite_fly')])
    put(m_goods3.yut(), 'goods')      # 윷 세트 아이템 모델 (설치하면 윷판 블록 yut_board)
    yut_board.write(ASSETS)
    cd = put(m_goods3.card(), 'goods')
    put(m_goods3.card(True).like(cd), 'goods_state')
    add_overrides('greeting_card', [(0.9, 'greeting_card_open')])

    bd = put(m_goods2.badge(), 'goods')
    put(m_goods2.badge(True).like(bd), 'goods_state')
    add_overrides('golden_badge', [(0.9, 'golden_badge_shine')])

    if preview:
        import preview as pv
        out = '/tmp/modelgen_preview'
        os.makedirs(out, exist_ok=True)
        for g, ms in sheets.items():
            pv.sheet(ms, os.path.join(out, g + '.png'), size=300, cols=4)
        print('preview ->', out)


if __name__ == '__main__':
    main()
