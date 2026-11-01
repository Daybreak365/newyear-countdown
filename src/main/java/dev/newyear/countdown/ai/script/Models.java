package dev.newyear.countdown.ai.script;

import com.mojang.math.Transformation;
import dev.newyear.countdown.ai.AiConfig;
import dev.newyear.countdown.ai.WishEngine;
import dev.newyear.countdown.ai.script.api.Args;
import dev.newyear.countdown.ai.script.api.EntityRef;
import dev.newyear.countdown.ai.script.api.McUtil;
import dev.newyear.countdown.ai.script.lang.Interpreter;
import dev.newyear.countdown.ai.script.lang.ScriptError;
import dev.newyear.countdown.ai.script.lang.Values.JsArray;
import dev.newyear.countdown.ai.script.lang.Values.JsObject;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.UUID;

/**
 * 절차적 3D 모델: 블록·아이템·글자·AI 텍스처 디스플레이 엔티티 여러 개를 한 덩어리로 묶는다.
 * 모든 부품은 모델 기준점 한 곳에 서 있고, 각자의 변환(위치·회전·크기)으로 모양을 만든다 —
 * 그래서 모델 전체를 옮기면 부품을 모두 같은 곳으로 순간이동하고, 돌리거나 키우면 변환만 바꾼다(클라이언트가 부드럽게 보간).
 * 바닐라 엔티티라 접속한 모든 플레이어가 같은 모습을 보고, 엔티티이므로 세계와 함께 저장된다.
 */
public final class Models {
    private Models() {}

    /** 부품 하나의 최종 변환: 모델 회전·크기 → 부품 위치 → 부품 회전·크기. 블록은 가운데를 기준으로 돌도록 반 칸 당긴다. */
    public static Transformation transform(ScriptRecord.ModelSpec m, ScriptRecord.ModelSpec.Part p) {
        Quaternionf yaw = new Quaternionf().rotateY((float) Math.toRadians(-m.yaw));
        Quaternionf partRot = new Quaternionf().rotateXYZ((float) Math.toRadians(p.rx), (float) Math.toRadians(p.ry), (float) Math.toRadians(p.rz));
        Quaternionf left = new Quaternionf(yaw).mul(partRot);
        Vector3f scale = new Vector3f(p.sx * m.scale, p.sy * m.scale, p.sz * m.scale);
        Vector3f t = yaw.transform(new Vector3f(p.x * m.scale, p.y * m.scale, p.z * m.scale));
        if ("block".equals(p.kind)) {
            Vector3f half = new Vector3f(scale).mul(0.5f);
            t.sub(left.transform(half));
        }
        return new Transformation(t, left, scale, null);
    }

    private static EntityType<?> typeOf(String kind) {
        return switch (kind) {
            case "block" -> EntityTypes.BLOCK_DISPLAY;
            case "text" -> EntityTypes.TEXT_DISPLAY;
            default -> EntityTypes.ITEM_DISPLAY;
        };
    }

    /** 명세 → 모델 생성 (모의 실행이면 검사만). */
    public static ScriptRecord.ModelSpec create(ScriptInstance inst, Interpreter in, double[] origin, JsArray parts, JsObject opts) {
        AiConfig cfg = AiConfig.get();
        if (parts.list.isEmpty()) throw in.error("a model needs at least one part");
        if (parts.list.size() > cfg.modelMaxParts) throw in.error("too many parts (max " + cfg.modelMaxParts + ")");
        if (inst.rec.models.size() >= cfg.scriptMaxModels) throw new ScriptError(ScriptError.Kind.BUDGET, "Too many models in this script (max " + cfg.scriptMaxModels + "); remove one first", 0, 0);
        Policy.checkRange(in, inst, origin);
        ScriptRecord.ModelSpec m = new ScriptRecord.ModelSpec();
        m.id = "m" + (++inst.seq);
        m.dimension = Args.optStr(opts, "dimension", inst.rec.dimension);
        m.origin = origin.clone();
        m.yaw = (float) Args.optNum(opts, "yaw", 0);
        m.scale = (float) Math.max(0.05, Math.min(8, Args.optNum(opts, "scale", 1)));
        for (Object o : parts.list) {
            if (!(o instanceof JsObject po)) throw in.error("each part must be an object like {block:'oak_planks', x:0, y:1, z:0}");
            ScriptRecord.ModelSpec.Part p = new ScriptRecord.ModelSpec.Part();
            if (po.get("block") instanceof String b) {
                p.kind = "block";
                p.block = McUtil.blockString(McUtil.block(in, b));
            } else if (po.get("text") instanceof String tx) {
                p.kind = "text";
                p.text = McUtil.clean(tx, 120);
            } else {
                p.kind = "item";
                p.item = Args.optStr(po, "item", "minecraft:paper");
                if (po.get("texture") instanceof String tex) p.text = Textures.normalize(tex);   // 아이템 부품에 입힐 AI 텍스처
                McUtil.item(in, p.item);
            }
            p.x = (float) Args.optNum(po, "x", 0);
            p.y = (float) Args.optNum(po, "y", 0);
            p.z = (float) Args.optNum(po, "z", 0);
            float s = (float) Args.optNum(po, "scale", 1);
            p.sx = (float) clampScale(Args.optNum(po, "sx", s));
            p.sy = (float) clampScale(Args.optNum(po, "sy", s));
            p.sz = (float) clampScale(Args.optNum(po, "sz", s));
            p.rx = (float) Args.optNum(po, "rx", 0);
            p.ry = (float) Args.optNum(po, "ry", 0);
            p.rz = (float) Args.optNum(po, "rz", 0);
            if (Math.abs(p.x) > 16 || Math.abs(p.y) > 16 || Math.abs(p.z) > 16) throw in.error("part offsets must stay within 16 blocks of the model origin");
            m.parts.add(p);
        }
        if (inst.dry) {
            inst.effect("model " + m.parts.size() + " parts at " + (int) origin[0] + "," + (int) origin[1] + "," + (int) origin[2]);
            return m;
        }
        WishEngine eng = WishEngine.get();
        if (eng.scripts().totalEntities() + m.parts.size() > cfg.globalMaxScriptEntities * 3) throw new ScriptError(ScriptError.Kind.PERMISSION, "Server-wide script entity limit reached", 0, 0);
        ServerLevel l = McUtil.level(eng.server(), m.dimension);
        if (l == null || !l.isLoaded(McUtil.blockPos(origin))) throw new ScriptError(ScriptError.Kind.PERMISSION, "Model position is not loaded", 0, 0);
        boolean keep = Args.optBool(opts, "keep", inst.rec.keepEntitiesDefault);
        boolean glow = Args.optBool(opts, "glow", false);
        for (ScriptRecord.ModelSpec.Part p : m.parts) {
            Entity e = typeOf(p.kind).create(l, EntitySpawnReason.COMMAND);
            if (!(e instanceof Display d)) throw in.error("cannot create display part");
            d.snapTo(origin[0], origin[1], origin[2], 0f, 0f);
            switch (d) {
                case Display.BlockDisplay bd -> bd.setBlockState(McUtil.parseBlockString(p.block));
                case Display.TextDisplay td -> {
                    td.setText(McUtil.text(p.text, "#f3d98a"));
                    td.setBillboardConstraints(Display.BillboardConstraints.CENTER);
                    td.setBackgroundColor(0x40000000);
                }
                case Display.ItemDisplay id -> {
                    JsObject io = new JsObject();
                    if (p.text != null) io.put("texture", p.text);
                    id.setItemStack(McUtil.makeStack(in, p.item, 1, io, inst.rec.id));
                }
                default -> { }
            }
            d.setTransformation(transform(m, p));
            d.setViewRange(1.5f);
            if (glow) d.setGlowingTag(true);
            d.addTag("wishai");
            d.addTag("wishai_" + inst.rec.id);
            l.addFreshEntity(d);
            p.entity = d.getUUID().toString();
            inst.rec.entities.add(p.entity);
            if (keep) inst.rec.keptEntities.add(p.entity);
        }
        inst.rec.models.add(m);
        double secs = Args.optNum(opts, "seconds", 0);
        if (secs > 0) for (ScriptRecord.ModelSpec.Part p : m.parts) {
            eng.scripts().scheduleEntityRemoval(inst.rec.id, UUID.fromString(p.entity), (long) (Math.min(3600, secs) * 20));
        }
        return m;
    }

    private static double clampScale(double s) {
        return Math.max(0.01, Math.min(16, s));
    }

    static Display part(ScriptRecord.ModelSpec.Part p) {
        if (p.entity == null) return null;
        try {
            return new EntityRef(UUID.fromString(p.entity), "?").live() instanceof Display d ? d : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** 모든 부품의 변환을 다시 계산한다 (ticks 동안 보간). */
    public static void refresh(ScriptRecord.ModelSpec m, int ticks) {
        for (ScriptRecord.ModelSpec.Part p : m.parts) refreshPart(m, p, ticks);
    }

    public static void refreshPart(ScriptRecord.ModelSpec m, ScriptRecord.ModelSpec.Part p, int ticks) {
        Display d = part(p);
        if (d == null) return;
        d.setTransformationInterpolationDelay(0);
        d.setTransformationInterpolationDuration(Math.max(0, Math.min(200, ticks)));
        d.setTransformation(transform(m, p));
    }

    /** 기준점을 옮긴다 (부품 엔티티를 모두 같은 곳으로; ticks 동안 미끄러지듯). */
    public static void moveTo(ScriptRecord.ModelSpec m, double[] pos, int ticks) {
        m.origin = pos.clone();
        for (ScriptRecord.ModelSpec.Part p : m.parts) {
            Display d = part(p);
            if (d == null) continue;
            d.setPosRotInterpolationDuration(Math.max(0, Math.min(59, ticks)));
            d.teleportTo(pos[0], pos[1], pos[2]);
        }
    }

    public static boolean alive(ScriptRecord.ModelSpec m) {
        for (ScriptRecord.ModelSpec.Part p : m.parts) {
            Display d = part(p);
            if (d != null && d.isAlive()) return true;
        }
        return false;
    }

    public static void remove(ScriptInstance inst, ScriptRecord.ModelSpec m) {
        for (ScriptRecord.ModelSpec.Part p : m.parts) {
            Display d = part(p);
            if (d != null) d.discard();
            if (p.entity != null) {
                inst.rec.entities.remove(p.entity);
                inst.rec.keptEntities.remove(p.entity);
            }
        }
        inst.rec.models.remove(m);
    }
}
