package dev.newyear.countdown.ai.extension;

import dev.newyear.countdown.ai.WishEngine;
import dev.newyear.countdown.ai.script.*;
import dev.newyear.countdown.ai.script.api.*;
import dev.newyear.countdown.ai.script.lang.Interpreter;
import net.minecraft.server.level.ServerPlayer;

/** Native code is trusted code, not a sandbox. This context supplies ownership and lifecycle helpers. */
public record ExtensionContext(WishEngine engine, ScriptInstance script, Interpreter interpreter) {
    public ServerPlayer owner() {
        if(script.rec.owner==null)throw interpreter.error("This operation needs a wish owner");
        ServerPlayer player=engine.player(java.util.UUID.fromString(script.rec.owner));
        if(player==null)throw interpreter.error("Owner is offline");return player;
    }
    public void requireConsent(ServerPlayer player,String action){Policy.checkAffect(interpreter,script,PlayerRef.of(player),action,true);}
    public void requireRange(double x,double y,double z){Policy.checkRange(interpreter,script,new double[]{x,y,z});}
    public void onStop(Runnable cleanup){engine.extensions().onScriptStop(script.rec.id,cleanup);}
}
