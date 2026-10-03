package dev.newyear.countdown.client;

import dev.newyear.countdown.bell.BellLayout;
import dev.newyear.countdown.bell.ModBlocks;
import dev.newyear.countdown.gacha.GachaBlocks;
import dev.newyear.countdown.gacha.GachaMachineItem;
import dev.newyear.countdown.omikuji.OmikujiBlocks;
import dev.newyear.countdown.omikuji.OmikujiLayout;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.RotationAxis;
import net.minecraft.util.math.Vec3d;

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
    private static int holding(MinecraftClient mc) {
        for (Hand h : Hand.values()) {
            ItemStack s = mc.player.getStackInHand(h);
            if (s.isOf(ModBlocks.BOSINGAK_BELL_ITEM)) return 1;
            if (s.isOf(OmikujiBlocks.OMIKUJI_ITEM)) return 2;
            if (s.isOf(GachaBlocks.GACHA_ITEM)) return 3;
        }
        return 0;
    }

    public static void render(WorldRenderContext ctx) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.world == null || BellSession.isActive()) return;
        int kind = holding(mc);
        if (kind == 0) return;
        if (!(mc.crosshairTarget instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) return;

        BlockPos target = hit.getBlockPos();
        BlockPos place = mc.world.getBlockState(target).isReplaceable() ? target : target.offset(hit.getSide());
        Direction facing = mc.player.getHorizontalFacing().getOpposite();

        long now = mc.world.getTime();
        if (cachePos == null || kind != cacheKind || !cachePos.equals(place) || cacheFacing != facing || now != cacheTick) {
            cacheValid = kind == 1 ? BellLayout.canPlace(mc.world, place, facing)
                    : kind == 2 ? OmikujiLayout.canPlace(mc.world, place, facing) : GachaMachineItem.canPlace(mc.world, place);
            cachePos = place.toImmutable();
            cacheFacing = facing;
            cacheTick = now;
            cacheKind = kind;
        }

        Vec3d cam = ctx.camera().getPos();
        MatrixStack m = ctx.matrixStack();
        VertexConsumerProvider providers = ctx.consumers();
        if (providers == null) return;

        m.push();
        m.translate(place.getX() + 0.5 - cam.x, place.getY() - cam.y, place.getZ() + 0.5 - cam.z);
        m.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(-facing.asRotation()));
        int argb = cacheValid ? OK : BAD;
        if (kind == 1) BellModel.drawGhost(providers, m, argb);
        else if (kind == 2) OmikujiModel.drawGhost(providers, m, argb);
        else GachaRenderer.drawGhost(providers, m, argb);
        m.pop();
    }
}
