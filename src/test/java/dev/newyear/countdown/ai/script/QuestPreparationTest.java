package dev.newyear.countdown.ai.script;

import com.google.gson.Gson;
import dev.newyear.countdown.ai.script.lang.Parser;
import dev.newyear.countdown.ai.script.lang.ScriptError;
import dev.newyear.countdown.ai.script.lang.Values.NativeFn;
import dev.newyear.countdown.ai.wish.Quest;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class QuestPreparationTest {
    private Quest quest() {
        Quest q = new Quest();
        q.id = "q-test"; q.wishId = "wish"; q.owner = "player";
        Quest.Objective goal = new Quest.Objective(); goal.id = "gift"; goal.target = 3;
        q.objectives.add(goal);
        q.startTick = 100; q.deadlineTick = 1300;
        QuestPreparation.stage(q);
        return q;
    }

    private ScriptInstance tracker(Quest q) {
        ScriptRecord r = new ScriptRecord(); r.id = "tracker"; r.wishId = q.wishId; r.owner = q.owner;
        r.state = ScriptRecord.State.ACTIVE;
        ScriptInstance inst = new ScriptInstance(r, false);
        inst.in.define("quests", ScriptApi.buildQuests(inst, () -> Map.of(q.id, q)));
        inst.in.define("state", inst.state);
        inst.in.budget.reset();
        inst.in.run(Parser.parse("const quest = quests.get('q-test'); state.questId = quest.id;"));
        return inst;
    }

    private void listen(ScriptInstance inst) {
        inst.listeners.put("item_give", new ArrayList<>(List.of(new ScriptInstance.Listener(1, "item_give",
                new NativeFn("track", (in, self, args) -> null), null, false))));
    }

    @Test void draftWaitsWithoutStartingItsDeadlineAndPublishesOnceAfterStartup() {
        Quest q = quest(); ScriptInstance inst = tracker(q); listen(inst);
        assertTrue(q.preparing);
        assertEquals(0, q.deadlineTick);
        assertFalse(QuestPreparation.publish(q, inst, 9000), "A live record alone is not completed startup");
        inst.initialized = true;
        assertTrue(QuestPreparation.publish(q, inst, 9000));
        assertFalse(q.preparing);
        assertEquals("tracker", q.scriptId);
        assertEquals(9000, q.startTick);
        assertEquals(10200, q.deadlineTick);
        assertFalse(QuestPreparation.publish(q, inst, 9500));
        assertEquals(10200, q.deadlineTick, "Repeated publication must not reset the timer");
    }

    @Test void emptyTrackerFailsInsteadOfPublishingTextOnlyQuest() {
        Quest q = quest(); ScriptInstance inst = tracker(q); inst.initialized = true;
        assertThrows(ScriptError.class, () -> QuestPreparation.publish(q, inst, 5000));
        assertTrue(q.preparing); assertEquals(0, q.deadlineTick); assertNull(q.scriptId);
    }

    @Test void failedOrUnapprovedTrackerCannotPublish() {
        for (ScriptRecord.State state : List.of(ScriptRecord.State.DRAFT, ScriptRecord.State.FAULTED, ScriptRecord.State.PENDING_APPROVAL, ScriptRecord.State.SUSPENDED)) {
            Quest q = quest(); ScriptInstance inst = tracker(q); listen(inst); inst.initialized = true; inst.rec.state = state;
            assertFalse(QuestPreparation.publish(q, inst, 5000));
            assertTrue(q.preparing); assertEquals(0, q.deadlineTick);
        }
    }

    @Test void rewardOnlyListenerCannotStandInForConditionTracking() {
        Quest q = quest(); ScriptInstance inst = tracker(q); listen(inst); inst.initialized = true;
        inst.listeners.put("quest_completed", inst.listeners.remove("item_give"));
        assertThrows(ScriptError.class, () -> QuestPreparation.publish(q, inst, 5000));
        assertTrue(q.preparing);
    }

    @Test void interactivePuzzleCanUseAnInstalledInputCallback() {
        Quest q = quest(); ScriptInstance inst = tracker(q); inst.initialized = true;
        inst.awaitingQuestUi = true;
        assertTrue(QuestPreparation.publish(q, inst, 5000));
    }

    @Test void unrelatedScriptCannotPretendToBeTheTracker() {
        Quest q = quest(); ScriptInstance inst = tracker(q); listen(inst); inst.initialized = true;
        inst.rec.owner = "someone-else";
        assertThrows(ScriptError.class, () -> QuestPreparation.publish(q, inst, 5000));
        inst.rec.owner = q.owner; inst.rec.wishId = "another-wish";
        assertThrows(ScriptError.class, () -> QuestPreparation.publish(q, inst, 5000));
        inst.rec.wishId = q.wishId; inst.preparingQuests.clear();
        assertThrows(ScriptError.class, () -> QuestPreparation.publish(q, inst, 5000));
        assertTrue(q.preparing);
    }

    @Test void timerBasedConditionsCanStartButCancelledTimersCannot() {
        Quest q = quest(); ScriptInstance inst = tracker(q); inst.initialized = true;
        ScriptInstance.Timer timer = new ScriptInstance.Timer(); inst.timers.add(timer);
        timer.cancelled = true;
        assertThrows(ScriptError.class, () -> QuestPreparation.publish(q, inst, 5000));
        timer.cancelled = false;
        assertTrue(QuestPreparation.publish(q, inst, 5000));
    }

    @Test void engineConstructionOnlyCoversItsOwnObjective() {
        Quest q = quest(); ScriptInstance inst = tracker(q); inst.initialized = true;
        Quest.Objective build = new Quest.Objective(); build.id = "build"; q.objectives.add(build);
        inst.engineQuestObjectives.put(q.id, "build");
        assertThrows(ScriptError.class, () -> QuestPreparation.publish(q, inst, 5000), "Gift condition still needs a tracker");
        q.objective("gift").done = true;
        assertTrue(QuestPreparation.publish(q, inst, 5000));
    }

    @Test void draftSnapshotPreservesRecoveryStateWithoutAliasingProgress() {
        Quest q = quest(); ScriptInstance inst = tracker(q);
        q.objective("gift").progress = 2; q.preparedCompletion = true;
        Quest before = inst.preparingQuests.get(q.id);
        assertEquals(0, before.objective("gift").progress);
        assertFalse(before.preparedCompletion);
        assertEquals(1200, before.preparedTimeLimitTicks);
        Quest saved = new Gson().fromJson(new Gson().toJson(before), Quest.class);
        assertTrue(saved.preparing); assertEquals(0, saved.deadlineTick);
        assertEquals(1200, saved.preparedTimeLimitTicks);
    }

    @Test void immediateConditionCanPublishOnlyAfterStartupAndCancelledDraftNeverPublishes() {
        Quest q = quest(); ScriptInstance inst = tracker(q);
        q.objective("gift").done = true;
        assertFalse(QuestPreparation.publish(q, inst, 5000));
        inst.initialized = true;
        assertTrue(QuestPreparation.publish(q, inst, 5000));
        Quest cancelled = quest(); ScriptInstance other = tracker(cancelled); listen(other); other.initialized = true;
        cancelled.state = Quest.State.CANCELLED;
        assertFalse(QuestPreparation.publish(cancelled, other, 6000));
    }
}
