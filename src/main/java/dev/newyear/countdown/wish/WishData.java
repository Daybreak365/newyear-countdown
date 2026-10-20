package dev.newyear.countdown.wish;

import net.minecraft.world.level.saveddata.SavedDataType;
import net.minecraft.core.UUIDUtil;
import dev.newyear.countdown.ModReg;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 플레이어별 소원 기록 (서버/월드 저장 데이터). 연등이 하늘 높이 올라가 사라져도 기록은 남는다.
 * 이 월드(서버)에서 빈 소원만 들어 있으므로 다른 서버나 맵과 섞이지 않는다.
 */
public class WishData extends SavedData {
    public static final class Entry {
        public final String text;
        public final long time;

        public Entry(String text, long time) {
            this.text = text;
            this.time = time;
        }
    }

    private static final int MAX = 200;
    private static final SavedDataType<WishData> TYPE = new SavedDataType<>(ModReg.id("wishes"), WishData::new,
            CompoundTag.CODEC.xmap(WishData::fromNbt, WishData::toNbt), null);

    private final Map<UUID, List<Entry>> byPlayer = new HashMap<>();

    public static WishData get(MinecraftServer server) {
        return server.getDataStorage().computeIfAbsent(TYPE);
    }

    /** 시간순(오래된 것부터). */
    public List<Entry> of(UUID id) {
        return byPlayer.getOrDefault(id, List.of());
    }

    public void add(UUID id, String text, long time) {
        List<Entry> list = byPlayer.computeIfAbsent(id, k -> new ArrayList<>());
        list.add(new Entry(text, time));
        while (list.size() > MAX) list.remove(0);
        setDirty();
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

    private static WishData fromNbt(CompoundTag nbt) {
        WishData data = new WishData();
        ListTag players = nbt.getListOrEmpty("Players");
        for (int i = 0; i < players.size(); i++) {
            CompoundTag p = players.getCompoundOrEmpty(i);
            UUID id = p.read("Id", UUIDUtil.CODEC).orElse(null);
            if (id == null) continue;
            List<Entry> list = new ArrayList<>();
            ListTag wishes = p.getListOrEmpty("Wishes");
            for (int k = 0; k < wishes.size(); k++) {
                CompoundTag c = wishes.getCompoundOrEmpty(k);
                list.add(new Entry(c.getStringOr("Text", ""), c.getLongOr("T", 0L)));
            }
            data.byPlayer.put(id, list);
        }
        return data;
    }
}
