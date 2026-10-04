package dev.newyear.countdown.omikuji;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import net.minecraft.core.UUIDUtil;
import dev.newyear.countdown.ModReg;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 플레이어별 오미쿠지 기록 (서버/월드 저장 데이터).
 * 이 월드(서버)에서 뽑은 것만 들어 있으므로 다른 서버나 맵과 섞이지 않고, 접속한 기기가 달라도 같다.
 * 목록의 첫 번째(0번)가 "첫 뽑기(메인)"이고 나머지는 추가 뽑기다.
 */
public class OmikujiData extends SavedData {
    public static final class Entry {
        public final int result;
        public final int number;
        public final long time;

        public Entry(int result, int number, long time) {
            this.result = result;
            this.number = number;
            this.time = time;
        }
    }

    private static final int MAX = 50;
    private static final SavedDataType<OmikujiData> TYPE = new SavedDataType<>(ModReg.id("omikuji"), OmikujiData::new,
            CompoundTag.CODEC.xmap(OmikujiData::fromNbt, OmikujiData::toNbt), null);

    private final Map<UUID, List<Entry>> byPlayer = new HashMap<>();

    public static OmikujiData get(MinecraftServer server) {
        return server.getDataStorage().computeIfAbsent(TYPE);
    }

    /** 모든 유저의 기록 (관리자 콘솔용). */
    public Map<UUID, List<Entry>> all() {
        return java.util.Collections.unmodifiableMap(byPlayer);
    }

    public List<Entry> of(UUID id) {
        return byPlayer.getOrDefault(id, List.of());
    }

    /** 기록을 더한다. 처음 뽑은 기록(0번)은 지켜지고, 50개를 넘으면 가장 오래된 추가 뽑기부터 지운다. */
    public Entry add(UUID id, int result, int number, long time) {
        List<Entry> list = byPlayer.computeIfAbsent(id, k -> new ArrayList<>());
        Entry e = new Entry(result, number, time);
        list.add(e);
        while (list.size() > MAX) list.remove(1);
        setDirty();
        return e;
    }

    private CompoundTag toNbt() {
        CompoundTag nbt = new CompoundTag();
        ListTag players = new ListTag();
        for (Map.Entry<UUID, List<Entry>> pe : byPlayer.entrySet()) {
            CompoundTag p = new CompoundTag();
            p.store("Id", UUIDUtil.CODEC, pe.getKey());
            ListTag list = new ListTag();
            for (Entry e : pe.getValue()) {
                CompoundTag c = new CompoundTag();
                c.putInt("R", e.result);
                c.putInt("N", e.number);
                c.putLong("T", e.time);
                list.add(c);
            }
            p.put("Entries", list);
            players.add(p);
        }
        nbt.put("Players", players);
        return nbt;
    }

    private static OmikujiData fromNbt(CompoundTag nbt) {
        OmikujiData data = new OmikujiData();
        ListTag players = nbt.getListOrEmpty("Players");
        for (int i = 0; i < players.size(); i++) {
            CompoundTag p = players.getCompoundOrEmpty(i);
            UUID id = p.read("Id", UUIDUtil.CODEC).orElse(null);
            if (id == null) continue;
            List<Entry> list = new ArrayList<>();
            ListTag entries = p.getListOrEmpty("Entries");
            for (int k = 0; k < entries.size(); k++) {
                CompoundTag c = entries.getCompoundOrEmpty(k);
                list.add(new Entry(c.getIntOr("R", 0), c.getIntOr("N", 0), c.getLongOr("T", 0L)));
            }
            data.byPlayer.put(id, list);
        }
        return data;
    }
}
