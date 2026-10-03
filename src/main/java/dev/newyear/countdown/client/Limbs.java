package dev.newyear.countdown.client;

import net.minecraft.client.model.Dilation;
import net.minecraft.client.model.ModelPart;
import net.minecraft.client.model.ModelPartBuilder;
import net.minecraft.client.model.ModelPartData;
import net.minecraft.client.model.ModelTransform;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.WeakHashMap;

/**
 * 플레이어/갑옷 모델의 팔다리를 위(허벅지·위팔)와 아래(정강이·아래팔) 두 마디로 나눈다.
 * 아래 마디는 위 마디의 자식이라, 굽히지 않으면(pitch 0) 원래 한 덩어리 팔다리와 똑같이 보인다.
 * 텍스처는 상자 UV 그대로 위 6줄 / 아래 6줄로 나뉘고, 부풀림(dilation)은 이음매에서 겹치지 않게 위아래로 나눠 준다.
 */
public final class Limbs {
    public static final String LOWER = "newyear_lower";
    private static final Map<ModelPart, ModelPart> CACHE = new WeakHashMap<>();
    private static final ModelPart NONE = new ModelPart(List.of(), Map.of());

    private Limbs() {}

    /** 아래 마디 (나누지 않은 모델이면 null). */
    public static ModelPart lower(ModelPart part) {
        ModelPart c = CACHE.get(part);
        if (c == null) {
            try {
                c = part.getChild(LOWER);
            } catch (NoSuchElementException e) {
                c = NONE;
            }
            CACHE.put(part, c);
        }
        return c == NONE ? null : c;
    }

    public static void bend(ModelPart part, float pitch) {
        ModelPart l = lower(part);
        if (l != null) {
            l.pitch = pitch;
            l.yaw = 0f;
            l.roll = 0f;
        }
    }

    public static float bendOf(ModelPart part) {
        ModelPart l = lower(part);
        return l == null ? 0f : l.pitch;
    }

    /**
     * 길이 12, 단면 w x 4 인 팔다리를 둘로 나눠 다시 붙인다.
     * @param top 상자 윗면 y (다리 0, 팔 -2). 이음매는 top + 6.
     */
    public static void split(ModelPartData root, String name, int u, int v, boolean mirror,
                             float x0, float top, float w, float dil, float px, float py, float pz) {
        Dilation half = new Dilation(dil, dil / 2f, dil);
        ModelPartData upper = root.addChild(name,
                ModelPartBuilder.create().uv(u, v).mirrored(mirror).cuboid(x0, top - dil / 2f, -2f, w, 6f, 4f, half),
                ModelTransform.pivot(px, py, pz));
        upper.addChild(LOWER,
                ModelPartBuilder.create().uv(u, v + 6).mirrored(mirror).cuboid(x0, dil / 2f, -2f, w, 6f, 4f, half),
                ModelTransform.pivot(0f, top + 6f, 0f));
    }

    /** Dilation 의 반지름 (필드 이름이 매핑마다 달라 리플렉션으로 첫 float 를 읽는다). 못 읽으면 NaN. */
    public static float radius(Dilation d) {
        try {
            List<Field> fs = new ArrayList<>();
            for (Field f : Dilation.class.getDeclaredFields()) {
                if (f.getType() == float.class && !Modifier.isStatic(f.getModifiers())) fs.add(f);
            }
            if (fs.size() != 3) return Float.NaN;
            float r = Float.NaN;
            for (Field f : fs) {
                f.setAccessible(true);
                float x = f.getFloat(d);
                if (Float.isNaN(r)) r = x;
                else if (Math.abs(x - r) > 1e-4f) return Float.NaN;   // 축마다 다르면 건드리지 않음
            }
            return r;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return Float.NaN;
        }
    }

    /** PlayerEntityModel.getTexturedModelData 결과의 팔·다리·소매·바지를 나눈다. */
    public static void splitPlayer(ModelPartData root, Dilation dilation, boolean slim) {
        float d = radius(dilation);
        if (Float.isNaN(d)) return;
        float o = d + 0.25f;
        split(root, "right_leg", 0, 16, false, -2f, 0f, 4f, d, -1.9f, 12f, 0f);
        split(root, "left_leg", 16, 48, false, -2f, 0f, 4f, d, 1.9f, 12f, 0f);
        split(root, "right_pants", 0, 32, false, -2f, 0f, 4f, o, -1.9f, 12f, 0f);
        split(root, "left_pants", 0, 48, false, -2f, 0f, 4f, o, 1.9f, 12f, 0f);
        float w = slim ? 3f : 4f, py = slim ? 2.5f : 2f, rx = slim ? -2f : -3f;
        split(root, "right_arm", 40, 16, false, rx, -2f, w, d, -5f, py, 0f);
        split(root, "left_arm", 32, 48, false, -1f, -2f, w, d, 5f, py, 0f);
        split(root, "right_sleeve", 40, 32, false, rx, -2f, w, o, -5f, py, 0f);
        split(root, "left_sleeve", 48, 48, false, -1f, -2f, w, o, 5f, py, 0f);
    }

    /** ArmorEntityModel.getModelData 결과의 팔·다리를 나눈다. */
    public static void splitArmor(ModelPartData root, Dilation dilation) {
        float d = radius(dilation);
        if (Float.isNaN(d)) return;
        split(root, "right_arm", 40, 16, false, -3f, -2f, 4f, d, -5f, 2f, 0f);
        split(root, "left_arm", 40, 16, true, -1f, -2f, 4f, d, 5f, 2f, 0f);
        split(root, "right_leg", 0, 16, false, -2f, 0f, 4f, d - 0.1f, -1.9f, 12f, 0f);
        split(root, "left_leg", 0, 16, true, -2f, 0f, 4f, d - 0.1f, 1.9f, 12f, 0f);
    }
}
