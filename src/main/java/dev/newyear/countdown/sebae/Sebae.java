package dev.newyear.countdown.sebae;

import dev.newyear.countdown.CountdownConfig;
import dev.newyear.countdown.NewYearCountdown;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.Uuids;
import net.minecraft.util.math.Vec3d;

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
 * 타임라인(틱): 0~10 무릎 꿇기, 10~22 엎드리기, 22~40 그대로, 40~50 일어나 앉기, 50~60 일어서기.
 */
public final class Sebae {
    public static final int TOTAL = 60;
    public static final int BOW_AT = 22;
    private static final int XP_REWARD = 30;

    /** 클라이언트 → 서버: 세배하기. */
    public record StartC2S() implements CustomPayload {
        public static final Id<StartC2S> ID = new Id<>(Identifier.of(NewYearCountdown.MOD_ID, "sebae_start"));
        public static final PacketCodec<RegistryByteBuf, StartC2S> CODEC = PacketCodec.unit(new StartC2S());
        @Override public Id<? extends CustomPayload> getId() { return ID; }
    }

    /** 서버 → 클라이언트: 이 플레이어가 절을 시작(true)/중단(false). */
    public record StateS2C(UUID player, boolean bowing) implements CustomPayload {
        public static final Id<StateS2C> ID = new Id<>(Identifier.of(NewYearCountdown.MOD_ID, "sebae_state"));
        public static final PacketCodec<RegistryByteBuf, StateS2C> CODEC = PacketCodec.tuple(
                Uuids.PACKET_CODEC, StateS2C::player,
                PacketCodecs.BOOL, StateS2C::bowing,
                StateS2C::new);
        @Override public Id<? extends CustomPayload> getId() { return ID; }
    }

    private static final class Bow {
        final long start;
        final Vec3d pos;
        final UUID target;

        Bow(long start, Vec3d pos, UUID target) {
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
        PayloadTypeRegistry.playC2S().register(StartC2S.ID, StartC2S.CODEC);
        PayloadTypeRegistry.playS2C().register(StateS2C.ID, StateS2C.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(StartC2S.ID, (payload, context) -> start(context.player()));
        ServerTickEvents.END_SERVER_TICK.register(Sebae::tick);
    }

    private static void start(ServerPlayerEntity p) {
        if (ACTIVE.containsKey(p.getUuid())) return;
        if (!p.isOnGround() || p.hasVehicle() || p.isSleeping() || p.isSwimming() || p.isSpectator() || p.isFallFlying()) {
            p.sendMessage(Text.translatable("sebae.newyearcountdown.cannot").formatted(Formatting.GRAY), true);
            return;
        }
        ServerWorld sw = (ServerWorld) p.getWorld();
        ServerPlayerEntity target = facedPlayer(sw, p);
        ACTIVE.put(p.getUuid(), new Bow(sw.getTime(), p.getPos(), target == null ? null : target.getUuid()));
        broadcast(p, true);
    }

    /** 앞쪽 5칸 안에서 바라보고 있는 플레이어. */
    private static ServerPlayerEntity facedPlayer(ServerWorld sw, ServerPlayerEntity p) {
        Vec3d look = p.getRotationVec(1.0f).multiply(1, 0, 1).normalize();
        ServerPlayerEntity best = null;
        double bestDot = 0.75;
        for (ServerPlayerEntity o : sw.getPlayers()) {
            if (o == p || o.isSpectator()) continue;
            Vec3d to = o.getPos().subtract(p.getPos()).multiply(1, 0, 1);
            double d = to.length();
            if (d > 5.0 || d < 0.3) continue;
            double dot = to.normalize().dotProduct(look);
            if (dot > bestDot) {
                bestDot = dot;
                best = o;
            }
        }
        return best;
    }

    private static void broadcast(ServerPlayerEntity p, boolean bowing) {
        StateS2C msg = new StateS2C(p.getUuid(), bowing);
        ServerPlayNetworking.send(p, msg);
        for (ServerPlayerEntity o : PlayerLookup.tracking(p)) {
            if (o != p) ServerPlayNetworking.send(o, msg);
        }
    }

    private static void tick(MinecraftServer server) {
        Iterator<Map.Entry<UUID, Bow>> it = ACTIVE.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Bow> e = it.next();
            ServerPlayerEntity p = server.getPlayerManager().getPlayer(e.getKey());
            Bow b = e.getValue();
            if (p == null) {
                it.remove();
                continue;
            }
            long age = p.getWorld().getTime() - b.start;
            boolean moved = p.getPos().squaredDistanceTo(b.pos) > 0.6 * 0.6;
            if (moved || age >= Sebae.TOTAL || age < 0) {
                it.remove();
                broadcast(p, false);
                continue;
            }
            if (age == BOW_AT) bowed(server, p, b);
        }
    }

    /** 가장 깊이 엎드린 순간: 세배 성립. */
    private static void bowed(MinecraftServer server, ServerPlayerEntity p, Bow b) {
        ServerWorld sw = (ServerWorld) p.getWorld();
        ServerPlayerEntity target = b.target == null ? null : server.getPlayerManager().getPlayer(b.target);
        if (target != null && target.getWorld() != p.getWorld()) target = null;
        Text msg = target == null
                ? Text.translatable("sebae.newyearcountdown.alone", p.getDisplayName())
                : Text.translatable("sebae.newyearcountdown.to", p.getDisplayName(), target.getDisplayName());
        for (ServerPlayerEntity o : sw.getPlayers(pl -> pl.squaredDistanceTo(p) <= 24 * 24)) {
            o.sendMessage(msg.copy().formatted(Formatting.GOLD), false);
        }
        sw.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.BLOCK_WOOL_PLACE, SoundCategory.PLAYERS, 0.8f, 0.8f);
        sw.spawnParticles(ParticleTypes.HAPPY_VILLAGER, p.getX(), p.getY() + 0.6, p.getZ(), 8, 0.5, 0.3, 0.5, 0.0);
        if (target == null) return;

        sw.spawnParticles(ParticleTypes.HEART, target.getX(), target.getEyeY() + 0.5, target.getZ(), 5, 0.3, 0.2, 0.3, 0.0);
        String key = p.getUuid() + "|" + target.getUuid() + "|" + LocalDate.now(CountdownConfig.get().zoneId());
        if (!REWARDED.add(key)) {
            p.sendMessage(Text.translatable("sebae.newyearcountdown.already", target.getDisplayName()).formatted(Formatting.GRAY), true);
            return;
        }
        p.addExperience(XP_REWARD);
        target.addStatusEffect(new StatusEffectInstance(StatusEffects.LUCK, 20 * 60 * 5, 0));
        sw.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, SoundCategory.PLAYERS, 0.8f, 1.2f);
        p.sendMessage(Text.translatable("sebae.newyearcountdown.reward", XP_REWARD).formatted(Formatting.YELLOW), true);
        target.sendMessage(Text.translatable("sebae.newyearcountdown.received", p.getDisplayName()).formatted(Formatting.YELLOW), true);
    }
}
