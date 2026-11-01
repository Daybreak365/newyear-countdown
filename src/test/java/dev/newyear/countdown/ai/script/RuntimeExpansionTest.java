package dev.newyear.countdown.ai.script;

import com.google.gson.*;
import dev.newyear.countdown.ai.extension.NativeExtensions;
import dev.newyear.countdown.ai.script.lang.Values.JsObject;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RuntimeExpansionTest {
    private ContentLibrary.Definition factory(String owner,String id,String code) {
        var d=new ContentLibrary.Definition();d.owner=owner;d.id=id;d.code=code;d.version=1;return d;
    }
    @Test void importedSourceIsPinnedAcrossLibraryChangesAndOnlyIncludedOnce() {
        var defs=new HashMap<String,ContentLibrary.Definition>();
        defs.put("alice/ability@1",factory("alice","alice/ability@1","return params.power * 2;"));
        String pinned=ContentLibrary.expand("// @use alice/ability@1\n// @use alice/ability@1\ncontent.create('alice/ability@1', {power: 2});","alice",defs);
        assertEquals(1,pinned.split("content.bind",-1).length-1);
        defs.get("alice/ability@1").code="throw 'changed';";
        assertEquals(pinned,ContentLibrary.expand(pinned,"alice",defs));
        assertTrue(pinned.contains("params.power * 2"));
    }
    @Test void unknownAndForeignFactoriesAreRejected() {
        var defs=Map.of("alice/a@1",factory("alice","alice/a@1","return 1;"));
        assertThrows(IllegalArgumentException.class,()->ContentLibrary.expand("// @use alice/a@1","bob",defs));
        assertThrows(IllegalArgumentException.class,()->ContentLibrary.expand("// @use alice/a@2","alice",defs));
    }
    @Test void importsAreBounded() {
        Map<String,ContentLibrary.Definition> defs=new HashMap<>();StringBuilder code=new StringBuilder();
        for(int i=0;i<9;i++){String id="a/f"+i+"@1";defs.put(id,factory("a",id,"return 1;"));code.append("// @use ").append(id).append('\n');}
        assertThrows(IllegalArgumentException.class,()->ContentLibrary.expand(code.toString(),"a",defs));
    }
    @Test void interceptionRejectsInvalidReturnsAndKeepsCancellationSticky() {
        JsObject result=new JsObject().put("cancel",false).put("amount",12.0);
        InterceptDecision.merge(result,null);InterceptDecision.merge(result,"cancel");
        InterceptDecision.merge(result,new JsObject().put("amount",Double.NaN));
        assertEquals(12.0,result.get("amount"));
        InterceptDecision.merge(result,new JsObject().put("amount",200.0));assertEquals(40.0,result.get("amount"));
        InterceptDecision.merge(result,new JsObject().put("cancel",true));
        InterceptDecision.merge(result,new JsObject().put("cancel",false).put("amount",2.0));
        assertEquals(true,result.get("cancel"));assertEquals(40.0,result.get("amount"));
    }
    @Test void hudAllowsUsefulPanelsAndRejectsUnboundedOrInvalidElements() {
        RuntimeControls.validatePanel(JsonParser.parseString("{x:0.5,y:0.1,elements:[{type:'text',text:'별빛'},{type:'bar',value:0.5,w:120,h:8}]}").getAsJsonObject());
        for(String bad:List.of("{x:2,elements:[]}","{elements:[{type:'javascript'}]}","{elements:[{type:'bar',value:9}]}","{elements:[{type:'rect',w:-1}]}","{elements:[{type:'text',color:{evil:true}}]}","{elements:[{type:'texture',texture:[]}]}") )
            assertThrows(IllegalArgumentException.class,()->RuntimeControls.validatePanel(JsonParser.parseString(bad).getAsJsonObject()));
        JsonObject huge=new JsonObject();JsonArray elements=new JsonArray();for(int i=0;i<33;i++)elements.add(JsonParser.parseString("{type:'text'}"));huge.add("elements",elements);
        assertThrows(IllegalArgumentException.class,()->RuntimeControls.validatePanel(huge));
    }
    @Test void nativeReportsCannotApproveAnotherArtifactOrEmptyTests() {
        JsonObject report=JsonParser.parseString("{passed:true,sha256:'abc',assertions:{world:true}}").getAsJsonObject();
        NativeExtensions.validateReport(report,"abc");
        assertThrows(IllegalStateException.class,()->NativeExtensions.validateReport(report,"other"));
        report.getAsJsonObject("assertions").addProperty("world",false);
        assertThrows(IllegalStateException.class,()->NativeExtensions.validateReport(report,"abc"));
        report.add("assertions",new JsonObject());
        assertThrows(IllegalStateException.class,()->NativeExtensions.validateReport(report,"abc"));
    }
    @Test void nativeResourcesCannotEscapeArtifactOrInjectServiceProviders() {
        NativeExtensions.safeResource("fabric.mod.json");NativeExtensions.safeResource("assets/example/lang/en_us.json");
        for(String path:List.of("../outside.json","/root.json","C:/outside.json","META-INF/services/tool.txt","bad.class"))
            assertThrows(IllegalArgumentException.class,()->NativeExtensions.safeResource(path));
    }
    @Test void runtimeStateSurvivesJsonRoundTrip() {
        ScriptRecord record=new ScriptRecord();RuntimeState.section(record,"projectiles").addProperty("sentinel",42);
        ScriptRecord loaded=new Gson().fromJson(new Gson().toJson(record),ScriptRecord.class);
        assertEquals(42,RuntimeState.section(loaded,"projectiles").get("sentinel").getAsInt());
    }
    @Test void pinnedFactoryExecutesWithinCallerInterpreter() {
        var defs=Map.of("alice/power@1",factory("alice","alice/power@1","return params.power * 2;"));
        String code=ContentLibrary.expand("// @use alice/power@1\nstate.result=content.create('alice/power@1',{power:7});","alice",defs);
        ScriptInstance inst=new ScriptInstance(new ScriptRecord(),true);
        inst.in.define("content",ContentLibrary.api(inst));inst.in.define("state",inst.state);inst.in.budget.reset();
        inst.in.run(dev.newyear.countdown.ai.script.lang.Parser.parse(code));
        assertEquals(14.0,inst.state.get("result"));
    }
}
