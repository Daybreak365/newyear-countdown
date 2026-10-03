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

import m_bell, m_shrine, m_lantern, m_gacha, m_capsule, m_goods1, m_goods2, entity_textures, armor_texture

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

    # ---- 캡슐: 6색 x (기본 / 좌우 흔들 / 열림)
    caps = []
    for i in range(6):
        base = put(m_capsule.build(i), 'capsule')
        put(m_capsule.build(i, True).like(base), 'capsule_open')
        base.write_variant(ASSETS, f'capsule_c{i}_a', f'capsule_c{i}', roll=12)
        base.write_variant(ASSETS, f'capsule_c{i}_b', f'capsule_c{i}', roll=-12)
    cap = {'parent': f'{NS}:item/capsule_c0', 'overrides': []}
    for code in range(24):
        c, s = divmod(code, 4)
        model = [f'capsule_c{c}', f'capsule_c{c}_a', f'capsule_c{c}_b', f'capsule_c{c}_open'][s]
        cap['overrides'].append({'predicate': {f'{NS}:capsule': round(code / 24, 5)}, 'model': f'{NS}:item/{model}'})
    dump('capsule', cap)

    # ---- 기념품
    put(m_goods1.glasses(), 'goods')

    pouch = put(m_goods1.pouch(), 'goods')
    put(m_goods1.pouch(True).like(pouch), 'goods_state')
    pouch.write_variant(ASSETS, 'lucky_pouch_a', 'lucky_pouch', roll=10)
    pouch.write_variant(ASSETS, 'lucky_pouch_b', 'lucky_pouch', roll=-10)
    add_overrides('lucky_pouch', [(0.3, 'lucky_pouch_a'), (0.6, 'lucky_pouch_b'), (0.9, 'lucky_pouch_open')])

    sheep = put(m_goods1.sheep(), 'goods')
    put(m_goods1.sheep(True).like(sheep), 'goods_state')
    add_overrides('sheep_plush', [(0.9, 'sheep_plush_squish')])

    bell = put(m_goods1.mini_bell(), 'goods')
    bell.write_variant(ASSETS, 'mini_bell_a', 'mini_bell', roll=16)
    bell.write_variant(ASSETS, 'mini_bell_b', 'mini_bell', roll=-16)
    add_overrides('mini_bell', [(0.3, 'mini_bell_a'), (0.6, 'mini_bell_b'), (0.9, 'mini_bell_a')])

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

    cid = put(m_goods2.cider(0), 'goods')
    shk = put(m_goods2.cider(1).like(cid), 'goods_state')
    put(m_goods2.cider(2).like(cid), 'goods_state')
    shk.write_variant(ASSETS, 'sparkling_cider_a', 'sparkling_cider_shake', roll=8)
    shk.write_variant(ASSETS, 'sparkling_cider_b', 'sparkling_cider_shake', roll=-8)
    add_overrides('sparkling_cider', [(0.3, 'sparkling_cider_a'), (0.6, 'sparkling_cider_b'), (0.9, 'sparkling_cider_pop')])

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
