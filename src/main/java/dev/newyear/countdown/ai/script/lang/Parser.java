package dev.newyear.countdown.ai.script.lang;

import dev.newyear.countdown.ai.script.lang.Lexer.T;
import dev.newyear.countdown.ai.script.lang.Lexer.Token;
import dev.newyear.countdown.ai.script.lang.Node.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 재귀 하강 파서. 지원하지 않는 문법은 고칠 방법과 함께 명확한 오류를 낸다. */
public final class Parser {
    public static final int MAX_SOURCE = 48_000;
    private static final int MAX_NESTING = 120;

    private final List<Token> toks;
    private int p = 0;
    private boolean noIn = false;
    private int nesting = 0;

    private Parser(List<Token> toks) {
        this.toks = toks;
    }

    public static Program parse(String src) {
        if (src.length() > MAX_SOURCE) {
            throw new ScriptError(ScriptError.Kind.SYNTAX, "Script too long (" + src.length() + " chars, max " + MAX_SOURCE + ")", 1, 1);
        }
        Parser ps = new Parser(new Lexer(src).tokenize());
        List<Node> body = new ArrayList<>();
        while (!ps.at(T.EOF)) body.add(ps.statement());
        return new Program(1, 1, body);
    }

    /** 단일 식 (템플릿 문자열 안의 ${...}). */
    static Node parseExpression(String src, int baseLine, int baseCol) {
        Parser ps = new Parser(new Lexer(src, baseLine, baseCol).tokenize());
        Node e = ps.expression();
        if (!ps.at(T.EOF)) throw ps.err("Unexpected " + ps.peek() + " in template expression");
        return e;
    }

    // ---------------------------------------------------------------- 토큰 도우미

    private Token peek() { return toks.get(p); }
    private Token peek(int o) { return toks.get(Math.min(toks.size() - 1, p + o)); }
    private boolean at(T t) { return peek().type() == t; }
    private boolean at(String s) { return peek().is(s); }
    private Token advance() { Token t = toks.get(p); if (p < toks.size() - 1) p++; return t; }

    private boolean eat(String s) {
        if (at(s)) { advance(); return true; }
        return false;
    }

    private Token expect(String s) {
        if (!at(s)) throw err("Expected '" + s + "' but found " + peek());
        return advance();
    }

    private ScriptError err(String msg) {
        Token t = peek();
        return new ScriptError(ScriptError.Kind.SYNTAX, msg, t.line(), t.col());
    }

    private String identName() {
        Token t = peek();
        if (t.type() == T.IDENT) { advance(); return t.text(); }
        throw err("Expected a name but found " + t);
    }

    /** 속성 이름은 예약어도 허용한다 (obj.default, {new: 1}). */
    private String propName() {
        Token t = peek();
        if (t.type() == T.IDENT || t.type() == T.KEYWORD) { advance(); return t.text(); }
        if (t.type() == T.STR) { advance(); return t.text(); }
        if (t.type() == T.NUM) { advance(); return Interpreter.numToString(t.num()); }
        throw err("Expected a property name but found " + t);
    }

    private void semi() {
        if (eat(";")) return;
        Token t = peek();
        if (t.is("}") || t.type() == T.EOF || t.nlBefore()) return;
        throw err("Expected ';' or a new line before " + t);
    }

    private void enter() {
        if (++nesting > MAX_NESTING) throw err("Code is nested too deeply");
    }

    private void leave() {
        nesting--;
    }

    // ---------------------------------------------------------------- 문장

    private Node statement() {
        enter();
        try {
            return statement0();
        } finally {
            leave();
        }
    }

    private Node statement0() {
        Token t = peek();
        int l = t.line(), c = t.col();
        if (t.type() == T.PUNCT) {
            if (t.is("{")) return block();
            if (t.is(";")) { advance(); return new Empty(l, c); }
        }
        if (t.type() == T.KEYWORD) {
            switch (t.text()) {
                case "let", "const", "var" -> {
                    VarDecl d = varDecl();
                    semi();
                    return d;
                }
                case "function" -> {
                    advance();
                    if (at("*")) throw err("Generator functions are not supported");
                    String name = identName();
                    return new FuncDecl(l, c, functionRest(l, c, name));
                }
                case "return" -> {
                    advance();
                    Node v = null;
                    if (!at(";") && !at("}") && !at(T.EOF) && !peek().nlBefore()) v = expression();
                    semi();
                    return new Return(l, c, v);
                }
                case "if" -> {
                    advance();
                    expect("(");
                    Node test = expression();
                    expect(")");
                    Node then = statement();
                    Node otherwise = null;
                    if (eat("else")) otherwise = statement();
                    return new If(l, c, test, then, otherwise);
                }
                case "while" -> {
                    advance();
                    expect("(");
                    Node test = expression();
                    expect(")");
                    return new While(l, c, test, statement());
                }
                case "do" -> {
                    advance();
                    Node body = statement();
                    expect("while");
                    expect("(");
                    Node test = expression();
                    expect(")");
                    eat(";");
                    return new DoWhile(l, c, body, test);
                }
                case "for" -> { return forStatement(); }
                case "break" -> { advance(); semi(); return new Break(l, c); }
                case "continue" -> { advance(); semi(); return new Continue(l, c); }
                case "throw" -> {
                    advance();
                    Node v = expression();
                    semi();
                    return new Throw(l, c, v);
                }
                case "try" -> {
                    advance();
                    Block b = block();
                    Node param = null;
                    Block handler = null, fin = null;
                    if (eat("catch")) {
                        if (eat("(")) {
                            param = bindingTarget();
                            expect(")");
                        }
                        handler = block();
                    }
                    if (eat("finally")) fin = block();
                    if (handler == null && fin == null) throw err("'try' needs 'catch' or 'finally'");
                    return new Try(l, c, b, param, handler, fin);
                }
                case "switch" -> { return switchStatement(); }
                case "class" -> throw err("'class' is not supported. Use plain objects and functions, e.g. const thing = { hp: 3 }; function hit(t) { t.hp--; }");
                case "import", "export" -> throw err("'" + t.text() + "' is not supported: scripts are single files and every API is already global");
                case "async", "await" -> throw err("async/await is not supported. Use after(ticks, fn) or every(ticks, fn) for delays");
                case "with" -> throw err("'with' is not supported");
                default -> { }
            }
        }
        Node e = expression();
        semi();
        return new ExprStmt(l, c, e);
    }

    private Block block() {
        Token t = expect("{");
        List<Node> body = new ArrayList<>();
        while (!at("}")) {
            if (at(T.EOF)) throw err("Missing '}' for block opened at line " + t.line());
            body.add(statement());
        }
        advance();
        return new Block(t.line(), t.col(), body);
    }

    private VarDecl varDecl() {
        Token t = advance();
        List<Declarator> decls = new ArrayList<>();
        do {
            Node target = bindingTarget();
            Node init = null;
            if (eat("=")) init = assignment();
            else if (t.text().equals("const") && !(at("of") || at("in"))) throw err("const declaration needs a value");
            decls.add(new Declarator(target, init));
        } while (eat(","));
        return new VarDecl(t.line(), t.col(), t.text(), decls);
    }

    private Node forStatement() {
        Token f = advance();
        if (at("await")) throw err("for await is not supported");
        expect("(");
        Node init = null;
        boolean prevNoIn = noIn;
        noIn = true;
        try {
            if (at("let") || at("const") || at("var")) {
                Token kt = peek();
                // for (const x of ...) / for (const k in ...)
                int save = p;
                advance();
                Node target = bindingTarget();
                if (at("of") || at("in")) {
                    boolean of = advance().text().equals("of");
                    noIn = prevNoIn;
                    Node iter = of ? assignment() : expression();
                    expect(")");
                    return new ForEach(f.line(), f.col(), kt.text(), target, iter, statement(), of);
                }
                p = save;
                init = varDecl();
            } else if (!at(";")) {
                Node e = expression();
                if (at("of") || at("in")) {
                    boolean of = advance().text().equals("of");
                    noIn = prevNoIn;
                    Node iter = of ? assignment() : expression();
                    expect(")");
                    return new ForEach(f.line(), f.col(), null, toPattern(e), iter, statement(), of);
                }
                init = new ExprStmt(e.line(), e.col(), e);
            }
        } finally {
            noIn = prevNoIn;
        }
        expect(";");
        Node test = at(";") ? null : expression();
        expect(";");
        Node update = at(")") ? null : expression();
        expect(")");
        return new For(f.line(), f.col(), init, test, update, statement());
    }

    private Node switchStatement() {
        Token s = advance();
        expect("(");
        Node disc = expression();
        expect(")");
        expect("{");
        List<Case> cases = new ArrayList<>();
        boolean sawDefault = false;
        while (!eat("}")) {
            Node test = null;
            if (eat("default")) {
                if (sawDefault) throw err("Duplicate default in switch");
                sawDefault = true;
            } else {
                expect("case");
                test = expression();
            }
            expect(":");
            List<Node> body = new ArrayList<>();
            while (!at("case") && !at("default") && !at("}")) {
                if (at(T.EOF)) throw err("Unterminated switch");
                body.add(statement());
            }
            cases.add(new Case(test, body));
        }
        return new Switch(s.line(), s.col(), disc, cases);
    }

    // ---------------------------------------------------------------- 패턴

    /** 선언/매개변수 자리: 이름, {a, b: c = 1, ...rest}, [x, , y = 2, ...rest] */
    private Node bindingTarget() {
        Token t = peek();
        if (at("{")) {
            advance();
            List<PatProp> props = new ArrayList<>();
            Node rest = null;
            while (!eat("}")) {
                if (eat("...")) {
                    rest = new Ident(peek().line(), peek().col(), identName());
                    eat(",");
                    expect("}");
                    break;
                }
                Node keyExpr = null;
                String key = null;
                if (eat("[")) { keyExpr = assignment(); expect("]"); }
                else key = propName();
                Node target;
                if (eat(":")) target = bindingTarget();
                else {
                    if (key == null) throw err("Computed key needs ': name'");
                    target = new Ident(t.line(), t.col(), key);
                }
                Node def = null;
                if (eat("=")) def = assignment();
                props.add(new PatProp(key, keyExpr, target, def));
                if (!eat(",")) { expect("}"); break; }
            }
            return new ObjPattern(t.line(), t.col(), props, rest);
        }
        if (at("[")) {
            advance();
            List<Node> elems = new ArrayList<>();
            List<Node> defs = new ArrayList<>();
            Node rest = null;
            while (!eat("]")) {
                if (at(",")) { advance(); elems.add(null); defs.add(null); continue; }
                if (eat("...")) {
                    rest = bindingTarget();
                    eat(",");
                    expect("]");
                    break;
                }
                elems.add(bindingTarget());
                defs.add(eat("=") ? assignment() : null);
                if (!eat(",")) { expect("]"); break; }
            }
            return new ArrPattern(t.line(), t.col(), elems, defs, rest);
        }
        return new Ident(t.line(), t.col(), identName());
    }

    /** 대입식 왼쪽의 배열/객체 리터럴을 패턴으로 바꾼다 ([a, b] = [b, a]). */
    private Node toPattern(Node n) {
        if (n instanceof Ident || n instanceof Member) return n;
        if (n instanceof ArrayLit a) {
            List<Node> elems = new ArrayList<>();
            List<Node> defs = new ArrayList<>();
            Node rest = null;
            for (Node e : a.elems()) {
                if (e == null) { elems.add(null); defs.add(null); }
                else if (e instanceof Spread s) rest = toPattern(s.arg());
                else if (e instanceof Assign as && as.op().equals("=")) { elems.add(toPattern(as.target())); defs.add(as.value()); }
                else { elems.add(toPattern(e)); defs.add(null); }
            }
            return new ArrPattern(a.line(), a.col(), elems, defs, rest);
        }
        if (n instanceof ObjectLit o) {
            List<PatProp> props = new ArrayList<>();
            Node rest = null;
            for (Prop pr : o.props()) {
                if (pr.spread()) { rest = toPattern(pr.value()); continue; }
                Node v = pr.value();
                Node def = null;
                if (v instanceof Assign as && as.op().equals("=")) { def = as.value(); v = as.target(); }
                props.add(new PatProp(pr.key(), pr.keyExpr(), toPattern(v), def));
            }
            return new ObjPattern(o.line(), o.col(), props, rest);
        }
        throw new ScriptError(ScriptError.Kind.SYNTAX, "Invalid assignment target", n.line(), n.col());
    }

    // ---------------------------------------------------------------- 식

    Node expression() {
        Node first = assignment();
        if (!at(",")) return first;
        List<Node> list = new ArrayList<>();
        list.add(first);
        while (eat(",")) list.add(assignment());
        return new Seq(first.line(), first.col(), list);
    }

    private static final Set<String> ASSIGN_OPS = Set.of("=", "+=", "-=", "*=", "/=", "%=", "**=", "??=", "||=", "&&=",
            "&=", "|=", "^=", "<<=", ">>=", ">>>=");

    private Node assignment() {
        enter();
        try {
            if (isArrowStart()) return arrow();
            Token start = peek();
            Node left = conditional();
            if (peek().type() == T.PUNCT && ASSIGN_OPS.contains(peek().text())) {
                String op = advance().text();
                Node target = op.equals("=") ? toPattern(left) : left;
                if (!(target instanceof Ident || target instanceof Member || target instanceof ArrPattern || target instanceof ObjPattern)) {
                    throw new ScriptError(ScriptError.Kind.SYNTAX, "Invalid assignment target", start.line(), start.col());
                }
                if (!op.equals("=") && !(target instanceof Ident || target instanceof Member)) {
                    throw new ScriptError(ScriptError.Kind.SYNTAX, "Invalid compound assignment target", start.line(), start.col());
                }
                return new Assign(start.line(), start.col(), op, target, assignment());
            }
            return left;
        } finally {
            leave();
        }
    }

    private boolean isArrowStart() {
        Token t = peek();
        if (t.type() == T.KEYWORD && t.text().equals("async")) return false;
        if (t.type() == T.IDENT) return peek(1).is("=>");
        if (!t.is("(")) return false;
        int depth = 0;
        for (int i = p; i < toks.size(); i++) {
            Token k = toks.get(i);
            if (k.type() == T.EOF) return false;
            if (k.is("(") || k.is("[") || k.is("{")) depth++;
            else if (k.is(")") || k.is("]") || k.is("}")) {
                depth--;
                if (depth == 0) return i + 1 < toks.size() && toks.get(i + 1).is("=>");
            }
        }
        return false;
    }

    private Node arrow() {
        Token t = peek();
        List<Param> params = new ArrayList<>();
        if (t.type() == T.IDENT) {
            advance();
            params.add(new Param(new Ident(t.line(), t.col(), t.text()), null, false));
        } else {
            params = paramList();
        }
        expect("=>");
        boolean prevNoIn = noIn;
        noIn = false;
        try {
            if (at("{")) return new Func(t.line(), t.col(), null, params, block(), true, false);
            Node body = assignment();
            return new Func(t.line(), t.col(), null, params, body, true, true);
        } finally {
            noIn = prevNoIn;
        }
    }

    private List<Param> paramList() {
        expect("(");
        List<Param> params = new ArrayList<>();
        while (!eat(")")) {
            boolean rest = eat("...");
            Node target = bindingTarget();
            Node def = null;
            if (!rest && eat("=")) def = assignment();
            params.add(new Param(target, def, rest));
            if (rest) { eat(","); expect(")"); break; }
            if (!eat(",")) { expect(")"); break; }
        }
        return params;
    }

    private Func functionRest(int l, int c, String name) {
        List<Param> params = paramList();
        boolean prevNoIn = noIn;
        noIn = false;
        try {
            return new Func(l, c, name, params, block(), false, false);
        } finally {
            noIn = prevNoIn;
        }
    }

    private Node conditional() {
        Node test = binary(0);
        if (at("?")) {
            Token q = advance();
            boolean prevNoIn = noIn;
            noIn = false;
            Node a = assignment();
            noIn = prevNoIn;
            expect(":");
            Node b = assignment();
            return new Cond(q.line(), q.col(), test, a, b);
        }
        return test;
    }

    private static final Map<String, Integer> PREC = Map.ofEntries(
            Map.entry("??", 1), Map.entry("||", 1), Map.entry("&&", 2),
            Map.entry("|", 3), Map.entry("^", 4), Map.entry("&", 5),
            Map.entry("==", 6), Map.entry("!=", 6), Map.entry("===", 6), Map.entry("!==", 6),
            Map.entry("<", 7), Map.entry(">", 7), Map.entry("<=", 7), Map.entry(">=", 7), Map.entry("in", 7), Map.entry("instanceof", 7),
            Map.entry("<<", 8), Map.entry(">>", 8), Map.entry(">>>", 8),
            Map.entry("+", 9), Map.entry("-", 9),
            Map.entry("*", 10), Map.entry("/", 10), Map.entry("%", 10),
            Map.entry("**", 11));

    private Node binary(int minPrec) {
        Node left = unary();
        while (true) {
            Token t = peek();
            if (t.type() != T.PUNCT && t.type() != T.KEYWORD) break;
            Integer prec = PREC.get(t.text());
            if (prec == null || prec < minPrec) break;
            if (t.text().equals("in") && noIn) break;
            if (t.text().equals("instanceof")) throw err("'instanceof' is not supported. Use typeof x or Array.isArray(x)");
            advance();
            // ** 는 오른쪽 결합, 나머지는 왼쪽 결합
            Node right = binary(t.text().equals("**") ? prec : prec + 1);
            String op = t.text();
            if (op.equals("&&") || op.equals("||") || op.equals("??")) left = new Logical(t.line(), t.col(), op, left, right);
            else left = new Binary(t.line(), t.col(), op, left, right);
        }
        return left;
    }

    private Node unary() {
        Token t = peek();
        if (t.type() == T.PUNCT || t.type() == T.KEYWORD) {
            switch (t.text()) {
                case "!", "-", "+", "~" -> {
                    advance();
                    Node a = unary();
                    if (t.text().equals("-") && a instanceof Num n) return new Num(t.line(), t.col(), -n.value());
                    return new Unary(t.line(), t.col(), t.text(), a);
                }
                case "typeof", "void", "delete" -> {
                    advance();
                    return new Unary(t.line(), t.col(), t.text(), unary());
                }
                case "++", "--" -> {
                    advance();
                    Node target = unary();
                    if (!(target instanceof Ident || target instanceof Member)) throw err("Invalid ++/-- target");
                    return new Update(t.line(), t.col(), t.text(), true, target);
                }
                case "await" -> throw err("await is not supported. Use after(ticks, fn) for delays");
                default -> { }
            }
        }
        Node e = postfix();
        if ((at("++") || at("--")) && !peek().nlBefore()) {
            Token op = advance();
            if (!(e instanceof Ident || e instanceof Member)) throw err("Invalid ++/-- target");
            return new Update(op.line(), op.col(), op.text(), false, e);
        }
        return e;
    }

    private Node postfix() {
        Node e = primary();
        while (true) {
            Token t = peek();
            if (t.is(".")) {
                advance();
                if (at("#")) throw err("Private fields are not supported");
                e = new Member(t.line(), t.col(), e, propName(), null, false);
            } else if (t.is("?.")) {
                advance();
                if (at("(")) e = new Call(t.line(), t.col(), e, args(), true);
                else if (eat("[")) { Node idx = expression(); expect("]"); e = new Member(t.line(), t.col(), e, null, idx, true); }
                else e = new Member(t.line(), t.col(), e, propName(), null, true);
            } else if (t.is("[")) {
                advance();
                boolean prevNoIn = noIn;
                noIn = false;
                Node idx = expression();
                noIn = prevNoIn;
                expect("]");
                e = new Member(t.line(), t.col(), e, null, idx, false);
            } else if (t.is("(")) {
                e = new Call(t.line(), t.col(), e, args(), false);
            } else if (t.type() == T.TEMPLATE && !t.nlBefore()) {
                throw err("Tagged templates are not supported");
            } else break;
        }
        return e;
    }

    private List<Node> args() {
        expect("(");
        List<Node> list = new ArrayList<>();
        boolean prevNoIn = noIn;
        noIn = false;
        try {
            while (!eat(")")) {
                Token t = peek();
                if (eat("...")) list.add(new Spread(t.line(), t.col(), assignment()));
                else list.add(assignment());
                if (!eat(",")) { expect(")"); break; }
            }
        } finally {
            noIn = prevNoIn;
        }
        return list;
    }

    private Node primary() {
        Token t = peek();
        int l = t.line(), c = t.col();
        switch (t.type()) {
            case NUM -> { advance(); return new Num(l, c, t.num()); }
            case STR -> { advance(); return new Str(l, c, t.text()); }
            case TEMPLATE -> {
                advance();
                List<Node> exprs = new ArrayList<>();
                for (int i = 0; i < t.tplExprs().size(); i++) {
                    int[] pos = t.tplPos().get(i);
                    exprs.add(parseExpression(t.tplExprs().get(i), pos[0], pos[1]));
                }
                return new Template(l, c, t.tplParts(), exprs);
            }
            case IDENT -> { advance(); return new Ident(l, c, t.text()); }
            case KEYWORD -> {
                switch (t.text()) {
                    case "true" -> { advance(); return new Bool(l, c, true); }
                    case "false" -> { advance(); return new Bool(l, c, false); }
                    case "null" -> { advance(); return new Null(l, c); }
                    case "undefined" -> { advance(); return new Undef(l, c); }
                    case "this" -> { advance(); return new This(l, c); }
                    case "function" -> {
                        advance();
                        String name = at(T.IDENT) ? identName() : null;
                        return functionRest(l, c, name);
                    }
                    case "new" -> {
                        // new Error("...") 만 허용 (일반 호출과 같다)
                        Token n = peek(1);
                        if (n.type() == T.IDENT && (n.text().equals("Error") || n.text().equals("TypeError") || n.text().equals("RangeError"))) {
                            advance();
                            advance();
                            Node callee = new Ident(n.line(), n.col(), n.text());
                            List<Node> a = at("(") ? args() : List.of();
                            return new Call(l, c, callee, a, false);
                        }
                        throw err("'new' is not supported. Use object/array literals ({} / []) instead of new Map/Set/Array; use now() instead of new Date()");
                    }
                    case "class" -> throw err("'class' is not supported. Use plain objects and functions");
                    default -> throw err("Unexpected keyword '" + t.text() + "'");
                }
            }
            case PUNCT -> {
                if (t.is("(")) {
                    advance();
                    boolean prevNoIn = noIn;
                    noIn = false;
                    Node e = expression();
                    noIn = prevNoIn;
                    expect(")");
                    return e;
                }
                if (t.is("[")) return arrayLit();
                if (t.is("{")) return objectLit();
                throw err("Unexpected " + t);
            }
            default -> throw err("Unexpected " + t);
        }
    }

    private Node arrayLit() {
        Token t = expect("[");
        List<Node> elems = new ArrayList<>();
        boolean prevNoIn = noIn;
        noIn = false;
        try {
            while (!eat("]")) {
                if (at(",")) { advance(); elems.add(new Undef(peek().line(), peek().col())); continue; }
                Token s = peek();
                if (eat("...")) elems.add(new Spread(s.line(), s.col(), assignment()));
                else elems.add(assignment());
                if (!eat(",")) { expect("]"); break; }
            }
        } finally {
            noIn = prevNoIn;
        }
        return new ArrayLit(t.line(), t.col(), elems);
    }

    private Node objectLit() {
        Token t = expect("{");
        List<Prop> props = new ArrayList<>();
        boolean prevNoIn = noIn;
        noIn = false;
        try {
            while (!eat("}")) {
                Token k = peek();
                if (eat("...")) {
                    props.add(new Prop(null, null, assignment(), true));
                } else {
                    if ((k.is("get") || k.is("set") || k.text().equals("get") || k.text().equals("set"))
                            && k.type() == T.IDENT && (peek(1).type() == T.IDENT || peek(1).type() == T.STR) && peek(2).is("(")) {
                        throw err("Getters/setters are not supported. Use a normal method like getValue()");
                    }
                    String key = null;
                    Node keyExpr = null;
                    if (eat("[")) { keyExpr = assignment(); expect("]"); }
                    else key = propName();
                    Node value;
                    if (at("(")) {
                        value = functionRest(k.line(), k.col(), key);
                    } else if (eat(":")) {
                        value = assignment();
                    } else {
                        if (key == null || k.type() != T.IDENT) throw err("Expected ':' after property name");
                        Node id = new Ident(k.line(), k.col(), key);
                        // 구조 분해 대입용 기본값 {a = 1} = obj
                        if (at("=")) { advance(); value = new Assign(k.line(), k.col(), "=", id, assignment()); }
                        else value = id;
                    }
                    props.add(new Prop(key, keyExpr, value, false));
                }
                if (!eat(",")) { expect("}"); break; }
            }
        } finally {
            noIn = prevNoIn;
        }
        return new ObjectLit(t.line(), t.col(), props);
    }
}
