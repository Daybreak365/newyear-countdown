package dev.newyear.countdown.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.newyear.countdown.gacha.YutBoardBlock;
import dev.newyear.countdown.gacha.YutBoardBlockEntity;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.resources.Identifier;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;

/**
 * 윷판 위의 말과 윷가락. 말판은 블록 모델(yut_board)이 그리고, 여기서는 움직이는 것들만 그린다.
 * 던지기: 던지는 자리 위로 가락 4개가 포물선으로 날며 회전하다가 앞/뒷면(평평한 밝은 면/둥근 어두운 면)이 정해져 내려앉는다.
 */
public class YutBoardRenderer implements BlockEntityRenderer<YutBoardBlockEntity, BeState<YutBoardBlockEntity>> {
    private static final Identifier T_FLAT = mc("birch_planks");
    private static final Identifier T_ROUND = mc("dark_oak_planks");
    private static final Identifier T_RED = mc("red_concrete");
    private static final Identifier T_BLUE = mc("blue_concrete");
    private static final Identifier T_WHITE = mc("white_concrete");
    private static final Identifier T_GOLD = mc("gold_block");
    private static final int WHITE = 0xFFFFFFFF;
    private static final int FULL = 0xF000F0;
    private static final float TOP = 2f / 16f;

    public YutBoardRenderer(BlockEntityRendererProvider.Context ctx) {
    }

    private static Identifier mc(String n) {
        return Identifier.withDefaultNamespace("textures/block/" + n + ".png");
    }

    @Override
    public boolean shouldRenderOffScreen() {
        return true;
    }

    @Override
    public BeState<YutBoardBlockEntity> createRenderState() {
        return new BeState<>();
    }

    @Override
    public void extractRenderState(YutBoardBlockEntity be, BeState<YutBoardBlockEntity> state, float partialTicks, Vec3 camera,
                                   ModelFeatureRenderer.@Nullable CrumblingOverlay breakProgress) {
        BlockEntityRenderer.super.extractRenderState(be, state, partialTicks, camera, breakProgress);
        state.be = be;
        state.partialTicks = partialTicks;
    }

    @Override
    public void submit(BeState<YutBoardBlockEntity> state, PoseStack m, SubmitNodeCollector collector, CameraRenderState camera) {
        GeoBuffers buffers = new GeoBuffers();
        render(state.be, state.partialTicks, m, buffers, camera, state.lightCoords);
        buffers.flush(collector);
    }

    private static float ease(float x) {
        x = Mth.clamp(x, 0f, 1f);
        return x * x * (3f - 2f * x);
    }

    private void render(YutBoardBlockEntity be, float tickDelta, PoseStack m, GeoBuffers providers, CameraRenderState camera, int light) {
        Level world = be.getLevel();
        if (world == null) return;
        float time = world.getGameTime() + tickDelta;

        m.pushPose();
        m.translate(0.5, 0, 0.5);
        m.rotate(Axis.YP.rotationDegrees(-be.getBlockState().getValue(YutBoardBlock.FACING).toYRot()));
        BellModel.Painter p = new BellModel.Painter(providers, m, light, 0);

        // 판은 3x3 블록: 판 기준 좌표(0~1)를 블록 단위로 바꾼다
        final float B = 3f;

        // ---- 말 ----
        int selPos = be.selected == YutBoardBlockEntity.SEL_NONE ? Integer.MIN_VALUE : be.selectionPos(be.selected);
        int selTeam = be.selected == YutBoardBlockEntity.SEL_HOME ? be.turn : (be.selected >= 0 && be.selected < 8 ? be.selected / 4 : -1);
        int[] stack = new int[YutBoardBlockEntity.POINTS.length];
        int[] homeN = new int[2], doneN = new int[2];
        boolean homeLit = false;
        for (int i = 0; i < be.pieces.length; i++) {
            int idx = be.pieces[i];
            int team = i / 4;
            float px, pz, y;
            boolean picked;
            float size = 1f;
            if (idx == YutBoardBlockEntity.HOME) {
                // 판 옆 대기 자리 (빨강 왼쪽, 파랑 오른쪽)
                px = ((team == 0 ? 0.085f : 0.915f) - 0.5f) * B;
                pz = (0.10f + homeN[team]++ * 0.115f - 0.5f) * B;
                y = TOP;
                picked = !homeLit && be.selected == YutBoardBlockEntity.SEL_HOME && team == be.turn;
                if (picked) homeLit = true;
            } else if (idx == YutBoardBlockEntity.DONE) {
                // 골인한 말: 대기 자리 아래쪽에 작게 (금빛 머리)
                px = ((team == 0 ? 0.085f : 0.915f) - 0.5f) * B;
                pz = (0.54f + doneN[team]++ * 0.05f - 0.5f) * B;
                y = TOP;
                picked = false;
                size = 0.7f;
            } else if (idx >= 0 && idx < stack.length) {
                px = (YutBoardBlockEntity.POINTS[idx][0] - 0.5f) * B;
                pz = (YutBoardBlockEntity.POINTS[idx][1] - 0.5f) * B;
                int k = stack[idx]++;
                picked = idx == selPos && team == selTeam;
                y = TOP + k * 0.15f;
            } else {
                continue;
            }
            if (picked) y += 0.14f + 0.03f * Mth.sin(time * 0.35f);
            Identifier tex = team == 0 ? T_RED : T_BLUE;
            if (picked) p.lightOverride = FULL;
            float r1 = 0.10f * size, r2 = 0.072f * size, r3 = 0.04f * size;
            p.use(tex, WHITE);
            p.box(px - r1, y, pz - r1, px + r1, y + 0.09f * size, pz + r1);
            p.box(px - r2, y + 0.09f * size, pz - r2, px + r2, y + 0.16f * size, pz + r2);
            boolean done = idx == YutBoardBlockEntity.DONE;
            p.use(picked ? T_WHITE : (done ? T_GOLD : tex), picked ? 0xFFFFF0A0 : (done ? WHITE : 0xFFDDDDDD));
            p.box(px - r3, y + 0.16f * size, pz - r3, px + r3, y + 0.19f * size, pz + r3);
            p.lightOverride = -1;
        }

        // ---- 고른 말이 갈 수 있는 칸 (남은 이동마다 하나) ----
        if (be.selected != YutBoardBlockEntity.SEL_NONE && be.winner < 0) {
            int from = be.selectionPos(be.selected);
            float pulse = 0.5f + 0.5f * Mth.sin(time * 0.3f);
            for (int steps : be.distinctMoves()) {
                int to = YutBoardBlockEntity.destination(from, steps);
                float mx, mz;
                if (to == YutBoardBlockEntity.FIN) {
                    mx = ((be.turn == 0 ? 0.085f : 0.915f) - 0.5f) * B;
                    mz = (0.62f - 0.5f) * B;
                } else {
                    mx = (YutBoardBlockEntity.POINTS[to][0] - 0.5f) * B;
                    mz = (YutBoardBlockEntity.POINTS[to][1] - 0.5f) * B;
                }
                p.lightOverride = FULL;
                p.setTranslucent(true);
                p.use(T_WHITE, ((int) (110 + 100 * pulse) << 24) | 0xFFE066);
                float rr = 0.16f, w = 0.03f, yy = TOP + 0.005f;
                p.box(mx - rr, yy, mz - rr, mx + rr, yy + 0.02f, mz - rr + w);
                p.box(mx - rr, yy, mz + rr - w, mx + rr, yy + 0.02f, mz + rr);
                p.box(mx - rr, yy, mz - rr + w, mx - rr + w, yy + 0.02f, mz + rr - w);
                p.box(mx + rr - w, yy, mz - rr + w, mx + rr, yy + 0.02f, mz + rr - w);
                p.setTranslucent(false);
                p.lightOverride = -1;
                label(providers, m, camera, mx, TOP + 0.55f, mz,
                        Component.translatable("yut.newyearcountdown.name." + KEYS[Math.min(steps, 5)]).withStyle(ChatFormatting.BOLD),
                        0xFFFFE066, 0.022f);
            }
        }

        // ---- 차례·남은 이동 안내 (판 가운데 위) ----
        label(providers, m, camera, 0f, 1.05f, -0.1f, status(be), 0xFFFFFFFF, 0.016f);

        // ---- 윷가락 ----
        if (be.lastResult >= 1) {
            float age = (world.getGameTime() - be.animStart) + tickDelta;
            float t = Mth.clamp(age / YutBoardBlockEntity.THROW_TICKS, 0f, 1f);
            boolean flying = age >= 0 && age < YutBoardBlockEntity.THROW_TICKS + 8;
            for (int i = 0; i < 4; i++) {
                boolean flatUp = (be.sticks >> i & 1) != 0;
                // 착지 위치/방향: 가락마다 조금씩 다르게(결정적)
                float landX = (i * 7 % 5 - 2) * 0.05f;
                float landZ = (THROW_Z0 + i * 0.05f - 0.5f) * B;
                float yaw = (i * 11 % 5 - 2) * 4f;
                float x = landX, y = TOP + 0.03f, z = landZ, spin = 0f, ys = yaw;
                if (flying && t < 1f) {
                    float u = ease(t);
                    float turns = 1 + ((be.animStart + i * 3) % 3);
                    float arc = 4f * t * (1f - t);
                    x = Mth.lerp(u, (i - 1.5f) * 0.1f, landX);
                    z = Mth.lerp(u, 0.1f, landZ);        // 판 가운데 위쪽에서 던져 앞쪽 자리로
                    y = TOP + 0.03f + 1.1f * arc;
                    spin = (1f - u) * 360f * turns;
                    ys = yaw + (1f - u) * (i % 2 == 0 ? 90f : -70f);
                } else if (flying) {
                    float b = (age - YutBoardBlockEntity.THROW_TICKS) / 8f;   // 착지 후 짧은 통통 튐
                    y += 0.08f * Math.abs(Mth.sin(b * (float) Math.PI * 2f)) * (1f - b);
                }
                m.pushPose();
                m.translate(x, y, z);
                m.rotate(Axis.YP.rotationDegrees(ys));
                m.rotate(Axis.XP.rotationDegrees(spin + (flatUp ? 0f : 180f)));
                // 위쪽 절반 = 평평한 밝은 면, 아래쪽 절반 = 둥근 어두운 면 (뒤집히면 어두운 면이 위)
                p.use(T_ROUND, WHITE);
                p.box(-0.40f, -0.03f, -0.05f, 0.40f, 0f, 0.05f);
                p.use(T_FLAT, WHITE);
                p.box(-0.40f, 0f, -0.05f, 0.40f, 0.03f, 0.05f);
                m.popPose();
            }
        }
        m.popPose();
    }

    private static final String[] KEYS = {"", "do", "gae", "geol", "yut", "mo"};

    /** 지금 상태 한 줄: 누구 차례인지, 남은 이동, 던질 수 있는지. */
    private static Component status(YutBoardBlockEntity be) {
        if (be.winner >= 0) {
            return Component.translatable("yut.newyearcountdown.status.win", team(be.winner)).withStyle(ChatFormatting.GOLD);
        }
        MutableComponent c = Component.translatable("yut.newyearcountdown.status.turn", team(be.turn));
        if (!be.pending.isEmpty()) {
            MutableComponent moves = Component.empty();
            for (int i = 0; i < be.pending.size(); i++) {
                if (i > 0) moves.append(" ");
                moves.append(Component.translatable("yut.newyearcountdown.name." + KEYS[Math.min(be.pending.get(i), 5)]));
            }
            c.append("  ").append(Component.translatable("yut.newyearcountdown.status.moves", moves.withStyle(ChatFormatting.YELLOW)));
        }
        if (be.canThrow) c.append("  ").append(Component.translatable("yut.newyearcountdown.status.throw").withStyle(ChatFormatting.GREEN));
        return c;
    }

    private static Component team(int t) {
        return Component.translatable(t == 0 ? "yut.newyearcountdown.team.red" : "yut.newyearcountdown.team.blue")
                .withStyle(t == 0 ? ChatFormatting.RED : ChatFormatting.AQUA);
    }

    /** 카메라를 향한 글자 (판 좌표계 기준 위치). */
    private static void label(GeoBuffers out, PoseStack m, CameraRenderState camera, float x, float y, float z, Component text, int color, float scale) {
        m.pushPose();
        m.translate(x, y, z);
        m.last().pose().set3x3(new org.joml.Matrix3f());   // 판 회전을 지우고
        m.rotate(camera.orientation);                      // 카메라를 향하게
        m.scale(scale, -scale, scale);
        var font = net.minecraft.client.Minecraft.getInstance().font;
        out.text(m, text, -font.width(text) / 2f, 0f, color, false, 0x60000000, FULL);
        m.popPose();
    }

    /** 던지는 자리 첫 가락의 z (판 기준 0~1). */
    private static final float THROW_Z0 = 0.79f;
}
