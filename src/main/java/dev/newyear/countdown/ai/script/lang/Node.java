package dev.newyear.countdown.ai.script.lang;

import java.util.List;

/** WishScript 구문 트리. 모든 노드는 오류 보고를 위해 줄/칸을 가진다. */
public sealed interface Node {
    int line();
    int col();

    // ---- 식 ----
    record Num(int line, int col, double value) implements Node {}
    record Str(int line, int col, String value) implements Node {}
    record Template(int line, int col, List<String> parts, List<Node> exprs) implements Node {}
    record Bool(int line, int col, boolean value) implements Node {}
    record Null(int line, int col) implements Node {}
    record Undef(int line, int col) implements Node {}
    record Ident(int line, int col, String name) implements Node {}
    record This(int line, int col) implements Node {}
    record ArrayLit(int line, int col, List<Node> elems) implements Node {}
    /** key 는 computed 가 아니면 이름, 맞으면 keyExpr 사용. spread 면 value 가 펼칠 대상. */
    record Prop(String key, Node keyExpr, Node value, boolean spread) {}
    record ObjectLit(int line, int col, List<Prop> props) implements Node {}
    record Param(Node target, Node defaultValue, boolean rest) {}
    record Func(int line, int col, String name, List<Param> params, Node body, boolean arrow, boolean exprBody) implements Node {}
    record Member(int line, int col, Node object, String name, Node index, boolean optional) implements Node {}
    record Call(int line, int col, Node callee, List<Node> args, boolean optional) implements Node {}
    record Spread(int line, int col, Node arg) implements Node {}
    record Unary(int line, int col, String op, Node arg) implements Node {}
    record Update(int line, int col, String op, boolean prefix, Node target) implements Node {}
    record Binary(int line, int col, String op, Node left, Node right) implements Node {}
    record Logical(int line, int col, String op, Node left, Node right) implements Node {}
    record Cond(int line, int col, Node test, Node then, Node otherwise) implements Node {}
    record Assign(int line, int col, String op, Node target, Node value) implements Node {}
    record Seq(int line, int col, List<Node> exprs) implements Node {}

    // ---- 구조 분해 패턴 ----
    record PatProp(String key, Node keyExpr, Node target, Node defaultValue) {}
    record ObjPattern(int line, int col, List<PatProp> props, Node rest) implements Node {}
    /** elems 의 null 은 건너뛴 자리. */
    record ArrPattern(int line, int col, List<Node> elems, List<Node> defaults, Node rest) implements Node {}

    // ---- 문장 ----
    record Declarator(Node target, Node init) {}
    record VarDecl(int line, int col, String kind, List<Declarator> decls) implements Node {}
    record FuncDecl(int line, int col, Func func) implements Node {}
    record Return(int line, int col, Node value) implements Node {}
    record If(int line, int col, Node test, Node then, Node otherwise) implements Node {}
    record While(int line, int col, Node test, Node body) implements Node {}
    record DoWhile(int line, int col, Node body, Node test) implements Node {}
    record For(int line, int col, Node init, Node test, Node update, Node body) implements Node {}
    /** of=true 면 for-of, 아니면 for-in. kind 는 let/const/var 또는 null (기존 변수). */
    record ForEach(int line, int col, String kind, Node target, Node iterable, Node body, boolean of) implements Node {}
    record Break(int line, int col) implements Node {}
    record Continue(int line, int col) implements Node {}
    record Throw(int line, int col, Node value) implements Node {}
    record Try(int line, int col, Block block, Node catchParam, Block handler, Block finalizer) implements Node {}
    record Case(Node test, List<Node> body) {}
    record Switch(int line, int col, Node discriminant, List<Case> cases) implements Node {}
    record Block(int line, int col, List<Node> body) implements Node {}
    record ExprStmt(int line, int col, Node expr) implements Node {}
    record Empty(int line, int col) implements Node {}
    record Program(int line, int col, List<Node> body) implements Node {}
}
