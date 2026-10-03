package dev.newyear.countdown;

import dev.newyear.countdown.bell.BellPackets;
import dev.newyear.countdown.bell.BellRegistry;
import dev.newyear.countdown.bell.ModBlocks;
import dev.newyear.countdown.omikuji.OmikujiBlocks;
import dev.newyear.countdown.wish.WishEntities;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.packet.s2c.play.SubtitleS2CPacket;
import net.minecraft.network.packet.s2c.play.TitleFadeS2CPacket;
import net.minecraft.network.packet.s2c.play.TitleS2CPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class NewYearCountdown implements ModInitializer {
    public static final String MOD_ID = "newyearcountdown";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    /** 새해가 되면 보신각이 치는 횟수 (전통 33타). */
    public static final int NEW_YEAR_BELL_STRIKES = 33;

    /** 이미 축하 연출을 한 목표 시각. 같은 목표에 두 번 발동하지 않게 한다. */
    private static long lastCelebrated = Long.MIN_VALUE;

    @Override
    public void onInitialize() {
        CountdownConfig.load();
        ModSounds.init();
        ModBlocks.init();
        OmikujiBlocks.init();
        WishEntities.init();
        dev.newyear.countdown.gacha.GachaBlocks.init();
        ModItemGroup.init();

        PayloadTypeRegistry.playS2C().register(SyncPayload.ID, SyncPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(SetTargetPayload.ID, SetTargetPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(AuthPayload.ID, AuthPayload.CODEC);
        BellPackets.register();

        // 접속 시 현재 상태 동기화, 퇴장 시 인증 해제
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
                ServerPlayNetworking.send(handler.player, CountdownState.snapshot()));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
                CountdownState.deauthorize(handler.player.getUuid()));

        // 비밀번호 검증 (서버에서 직접 확인)
        ServerPlayNetworking.registerGlobalReceiver(AuthPayload.ID, (payload, context) -> {
            if (DebugAuth.matches(payload.password())) {
                CountdownState.authorize(context.player().getUuid());
            }
        });

        // 클라이언트 설정 화면의 요청 처리 — 인증된 플레이어만
        ServerPlayNetworking.registerGlobalReceiver(SetTargetPayload.ID, (payload, context) -> {
            ServerPlayerEntity player = context.player();
            MinecraftServer server = player.getServer();
            if (server == null) return;
            if (!CountdownState.isAuthorized(player.getUuid())) {
                player.sendMessage(Text.translatable("debug.newyearcountdown.no_permission"), true);
                return;
            }
            CountdownState.apply(payload, server);
        });

        // 1초마다 종료 여부 확인
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (server.getTicks() % 20 == 0) checkFinished(server);
        });
    }

    private static void checkFinished(MinecraftServer server) {
        long now = System.currentTimeMillis();
        long target = CountdownState.targetMs();
        if (now < target || lastCelebrated == target) return;
        lastCelebrated = target;
        // 서버가 목표 시각을 한참 지나서 켜진 경우엔 연출 생략
        if (now - target > 60_000) return;
        celebrate(server);
    }

    private static void celebrate(MinecraftServer server) {
        CountdownConfig cfg = CountdownConfig.get();
        for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
            p.networkHandler.sendPacket(new TitleFadeS2CPacket(10, 100, 30));
            p.networkHandler.sendPacket(new SubtitleS2CPacket(Text.literal(cfg.subtitle)));
            p.networkHandler.sendPacket(new TitleS2CPacket(Text.literal(cfg.title)));
            p.playSoundToPlayer(SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, SoundCategory.MASTER, 1.0f, 1.0f);
            p.playSoundToPlayer(SoundEvents.ENTITY_FIREWORK_ROCKET_LARGE_BLAST, SoundCategory.MASTER, 1.0f, 1.0f);
        }
        if (!cfg.chatMessage.isEmpty()) {
            server.getPlayerManager().broadcast(Text.literal(cfg.chatMessage), false);
        }
        // 설치된 모든 보신각이 33번 타종
        BellRegistry.startAutoRingAll(NEW_YEAR_BELL_STRIKES);
    }
}
