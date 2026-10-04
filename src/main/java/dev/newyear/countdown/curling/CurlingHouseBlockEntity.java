package dev.newyear.countdown.curling;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * 컬링 하우스와 경기 진행. 4엔드, 엔드마다 팀당 스톤 4개를 번갈아 던진다.
 * 엔드가 끝나면 가운데(버튼)에 가장 가까운 팀이, 상대의 가장 가까운 스톤보다 안쪽에 있는 자기 스톤 수만큼 득점.
 * 득점한 팀이 다음 엔드를 먼저 던진다. 판 위에 엔드·점수·차례·남은 스톤이 떠 있다.
 */
public class CurlingHouseBlockEntity extends BlockEntity {
    /** 하우스 반지름(칸). 렌더러·텍스처와 같은 값. */
    public static final float RADIUS = 2.5f;
    public static final int ENDS = 4, STONES = 4;
    /** 서버에 로드된 하우스 (던질 때 가까운 하우스를 찾는다). */
    private static final Set<CurlingHouseBlockEntity> LOADED = Collections.newSetFromMap(new WeakHashMap<>());

    public int end = 1;
    public final int[] score = new int[2];
    public final int[] thrown = new int[2];
    public int turn;
    public int winner = -1;          // 경기 끝: 이긴 팀 (2 = 무승부)

    private boolean active;
    private int lastStone = -1;
    private final Set<Integer> inHouseAtThrow = new HashSet<>();
    private long clearAt = -1;

    public CurlingHouseBlockEntity(BlockPos pos, BlockState state) {
        super(Curling.HOUSE_BE, pos, state);
    }

    @Override
    public void setLevel(Level level) {
        super.setLevel(level);
        if (!level.isClientSide()) LOADED.add(this);
    }

    @Override
    public void setRemoved() {
        super.setRemoved();
        LOADED.remove(this);
    }

    private double cx() { return worldPosition.getX() + 0.5; }
    private double cz() { return worldPosition.getZ() + 0.5; }

    /** 던지는 쪽에서 바라보는 방향으로 64칸 안에 있는 가장 가까운 하우스. */
    public static @Nullable CurlingHouseBlockEntity find(ServerLevel level, Vec3 from, Vec3 dir) {
        CurlingHouseBlockEntity best = null;
        double bestD = Double.MAX_VALUE;
        for (CurlingHouseBlockEntity h : LOADED) {
            if (h.isRemoved() || h.level != level) continue;
            Vec3 to = new Vec3(h.cx() - from.x, 0, h.cz() - from.z);
            double d = to.length();
            if (d > 64 || d < 3 || to.normalize().dot(dir) < 0.85) continue;
            if (d < bestD) { bestD = d; best = h; }
        }
        return best;
    }

    // ------------------------------------------------------------------ 동기화

    @Override
    protected void saveAdditional(ValueOutput out) {
        super.saveAdditional(out);
        out.putInt("end", end);
        out.putIntArray("score", score);
        out.putIntArray("thrown", thrown);
        out.putInt("turn", turn);
        out.putInt("winner", winner);
    }

    @Override
    protected void loadAdditional(ValueInput in) {
        super.loadAdditional(in);
        end = in.getIntOr("end", 1);
        int[] s = in.getIntArray("score").orElse(new int[2]);
        int[] t = in.getIntArray("thrown").orElse(new int[2]);
        for (int i = 0; i < 2; i++) {
            score[i] = i < s.length ? s[i] : 0;
            thrown[i] = i < t.length ? t[i] : 0;
        }
        turn = in.getIntOr("turn", 0);
        winner = in.getIntOr("winner", -1);
    }

    @Override
    public @Nullable Packet<ClientGamePacketListener> getUpdatePacket() {
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

    // ------------------------------------------------------------------ 경기

    private List<CurlingStoneEntity> stones(ServerLevel sw, double range) {
        return sw.getEntitiesOfClass(CurlingStoneEntity.class, new AABB(worldPosition).inflate(range, 3, range), e -> true);
    }

    private double dist(CurlingStoneEntity s) {
        double dx = s.getX() - cx(), dz = s.getZ() - cz();
        return Math.sqrt(dx * dx + dz * dz);
    }

    private boolean inHouse(CurlingStoneEntity s) {
        return dist(s) <= RADIUS + CurlingStoneEntity.RADIUS;
    }

    /** 스톤을 던지려 한다: 차례가 아니면 거절(메시지)하고 false. */
    public boolean tryThrow(ServerPlayer p, int team) {
        if (!(level instanceof ServerLevel sw)) return true;
        if (winner >= 0) resetGame(sw);                 // 끝난 경기는 다음 투구로 새 경기
        if (clearAt >= 0) {
            p.sendOverlayMessage(Component.translatable("curling.newyearcountdown.wait").withStyle(ChatFormatting.GRAY));
            return false;
        }
        if (team != turn) {
            p.sendOverlayMessage(Component.translatable("curling.newyearcountdown.not_turn", teamName(turn)).withStyle(ChatFormatting.RED));
            return false;
        }
        if (thrown[team] >= STONES) {
            p.sendOverlayMessage(Component.translatable("curling.newyearcountdown.no_stones", teamName(team)).withStyle(ChatFormatting.RED));
            return false;
        }
        return true;
    }

    /** 던진 스톤을 경기에 등록한다. */
    public void onThrown(CurlingStoneEntity stone, int team) {
        if (!(level instanceof ServerLevel sw)) return;
        thrown[team]++;
        turn = 1 - team;
        lastStone = stone.getId();
        inHouseAtThrow.clear();
        for (CurlingStoneEntity s : stones(sw, RADIUS + 1)) if (inHouse(s)) inHouseAtThrow.add(s.getId());
        active = true;
        sync();
    }

    public static void tick(Level level, BlockPos pos, BlockState state, CurlingHouseBlockEntity be) {
        if (!(level instanceof ServerLevel sw)) return;
        long now = sw.getGameTime();
        if (be.clearAt >= 0 && now >= be.clearAt) {
            be.clearAt = -1;
            be.nextEnd(sw);
            return;
        }
        if (now % 4 != 0 || !be.active) return;
        List<CurlingStoneEntity> all = be.stones(sw, 64);
        if (all.stream().anyMatch(CurlingStoneEntity::isMoving)) return;
        be.active = false;
        be.settled(sw, all);
    }

    /** 던진 스톤이 모두 멈췄다: 결과 알림, 엔드가 끝났으면 점수 계산. */
    private void settled(ServerLevel sw, List<CurlingStoneEntity> all) {
        CurlingStoneEntity last = null;
        int knocked = 0;
        for (CurlingStoneEntity s : all) {
            if (s.getId() == lastStone) last = s;
            if (inHouseAtThrow.contains(s.getId()) && !inHouse(s)) knocked++;
        }
        for (int id : inHouseAtThrow) if (all.stream().noneMatch(s -> s.getId() == id)) knocked++;   // 시트 밖으로 사라짐
        Component msg;
        if (knocked > 0) {
            msg = Component.translatable("curling.newyearcountdown.takeout", knocked).withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD);
            sw.playSound(null, worldPosition, SoundEvents.FIREWORK_ROCKET_BLAST, SoundSource.BLOCKS, 0.8f, 1.3f);
        } else if (last != null && dist(last) < 0.35) {
            msg = Component.translatable("curling.newyearcountdown.button").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD);
            sw.sendParticles(ParticleTypes.TOTEM_OF_UNDYING, last.getX(), last.getY() + 0.3, last.getZ(), 24, 0.2, 0.2, 0.2, 0.2);
            sw.playSound(null, worldPosition, SoundEvents.PLAYER_LEVELUP, SoundSource.BLOCKS, 0.8f, 1.6f);
        } else if (last != null && inHouse(last)) {
            msg = Component.translatable("curling.newyearcountdown.distance", String.format("%.1f", dist(last))).withStyle(ChatFormatting.AQUA);
            sw.playSound(null, worldPosition, SoundEvents.NOTE_BLOCK_CHIME.value(), SoundSource.BLOCKS, 0.7f, 1.2f);
        } else {
            msg = Component.translatable("curling.newyearcountdown.miss").withStyle(ChatFormatting.GRAY);
        }
        int[] sc = score(sw);
        Component standing = sc[0] < 0 ? Component.translatable("curling.newyearcountdown.score.none")
                : Component.translatable("curling.newyearcountdown.score", teamName(sc[0]), sc[1]);
        Component line = msg.copy().append(Component.literal("  ")).append(standing.copy().withStyle(ChatFormatting.WHITE));
        for (ServerPlayer p : near(sw)) p.sendOverlayMessage(line);

        if (thrown[0] >= STONES && thrown[1] >= STONES) endFinished(sw, sc);
        sync();
    }

    private void endFinished(ServerLevel sw, int[] sc) {
        Component title;
        if (sc[0] < 0) {
            title = Component.translatable("curling.newyearcountdown.end.blank", end);
        } else {
            score[sc[0]] += sc[1];
            turn = sc[0];                               // 득점한 팀이 다음 엔드를 먼저
            title = Component.translatable("curling.newyearcountdown.end.score", end, teamName(sc[0]), sc[1]);
            int rgb = sc[0] == 0 ? 0xE02020 : 0xF0C020;
            for (int i = 0; i < 6; i++) {
                sw.sendParticles(new DustParticleOptions(rgb, 1.6f), cx(), worldPosition.getY() + 1.2 + i * 0.3, cz(), 12, 0.8, 0.3, 0.8, 0.05);
            }
            sw.sendParticles(ParticleTypes.FIREWORK, cx(), worldPosition.getY() + 2, cz(), 40, 0.6, 0.6, 0.6, 0.15);
            sw.playSound(null, worldPosition, SoundEvents.FIREWORK_ROCKET_TWINKLE, SoundSource.BLOCKS, 1.0f, 1.0f);
        }
        Component sub = Component.translatable("curling.newyearcountdown.total", teamName(0), score[0], score[1], teamName(1));
        boolean over = end >= ENDS;
        if (over) {
            winner = score[0] == score[1] ? 2 : (score[0] > score[1] ? 0 : 1);
            title = winner == 2 ? Component.translatable("curling.newyearcountdown.draw")
                    : Component.translatable("curling.newyearcountdown.win", teamName(winner));
            sw.playSound(null, worldPosition, SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, SoundSource.BLOCKS, 1.0f, 1.0f);
            sw.sendParticles(ParticleTypes.TOTEM_OF_UNDYING, cx(), worldPosition.getY() + 1, cz(), 80, 1.2, 0.6, 1.2, 0.4);
        }
        for (ServerPlayer p : near(sw)) {
            p.connection.send(new ClientboundSetTitlesAnimationPacket(5, 50, 15));
            p.connection.send(new ClientboundSetSubtitleTextPacket(sub));
            p.connection.send(new ClientboundSetTitleTextPacket(title));
            p.sendSystemMessage(title.copy().append(Component.literal("  ")).append(sub));
        }
        clearAt = sw.getGameTime() + 80;                // 4초 뒤 스톤을 거두고 다음 엔드
    }

    /** 스톤을 던진 사람에게 돌려주고 다음 엔드로. */
    private void nextEnd(ServerLevel sw) {
        returnStones(sw, 64);
        thrown[0] = thrown[1] = 0;
        if (winner < 0) end++;
        sync();
    }

    private void returnStones(ServerLevel sw, double range) {
        for (CurlingStoneEntity s : stones(sw, range)) {
            UUID t = s.thrower();
            ServerPlayer p = t == null ? null : sw.getServer().getPlayerList().getPlayer(t);
            if (p != null && !p.getAbilities().instabuild) {
                p.getInventory().placeItemBackInInventory(new ItemStack(s.team() == 0 ? Curling.STONE_RED : Curling.STONE_YELLOW),
                        net.minecraft.util.Prediction.SERVER_ONLY);
            }
            s.discard();
        }
    }

    private void resetGame(ServerLevel sw) {
        end = 1;
        score[0] = score[1] = 0;
        thrown[0] = thrown[1] = 0;
        turn = 0;
        winner = -1;
        clearAt = -1;
        active = false;
        sync();
    }

    private List<ServerPlayer> near(ServerLevel sw) {
        return sw.getPlayers(p -> p.distanceToSqr(cx(), worldPosition.getY(), cz()) < 64 * 64);
    }

    /** {팀, 점수} (팀 -1 = 하우스 안에 스톤 없음). */
    public int[] score(ServerLevel sw) {
        List<CurlingStoneEntity> in = new ArrayList<>();
        for (CurlingStoneEntity s : stones(sw, RADIUS + 1)) if (inHouse(s)) in.add(s);
        if (in.isEmpty()) return new int[]{-1, 0};
        in.sort(Comparator.comparingDouble(this::dist));
        int team = in.get(0).team(), n = 0;
        for (CurlingStoneEntity s : in) {
            if (s.team() != team) break;
            n++;
        }
        return new int[]{team, n};
    }

    public static Component teamName(int team) {
        return Component.translatable(team == 0 ? "curling.newyearcountdown.team.red" : "curling.newyearcountdown.team.yellow")
                .withStyle(team == 0 ? ChatFormatting.RED : ChatFormatting.YELLOW);
    }

    public void showScore(ServerPlayer p) {
        if (!(level instanceof ServerLevel sw)) return;
        int[] sc = score(sw);
        Component standing = sc[0] < 0 ? Component.translatable("curling.newyearcountdown.score.none")
                : Component.translatable("curling.newyearcountdown.score", teamName(sc[0]), sc[1]);
        p.sendSystemMessage(Component.translatable("curling.newyearcountdown.total", teamName(0), score[0], score[1], teamName(1))
                .append(Component.literal("  ")).append(standing));
    }

    /** 웅크리고 우클릭: 스톤을 거두고 새 경기. */
    public void collect(ServerPlayer p) {
        if (!(level instanceof ServerLevel sw)) return;
        returnStones(sw, 64);
        resetGame(sw);
        sw.playSound(null, worldPosition, SoundEvents.STONE_PLACE, SoundSource.BLOCKS, 0.8f, 1.1f);
        p.sendOverlayMessage(Component.translatable("curling.newyearcountdown.reset").withStyle(ChatFormatting.YELLOW));
    }
}
