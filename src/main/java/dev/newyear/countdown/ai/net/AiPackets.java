package dev.newyear.countdown.ai.net;

import dev.newyear.countdown.ModReg;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * AI 소원 UI 프로토콜. UI 는 선언형이라 서버가 JSON 으로 "무엇을" 보여 줄지 보내고, 모양은 클라이언트 디자인 시스템이 정한다.
 * 클라이언트 → 서버는 대화 입력과 UI 상호작용(고유 ID + nonce)만 보낸다. API 키는 어떤 패킷에도 실리지 않는다.
 */
public final class AiPackets {
    public static final int MAX_JSON = 60_000;

    private AiPackets() {}

    /** 서버 → 클라이언트: UI 연산 하나 ({"op": "...", ...}). */
    public record UiS2C(String json) implements CustomPacketPayload {
        public static final Type<UiS2C> ID = new Type<>(ModReg.id("ai_ui"));
        public static final StreamCodec<RegistryFriendlyByteBuf, UiS2C> CODEC =
                ByteBufCodecs.stringUtf8(MAX_JSON).map(UiS2C::new, UiS2C::json).cast();

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return ID;
        }
    }

    /** 클라이언트 → 서버: 소원 대화 입력. wishId 가 비면 새 소원. */
    public record ChatC2S(String wishId, String text, int nonce) implements CustomPacketPayload {
        public static final Type<ChatC2S> ID = new Type<>(ModReg.id("ai_chat"));
        public static final StreamCodec<RegistryFriendlyByteBuf, ChatC2S> CODEC = StreamCodec.composite(
                ByteBufCodecs.stringUtf8(64), ChatC2S::wishId,
                ByteBufCodecs.stringUtf8(1000), ChatC2S::text,
                ByteBufCodecs.VAR_INT, ChatC2S::nonce,
                ChatC2S::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return ID;
        }
    }

    /** 클라이언트 → 서버: UI 상호작용 응답 (선택지 번호 또는 입력 텍스트). */
    public record InteractC2S(String interactionId, int index, String value, int nonce) implements CustomPacketPayload {
        public static final Type<InteractC2S> ID = new Type<>(ModReg.id("ai_interact"));
        public static final StreamCodec<RegistryFriendlyByteBuf, InteractC2S> CODEC = StreamCodec.composite(
                ByteBufCodecs.stringUtf8(64), InteractC2S::interactionId,
                ByteBufCodecs.VAR_INT, InteractC2S::index,
                ByteBufCodecs.stringUtf8(1000), InteractC2S::value,
                ByteBufCodecs.VAR_INT, InteractC2S::nonce,
                InteractC2S::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return ID;
        }
    }

    /** 클라이언트 → 서버: 화면을 열었으니 전체 상태를 다시 보내 달라 / 동작 요청 (cancel 등). */
    public record ActionC2S(String action, String arg) implements CustomPacketPayload {
        public static final Type<ActionC2S> ID = new Type<>(ModReg.id("ai_action"));
        public static final StreamCodec<RegistryFriendlyByteBuf, ActionC2S> CODEC = StreamCodec.composite(
                ByteBufCodecs.stringUtf8(32), ActionC2S::action,
                ByteBufCodecs.stringUtf8(128), ActionC2S::arg,
                ActionC2S::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return ID;
        }
    }

    public static void registerTypes() {
        PayloadTypeRegistry.clientboundPlay().register(UiS2C.ID, UiS2C.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(ChatC2S.ID, ChatC2S.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(InteractC2S.ID, InteractC2S.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(ActionC2S.ID, ActionC2S.CODEC);
    }
}
