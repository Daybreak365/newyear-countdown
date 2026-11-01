package dev.newyear.countdown.client.ai;

import com.google.gson.*;
import com.mojang.blaze3d.platform.InputConstants;
import dev.newyear.countdown.ModReg;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.*;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import java.util.*;

public final class RuntimeHud {
    private RuntimeHud() {}
    private static final Map<String,JsonObject> SCRIPTS=new LinkedHashMap<>();
    private static final KeyMapping[] KEYS=new KeyMapping[4];
    private static final boolean[] DOWN=new boolean[4];
    public static void register(){
        var category=KeyMapping.Category.register(ModReg.id("abilities"));
        for(int i=0;i<4;i++)KEYS[i]=KeyMappingHelper.registerKeyMapping(new KeyMapping("key.newyearcountdown.ability_"+(i+1),InputConstants.Type.KEYBOARD,InputConstants.UNKNOWN.getValue(),category));
    }
    public static void clear(){SCRIPTS.clear();Arrays.fill(DOWN,false);}
    public static void apply(JsonObject packet){String id=packet.get("script").getAsString();if(packet.getAsJsonArray("controls").isEmpty()&&packet.getAsJsonArray("panels").isEmpty())SCRIPTS.remove(id);else if(SCRIPTS.size()<64||SCRIPTS.containsKey(id))SCRIPTS.put(id,packet.deepCopy());}
    public static void tick(Minecraft mc){
        if(mc.player==null){clear();return;}
        for(int i=0;i<4;i++){
            boolean down=mc.gui.screen()==null&&KEYS[i]!=null&&KEYS[i].isDown();if(down==DOWN[i])continue;DOWN[i]=down;
            for(var entry:new ArrayList<>(SCRIPTS.entrySet()))for(JsonElement el:entry.getValue().getAsJsonArray("controls"))if(el.getAsJsonObject().get("slot").getAsInt()==i){
                JsonObject msg=new JsonObject();msg.addProperty("script",entry.getKey());msg.addProperty("slot",i);msg.addProperty("pressed",down);AiClientState.action("ability_input",msg.toString());
            }
        }
    }
    private static int n(JsonObject o,String k,int fallback){return o.has(k)?o.get(k).getAsInt():fallback;}
    private static String s(JsonObject o,String k,String fallback){return o.has(k)?o.get(k).getAsString():fallback;}
    public static void render(GuiGraphicsExtractor g){
        Minecraft mc=Minecraft.getInstance();if(mc.player==null)return;int keyY=g.guiHeight()-65;
        for(JsonObject script:SCRIPTS.values()){
            for(JsonElement control:script.getAsJsonArray("controls")){
                JsonObject c=control.getAsJsonObject();int slot=c.get("slot").getAsInt();String key=KEYS[slot]==null||KEYS[slot].isUnbound()?net.minecraft.network.chat.Component.translatable("gui.newyearcountdown.ability_unbound",slot+1).getString():KEYS[slot].getTranslatedKeyMessage().getString();g.text(mc.font,c.get("label").getAsString()+" ["+key+"]",8,keyY,0xffdfcfff,true);keyY-=11;
            }
            for(JsonElement panel:script.getAsJsonArray("panels")){
                JsonObject p=panel.getAsJsonObject();int ax=(int)((p.has("x")?p.get("x").getAsDouble():0)*g.guiWidth()),ay=(int)((p.has("y")?p.get("y").getAsDouble():0)*g.guiHeight());
                for(JsonElement element:p.getAsJsonArray("elements")){
                    JsonObject e=element.getAsJsonObject();int x=ax+n(e,"x",0),y=ay+n(e,"y",0),w=Math.max(0,n(e,"w",80)),h=Math.max(0,n(e,"h",8));int color=dev.newyear.countdown.ai.script.Textures.argb(s(e,"color","#ffffff"));
                    switch(s(e,"type","text")){
                        case "text"->g.text(mc.font,s(e,"text",""),x,y,color,true);
                        case "rect"->g.fill(x,y,x+w,y+h,color);
                        case "bar"->{g.fill(x,y,x+w,y+h,0x90000000);double value=e.has("value")?e.get("value").getAsDouble():0;g.fill(x,y,x+(int)(w*Math.clamp(value,0,1)),y+h,color);}
                        case "texture"->{var texture=DynamicTextures.get(s(e,"texture",""));if(texture!=null)g.blit(texture.location,x,y,x+w,y+h,0f,1f,0f,1f);}
                    }
                }
            }
        }
    }
}
