package dev.newyear.countdown.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.newyear.countdown.bell.BellLayout;
import dev.newyear.countdown.bell.ModBlocks;
import dev.newyear.countdown.gacha.GachaBlocks;
import dev.newyear.countdown.gacha.GachaMachineItem;
import dev.newyear.countdown.omikuji.OmikujiBlocks;
import dev.newyear.countdown.omikuji.OmikujiLayout;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** 보신각 아이템을 들고 있을 때 설치될 모습을 홀로그램으로 보여준다. 겹치는 블록이 있으면 빨간색. */
public final class BellPreview {
    private static final int OK = 0x80_55E6FF;    // 청록
    private static final int BAD = 0x80_FF3A3A;   // 빨강

    // 유효성 캐시 (매 프레임 수십 칸 검사 방지)
    private static BlockPos cachePos;
    private static Direction cacheFacing;
    private static long cacheTick = -1;
    private static boolean cacheValid;
    private static int cacheKind;

    private BellPreview() {}

    /** 들고 있는 설치 아이템: 0 없음, 1 보신각, 2 오미쿠지 뽑기대, 3 가챠 머신. */
    private static int holding(Minecraft mc) {
        for (InteractionHand h : InteractionHand.values()) {
            ItemStack s = mc.player.getItemInHand(h);
            if (s.is(ModBlocks.BOSINGAK_BELL_ITEM)) return 1;
            if (s.is(OmikujiBlocks.OMIKUJI_ITEM)) return 2;
            if (s.is(GachaBlocks.GACHA_ITEM)) return 3;
        }
        return 0;
    }

    public static void render(LevelRenderContext ctx) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || BellSession.isActive()) return;
        int kind = holding(mc);
        if (kind == 0) return;
        if (!(mc.hitResult instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) return;

        BlockPos target = hit.getBlockPos();
        BlockPos place = mc.level.getBlockState(target).canBeReplaced() ? target : target.relative(hit.getDirection());
        Direction facing = mc.player.getDirection().getOpposite();

        long now = mc.level.getGameTime();
        if (cachePos == null || kind != cacheKind || !cachePos.equals(place) || cacheFacing != facing || now != cacheTick) {
            cacheValid = kind == 1 ? BellLayout.canPlace(mc.level, place, facing)
                    : kind == 2 ? OmikujiLayout.canPlace(mc.level, place, facing) : GachaMachineItem.canPlace(mc.level, place);
            cachePos = place.immutable();
            cacheFacing = facing;
            cacheTick = now;
            cacheKind = kind;
        }

        Vec3 cam = ctx.levelState().cameraRenderState.pos;
        PoseStack m = ctx.poseStack();
        GeoBuffers providers = new GeoBuffers();

        m.pushPose();
        m.translate(place.getX() + 0.5 - cam.x, place.getY() - cam.y, place.getZ() + 0.5 - cam.z);
        m.rotate(Axis.YP.rotationDegrees(-facing.toYRot()));
        int argb = cacheValid ? OK : BAD;
        if (kind == 1) BellModel.drawGhost(providers, m, argb);
        else if (kind == 2) OmikujiModel.drawGhost(providers, m, argb);
        else GachaRenderer.drawGhost(providers, m, argb);
        m.popPose();
        providers.flush(ctx.submitNodeCollector());
    }
}
