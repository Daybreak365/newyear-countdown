# 아이템 3D 모델 생성기

보신각·오미쿠지·소원 연등·가챠 머신 아이템 아이콘과 2027 안경은 **큐보이드 3D 모델(JSON element) + 고해상도 텍스처 아틀라스**입니다.
손으로 픽셀을 찍는 대신 이 도구가 박스 목록에서 모델 JSON 과 텍스처를 함께 만듭니다.

```
pip install pillow numpy
python3 tools/modelgen/build.py             # src/main/resources 에 모델/텍스처 생성
python3 tools/modelgen/build.py --preview   # + /tmp/modelgen_preview/*.png 로 GUI 시점 미리보기
```

- `core.py` — 박스/면 아틀라스 배치, 면 위 모든 픽셀의 3D 위치로 구·원통 조명·광택을 텍스처에 굽는 재질(Mat), JSON 출력
- `lib.py` — 나무결/벽돌/기와/천/금속 재질, 이미지·SDF 오버레이(문자, 달력, 라벨 등)
- `shapes.py` — 정다각 기둥(변마다 얇은 직사각 박스), 회전체(lathe), 구
- `m_*.py` — 모델 정의. `preview.py` — 게임 없이 GUI 렌더를 확인하는 소프트웨어 렌더러
- 상태 변형(`*_open`, `*_lit`, `*_burst`, 좌우 흔들림 `*_a/_b`)은 `build.py` 에서 만들고, 아이템 모델의 `overrides` 가
  클라이언트 predicate(`newyearcountdown:capsule`, `newyearcountdown:fx`, 코드는 `client/ItemAnim.java`)에 따라 고릅니다.

- `capsule_2d.py`, `goods_2d.py`, `yut_board.py` 는 3D 가 아닌 픽셀아트 텍스처(캡슐, 굿즈, 윷판)를 만듭니다.
