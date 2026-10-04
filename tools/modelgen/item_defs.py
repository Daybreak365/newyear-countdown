"""26.x 아이템 모델 정의(assets/<ns>/items/<이름>.json)와 장비(착용) 정의를 만든다.
models/item 의 overrides(예전 predicate 방식)는 range_dispatch 로 옮기고 모델에서는 지운다."""
import glob
import json
import os

NS = 'newyearcountdown'
ITEMS = ['bosingak_bell', 'omikuji_box', 'gacha_machine', 'capsule', 'wish_lantern', 'party_glasses', 'lucky_pouch',
         'red_envelope', 'mini_calendar', 'firecracker_keychain', 'champagne', 'golden_badge', 'tteokguk', 'kite',
         'yut_set', 'greeting_card']


def _model(ref):
    return {'type': 'minecraft:model', 'model': ref}


def write(assets):
    mdir = os.path.join(assets, 'models', 'item')
    idir = os.path.join(assets, 'items')
    os.makedirs(idir, exist_ok=True)
    defs = {}
    for p in sorted(glob.glob(os.path.join(mdir, '*.json'))):
        name = os.path.splitext(os.path.basename(p))[0]
        with open(p) as fh:
            data = json.load(fh)
        ov = data.pop('overrides', None)
        if not ov:
            continue
        prop = next(iter(ov[0]['predicate']))
        entries = [{'threshold': o['predicate'][prop], 'model': _model(o['model'])} for o in ov]
        # 부모만 가리키는 껍데기 모델(캡슐)은 부모를 기본 모델로 쓴다
        fallback = data['parent'] if set(data) == {'parent'} else f'{NS}:item/{name}'
        defs[name] = {'model': {'type': 'minecraft:range_dispatch', 'property': prop, 'scale': 1.0,
                                'entries': entries, 'fallback': _model(fallback)}}
        if set(data) == {'parent'}:
            os.remove(p)
        else:
            with open(p, 'w') as fh:
                json.dump(data, fh, separators=(',', ':'))
    for name in ITEMS:
        d = defs.get(name, {'model': _model(f'{NS}:item/{name}')})
        with open(os.path.join(idir, name + '.json'), 'w') as fh:
            json.dump(d, fh, indent=1)
            fh.write('\n')
    # 2027 안경 착용 텍스처 (textures/entity/equipment/humanoid/party_glasses.png)
    edir = os.path.join(assets, 'equipment')
    os.makedirs(edir, exist_ok=True)
    with open(os.path.join(edir, 'party_glasses.json'), 'w') as fh:
        json.dump({'layers': {'humanoid': [{'texture': f'{NS}:party_glasses'}]}}, fh, indent=1)
        fh.write('\n')
