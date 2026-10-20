"""카운트다운 째깍 소리 합성 (기계식 탁상시계의 탈진기 소리).

실제 시계의 '째깍' 한 번은 수 ms 간격으로 겹치는 2~3번의 금속 충돌(앵커가 탈진 바퀴 이빨을 잡고 놓는 소리)과
그 진동을 받아 울리는 나무/금속 케이스의 짧은 공명으로 이루어진다. 이를 감쇠 사인파(금속 고유 진동) +
대역 통과 잡음(충돌의 거친 성분) + 케이스 공명 + 짧은 초기 반사음으로 만든다.
tick(째) 과 tock(깍) 은 충돌 간격·음높이·케이스 울림을 조금씩 달리한다.

    python3 tools/sounds/clock.py     # src/main/resources/assets/newyearcountdown/sounds/clock_{tick,tock}.ogg (ffmpeg 필요)
"""
import os
import subprocess
import wave
import numpy as np

SR = 44100
HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.abspath(os.path.join(HERE, '..', '..', 'src', 'main', 'resources', 'assets', 'newyearcountdown', 'sounds'))


def bandnoise(n, lo, hi, rng):
    x = rng.standard_normal(n)
    X = np.fft.rfft(x)
    f = np.fft.rfftfreq(n, 1 / SR)
    X[(f < lo) | (f > hi)] = 0
    # 경계를 부드럽게
    X *= np.clip((f - lo) / 300.0, 0, 1) * np.clip((hi - f) / 600.0, 0, 1)
    y = np.fft.irfft(X, n)
    return y / (np.abs(y).max() + 1e-9)


def click(n, start, amp, modes, noise_band, noise_decay, rng):
    """한 번의 금속 충돌: start(s) 부터 감쇠 사인파 묶음 + 짧은 잡음."""
    out = np.zeros(n)
    i0 = int(start * SR)
    t = np.arange(n - i0) / SR
    for f, decay, a in modes:
        ph = rng.uniform(0, 2 * np.pi)
        out[i0:] += a * np.sin(2 * np.pi * f * t + ph) * np.exp(-t / decay)
    nz = bandnoise(n - i0, noise_band[0], noise_band[1], rng)
    out[i0:] += 0.9 * nz * np.exp(-t / noise_decay)
    # 아주 짧은 어택(0.15ms)으로 딱딱하지만 찢어지지 않게
    att = np.clip(t / 0.00015, 0, 1)
    out[i0:] *= att
    return out * amp


def make(kind, seed):
    rng = np.random.default_rng(seed)
    n = int(SR * 0.32)
    k = 1.0 if kind == 'tick' else 0.86            # tock 은 전체적으로 조금 낮다
    metal = [(3150 * k, 0.0055, 1.0), (4720 * k, 0.0035, 0.6), (2270 * k, 0.0085, 0.55), (6900 * k, 0.0022, 0.35), (8400 * k, 0.0015, 0.2)]
    # 탈진기: 큰 충돌 → 4~7ms 뒤 작은 충돌 → 15~20ms 뒤 더 작은 '떨어짐'
    hits = [(0.000, 1.0), (0.0055 if kind == 'tick' else 0.0068, 0.42), (0.0165 if kind == 'tick' else 0.0195, 0.22)]
    y = np.zeros(n)
    for start, amp in hits:
        jitter = [(f * rng.uniform(0.985, 1.015), d, a) for f, d, a in metal]
        y += click(n, start, amp, jitter, (1800, 11000), 0.0018, rng)
    # 케이스(나무 상자) 공명: 낮고 둥근 울림
    t = np.arange(n) / SR
    case = (0.32 * np.sin(2 * np.pi * (640 * k) * t) * np.exp(-t / 0.028)
            + 0.20 * np.sin(2 * np.pi * (1180 * k) * t + 1.0) * np.exp(-t / 0.018)
            + 0.12 * np.sin(2 * np.pi * (320 * k) * t + 0.4) * np.exp(-t / 0.045))
    case *= np.clip(t / 0.0008, 0, 1)
    y += case
    # 방 안의 짧은 초기 반사음 + 희미한 잔향
    dry = y.copy()
    for d, g in ((0.009, 0.22), (0.014, 0.16), (0.023, 0.11), (0.037, 0.07)):
        i = int(d * SR)
        y[i:] += g * dry[:-i]
    tail = bandnoise(n, 300, 6000, rng) * np.exp(-t / 0.06) * 0.025 * np.clip(t / 0.01, 0, 1)
    y += tail
    # 부드러운 끝처리 + 정규화
    fade = np.ones(n)
    fl = int(0.08 * SR)
    fade[-fl:] = np.linspace(1, 0, fl) ** 2
    y *= fade
    y = y / np.abs(y).max() * 0.85
    return y


def write_ogg(y, path):
    wav = path[:-4] + '.wav'
    with wave.open(wav, 'wb') as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(SR)
        w.writeframes((np.clip(y, -1, 1) * 32767).astype(np.int16).tobytes())
    subprocess.run(['ffmpeg', '-y', '-loglevel', 'error', '-i', wav, '-c:a', 'libvorbis', '-q:a', '7', path], check=True)
    os.remove(wav)


if __name__ == '__main__':
    write_ogg(make('tick', 2027), os.path.join(OUT, 'clock_tick.ogg'))
    write_ogg(make('tock', 1231), os.path.join(OUT, 'clock_tock.ogg'))
    print('ok', OUT)
