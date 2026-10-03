"""2027 안경 아이템 모델: 착용 텍스처(갑옷 레이어)를 그대로 머리 상자에 입혀, 손/인벤토리에서도 쓴 모습과 똑같이 보이게 한다.
갑옷 텍스처(64x32 배치, 512x256)는 아이템 아틀라스에 들어가지 않으므로 textures/item/party_glasses_worn.png 로 복사해 쓴다."""
import json
import os
import shutil

# 64x32 배치 기준 머리 상자 각 면 → 모델 UV(0~16)
def uv(x0, y0, x1, y1):
    return [x0 * 16 / 64, y0 * 16 / 32, x1 * 16 / 64, y1 * 16 / 32]


def write(assets):
    src = os.path.join(assets, 'textures', 'models', 'armor', 'party_glasses_layer_1.png')
    dst = os.path.join(assets, 'textures', 'item', 'party_glasses_worn.png')
    shutil.copyfile(src, dst)
    tex = 'newyearcountdown:item/party_glasses_worn'
    # 정면(앞면 텍스처)을 남쪽(+z, 보는 사람 쪽)에 둔다. 보는 사람 기준 왼쪽(서쪽) 면 = 캐릭터 오른쪽 옆면.
    faces = {
        'south': {'uv': uv(8, 8, 16, 16), 'texture': '#0'},
        'west': {'uv': uv(0, 8, 8, 16), 'texture': '#0'},
        'east': {'uv': uv(16, 8, 24, 16), 'texture': '#0'},
    }
    model = {
        'textures': {'0': tex, 'particle': tex},
        'gui_light': 'front',
        'elements': [{'from': [0, 0, 0], 'to': [16, 16, 16], 'faces': faces}],
        'display': {
            'gui': {'rotation': [8, -28, 0], 'translation': [0, 0, 0], 'scale': [0.72, 0.72, 0.72]},
            'ground': {'rotation': [0, 0, 0], 'translation': [0, 2, 0], 'scale': [0.5, 0.5, 0.5]},
            'fixed': {'rotation': [0, 180, 0], 'translation': [0, 0, 0], 'scale': [0.9, 0.9, 0.9]},
            'thirdperson_righthand': {'rotation': [0, 0, 0], 'translation': [0, 3, 1], 'scale': [0.5, 0.5, 0.5]},
            'thirdperson_lefthand': {'rotation': [0, 0, 0], 'translation': [0, 3, 1], 'scale': [0.5, 0.5, 0.5]},
            'firstperson_righthand': {'rotation': [0, -90, 25], 'translation': [1.13, 3.2, 1.13], 'scale': [0.68, 0.68, 0.68]},
            'firstperson_lefthand': {'rotation': [0, 90, -25], 'translation': [1.13, 3.2, 1.13], 'scale': [0.68, 0.68, 0.68]},
        },
    }
    with open(os.path.join(assets, 'models', 'item', 'party_glasses.json'), 'w') as fh:
        json.dump(model, fh, indent=1)
