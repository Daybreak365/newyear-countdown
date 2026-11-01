package dev.newyear.countdown.ai.script;

import com.google.gson.Gson;
import dev.newyear.countdown.ai.script.lang.ScriptError;
import dev.newyear.countdown.ai.script.lang.Values.NativeFn;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AbilityBindingsTest {
    private ScriptRecord record(String id) {
        ScriptRecord rec=new ScriptRecord();rec.id=id;rec.name=id;rec.state=ScriptRecord.State.ACTIVE;return rec;
    }
    @Test void newAbilityFindsFreeSlotWithoutReplacingOthers() {
        ScriptRecord a=record("a"),b=record("b"),c=record("c");var all=List.of(a,b,c);
        AbilityBindings.bind(a,all,"player",0,"shield","Shield");
        AbilityBindings.bind(b,all,"player",2,"flight","Flight");
        assertEquals(1,AbilityBindings.assign(c,all,"player","dash","Dash"));
        assertEquals("shield",a.runtime.getAsJsonObject("controls").getAsJsonObject("player:0").get("action").getAsString());
        assertEquals(3,AbilityBindings.assign(c,all,"player","blink","Blink"));
    }
    @Test void repeatedAssignmentAfterSaveKeepsSlotAndUpdatesLabel() {
        ScriptRecord a=record("a");AbilityBindings.bind(a,List.of(a),"player",3,"dash","Old name");
        a=new Gson().fromJson(new Gson().toJson(a),ScriptRecord.class);
        assertEquals(3,AbilityBindings.assign(a,List.of(a),"player","dash","New name"));
        assertEquals(1,a.runtime.getAsJsonObject("controls").size());
        assertEquals("New name",a.runtime.getAsJsonObject("controls").getAsJsonObject("player:3").get("label").getAsString());
    }
    @Test void eachPlayerHasTheirOwnSlots() {
        ScriptRecord a=record("a"),b=record("b");var all=List.of(a,b);
        assertEquals(0,AbilityBindings.assign(a,all,"alice","dash","Dash"));
        assertEquals(0,AbilityBindings.assign(b,all,"bob","dash","Dash"));
    }
    @Test void exhaustedSlotsFailWithoutChangingExistingBindings() {
        ScriptRecord a=record("a"),b=record("b");var all=List.of(a,b);
        for(int i=0;i<4;i++)AbilityBindings.bind(a,all,"player",i,"a"+i,"Ability "+i);
        String before=a.runtime.toString();
        assertThrows(IllegalArgumentException.class,()->AbilityBindings.assign(b,all,"player","dash","Dash"));
        assertEquals(before,a.runtime.toString());assertFalse(b.runtime.has("controls"));
    }
    @Test void suspendedAbilityKeepsReservationAndStoppedAbilityReleasesIt() {
        ScriptRecord a=record("a"),b=record("b");var all=List.of(a,b);
        AbilityBindings.bind(a,all,"player",0,"old","Old");a.state=ScriptRecord.State.SUSPENDED;
        assertEquals(1,AbilityBindings.assign(b,all,"player","new","New"));
        a.state=ScriptRecord.State.STOPPED;
        assertEquals(0,AbilityBindings.assign(b,all,"player","another","Another"));
    }
    @Test void explicitCollisionFailsBeforeWriting() {
        ScriptRecord a=record("a"),b=record("b");var all=List.of(a,b);
        AbilityBindings.bind(a,all,"player",0,"old","Old");
        assertThrows(IllegalArgumentException.class,()->AbilityBindings.bind(b,all,"player",0,"new","New"));
        assertFalse(b.runtime.has("controls"));
    }
    @Test void editedAbilityKeepsItsSlotWithoutKeepingDeletedActions() {
        ScriptRecord old=record("same-id");AbilityBindings.bind(old,List.of(old),"player",3,"dash","Dash");
        AbilityBindings.bind(old,List.of(old),"player",2,"deleted","Deleted");
        ScriptInstance replacement=new ScriptInstance(old,false);
        old.runtime.getAsJsonObject("controls").asMap().clear();
        assertEquals(3,AbilityBindings.assign(old,List.of(old),"player","dash","Renamed",replacement.previousControls));
        assertFalse(old.runtime.getAsJsonObject("controls").has("player:2"));
    }
    @Test void validationRecordCanReplaceItsOwnIdButNotAnotherScriptsSlot() {
        ScriptRecord old=record("same-id"),trial=record("same-id");
        AbilityBindings.bind(old,List.of(old),"player",0,"old","Old");
        assertEquals(0,AbilityBindings.assign(trial,List.of(old),"player","new","New"));
        assertEquals("old",old.runtime.getAsJsonObject("controls").getAsJsonObject("player:0").get("action").getAsString());
    }
    @Test void boundKeyWithoutInputReceiverCannotAppearReady() {
        ScriptRecord rec=record("a");ScriptInstance inst=new ScriptInstance(rec,true);
        AbilityBindings.assign(rec,List.of(rec),"player","dash","Dash");
        assertThrows(ScriptError.class,()->RuntimeControls.validateReceiver(inst));
        inst.listeners.put("ability_input",new java.util.ArrayList<>(List.of(new ScriptInstance.Listener(1,"ability_input",new NativeFn("handler",(in,s,a)->null),null,false))));
        assertDoesNotThrow(()->RuntimeControls.validateReceiver(inst));
        inst.listeners.get("ability_input").clear();
        assertThrows(ScriptError.class,()->RuntimeControls.validateReceiver(inst));
    }
    @Test void dryRunUsesTheSameAllocatorAndDetectsMissingInputHandler() {
        ScriptRecord rec=record("dry");rec.owner=java.util.UUID.randomUUID().toString();
        ScriptInstance inst=new ScriptInstance(rec,true);
        inst.in.define("owner",new dev.newyear.countdown.ai.script.api.PlayerRef(java.util.UUID.fromString(rec.owner),"tester"));
        inst.in.define("controls",RuntimeControls.api(inst));inst.in.define("state",inst.state);
        inst.in.budget.reset();
        inst.in.run(dev.newyear.countdown.ai.script.lang.Parser.parse("state.first=controls.assign(owner,'dash','Dash'); state.again=controls.assign(owner,'dash','Dash'); state.second=controls.assign(owner,'shield','Shield');"));
        assertEquals(0.0,inst.state.get("first"));assertEquals(0.0,inst.state.get("again"));assertEquals(1.0,inst.state.get("second"));
        assertEquals(2,rec.runtime.getAsJsonObject("controls").size());
        assertThrows(ScriptError.class,()->RuntimeControls.validateReceiver(inst));
    }
}
