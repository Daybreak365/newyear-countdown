package dev.newyear.countdown.ai.script;

import dev.newyear.countdown.ai.WishEngine;
import dev.newyear.countdown.ai.script.api.EntityRef;
import dev.newyear.countdown.ai.script.api.McUtil;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.AvoidEntityGoal;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;

import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Predicate;

/**
 * 스크립트가 엔티티에 붙이는 진짜 AI 목표(Goal). 바닐라 목표 선택기(GoalSelector)에 들어가 다른 목표와 우선순위로 경쟁한다.
 * <ul>
 *   <li>guard: 기준점(또는 지킬 플레이어) 주변을 지키며 다가오는 적대 몹을 공격하고, 멀어지면 돌아온다.</li>
 *   <li>flee: 대상(플레이어·몹 종류·적대 몹)을 피해 달아난다.</li>
 *   <li>attack: 대상을 쫓아가 근접 공격한다 (공격력이 있는 몹만; 다른 플레이어는 동의가 있어야 한다).</li>
 *   <li>wander / patrol: 정해진 반경 안을 거닐거나, 여러 지점을 차례로 돈다.</li>
 *   <li>look: 가까운 플레이어를 바라본다.</li>
 *   <li>script: 스크립트가 직접 짠 목표 — canUse/start/tick/stop 콜백 (entity.brain).</li>
 * </ul>
 * 목표는 엔티티 객체에만 붙어 있으므로, 청크가 다시 로드되거나 서버가 재시작되면 ScriptRecord.goals 명세로 다시 붙인다.
 */
public final class Goals {
    private Goals() {}

    /** 그 스크립트가 지금 실행 중일 때만 목표가 움직인다 (일시정지·킬 스위치 중에는 쉰다). */
    static boolean active(String scriptId) {
        WishEngine e = WishEngine.get();
        return e != null && !e.state().killSwitch && e.scripts().instance(scriptId) != null;
    }

    /** 이 모드가 붙인 목표 표시. */
    public interface WishGoal {
        String scriptId();

        int goalId();
    }

    /** 바닐라 목표를 감싸 표시를 단다. */
    static final class Wrapped extends Goal implements WishGoal {
        private final Goal inner;
        private final String script;
        private final int id;

        Wrapped(Goal inner, String script, int id) {
            this.inner = inner;
            this.script = script;
            this.id = id;
            setFlags(inner.getFlags());
        }

        @Override public boolean canUse() { return active(script) && inner.canUse(); }
        @Override public boolean canContinueToUse() { return inner.canContinueToUse(); }
        @Override public boolean isInterruptable() { return inner.isInterruptable(); }
        @Override public void start() { inner.start(); }
        @Override public void stop() { inner.stop(); }
        @Override public boolean requiresUpdateEveryTick() { return inner.requiresUpdateEveryTick(); }
        @Override public void tick() { inner.tick(); }
        @Override public String scriptId() { return script; }
        @Override public int goalId() { return id; }
    }

    // ---------------------------------------------------------------- 대상

    /** 대상 명세 → 판정. "hostile", "players", "type:minecraft:zombie", "player:<uuid>", "entity:<uuid>". */
    static Predicate<LivingEntity> matcher(String target) {
        if (target == null) return e -> false;
        if (target.equals("hostile")) return e -> e instanceof Enemy && e.isAlive();
        if (target.equals("players")) return e -> e instanceof Player p && !p.isSpectator() && !p.isCreative();
        if (target.startsWith("type:")) {
            String id = target.substring(5);
            return e -> McUtil.entityId(e.getType()).equals(id) && e.isAlive();
        }
        if (target.startsWith("player:") || target.startsWith("entity:")) {
            try {
                UUID u = UUID.fromString(target.substring(7));
                return e -> e.getUUID().equals(u) && e.isAlive();
            } catch (IllegalArgumentException ex) {
                return e -> false;
            }
        }
        return e -> false;
    }

    /** 기준점: anchor 좌표, 또는 지킬 대상(플레이어/엔티티)의 현재 위치. */
    static double[] anchorOf(ScriptRecord.GoalSpec s, Mob self) {
        if (s.target != null && (s.target.startsWith("player:") || s.target.startsWith("entity:")) && s.kind.equals("guard")) {
            try {
                UUID u = UUID.fromString(s.target.substring(7));
                Entity e = s.target.startsWith("player:") ? WishEngine.get().player(u) : new EntityRef(u, "?").live();
                if (e != null && e.level() == self.level()) return new double[]{e.getX(), e.getY(), e.getZ()};
            } catch (IllegalArgumentException ignored) { }
        }
        return s.anchor;
    }

    public static boolean canAttack(Mob m) {
        return m instanceof PathfinderMob && m.getAttribute(Attributes.ATTACK_DAMAGE) != null;
    }

    // ---------------------------------------------------------------- 붙이기·떼기

    /** 명세대로 목표를 붙인다 (같은 스크립트의 같은 id 목표는 먼저 뗀다). 붙일 수 없으면 이유를 돌려준다. */
    public static String apply(Mob m, String scriptId, ScriptRecord.GoalSpec s) {
        remove(m, scriptId, s.id);
        int pri = Math.max(0, Math.min(9, s.priority));
        var gs = m.getGoalSelector();
        switch (s.kind) {
            case "guard" -> {
                if (!canAttack(m)) return m.getType().getDescriptionId() + " cannot attack";
                PathfinderMob pm = (PathfinderMob) m;
                gs.addGoal(pri, new Wrapped(new MeleeAttackGoal(pm, s.speed, true), scriptId, s.id));
                gs.addGoal(pri, new TargetPicker(m, scriptId, s.id, matcher("hostile"), s.radius, s));
                gs.addGoal(pri + 1, new ReturnHome(m, scriptId, s.id, s));
            }
            case "attack" -> {
                if (!canAttack(m)) return m.getType().getDescriptionId() + " cannot attack";
                PathfinderMob pm = (PathfinderMob) m;
                gs.addGoal(pri, new Wrapped(new MeleeAttackGoal(pm, s.speed, true), scriptId, s.id));
                gs.addGoal(pri, new TargetPicker(m, scriptId, s.id, matcher(s.target), s.radius, null));
            }
            case "flee" -> {
                if (!(m instanceof PathfinderMob pm)) return "only walking mobs can flee";
                Predicate<LivingEntity> match = matcher(s.target);
                gs.addGoal(pri, new Wrapped(new AvoidEntityGoal<>(pm, LivingEntity.class, (float) s.radius, s.speed, s.speed * 1.3, match::test), scriptId, s.id));
            }
            case "wander" -> gs.addGoal(pri, new Wander(m, scriptId, s.id, s));
            case "patrol" -> {
                if (s.points.isEmpty()) return "patrol needs points";
                gs.addGoal(pri, new Patrol(m, scriptId, s.id, s));
            }
            case "look" -> gs.addGoal(pri, new Wrapped(new LookAtPlayerGoal(m, Player.class, (float) s.radius), scriptId, s.id));
            case "script" -> gs.addGoal(pri, new ScriptGoal(m, scriptId, s.id, s));
            default -> {
                return "unknown goal kind " + s.kind;
            }
        }
        return null;
    }

    public static void remove(Mob m, String scriptId, int goalId) {
        m.getGoalSelector().removeAllGoals(g -> g instanceof WishGoal w && w.scriptId().equals(scriptId) && (goalId < 0 || w.goalId() == goalId));
        if (goalId < 0 || m.getTarget() != null && !m.getTarget().isAlive()) m.setTarget(null);
    }

    public static void removeAll(Mob m, String scriptId) {
        remove(m, scriptId, -1);
        m.getNavigation().stop();
    }

    // ---------------------------------------------------------------- 사용자 목표

    /** 일정 간격으로 가장 가까운 대상을 골라 setTarget (근접 공격 목표가 그 대상을 쫓는다). 움직임 권한은 쓰지 않는다. */
    static final class TargetPicker extends Goal implements WishGoal {
        private final Mob mob;
        private final String script;
        private final int id;
        private final Predicate<LivingEntity> match;
        private final double radius;
        private final ScriptRecord.GoalSpec guard;
        private int cooldown;

        TargetPicker(Mob mob, String script, int id, Predicate<LivingEntity> match, double radius, ScriptRecord.GoalSpec guard) {
            this.mob = mob;
            this.script = script;
            this.id = id;
            this.match = match;
            this.radius = radius;
            this.guard = guard;
            setFlags(EnumSet.noneOf(Flag.class));
        }

        @Override
        public boolean canUse() {
            if (--cooldown > 0 || !active(script)) return false;
            cooldown = 10;
            LivingEntity cur = mob.getTarget();
            double[] c = guard == null ? new double[]{mob.getX(), mob.getY(), mob.getZ()} : anchorOf(guard, mob);
            if (c == null) c = new double[]{mob.getX(), mob.getY(), mob.getZ()};
            double r = radius;
            if (cur != null && cur.isAlive() && match.test(cur) && cur.distanceToSqr(c[0], c[1], c[2]) <= r * r * 1.5) return false;
            List<LivingEntity> near = mob.level().getEntitiesOfClass(LivingEntity.class, new AABB(c[0] - r, c[1] - r / 2, c[2] - r, c[0] + r, c[1] + r / 2, c[2] + r),
                    e -> e != mob && match.test(e) && e.distanceToSqr(mob) <= r * r * 4);
            LivingEntity best = null;
            for (LivingEntity e : near) if (best == null || e.distanceToSqr(mob) < best.distanceToSqr(mob)) best = e;
            if (best != cur) mob.setTarget(best);
            return false;
        }

        @Override public String scriptId() { return script; }
        @Override public int goalId() { return id; }
    }

    /** 지키는 자리에서 너무 멀어지면 돌아간다. */
    static final class ReturnHome extends Goal implements WishGoal {
        private final Mob mob;
        private final String script;
        private final int id;
        private final ScriptRecord.GoalSpec spec;

        ReturnHome(Mob mob, String script, int id, ScriptRecord.GoalSpec spec) {
            this.mob = mob;
            this.script = script;
            this.id = id;
            this.spec = spec;
            setFlags(EnumSet.of(Flag.MOVE));
        }

        private double dist2() {
            double[] a = anchorOf(spec, mob);
            if (a == null) return 0;
            double dx = a[0] - mob.getX(), dy = a[1] - mob.getY(), dz = a[2] - mob.getZ();
            return dx * dx + dy * dy + dz * dz;
        }

        @Override
        public boolean canUse() {
            double keep = Math.max(3, spec.radius * 0.5);
            return active(script) && mob.getTarget() == null && dist2() > keep * keep;
        }

        @Override
        public boolean canContinueToUse() {
            return mob.getTarget() == null && dist2() > 4 && !mob.getNavigation().isDone();
        }

        @Override
        public void start() {
            double[] a = anchorOf(spec, mob);
            if (a == null) return;
            if (dist2() > 48 * 48 && mob.level() instanceof ServerLevel) mob.teleportTo(a[0], a[1], a[2]);
            else mob.getNavigation().moveTo(a[0], a[1], a[2], spec.speed);
        }

        @Override public String scriptId() { return script; }
        @Override public int goalId() { return id; }
    }

    /** 기준점 반경 안에서 가끔 새 자리로 걸어간다. */
    static final class Wander extends Goal implements WishGoal {
        private final Mob mob;
        private final String script;
        private final int id;
        private final ScriptRecord.GoalSpec spec;

        Wander(Mob mob, String script, int id, ScriptRecord.GoalSpec spec) {
            this.mob = mob;
            this.script = script;
            this.id = id;
            this.spec = spec;
            setFlags(EnumSet.of(Flag.MOVE));
        }

        @Override
        public boolean canUse() {
            return mob.getRandom().nextInt(60) == 0 && mob.getTarget() == null && active(script);
        }

        @Override
        public boolean canContinueToUse() {
            return !mob.getNavigation().isDone();
        }

        @Override
        public void start() {
            double[] c = spec.anchor != null ? spec.anchor : new double[]{mob.getX(), mob.getY(), mob.getZ()};
            ThreadLocalRandom r = ThreadLocalRandom.current();
            double ang = r.nextDouble() * Math.PI * 2, d = Math.sqrt(r.nextDouble()) * spec.radius;
            mob.getNavigation().moveTo(c[0] + Math.cos(ang) * d, c[1], c[2] + Math.sin(ang) * d, spec.speed);
        }

        @Override public String scriptId() { return script; }
        @Override public int goalId() { return id; }
    }

    /** 지점들을 차례로 돈다 (끝나면 처음으로). */
    static final class Patrol extends Goal implements WishGoal {
        private final Mob mob;
        private final String script;
        private final int id;
        private final ScriptRecord.GoalSpec spec;
        private int index;
        private int stuck;

        Patrol(Mob mob, String script, int id, ScriptRecord.GoalSpec spec) {
            this.mob = mob;
            this.script = script;
            this.id = id;
            this.spec = spec;
            setFlags(EnumSet.of(Flag.MOVE));
        }

        @Override
        public boolean canUse() {
            return mob.getTarget() == null && active(script);
        }

        @Override
        public boolean canContinueToUse() {
            return canUse();
        }

        @Override
        public boolean requiresUpdateEveryTick() {
            return false;
        }

        @Override
        public void tick() {
            double[] p = spec.points.get(index % spec.points.size());
            double dx = p[0] - mob.getX(), dz = p[2] - mob.getZ();
            if (dx * dx + dz * dz < 2.25 || ++stuck > 200) {
                index = (index + 1) % spec.points.size();
                stuck = 0;
                p = spec.points.get(index);
            }
            if (mob.getNavigation().isDone()) mob.getNavigation().moveTo(p[0], p[1], p[2], spec.speed);
        }

        @Override public String scriptId() { return script; }
        @Override public int goalId() { return id; }
    }

    /**
     * entity.brain({canUse, start, tick, stop, every}): 스크립트 콜백으로 판단하는 목표.
     * 콜백은 every 틱마다만 부르고(예산 적용), 그 사이에는 마지막 판단을 그대로 쓴다.
     */
    static final class ScriptGoal extends Goal implements WishGoal {
        private final Mob mob;
        private final String script;
        private final int id;
        private final ScriptRecord.GoalSpec spec;
        private int wait;
        private boolean last;

        ScriptGoal(Mob mob, String script, int id, ScriptRecord.GoalSpec spec) {
            this.mob = mob;
            this.script = script;
            this.id = id;
            this.spec = spec;
            setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
        }

        private Object call(String which) {
            WishEngine e = WishEngine.get();
            return e == null ? null : e.scripts().callBrain(script, mob, which);
        }

        @Override
        public boolean canUse() {
            if (--wait > 0) return last;
            wait = Math.max(5, spec.interval);
            if (!active(script)) return last = false;
            Object r = call("canUse");
            last = r == null ? true : dev.newyear.countdown.ai.script.lang.Interpreter.truthy(r);
            return last;
        }

        @Override
        public boolean canContinueToUse() {
            return canUse();
        }

        @Override
        public void start() {
            call("start");
        }

        @Override
        public void stop() {
            call("stop");
        }

        @Override
        public void tick() {
            if (mob.tickCount % Math.max(5, spec.interval) == 0) call("tick");
        }

        @Override public String scriptId() { return script; }
        @Override public int goalId() { return id; }
    }

    /** 다른 플레이어를 공격 대상으로 삼으려면 주인 본인이거나 동의가 있어야 한다. */
    public static boolean playerTargetAllowed(ScriptInstance inst, String target) {
        if (target == null || !target.startsWith("player:")) return !"players".equals(target);
        String uuid = target.substring(7);
        return uuid.equals(inst.rec.owner) || Policy.hasConsent(inst, uuid);
    }
}
