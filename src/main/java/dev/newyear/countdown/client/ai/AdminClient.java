package dev.newyear.countdown.client.ai;

import com.google.gson.JsonObject;
import dev.newyear.countdown.ai.net.AiPackets;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;

/**
 * 관리자 화면의 클라이언트 상태. 서버가 보낸 {"op":"admin","kind":...} 응답을 종류별로 들고 있고, 화면은 이것만 그린다.
 * 권한은 서버가 매 요청마다 확인한다 (여기서 막는 것은 편의일 뿐이다).
 */
public final class AdminClient {
    private AdminClient() {}

    public static JsonObject overview, scripts, script, wishes, memory, preview, editResult, config, configResult, sessionKeyResult;
    public static String message = "";
    public static long messageAt;
    public static String scriptFilter = "live";

    public static void clear() {
        overview = scripts = script = wishes = memory = preview = editResult = config = configResult = sessionKeyResult = null;
        message = "";
    }

    public static void send(JsonObject req) {
        if (ClientPlayNetworking.canSend(AiPackets.AdminC2S.ID)) ClientPlayNetworking.send(new AiPackets.AdminC2S(req.toString()));
    }

    public static void request(String op) {
        JsonObject o = new JsonObject();
        o.addProperty("op", op);
        send(o);
    }

    public static void request(String op, String key, String value) {
        JsonObject o = new JsonObject();
        o.addProperty("op", op);
        o.addProperty(key, value);
        send(o);
    }

    public static void scriptAction(String id, String action) {
        JsonObject o = new JsonObject();
        o.addProperty("op", "script_action");
        o.addProperty("id", id);
        o.addProperty("action", action);
        send(o);
    }

    public static void edit(String id, String code, boolean apply) {
        JsonObject o = new JsonObject();
        o.addProperty("op", "script_edit");
        o.addProperty("id", id);
        o.addProperty("code", code);
        o.addProperty("apply", apply);
        o.addProperty("reason", apply ? "edited in admin GUI" : "check");
        send(o);
    }

    /** 단축키: 서버에 개요를 물어보고, 관리자이면 화면이 열린다. */
    public static void openRequest() {
        request("open");
    }

    public static void apply(JsonObject o) {
        String kind = o.has("kind") ? o.get("kind").getAsString() : "";
        Minecraft mc = Minecraft.getInstance();
        switch (kind) {
            case "open" -> {
                overview = o;
                if (!(mc.gui.screen() instanceof AdminScreen)) mc.gui.setScreen(new AdminScreen());
            }
            case "overview" -> overview = o;
            case "config" -> config = o;
            case "session_key" -> {
                sessionKeyResult = o;
                request("overview");
            }
            case "config_result" -> {
                configResult = o;
                message = o.has("text") ? o.get("text").getAsString() : "";
                messageAt = System.currentTimeMillis();
                if (o.has("ok") && o.get("ok").getAsBoolean()) {
                    config = o;
                    request("overview");
                }
            }
            case "scripts" -> scripts = o;
            case "script" -> script = o;
            case "wishes" -> wishes = o;
            case "memory" -> memory = o;
            case "preview_data" -> {
                preview = o;
                if (!(mc.gui.screen() instanceof BlockPreviewScreen)) mc.gui.setScreen(new BlockPreviewScreen(mc.gui.screen(), BlockPreview.parse(o)));
                else ((BlockPreviewScreen) mc.gui.screen()).setPreview(BlockPreview.parse(o));
            }
            case "edit_result" -> {
                editResult = o;
                message = o.has("text") ? o.get("text").getAsString() : "";
                messageAt = System.currentTimeMillis();
                if (o.has("applied") && o.get("applied").getAsBoolean() && o.has("id")) request("script", "id", o.get("id").getAsString());
            }
            case "message" -> {
                message = o.has("text") ? o.get("text").getAsString() : "";
                messageAt = System.currentTimeMillis();
                String refresh = o.has("refresh") ? o.get("refresh").getAsString() : "";
                switch (refresh) {
                    case "overview" -> request("overview");
                    case "scripts" -> {
                        script = null;
                        request("scripts", "filter", scriptFilter);
                    }
                    case "script" -> {
                        if (o.has("id")) request("script", "id", o.get("id").getAsString());
                        request("scripts", "filter", scriptFilter);
                    }
                    case "wishes" -> request("wishes", "filter", "open");
                    case "memory" -> {
                        if (memory != null && memory.has("who")) request("memory", "who", memory.get("who").getAsString());
                    }
                    default -> { }
                }
            }
            default -> { }
        }
    }
}
