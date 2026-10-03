package dev.newyear.countdown.gacha;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 샴페인을 딴 뒤 이어지는 "쏴아아아" 거품 분사. 터뜨린 순간 한 번이 아니라 1~4초 동안 매 틱,
 * 병을 든 사람이 바라보는 방향(고개를 돌리면 따라간다)으로 거품 줄기를 뿜는다.
 * 줄기에 닿은 불은 꺼지고, 몬스터는 밀려나며 둔해지고, 플레이어는 "샴페인 세례"로 잠깐 재생된다.
 */
public final class ChampagneSpray {
    private record Spray(ServerWorld world, int total, float power, int[] age) {}

    private static final Map<UUID, Spray> ACTIVE = new ConcurrentHashMap<>();

    private ChampagneSpray() {}

    public static int durationFor(float power) {
        return 24 + (int) (power * 56);
    }

    public static void start(ServerPlayerEntity p, float power) {
        ACTIVE.put(p.getUuid(), new Spray((ServerWorld) p.getWorld(), durationFor(power), power, new int[]{0}));
    }

    public static void init() {
        ServerTickEvents.END_SERVER_TICK.register(ChampagneSpray::tick);
    }

    private static void tick(MinecraftServer server) {
        Iterator<Map.Entry<UUID, Spray>> it = ACTIVE.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Spray> e = it.next();
            Spray s = e.getValue();
            ServerPlayerEntity p = server.getPlayerManager().getPlayer(e.getKey());
            if (p == null || p.isRemoved() || p.getWorld() != s.world() || s.age()[0] >= s.total()) {
                it.remove();
                continue;
            }
            step(p, s);
            s.age()[0]++;
        }
    }

    private static void step(ServerPlayerEntity p, Spray s) {
        ServerWorld sw = s.world();
        int age = s.age()[0];
        float t = age / (float) s.total();
        float strength = (1f - t * 0.55f) * (0.55f + 0.45f * s.power());   // 끝으로 갈수록 약해진다
        Vec3d look = p.getRotationVec(1.0f);
        Vec3d mouth = p.getEyePos().add(look.multiply(0.75)).add(0, -0.25, 0);

        // 거품 줄기: 속도가 있는 파티클(count 0 이면 오프셋이 곧 속도)을 부채꼴로 여러 줄 쏜다
        int jets = 6 + (int) (strength * 8);
        double speed = 0.45 + 0.55 * strength;
        for (int i = 0; i < jets; i++) {
            double sp = 0.10 + 0.05 * (i % 3);
            Vec3d d = look.add((sw.random.nextDouble() - 0.5) * sp * 2, (sw.random.nextDouble() - 0.5) * sp * 2, (sw.random.nextDouble() - 0.5) * sp * 2);
            sw.spawnParticles(ParticleTypes.CLOUD, mouth.x, mouth.y, mouth.z, 0, d.x, d.y, d.z, speed);
            if (i % 2 == 0) {
                sw.spawnParticles(ParticleTypes.SPLASH, mouth.x, mouth.y, mouth.z, 0, d.x, d.y + 0.03, d.z, speed * 1.1);
            }
            if (i % 3 == 0) {
                sw.spawnParticles(ParticleTypes.BUBBLE_POP, mouth.x, mouth.y, mouth.z, 0, d.x, d.y, d.z, speed * 0.8);
            }
        }
        if (age % 4 == 0) {
            sw.spawnParticles(ParticleTypes.FIREWORK, mouth.x, mouth.y, mouth.z, 0, look.x, look.y, look.z, 0.25 + 0.3 * strength);
        }
        // 쏴아아 소리
        if (age % 3 == 0) {
            sw.playSound(null, mouth.x, mouth.y, mouth.z, SoundEvents.BLOCK_FIRE_EXTINGUISH, SoundCategory.PLAYERS, 0.55f, 1.35f + sw.random.nextFloat() * 0.3f);
        }
        if (age % 6 == 2) {
            sw.playSound(null, mouth.x, mouth.y, mouth.z, SoundEvents.ITEM_BOTTLE_EMPTY, SoundCategory.PLAYERS, 0.4f, 0.6f);
        }

        // 분사 반동: 뒤로 살짝 밀린다 (수평 위주)
        if (age < 12) {
            double k = 0.045 * strength;
            p.addVelocity(-look.x * k, -look.y * k * 0.3, -look.z * k);
            p.velocityModified = true;
        }

        // 줄기가 닿는 길이만큼 불 끄기 + 피해 없는 밀어내기
        double reach = 3.0 + 4.0 * strength;
        for (double d = 0.8; d <= reach; d += 0.6) {
            Vec3d at = mouth.add(look.multiply(d));
            BlockPos bp = BlockPos.ofFloored(at);
            if (sw.getBlockState(bp).isOf(Blocks.FIRE) || sw.getBlockState(bp).isOf(Blocks.SOUL_FIRE)) {
                sw.removeBlock(bp, false);
                sw.playSound(null, bp, SoundEvents.BLOCK_FIRE_EXTINGUISH, SoundCategory.BLOCKS, 0.6f, 1.6f);
            }
        }
        Vec3d end = mouth.add(look.multiply(reach));
        Box box = new Box(mouth, end).expand(1.4);
        for (Entity en : sw.getOtherEntities(p, box)) {
            if (!(en instanceof LivingEntity le)) continue;
            Vec3d to = le.getPos().add(0, le.getHeight() / 2, 0).subtract(mouth);
            double dist = to.length();
            if (dist > reach + 0.5 || dist < 0.1 || to.normalize().dotProduct(look) < 0.82) continue;   // 부채꼴 안만
            le.extinguish();
            if (le instanceof PlayerEntity pl) {
                if (age % 10 == 0) pl.addStatusEffect(new StatusEffectInstance(StatusEffects.REGENERATION, 20 * 4, 0));
            } else {
                le.addVelocity(look.x * 0.18 * strength, 0.03, look.z * 0.18 * strength);
                le.velocityModified = true;
                if (le instanceof HostileEntity && age % 8 == 0) {
                    le.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, 20 * 3, 1));
                }
            }
        }
        if (age == s.total() - 1) {
            sw.playSound(null, mouth.x, mouth.y, mouth.z, SoundEvents.ENTITY_FIREWORK_ROCKET_TWINKLE, SoundCategory.PLAYERS, 0.5f, 1.2f);
        }
    }
}
