package dev.newyear.countdown.omikuji;

import dev.newyear.countdown.ModSounds;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

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

    public static void tick(World world, BlockPos pos, BlockState state, OmikujiBlockEntity be) {
        if (world instanceof ServerWorld sw) be.serverTick(sw);
    }

    private void serverTick(ServerWorld world) {
        if (startTick < 0) return;
        long dt = world.getTime() - startTick;
        if (!revealed && dt >= REVEAL_AT) {
            revealed = true;
            reveal(world);
        }
        if (dt >= BUSY_UNTIL) startTick = -1;
    }

    /** 우클릭: 뽑기 시작 (서버). */
    public void draw(ServerPlayerEntity sp) {
        if (!(world instanceof ServerWorld sw)) return;
        if (startTick >= 0) {
            sp.sendMessage(Text.translatable("omikuji.newyearcountdown.busy"), true);
            return;
        }
        startTick = sw.getTime();
        drawer = sp.getUuid();
        revealed = false;
        result = Fortunes.pick(new java.util.Random(sw.getRandom().nextLong()));

        sw.playSound(null, pos, ModSounds.OMIKUJI_SUZU, SoundCategory.BLOCKS, 0.9f, 1.0f);
        sw.playSound(null, pos, ModSounds.OMIKUJI_SHAKE, SoundCategory.BLOCKS, 1.0f, 1.0f);
        for (ServerPlayerEntity p : PlayerLookup.tracking(sw, pos)) {
            ServerPlayNetworking.send(p, new OmikujiPackets.AnimS2C(pos, result));
        }
    }

    private void reveal(ServerWorld sw) {
        Fortunes.Fortune f = Fortunes.get(result);
        sw.playSound(null, pos, ModSounds.OMIKUJI_REVEAL, SoundCategory.BLOCKS, 1.0f, f.tier() < 0 ? 0.7f : 1.0f);
        if (f.tier() == 2) sw.playSound(null, pos, SoundEvents.ENTITY_FIREWORK_ROCKET_TWINKLE, SoundCategory.BLOCKS, 1.0f, 1.0f);
        if (f.tier() == -2) sw.playSound(null, pos, SoundEvents.ENTITY_WITHER_AMBIENT, SoundCategory.BLOCKS, 0.4f, 1.6f);

        spawnParticles(sw);

        ServerPlayerEntity p = drawer == null ? null : sw.getServer().getPlayerManager().getPlayer(drawer);
        if (p == null) return;

        Text name = Text.translatable("omikuji.newyearcountdown.fortune." + f.key()).formatted(f.color(), Formatting.BOLD);
        // 결과는 채팅이 아니라 뽑은 사람 화면에 운세 종이 UI 로 보여 준다
        OmikujiData.Entry saved = OmikujiData.get(sw.getServer())
                .add(p.getUuid(), result, 1 + sw.getRandom().nextInt(100), System.currentTimeMillis());
        ServerPlayNetworking.send(p, new OmikujiPackets.ResultS2C(saved.result, saved.number, saved.time));

        if (f.tier() == 2) { // 대길은 서버 전체에 알린다
            sw.getServer().getPlayerManager().broadcast(
                    Text.translatable("omikuji.newyearcountdown.broadcast", p.getDisplayName(), name), false);
        }
    }

    /** 결과에 맞는 파티클 연출 (서버가 뿌리면 근처 모두에게 보인다). */
    private void spawnParticles(ServerWorld sw) {
        double x = pos.getX() + 0.5, y = pos.getY() + 2.9, z = pos.getZ() + 0.5;
        Fortunes.Fortune f = Fortunes.get(result);
        switch (f.key()) {
            case "daekil" -> { // 대길: 황금빛 폭죽
                sw.spawnParticles(ParticleTypes.TOTEM_OF_UNDYING, x, y, z, 50, 0.5, 0.3, 0.5, 0.4);
                sw.spawnParticles(ParticleTypes.END_ROD, x, y + 0.3, z, 30, 0.8, 0.5, 0.8, 0.05);
            }
            case "jungil", "sokil" -> sw.spawnParticles(ParticleTypes.HAPPY_VILLAGER, x, y, z, 24, 0.7, 0.4, 0.7, 0.0);
            case "malgil" -> sw.spawnParticles(ParticleTypes.END_ROD, x, y, z, 14, 0.5, 0.3, 0.5, 0.02);
            case "pyeongta" -> sw.spawnParticles(ParticleTypes.CHERRY_LEAVES, x, y + 0.5, z, 30, 0.9, 0.4, 0.9, 0.0);
            case "jwejwe" -> { // 최악
                sw.spawnParticles(ParticleTypes.SOUL, x, y, z, 20, 0.5, 0.3, 0.5, 0.03);
                sw.spawnParticles(ParticleTypes.LARGE_SMOKE, x, y, z, 16, 0.5, 0.2, 0.5, 0.02);
            }
            default -> sw.spawnParticles(ParticleTypes.SMOKE, x, y, z, 20, 0.4, 0.2, 0.4, 0.02);   // 흉 계열
        }
    }
}
