package dev.newyear.countdown.sebae;

import dev.newyear.countdown.CountdownConfig;
import dev.newyear.countdown.NewYearCountdown;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.phys.Vec3;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 세배(큰절). 클라이언트가 세배 키를 누르면 서버가 확인한 뒤 주변 모두에게 절하는 자세를 보여 준다.
 * 앞에 있는 플레이어를 바라보고 절하면 "세배"가 되어, 절한 사람은 경험치(세뱃돈), 받은 사람은 행운(덕담)을 얻는다
 * (같은 상대에게는 하루 한 번). 절하는 도중 움직이면 멈춘다.
 *
 * 타임라인(틱): 0~10 손을 이마로, 10~40 무릎 꿇고 앉기, 40~56 엎드리기, 56~76 그대로, 76~88 상체 들기,
 * 88~110 일어서기, 106~116 손 내리기. 자세는 클라이언트 SebaePose.
 */
public final class Sebae {
    public static final int TOTAL = 120;
    public static final int BOW_AT = 56;
    private static final int XP_REWARD = 30;

    /** 클라이언트 → 서버: 세배하기. */
    public record StartC2S() implements CustomPacketPayload {
        public static final Type<StartC2S> ID = new Type<>(Identifier.fromNamespaceAndPath(NewYearCountdown.MOD_ID, "sebae_start"));
        public static final StreamCodec<RegistryFriendlyByteBuf, StartC2S> CODEC = StreamCodec.unit(new StartC2S());
        @Override public Type<? extends CustomPacketPayload> type() { return ID; }
    }

    /** 서버 → 클라이언트: 이 플레이어가 절을 시작(true)/중단(false). */
    public record StateS2C(UUID player, boolean bowing) implements CustomPacketPayload {
        public static final Type<StateS2C> ID = new Type<>(Identifier.fromNamespaceAndPath(NewYearCountdown.MOD_ID, "sebae_state"));
        public static final StreamCodec<RegistryFriendlyByteBuf, StateS2C> CODEC = StreamCodec.composite(
                UUIDUtil.STREAM_CODEC, StateS2C::player,
                ByteBufCodecs.BOOL, StateS2C::bowing,
                StateS2C::new);
        @Override public Type<? extends CustomPacketPayload> type() { return ID; }
    }

    private static final class Bow {
        final long start;
        final Vec3 pos;
        final UUID target;

        Bow(long start, Vec3 pos, UUID target) {
            this.start = start;
            this.pos = pos;
            this.target = target;
        }
    }

    private static final Map<UUID, Bow> ACTIVE = new HashMap<>();
    /** "절한 사람|받은 사람|날짜" — 같은 상대에게 하루 한 번만 보상. */
    private static final Set<String> REWARDED = new HashSet<>();

    private Sebae() {}

    public static void init() {
        PayloadTypeRegistry.serverboundPlay().register(StartC2S.ID, StartC2S.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(StateS2C.ID, StateS2C.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(StartC2S.ID, (payload, context) -> start(context.player()));
        ServerTickEvents.END_SERVER_TICK.register(Sebae::tick);
    }

    private static void start(ServerPlayer p) {
        if (ACTIVE.containsKey(p.getUUID())) return;
        if (!p.onGround() || p.isPassenger() || p.isSleeping() || p.isSwimming() || p.isSpectator() || p.isFallFlying()) {
            p.sendOverlayMessage(Component.translatable("sebae.newyearcountdown.cannot").withStyle(ChatFormatting.GRAY));
            return;
        }
        ServerLevel sw = (ServerLevel) p.level();
        ServerPlayer target = facedPlayer(sw, p);
        ACTIVE.put(p.getUUID(), new Bow(sw.getGameTime(), p.position(), target == null ? null : target.getUUID()));
        broadcast(p, true);
    }

    /** 앞쪽 5칸 안에서 바라보고 있는 플레이어. */
    private static ServerPlayer facedPlayer(ServerLevel sw, ServerPlayer p) {
        Vec3 look = p.getViewVector(1.0f).multiply(1, 0, 1).normalize();
        ServerPlayer best = null;
        double bestDot = 0.75;
        for (ServerPlayer o : sw.players()) {
            if (o == p || o.isSpectator()) continue;
            Vec3 to = o.position().subtract(p.position()).multiply(1, 0, 1);
            double d = to.length();
            if (d > 5.0 || d < 0.3) continue;
            double dot = to.normalize().dot(look);
            if (dot > bestDot) {
                bestDot = dot;
                best = o;
            }
        }
        return best;
    }

    private static void broadcast(ServerPlayer p, boolean bowing) {
        StateS2C msg = new StateS2C(p.getUUID(), bowing);
        ServerPlayNetworking.send(p, msg);
        for (ServerPlayer o : PlayerLookup.tracking(p)) {
            if (o != p) ServerPlayNetworking.send(o, msg);
        }
    }

    private static void tick(MinecraftServer server) {
        Iterator<Map.Entry<UUID, Bow>> it = ACTIVE.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Bow> e = it.next();
            ServerPlayer p = server.getPlayerList().getPlayer(e.getKey());
            Bow b = e.getValue();
            if (p == null) {
                it.remove();
                continue;
            }
            long age = p.level().getGameTime() - b.start;
            boolean moved = p.position().distanceToSqr(b.pos) > 0.6 * 0.6;
            if (moved || age >= Sebae.TOTAL || age < 0) {
                it.remove();
                broadcast(p, false);
                continue;
            }
            if (age == BOW_AT) bowed(server, p, b);
        }
    }

    /** 가장 깊이 엎드린 순간: 세배 성립. */
    private static void bowed(MinecraftServer server, ServerPlayer p, Bow b) {
        ServerLevel sw = (ServerLevel) p.level();
        ServerPlayer target = b.target == null ? null : server.getPlayerList().getPlayer(b.target);
        if (target != null && target.level() != p.level()) target = null;
        Component msg = target == null
                ? Component.translatable("sebae.newyearcountdown.alone", p.getDisplayName())
                : Component.translatable("sebae.newyearcountdown.to", p.getDisplayName(), target.getDisplayName());
        for (ServerPlayer o : sw.getPlayers(pl -> pl.distanceToSqr(p) <= 24 * 24)) {
            o.sendSystemMessage(msg.copy().withStyle(ChatFormatting.GOLD));
        }
        sw.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.WOOL_PLACE, SoundSource.PLAYERS, 0.8f, 0.8f);
        sw.sendParticles(ParticleTypes.HAPPY_VILLAGER, p.getX(), p.getY() + 0.6, p.getZ(), 8, 0.5, 0.3, 0.5, 0.0);
        if (target == null) return;

        sw.sendParticles(ParticleTypes.HEART, target.getX(), target.getEyeY() + 0.5, target.getZ(), 5, 0.3, 0.2, 0.3, 0.0);
        String key = p.getUUID() + "|" + target.getUUID() + "|" + LocalDate.now(CountdownConfig.get().zoneId());
        if (!REWARDED.add(key)) {
            p.sendOverlayMessage(Component.translatable("sebae.newyearcountdown.already", target.getDisplayName()).withStyle(ChatFormatting.GRAY));
            return;
        }
        p.giveExperiencePoints(XP_REWARD);
        target.addEffect(new MobEffectInstance(MobEffects.LUCK, 20 * 60 * 5, 0));
        sw.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.EXPERIENCE_ORB_PICKUP, SoundSource.PLAYERS, 0.8f, 1.2f);
        p.sendOverlayMessage(Component.translatable("sebae.newyearcountdown.reward", XP_REWARD).withStyle(ChatFormatting.YELLOW));
        target.sendOverlayMessage(Component.translatable("sebae.newyearcountdown.received", p.getDisplayName()).withStyle(ChatFormatting.YELLOW));
    }
}
