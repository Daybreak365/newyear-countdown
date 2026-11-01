package dev.newyear.countdown.ai.script;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.newyear.countdown.ai.AiConfig;
import dev.newyear.countdown.ai.script.lang.Interpreter;
import dev.newyear.countdown.ai.script.lang.ScriptError;
import dev.newyear.countdown.ai.script.lang.Values;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * AI 가 그리는 동적 텍스처 (최대 32x32 픽셀 그림). 서버가 세계 자원으로 보관하고 모든 클라이언트에 보내면,
 * 클라이언트가 실제 GPU 텍스처로 만들어 아이템 모습(손·인벤토리·땅·아이템 디스플레이), HUD 아이콘에 쓴다.
 * 레지스트리에 새 아이템 타입을 넣을 수는 없지만, 바닐라 아이템에 이 텍스처를 입히면 새 아이템처럼 보인다.
 * 형식: 행 문자열 배열 + 팔레트 (문자 → #rrggbb 또는 #aarrggbb). '.' 과 ' ' 은 투명.
 */
public final class Textures {
    private Textures() {}

    /** 세계에 저장되는 텍스처 하나 (AiState.textures). */
    public static final class Def {
        public String id;
        public int w, h;
        public List<String> rows = new ArrayList<>();
        /** "c=aarrggbb;d=aarrggbb" */
        public String palette = "";
        public String scriptId;
        public String owner;
        public long created;
    }

    public static String normalize(String name) {
        String s = name.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "_");
        if (s.isEmpty() || s.length() > 32) throw new ScriptError(ScriptError.Kind.RUNTIME, "texture name must be 1-32 letters/digits/_", 0, 0);
        return s;
    }

    /** 스크립트 명세 → 텍스처. 크기·색 수를 검사한다. */
    public static Def parse(Interpreter in, String name, Values.JsObject spec) {
        int max = AiConfig.get().dynamicTextureMaxSize;
        if (!(spec.get("pixels") instanceof Values.JsArray rows) || rows.list.isEmpty()) throw in.error("texture needs pixels: ['..##..', ...]");
        if (rows.list.size() > max) throw in.error("texture too tall (max " + max + " rows)");
        Def d = new Def();
        d.id = normalize(name);
        int w = 0;
        for (Object r : rows.list) {
            String row = Interpreter.toStr(r);
            if (row.length() > max) throw in.error("texture row too wide (max " + max + " pixels)");
            w = Math.max(w, row.length());
            d.rows.add(row);
        }
        d.w = w;
        d.h = d.rows.size();
        StringBuilder pal = new StringBuilder();
        if (spec.get("palette") instanceof Values.JsObject p) {
            int n = 0;
            for (Map.Entry<String, Object> e : p.map.entrySet()) {
                if (e.getKey().length() != 1) throw in.error("palette keys must be single characters");
                if (++n > 24) throw in.error("too many palette colours (max 24)");
                int argb = argb(Interpreter.toStr(e.getValue()));
                if (pal.length() > 0) pal.append(';');
                pal.append(e.getKey()).append('=').append(String.format("%08x", argb));
            }
        }
        if (pal.isEmpty()) throw in.error("texture needs a palette: {'#': '#ffcc00', ...}");
        d.palette = pal.toString();
        return d;
    }

    /** "#rgb", "#rrggbb", "#aarrggbb" 또는 색 이름 → ARGB (불투명 기본). */
    public static int argb(String s) {
        String t = s.trim();
        if (t.startsWith("#") && t.length() == 9) {
            try {
                return (int) Long.parseLong(t.substring(1), 16);
            } catch (NumberFormatException ignored) { }
        }
        return 0xFF000000 | dev.newyear.countdown.ai.script.api.McUtil.color(t, 0xFFFFFF);
    }

    public static JsonObject toJson(Def d) {
        JsonObject o = new JsonObject();
        o.addProperty("op", "texture");
        o.addProperty("id", d.id);
        o.addProperty("w", d.w);
        o.addProperty("h", d.h);
        JsonArray rows = new JsonArray();
        for (String r : d.rows) rows.add(r);
        o.add("pixels", rows);
        o.addProperty("palette", d.palette);
        return o;
    }
}
