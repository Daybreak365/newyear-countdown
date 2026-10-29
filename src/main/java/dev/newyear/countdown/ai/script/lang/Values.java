package dev.newyear.countdown.ai.script.lang;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 스크립트 값 표현.
 * <ul>
 *   <li>undefined → {@link Undefined#INSTANCE}, null → Java null</li>
 *   <li>boolean → Boolean, number → Double, string → String</li>
 *   <li>배열 → {@link JsArray}, 객체 → {@link JsObject}, 함수 → {@link Callable}</li>
 *   <li>게임 API 객체 → {@link HostObject} (스크립트는 자바 객체에 직접 닿을 수 없다)</li>
 * </ul>
 */
public final class Values {
    private Values() {}

    public enum Undefined {
        INSTANCE;

        @Override
        public String toString() {
            return "undefined";
        }
    }

    public static final Object UNDEFINED = Undefined.INSTANCE;

    public static final class JsArray {
        public final List<Object> list;

        public JsArray() {
            this.list = new ArrayList<>();
        }

        public JsArray(List<Object> list) {
            this.list = list;
        }

        public static JsArray of(Collection<?> items) {
            return new JsArray(new ArrayList<>(items));
        }
    }

    public static final class JsObject {
        public final LinkedHashMap<String, Object> map;
        /** 굳힌 객체(API 상수 묶음 등)는 수정할 수 없다. */
        public boolean frozen;

        public JsObject() {
            this.map = new LinkedHashMap<>();
        }

        public JsObject(Map<String, Object> m) {
            this.map = new LinkedHashMap<>(m);
        }

        public JsObject put(String k, Object v) {
            map.put(k, v);
            return this;
        }

        public Object get(String k) {
            Object v = map.get(k);
            return v == null && !map.containsKey(k) ? UNDEFINED : v;
        }
    }

    /** 호출 가능한 값. self 는 메서드 호출일 때의 수신 객체 (this). */
    public interface Callable {
        Object call(Interpreter in, Object self, List<Object> args);

        String name();
    }

    /** 자바로 구현한 함수. */
    public record NativeFn(String name, Impl impl) implements Callable {
        @FunctionalInterface
        public interface Impl {
            Object call(Interpreter in, Object self, List<Object> args);
        }

        @Override
        public Object call(Interpreter in, Object self, List<Object> args) {
            return impl.call(in, self, args);
        }
    }

    /**
     * 게임 API 가 스크립트에 노출하는 객체. 스크립트는 여기서 돌려주는 값 외에는 아무것도 볼 수 없다.
     */
    public interface HostObject {
        /** 없는 멤버는 UNDEFINED. */
        Object getMember(Interpreter in, String name);

        /** 쓸 수 있으면 true. 기본은 읽기 전용. */
        default boolean setMember(Interpreter in, String name, Object value) {
            return false;
        }

        /** 오류 메시지·자동 완성용 멤버 목록. */
        Collection<String> memberNames();

        /** typeof 와 오류 메시지에 쓰는 이름 (예: "Player"). */
        String typeName();
    }
}
