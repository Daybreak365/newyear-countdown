"""GUI 아이콘 (16x16 픽셀아트): 인벤토리 오미쿠지 보관함 칸의 오미쿠지 통."""
import os
from goods_2d import Sprite, GOLD, GOLD2


def omikuji_box():
    s = Sprite()
    s.rect(4, 6, 11, 15, '#c0281e')                 # 팔각 뽑기통 몸통 (붉은 옻칠)
    s.rect(4, 6, 5, 15, '#8e1c15', False)           # 왼쪽 옆면(어두운 면)
    s.rect(10, 6, 11, 15, '#a52219', False)         # 오른쪽 옆면
    s.rect(4, 7, 11, 7, GOLD, False)                # 금띠
    s.rect(4, 14, 11, 14, GOLD, False)
    s.rect(5, 5, 10, 5, '#4b3426', False)           # 뚜껑
    s.dot(7, 5, '#1a1410'); s.dot(8, 5, '#1a1410')  # 막대 구멍
    s.line(8, 4.6, 10.6, 0.2, '#e4c789', 1)         # 튀어나온 막대
    s.dot(10, 0, '#c8352c'); s.dot(11, 0, '#c8352c')   # 막대 끝 붉은 칠
    s.rect(6, 9, 9, 12, '#f3e6c0', False)           # 앞면 종이 표찰
    s.dot(7, 10, '#3a2614'); s.dot(7, 11, '#3a2614'); s.dot(8, 10, '#3a2614')
    # 막대에 묶인 접힌 운세 종이
    s.rect(12, 2, 14, 6, '#fbf6e6', False)
    s.rect(12, 3, 14, 3, '#c8352c', False)
    s.dot(13, 5, '#8a7656')
    return s.render()


def write(assets):
    d = os.path.join(assets, 'textures', 'gui')
    os.makedirs(d, exist_ok=True)
    omikuji_box().save(os.path.join(d, 'omikuji_slot.png'))


if __name__ == '__main__':
    omikuji_box().resize((256, 256), 0).save('/tmp/claude-0/s/omikuji_icon.png')
