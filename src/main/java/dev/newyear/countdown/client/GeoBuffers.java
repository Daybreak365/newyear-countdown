package dev.newyear.countdown.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

/**
 * 즉시 그리기 방식(렌더 타입별 버퍼에 정점을 넣는) 모델 코드를 그대로 쓰기 위한 기록용 버퍼.
 * 정점은 넣는 순간의 변환을 적용한 채 저장하고, flush 에서 렌더 타입마다 한 번씩 SubmitNodeCollector 로 넘긴다.
 */
public final class GeoBuffers {
    private final Map<RenderType, Recorder> recorders = new LinkedHashMap<>();
    private final List<Runnable> pending = new ArrayList<>();
    private final List<TextCmd> texts = new ArrayList<>();

    public VertexConsumer getBuffer(RenderType type) {
        return recorders.computeIfAbsent(type, t -> new Recorder());
    }

    /** 월드 안 글자 (현재 변환 기준). */
    public void text(PoseStack m, Component text, float x, float y, int color, boolean shadow, int background, int light) {
        text(m, text.getVisualOrderText(), x, y, color, shadow, background, light);
    }

    public void text(PoseStack m, FormattedCharSequence text, float x, float y, int color, boolean shadow, int background, int light) {
        PoseStack copy = new PoseStack();
        copy.last().set(m.last());
        texts.add(new TextCmd(copy, text, x, y, color, shadow, background, light));
    }

    public void flush(SubmitNodeCollector collector) {
        PoseStack identity = new PoseStack();
        for (Map.Entry<RenderType, Recorder> e : recorders.entrySet()) {
            Recorder r = e.getValue();
            if (r.count == 0) continue;
            collector.submitCustomGeometry(identity, e.getKey(), (pose, vc) -> r.replay(vc));
        }
        for (TextCmd t : texts) {
            collector.submitText(t.pose, t.x, t.y, t.text, t.shadow, Font.DisplayMode.NORMAL, t.light, t.color, t.background, 0);
        }
        recorders.clear();
        texts.clear();
    }

    private record TextCmd(PoseStack pose, FormattedCharSequence text, float x, float y, int color, boolean shadow, int background, int light) {}

    /** 정점 하나: x y z color u v overlayU overlayV lightU lightV nx ny nz */
    private static final class Recorder implements VertexConsumer {
        private static final int STRIDE = 13;
        float[] data = new float[STRIDE * 64];
        int count;
        int base = -STRIDE;

        private void grow() {
            if ((count + 1) * STRIDE > data.length) data = Arrays.copyOf(data, data.length * 2);
        }

        @Override
        public VertexConsumer addVertex(float x, float y, float z) {
            grow();
            base = count * STRIDE;
            count++;
            data[base] = x;
            data[base + 1] = y;
            data[base + 2] = z;
            data[base + 3] = Float.intBitsToFloat(0xFFFFFFFF);
            data[base + 6] = 0f;      // 기본 오버레이: 없음 (OverlayTexture.NO_OVERLAY = 0, 10)
            data[base + 7] = 10f;
            data[base + 8] = 240f;    // 기본 밝기: 최대
            data[base + 9] = 240f;
            data[base + 10] = 0f;
            data[base + 11] = 1f;
            data[base + 12] = 0f;
            return this;
        }

        @Override
        public VertexConsumer setColor(int r, int g, int b, int a) {
            return setColor((a & 255) << 24 | (r & 255) << 16 | (g & 255) << 8 | (b & 255));
        }

        @Override
        public VertexConsumer setColor(int color) {
            data[base + 3] = Float.intBitsToFloat(color);
            return this;
        }

        @Override
        public VertexConsumer setUv(float u, float v) {
            data[base + 4] = u;
            data[base + 5] = v;
            return this;
        }

        @Override
        public VertexConsumer setUv1(int u, int v) {
            data[base + 6] = u;
            data[base + 7] = v;
            return this;
        }

        @Override
        public VertexConsumer setUv2(int u, int v) {
            data[base + 8] = u;
            data[base + 9] = v;
            return this;
        }

        @Override
        public VertexConsumer setUv3(float u, float v) {
            return this;
        }

        @Override
        public VertexConsumer setNormal(float x, float y, float z) {
            data[base + 10] = x;
            data[base + 11] = y;
            data[base + 12] = z;
            return this;
        }

        @Override
        public VertexConsumer setLineWidth(float width) {
            return this;
        }

        void replay(VertexConsumer vc) {
            for (int i = 0; i < count; i++) {
                int o = i * STRIDE;
                vc.addVertex(data[o], data[o + 1], data[o + 2])
                        .setColor(Float.floatToRawIntBits(data[o + 3]))
                        .setUv(data[o + 4], data[o + 5])
                        .setUv1((int) data[o + 6], (int) data[o + 7])
                        .setUv2((int) data[o + 8], (int) data[o + 9])
                        .setNormal(data[o + 10], data[o + 11], data[o + 12]);
            }
        }
    }
}
