package dev.newyear.countdown.ai.script.lang;

import dev.newyear.countdown.ai.script.lang.Node.*;
import dev.newyear.countdown.ai.script.lang.Values.Callable;
import dev.newyear.countdown.ai.script.lang.Values.HostObject;
import dev.newyear.countdown.ai.script.lang.Values.JsArray;
import dev.newyear.countdown.ai.script.lang.Values.JsObject;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static dev.newyear.countdown.ai.script.lang.Values.UNDEFINED;

/**
 * 트리 순회 인터프리터. 스크립트는 이 클래스가 제공하는 값과 {@link HostObject} 만 볼 수 있다.
 * 자바 리플렉션·파일·네트워크·스레드에 닿는 경로가 언어 차원에서 존재하지 않는다.
 * 모든 노드 평가는 {@link Budget} 을 소모한다.
 */
public final class Interpreter {
    public static final int MAX_STRING = 100_000;
    public static final int MAX_ARRAY = 20_000;

    /** 변수 저장 공간. */
    public static final class Scope {
        final Scope parent;
        final HashMap<String, Binding> vars = new HashMap<>(8);

        Scope(Scope parent) {
            this.parent = parent;
        }

        Binding find(String name) {
            for (Scope s = this; s != null; s = s.parent) {
                Binding b = s.vars.get(name);
                if (b != null) return b;
            }
            return null;
        }
    }

    static final class Binding {
        Object value;
        final boolean constant;

        Binding(Object value, boolean constant) {
            this.value = value;
            this.constant = constant;
        }
    }

    private static final class BreakSignal extends RuntimeException {
        BreakSignal() { super(null, null, false, false); }
    }

    private static final class ContinueSignal extends RuntimeException {
        ContinueSignal() { super(null, null, false, false); }
    }

    private static final class ReturnSignal extends RuntimeException {
        final Object value;
        ReturnSignal(Object value) { super(null, null, false, false); this.value = value; }
    }

    private static final BreakSignal BREAK = new BreakSignal();
    private static final ContinueSignal CONTINUE = new ContinueSignal();

    /** 스크립트가 정의한 함수 (클로저). */
    public static final class ScriptFunction implements Callable {
        final Func decl;
        final Scope closure;
        final Object lexicalThis;

        ScriptFunction(Func decl, Scope closure, Object lexicalThis) {
            this.decl = decl;
            this.closure = closure;
            this.lexicalThis = lexicalThis;
        }

        @Override
        public String name() {
            return decl.name() == null ? "anonymous" : decl.name();
        }

        @Override
        public Object call(Interpreter in, Object self, List<Object> args) {
            return in.invokeScript(this, self, args);
        }
    }

    public final Scope globals = new Scope(null);
    public Budget budget = Budget.defaults();
    /** 호스트(게임 API)가 실행 맥락을 붙여 두는 자리. 스크립트에서는 보이지 않는다. */
    public Object hostContext;
    /** console.log 출력 (최근 것만). */
    public final List<String> log = new ArrayList<>();
    public int maxLogLines = 60;
    /** 현재 실행 중인 노드 (호스트 함수 오류 위치 표시용). */
    private Node current;

    public Interpreter() {
        Stdlib.install(this);
    }

    // ---------------------------------------------------------------- 공개 API

    /** 읽기 전용 전역 값을 정의한다. */
    public void define(String name, Object value) {
        globals.vars.put(name, new Binding(value, true));
    }

    public boolean isDefined(String name) {
        return globals.vars.containsKey(name);
    }

    public Object getGlobal(String name) {
        Binding b = globals.vars.get(name);
        return b == null ? UNDEFINED : b.value;
    }

    /** 프로그램 최상위 코드 실행. 예산 초기화는 호출하는 쪽에서 한다. */
    public void run(Program program) {
        Scope top = new Scope(globals);
        try {
            execBlockBody(program.body(), top);
        } catch (ReturnSignal r) {
            // 최상위 return 은 그냥 종료로 취급
        } catch (BreakSignal | ContinueSignal s) {
            throw new ScriptError(ScriptError.Kind.SYNTAX, "break/continue outside of a loop", 0, 0);
        } catch (StackOverflowError so) {
            throw new ScriptError(ScriptError.Kind.BUDGET, "Recursion too deep", pos(), col());
        }
    }

    /** 호스트에서 스크립트 함수를 부른다 (이벤트 핸들러, 타이머 등). */
    public Object invoke(Callable fn, List<Object> args) {
        try {
            return callFn(fn, UNDEFINED, args);
        } catch (BreakSignal | ContinueSignal s) {
            throw new ScriptError(ScriptError.Kind.SYNTAX, "break/continue outside of a loop", 0, 0);
        } catch (StackOverflowError so) {
            throw new ScriptError(ScriptError.Kind.BUDGET, "Recursion too deep", pos(), col());
        }
    }

    public void print(String line) {
        if (line.length() > 500) line = line.substring(0, 500) + "…";
        log.add(line);
        while (log.size() > maxLogLines) log.remove(0);
    }

    int pos() {
        return current == null ? 0 : current.line();
    }

    int col() {
        return current == null ? 0 : current.col();
    }

    /** 호스트 함수에서 쓰는 오류 생성기 (현재 위치를 붙인다). */
    public ScriptError error(String msg) {
        return new ScriptError(ScriptError.Kind.RUNTIME, msg, pos(), col());
    }

    // ---------------------------------------------------------------- 문장 실행

    private void hoist(List<Node> body, Scope scope) {
        for (Node n : body) {
            if (n instanceof FuncDecl fd) {
                scope.vars.put(fd.func().name(), new Binding(new ScriptFunction(fd.func(), scope, null), false));
            }
        }
    }

    private void execBlockBody(List<Node> body, Scope scope) {
        hoist(body, scope);
        for (Node n : body) exec(n, scope);
    }

    private void exec(Node n, Scope scope) {
        budget.step();
        current = n;
        switch (n) {
            case ExprStmt e -> eval(e.expr(), scope);
            case VarDecl d -> {
                boolean konst = d.kind().equals("const");
                for (Declarator dc : d.decls()) {
                    Object v = dc.init() == null ? UNDEFINED : eval(dc.init(), scope);
                    if (dc.init() instanceof Func f && f.name() == null && dc.target() instanceof Ident id && v instanceof ScriptFunction) {
                        // 익명 함수에 변수 이름을 붙인다 (오류 메시지용)
                        v = new ScriptFunction(new Func(f.line(), f.col(), id.name(), f.params(), f.body(), f.arrow(), f.exprBody()),
                                ((ScriptFunction) v).closure, ((ScriptFunction) v).lexicalThis);
                    }
                    bindPattern(dc.target(), v, scope, true, konst);
                }
            }
            case FuncDecl fd -> { /* hoist 에서 처리 */ }
            case Return r -> throw new ReturnSignal(r.value() == null ? UNDEFINED : eval(r.value(), scope));
            case If i -> {
                if (truthy(eval(i.test(), scope))) exec(i.then(), scope);
                else if (i.otherwise() != null) exec(i.otherwise(), scope);
            }
            case Block b -> execBlockBody(b.body(), new Scope(scope));
            case While w -> {
                while (truthy(eval(w.test(), scope))) {
                    budget.step();
                    try {
                        exec(w.body(), scope);
                    } catch (BreakSignal br) {
                        break;
                    } catch (ContinueSignal cs) {
                        // 다음 반복
                    }
                }
            }
            case DoWhile w -> {
                do {
                    budget.step();
                    try {
                        exec(w.body(), scope);
                    } catch (BreakSignal br) {
                        break;
                    } catch (ContinueSignal cs) {
                        // 다음 반복
                    }
                } while (truthy(eval(w.test(), scope)));
            }
            case For f -> execFor(f, scope);
            case ForEach f -> execForEach(f, scope);
            case Break b -> throw BREAK;
            case Continue c -> throw CONTINUE;
            case Throw t -> {
                Object v = eval(t.value(), scope);
                throw new ScriptError(ScriptError.Kind.RUNTIME, "Uncaught " + describeThrown(v), t.line(), t.col(), v);
            }
            case Try t -> execTry(t, scope);
            case Switch s -> execSwitch(s, scope);
            case Empty e -> { }
            default -> throw new ScriptError(ScriptError.Kind.SYNTAX, "Unexpected statement", n.line(), n.col());
        }
    }

    private void execFor(For f, Scope outer) {
        Scope scope = new Scope(outer);
        if (f.init() != null) exec(f.init(), scope);
        // let 루프 변수는 반복마다 새 바인딩 (클로저가 각 값을 잡도록)
        List<String> perIter = new ArrayList<>();
        if (f.init() instanceof VarDecl vd && !vd.kind().equals("var")) {
            perIter.addAll(scope.vars.keySet());
        }
        while (true) {
            budget.step();
            if (f.test() != null && !truthy(eval(f.test(), scope))) break;
            Scope body = scope;
            if (!perIter.isEmpty()) {
                body = new Scope(scope);
                for (String k : perIter) body.vars.put(k, new Binding(scope.vars.get(k).value, false));
            }
            try {
                exec(f.body(), body);
            } catch (BreakSignal br) {
                break;
            } catch (ContinueSignal cs) {
                // 다음 반복
            } finally {
                if (body != scope) for (String k : perIter) scope.vars.get(k).value = body.vars.get(k).value;
            }
            if (f.update() != null) eval(f.update(), scope);
        }
    }

    private void execForEach(ForEach f, Scope outer) {
        Object it = eval(f.iterable(), outer);
        List<Object> items;
        if (f.of()) {
            items = iterate(it, f);
        } else {
            items = new ArrayList<>();
            if (it instanceof JsObject o) items.addAll(o.map.keySet());
            else if (it instanceof JsArray a) for (int i = 0; i < a.list.size(); i++) items.add(numToString(i));
            else if (it instanceof HostObject h) items.addAll(h.memberNames());
            else if (it == null || it == UNDEFINED) throw new ScriptError(ScriptError.Kind.RUNTIME, "Cannot iterate over " + typeName(it) + " with for...in", f.line(), f.col());
        }
        int n = 0;
        for (int i = 0; ; i++) {
            Object v;
            if (f.of() && it instanceof JsArray a) {
                if (i >= a.list.size()) break;   // 배열은 반복 중 변경을 반영한다
                v = a.list.get(i);
            } else {
                if (i >= items.size()) break;
                v = items.get(i);
            }
            budget.step();
            Scope body = new Scope(outer);
            if (f.kind() != null) bindPattern(f.target(), v, body, true, f.kind().equals("const"));
            else assignTo(f.target(), v, outer);
            try {
                exec(f.body(), body);
            } catch (BreakSignal br) {
                break;
            } catch (ContinueSignal cs) {
                // 다음
            }
            n++;
        }
    }

    private List<Object> iterate(Object it, Node at) {
        if (it instanceof JsArray a) return a.list;
        if (it instanceof String s) {
            List<Object> chars = new ArrayList<>();
            s.codePoints().forEach(cp -> chars.add(new String(Character.toChars(cp))));
            return chars;
        }
        if (it instanceof HostObject h) {
            Object arr = h.getMember(this, "toArray");
            if (arr instanceof Callable c) {
                Object r = callFn(c, h, List.of());
                if (r instanceof JsArray ja) return ja.list;
            }
        }
        String hint = it instanceof JsObject ? " (use Object.keys(obj), Object.values(obj) or Object.entries(obj))" : "";
        throw new ScriptError(ScriptError.Kind.RUNTIME, typeName(it) + " is not iterable" + hint, at.line(), at.col());
    }

    private void execTry(Try t, Scope scope) {
        try {
            exec(t.block(), scope);
        } catch (ScriptError e) {
            // 예산 초과는 잡을 수 없다 (무한 반복을 try 로 감추지 못하게)
            if (e.kind == ScriptError.Kind.BUDGET || t.handler() == null) throw e;
            Scope cs = new Scope(scope);
            if (t.catchParam() != null) {
                Object v = e.thrown != null ? e.thrown : errorObject(e);
                bindPattern(t.catchParam(), v, cs, true, false);
            }
            execBlockBody(t.handler().body(), cs);
        } finally {
            if (t.finalizer() != null) exec(t.finalizer(), scope);
        }
    }

    private void execSwitch(Switch s, Scope outer) {
        Object d = eval(s.discriminant(), outer);
        Scope scope = new Scope(outer);
        int start = -1;
        for (int i = 0; i < s.cases().size(); i++) {
            Case c = s.cases().get(i);
            if (c.test() != null && strictEquals(d, eval(c.test(), scope))) { start = i; break; }
        }
        if (start < 0) {
            for (int i = 0; i < s.cases().size(); i++) if (s.cases().get(i).test() == null) { start = i; break; }
        }
        if (start < 0) return;
        try {
            for (int i = start; i < s.cases().size(); i++) {
                List<Node> body = s.cases().get(i).body();
                hoist(body, scope);
                for (Node n : body) exec(n, scope);
            }
        } catch (BreakSignal br) {
            // switch 끝
        }
    }

    // ---------------------------------------------------------------- 패턴 바인딩

    private void bindPattern(Node target, Object v, Scope scope, boolean declare, boolean konst) {
        switch (target) {
            case Ident id -> {
                if (declare) {
                    if (scope.vars.containsKey(id.name()) && scope.vars.get(id.name()).constant) {
                        throw new ScriptError(ScriptError.Kind.RUNTIME, "'" + id.name() + "' has already been declared", id.line(), id.col());
                    }
                    scope.vars.put(id.name(), new Binding(v, konst));
                } else assignTo(id, v, scope);
            }
            case ObjPattern op -> {
                if (v == null || v == UNDEFINED) {
                    throw new ScriptError(ScriptError.Kind.RUNTIME, "Cannot destructure " + typeName(v), op.line(), op.col());
                }
                List<String> used = new ArrayList<>();
                for (PatProp pp : op.props()) {
                    String key = pp.key() != null ? pp.key() : toStr(eval(pp.keyExpr(), scope));
                    used.add(key);
                    Object pv = getMember(v, key, op);
                    if (pv == UNDEFINED && pp.defaultValue() != null) pv = eval(pp.defaultValue(), scope);
                    bindPattern(pp.target(), pv, scope, declare, konst);
                }
                if (op.rest() != null) {
                    JsObject rest = new JsObject();
                    if (v instanceof JsObject o) for (Map.Entry<String, Object> e : o.map.entrySet()) if (!used.contains(e.getKey())) rest.map.put(e.getKey(), e.getValue());
                    bindPattern(op.rest(), rest, scope, declare, konst);
                }
            }
            case ArrPattern ap -> {
                List<Object> list = iterate(v, ap);
                for (int i = 0; i < ap.elems().size(); i++) {
                    Node el = ap.elems().get(i);
                    if (el == null) continue;
                    Object ev = i < list.size() ? list.get(i) : UNDEFINED;
                    if (ev == UNDEFINED && ap.defaults().get(i) != null) ev = eval(ap.defaults().get(i), scope);
                    bindPattern(el, ev, scope, declare, konst);
                }
                if (ap.rest() != null) {
                    List<Object> rest = new ArrayList<>();
                    for (int i = ap.elems().size(); i < list.size(); i++) rest.add(list.get(i));
                    bindPattern(ap.rest(), new JsArray(rest), scope, declare, konst);
                }
            }
            case Member m -> assignTo(m, v, scope);
            default -> throw new ScriptError(ScriptError.Kind.SYNTAX, "Invalid binding target", target.line(), target.col());
        }
    }

    private void assignTo(Node target, Object v, Scope scope) {
        switch (target) {
            case Ident id -> {
                Binding b = scope.find(id.name());
                if (b == null) {
                    throw new ScriptError(ScriptError.Kind.RUNTIME, "'" + id.name() + "' is not declared. Use let/const first", id.line(), id.col());
                }
                if (b.constant) {
                    throw new ScriptError(ScriptError.Kind.RUNTIME, "Cannot assign to constant '" + id.name() + "'", id.line(), id.col());
                }
                b.value = v;
            }
            case Member m -> {
                Object obj = eval(m.object(), scope);
                String key = m.name() != null ? m.name() : propertyKey(eval(m.index(), scope));
                setMember(obj, key, v, m);
            }
            case ObjPattern op -> bindPattern(op, v, scope, false, false);
            case ArrPattern ap -> bindPattern(ap, v, scope, false, false);
            default -> throw new ScriptError(ScriptError.Kind.SYNTAX, "Invalid assignment target", target.line(), target.col());
        }
    }

    // ---------------------------------------------------------------- 식 평가

    Object eval(Node n, Scope scope) {
        budget.step();
        switch (n) {
            case Num x -> { return x.value(); }
            case Str x -> { return x.value(); }
            case Bool x -> { return x.value(); }
            case Null x -> { return null; }
            case Undef x -> { return UNDEFINED; }
            case Template t -> {
                StringBuilder sb = new StringBuilder(t.parts().get(0));
                for (int i = 0; i < t.exprs().size(); i++) {
                    sb.append(toStr(eval(t.exprs().get(i), scope)));
                    sb.append(t.parts().get(i + 1));
                }
                return checkString(sb.toString(), t);
            }
            case Ident id -> {
                Binding b = scope.find(id.name());
                if (b == null) {
                    throw new ScriptError(ScriptError.Kind.RUNTIME, "'" + id.name() + "' is not defined", id.line(), id.col());
                }
                return b.value;
            }
            case This t -> {
                Binding b = scope.find("this");
                return b == null ? UNDEFINED : b.value;
            }
            case ArrayLit a -> {
                List<Object> list = new ArrayList<>(a.elems().size());
                for (Node e : a.elems()) {
                    if (e instanceof Spread s) list.addAll(iterate(eval(s.arg(), scope), s));
                    else list.add(eval(e, scope));
                }
                budget.allocate(list.size() + 1);
                checkArray(list.size(), a);
                return new JsArray(list);
            }
            case ObjectLit o -> {
                JsObject obj = new JsObject();
                Binding thisB = scope.find("this");
                for (Prop p : o.props()) {
                    if (p.spread()) {
                        Object src = eval(p.value(), scope);
                        if (src instanceof JsObject so) obj.map.putAll(so.map);
                        else if (src instanceof JsArray sa) for (int i = 0; i < sa.list.size(); i++) obj.map.put(numToString(i), sa.list.get(i));
                        continue;
                    }
                    String key = p.key() != null ? p.key() : propertyKey(eval(p.keyExpr(), scope));
                    obj.map.put(key, eval(p.value(), scope));
                }
                budget.allocate(obj.map.size() + 1);
                return obj;
            }
            case Func f -> {
                Binding thisB = scope.find("this");
                return new ScriptFunction(f, scope, f.arrow() ? (thisB == null ? UNDEFINED : thisB.value) : null);
            }
            case Member m -> {
                Object obj = eval(m.object(), scope);
                if (m.optional() && (obj == null || obj == UNDEFINED)) return UNDEFINED;
                String key = m.name() != null ? m.name() : propertyKey(eval(m.index(), scope));
                return getMember(obj, key, m);
            }
            case Call c -> { return evalCall(c, scope); }
            case Unary u -> { return evalUnary(u, scope); }
            case Update u -> {
                Object old = eval(u.target(), scope);
                double ov = toNumber(old);
                double nv = u.op().equals("++") ? ov + 1 : ov - 1;
                assignTo(u.target(), nv, scope);
                return u.prefix() ? nv : ov;
            }
            case Binary b -> { return binary(b.op(), eval(b.left(), scope), eval(b.right(), scope), b); }
            case Logical l -> {
                Object left = eval(l.left(), scope);
                return switch (l.op()) {
                    case "&&" -> truthy(left) ? eval(l.right(), scope) : left;
                    case "||" -> truthy(left) ? left : eval(l.right(), scope);
                    default -> (left == null || left == UNDEFINED) ? eval(l.right(), scope) : left;
                };
            }
            case Cond c -> { return truthy(eval(c.test(), scope)) ? eval(c.then(), scope) : eval(c.otherwise(), scope); }
            case Assign a -> { return evalAssign(a, scope); }
            case Seq s -> {
                Object last = UNDEFINED;
                for (Node e : s.exprs()) last = eval(e, scope);
                return last;
            }
            case Spread s -> throw new ScriptError(ScriptError.Kind.SYNTAX, "Unexpected '...'", s.line(), s.col());
            default -> throw new ScriptError(ScriptError.Kind.SYNTAX, "Unexpected expression", n.line(), n.col());
        }
    }

    private Object evalAssign(Assign a, Scope scope) {
        String op = a.op();
        if (op.equals("=")) {
            Object v = eval(a.value(), scope);
            assignTo(a.target(), v, scope);
            return v;
        }
        Object cur = eval(a.target(), scope);
        Object v;
        switch (op) {
            case "??=" -> {
                if (cur != null && cur != UNDEFINED) return cur;
                v = eval(a.value(), scope);
            }
            case "||=" -> {
                if (truthy(cur)) return cur;
                v = eval(a.value(), scope);
            }
            case "&&=" -> {
                if (!truthy(cur)) return cur;
                v = eval(a.value(), scope);
            }
            default -> v = binary(op.substring(0, op.length() - 1), cur, eval(a.value(), scope), a);
        }
        assignTo(a.target(), v, scope);
        return v;
    }

    private Object evalUnary(Unary u, Scope scope) {
        switch (u.op()) {
            case "typeof" -> {
                if (u.arg() instanceof Ident id && scope.find(id.name()) == null) return "undefined";
                return typeOf(eval(u.arg(), scope));
            }
            case "delete" -> {
                if (u.arg() instanceof Member m) {
                    Object obj = eval(m.object(), scope);
                    String key = m.name() != null ? m.name() : propertyKey(eval(m.index(), scope));
                    if (obj instanceof JsObject o) {
                        if (o.frozen) throw new ScriptError(ScriptError.Kind.RUNTIME, "Cannot modify a read-only object", u.line(), u.col());
                        o.map.remove(key);
                        return true;
                    }
                    if (obj instanceof JsArray arr) {
                        int idx = arrayIndex(key);
                        if (idx >= 0 && idx < arr.list.size()) arr.list.set(idx, UNDEFINED);
                        return true;
                    }
                }
                return false;
            }
            default -> { }
        }
        Object v = eval(u.arg(), scope);
        return switch (u.op()) {
            case "!" -> !truthy(v);
            case "-" -> -toNumber(v);
            case "+" -> toNumber(v);
            case "~" -> (double) (~toInt32(v));
            case "void" -> UNDEFINED;
            default -> throw new ScriptError(ScriptError.Kind.SYNTAX, "Unknown operator " + u.op(), u.line(), u.col());
        };
    }

    private Object evalCall(Call c, Scope scope) {
        Object self = UNDEFINED;
        Object fn;
        String calleeName;
        if (c.callee() instanceof Member m) {
            Object obj = eval(m.object(), scope);
            if (m.optional() && (obj == null || obj == UNDEFINED)) return UNDEFINED;
            String key = m.name() != null ? m.name() : propertyKey(eval(m.index(), scope));
            fn = getMember(obj, key, m);
            self = obj;
            calleeName = describeCallee(m.object()) + "." + key;
            if (!(fn instanceof Callable)) {
                if (c.optional() && (fn == null || fn == UNDEFINED)) return UNDEFINED;
                String hint = "";
                if (obj instanceof HostObject h) hint = ". " + h.typeName() + " has: " + String.join(", ", h.memberNames());
                else if (obj instanceof JsArray) hint = " (arrays support: " + String.join(", ", Stdlib.ARRAY_METHODS) + ")";
                else if (obj instanceof String) hint = " (strings support: " + String.join(", ", Stdlib.STRING_METHODS) + ")";
                throw new ScriptError(ScriptError.Kind.RUNTIME, calleeName + " is not a function" + hint, c.line(), c.col());
            }
        } else {
            fn = eval(c.callee(), scope);
            calleeName = describeCallee(c.callee());
            if (!(fn instanceof Callable)) {
                if (c.optional() && (fn == null || fn == UNDEFINED)) return UNDEFINED;
                throw new ScriptError(ScriptError.Kind.RUNTIME, calleeName + " is not a function (it is " + typeName(fn) + ")", c.line(), c.col());
            }
        }
        List<Object> args = new ArrayList<>(c.args().size());
        for (Node a : c.args()) {
            if (a instanceof Spread s) args.addAll(iterate(eval(s.arg(), scope), s));
            else args.add(eval(a, scope));
        }
        current = c;
        try {
            return callFn((Callable) fn, self, args);
        } catch (ScriptError e) {
            throw e.at(c.line(), c.col());
        }
    }

    private static String describeCallee(Node n) {
        return switch (n) {
            case Ident id -> id.name();
            case Member m -> describeCallee(m.object()) + (m.name() != null ? "." + m.name() : "[...]");
            case This t -> "this";
            case Call c -> describeCallee(c.callee()) + "(...)";
            default -> "expression";
        };
    }

    /** 함수 호출 (깊이·예외 변환 포함). */
    public Object callFn(Callable fn, Object self, List<Object> args) {
        budget.enter();
        Node saved = current;
        try {
            return fn.call(this, self, args);
        } catch (ScriptError | BreakSignal | ContinueSignal | ReturnSignal e) {
            throw e;
        } catch (StackOverflowError so) {
            throw new ScriptError(ScriptError.Kind.BUDGET, "Recursion too deep", pos(), col());
        } catch (RuntimeException e) {
            // 호스트 함수의 예상치 못한 자바 예외도 스크립트 오류로 바꾼다 (서버를 멈추지 않는다)
            String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            throw new ScriptError(ScriptError.Kind.RUNTIME, fn.name() + ": " + msg, pos(), col());
        } finally {
            current = saved;
            budget.exit();
        }
    }

    Object invokeScript(ScriptFunction f, Object self, List<Object> args) {
        Scope scope = new Scope(f.closure);
        Func d = f.decl;
        scope.vars.put("this", new Binding(d.arrow() ? f.lexicalThis : self, true));
        for (int i = 0; i < d.params().size(); i++) {
            Param p = d.params().get(i);
            Object v;
            if (p.rest()) {
                List<Object> rest = new ArrayList<>();
                for (int k = i; k < args.size(); k++) rest.add(args.get(k));
                v = new JsArray(rest);
            } else {
                v = i < args.size() ? args.get(i) : UNDEFINED;
                if (v == UNDEFINED && p.defaultValue() != null) v = eval(p.defaultValue(), scope);
            }
            bindPattern(p.target(), v, scope, true, false);
        }
        if (d.exprBody()) return eval(d.body(), scope);
        try {
            execBlockBody(((Block) d.body()).body(), scope);
        } catch (ReturnSignal r) {
            return r.value;
        } catch (BreakSignal | ContinueSignal s) {
            throw new ScriptError(ScriptError.Kind.SYNTAX, "break/continue outside of a loop", d.line(), d.col());
        }
        return UNDEFINED;
    }

    // ---------------------------------------------------------------- 속성

    public Object getMember(Object obj, String key, Node at) {
        if (obj == null || obj == UNDEFINED) {
            String what = at instanceof Member m ? describeCallee(m.object()) : "value";
            throw new ScriptError(ScriptError.Kind.RUNTIME, "Cannot read property '" + key + "' of " + typeName(obj) + " (" + what + " is " + typeName(obj) + ")",
                    at == null ? pos() : at.line(), at == null ? col() : at.col());
        }
        switch (obj) {
            case JsObject o -> {
                Object v = o.map.get(key);
                if (v != null || o.map.containsKey(key)) return v;
                if (key.equals("hasOwnProperty")) return Stdlib.HAS_OWN;
                return UNDEFINED;
            }
            case JsArray a -> {
                if (key.equals("length")) return (double) a.list.size();
                int idx = arrayIndex(key);
                if (idx >= 0) return idx < a.list.size() ? a.list.get(idx) : UNDEFINED;
                Callable m = Stdlib.arrayMethod(key);
                return m == null ? UNDEFINED : m;
            }
            case String s -> {
                if (key.equals("length")) return (double) s.length();
                int idx = arrayIndex(key);
                if (idx >= 0) return idx < s.length() ? String.valueOf(s.charAt(idx)) : UNDEFINED;
                Callable m = Stdlib.stringMethod(key);
                return m == null ? UNDEFINED : m;
            }
            case Double d -> {
                Callable m = Stdlib.numberMethod(key);
                return m == null ? UNDEFINED : m;
            }
            case HostObject h -> { return h.getMember(this, key); }
            case Callable c -> { return key.equals("name") ? c.name() : UNDEFINED; }
            default -> { return UNDEFINED; }
        }
    }

    public void setMember(Object obj, String key, Object v, Node at) {
        switch (obj) {
            case JsObject o -> {
                if (o.frozen) throw new ScriptError(ScriptError.Kind.RUNTIME, "Cannot modify read-only object (property '" + key + "')", at.line(), at.col());
                if (o.map.put(key, v) == null) budget.allocate(1);
            }
            case JsArray a -> {
                if (key.equals("length")) {
                    int n = (int) Math.max(0, Math.min(MAX_ARRAY, toNumber(v)));
                    while (a.list.size() > n) a.list.remove(a.list.size() - 1);
                    while (a.list.size() < n) a.list.add(UNDEFINED);
                    return;
                }
                int idx = arrayIndex(key);
                if (idx < 0) throw new ScriptError(ScriptError.Kind.RUNTIME, "Arrays only accept numeric indexes (got '" + key + "')", at.line(), at.col());
                checkArray(idx + 1, at);
                while (a.list.size() <= idx) { a.list.add(UNDEFINED); budget.allocate(1); }
                a.list.set(idx, v);
            }
            case HostObject h -> {
                if (!h.setMember(this, key, v)) {
                    throw new ScriptError(ScriptError.Kind.RUNTIME, "Cannot set '" + key + "' on " + h.typeName() + " (read-only). Use its methods instead", at.line(), at.col());
                }
            }
            default -> throw new ScriptError(ScriptError.Kind.RUNTIME, "Cannot set property '" + key + "' of " + typeName(obj), at.line(), at.col());
        }
    }

    static int arrayIndex(String key) {
        int n = key.length();
        if (n == 0 || n > 9) return -1;
        for (int i = 0; i < n; i++) if (!Character.isDigit(key.charAt(i))) return -1;
        if (n > 1 && key.charAt(0) == '0') return -1;
        return Integer.parseInt(key);
    }

    public String propertyKey(Object v) {
        if (v instanceof Double d) return numToString(d);
        return toStr(v);
    }

    // ---------------------------------------------------------------- 연산자

    private Object binary(String op, Object l, Object r, Node at) {
        switch (op) {
            case "+" -> {
                if (l instanceof String || r instanceof String || (!(l instanceof Double) && !(l instanceof Boolean) && l != null && l != UNDEFINED)
                        || (!(r instanceof Double) && !(r instanceof Boolean) && r != null && r != UNDEFINED)) {
                    String s = toStr(l) + toStr(r);
                    budget.allocate(s.length() / 16 + 1);
                    return checkString(s, at);
                }
                return toNumber(l) + toNumber(r);
            }
            case "-" -> { return toNumber(l) - toNumber(r); }
            case "*" -> { return toNumber(l) * toNumber(r); }
            case "/" -> { return toNumber(l) / toNumber(r); }
            case "%" -> {
                double a = toNumber(l), b = toNumber(r);
                return a % b;
            }
            case "**" -> { return Math.pow(toNumber(l), toNumber(r)); }
            case "==" -> { return looseEquals(l, r); }
            case "!=" -> { return !looseEquals(l, r); }
            case "===" -> { return strictEquals(l, r); }
            case "!==" -> { return !strictEquals(l, r); }
            case "<", ">", "<=", ">=" -> {
                if (l instanceof String a && r instanceof String b) {
                    int c = a.compareTo(b);
                    return switch (op) { case "<" -> c < 0; case ">" -> c > 0; case "<=" -> c <= 0; default -> c >= 0; };
                }
                double a = toNumber(l), b = toNumber(r);
                return switch (op) { case "<" -> a < b; case ">" -> a > b; case "<=" -> a <= b; default -> a >= b; };
            }
            case "&" -> { return (double) (toInt32(l) & toInt32(r)); }
            case "|" -> { return (double) (toInt32(l) | toInt32(r)); }
            case "^" -> { return (double) (toInt32(l) ^ toInt32(r)); }
            case "<<" -> { return (double) (toInt32(l) << (toInt32(r) & 31)); }
            case ">>" -> { return (double) (toInt32(l) >> (toInt32(r) & 31)); }
            case ">>>" -> { return (double) ((toInt32(l) & 0xFFFFFFFFL) >>> (toInt32(r) & 31)); }
            case "in" -> {
                String key = propertyKey(l);
                if (r instanceof JsObject o) return o.map.containsKey(key);
                if (r instanceof JsArray a) { int i = arrayIndex(key); return i >= 0 && i < a.list.size(); }
                if (r instanceof HostObject h) return h.memberNames().contains(key);
                throw new ScriptError(ScriptError.Kind.RUNTIME, "Cannot use 'in' on " + typeName(r), at.line(), at.col());
            }
            default -> throw new ScriptError(ScriptError.Kind.SYNTAX, "Unknown operator " + op, at.line(), at.col());
        }
    }

    private String checkString(String s, Node at) {
        if (s.length() > MAX_STRING) {
            throw new ScriptError(ScriptError.Kind.BUDGET, "String too long (" + s.length() + " chars, max " + MAX_STRING + ")", at.line(), at.col());
        }
        return s;
    }

    private void checkArray(int n, Node at) {
        if (n > MAX_ARRAY) {
            throw new ScriptError(ScriptError.Kind.BUDGET, "Array too large (max " + MAX_ARRAY + " elements)", at.line(), at.col());
        }
    }

    // ---------------------------------------------------------------- 형 변환 (정적)

    public static boolean truthy(Object v) {
        if (v == null || v == UNDEFINED) return false;
        if (v instanceof Boolean b) return b;
        if (v instanceof Double d) return d != 0 && !d.isNaN();
        if (v instanceof String s) return !s.isEmpty();
        return true;
    }

    public static double toNumber(Object v) {
        if (v instanceof Double d) return d;
        if (v == null) return 0;
        if (v == UNDEFINED) return Double.NaN;
        if (v instanceof Boolean b) return b ? 1 : 0;
        if (v instanceof String s) {
            String t = s.trim();
            if (t.isEmpty()) return 0;
            try {
                if (t.startsWith("0x") || t.startsWith("0X")) return Long.parseLong(t.substring(2), 16);
                return Double.parseDouble(t);
            } catch (NumberFormatException e) {
                return Double.NaN;
            }
        }
        if (v instanceof Integer i) return i;
        if (v instanceof Long l) return l;
        if (v instanceof Float f) return f;
        return Double.NaN;
    }

    public static int toInt32(Object v) {
        double d = toNumber(v);
        if (Double.isNaN(d) || Double.isInfinite(d)) return 0;
        return (int) (long) d;
    }

    public static String numToString(double d) {
        if (Double.isNaN(d)) return "NaN";
        if (Double.isInfinite(d)) return d > 0 ? "Infinity" : "-Infinity";
        if (d == Math.rint(d) && Math.abs(d) < 1e21) {
            if (d == 0) return "0";
            return Long.toString((long) d);
        }
        String s = Double.toString(d);
        if (s.contains("E")) {
            double a = Math.abs(d);
            if (a >= 1e-6 && a < 1e21) return new BigDecimal(s).stripTrailingZeros().toPlainString();
            return s.replace("E", "e").replace("e", a >= 1 ? "e+" : "e").replace("e+-", "e-");
        }
        return s;
    }

    public static String toStr(Object v) {
        if (v == null) return "null";
        if (v == UNDEFINED) return "undefined";
        if (v instanceof String s) return s;
        if (v instanceof Double d) return numToString(d);
        if (v instanceof Boolean b) return b.toString();
        if (v instanceof JsArray a) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < a.list.size() && sb.length() < MAX_STRING; i++) {
                if (i > 0) sb.append(',');
                Object e = a.list.get(i);
                if (e != null && e != UNDEFINED) sb.append(e == a ? "" : toStr(e));
            }
            return sb.toString();
        }
        if (v instanceof JsObject) return "[object Object]";
        if (v instanceof HostObject h) {
            Object name = h.getMember(null, "name");
            return name instanceof String s ? s : "[" + h.typeName() + "]";
        }
        if (v instanceof Callable c) return "function " + c.name() + "() { [code] }";
        if (v instanceof Number n) return numToString(n.doubleValue());
        return String.valueOf(v);
    }

    public static String typeOf(Object v) {
        if (v == UNDEFINED) return "undefined";
        if (v == null) return "object";
        if (v instanceof Boolean) return "boolean";
        if (v instanceof Double) return "number";
        if (v instanceof String) return "string";
        if (v instanceof Callable) return "function";
        return "object";
    }

    public static String typeName(Object v) {
        if (v == UNDEFINED) return "undefined";
        if (v == null) return "null";
        if (v instanceof JsArray) return "array";
        if (v instanceof HostObject h) return h.typeName();
        return typeOf(v);
    }

    public static boolean strictEquals(Object a, Object b) {
        if (a == b) return true;
        if (a == null || b == null) return false;
        if (a instanceof Double x && b instanceof Double y) return x.doubleValue() == y.doubleValue();
        if (a instanceof String x && b instanceof String y) return x.equals(y);
        if (a instanceof Boolean x && b instanceof Boolean y) return x.equals(y);
        if (a instanceof HostObject x && b instanceof HostObject y) return x.equals(y);
        return false;
    }

    public static boolean looseEquals(Object a, Object b) {
        boolean an = a == null || a == UNDEFINED, bn = b == null || b == UNDEFINED;
        if (an || bn) return an && bn;
        if (a instanceof Double || b instanceof Double || a instanceof Boolean || b instanceof Boolean) {
            if ((a instanceof Double || a instanceof String || a instanceof Boolean) && (b instanceof Double || b instanceof String || b instanceof Boolean)) {
                return toNumber(a) == toNumber(b);
            }
        }
        return strictEquals(a, b);
    }

    static String describeThrown(Object v) {
        if (v instanceof JsObject o && o.map.get("message") instanceof String m) return m;
        return toStr(v);
    }

    private static JsObject errorObject(ScriptError e) {
        JsObject o = new JsObject();
        o.put("name", e.kind == ScriptError.Kind.PERMISSION ? "PermissionError" : "Error");
        o.put("message", e.getMessage());
        if (e.line > 0) o.put("line", (double) e.line);
        return o;
    }
}
