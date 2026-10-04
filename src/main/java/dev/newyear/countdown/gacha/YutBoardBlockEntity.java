package dev.newyear.countdown.gacha;

import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 윷판 상태: 말 8개(빨강 0~3, 파랑 4~7)의 위치, 마지막으로 던진 윷가락 4개의 면과 결과.
 * 던지기 연출은 클라이언트(YutBoardRenderer)가 animStart 부터 시간으로 그린다.
 */
public class YutBoardBlockEntity extends BlockEntity {
    public static final int THROW_TICKS = 20;
    /** 판(3x3 전체를 0~1) 기준 좌표에서 이 값보다 앞(+z)이면 던지는 자리(붉은 양털). */
    public static final double THROW_Z = 34.0 / 48.0;

    /** 29점의 좌표 {lx, lz} (3x3 전체를 0~1). tools/modelgen/yut_board.py 의 points() 와 같은 공식. */
    public static final float[][] POINTS = buildPoints();

    public final int[] pieces = {-1, -1, -1, -1, -1, -1, -1, -1};   // 점 번호, -1 = 판 밖
    public int sticks;                // 비트 i = 1 이면 i번째 가락의 평평한 면이 위
    public int lastResult = -1;       // 1 도, 2 개, 3 걸, 4 윷, 5 모
    public int hilite = -1;           // 집어 든 말(표시용)
    public long animStart = Long.MIN_VALUE / 2;

    private long busyUntil;
    private long announceAt = -1;
    private UUID thrower;
    private final Map<UUID, Integer> teams = new HashMap<>();
    private final Map<UUID, Integer> selected = new HashMap<>();

    public YutBoardBlockEntity(BlockPos pos, BlockState state) {
        super(Souvenirs.YUT_BE, pos, state);
        Arrays.fill(pieces, -1);
    }

    private static float[][] buildPoints() {
        float x0 = 9f / 48, x1 = 39f / 48, z0 = 3f / 48, z1 = 33f / 48;
        float[][] corners = {{x1, z1}, {x1, z0}, {x0, z0}, {x0, z1}};
        List<float[]> l = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            float[] a = corners[i], b = corners[(i + 1) % 4];
            for (int k = 0; k < 5; k++) {
                float t = k / 5f;
                l.add(new float[]{a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t});
            }
        }
        float cx = (x0 + x1) / 2, cz = (z0 + z1) / 2;
        for (float[] c : corners) {
            for (float f : new float[]{1f / 3f, 2f / 3f}) l.add(new float[]{c[0] + (cx - c[0]) * f, c[1] + (cz - c[1]) * f});
        }
        l.add(new float[]{cx, cz});
        return l.toArray(new float[0][]);
    }

    // ------------------------------------------------------------------ 저장 / 동기화

    @Override
    protected void saveAdditional(ValueOutput nbt) {
        super.saveAdditional(nbt);
        nbt.putIntArray("pieces", pieces);
        nbt.putInt("sticks", sticks);
        nbt.putInt("result", lastResult);
        nbt.putInt("hilite", hilite);
    }

    @Override
    protected void loadAdditional(ValueInput nbt) {
        super.loadAdditional(nbt);
        int[] p = nbt.getIntArray("pieces").orElse(new int[0]);
        for (int i = 0; i < pieces.length; i++) pieces[i] = i < p.length ? p[i] : -1;
        sticks = nbt.getIntOr("sticks", 0);
        lastResult = nbt.getIntOr("result", -1);
        hilite = nbt.getIntOr("hilite", -1);
    }

    @Nullable
    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider lookup) {
        return saveWithoutMetadata(lookup);
    }

    private void sync() {
        setChanged();
        if (level != null) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_CLIENTS);
    }

    @Override
    public boolean triggerEvent(int type, int data) {
        if (type == 1 && level != null) {
            animStart = level.getGameTime();
            return true;
        }
        return super.triggerEvent(type, data);
    }

    // ------------------------------------------------------------------ 상호작용 (서버)

    /** lx, lz: 판 기준 클릭 지점(0~1, 앞쪽이 +z). */
    public void interact(ServerPlayer p, double lx, double lz) {
        if (!(level instanceof ServerLevel sw)) return;
        if (lz > THROW_Z) {
            throwSticks(sw, p);
            return;
        }
        int best = -1;
        double bestD = Double.MAX_VALUE;
        for (int i = 0; i < POINTS.length; i++) {
            double dx = POINTS[i][0] - lx, dz = POINTS[i][1] - lz;
            double d = dx * dx + dz * dz;
            if (d < bestD) {
                bestD = d;
                best = i;
            }
        }
        if (best < 0 || bestD > 0.06 * 0.06) {
            if (selected.remove(p.getUUID()) != null) {   // 점 밖을 누르면 선택 취소
                hilite = -1;
                sync();
                p.sendOverlayMessage(Component.translatable("yut.newyearcountdown.cancel").withStyle(ChatFormatting.GRAY));
            } else {
                p.sendOverlayMessage(Component.translatable("yut.newyearcountdown.hint").withStyle(ChatFormatting.GRAY));
            }
            return;
        }
        pointAction(sw, p, best);
    }

    private int teamOf(ServerPlayer p) {
        return teams.computeIfAbsent(p.getUUID(), k -> teams.size() % 2);
    }

    private static boolean sameTeam(int piece, int team) {
        return piece / 4 == team;
    }

    private static Component teamName(int team) {
        return Component.translatable(team == 0 ? "yut.newyearcountdown.team.red" : "yut.newyearcountdown.team.blue")
                .withStyle(team == 0 ? ChatFormatting.RED : ChatFormatting.BLUE);
    }

    private void pointAction(ServerLevel sw, ServerPlayer p, int idx) {
        int team = teamOf(p);
        Integer sel = selected.get(p.getUUID());
        if (sel != null) {                       // 집은 말을 여기로 옮긴다
            if (p.isShiftKeyDown()) {                // 웅크리고 누르면 선택만 취소
                selected.remove(p.getUUID());
                hilite = -1;
                sync();
                p.sendOverlayMessage(Component.translatable("yut.newyearcountdown.cancel").withStyle(ChatFormatting.GRAY));
                return;
            }
            pieces[sel] = idx;
            selected.remove(p.getUUID());
            hilite = -1;
            sw.playSound(null, worldPosition, SoundEvents.WOOD_PLACE, SoundSource.BLOCKS, 0.8f, 1.3f);
            sw.sendParticles(ParticleTypes.CRIT, worldPosition.getX() + 0.5 + (POINTS[idx][0] - 0.5) * 3, worldPosition.getY() + 0.25, worldPosition.getZ() + 0.5 + (POINTS[idx][1] - 0.5) * 3, 4, 0.05, 0.03, 0.05, 0.02);
            sync();
            p.sendOverlayMessage(Component.translatable("yut.newyearcountdown.moved", teamName(sel / 4)).withStyle(ChatFormatting.GREEN));
            return;
        }
        List<Integer> here = new ArrayList<>();
        for (int i = 0; i < pieces.length; i++) if (pieces[i] == idx) here.add(i);
        if (!here.isEmpty()) {
            int pick = here.get(here.size() - 1);
            for (int k = here.size() - 1; k >= 0; k--) {
                if (sameTeam(here.get(k), team)) {
                    pick = here.get(k);
                    break;
                }
            }
            if (p.isShiftKeyDown()) {                // 웅크리고 누르면 말을 판 밖으로 치운다(잡힘/도착)
                pieces[pick] = -1;
                sw.playSound(null, worldPosition, SoundEvents.WOOD_BREAK, SoundSource.BLOCKS, 0.6f, 1.4f);
                sync();
                p.sendOverlayMessage(Component.translatable("yut.newyearcountdown.removed", teamName(pick / 4)).withStyle(ChatFormatting.GRAY));
                return;
            }
            selected.put(p.getUUID(), pick);
            hilite = pick;
            sw.playSound(null, worldPosition, SoundEvents.WOOD_HIT, SoundSource.BLOCKS, 0.7f, 1.6f);
            sync();
            p.sendOverlayMessage(Component.translatable("yut.newyearcountdown.picked", teamName(pick / 4)).withStyle(ChatFormatting.YELLOW));
            return;
        }
        if (p.isShiftKeyDown()) return;
        for (int i = team * 4; i < team * 4 + 4; i++) {   // 빈 점: 우리 팀 새 말을 놓는다 (팀당 4개)
            if (pieces[i] == -1) {
                pieces[i] = idx;
                sw.playSound(null, worldPosition, SoundEvents.WOOD_PLACE, SoundSource.BLOCKS, 0.8f, 1.0f);
                sync();
                p.sendOverlayMessage(Component.translatable("yut.newyearcountdown.placed", teamName(team)).withStyle(ChatFormatting.GREEN));
                return;
            }
        }
        p.sendOverlayMessage(Component.translatable("yut.newyearcountdown.full", teamName(team)).withStyle(ChatFormatting.RED));
    }

    private void throwSticks(ServerLevel sw, ServerPlayer p) {
        if (sw.getGameTime() < busyUntil) {
            p.sendOverlayMessage(Component.translatable("yut.newyearcountdown.busy").withStyle(ChatFormatting.GRAY));
            return;
        }
        int mask = 0;
        for (int i = 0; i < 4; i++) if (sw.getRandom().nextBoolean()) mask |= 1 << i;
        int flat = Integer.bitCount(mask);
        sticks = mask;
        lastResult = flat == 0 ? 5 : flat;    // 평평한 면이 1개 도 / 2개 개 / 3개 걸 / 4개 윷 / 0개 모
        thrower = p.getUUID();
        animStart = sw.getGameTime();
        busyUntil = sw.getGameTime() + THROW_TICKS + 10;
        announceAt = sw.getGameTime() + THROW_TICKS;
        sw.blockEvent(worldPosition, getBlockState().getBlock(), 1, 0);
        sw.playSound(null, worldPosition, SoundEvents.ITEM_PICKUP, SoundSource.BLOCKS, 0.8f, 0.7f);
        sw.playSound(null, worldPosition, SoundEvents.BAMBOO_WOOD_PLACE, SoundSource.BLOCKS, 0.9f, 1.3f);
        sync();
    }

    public static void tick(Level world, BlockPos pos, BlockState state, YutBoardBlockEntity be) {
        if (!(world instanceof ServerLevel sw) || be.announceAt < 0) return;
        long now = sw.getGameTime();
        if (now == be.announceAt - 12) sw.playSound(null, pos, SoundEvents.WOOD_HIT, SoundSource.BLOCKS, 0.9f, 0.9f);
        if (now == be.announceAt - 6) sw.playSound(null, pos, SoundEvents.WOOD_HIT, SoundSource.BLOCKS, 0.7f, 1.2f);
        if (now >= be.announceAt) {
            be.announceAt = -1;
            be.announce(sw);
        }
    }

    private void announce(ServerLevel sw) {
        String[] keys = {"", "do", "gae", "geol", "yut", "mo"};
        int r = lastResult;
        boolean again = r >= 4;
        Component name = Component.translatable("yut.newyearcountdown.name." + keys[r]);
        ServerPlayer t = thrower == null ? null : sw.getServer().getPlayerList().getPlayer(thrower);
        Component who = t == null ? Component.literal("?") : t.getDisplayName();
        Component msg = Component.translatable("yut.newyearcountdown.result", who, name, r)
                .append(again ? Component.translatable("yut.newyearcountdown.again") : Component.empty())
                .withStyle(again ? ChatFormatting.GOLD : ChatFormatting.YELLOW);
        for (ServerPlayer o : sw.getPlayers(pl -> pl.distanceToSqr(worldPosition.getX() + 0.5, worldPosition.getY(), worldPosition.getZ() + 0.5) <= 24 * 24)) {
            o.sendSystemMessage(msg);
        }
        double cx = worldPosition.getX() + 0.5, cy = worldPosition.getY() + 0.25, cz = worldPosition.getZ() + 0.5;
        sw.sendParticles(again ? ParticleTypes.TOTEM_OF_UNDYING : ParticleTypes.HAPPY_VILLAGER, cx, cy + 0.2, cz + 1.2, again ? 16 : 6, 0.3, 0.1, 0.2, again ? 0.2 : 0.0);
        sw.playSound(null, worldPosition, again ? SoundEvents.PLAYER_LEVELUP : SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.BLOCKS, 0.9f, again ? 1.5f : 1.3f);
    }
}
