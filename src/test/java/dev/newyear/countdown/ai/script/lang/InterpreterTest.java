package dev.newyear.countdown.ai.script.lang;

import dev.newyear.countdown.ai.script.lang.Values.JsArray;
import dev.newyear.countdown.ai.script.lang.Values.NativeFn;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class InterpreterTest {
    private static String run(String src) {
        Interpreter in = new Interpreter();
        List<String> out = new ArrayList<>();
        in.define("out", new NativeFn("out", (i, s, a) -> { out.add(Interpreter.toStr(a.get(0))); return Values.UNDEFINED; }));
        in.budget.reset();
        in.run(Parser.parse(src));
        return String.join("|", out);
    }

    private static ScriptError fail(String src) {
        return assertThrows(ScriptError.class, () -> run(src));
    }

    @Test
    void arithmeticAndStrings() {
        assertEquals("7|2.5|ab3|true|1", run("out(1+2*3); out(5/2); out('a'+'b'+3); out(2**3 === 8); out(7 % 3)"));
        assertEquals("hello 6 world", run("const n = 6; out(`hello ${n} ${'world'}`)"));
        assertEquals("0.1", run("out(0.1)"));
    }

    @Test
    void variablesAndFunctions() {
        assertEquals("55", run("function fib(n) { return n < 2 ? n : fib(n-1) + fib(n-2); } out(fib(10))"));
        assertEquals("3", run("const add = (a, b = 2) => a + b; out(add(1))"));
        assertEquals("6", run("const sum = (...xs) => xs.reduce((a, b) => a + b, 0); out(sum(1,2,3))"));
        assertEquals("1,2,3", run("let a = []; for (let i = 1; i <= 3; i++) a.push(i); out(a.join(','))"));
    }

    @Test
    void closuresCapturePerIteration() {
        assertEquals("0,1,2", run("const fs = []; for (let i = 0; i < 3; i++) fs.push(() => i); out(fs.map(f => f()).join(','))"));
        assertEquals("2", run("function counter() { let c = 0; return () => ++c; } const c = counter(); c(); out(c())"));
    }

    @Test
    void objectsArraysDestructuring() {
        assertEquals("3|x|1,2", run("const o = {a: 1, b: {c: 3}}; out(o.b.c); const {a, b: {c}} = o; out(Object.keys({x:1})[0]); const [p, q] = [1, 2]; out([p,q].join(','))"));
        assertEquals("2,1", run("let x = 1, y = 2; [x, y] = [y, x]; out(x + ',' + y)"));
        assertEquals("4", run("const o = {n: 1, inc() { this.n += 3; return this.n; }}; out(o.inc())"));
        assertEquals("{\"a\":1,\"b\":[1,2]}", run("out(JSON.stringify({a:1, b:[1,2]}))"));
        assertEquals("3", run("const o = {...{a:1}, b: 2}; out(o.a + o.b)"));
        assertEquals("undefined", run("const o = {}; out(o?.x?.y)"));
        assertEquals("d", run("const o = {}; out(o.x ?? 'd')"));
    }

    @Test
    void controlFlow() {
        assertEquals("b", run("switch (2) { case 1: out('a'); break; case 2: out('b'); break; default: out('c'); }"));
        assertEquals("caught boom", run("try { throw new Error('boom'); } catch (e) { out('caught ' + e.message); }"));
        assertEquals("k1,k2", run("const r = []; for (const k in {k1: 1, k2: 2}) r.push(k); out(r.join(','))"));
        assertEquals("6", run("let s = 0; for (const v of [1,2,3]) s += v; out(s)"));
        assertEquals("3", run("let i = 0; while (true) { i++; if (i >= 3) break; } out(i)"));
        assertEquals("ok", run("let i = 0; do { i++; } while (i < 5); out(i === 5 ? 'ok' : 'no')"));
    }

    @Test
    void stdlib() {
        assertEquals("3|1|ABC|a-b|2", run("out(Math.max(1,3,2)); out(Math.floor(1.7)); out('abc'.toUpperCase()); out('a b'.split(' ').join('-')); out([3,1,2].sort((a,b)=>a-b)[1])"));
        assertEquals("1.50", run("out((1.5).toFixed(2))"));
        assertEquals("42", run("out(parseInt('42px'))"));
        assertEquals("true", run("out(Array.isArray([]))"));
    }

    @Test
    void stepBudgetStopsInfiniteLoops() {
        ScriptError e = assertThrows(ScriptError.class, () -> {
            Interpreter in = new Interpreter();
            in.budget = new Budget(10_000, 100_000, 64, 1000);
            in.budget.reset();
            in.run(Parser.parse("let i = 0; while (i >= 0) { i++; }"));
        });
        assertEquals(ScriptError.Kind.BUDGET, e.kind);
    }

    @Test
    void budgetErrorCannotBeCaught() {
        ScriptError e = assertThrows(ScriptError.class, () -> {
            Interpreter in = new Interpreter();
            in.budget = new Budget(5_000, 100_000, 64, 1000);
            in.budget.reset();
            in.run(Parser.parse("try { let i = 0; while (i >= 0) i++; } catch (e) { }"));
        });
        assertEquals(ScriptError.Kind.BUDGET, e.kind);
    }

    @Test
    void recursionDepthLimited() {
        ScriptError e = fail("function f(n) { return f(n + 1); } f(0)");
        assertEquals(ScriptError.Kind.BUDGET, e.kind);
    }

    @Test
    void memoryLimited() {
        ScriptError e = fail("let s = 'x'; for (let i = 0; i < 40; i++) s = s + s;");
        assertEquals(ScriptError.Kind.BUDGET, e.kind);
        ScriptError e2 = fail("const a = []; for (let i = 0; i < 100000; i++) a.push(i);");
        assertEquals(ScriptError.Kind.BUDGET, e2.kind);
    }

    @Test
    void syntaxErrorsHavePositions() {
        ScriptError e = assertThrows(ScriptError.class, () -> Parser.parse("let x = ;\n"));
        assertEquals(ScriptError.Kind.SYNTAX, e.kind);
        assertEquals(1, e.line);
        ScriptError cls = assertThrows(ScriptError.class, () -> Parser.parse("class A {}"));
        assertTrue(cls.getMessage().contains("not supported"));
        ScriptError nw = assertThrows(ScriptError.class, () -> Parser.parse("const m = new Map()"));
        assertTrue(nw.getMessage().contains("not supported"));
    }

    @Test
    void runtimeErrorsAreDescriptive() {
        ScriptError e = fail("const p = undefined;\np.name");
        assertEquals(2, e.line);
        assertTrue(e.getMessage().contains("Cannot read property 'name'"));
        ScriptError c = fail("const x = 1; x = 2;");
        assertTrue(c.getMessage().contains("constant"));
    }

    @Test
    void noJavaAccess() {
        // 자바 클래스나 전역 객체로 빠져나갈 길이 없어야 한다
        assertEquals("undefined|undefined|undefined", run("out(typeof java); out(typeof Packages); out(({}).constructor)"));
        ScriptError e = fail("out.getClass()");
        assertEquals(ScriptError.Kind.RUNTIME, e.kind);
        assertEquals("undefined", run("out(Math.constructor)"));
    }

    @Test
    void frozenApiObjects() {
        ScriptError e = fail("Math.floor = 1");
        assertTrue(e.getMessage().contains("read-only"));
    }

    @Test
    void staticCheckerFindsUnknownNames() {
        var prog = Parser.parse("const p = world.tim(); undefinedThing(); on('jump', e => e.player.give('bread'))");
        var res = StaticChecker.check(prog, Set.of("world", "on"), Map.of("world", List.of("time", "weather")));
        assertFalse(res.ok());
        assertTrue(res.issues().stream().anyMatch(i -> i.message().contains("did you mean 'time'")));
        assertTrue(res.issues().stream().anyMatch(i -> i.message().contains("undefinedThing")));
        assertTrue(res.events().contains("jump"));
    }

    @Test
    void staticCheckerInfiniteLoop() {
        var res = StaticChecker.check(Parser.parse("while (true) { let a = 1; }"), Set.of(), Map.of());
        assertFalse(res.ok());
        var ok = StaticChecker.check(Parser.parse("while (true) { if (x()) break; } function x() { return true; }"), Set.of(), Map.of());
        assertTrue(ok.ok(), ok.issues().toString());
    }

    @Test
    void hostCallbacks() {
        Interpreter in = new Interpreter();
        List<Values.Callable> handlers = new ArrayList<>();
        in.define("on", new NativeFn("on", (i, s, a) -> { handlers.add((Values.Callable) a.get(1)); return Values.UNDEFINED; }));
        in.budget.reset();
        in.run(Parser.parse("let count = 0; on('jump', (e) => { count++; return count; });"));
        assertEquals(1, handlers.size());
        in.budget.reset();
        in.invoke(handlers.get(0), List.of());
        Object r = in.invoke(handlers.get(0), List.of());
        assertEquals(2.0, r);
    }

    @Test
    void jsonRoundTrip() {
        Interpreter in = new Interpreter();
        in.budget.reset();
        Object v = Stdlib.fromJson(com.google.gson.JsonParser.parseString("{\"a\":[1,2,{\"b\":null}]}"), in);
        assertEquals("{\"a\":[1,2,{\"b\":null}]}", Stdlib.toJson(v).toString());
        assertTrue(((Values.JsObject) v).get("a") instanceof JsArray);
    }
}
