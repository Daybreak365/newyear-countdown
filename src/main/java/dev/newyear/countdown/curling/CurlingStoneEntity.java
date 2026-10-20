package dev.newyear.countdown.curling;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.Vec3;

/**
 * 컬링 스톤. 얼음 위에서 미끄러지며 회전 방향 쪽으로 휘고(속도가 느려질수록 더 많이), 스톤끼리 부딪히면 튕겨 나간다.
 * 앞에서 브룸으로 쓸면 덜 느려지고 덜 휜다. 물리는 서버가 계산하고 위치는 매 틱 동기화된다.
 * 우클릭하거나 때리면 집어 든다.
 */
public class CurlingStoneEntity extends Entity {
    public static final float RADIUS = 0.3f;
    private static final EntityDataAccessor<Integer> TEAM = SynchedEntityData.defineId(CurlingStoneEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Float> SPIN = SynchedEntityData.defineId(CurlingStoneEntity.class, EntityDataSerializers.FLOAT);
    /** 휘는 정도. */
    private static final double CURL = 0.0008;

    private int sweepTicks;
    private java.util.@org.jspecify.annotations.Nullable UUID thrower;
    private int slideSoundCooldown;
    /** 클라이언트 표시용 누적 회전 각(도). */
    public float spinAngle, prevSpinAngle;

    public CurlingStoneEntity(EntityType<? extends CurlingStoneEntity> type, Level level) {
        super(type, level);
    }

    public int team() { return entityData.get(TEAM); }
    public float spin() { return entityData.get(SPIN); }

    public java.util.@org.jspecify.annotations.Nullable UUID thrower() { return thrower; }

    public void setThrower(java.util.UUID id) { thrower = id; }

    /** 블록 마찰에 따른 틱당 속도 유지율: 얼음 0.985, 파란 얼음 0.992, 보통 블록 0.7. 쓸면 조금 더 유지. */
    public static double keep(double blockFriction, boolean swept) {
        double k = 1.0 - (1.0 - blockFriction) * 0.75;
        return swept ? Math.min(0.997, k + 0.004) : k;
    }

    /** 한 틱 미끄러짐: 회전 방향으로 휘고(느릴수록 많이) 느려진 수평 속도. (예상 경로 표시도 같은 식을 쓴다) */
    public static Vec3 slide(Vec3 v, double blockFriction, float spin, boolean swept) {
        double sp = Math.sqrt(v.x * v.x + v.z * v.z);
        if (sp <= 0) return new Vec3(0, v.y, 0);
        double curl = spin * CURL / (sp + 0.12) * (swept ? 0.45 : 1.0);
        double nx = v.x / sp, nz = v.z / sp;
        double hx = nx + (-nz) * curl, hz = nz + nx * curl;
        double hl = Math.sqrt(hx * hx + hz * hz);
        double nsp = Math.max(0, sp * keep(blockFriction, swept) - 0.0009);
        if (nsp < 0.004) nsp = 0;
        return new Vec3(hx / hl * nsp, v.y, hz / hl * nsp);
    }

    /** 다른 것에 부딪히지 않는다고 칠 때의 미끄러지는 경로 (2틱마다 한 점). */
    public static java.util.List<Vec3> predict(Level level, Vec3 start, Vec3 vel, float spin) {
        java.util.List<Vec3> out = new java.util.ArrayList<>();
        Vec3 pos = start, v = vel;
        for (int t = 0; t < 900; t++) {
            BlockPos below = BlockPos.containing(pos.x, pos.y - 0.3, pos.z);
            if (level.getBlockState(below).getCollisionShape(level, below).isEmpty()) break;     // 떨어짐
            v = slide(v, level.getBlockState(below).getBlock().getFriction(), spin, false);
            Vec3 next = pos.add(v.x, 0, v.z);
            BlockPos at = BlockPos.containing(next.x, next.y + 0.1, next.z);
            if (!level.getBlockState(at).getCollisionShape(level, at).isEmpty()) break;          // 벽
            pos = next;
            if (t % 2 == 0) out.add(pos);
            if (v.x == 0 && v.z == 0) break;
        }
        out.add(pos);
        return out;
    }

    public void setup(int team, float spin) {
        entityData.set(TEAM, team);
        entityData.set(SPIN, spin);
    }

    /** 브룸으로 쓸었다: 몇 틱 동안 덜 느려지고 덜 휜다. */
    public void sweep() {
        sweepTicks = 4;
    }

    public boolean isMoving() {
        Vec3 v = getDeltaMovement();
        return v.x * v.x + v.z * v.z > 1.0e-6 || (!onGround() && v.y < -0.05);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(TEAM, 0);
        builder.define(SPIN, 1f);
    }

    @Override
    public void tick() {
        super.tick();
        Level w = level();
        Vec3 v = getDeltaMovement();
        double sp = Math.sqrt(v.x * v.x + v.z * v.z);
        if (w.isClientSide()) {
            prevSpinAngle = spinAngle;
            double moved = Math.sqrt((getX() - xo) * (getX() - xo) + (getZ() - zo) * (getZ() - zo));
            spinAngle += (float) (spin() * moved * 70.0);
            if (sweepTicks > 0) sweepTicks--;
            return;
        }
        v = v.add(0, -0.04, 0);       // 늘 중력을 줘야 바닥 판정(onGround)이 매 틱 유지된다
        if (onGround() && sp > 0) {
            boolean swept = sweepTicks > 0;
            BlockPos below = getBlockPosBelowThatAffectsMyMovement();
            Vec3 nv = slide(v, w.getBlockState(below).getBlock().getFriction(), spin(), swept);
            double nsp = Math.sqrt(nv.x * nv.x + nv.z * nv.z);
            double nx = v.x / sp, nz = v.z / sp;
            v = nv;
            if (swept && w instanceof ServerLevel sw && tickCount % 2 == 0) {
                sw.sendParticles(ParticleTypes.SNOWFLAKE, getX() + nx * 0.6, getY() + 0.05, getZ() + nz * 0.6, 2, 0.2, 0.02, 0.2, 0.01);
            }
            if (nsp > 0.05 && --slideSoundCooldown <= 0) {
                slideSoundCooldown = 8;
                w.playSound(null, getX(), getY(), getZ(), SoundEvents.POWDER_SNOW_STEP, SoundSource.NEUTRAL, (float) Math.min(0.5, nsp), 0.6f);
            }
        }
        if (sweepTicks > 0) sweepTicks--;
        Vec3 before = v;
        setDeltaMovement(v);
        move(MoverType.SELF, v);
        Vec3 after = getDeltaMovement();
        if (horizontalCollision) {       // 벽에 부딪히면 반쯤 튕긴다
            double bx = Math.abs(after.x) < Math.abs(before.x) * 0.5 ? -before.x * 0.5 : after.x;
            double bz = Math.abs(after.z) < Math.abs(before.z) * 0.5 ? -before.z * 0.5 : after.z;
            setDeltaMovement(bx, after.y, bz);
            w.playSound(null, getX(), getY(), getZ(), SoundEvents.STONE_HIT, SoundSource.NEUTRAL, 0.8f, 0.8f);
        }
        collideStones((ServerLevel) w);
    }

    /** 다른 스톤과의 충돌 (질량이 같은 반탄성 충돌). 번호가 작은 쪽이 한 번만 계산한다. */
    private void collideStones(ServerLevel w) {
        List<CurlingStoneEntity> near = w.getEntitiesOfClass(CurlingStoneEntity.class, getBoundingBox().inflate(0.8), e -> e != this);
        for (CurlingStoneEntity o : near) {
            if (o.getId() < getId() && o.isMoving()) continue;
            double dx = o.getX() - getX(), dz = o.getZ() - getZ();
            double d = Math.sqrt(dx * dx + dz * dz);
            if (d >= RADIUS * 2 || d < 1.0e-6) continue;
            double nx = dx / d, nz = dz / d;
            Vec3 va = getDeltaMovement(), vb = o.getDeltaMovement();
            double rel = (va.x - vb.x) * nx + (va.z - vb.z) * nz;
            double push = (RADIUS * 2 - d) / 2 + 0.001;      // 겹친 만큼 벌린다
            setPos(getX() - nx * push, getY(), getZ() - nz * push);
            o.setPos(o.getX() + nx * push, o.getY(), o.getZ() + nz * push);
            if (rel <= 0) continue;
            double j = rel * 0.95;                            // (1 + 반발계수 0.9) / 2
            setDeltaMovement(va.x - nx * j, va.y, va.z - nz * j);
            o.setDeltaMovement(vb.x + nx * j, vb.y, vb.z + nz * j);
            float vol = (float) Math.min(1.0, rel * 3);
            w.playSound(null, getX(), getY(), getZ(), SoundEvents.STONE_HIT, SoundSource.NEUTRAL, 0.6f + vol, 1.4f);
            w.playSound(null, getX(), getY(), getZ(), SoundEvents.BASALT_HIT, SoundSource.NEUTRAL, vol, 1.0f);
            w.sendParticles(ParticleTypes.CRIT, getX() + nx * RADIUS, getY() + 0.15, getZ() + nz * RADIUS, 4, 0.05, 0.05, 0.05, 0.1);
        }
    }

    private ItemStack asItem() {
        return new ItemStack(team() == 0 ? Curling.STONE_RED : Curling.STONE_YELLOW);
    }

    @Override
    public InteractionResult interact(Player player, InteractionHand hand, Vec3 location) {
        if (level().isClientSide()) return InteractionResult.SUCCESS;
        if (!player.getAbilities().instabuild || !player.getInventory().contains(asItem())) {
            player.getInventory().placeItemBackInInventory(asItem(), net.minecraft.util.Prediction.SERVER_ONLY);
        }
        level().playSound(null, getX(), getY(), getZ(), SoundEvents.STONE_PLACE, SoundSource.NEUTRAL, 0.7f, 1.2f);
        discard();
        return InteractionResult.SUCCESS;
    }

    @Override
    public boolean hurtServer(ServerLevel level, DamageSource source, float damage) {
        if (source.getEntity() instanceof Player p) {
            interact(p, InteractionHand.MAIN_HAND, position());
            return true;
        }
        return false;
    }

    @Override
    public boolean isPickable() {
        return true;
    }

    @Override
    public boolean isPushable() {
        return false;
    }

    @Override
    protected void readAdditionalSaveData(ValueInput in) {
        thrower = in.read("Thrower", net.minecraft.core.UUIDUtil.CODEC).orElse(null);
        entityData.set(TEAM, in.getIntOr("Team", 0));
        entityData.set(SPIN, in.getFloatOr("Spin", 1f));
    }

    @Override
    protected void addAdditionalSaveData(ValueOutput out) {
        out.storeNullable("Thrower", net.minecraft.core.UUIDUtil.CODEC, thrower);
        out.putInt("Team", team());
        out.putFloat("Spin", spin());
    }
}
