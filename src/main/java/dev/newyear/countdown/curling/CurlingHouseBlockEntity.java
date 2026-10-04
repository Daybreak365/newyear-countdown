package dev.newyear.countdown.curling;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

/** 하우스 점수: 가운데(버튼)에 가장 가까운 팀이, 상대의 가장 가까운 스톤보다 안쪽에 있는 자기 스톤 수만큼 득점. */
public class CurlingHouseBlockEntity extends BlockEntity {
    /** 하우스 반지름(칸). 렌더러도 같은 값을 쓴다. */
    public static final float RADIUS = 2.0f;
    private boolean active;

    public CurlingHouseBlockEntity(BlockPos pos, BlockState state) {
        super(Curling.HOUSE_BE, pos, state);
    }

    private double cx() { return worldPosition.getX() + 0.5; }
    private double cz() { return worldPosition.getZ() + 0.5; }

    private List<CurlingStoneEntity> stones(ServerLevel sw, double range) {
        return sw.getEntitiesOfClass(CurlingStoneEntity.class,
                new AABB(worldPosition).inflate(range, 2, range), e -> true);
    }

    public static void tick(Level level, BlockPos pos, BlockState state, CurlingHouseBlockEntity be) {
        if (!(level instanceof ServerLevel sw) || sw.getGameTime() % 5 != 0) return;
        List<CurlingStoneEntity> all = be.stones(sw, 40);
        boolean moving = all.stream().anyMatch(CurlingStoneEntity::isMoving);
        if (moving) {
            be.active = true;
        } else if (be.active) {         // 던진 스톤이 모두 멈췄다: 지금 점수를 알린다
            be.active = false;
            Component msg = be.scoreText(sw);
            for (ServerPlayer p : sw.getPlayers(p -> p.distanceToSqr(be.cx(), pos.getY(), be.cz()) < 48 * 48)) p.sendOverlayMessage(msg);
        }
    }

    /** {팀, 점수} (팀 -1 = 하우스 안에 스톤 없음). */
    public int[] score(ServerLevel sw) {
        List<CurlingStoneEntity> in = new ArrayList<>();
        for (CurlingStoneEntity s : stones(sw, RADIUS + 1)) {
            if (s.isMoving()) continue;
            if (dist(s) <= RADIUS + CurlingStoneEntity.RADIUS) in.add(s);
        }
        if (in.isEmpty()) return new int[]{-1, 0};
        in.sort(Comparator.comparingDouble(this::dist));
        int team = in.get(0).team(), n = 0;
        for (CurlingStoneEntity s : in) {
            if (s.team() != team) break;
            n++;
        }
        return new int[]{team, n};
    }

    private double dist(CurlingStoneEntity s) {
        double dx = s.getX() - cx(), dz = s.getZ() - cz();
        return Math.sqrt(dx * dx + dz * dz);
    }

    private Component scoreText(ServerLevel sw) {
        int[] r = score(sw);
        if (r[0] < 0) return Component.translatable("curling.newyearcountdown.score.none").withStyle(ChatFormatting.GRAY);
        return Component.translatable("curling.newyearcountdown.score", teamName(r[0]), r[1]).withStyle(ChatFormatting.WHITE);
    }

    public static Component teamName(int team) {
        return Component.translatable(team == 0 ? "curling.newyearcountdown.team.red" : "curling.newyearcountdown.team.yellow")
                .withStyle(team == 0 ? ChatFormatting.RED : ChatFormatting.YELLOW);
    }

    public void showScore(ServerPlayer p) {
        if (!(level instanceof ServerLevel sw)) return;
        p.sendSystemMessage(scoreText(sw));
        sw.sendParticles(ParticleTypes.END_ROD, cx(), worldPosition.getY() + 0.2, cz(), 6, 0.2, 0.1, 0.2, 0.02);
    }

    /** 주변(8칸) 스톤을 모두 집어 웅크린 플레이어에게 돌려준다. */
    public void collect(ServerPlayer p) {
        if (!(level instanceof ServerLevel sw)) return;
        int n = 0;
        for (CurlingStoneEntity s : stones(sw, 8)) {
            if (s.isMoving()) continue;
            if (!p.getAbilities().instabuild) {
                p.getInventory().placeItemBackInInventory(new ItemStack(s.team() == 0 ? Curling.STONE_RED : Curling.STONE_YELLOW),
                        net.minecraft.util.Prediction.SERVER_ONLY);
            }
            s.discard();
            n++;
        }
        sw.playSound(null, worldPosition, SoundEvents.STONE_PLACE, SoundSource.BLOCKS, 0.8f, 1.1f);
        p.sendOverlayMessage(Component.translatable("curling.newyearcountdown.collected", n).withStyle(ChatFormatting.GRAY));
    }
}
