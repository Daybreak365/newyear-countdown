package dev.newyear.countdown.ai.script.api;

import dev.newyear.countdown.ai.script.lang.Interpreter;
import dev.newyear.countdown.ai.script.lang.Values;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/** 고정된 멤버를 가진 API 객체 (world, fx, ui …). 값 멤버는 읽을 때마다 계산할 수도 있다. */
public final class ApiObject implements Values.HostObject {
    private final String type;
    private final LinkedHashMap<String, Object> members = new LinkedHashMap<>();
    private final Map<String, Supplier<Object>> getters = new LinkedHashMap<>();
    /** 문서용 한 줄 설명. */
    private final Map<String, String> docs = new LinkedHashMap<>();

    public ApiObject(String type) {
        this.type = type;
    }

    public ApiObject fn(String name, String doc, Values.NativeFn.Impl impl) {
        members.put(name, new Values.NativeFn(type.toLowerCase() + "." + name, impl));
        docs.put(name, doc);
        return this;
    }

    public ApiObject value(String name, String doc, Object v) {
        members.put(name, v);
        docs.put(name, doc);
        return this;
    }

    public ApiObject getter(String name, String doc, Supplier<Object> g) {
        getters.put(name, g);
        docs.put(name, doc);
        return this;
    }

    @Override
    public Object getMember(Interpreter in, String name) {
        Supplier<Object> g = getters.get(name);
        if (g != null) return g.get();
        Object v = members.get(name);
        return v == null && !members.containsKey(name) ? Values.UNDEFINED : v;
    }

    @Override
    public Collection<String> memberNames() {
        java.util.List<String> all = new java.util.ArrayList<>(members.keySet());
        all.addAll(getters.keySet());
        return all;
    }

    @Override
    public String typeName() {
        return type;
    }

    public Map<String, String> docs() {
        return docs;
    }
}
