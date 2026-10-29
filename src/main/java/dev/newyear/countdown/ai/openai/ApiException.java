package dev.newyear.countdown.ai.openai;

/** API 호출 실패. kind 로 플레이어 안내 문구와 재시도 여부를 정한다. */
public class ApiException extends Exception {
    public enum Kind {
        NO_API_KEY, AUTH, RATE_LIMIT, QUOTA, BAD_REQUEST, SERVER, NETWORK, TIMEOUT, CANCELLED, BUDGET, DISABLED, INVALID_OUTPUT
    }

    public final Kind kind;
    public final int status;

    public ApiException(Kind kind, int status, String message) {
        super(message);
        this.kind = kind;
        this.status = status;
    }

    public ApiException(Kind kind, String message) {
        this(kind, 0, message);
    }

    public boolean retryable() {
        return kind == Kind.RATE_LIMIT || kind == Kind.SERVER || kind == Kind.NETWORK || kind == Kind.TIMEOUT;
    }
}
