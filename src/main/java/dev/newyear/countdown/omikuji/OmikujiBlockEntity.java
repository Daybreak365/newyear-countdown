package dev.newyear.countdown.omikuji;

import dev.newyear.countdown.ModSounds;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import java.util.UUID;

/** 오미쿠지 뽑기대. 서버가 결과를 정하고, 연출은 모든 근처 클라이언트가 같은 시간표로 재생한다. */
public class OmikujiBlockEntity extends BlockEntity {
    /** 연출 시간표 (tick): 통 흔들기 → 막대가 나옴 → 결과 공개 → 정리. */
    public static final int SHAKE_END = 42;
    public static final int REVEAL_AT = 52;
    public static final int STICK_DOWN_AT = 112;
    public static final int BUSY_UNTIL = 124;
    public static final int SLIP_UNTIL = 170;

    // 서버 상태
    private long startTick = -1;
    private UUID drawer;
    private int result = -1;
    private boolean revealed;

    // 클라이언트 연출 상태
    public long animStart = -100000;
    public int animResult = -1;

    public OmikujiBlockEntity(BlockPos pos, BlockState state) {
        super(OmikujiBlocks.OMIKUJI_BE, pos, state);
    }

    public static void tick(Level world, BlockPos pos, BlockState state, OmikujiBlockEntity be) {
        if (world instanceof ServerLevel sw) be.serverTick(sw);
    }

    private void serverTick(ServerLevel world) {
        if (startTick < 0) return;
        long dt = world.getGameTime() - startTick;
        if (!revealed && dt >= REVEAL_AT) {
            revealed = true;
            reveal(world);
        }
        if (dt >= BUSY_UNTIL) startTick = -1;
    }

    /** 우클릭: 뽑기 시작 (서버). */
    public void draw(ServerPlayer sp) {
        if (!(level instanceof ServerLevel sw)) return;
        if (startTick >= 0) {
            sp.sendOverlayMessage(Component.translatable("omikuji.newyearcountdown.busy"));
            return;
        }
        startTick = sw.getGameTime();
        drawer = sp.getUUID();
        revealed = false;
        result = Fortunes.pick(new java.util.Random(sw.getRandom().nextLong()));

        sw.playSound(null, worldPosition, ModSounds.OMIKUJI_SUZU, SoundSource.BLOCKS, 0.9f, 1.0f);
        sw.playSound(null, worldPosition, ModSounds.OMIKUJI_SHAKE, SoundSource.BLOCKS, 1.0f, 1.0f);
        for (ServerPlayer p : PlayerLookup.tracking(sw, worldPosition)) {
            ServerPlayNetworking.send(p, new OmikujiPackets.AnimS2C(worldPosition, result));
        }
    }

    private void reveal(ServerLevel sw) {
        Fortunes.Fortune f = Fortunes.get(result);
        sw.playSound(null, worldPosition, ModSounds.OMIKUJI_REVEAL, SoundSource.BLOCKS, 1.0f, f.tier() < 0 ? 0.7f : 1.0f);
        if (f.tier() == 2) sw.playSound(null, worldPosition, SoundEvents.FIREWORK_ROCKET_TWINKLE, SoundSource.BLOCKS, 1.0f, 1.0f);
        if (f.tier() == -2) sw.playSound(null, worldPosition, SoundEvents.WITHER_AMBIENT, SoundSource.BLOCKS, 0.4f, 1.6f);

        spawnParticles(sw);

        ServerPlayer p = drawer == null ? null : sw.getServer().getPlayerList().getPlayer(drawer);
        if (p == null) return;

        Component name = Component.translatable("omikuji.newyearcountdown.fortune." + f.key()).withStyle(f.color(), ChatFormatting.BOLD);
        // 결과는 채팅이 아니라 뽑은 사람 화면에 운세 종이 UI 로 보여 준다
        OmikujiData.Entry saved = OmikujiData.get(sw.getServer())
                .add(p.getUUID(), result, 1 + sw.getRandom().nextInt(100), System.currentTimeMillis());
        ServerPlayNetworking.send(p, new OmikujiPackets.ResultS2C(saved.result, saved.number, saved.time));

        if (f.tier() == 2) { // 대길은 서버 전체에 알린다
            sw.getServer().getPlayerList().broadcastSystemMessage(
                    Component.translatable("omikuji.newyearcountdown.broadcast", p.getDisplayName(), name), false);
        }
    }

    /** 결과에 맞는 파티클 연출 (서버가 뿌리면 근처 모두에게 보인다). */
    private void spawnParticles(ServerLevel sw) {
        double x = worldPosition.getX() + 0.5, y = worldPosition.getY() + 2.9, z = worldPosition.getZ() + 0.5;
        Fortunes.Fortune f = Fortunes.get(result);
        switch (f.key()) {
            case "daekil" -> { // 대길: 황금빛 폭죽
                sw.sendParticles(ParticleTypes.TOTEM_OF_UNDYING, x, y, z, 50, 0.5, 0.3, 0.5, 0.4);
                sw.sendParticles(ParticleTypes.END_ROD, x, y + 0.3, z, 30, 0.8, 0.5, 0.8, 0.05);
            }
            case "jungil", "sokil" -> sw.sendParticles(ParticleTypes.HAPPY_VILLAGER, x, y, z, 24, 0.7, 0.4, 0.7, 0.0);
            case "malgil" -> sw.sendParticles(ParticleTypes.END_ROD, x, y, z, 14, 0.5, 0.3, 0.5, 0.02);
            case "pyeongta" -> sw.sendParticles(ParticleTypes.CHERRY_LEAVES, x, y + 0.5, z, 30, 0.9, 0.4, 0.9, 0.0);
            case "jwejwe" -> { // 대흉
                sw.sendParticles(ParticleTypes.SOUL, x, y, z, 20, 0.5, 0.3, 0.5, 0.03);
                sw.sendParticles(ParticleTypes.LARGE_SMOKE, x, y, z, 16, 0.5, 0.2, 0.5, 0.02);
            }
            default -> sw.sendParticles(ParticleTypes.SMOKE, x, y, z, 20, 0.4, 0.2, 0.4, 0.02);   // 흉 계열
        }
    }
}
