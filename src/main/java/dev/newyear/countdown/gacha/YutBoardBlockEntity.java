package dev.newyear.countdown.gacha;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.network.listener.ClientPlayPacketListener;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.s2c.play.BlockEntityUpdateS2CPacket;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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
    protected void writeNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup lookup) {
        super.writeNbt(nbt, lookup);
        nbt.putIntArray("pieces", pieces);
        nbt.putInt("sticks", sticks);
        nbt.putInt("result", lastResult);
        nbt.putInt("hilite", hilite);
    }

    @Override
    protected void readNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup lookup) {
        super.readNbt(nbt, lookup);
        int[] p = nbt.getIntArray("pieces");
        for (int i = 0; i < pieces.length; i++) pieces[i] = i < p.length ? p[i] : -1;
        sticks = nbt.getInt("sticks");
        lastResult = nbt.contains("result") ? nbt.getInt("result") : -1;
        hilite = nbt.contains("hilite") ? nbt.getInt("hilite") : -1;
    }

    @Nullable
    @Override
    public Packet<ClientPlayPacketListener> toUpdatePacket() {
        return BlockEntityUpdateS2CPacket.create(this);
    }

    @Override
    public NbtCompound toInitialChunkDataNbt(RegistryWrapper.WrapperLookup lookup) {
        return createNbt(lookup);
    }

    private void sync() {
        markDirty();
        if (world != null) world.updateListeners(pos, getCachedState(), getCachedState(), Block.NOTIFY_LISTENERS);
    }

    @Override
    public boolean onSyncedBlockEvent(int type, int data) {
        if (type == 1 && world != null) {
            animStart = world.getTime();
            return true;
        }
        return super.onSyncedBlockEvent(type, data);
    }

    // ------------------------------------------------------------------ 상호작용 (서버)

    /** lx, lz: 판 기준 클릭 지점(0~1, 앞쪽이 +z). */
    public void interact(ServerPlayerEntity p, double lx, double lz) {
        if (!(world instanceof ServerWorld sw)) return;
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
            if (selected.remove(p.getUuid()) != null) {   // 점 밖을 누르면 선택 취소
                hilite = -1;
                sync();
                p.sendMessage(Text.translatable("yut.newyearcountdown.cancel").formatted(Formatting.GRAY), true);
            } else {
                p.sendMessage(Text.translatable("yut.newyearcountdown.hint").formatted(Formatting.GRAY), true);
            }
            return;
        }
        pointAction(sw, p, best);
    }

    private int teamOf(ServerPlayerEntity p) {
        return teams.computeIfAbsent(p.getUuid(), k -> teams.size() % 2);
    }

    private static boolean sameTeam(int piece, int team) {
        return piece / 4 == team;
    }

    private static Text teamName(int team) {
        return Text.translatable(team == 0 ? "yut.newyearcountdown.team.red" : "yut.newyearcountdown.team.blue")
                .formatted(team == 0 ? Formatting.RED : Formatting.BLUE);
    }

    private void pointAction(ServerWorld sw, ServerPlayerEntity p, int idx) {
        int team = teamOf(p);
        Integer sel = selected.get(p.getUuid());
        if (sel != null) {                       // 집은 말을 여기로 옮긴다
            if (p.isSneaking()) {                // 웅크리고 누르면 선택만 취소
                selected.remove(p.getUuid());
                hilite = -1;
                sync();
                p.sendMessage(Text.translatable("yut.newyearcountdown.cancel").formatted(Formatting.GRAY), true);
                return;
            }
            pieces[sel] = idx;
            selected.remove(p.getUuid());
            hilite = -1;
            sw.playSound(null, pos, SoundEvents.BLOCK_WOOD_PLACE, SoundCategory.BLOCKS, 0.8f, 1.3f);
            sw.spawnParticles(ParticleTypes.CRIT, pos.getX() + 0.5 + (POINTS[idx][0] - 0.5) * 3, pos.getY() + 0.25, pos.getZ() + 0.5 + (POINTS[idx][1] - 0.5) * 3, 4, 0.05, 0.03, 0.05, 0.02);
            sync();
            p.sendMessage(Text.translatable("yut.newyearcountdown.moved", teamName(sel / 4)).formatted(Formatting.GREEN), true);
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
            if (p.isSneaking()) {                // 웅크리고 누르면 말을 판 밖으로 치운다(잡힘/도착)
                pieces[pick] = -1;
                sw.playSound(null, pos, SoundEvents.BLOCK_WOOD_BREAK, SoundCategory.BLOCKS, 0.6f, 1.4f);
                sync();
                p.sendMessage(Text.translatable("yut.newyearcountdown.removed", teamName(pick / 4)).formatted(Formatting.GRAY), true);
                return;
            }
            selected.put(p.getUuid(), pick);
            hilite = pick;
            sw.playSound(null, pos, SoundEvents.BLOCK_WOOD_HIT, SoundCategory.BLOCKS, 0.7f, 1.6f);
            sync();
            p.sendMessage(Text.translatable("yut.newyearcountdown.picked", teamName(pick / 4)).formatted(Formatting.YELLOW), true);
            return;
        }
        if (p.isSneaking()) return;
        for (int i = team * 4; i < team * 4 + 4; i++) {   // 빈 점: 우리 팀 새 말을 놓는다 (팀당 4개)
            if (pieces[i] == -1) {
                pieces[i] = idx;
                sw.playSound(null, pos, SoundEvents.BLOCK_WOOD_PLACE, SoundCategory.BLOCKS, 0.8f, 1.0f);
                sync();
                p.sendMessage(Text.translatable("yut.newyearcountdown.placed", teamName(team)).formatted(Formatting.GREEN), true);
                return;
            }
        }
        p.sendMessage(Text.translatable("yut.newyearcountdown.full", teamName(team)).formatted(Formatting.RED), true);
    }

    private void throwSticks(ServerWorld sw, ServerPlayerEntity p) {
        if (sw.getTime() < busyUntil) {
            p.sendMessage(Text.translatable("yut.newyearcountdown.busy").formatted(Formatting.GRAY), true);
            return;
        }
        int mask = 0;
        for (int i = 0; i < 4; i++) if (sw.random.nextBoolean()) mask |= 1 << i;
        int flat = Integer.bitCount(mask);
        sticks = mask;
        lastResult = flat == 0 ? 5 : flat;    // 평평한 면이 1개 도 / 2개 개 / 3개 걸 / 4개 윷 / 0개 모
        thrower = p.getUuid();
        animStart = sw.getTime();
        busyUntil = sw.getTime() + THROW_TICKS + 10;
        announceAt = sw.getTime() + THROW_TICKS;
        sw.addSyncedBlockEvent(pos, getCachedState().getBlock(), 1, 0);
        sw.playSound(null, pos, SoundEvents.ENTITY_ITEM_PICKUP, SoundCategory.BLOCKS, 0.8f, 0.7f);
        sw.playSound(null, pos, SoundEvents.BLOCK_BAMBOO_WOOD_PLACE, SoundCategory.BLOCKS, 0.9f, 1.3f);
        sync();
    }

    public static void tick(World world, BlockPos pos, BlockState state, YutBoardBlockEntity be) {
        if (!(world instanceof ServerWorld sw) || be.announceAt < 0) return;
        long now = sw.getTime();
        if (now == be.announceAt - 12) sw.playSound(null, pos, SoundEvents.BLOCK_WOOD_HIT, SoundCategory.BLOCKS, 0.9f, 0.9f);
        if (now == be.announceAt - 6) sw.playSound(null, pos, SoundEvents.BLOCK_WOOD_HIT, SoundCategory.BLOCKS, 0.7f, 1.2f);
        if (now >= be.announceAt) {
            be.announceAt = -1;
            be.announce(sw);
        }
    }

    private void announce(ServerWorld sw) {
        String[] keys = {"", "do", "gae", "geol", "yut", "mo"};
        int r = lastResult;
        boolean again = r >= 4;
        Text name = Text.translatable("yut.newyearcountdown.name." + keys[r]);
        ServerPlayerEntity t = thrower == null ? null : sw.getServer().getPlayerManager().getPlayer(thrower);
        Text who = t == null ? Text.literal("?") : t.getDisplayName();
        Text msg = Text.translatable("yut.newyearcountdown.result", who, name, r)
                .append(again ? Text.translatable("yut.newyearcountdown.again") : Text.empty())
                .formatted(again ? Formatting.GOLD : Formatting.YELLOW);
        for (ServerPlayerEntity o : sw.getPlayers(pl -> pl.squaredDistanceTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5) <= 24 * 24)) {
            o.sendMessage(msg, false);
        }
        double cx = pos.getX() + 0.5, cy = pos.getY() + 0.25, cz = pos.getZ() + 0.5;
        sw.spawnParticles(again ? ParticleTypes.TOTEM_OF_UNDYING : ParticleTypes.HAPPY_VILLAGER, cx, cy + 0.2, cz + 1.2, again ? 16 : 6, 0.3, 0.1, 0.2, again ? 0.2 : 0.0);
        sw.playSound(null, pos, again ? SoundEvents.ENTITY_PLAYER_LEVELUP : SoundEvents.BLOCK_AMETHYST_BLOCK_CHIME, SoundCategory.BLOCKS, 0.9f, again ? 1.5f : 1.3f);
    }
}
