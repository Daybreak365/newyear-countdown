package dev.newyear.countdown.client;

import dev.newyear.countdown.ModSounds;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;

/** 마지막 30초 동안 HUD의 초 숫자가 바뀌는 순간마다 째깍/째깍 소리를 낸다. */
public final class CountdownTicker {
    private static final int WINDOW_SECONDS = 30;
    private static long lastSec = -1;

    private CountdownTicker() {}

    public static void tick(Minecraft client) {
        if (client.player == null) {
            lastSec = -1;
            return;
        }
        long remMs = ClientCountdown.targetMs() - ClientCountdown.now();
        long remSec = (remMs + 999) / 1000; // HUD에 표시되는 값과 동일
        if (remMs <= 0 || remSec > WINDOW_SECONDS) {
            lastSec = -1;
            return;
        }
        if (remSec == lastSec) return;
        lastSec = remSec;
        if (!ClientConfig.get().tickSound) return;

        // 짝수 초 = 째(tick), 홀수 초 = 깍(tock). 실제 시계 소리를 합성한 음원.
        boolean tick = remSec % 2 == 0;
        float pitch = 0.98f + client.level.getRandom().nextFloat() * 0.04f;   // 미세한 변화로 기계적인 반복감 줄임
        float volume = remSec <= 10 ? 0.95f : 0.6f;                      // 마지막 10초는 더 크게
        client.getSoundManager().play(SimpleSoundInstance.forUI(
                tick ? ModSounds.CLOCK_TICK : ModSounds.CLOCK_TOCK, pitch, volume));
    }
}
