package dev.newyear.countdown.wish;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.MovementType;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.entity.data.TrackedData;
import net.minecraft.entity.data.TrackedDataHandlerRegistry;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.particle.DustColorTransitionParticleEffect;
import net.minecraft.particle.ParticleTypes;
import org.joml.Vector3f;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.World;

/**
 * 하늘로 날아가는 소원 연등(풍등).
 * 처음엔 손 위에서 열기로 부풀며 천천히 떠오르고, 점점 빨라지다가 바람을 타고 흔들리며 높이 올라간다.
 * 눈에 보이지 않을 만큼 높이 올라가면 사라진다 (소원 기록은 WishData 에 남는다).
 */
public class WishLanternEntity extends Entity {
    private static final TrackedData<String> WISH = DataTracker.registerData(WishLanternEntity.class, TrackedDataHandlerRegistry.STRING);
    private static final TrackedData<Integer> COLOR = DataTracker.registerData(WishLanternEntity.class, TrackedDataHandlerRegistry.INTEGER);
    private static final TrackedData<Float> START_Y = DataTracker.registerData(WishLanternEntity.class, TrackedDataHandlerRegistry.FLOAT);

    /** 이 높이만큼 올라가면 사라진다 (그 직전 40칸 동안 점점 작아져 보인다). */
    public static final float VANISH_HEIGHT = 190f;
    public static final float FADE_LENGTH = 45f;
    public static final int INFLATE_TICKS = 40;

    public int life;
    private int stuck;
    private double windAngle, windPhase;

    public WishLanternEntity(EntityType<? extends WishLanternEntity> type, World world) {
        super(type, world);
        setNoGravity(true);
        this.noClip = false;
        this.windAngle = (this.getUuid().hashCode() & 1023) / 1024.0 * Math.PI * 2;
        this.windPhase = ((this.getUuid().getLeastSignificantBits() >>> 7) & 1023) / 1024.0 * 100.0;
    }

    public static int randomColor(Random r) {
        // 사진 속 풍등처럼 따뜻한 황금빛이 대부분, 가끔 크림색과 살구빛
        int[] palette = {0xFFC870, 0xFFB45A, 0xFFD27A, 0xFFC870, 0xFFE2A0, 0xFFA848, 0xFFF0D0, 0xFFB890};
        return palette[r.nextInt(palette.length)];
    }

    public void setWish(String text, int rgb) {
        dataTracker.set(WISH, text);
        dataTracker.set(COLOR, rgb);
        dataTracker.set(START_Y, (float) getY());
    }

    /** 발사기에서 나온 연등: 이미 한창 떠오르는 상태로 시작한다. */
    public void fastStart(int ticks) { this.life = ticks; }

    public String getWish() { return dataTracker.get(WISH); }
    public int getColorRgb() { return dataTracker.get(COLOR); }
    public float getStartY() { return dataTracker.get(START_Y); }

    @Override
    protected void initDataTracker(DataTracker.Builder builder) {
        builder.add(WISH, "");
        builder.add(COLOR, 0xFFD870);
        builder.add(START_Y, 0f);
    }

    @Override
    protected void readCustomDataFromNbt(NbtCompound nbt) {
        dataTracker.set(WISH, nbt.getString("Wish"));
        dataTracker.set(COLOR, nbt.contains("Color") ? nbt.getInt("Color") : 0xFFD870);
        dataTracker.set(START_Y, nbt.contains("StartY") ? nbt.getFloat("StartY") : (float) getY());
        life = nbt.getInt("Life");
    }

    @Override
    protected void writeCustomDataToNbt(NbtCompound nbt) {
        nbt.putString("Wish", getWish());
        nbt.putInt("Color", getColorRgb());
        nbt.putFloat("StartY", getStartY());
        nbt.putInt("Life", life);
    }

    @Override
    public boolean isAttackable() { return false; }

    @Override
    public boolean isPushable() { return false; }

    @Override
    public boolean canHit() { return false; }

    @Override
    public void tick() {
        super.tick();
        life++;

        if (getWorld().isClient) {
            clientEffects();
            return;
        }

        // ---- 상승: 손 위에서 열기로 부풀며 가만히 떠 있다가 → 망설이듯 천천히 떠오르고 → 서서히 가속해 일정한 속도에 이른다 ----
        double targetVy;
        if (life < INFLATE_TICKS) {
            targetVy = 0.002 + 0.008 * smooth(life / (double) INFLATE_TICKS);
        } else {
            double k = smooth(Math.min(1.0, (life - INFLATE_TICKS) / 180.0));
            targetVy = 0.010 + 0.042 * k;
            targetVy *= 1.0 + 0.07 * Math.sin((life + windPhase) * 0.045); // 숨 쉬듯 미세하게 오르내리는 속도
        }

        // ---- 바람: 높이 올라갈수록 세지고 방향이 천천히 휘며, 좌우로 살랑이며 나선을 그린다 ----
        double alt = Math.max(0, getY() - getStartY());
        double speed = (life < INFLATE_TICKS ? 0.0 : 0.004) + Math.min(0.040, alt * 0.00026);
        double ang = windAngle + 0.5 * Math.sin((life + windPhase) * 0.011) + 0.0006 * alt;
        double gust = 1.0 + 0.30 * Math.sin((life + windPhase) * 0.05);
        double sideways = 0.006 * Math.sin((life + windPhase) * 0.035) * smooth(Math.min(1.0, life / 120.0));
        double tx = Math.sin(ang) * speed * gust + Math.cos(ang) * sideways;
        double tz = Math.cos(ang) * speed * gust - Math.sin(ang) * sideways;

        Vec3d v = getVelocity();
        double vx = MathHelper.lerp(0.02, v.x, tx);
        double vy = MathHelper.lerp(0.025, v.y, targetVy);
        double vz = MathHelper.lerp(0.02, v.z, tz);
        setVelocity(vx, vy, vz);
        move(MovementType.SELF, getVelocity());

        if (horizontalCollision) { // 벽에 닿으면 살짝 밀려난다
            windAngle += Math.PI * 0.6;
            setVelocity(-vx * 0.5, getVelocity().y, -vz * 0.5);
        }
        if (verticalCollision && getVelocity().y >= -1.0e-4 && vy > 0.005) stuck++; else stuck = Math.max(0, stuck - 1);

        // 천장에 막히거나, 물에 닿거나, 너무 오래 떠 있으면 꺼진다
        if (stuck > 25 || isTouchingWater() || life > 20 * 600) {
            burnOut();
            return;
        }
        // 눈에 보이지 않을 만큼 높이 올라가면 사라진다
        World w = getWorld();
        if (getY() - getStartY() > VANISH_HEIGHT || getY() > w.getTopY() - 1) {
            discard();
        }
    }

    private static double smooth(double t) {
        t = MathHelper.clamp(t, 0.0, 1.0);
        return t * t * (3.0 - 2.0 * t);
    }

    private void burnOut() {
        if (getWorld() instanceof ServerWorld sw) {
            sw.spawnParticles(ParticleTypes.SMOKE, getX(), getY() + 0.6, getZ(), 10, 0.25, 0.3, 0.25, 0.01);
        }
        discard();
    }

    /** 은은한 불씨: 작은 금빛 알갱이가 가끔 천천히 흩날린다 (핏방울처럼 보이는 파티클은 쓰지 않는다). */
    private void clientEffects() {
        World w = getWorld();
        float rise = (float) Math.min(1.0, life / 60.0);
        if (life % 3 == 0) {
            w.addParticle(ParticleTypes.SMALL_FLAME, getX() + (random.nextDouble() - 0.5) * 0.05, getY() + 0.2,
                    getZ() + (random.nextDouble() - 0.5) * 0.05, 0, 0.004, 0);
        }
        if (life % 5 == 0 && random.nextFloat() < 0.8f * rise) {
            w.addParticle(EMBER, getX() + (random.nextDouble() - 0.5) * 0.35, getY() + 0.1 + random.nextDouble() * 0.2,
                    getZ() + (random.nextDouble() - 0.5) * 0.35,
                    (random.nextDouble() - 0.5) * 0.004, 0.006 + random.nextDouble() * 0.006, (random.nextDouble() - 0.5) * 0.004);
        }
    }

    private static final DustColorTransitionParticleEffect EMBER = new DustColorTransitionParticleEffect(
            new Vector3f(1.0f, 0.82f, 0.45f), new Vector3f(1.0f, 0.55f, 0.2f), 0.55f);
}
