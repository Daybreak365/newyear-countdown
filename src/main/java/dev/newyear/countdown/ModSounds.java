package dev.newyear.countdown;

import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.sound.SoundEvent;
import net.minecraft.util.Identifier;

/** 모드 사운드. 실제 오디오는 assets/newyearcountdown/sounds/*.ogg, 정의는 sounds.json. */
public final class ModSounds {
    public static final SoundEvent CLOCK_TICK = register("clock_tick");
    public static final SoundEvent CLOCK_TOCK = register("clock_tock");
    public static final SoundEvent BELL_STRIKE = register("bell_strike");
    public static final SoundEvent OMIKUJI_SHAKE = register("omikuji_shake");
    public static final SoundEvent OMIKUJI_SUZU = register("omikuji_suzu");
    public static final SoundEvent WISH_LAUNCH = register("wish_launch");
    public static final SoundEvent OMIKUJI_WHOOSH = register("omikuji_whoosh");
    public static final SoundEvent OMIKUJI_REVEAL = register("omikuji_reveal");

    private ModSounds() {}

    private static SoundEvent register(String name) {
        Identifier id = Identifier.of(NewYearCountdown.MOD_ID, name);
        return Registry.register(Registries.SOUND_EVENT, id, SoundEvent.of(id));
    }

    /** 클래스 로딩을 강제해 등록을 수행한다. */
    public static void init() {}
}
