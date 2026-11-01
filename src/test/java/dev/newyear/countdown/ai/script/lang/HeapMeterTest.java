package dev.newyear.countdown.ai.script.lang;

import dev.newyear.countdown.ai.script.ScriptInstance;
import dev.newyear.countdown.ai.script.ScriptRecord;
import dev.newyear.countdown.ai.script.lang.Values.JsArray;
import dev.newyear.countdown.ai.script.lang.Values.JsObject;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class HeapMeterTest {
    @Test void constructionWaitingNullCanBeMeasuredBeforeAndAfterAnEdit() {
        ScriptInstance inst=new ScriptInstance(new ScriptRecord(),false);
        inst.in.define("state",inst.state);inst.in.budget.reset();
        inst.in.run(Parser.parse("state.phase='gather';state.waiting=null;state.index=0;"));
        assertFalse(HeapMeter.measure(inst.heapRoots(),1_000_000,inst.in.globals).overCap());
        inst.state.put("waiting","e1");
        assertFalse(HeapMeter.measure(inst.heapRoots(),1_000_000,inst.in.globals).overCap());
        inst.state.put("waiting",null);
        assertFalse(HeapMeter.measure(inst.heapRoots(),1_000_000,inst.in.globals).overCap());
    }
    @Test void nullsInsideArraysObjectsAndCapturedBindingsAreLegal() {
        Interpreter in=new Interpreter();in.budget.reset();
        in.run(Parser.parse("let empty=null;let nested={value:null,list:[null,undefined,true]};function make(){let waiting=null;return ()=>waiting;}const callback=make();"));
        var measured=HeapMeter.measure(Arrays.asList(null,in.topScope),1_000_000,in.globals);
        assertTrue(measured.bytes()>0);assertFalse(measured.overCap());
    }
    @Test void nullHandlingPreservesCycleDetectionAndMemoryCap() {
        JsObject o=new JsObject().put("empty",null);JsArray a=new JsArray();a.list.add(o);a.list.add(null);o.put("cycle",a);
        var result=HeapMeter.measure(List.of(o,o),1_000_000,null);
        assertEquals(result,HeapMeter.measure(List.of(o),1_000_000,null));
        o.put("large","x".repeat(100_000));assertTrue(HeapMeter.measure(List.of(o),1024,null).overCap());
    }
}
