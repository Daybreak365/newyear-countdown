package dev.newyear.countdown.wish;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.PersistentState;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 플레이어별 소원 기록 (서버/월드 저장 데이터). 연등이 하늘 높이 올라가 사라져도 기록은 남는다.
 * 이 월드(서버)에서 빈 소원만 들어 있으므로 다른 서버나 맵과 섞이지 않는다.
 */
public class WishData extends PersistentState {
    public static final class Entry {
        public final String text;
        public final long time;

        public Entry(String text, long time) {
            this.text = text;
            this.time = time;
        }
    }

    private static final int MAX = 200;
    private static final Type<WishData> TYPE = new Type<>(WishData::new, WishData::fromNbt, null);

    private final Map<UUID, List<Entry>> byPlayer = new HashMap<>();

    public static WishData get(MinecraftServer server) {
        return server.getOverworld().getPersistentStateManager().getOrCreate(TYPE, "newyearcountdown_wishes");
    }

    /** 시간순(오래된 것부터). */
    public List<Entry> of(UUID id) {
        return byPlayer.getOrDefault(id, List.of());
    }

    public void add(UUID id, String text, long time) {
        List<Entry> list = byPlayer.computeIfAbsent(id, k -> new ArrayList<>());
        list.add(new Entry(text, time));
        while (list.size() > MAX) list.remove(0);
        markDirty();
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
                c.putString("Text", e.text);
                c.putLong("T", e.time);
                list.add(c);
            }
            p.put("Wishes", list);
            players.add(p);
        }
        nbt.put("Players", players);
        return nbt;
    }

    public static WishData fromNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup lookup) {
        WishData data = new WishData();
        NbtList players = nbt.getList("Players", NbtElement.COMPOUND_TYPE);
        for (int i = 0; i < players.size(); i++) {
            NbtCompound p = players.getCompound(i);
            if (!p.containsUuid("Id")) continue;
            List<Entry> list = new ArrayList<>();
            NbtList wishes = p.getList("Wishes", NbtElement.COMPOUND_TYPE);
            for (int k = 0; k < wishes.size(); k++) {
                NbtCompound c = wishes.getCompound(k);
                list.add(new Entry(c.getString("Text"), c.getLong("T")));
            }
            data.byPlayer.put(p.getUuid("Id"), list);
        }
        return data;
    }
}
