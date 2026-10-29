package dev.newyear.countdown.ai.agent;

import java.util.UUID;

/** AI 에게 맡길 한 번의 턴. */
public record TurnRequest(String wishId, UUID player, Kind kind, String message, String trigger) {
    public enum Kind {
        /** 플레이어가 말함. */
        PLAYER,
        /** AI 가 띄운 UI 에 대한 응답. */
        UI,
        /** 스크립트가 ai.ask 로 알림. */
        SCRIPT,
        /** 자율 이벤트 판단. */
        AUTONOMOUS
    }

    public static TurnRequest player(String wishId, UUID player, String text) {
        return new TurnRequest(wishId, player, Kind.PLAYER, text, null);
    }
}
