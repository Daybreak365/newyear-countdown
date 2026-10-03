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
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.Arm;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 샴페인을 딴 뒤 이어지는 거품 분사. 1~4초 동안 매 틱
 * 병을 든 사람이 바라보는 방향(고개를 돌리면 따라간다)으로 거품 줄기를 뿜는다.
 * 줄기에 닿은 불은 꺼지고, 몬스터는 밀려나며 둔해지고, 플레이어는 "샴페인 세례"로 잠깐 재생된다.
 */
public final class ChampagneSpray {
    private record Spray(ServerWorld world, int total, float power, int[] age) {}

    /** 바닥/벽에 뿌려져 잠깐 남는 거품 자국. life 가 다하면 사라진다. */
    private record Foam(ServerWorld world, Vec3d pos, int[] life) {}

    private static final Map<UUID, Spray> ACTIVE = new ConcurrentHashMap<>();
    private static final List<Foam> FOAM = new CopyOnWriteArrayList<>();
    private static final int MAX_FOAM = 300;

    /** 병 입구 위치: 눈 앞 + 주로 쓰는 손 쪽으로 치우친 곳(들고 있는 병의 윗부분). */
    public static Vec3d mouth(ServerPlayerEntity p) {
        Vec3d look = p.getRotationVec(1.0f);
        Vec3d right = look.crossProduct(new Vec3d(0, 1, 0));
        right = right.lengthSquared() < 1e-4 ? new Vec3d(1, 0, 0) : right.normalize();
        double side = p.getMainArm() == Arm.RIGHT ? 0.30 : -0.30;
        return p.getEyePos().add(look.multiply(0.8)).add(right.multiply(side)).add(0, -0.16, 0);
    }

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

    private static void tickFoam() {
        for (Foam f : FOAM) {
            int left = --f.life()[0];
            if (left <= 0) {
                FOAM.remove(f);
                continue;
            }
            ServerWorld sw = f.world();
            // 시간이 지날수록 듬성듬성: 거품이 꺼지며(BUBBLE_POP) 사라진다
            if (sw.random.nextInt(60) < left || left > 40) {
                double jx = (sw.random.nextDouble() - 0.5) * 0.7, jz = (sw.random.nextDouble() - 0.5) * 0.7;
                sw.spawnParticles(ParticleTypes.SPIT, f.pos().x + jx, f.pos().y + 0.04, f.pos().z + jz, 1, 0.02, 0.0, 0.02, 0.0);
            }
            if (left % 3 == 0) {
                sw.spawnParticles(ParticleTypes.BUBBLE_POP, f.pos().x, f.pos().y + 0.06, f.pos().z, 1, 0.3, 0.02, 0.3, 0.0);
            }
        }
    }

    private static void tick(MinecraftServer server) {
        tickFoam();
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
        Vec3d mouth = mouth(p);

        // 거품 줄기: 병 입구에서 좁게 시작해 멀어질수록 퍼진다. 속도가 제각각인 하얀 거품 알갱이(SPIT) + 안개(SNOWFLAKE) + 물방울
        int jets = 10 + (int) (strength * 12);
        for (int i = 0; i < jets; i++) {
            double sp = 0.035 + sw.random.nextDouble() * 0.09;
            Vec3d d = look.add((sw.random.nextDouble() - 0.5) * sp * 2, (sw.random.nextDouble() - 0.5) * sp * 2 + 0.02, (sw.random.nextDouble() - 0.5) * sp * 2);
            double speed = (0.35 + 0.75 * strength) * (0.55 + sw.random.nextDouble() * 0.9);
            sw.spawnParticles(ParticleTypes.SPIT, mouth.x, mouth.y, mouth.z, 0, d.x, d.y, d.z, speed);
            if (i % 2 == 0) sw.spawnParticles(ParticleTypes.SNOWFLAKE, mouth.x, mouth.y, mouth.z, 0, d.x, d.y, d.z, speed * 0.85);
            if (i % 4 == 0) sw.spawnParticles(ParticleTypes.SPLASH, mouth.x, mouth.y, mouth.z, 0, d.x, d.y + 0.04, d.z, speed);
            if (i % 5 == 0) sw.spawnParticles(ParticleTypes.BUBBLE_POP, mouth.x, mouth.y, mouth.z, 0, d.x, d.y, d.z, speed * 0.7);
        }
        if (age % 3 == 0) {
            sw.spawnParticles(ParticleTypes.CLOUD, mouth.x, mouth.y, mouth.z, 0, look.x, look.y, look.z, 0.5 + 0.4 * strength);
        }
        if (age < 4) {
            sw.spawnParticles(ParticleTypes.POOF, mouth.x, mouth.y, mouth.z, 4, 0.05, 0.05, 0.05, 0.08);
        }
        // 닿은 곳(벽·바닥, 허공이면 떨어질 바닥)에 거품 자국을 남긴다
        if (age % 2 == 0 && FOAM.size() < MAX_FOAM) {
            double reachFoam = 3.0 + 4.0 * strength;
            Vec3d end = mouth.add(look.multiply(reachFoam));
            BlockHitResult hr = sw.raycast(new RaycastContext(mouth, end, RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, p));
            Vec3d spot = null;
            if (hr.getType() == HitResult.Type.BLOCK) {
                spot = hr.getPos().add(Vec3d.of(hr.getSide().getVector()).multiply(0.03));
            } else {
                BlockHitResult down = sw.raycast(new RaycastContext(end, end.add(0, -6, 0), RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, p));
                if (down.getType() == HitResult.Type.BLOCK) spot = down.getPos().add(0, 0.03, 0);
            }
            if (spot != null) {
                for (int k = 0; k < 2; k++) {
                    Vec3d at = spot.add((sw.random.nextDouble() - 0.5) * 0.9, 0, (sw.random.nextDouble() - 0.5) * 0.9);
                    FOAM.add(new Foam(sw, at, new int[]{50 + sw.random.nextInt(50)}));
                }
            }
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
