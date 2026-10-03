package dev.newyear.countdown.omikuji;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.PersistentState;
import net.minecraft.world.PersistentStateManager;

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
public class OmikujiData extends PersistentState {
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
    private static final String KEY = "newyearcountdown_omikuji";
    private static final Type<OmikujiData> TYPE = new Type<>(OmikujiData::new, OmikujiData::fromNbt, null);

    private final Map<UUID, List<Entry>> byPlayer = new HashMap<>();

    public static OmikujiData get(MinecraftServer server) {
        PersistentStateManager mgr = server.getOverworld().getPersistentStateManager();
        return mgr.getOrCreate(TYPE, KEY);
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
        markDirty();
        return e;
    }

    @Override
    public NbtCompound writeNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup lookup) {
        NbtList players = new NbtList();
        for (Map.Entry<UUID, List<Entry>> pe : byPlayer.entrySet()) {
            NbtCompound p = new NbtCompound();
            p.putUuid("Id", pe.getKey());
            NbtList list = new NbtList();
            for (Entry e : pe.getValue()) {
                NbtCompound c = new NbtCompound();
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

    public static OmikujiData fromNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup lookup) {
        OmikujiData data = new OmikujiData();
        NbtList players = nbt.getList("Players", NbtElement.COMPOUND_TYPE);
        for (int i = 0; i < players.size(); i++) {
            NbtCompound p = players.getCompound(i);
            if (!p.containsUuid("Id")) continue;
            List<Entry> list = new ArrayList<>();
            NbtList entries = p.getList("Entries", NbtElement.COMPOUND_TYPE);
            for (int k = 0; k < entries.size(); k++) {
                NbtCompound c = entries.getCompound(k);
                list.add(new Entry(c.getInt("R"), c.getInt("N"), c.getLong("T")));
            }
            data.byPlayer.put(p.getUuid("Id"), list);
        }
        return data;
    }
}
