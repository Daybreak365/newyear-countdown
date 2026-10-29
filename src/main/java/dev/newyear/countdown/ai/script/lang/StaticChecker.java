package dev.newyear.countdown.ai.script.lang;

import dev.newyear.countdown.ai.script.lang.Node.*;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 실행 전 정적 검사. 선언되지 않은 이름, 알려진 API 객체의 없는 멤버를 찾고,
 * 스크립트가 쓰는 API 호출 목록(영향 분석용)을 모은다.
 */
public final class StaticChecker {
    public record Issue(boolean error, int line, int col, String message) {
        @Override
        public String toString() {
            return (error ? "error" : "warning") + " (line " + line + ":" + col + "): " + message;
        }
    }

    public record Result(List<Issue> issues, Set<String> apiCalls, Set<String> events) {
        public boolean ok() {
            return issues.stream().noneMatch(Issue::error);
        }
    }

    private final Set<String> globals;
    /** 전역 API 객체 이름 → 멤버 이름 (예: world → {time, setBlock, ...}). */
    private final Map<String, ? extends Collection<String>> hostMembers;
    private final List<Issue> issues = new ArrayList<>();
    private final Set<String> apiCalls = new LinkedHashSet<>();
    private final Set<String> events = new LinkedHashSet<>();

    private StaticChecker(Set<String> globals, Map<String, ? extends Collection<String>> hostMembers) {
        this.globals = globals;
        this.hostMembers = hostMembers;
    }

    public static Result check(Program program, Set<String> globals, Map<String, ? extends Collection<String>> hostMembers) {
        StaticChecker c = new StaticChecker(globals, hostMembers);
        Set<String> top = new HashSet<>();
        c.collectDecls(program.body(), top);
        c.walkList(program.body(), List.of(top));
        return new Result(c.issues, c.apiCalls, c.events);
    }

    private void error(Node n, String msg) {
        issues.add(new Issue(true, n.line(), n.col(), msg));
    }

    private void warn(Node n, String msg) {
        issues.add(new Issue(false, n.line(), n.col(), msg));
    }

    // 함수 범위 안의 모든 선언 이름을 모은다 (중첩 함수 안쪽은 제외)
    private void collectDecls(List<Node> body, Set<String> out) {
        for (Node n : body) collectDecl(n, out);
    }

    private void collectDecl(Node n, Set<String> out) {
        if (n == null) return;
        switch (n) {
            case VarDecl d -> { for (Declarator dc : d.decls()) patternNames(dc.target(), out); }
            case FuncDecl f -> out.add(f.func().name());
            case Block b -> collectDecls(b.body(), out);
            case If i -> { collectDecl(i.then(), out); collectDecl(i.otherwise(), out); }
            case While w -> collectDecl(w.body(), out);
            case DoWhile w -> collectDecl(w.body(), out);
            case For f -> { collectDecl(f.init(), out); collectDecl(f.body(), out); }
            case ForEach f -> { if (f.kind() != null) patternNames(f.target(), out); collectDecl(f.body(), out); }
            case Try t -> {
                collectDecl(t.block(), out);
                if (t.catchParam() != null) patternNames(t.catchParam(), out);
                if (t.handler() != null) collectDecl(t.handler(), out);
                if (t.finalizer() != null) collectDecl(t.finalizer(), out);
            }
            case Switch s -> { for (Case c : s.cases()) collectDecls(c.body(), out); }
            default -> { }
        }
    }

    private void patternNames(Node p, Set<String> out) {
        switch (p) {
            case Ident id -> out.add(id.name());
            case ObjPattern op -> {
                for (PatProp pp : op.props()) patternNames(pp.target(), out);
                if (op.rest() != null) patternNames(op.rest(), out);
            }
            case ArrPattern ap -> {
                for (Node e : ap.elems()) if (e != null) patternNames(e, out);
                if (ap.rest() != null) patternNames(ap.rest(), out);
            }
            default -> { }
        }
    }

    private boolean declared(String name, List<Set<String>> scopes) {
        for (Set<String> s : scopes) if (s.contains(name)) return true;
        return globals.contains(name) || Stdlib.GLOBALS.contains(name);
    }

    private void walkList(List<Node> list, List<Set<String>> scopes) {
        for (Node n : list) walk(n, scopes);
    }

    private void walk(Node n, List<Set<String>> scopes) {
        if (n == null) return;
        switch (n) {
            case Ident id -> {
                if (!declared(id.name(), scopes)) {
                    error(id, "'" + id.name() + "' is not defined" + suggest(id.name()));
                }
            }
            case Template t -> walkList(t.exprs(), scopes);
            case ArrayLit a -> walkList(a.elems(), scopes);
            case ObjectLit o -> {
                for (Prop p : o.props()) {
                    if (p.keyExpr() != null) walk(p.keyExpr(), scopes);
                    walk(p.value(), scopes);
                }
            }
            case Func f -> walkFunc(f, scopes);
            case Member m -> {
                walk(m.object(), scopes);
                if (m.index() != null) walk(m.index(), scopes);
                checkHostMember(m, scopes);
            }
            case Call c -> {
                walk(c.callee(), scopes);
                walkList(c.args(), scopes);
                recordCall(c, scopes);
            }
            case Spread s -> walk(s.arg(), scopes);
            case Unary u -> {
                if (u.op().equals("typeof") && u.arg() instanceof Ident) return;   // typeof x 는 선언 여부 확인용
                walk(u.arg(), scopes);
            }
            case Update u -> walk(u.target(), scopes);
            case Binary b -> { walk(b.left(), scopes); walk(b.right(), scopes); }
            case Logical l -> { walk(l.left(), scopes); walk(l.right(), scopes); }
            case Cond c -> { walk(c.test(), scopes); walk(c.then(), scopes); walk(c.otherwise(), scopes); }
            case Assign a -> {
                walkTarget(a.target(), scopes);
                walk(a.value(), scopes);
            }
            case Seq s -> walkList(s.exprs(), scopes);
            case VarDecl d -> {
                for (Declarator dc : d.decls()) {
                    walkPatternDefaults(dc.target(), scopes);
                    walk(dc.init(), scopes);
                }
            }
            case FuncDecl f -> walkFunc(f.func(), scopes);
            case Return r -> walk(r.value(), scopes);
            case If i -> { walk(i.test(), scopes); walk(i.then(), scopes); walk(i.otherwise(), scopes); }
            case While w -> { walk(w.test(), scopes); walk(w.body(), scopes); checkInfinite(w.test(), w.body(), w); }
            case DoWhile w -> { walk(w.body(), scopes); walk(w.test(), scopes); checkInfinite(w.test(), w.body(), w); }
            case For f -> {
                walk(f.init(), scopes);
                walk(f.test(), scopes);
                walk(f.update(), scopes);
                walk(f.body(), scopes);
                if (f.test() == null) checkInfinite(null, f.body(), f);
            }
            case ForEach f -> {
                if (f.kind() == null) walkTarget(f.target(), scopes);
                walk(f.iterable(), scopes);
                walk(f.body(), scopes);
            }
            case Throw t -> walk(t.value(), scopes);
            case Try t -> {
                walk(t.block(), scopes);
                walk(t.handler(), scopes);
                walk(t.finalizer(), scopes);
            }
            case Switch s -> {
                walk(s.discriminant(), scopes);
                for (Case c : s.cases()) {
                    walk(c.test(), scopes);
                    walkList(c.body(), scopes);
                }
            }
            case Block b -> walkList(b.body(), scopes);
            case ExprStmt e -> walk(e.expr(), scopes);
            case Program p -> walkList(p.body(), scopes);
            default -> { }
        }
    }

    private void walkTarget(Node t, List<Set<String>> scopes) {
        switch (t) {
            case Ident id -> {
                if (!declared(id.name(), scopes)) error(id, "Assignment to undeclared variable '" + id.name() + "'. Declare it with let first");
                else if (globals.contains(id.name()) || Stdlib.GLOBALS.contains(id.name())) {
                    boolean local = false;
                    for (Set<String> s : scopes) if (s.contains(id.name())) local = true;
                    if (!local) error(id, "Cannot reassign built-in '" + id.name() + "'");
                }
            }
            case Member m -> walk(m, scopes);
            case ObjPattern op -> { for (PatProp pp : op.props()) { walkTarget(pp.target(), scopes); walk(pp.defaultValue(), scopes); } }
            case ArrPattern ap -> { for (Node e : ap.elems()) if (e != null) walkTarget(e, scopes); }
            default -> walk(t, scopes);
        }
    }

    private void walkPatternDefaults(Node p, List<Set<String>> scopes) {
        if (p instanceof ObjPattern op) for (PatProp pp : op.props()) {
            if (pp.keyExpr() != null) walk(pp.keyExpr(), scopes);
            walk(pp.defaultValue(), scopes);
            walkPatternDefaults(pp.target(), scopes);
        }
        if (p instanceof ArrPattern ap) for (int i = 0; i < ap.elems().size(); i++) {
            walk(ap.defaults().get(i), scopes);
            if (ap.elems().get(i) != null) walkPatternDefaults(ap.elems().get(i), scopes);
        }
    }

    private void walkFunc(Func f, List<Set<String>> outer) {
        Set<String> local = new HashSet<>();
        if (f.name() != null) local.add(f.name());
        for (Param p : f.params()) patternNames(p.target(), local);
        if (f.body() instanceof Block b) collectDecls(b.body(), local);
        List<Set<String>> scopes = new ArrayList<>(outer);
        scopes.add(0, local);
        for (Param p : f.params()) {
            walk(p.defaultValue(), scopes);
            walkPatternDefaults(p.target(), scopes);
        }
        walk(f.body(), scopes);
    }

    private static boolean containsBreakOrReturn(Node n) {
        if (n == null) return false;
        return switch (n) {
            case Break b -> true;
            case Return r -> true;
            case Throw t -> true;
            case Block b -> b.body().stream().anyMatch(StaticChecker::containsBreakOrReturn);
            case If i -> containsBreakOrReturn(i.then()) || containsBreakOrReturn(i.otherwise());
            case Try t -> containsBreakOrReturn(t.block()) || containsBreakOrReturn(t.handler()) || containsBreakOrReturn(t.finalizer());
            case Switch s -> s.cases().stream().anyMatch(c -> c.body().stream().anyMatch(x -> x instanceof Return || x instanceof Throw));
            default -> false;
        };
    }

    private void checkInfinite(Node test, Node body, Node at) {
        boolean always = test == null || (test instanceof Bool b && b.value()) || (test instanceof Num num && num.value() != 0);
        if (always && !containsBreakOrReturn(body)) {
            error(at, "Infinite loop: this loop never ends. Use every(ticks, fn) for repeating behaviour");
        }
    }

    private void checkHostMember(Member m, List<Set<String>> scopes) {
        if (!(m.object() instanceof Ident id) || m.name() == null) return;
        for (Set<String> s : scopes) if (s.contains(id.name())) return;   // 지역 변수가 가린 경우
        Collection<String> members = hostMembers.get(id.name());
        if (members == null || members.isEmpty()) return;
        if (!members.contains(m.name())) {
            error(m, "'" + id.name() + "' has no member '" + m.name() + "'" + suggestFrom(m.name(), members)
                    + ". Available: " + String.join(", ", members));
        }
    }

    private void recordCall(Call c, List<Set<String>> scopes) {
        String name = calleeName(c.callee());
        if (name == null) return;
        String root = name.contains(".") ? name.substring(0, name.indexOf('.')) : name;
        for (Set<String> s : scopes) if (s.contains(root)) {
            // 지역 변수(플레이어 핸들 등)에 대한 메서드 호출은 메서드 이름만 기록
            if (name.contains(".")) apiCalls.add("*." + name.substring(name.lastIndexOf('.') + 1));
            return;
        }
        apiCalls.add(name);
        if ((name.equals("on") || name.equals("once")) && !c.args().isEmpty() && c.args().get(0) instanceof Str s) events.add(s.value());
    }

    private static String calleeName(Node n) {
        if (n instanceof Ident id) return id.name();
        if (n instanceof Member m && m.name() != null) {
            String inner = calleeName(m.object());
            if (inner != null) return inner + "." + m.name();
            return "*." + m.name();
        }
        if (n instanceof Call c && calleeName(c.callee()) != null) return calleeName(c.callee()) + "()";
        return null;
    }

    private String suggest(String name) {
        Set<String> all = new HashSet<>(globals);
        all.addAll(Stdlib.GLOBALS);
        return suggestFrom(name, all);
    }

    static String suggestFrom(String name, Collection<String> candidates) {
        String best = null;
        int bestD = Integer.MAX_VALUE;
        for (String c : candidates) {
            int d = distance(name.toLowerCase(), c.toLowerCase());
            if (d < bestD) { bestD = d; best = c; }
        }
        if (best != null && bestD <= Math.max(2, name.length() / 3)) return " (did you mean '" + best + "'?)";
        return "";
    }

    private static int distance(String a, String b) {
        int[] prev = new int[b.length() + 1], cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] t = prev; prev = cur; cur = t;
        }
        return prev[b.length()];
    }
}
