package wishai.generated;

import com.google.gson.JsonObject;
import dev.newyear.countdown.ai.extension.ExtensionContext;
import dev.newyear.countdown.ai.extension.NativeExtension;
import java.util.Map;

/** Native SPI example; simple movement should normally use WishScript's existing primitives. */
public final class Momentum implements NativeExtension {
    public Map<String,String> operations() {
        return Map.of("dash", "{x:number,z:number}, each -1..1; adds horizontal motion to the consenting wish owner; returns {applied:true}");
    }
    private static double axis(JsonObject args,String name) {
        double n=args.has(name)?args.get(name).getAsDouble():0;
        if(!Double.isFinite(n)||Math.abs(n)>1)throw new IllegalArgumentException("Axis must be -1..1");
        return n;
    }
    public JsonObject preview(String operation,JsonObject args) {
        if(!operation.equals("dash"))throw new IllegalArgumentException("Unknown operation");
        axis(args,"x");axis(args,"z");
        JsonObject result=new JsonObject();result.addProperty("effect","Add horizontal motion to the owner");return result;
    }
    public JsonObject invoke(String operation,ExtensionContext ctx,JsonObject args) {
        preview(operation,args);
        var owner=ctx.owner();ctx.requireConsent(owner,"dash");ctx.requireRange(owner.getX(),owner.getY(),owner.getZ());
        owner.push(axis(args,"x"),0,axis(args,"z"));owner.syncVelocity=true;
        JsonObject result=new JsonObject();result.addProperty("applied",true);return result;
    }
    public Map<String,Boolean> selfTest() {
        JsonObject input=new JsonObject();input.addProperty("x",0.5);
        boolean valid=axis(input,"x")==0.5&&axis(input,"z")==0;
        input.addProperty("x",Double.NaN);boolean rejected=false;
        try{axis(input,"x");}catch(IllegalArgumentException e){rejected=true;}
        return Map.of("finite input",valid,"reject NaN",rejected);
    }
    public Map<String,Boolean> integrationTest(ExtensionContext ctx) {
        var player=ctx.owner();var original=player.getDeltaMovement();
        try{
            JsonObject input=new JsonObject();input.addProperty("x",0.5);input.addProperty("z",0.25);
            JsonObject result=invoke("dash",ctx,input);
            return Map.of("operation result",result.get("applied").getAsBoolean(),
                "world velocity changed",Math.abs(player.getDeltaMovement().x-original.x-0.5)<1e-6);
        }finally{player.setDeltaMovement(original);}
    }
}
