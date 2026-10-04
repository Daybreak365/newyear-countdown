package dev.newyear.countdown.gacha;

import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
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
 * 윷판 상태와 규칙. 말 8개(빨강 0~3, 파랑 4~7)의 위치, 차례, 남은 이동(던진 결과), 마지막으로 던진 윷가락 4개.
 * 규칙은 자동: 이동 경로·지름길(모서리·방에서 꺾기)·업기·잡기(한 번 더)·윷/모(한 번 더)·참먹이를 지나면 골인.
 * 던지기 연출은 클라이언트(YutBoardRenderer)가 animStart 부터 시간으로 그린다.
 *
 * 점 번호: 0~19 바깥 둘레(0 = 출발/도착 모서리, 5·10·15 모서리), 20·21 = 0번에서 방 쪽, 22·23 = 5번에서 방 쪽,
 * 24·25 = 10번에서 방 쪽, 26·27 = 15번에서 방 쪽, 28 = 방(가운데).
 */
public class YutBoardBlockEntity extends BlockEntity {
    public static final int THROW_TICKS = 20;
    /** 판(3x3 전체를 0~1) 기준 좌표에서 이 값보다 앞(+z)이면 던지는 자리(붉은 양털). */
    public static final double THROW_Z = 34.0 / 48.0;
    /** 판 양옆의 대기 자리: 왼쪽 빨강, 오른쪽 파랑. */
    public static final double SIDE_L = 8.0 / 48.0, SIDE_R = 40.0 / 48.0;
    public static final int HOME = -1, DONE = -2, FIN = -2;
    /** 선택: 말 번호 0~7, 또는 SEL_HOME(대기 중인 새 말). */
    public static final int SEL_NONE = -1, SEL_HOME = 8;

    /** 29점의 좌표 {lx, lz} (3x3 전체를 0~1). tools/modelgen/yut_board.py 의 points() 와 같은 공식. */
    public static final float[][] POINTS = buildPoints();

    public final int[] pieces = {HOME, HOME, HOME, HOME, HOME, HOME, HOME, HOME};   // 점 번호, HOME 대기, DONE 골인
    public int sticks;                // 비트 i = 1 이면 i번째 가락의 평평한 면이 위
    public int lastResult = -1;       // 1 도, 2 개, 3 걸, 4 윷, 5 모
    public int turn;                  // 0 빨강, 1 파랑
    public boolean canThrow = true;   // 지금 던질 수 있는지 (아니면 남은 이동을 써야 함)
    public final List<Integer> pending = new ArrayList<>();   // 남은 이동(칸 수)
    public int selected = SEL_NONE;
    public int winner = -1;
    public long animStart = Long.MIN_VALUE / 2;

    private long busyUntil;
    private long announceAt = -1;
    private long resetArmedUntil;
    private UUID thrower;

    public YutBoardBlockEntity(BlockPos pos, BlockState state) {
        super(Souvenirs.YUT_BE, pos, state);
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

    // ------------------------------------------------------------------ 규칙 (서버·클라이언트 공용)

    private static final int[] ROUTE_A = {5, 22, 23, 28, 27, 26, 15, 16, 17, 18, 19, 0};   // 첫 모서리에서 꺾기
    private static final int[] ROUTE_B = {10, 24, 25, 28, 21, 20, 0};                      // 둘째 모서리에서 꺾기
    private static final int[] ROUTE_C = {28, 21, 20, 0};                                  // 방에서 멈췄을 때

    /** pos 에서 출발할 때 지나는 점들 (pos 제외, 마지막 다음은 골인). */
    public static List<Integer> pathFrom(int pos) {
        List<Integer> out = new ArrayList<>();
        int[] route = null;
        int at = -1;
        if (pos == 28) { route = ROUTE_C; at = 0; }
        else if (pos == 5 || pos == 22 || pos == 23 || pos == 27 || pos == 26) { route = ROUTE_A; at = indexOf(ROUTE_A, pos); }
        else if (pos == 10 || pos == 24 || pos == 25 || pos == 21 || pos == 20) { route = ROUTE_B; at = indexOf(ROUTE_B, pos); }
        if (route != null) {
            for (int i = at + 1; i < route.length; i++) out.add(route[i]);
        } else if (pos == HOME) {
            for (int i = 1; i <= 19; i++) out.add(i);
            out.add(0);
        } else if (pos >= 1 && pos <= 19) {
            for (int i = pos + 1; i <= 19; i++) out.add(i);
            out.add(0);
        }
        // pos == 0 (한 바퀴 돌아 참먹이에 선 말): 다음 이동이면 바로 골인
        return out;
    }

    private static int indexOf(int[] a, int v) {
        for (int i = 0; i < a.length; i++) if (a[i] == v) return i;
        return -1;
    }

    /** pos 에서 steps 칸 갔을 때의 점 (FIN = 골인). */
    public static int destination(int pos, int steps) {
        List<Integer> path = pathFrom(pos);
        return steps <= path.size() ? path.get(steps - 1) : FIN;
    }

    /** 선택(말 번호 또는 SEL_HOME)의 현재 위치. */
    public int selectionPos(int sel) {
        return sel == SEL_HOME ? HOME : (sel >= 0 && sel < 8 ? pieces[sel] : DONE);
    }

    /** 남은 이동 중 서로 다른 칸 수 (작은 것부터). */
    public List<Integer> distinctMoves() {
        List<Integer> d = new ArrayList<>();
        for (int v : pending) if (!d.contains(v)) d.add(v);
        d.sort(Integer::compare);
        return d;
    }

    public int count(int team, int where) {
        int n = 0;
        for (int i = team * 4; i < team * 4 + 4; i++) if (pieces[i] == where) n++;
        return n;
    }

    // ------------------------------------------------------------------ 저장 / 동기화

    @Override
    protected void saveAdditional(ValueOutput nbt) {
        super.saveAdditional(nbt);
        nbt.putIntArray("pieces", pieces);
        nbt.putInt("sticks", sticks);
        nbt.putInt("result", lastResult);
        nbt.putInt("turn", turn);
        nbt.putBoolean("canThrow", canThrow);
        nbt.putIntArray("pending", pending.stream().mapToInt(Integer::intValue).toArray());
        nbt.putInt("selected", selected);
        nbt.putInt("winner", winner);
    }

    @Override
    protected void loadAdditional(ValueInput nbt) {
        super.loadAdditional(nbt);
        int[] p = nbt.getIntArray("pieces").orElse(new int[0]);
        for (int i = 0; i < pieces.length; i++) pieces[i] = i < p.length ? p[i] : HOME;
        sticks = nbt.getIntOr("sticks", 0);
        lastResult = nbt.getIntOr("result", -1);
        turn = nbt.getIntOr("turn", 0);
        canThrow = nbt.getBooleanOr("canThrow", true);
        pending.clear();
        for (int v : nbt.getIntArray("pending").orElse(new int[0])) pending.add(v);
        selected = nbt.getIntOr("selected", SEL_NONE);
        winner = nbt.getIntOr("winner", -1);
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
            if (p.isShiftKeyDown()) requestReset(sw, p);
            else throwSticks(sw, p);
            return;
        }
        if (winner >= 0) {
            p.sendOverlayMessage(Component.translatable("yut.newyearcountdown.over").withStyle(ChatFormatting.GRAY));
            return;
        }
        if (p.isShiftKeyDown()) {                    // 웅크리고 누르면 선택 취소
            clearSelection(p);
            return;
        }
        if (lx < SIDE_L || lx > SIDE_R) {            // 판 옆 대기 자리
            int side = lx < SIDE_L ? 0 : 1;
            if (side != turn) {
                p.sendOverlayMessage(Component.translatable("yut.newyearcountdown.not_turn", teamName(turn)).withStyle(ChatFormatting.GRAY));
                return;
            }
            // 이미 고른 말이 골인할 수 있으면 대기 자리를 눌러 골인
            if (selected != SEL_NONE && selected != SEL_HOME && tryMoveTo(sw, p, FIN)) return;
            if (count(turn, HOME) == 0) {
                p.sendOverlayMessage(Component.translatable("yut.newyearcountdown.no_home").withStyle(ChatFormatting.GRAY));
                return;
            }
            choose(sw, p, SEL_HOME);
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
            clearSelection(p);
            return;
        }
        // 고른 말의 도착 후보를 누르면 그 칸으로 간다
        if (selected != SEL_NONE && tryMoveTo(sw, p, best)) return;
        int mine = topPieceAt(best, turn);
        if (mine >= 0) {
            choose(sw, p, mine);
            return;
        }
        p.sendOverlayMessage(Component.translatable(pending.isEmpty() ? "yut.newyearcountdown.throw_first" : "yut.newyearcountdown.pick_piece",
                teamName(turn)).withStyle(ChatFormatting.GRAY));
    }

    private int topPieceAt(int point, int team) {
        for (int i = team * 4 + 3; i >= team * 4; i--) if (pieces[i] == point) return i;
        return -1;
    }

    private void clearSelection(ServerPlayer p) {
        if (selected != SEL_NONE) {
            selected = SEL_NONE;
            sync();
        }
        p.sendOverlayMessage(Component.translatable("yut.newyearcountdown.hint").withStyle(ChatFormatting.GRAY));
    }

    /** 말(또는 새 말)을 고른다. 갈 수 있는 칸이 하나뿐이면 바로 옮긴다. */
    private void choose(ServerLevel sw, ServerPlayer p, int sel) {
        if (pending.isEmpty()) {
            p.sendOverlayMessage(Component.translatable("yut.newyearcountdown.throw_first", teamName(turn)).withStyle(ChatFormatting.GRAY));
            return;
        }
        List<Integer> moves = distinctMoves();
        if (moves.size() == 1) {
            move(sw, p, sel, moves.get(0));
            return;
        }
        selected = sel;
        sw.playSound(null, worldPosition, SoundEvents.WOOD_HIT, SoundSource.BLOCKS, 0.7f, 1.6f);
        sync();
        p.sendOverlayMessage(Component.translatable("yut.newyearcountdown.choose_dest").withStyle(ChatFormatting.YELLOW));
    }

    /** 고른 말을 target 으로 보내는 남은 이동이 있으면 쓴다. */
    private boolean tryMoveTo(ServerLevel sw, ServerPlayer p, int target) {
        int from = selectionPos(selected);
        for (int steps : distinctMoves()) {
            if (destination(from, steps) == target) {
                move(sw, p, selected, steps);
                return true;
            }
        }
        return false;
    }

    private void move(ServerLevel sw, ServerPlayer p, int sel, int steps) {
        int from = selectionPos(sel);
        int to = destination(from, steps);
        pending.remove(Integer.valueOf(steps));
        selected = SEL_NONE;
        // 함께 움직이는 말: 새 말이면 하나, 판 위의 말이면 같은 점의 우리 말 전부(업기)
        List<Integer> group = new ArrayList<>();
        if (sel == SEL_HOME) {
            for (int i = turn * 4; i < turn * 4 + 4; i++) if (pieces[i] == HOME) { group.add(i); break; }
        } else {
            for (int i = turn * 4; i < turn * 4 + 4; i++) if (pieces[i] == from) group.add(i);
        }
        boolean captured = false;
        if (to != FIN) {
            int other = 1 - turn;
            for (int i = other * 4; i < other * 4 + 4; i++) {
                if (pieces[i] == to) {
                    pieces[i] = HOME;
                    captured = true;
                }
            }
        }
        for (int i : group) pieces[i] = to;
        double[] at = to == FIN ? worldXZ(turn == 0 ? 0.08 : 0.92, 0.4) : worldXZ(POINTS[to][0], POINTS[to][1]);
        sw.playSound(null, worldPosition, SoundEvents.WOOD_PLACE, SoundSource.BLOCKS, 0.8f, 1.2f);
        sw.sendParticles(ParticleTypes.CRIT, at[0], worldPosition.getY() + 0.25, at[1], 5, 0.05, 0.03, 0.05, 0.02);
        Component name = Component.translatable("yut.newyearcountdown.name." + KEYS[Math.min(steps, 5)]);
        if (captured) {
            canThrow = true;                     // 잡으면 한 번 더
            sw.playSound(null, worldPosition, SoundEvents.PLAYER_ATTACK_STRONG, SoundSource.BLOCKS, 0.8f, 1.3f);
            broadcast(sw, Component.translatable("yut.newyearcountdown.captured", teamName(turn)).withStyle(ChatFormatting.GOLD));
        } else if (to == FIN) {
            sw.playSound(null, worldPosition, SoundEvents.NOTE_BLOCK_CHIME.value(), SoundSource.BLOCKS, 0.9f, 1.4f);
            p.sendOverlayMessage(Component.translatable("yut.newyearcountdown.finished", teamName(turn), group.size()).withStyle(ChatFormatting.GREEN));
        } else {
            p.sendOverlayMessage(Component.translatable("yut.newyearcountdown.moved", teamName(turn), name, group.size()).withStyle(ChatFormatting.GREEN));
        }
        if (count(turn, DONE) == 4) {
            winner = turn;
            pending.clear();
            canThrow = false;
            broadcast(sw, Component.translatable("yut.newyearcountdown.win", teamName(turn)).withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
            sw.sendParticles(ParticleTypes.TOTEM_OF_UNDYING, worldPosition.getX() + 0.5, worldPosition.getY() + 0.6, worldPosition.getZ() + 0.5, 60, 0.8, 0.4, 0.8, 0.3);
            sw.playSound(null, worldPosition, SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, SoundSource.BLOCKS, 1.0f, 1.0f);
        } else {
            endTurnIfDone(sw);
        }
        sync();
    }

    /** 남은 이동도 없고 더 던질 수도 없으면 상대 차례. */
    private void endTurnIfDone(ServerLevel sw) {
        if (pending.isEmpty() && !canThrow) {
            turn = 1 - turn;
            canThrow = true;
            broadcast(sw, Component.translatable("yut.newyearcountdown.turn", teamName(turn)).withStyle(turn == 0 ? ChatFormatting.RED : ChatFormatting.BLUE));
        }
    }

    private double[] worldXZ(double lx, double lz) {
        float yaw = getBlockState().getValue(YutBoardBlock.FACING).toYRot() * Mth.DEG_TO_RAD;
        double x = (lx - 0.5) * 3, z = (lz - 0.5) * 3;
        double c = Math.cos(yaw), s = Math.sin(yaw);
        return new double[]{worldPosition.getX() + 0.5 + x * c - z * s, worldPosition.getZ() + 0.5 + x * s + z * c};
    }

    private void broadcast(ServerLevel sw, Component msg) {
        for (ServerPlayer o : sw.getPlayers(pl -> pl.distanceToSqr(worldPosition.getX() + 0.5, worldPosition.getY(), worldPosition.getZ() + 0.5) <= 24 * 24)) {
            o.sendSystemMessage(msg);
        }
    }

    private static Component teamName(int team) {
        return Component.translatable(team == 0 ? "yut.newyearcountdown.team.red" : "yut.newyearcountdown.team.blue")
                .withStyle(team == 0 ? ChatFormatting.RED : ChatFormatting.BLUE);
    }

    /** 웅크리고 던지는 자리를 두 번(3초 안에) 누르면 새 판. */
    private void requestReset(ServerLevel sw, ServerPlayer p) {
        if (sw.getGameTime() > resetArmedUntil) {
            resetArmedUntil = sw.getGameTime() + 60;
            p.sendOverlayMessage(Component.translatable("yut.newyearcountdown.reset_confirm").withStyle(ChatFormatting.YELLOW));
            return;
        }
        resetArmedUntil = 0;
        Arrays.fill(pieces, HOME);
        pending.clear();
        turn = 0;
        canThrow = true;
        selected = SEL_NONE;
        winner = -1;
        lastResult = -1;
        sw.playSound(null, worldPosition, SoundEvents.WOOD_BREAK, SoundSource.BLOCKS, 0.8f, 1.2f);
        broadcast(sw, Component.translatable("yut.newyearcountdown.reset").withStyle(ChatFormatting.YELLOW));
        sync();
    }

    private void throwSticks(ServerLevel sw, ServerPlayer p) {
        if (winner >= 0) {                       // 끝난 판은 던지면 새 판
            resetArmedUntil = sw.getGameTime() + 60;
            requestReset(sw, p);
            return;
        }
        if (sw.getGameTime() < busyUntil) {
            p.sendOverlayMessage(Component.translatable("yut.newyearcountdown.busy").withStyle(ChatFormatting.GRAY));
            return;
        }
        if (!canThrow) {
            p.sendOverlayMessage(Component.translatable("yut.newyearcountdown.move_first", teamName(turn)).withStyle(ChatFormatting.GRAY));
            return;
        }
        int mask = 0;
        for (int i = 0; i < 4; i++) if (sw.getRandom().nextBoolean()) mask |= 1 << i;
        int flat = Integer.bitCount(mask);
        sticks = mask;
        lastResult = flat == 0 ? 5 : flat;    // 평평한 면이 1개 도 / 2개 개 / 3개 걸 / 4개 윷 / 0개 모
        canThrow = false;                     // 결과가 나오면(윷·모) 다시 열린다
        selected = SEL_NONE;
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

    private static final String[] KEYS = {"", "do", "gae", "geol", "yut", "mo"};

    private void announce(ServerLevel sw) {
        int r = lastResult;
        boolean again = r >= 4;
        pending.add(r);
        if (again) canThrow = true;
        Component name = Component.translatable("yut.newyearcountdown.name." + KEYS[r]);
        ServerPlayer t = thrower == null ? null : sw.getServer().getPlayerList().getPlayer(thrower);
        Component who = t == null ? teamName(turn) : t.getDisplayName();
        broadcast(sw, Component.translatable("yut.newyearcountdown.result", who, name, r)
                .append(again ? Component.translatable("yut.newyearcountdown.again") : Component.empty())
                .withStyle(again ? ChatFormatting.GOLD : ChatFormatting.YELLOW));
        double cx = worldPosition.getX() + 0.5, cy = worldPosition.getY() + 0.25, cz = worldPosition.getZ() + 0.5;
        sw.sendParticles(again ? ParticleTypes.TOTEM_OF_UNDYING : ParticleTypes.HAPPY_VILLAGER, cx, cy + 0.2, cz, again ? 16 : 6, 0.3, 0.1, 0.3, again ? 0.2 : 0.0);
        sw.playSound(null, worldPosition, again ? SoundEvents.PLAYER_LEVELUP : SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.BLOCKS, 0.9f, again ? 1.5f : 1.3f);
        sync();
    }
}
