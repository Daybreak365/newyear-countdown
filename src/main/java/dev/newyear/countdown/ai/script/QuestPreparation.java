package dev.newyear.countdown.ai.script;

import dev.newyear.countdown.ai.wish.Quest;
import java.util.Objects;

/** Publication barrier shared by script startup and callbacks. No Minecraft/UI dependencies. */
public final class QuestPreparation {
    private QuestPreparation() {}

    public static void stage(Quest q) {
        q.preparing = true;
        q.preparedTimeLimitTicks = q.deadlineTick > 0 ? Math.max(0, q.deadlineTick - q.startTick) : 0;
        q.startTick = q.deadlineTick = 0;
    }

    public static boolean belongsTo(Quest q, ScriptInstance inst) {
        return Objects.equals(q.wishId, inst.rec.wishId)
                && (Objects.equals(q.owner, inst.rec.owner) || (q.scriptId != null && q.scriptId.equals(inst.rec.id)));
    }

    public static boolean hasReceiver(Quest q, ScriptInstance inst) {
        if (q.allRequiredDone() || q.preparedCompletion) return true;
        String engineObjective = inst.engineQuestObjectives.get(q.id);
        boolean unfinished = q.objectives.stream().anyMatch(o -> !o.optional && !o.done && !Objects.equals(o.id, engineObjective));
        if (!unfinished && engineObjective != null) return true;
        boolean conditionListener = inst.listeners.entrySet().stream().anyMatch(e -> !e.getValue().isEmpty()
                && !java.util.Set.of("quest_completed", "quest_failed", "quest_progress").contains(e.getKey()));
        return conditionListener || inst.activeTimers() > 0 || inst.awaitingQuestUi;
    }

    public static void validate(Quest q, ScriptInstance inst) {
        if (!belongsTo(q, inst)) throw inst.in.error("Quest tracker must belong to the same wish and player");
        if (!inst.preparingQuests.containsKey(q.id)) throw inst.in.error("Read quests.get('" + q.id + "') at top level before registering its condition handlers");
        if (!hasReceiver(q, inst)) throw inst.in.error("Quest '" + q.id + "' has no active condition listener, timer or input callback. Reward-only listeners do not count. Implement completion conditions before showing it.");
    }

    public static boolean publish(Quest q, ScriptInstance inst, long now) {
        if (!q.preparing || q.state != Quest.State.ACTIVE) return false;
        if (!inst.initialized || inst.dry || inst.rec.state != ScriptRecord.State.ACTIVE) return false;
        validate(q, inst);
        q.scriptId = inst.rec.id;
        q.preparing = false;
        q.startTick = now;
        q.deadlineTick = q.preparedTimeLimitTicks > 0 ? now + q.preparedTimeLimitTicks : 0;
        return true;
    }
}
