package dev.newyear.countdown.ai.script;

import com.google.gson.*;
import dev.newyear.countdown.ai.WishEngine;
import dev.newyear.countdown.ai.script.api.*;
import dev.newyear.countdown.ai.script.lang.*;
import dev.newyear.countdown.ai.script.lang.Values.*;
import net.minecraft.server.level.ServerPlayer;
import java.util.*;

/** Declarative, bounded client UI and four remappable ability controls. */
public final class RuntimeControls {
    private RuntimeControls() {}
    private static final Map<String,Long> LAST_INPUT=new HashMap<>();
    public static ApiObject api(ScriptInstance inst) {
        return new ApiObject("controls")
            .fn("bind","(player,slot 0..3,action,label) reserve one remappable ability key. Emits ability_input for this script only.",(in,s,a)->{
                ServerPlayer p=RuntimeItems.player(in,inst,Args.get(a,0),true);double number=Args.num(in,a,1,"slot");int slot=(int)number;if(slot<0||slot>3||number!=slot)throw in.error("Control slot must be an integer 0..3");
                String action=Args.str(in,a,2,"action"),player=((PlayerRef)Args.get(a,0)).id.toString();
                try{AbilityBindings.bind(inst.rec,records(),player,slot,action,McUtil.clean(Args.str(a,3,action),60));}catch(IllegalArgumentException e){throw in.error(e.getMessage());}
                if(inst.dry)inst.effect("bind control "+slot+" "+action);else send(p,inst.rec);return true;
            })
            .fn("assign","(player,action,label) → slot 0..3. Automatically reserves a free ability slot; same player/action keeps its slot. Fails if all four are reserved, never replaces another ability.",(in,s,a)->{
                ServerPlayer p=RuntimeItems.player(in,inst,Args.get(a,0),true);String player=((PlayerRef)Args.get(a,0)).id.toString();
                String action=Args.str(in,a,1,"action");int slot;
                try{slot=AbilityBindings.assign(inst.rec,records(),player,action,McUtil.clean(Args.str(a,2,action),60),inst.previousControls);}catch(IllegalArgumentException e){throw in.error(e.getMessage());}
                if(inst.dry)inst.effect("assign control "+slot+" "+action);else send(p,inst.rec);return (double)slot;
            })
            .fn("panel","(player,id,{x:0..1,y:0..1,elements:[{type:'text'|'bar'|'rect'|'texture',x,y,w,h,text,color,value,texture}]}) show/update bounded HUD panel; offsets in pixels. Max 8 panels,32 elements each.",(in,s,a)->{
                ServerPlayer p=RuntimeItems.player(in,inst,Args.get(a,0),true);String id=Args.str(in,a,1,"id");if(!id.matches("[a-zA-Z0-9_-]{1,40}"))throw in.error("Invalid panel id");
                JsonObject spec=Stdlib.toJson(Args.opts(a,2)).getAsJsonObject();validatePanel(spec);
                if(inst.dry){inst.effect("HUD panel "+id);return true;}
                var panels=RuntimeState.section(inst.rec,"panels");String key=p.getUUID()+":"+id;if(panels.size()>=8&&!panels.has(key))throw in.error("Panel limit reached");spec.addProperty("player",p.getUUID().toString());panels.add(key,spec);send(p,inst.rec);return true;
            })
            .fn("clear","(player) remove this script's panels and controls for player",(in,s,a)->{
                ServerPlayer p=RuntimeItems.player(in,inst,Args.get(a,0),true);if(!inst.dry){for(String section:List.of("panels","controls"))RuntimeState.section(inst.rec,section).entrySet().removeIf(e->e.getValue().getAsJsonObject().get("player").getAsString().equals(p.getUUID().toString()));send(p,inst.rec);}return true;
            });
    }
    private static Collection<ScriptRecord> records(){return WishEngine.get()==null?List.of():WishEngine.get().state().scripts.values();}
    public static void validateReceiver(ScriptInstance inst){
        if(RuntimeState.section(inst.rec,"controls").size()>0&&inst.listeners.getOrDefault("ability_input",List.of()).isEmpty())
            throw inst.in.error("Ability keys are bound but no ability_input handler is registered. Register on('ability_input', ...) at top level before activating.");
    }
    public static void validatePanel(JsonObject spec) {
        if(spec.toString().length()>10000||!spec.has("elements")||!spec.get("elements").isJsonArray()||spec.getAsJsonArray("elements").size()>32)throw new IllegalArgumentException("Panel needs at most 32 elements and 10000 characters");
        for(String axis:List.of("x","y")){double value=spec.has(axis)?spec.get(axis).getAsDouble():0;if(!Double.isFinite(value)||value<0||value>1)throw new IllegalArgumentException("Panel anchors must be 0..1");}
        for(JsonElement el:spec.getAsJsonArray("elements")){
            JsonObject e=el.getAsJsonObject();if(!e.has("type")||!Set.of("text","bar","rect","texture").contains(e.get("type").getAsString()))throw new IllegalArgumentException("Unknown panel element type");
            for(String k:List.of("x","y","w","h","value"))if(e.has(k)){double v=e.get(k).getAsDouble();if(!Double.isFinite(v)||Math.abs(v)>1000||(k.equals("w")||k.equals("h"))&&v<0||k.equals("value")&&(v<0||v>1))throw new IllegalArgumentException("Invalid panel coordinate/value");}
            if(e.has("text")&&e.get("text").getAsString().length()>160)throw new IllegalArgumentException("Panel text too long");
            for(String key:List.of("color","texture"))if(e.has(key)&&(!e.get(key).isJsonPrimitive()||!e.getAsJsonPrimitive(key).isString()||e.get(key).getAsString().length()>120))throw new IllegalArgumentException("Panel "+key+" must be a short string");
        }
    }
    private static void send(ServerPlayer p,ScriptRecord rec) {
        JsonObject packet=new JsonObject();packet.addProperty("op","runtime_ui");packet.addProperty("script",rec.id);JsonArray controls=new JsonArray(),panels=new JsonArray();
        if(rec.state.live())for(String name:List.of("controls","panels"))for(var entry:RuntimeState.section(rec,name).entrySet())if(entry.getValue().getAsJsonObject().get("player").getAsString().equals(p.getUUID().toString()))(name.equals("controls")?controls:panels).add(entry.getValue());
        packet.add("controls",controls);packet.add("panels",panels);WishEngine.get().ui().send(p,packet);
    }
    public static void sync(ServerPlayer p){for(ScriptRecord rec:WishEngine.get().state().scripts.values())if(rec.state.live())send(p,rec);}
    public static void cleanup(ScriptRecord rec){for(ServerPlayer p:WishEngine.get().server().getPlayerList().getPlayers())send(p,rec);LAST_INPUT.keySet().removeIf(k->k.startsWith(rec.id+"|"));}
    public static void input(ServerPlayer p,String json) {
        WishEngine eng=WishEngine.get();if(eng==null||!eng.aiEnabled()||json.length()>300)return;
        try {
            JsonObject message=JsonParser.parseString(json).getAsJsonObject();String id=message.get("script").getAsString();int slot=message.get("slot").getAsInt();boolean pressed=message.get("pressed").getAsBoolean();
            ScriptRecord rec=eng.state().scripts.get(id);if(rec==null||!rec.state.live())return;
            JsonElement found=RuntimeState.section(rec,"controls").get(p.getUUID()+":"+slot);if(found==null)return;
            String rate=id+"|"+p.getUUID()+"|"+slot+"|"+pressed;long now=eng.tick();if(now-LAST_INPUT.getOrDefault(rate,-100L)<2)return;LAST_INPUT.put(rate,now);
            eng.scripts().emit("ability_input",new JsObject().put("player",PlayerRef.of(p)).put("action",found.getAsJsonObject().get("action").getAsString()).put("pressed",pressed),new ScriptManager.Ctx(p.getUUID(),null,null,null,id));
        }catch(RuntimeException ignored){ }
    }
}
