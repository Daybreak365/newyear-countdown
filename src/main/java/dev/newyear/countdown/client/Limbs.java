package dev.newyear.countdown.client;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;

/**
 * 플레이어/갑옷 모델의 팔다리를 위(허벅지·위팔)와 아래(정강이·아래팔) 두 마디로 나눈다.
 * 아래 마디는 위 마디의 자식이라, 굽히지 않으면(xRot 0) 원래 한 덩어리 팔다리와 똑같이 보인다.
 * 소매·바지 겉층도 같은 방식으로 나눈다(겉층은 팔다리의 자식, 겉층의 아래 마디는 겉층의 자식).
 * 텍스처는 상자 UV 그대로 위 6줄 / 아래 6줄로 나뉘고, 부풀림은 이음매에서 겹치지 않게 위아래로 나눠 준다.
 */
public final class Limbs {
    public static final String LOWER = "newyear_lower";
    private static final String[] OVERLAYS = {"right_sleeve", "left_sleeve", "right_pants", "left_pants"};
    private static final Map<ModelPart, List<ModelPart>> CACHE = new WeakHashMap<>();

    private Limbs() {}

    /** 이 팔다리(와 그 겉층)의 아래 마디들. 나누지 않은 모델이면 빈 목록. */
    private static List<ModelPart> lowers(ModelPart part) {
        return CACHE.computeIfAbsent(part, p -> {
            List<ModelPart> out = new ArrayList<>(2);
            if (p.hasChild(LOWER)) out.add(p.getChild(LOWER));
            for (String o : OVERLAYS) {
                if (p.hasChild(o) && p.getChild(o).hasChild(LOWER)) out.add(p.getChild(o).getChild(LOWER));
            }
            return out;
        });
    }

    /** 아래 마디(정강이·아래팔)를 위 마디 기준으로 굽힌다. */
    public static void bend(ModelPart part, float xRot) {
        for (ModelPart l : lowers(part)) {
            l.xRot = xRot;
            l.yRot = 0f;
            l.zRot = 0f;
        }
    }

    /** 첫 번째 아래 마디 (손 위치 계산용). */
    public static ModelPart lower(ModelPart part) {
        List<ModelPart> l = lowers(part);
        return l.isEmpty() ? null : l.get(0);
    }

    /**
     * 길이 12, 단면 w x 4 인 상자를 parent 아래 name 으로 둘로 나눠 다시 붙인다. 기존 자식은 사라지므로 겉층은 따로 다시 만든다.
     * @param top 상자 윗면 y (다리 0, 팔 -2). 이음매는 top + 6.
     */
    public static PartDefinition split(PartDefinition parent, String name, int u, int v, boolean mirror,
                                       float x0, float top, float w, float dil, PartPose pose) {
        CubeDeformation half = new CubeDeformation(dil, dil / 2f, dil);
        PartDefinition upper = parent.addOrReplaceChild(name,
                CubeListBuilder.create().texOffs(u, v).mirror(mirror).addBox(x0, top - dil / 2f, -2f, w, 6f, 4f, half), pose);
        upper.addOrReplaceChild(LOWER,
                CubeListBuilder.create().texOffs(u, v + 6).mirror(mirror).addBox(x0, dil / 2f, -2f, w, 6f, 4f, half),
                PartPose.offset(0f, top + 6f, 0f));
        return upper;
    }

    /** CubeDeformation 의 반지름 (필드가 패키지 전용이라 리플렉션으로 읽는다). 축마다 다르거나 못 읽으면 NaN. */
    public static float radius(CubeDeformation d) {
        try {
            float r = Float.NaN;
            int n = 0;
            for (Field f : CubeDeformation.class.getDeclaredFields()) {
                if (f.getType() != float.class || Modifier.isStatic(f.getModifiers())) continue;
                f.setAccessible(true);
                float x = f.getFloat(d);
                if (n++ == 0) r = x;
                else if (Math.abs(x - r) > 1e-4f) return Float.NaN;
            }
            return n == 3 ? r : Float.NaN;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return Float.NaN;
        }
    }

    /** PlayerModel.createMesh 결과의 팔·다리와 소매·바지를 나눈다. */
    public static void splitPlayer(MeshDefinition mesh, CubeDeformation scale, boolean slim) {
        float d = radius(scale);
        if (Float.isNaN(d)) return;
        float o = d + 0.25f;
        PartDefinition root = mesh.getRoot();
        PartDefinition rl = split(root, "right_leg", 0, 16, false, -2f, 0f, 4f, d, PartPose.offset(-1.9f, 12f, 0f));
        split(rl, "right_pants", 0, 32, false, -2f, 0f, 4f, o, PartPose.ZERO);
        PartDefinition ll = split(root, "left_leg", 16, 48, false, -2f, 0f, 4f, d, PartPose.offset(1.9f, 12f, 0f));
        split(ll, "left_pants", 0, 48, false, -2f, 0f, 4f, o, PartPose.ZERO);
        float w = slim ? 3f : 4f, rx = slim ? -2f : -3f;
        PartDefinition ra = split(root, "right_arm", 40, 16, false, rx, -2f, w, d, PartPose.offset(-5f, 2f, 0f));
        split(ra, "right_sleeve", 40, 32, false, rx, -2f, w, o, PartPose.ZERO);
        PartDefinition la = split(root, "left_arm", 32, 48, false, -1f, -2f, w, d, PartPose.offset(5f, 2f, 0f));
        split(la, "left_sleeve", 48, 48, false, -1f, -2f, w, o, PartPose.ZERO);
    }

    /** 어른 갑옷 메시(가슴: 팔, 레깅스·부츠: 다리)를 나눈다. 슬롯별로 남은 부위에만 상자를 만든다. */
    public static void splitArmor(MeshDefinition chest, MeshDefinition legs, MeshDefinition feet, CubeDeformation inner, CubeDeformation outer) {
        float di = radius(inner), dout = radius(outer);
        if (Float.isNaN(di) || Float.isNaN(dout)) return;
        PartDefinition c = chest.getRoot();
        split(c, "right_arm", 40, 16, false, -3f, -2f, 4f, dout, PartPose.offset(-5f, 2f, 0f));
        split(c, "left_arm", 40, 16, true, -1f, -2f, 4f, dout, PartPose.offset(5f, 2f, 0f));
        for (Object[] m : new Object[][]{{legs, di}, {feet, dout}}) {
            PartDefinition r = ((MeshDefinition) m[0]).getRoot();
            float dl = (float) m[1] - 0.1f;
            split(r, "right_leg", 0, 16, false, -2f, 0f, 4f, dl, PartPose.offset(-1.9f, 12f, 0f));
            split(r, "left_leg", 0, 16, true, -2f, 0f, 4f, dl, PartPose.offset(1.9f, 12f, 0f));
        }
    }
}
