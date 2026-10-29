package dev.newyear.countdown.ai.script;

import dev.newyear.countdown.ai.script.lang.Interpreter;
import dev.newyear.countdown.ai.script.lang.Values;
import dev.newyear.countdown.ai.wish.StatusEntry;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * AI 가 만드는 작은 픽셀 아이콘 (최대 12x12). 클라이언트가 그대로 그려 모든 플레이어에게 같은 모습으로 보인다.
 * palette 는 "문자=색" 쌍을 ';' 로 이어 저장한다 (예: "#=ffcc00;o=ffffff").
 */
public final class Icons {
    private Icons() {}

    public static void apply(StatusEntry st, Values.JsObject icon) {
        if (!(icon.get("pixels") instanceof Values.JsArray rows)) return;
        List<String> out = new ArrayList<>();
        for (Object r : rows.list) {
            if (out.size() >= 12) break;
            String row = Interpreter.toStr(r);
            out.add(row.length() > 12 ? row.substring(0, 12) : row);
        }
        StringBuilder pal = new StringBuilder();
        if (icon.get("palette") instanceof Values.JsObject p) {
            int n = 0;
            for (Map.Entry<String, Object> e : p.map.entrySet()) {
                if (e.getKey().length() != 1 || n++ >= 8) continue;
                int c = dev.newyear.countdown.ai.script.api.McUtil.color(Interpreter.toStr(e.getValue()), 0xFFFFFF);
                if (pal.length() > 0) pal.append(';');
                pal.append(e.getKey()).append('=').append(String.format("%06x", c));
            }
        }
        st.iconPixels = out.toArray(new String[0]);
        st.iconPalette = pal.toString();
    }
}
