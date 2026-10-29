package dev.newyear.countdown.ai.script.api;

import dev.newyear.countdown.ai.WishEngine;
import dev.newyear.countdown.ai.script.Policy;
import dev.newyear.countdown.ai.script.ScriptInstance;
import dev.newyear.countdown.ai.script.ScriptRecord;
import dev.newyear.countdown.ai.script.lang.Interpreter;
import dev.newyear.countdown.ai.script.lang.Values;
import dev.newyear.countdown.ai.script.lang.Values.JsObject;
import dev.newyear.countdown.ai.script.lang.Values.NativeFn;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.AgeableMob;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.player.Player;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import static dev.newyear.countdown.ai.script.lang.Values.UNDEFINED;

/**
 * 스크립트가 보는 엔티티 (플레이어 제외). UUID 로만 붙잡고, 쓸 때마다 월드에서 다시 찾는다.
 * 모의 실행에서 소환한 엔티티는 fake 로 표시되어 아무것도 바꾸지 않는다.
 */
public final class EntityRef implements Args.Positioned {
    public final UUID id;
    public final String type;
    public final boolean fake;
    private final double[] fakePos;

    public EntityRef(UUID id, String type) {
        this(id, type, false, null);
    }

    private EntityRef(UUID id, String type, boolean fake, double[] fakePos) {
        this.id = id;
        this.type = type;
        this.fake = fake;
        this.fakePos = fakePos;
    }

    public static EntityRef fake(String type, double[] pos) {
        return new EntityRef(UUID.randomUUID(), type, true, pos);
    }

    public static EntityRef of(Entity e) {
        return new EntityRef(e.getUUID(), McUtil.entityId(e.getType()));
    }

    public Entity live() {
        if (fake) return null;
        WishEngine eng = WishEngine.get();
        if (eng == null) return null;
        for (ServerLevel l : eng.server().getAllLevels()) {
            Entity e = l.getEntity(id);
            if (e != null) return e;
        }
        return null;
    }

    @Override
    public double[] position() {
        if (fake) return fakePos;
        Entity e = live();
        return e == null ? null : new double[]{e.getX(), e.getY(), e.getZ()};
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof EntityRef r && r.id.equals(id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String typeName() {
        return "Entity";
    }

    @Override
    public Object getMember(Interpreter in, String member) {
        switch (member) {
            case "uuid": return id.toString();
            case "type": return type;
            case "$ref": return "entity:" + id + ":" + type;
            case "name": {
                Entity e = live();
                return e == null ? type : e.getName().getString();
            }
            default: break;
        }
        Values.Callable m = METHODS.get(member);
        return m == null ? UNDEFINED : m;
    }

    @Override
    public Collection<String> memberNames() {
        List<String> all = new java.util.ArrayList<>(List.of("uuid", "type", "name"));
        all.addAll(METHODS.keySet());
        return all;
    }

    private static final Map<String, Values.Callable> METHODS = new LinkedHashMap<>();
    public static final Map<String, String> DOCS = new LinkedHashMap<>();

    private static void def(String name, String doc, NativeFn.Impl impl) {
        METHODS.put(name, new NativeFn("entity." + name, impl));
        DOCS.put(name, doc);
    }

    private static EntityRef self(Interpreter in, Object s) {
        if (s instanceof EntityRef r) return r;
        throw in.error("entity method called without an entity");
    }

    private static Entity need(Interpreter in, Object s) {
        EntityRef r = self(in, s);
        Entity e = r.live();
        if (e == null) throw in.error("That " + r.type + " no longer exists or is unloaded (check entity.exists() first)");
        return e;
    }

    private static Mob mob(Interpreter in, Object s) {
        Entity e = need(in, s);
        if (e instanceof Mob m) return m;
        throw in.error(self(in, s).type + " is not a mob and cannot be controlled");
    }

    /** 이 스크립트가 만든 엔티티인지. */
    public static boolean owned(ScriptInstance inst, EntityRef r) {
        return r.fake || inst.rec.entities.contains(r.id.toString()) || inst.rec.keptEntities.contains(r.id.toString());
    }

    /** 다른 스크립트·플레이어 소유물을 함부로 바꾸지 않도록: 소유하지 않은 엔티티의 파괴적 조작은 막는다. */
    private static void requireOwned(Interpreter in, ScriptInstance inst, EntityRef r, String what) {
        if (!owned(inst, r)) {
            throw new dev.newyear.countdown.ai.script.lang.ScriptError(dev.newyear.countdown.ai.script.lang.ScriptError.Kind.PERMISSION,
                    "Can only " + what + " entities this script spawned (use entities.spawn). This " + r.type + " belongs to the world", 0, 0);
        }
    }

    private static boolean dry(Interpreter in, String what) {
        ScriptInstance inst = ScriptInstance.of(in);
        if (inst.dry) inst.effect(what);
        return inst.dry;
    }

    private static void setBehaviour(ScriptInstance inst, EntityRef r, ScriptRecord.Behaviour b) {
        inst.rec.behaviours.removeIf(x -> x.entity.equals(r.id.toString()));
        if (b != null) {
            if (inst.rec.behaviours.size() >= 64) throw new dev.newyear.countdown.ai.script.lang.ScriptError(dev.newyear.countdown.ai.script.lang.ScriptError.Kind.BUDGET, "Too many entity behaviours (max 64)", 0, 0);
            inst.rec.behaviours.add(b);
        }
    }

    static {
        def("exists", "() → boolean (alive and loaded)", (in, s, a) -> {
            EntityRef r = self(in, s);
            if (r.fake) return true;
            Entity e = r.live();
            return e != null && e.isAlive();
        });
        def("pos", "() → {x,y,z}", (in, s, a) -> {
            EntityRef r = self(in, s);
            double[] p = r.position();
            if (p == null) throw in.error("That " + r.type + " no longer exists");
            return Args.posObj(p[0], p[1], p[2]);
        });
        def("dimension", "() → dimension id", (in, s, a) -> self(in, s).fake ? "minecraft:overworld" : McUtil.dimId(need(in, s).level()));
        def("health", "() → number (0 if not living)", (in, s, a) -> {
            if (self(in, s).fake) return 10.0;
            return need(in, s) instanceof LivingEntity le ? (double) le.getHealth() : 0.0;
        });
        def("isOwned", "() → true if this script spawned it", (in, s, a) -> owned(ScriptInstance.of(in), self(in, s)));
        def("tags", "() → [string] scoreboard tags", (in, s, a) -> {
            if (self(in, s).fake) return new Values.JsArray();
            return Values.JsArray.of(need(in, s).entityTags());
        });
        def("setName", "(text, visible=true, color?) custom name above the entity", (in, s, a) -> {
            String t = Args.str(in, a, 0, "name");
            if (dry(in, "setName " + t)) return true;
            Entity e = need(in, s);
            e.setCustomName(McUtil.text(t, Args.str(a, 2, null)));
            e.setCustomNameVisible(!Args.present(a, 1) || Interpreter.truthy(a.get(1)));
            return true;
        });
        def("glow", "(on=true) outline glow visible through walls", (in, s, a) -> {
            if (dry(in, "glow")) return true;
            need(in, s).setGlowingTag(!Args.present(a, 0) || Interpreter.truthy(a.get(0)));
            return true;
        });
        def("follow", "(target player|entity, opts?{speed=1, distance=3}) keep walking after the target (persists)", (in, s, a) -> {
            ScriptInstance inst = ScriptInstance.of(in);
            EntityRef r = self(in, s);
            Object t = Args.get(a, 0);
            String target;
            if (t instanceof PlayerRef pr) target = "player:" + pr.id;
            else if (t instanceof EntityRef er) target = "entity:" + er.id;
            else throw in.error("follow target must be a player or entity");
            JsObject o = Args.opts(a, 1);
            if (!r.fake) mob(in, s);
            Policy.checkControl(in, inst, r);
            ScriptRecord.Behaviour b = new ScriptRecord.Behaviour();
            b.entity = r.id.toString();
            b.kind = "follow";
            b.target = target;
            b.speed = Math.max(0.3, Math.min(2.0, Args.optNum(o, "speed", 1.0)));
            b.distance = Math.max(1.5, Math.min(16, Args.optNum(o, "distance", 3.0)));
            if (dry(in, r.type + " follows " + target)) return true;
            setBehaviour(inst, r, b);
            return true;
        });
        def("orbit", "(center pos|player|entity, opts?{radius=3, speed=1}) walk in circles around a point/target (persists)", (in, s, a) -> {
            ScriptInstance inst = ScriptInstance.of(in);
            EntityRef r = self(in, s);
            JsObject o = Args.opts(a, 1);
            ScriptRecord.Behaviour b = new ScriptRecord.Behaviour();
            b.entity = r.id.toString();
            b.kind = "orbit";
            Object t = Args.get(a, 0);
            if (t instanceof PlayerRef pr) b.target = "player:" + pr.id;
            else if (t instanceof EntityRef er) b.target = "entity:" + er.id;
            else b.anchor = Args.pos(in, t, "orbit center");
            b.radius = Math.max(1.5, Math.min(12, Args.optNum(o, "radius", 3)));
            b.speed = Math.max(0.3, Math.min(2.0, Args.optNum(o, "speed", 1.0)));
            if (!r.fake) mob(in, s);
            Policy.checkControl(in, inst, r);
            if (dry(in, r.type + " orbits")) return true;
            setBehaviour(inst, r, b);
            return true;
        });
        def("stay", "(pos?) stand still near a position (persists)", (in, s, a) -> {
            ScriptInstance inst = ScriptInstance.of(in);
            EntityRef r = self(in, s);
            ScriptRecord.Behaviour b = new ScriptRecord.Behaviour();
            b.entity = r.id.toString();
            b.kind = "stay";
            b.anchor = Args.present(a, 0) ? Args.pos(in, a.get(0), "stay position") : r.position();
            Policy.checkControl(in, inst, r);
            if (dry(in, r.type + " stays")) return true;
            setBehaviour(inst, r, b);
            return true;
        });
        def("stopBehaviour", "() stop follow/orbit/stay", (in, s, a) -> {
            ScriptInstance inst = ScriptInstance.of(in);
            EntityRef r = self(in, s);
            if (dry(in, r.type + " stops behaviour")) return true;
            setBehaviour(inst, r, null);
            Entity e = r.live();
            if (e instanceof Mob m) m.getNavigation().stop();
            return true;
        });
        def("moveTo", "(pos, speed=1) walk to a position once", (in, s, a) -> {
            ScriptInstance inst = ScriptInstance.of(in);
            EntityRef r = self(in, s);
            double[] p = Args.pos(in, Args.get(a, 0), "moveTo target");
            Policy.checkControl(in, inst, r);
            if (dry(in, r.type + " moves to " + (int) p[0] + "," + (int) p[1] + "," + (int) p[2])) return true;
            return mob(in, s).getNavigation().moveTo(p[0], p[1], p[2], Math.max(0.3, Math.min(2.0, Args.num(a, 1, 1))));
        });
        def("lookAt", "(pos|player|entity)", (in, s, a) -> {
            double[] p = Args.pos(in, Args.get(a, 0), "lookAt target");
            if (dry(in, "lookAt")) return true;
            mob(in, s).getLookControl().setLookAt(p[0], p[1], p[2]);
            return true;
        });
        def("teleport", "(pos) only entities this script spawned", (in, s, a) -> {
            ScriptInstance inst = ScriptInstance.of(in);
            EntityRef r = self(in, s);
            requireOwned(in, inst, r, "teleport");
            double[] p = Args.pos(in, Args.get(a, 0), "teleport target");
            Policy.checkRange(in, inst, p);
            if (dry(in, r.type + " teleports")) return true;
            need(in, s).teleportTo(p[0], p[1], p[2]);
            return true;
        });
        def("push", "(vx, vy, vz)", (in, s, a) -> {
            if (dry(in, "push entity")) return true;
            Entity e = need(in, s);
            if (e instanceof Player) throw in.error("use player.push for players");
            e.push(Math.max(-3, Math.min(3, Args.num(a, 0, 0))), Math.max(-3, Math.min(3, Args.num(a, 1, 0))), Math.max(-3, Math.min(3, Args.num(a, 2, 0))));
            e.needsSync = true;
            return true;
        });
        def("setAI", "(enabled) only entities this script spawned", (in, s, a) -> {
            ScriptInstance inst = ScriptInstance.of(in);
            requireOwned(in, inst, self(in, s), "change AI of");
            if (dry(in, "setAI")) return true;
            mob(in, s).setNoAi(!Interpreter.truthy(Args.get(a, 0)));
            return true;
        });
        def("setSilent", "(on)", (in, s, a) -> {
            if (dry(in, "setSilent")) return true;
            need(in, s).setSilent(Interpreter.truthy(Args.get(a, 0)));
            return true;
        });
        def("setInvulnerable", "(on) only entities this script spawned", (in, s, a) -> {
            ScriptInstance inst = ScriptInstance.of(in);
            requireOwned(in, inst, self(in, s), "make invulnerable");
            if (dry(in, "setInvulnerable")) return true;
            need(in, s).setPermanentlyInvulnerable(Interpreter.truthy(Args.get(a, 0)));
            return true;
        });
        def("setBaby", "(on) for ageable mobs", (in, s, a) -> {
            if (dry(in, "setBaby")) return true;
            if (need(in, s) instanceof AgeableMob am) { am.setBaby(Interpreter.truthy(Args.get(a, 0))); return true; }
            return false;
        });
        def("tame", "(player) tame a wolf/cat/parrot… for that player (script-spawned only)", (in, s, a) -> {
            ScriptInstance inst = ScriptInstance.of(in);
            requireOwned(in, inst, self(in, s), "tame");
            Object p = Args.get(a, 0);
            if (!(p instanceof PlayerRef pr)) throw in.error("tame needs a player");
            if (dry(in, "tame for " + pr.name)) return true;
            ServerPlayer sp = pr.live();
            if (sp != null && need(in, s) instanceof TamableAnimal ta) { ta.tame(sp); return true; }
            return false;
        });
        def("sit", "(on) tamed animals sit/stand", (in, s, a) -> {
            if (dry(in, "sit")) return true;
            if (need(in, s) instanceof TamableAnimal ta) { boolean on = Interpreter.truthy(Args.get(a, 0)); ta.setOrderedToSit(on); ta.setInSittingPose(on); return true; }
            return false;
        });
        def("equip", "(slot 'head'|'mainhand'|'offhand'|'chest'|'legs'|'feet', itemId, opts?) script-spawned mobs only", (in, s, a) -> {
            ScriptInstance inst = ScriptInstance.of(in);
            requireOwned(in, inst, self(in, s), "equip");
            String slot = Args.str(in, a, 0, "slot");
            var stack = McUtil.makeStack(in, Args.str(in, a, 1, "item id"), 1, Args.opts(a, 2), inst.rec.id);
            if (dry(in, "equip " + slot)) return true;
            EquipmentSlot es = switch (slot) {
                case "head" -> EquipmentSlot.HEAD;
                case "chest" -> EquipmentSlot.CHEST;
                case "legs" -> EquipmentSlot.LEGS;
                case "feet" -> EquipmentSlot.FEET;
                case "offhand" -> EquipmentSlot.OFFHAND;
                default -> EquipmentSlot.MAINHAND;
            };
            Mob m = mob(in, s);
            m.setItemSlot(es, stack);
            m.setDropChance(es, 0f);
            return true;
        });
        def("keep", "(on=true) keep this entity in the world even after the script ends", (in, s, a) -> {
            ScriptInstance inst = ScriptInstance.of(in);
            EntityRef r = self(in, s);
            requireOwned(in, inst, r, "keep");
            String id = r.id.toString();
            boolean on = !Args.present(a, 0) || Interpreter.truthy(a.get(0));
            inst.rec.keptEntities.remove(id);
            if (on) inst.rec.keptEntities.add(id);
            return true;
        });
        def("remove", "() remove an entity this script spawned", (in, s, a) -> {
            ScriptInstance inst = ScriptInstance.of(in);
            EntityRef r = self(in, s);
            requireOwned(in, inst, r, "remove");
            if (dry(in, "remove " + r.type)) return true;
            Entity e = r.live();
            if (e != null) e.discard();
            inst.rec.entities.remove(r.id.toString());
            inst.rec.keptEntities.remove(r.id.toString());
            inst.rec.behaviours.removeIf(b -> b.entity.equals(r.id.toString()));
            return true;
        });
        def("distanceTo", "(player|entity|pos) → blocks", (in, s, a) -> {
            double[] me = self(in, s).position();
            if (me == null) return Double.POSITIVE_INFINITY;
            Object o = Args.get(a, 0);
            double[] q = o instanceof Args.Positioned h ? h.position() : Args.pos(in, o, "target");
            if (q == null) return Double.POSITIVE_INFINITY;
            double dx = me[0] - q[0], dy = me[1] - q[1], dz = me[2] - q[2];
            return Math.sqrt(dx * dx + dy * dy + dz * dz);
        });
        def("data", "(key, value?) per-entity storage in this script's state (persists)", (in, s, a) -> {
            ScriptInstance inst = ScriptInstance.of(in);
            EntityRef r = self(in, s);
            Object bag = inst.state.get("$entities");
            JsObject all;
            if (bag instanceof JsObject jo) all = jo;
            else { all = new JsObject(); inst.state.put("$entities", all); }
            Object mine = all.get(r.id.toString());
            JsObject my;
            if (mine instanceof JsObject jo) my = jo;
            else { my = new JsObject(); all.put(r.id.toString(), my); }
            String key = Args.str(in, a, 0, "key");
            if (a.size() >= 2) { my.put(key, a.get(1)); return a.get(1); }
            return my.get(key);
        });
    }
}
