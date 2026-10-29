package dev.newyear.countdown.ai.script.lang;

/** 스크립트 오류. 줄/칸 정보를 담아 AI 가 코드를 고칠 수 있게 한다. */
public class ScriptError extends RuntimeException {
    public enum Kind {
        /** 문법 오류 (파싱 단계). */
        SYNTAX,
        /** 정적 검사에서 발견된 오류 (알 수 없는 이름 등). */
        STATIC,
        /** 실행 중 오류 (타입 오류, throw 등). */
        RUNTIME,
        /** 실행 예산 초과 (단계 수, 메모리, 시간, 재귀 깊이). 스크립트를 멈춰야 한다. */
        BUDGET,
        /** 권한/정책 위반. */
        PERMISSION
    }

    public final Kind kind;
    public final int line, col;
    /** throw 로 던진 스크립트 값 (catch 에서 그대로 돌려준다). */
    public final Object thrown;

    public ScriptError(Kind kind, String message, int line, int col) {
        this(kind, message, line, col, null);
    }

    public ScriptError(Kind kind, String message, int line, int col, Object thrown) {
        super(message, null, false, false);
        this.kind = kind;
        this.line = line;
        this.col = col;
        this.thrown = thrown;
    }

    /** 위치 없이 만든 오류에 위치를 붙인다. */
    public ScriptError at(int line, int col) {
        if (this.line > 0) return this;
        return new ScriptError(kind, getMessage(), line, col, thrown);
    }

    public String describe() {
        String where = line > 0 ? " (line " + line + ":" + col + ")" : "";
        return kind.name().toLowerCase() + " error: " + getMessage() + where;
    }

    @Override
    public String toString() {
        return describe();
    }
}
