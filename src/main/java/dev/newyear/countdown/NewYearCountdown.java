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
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.sounds.SoundSource;
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

        PayloadTypeRegistry.clientboundPlay().register(SyncPayload.ID, SyncPayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(SetTargetPayload.ID, SetTargetPayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(AuthPayload.ID, AuthPayload.CODEC);
        BellPackets.register();
        dev.newyear.countdown.sebae.Sebae.init();

        // 접속 시 현재 상태 동기화, 퇴장 시 인증 해제
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
                ServerPlayNetworking.send(handler.player, CountdownState.snapshot()));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
                CountdownState.deauthorize(handler.player.getUUID()));

        // 비밀번호 검증 (서버에서 직접 확인)
        ServerPlayNetworking.registerGlobalReceiver(AuthPayload.ID, (payload, context) -> {
            if (DebugAuth.matches(payload.password())) {
                CountdownState.authorize(context.player().getUUID());
            }
        });

        // 클라이언트 설정 화면의 요청 처리 — 인증된 플레이어만
        ServerPlayNetworking.registerGlobalReceiver(SetTargetPayload.ID, (payload, context) -> {
            ServerPlayer player = context.player();
            MinecraftServer server = player.level().getServer();
            if (server == null) return;
            if (!CountdownState.isAuthorized(player.getUUID())) {
                player.sendOverlayMessage(Component.translatable("debug.newyearcountdown.no_permission"));
                return;
            }
            CountdownState.apply(payload, server);
        });

        // 1초마다 종료 여부 확인
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (server.getTickCount() % 20 == 0) checkFinished(server);
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

    /** 그 플레이어에게만 들리는 소리. */
    private static void notify(ServerPlayer p, SoundEvent sound) {
        p.connection.send(new ClientboundSoundPacket(BuiltInRegistries.SOUND_EVENT.wrapAsHolder(sound), SoundSource.MASTER,
                p.getX(), p.getY(), p.getZ(), 1.0f, 1.0f, p.getRandom().nextLong()));
    }

    private static void celebrate(MinecraftServer server) {
        CountdownConfig cfg = CountdownConfig.get();
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            p.connection.send(new ClientboundSetTitlesAnimationPacket(10, 100, 30));
            p.connection.send(new ClientboundSetSubtitleTextPacket(Component.literal(cfg.subtitle)));
            p.connection.send(new ClientboundSetTitleTextPacket(Component.literal(cfg.title)));
            notify(p, SoundEvents.UI_TOAST_CHALLENGE_COMPLETE);
            notify(p, SoundEvents.FIREWORK_ROCKET_LARGE_BLAST);
        }
        if (!cfg.chatMessage.isEmpty()) {
            server.getPlayerList().broadcastSystemMessage(Component.literal(cfg.chatMessage), false);
        }
        // 설치된 모든 보신각이 33번 타종
        BellRegistry.startAutoRingAll(NEW_YEAR_BELL_STRIKES);
    }
}
