package dev.newyear.countdown.ai.script;

import dev.newyear.countdown.ai.script.lang.Parser;
import dev.newyear.countdown.ai.wish.Quest;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class QuestIsolationTest {
    Quest live() {
        Quest q=new Quest();q.id="contract";q.participants.add("alice");
        Quest.Objective o=new Quest.Objective();o.id="pay";q.objectives.add(o);return q;
    }
    ScriptInstance boot(Quest q) {
        ScriptInstance inst=new ScriptInstance(new ScriptRecord(),true);
        inst.in.define("quests",ScriptApi.buildQuests(inst,()->Map.of(q.id,q)));
        inst.in.define("state",inst.state);inst.in.budget.reset();return inst;
    }
    void run(ScriptInstance inst,String code){inst.in.run(Parser.parse(code));}
    @Test void validatingPaymentCompletesOnlySnapshotAndRepeatedLookupsSeeIt() {
        Quest q=live();var inst=boot(q);
        run(inst,"quests.get('contract').add('pay'); state.done=quests.get('contract').isComplete(); state.status=quests.get('contract').state(); state.active=quests.active().length;");
        assertEquals(true,inst.state.get("done"));assertEquals("COMPLETED",inst.state.get("status"));assertEquals(0.0,inst.state.get("active"));
        assertEquals(Quest.State.ACTIVE,q.state);assertFalse(q.objective("pay").done);assertEquals(0,q.objective("pay").progress);
    }
    @Test void activeQuestHandlesAreIsolatedAndAFreshValidationStartsClean() {
        Quest q=live();var first=boot(q);run(first,"quests.active()[0].fail('test');");
        assertEquals(Quest.State.ACTIVE,q.state);
        var second=boot(q);run(second,"state.s=quests.get('contract').state();");assertEquals("ACTIVE",second.state.get("s"));
    }
    @Test void liveReferenceFromEventCannotBypassDryIsolation() {
        Quest q=live();var inst=boot(q);inst.questSnapshot(q);
        inst.in.define("eventQuest",new QuestRef(q.id,false,null));
        run(inst,"eventQuest.complete('simulated');");
        assertEquals(Quest.State.ACTIVE,q.state);assertEquals(Quest.State.COMPLETED,inst.dryQuests.get(q.id).state);
    }
    @Test void SnapshotListsAndObjectivesDoNotAliasLiveState() {
        Quest q=live();Quest copy=q.copy();copy.participants.clear();copy.objectives.get(0).progress=99;
        assertEquals(List.of("alice"),q.participants);assertEquals(0,q.objective("pay").progress);
    }
    @Test void failedSimulatedQuestCannotLaterBeCompleted() {
        Quest q=live();var inst=boot(q);run(inst,"let q=quests.get('contract');q.fail('test');q.add('pay');q.complete();state.s=q.state();");
        assertEquals("FAILED",inst.state.get("s"));assertEquals(Quest.State.ACTIVE,q.state);
    }
}
