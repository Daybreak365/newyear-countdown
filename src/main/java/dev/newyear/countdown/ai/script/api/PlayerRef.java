package dev.newyear.countdown.ai.script.api;

import dev.newyear.countdown.ai.WishEngine;
import dev.newyear.countdown.ai.script.Policy;
import dev.newyear.countdown.ai.script.ScriptInstance;
import dev.newyear.countdown.ai.script.lang.Interpreter;
import dev.newyear.countdown.ai.script.lang.Values;
import dev.newyear.countdown.ai.script.lang.Values.JsArray;
import dev.newyear.countdown.ai.script.lang.Values.JsObject;
import dev.newyear.countdown.ai.script.lang.Values.NativeFn;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import static dev.newyear.countdown.ai.script.lang.Values.UNDEFINED;

/** 스크립트가 보는 플레이어. 서버 객체를 직접 노출하지 않고 UUID 로만 붙잡는다. */
public final class PlayerRef implements Args.Positioned {
    public final UUID id;
    public final String name;

    public PlayerRef(UUID id, String name) {
        this.id = id;
        this.name = name;
    }

    public static PlayerRef of(ServerPlayer p) {
        return new PlayerRef(p.getUUID(), p.getGameProfile().name());
    }

    public ServerPlayer live() {
        WishEngine e = WishEngine.get();
        return e == null ? null : e.player(id);
    }

    @Override
    public double[] position() {
        ServerPlayer p = live();
        return p == null ? null : new double[]{p.getX(), p.getY(), p.getZ()};
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof PlayerRef r && r.id.equals(id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String typeName() {
        return "Player";
    }

    @Override
    public Object getMember(Interpreter in, String member) {
        switch (member) {
            case "name": return name;
            case "uuid": return id.toString();
            case "$ref": return "player:" + id;
            case "isOwner": {
                if (in == null) return false;
                ScriptInstance inst = ScriptInstance.of(in);
                return inst.rec.owner != null && inst.rec.owner.equals(id.toString());
            }
            default: break;
        }
        Values.Callable m = METHODS.get(member);
        return m == null ? UNDEFINED : m;
    }

    @Override
    public Collection<String> memberNames() {
        List<String> all = new java.util.ArrayList<>(List.of("name", "uuid", "isOwner"));
        all.addAll(METHODS.keySet());
        return all;
    }

    // ---------------------------------------------------------------- 메서드 표

    private static final Map<String, Values.Callable> METHODS = new LinkedHashMap<>();
    public static final Map<String, String> DOCS = new LinkedHashMap<>();

    private static void def(String name, String doc, NativeFn.Impl impl) {
        METHODS.put(name, new NativeFn("player." + name, impl));
        DOCS.put(name, doc);
    }

    private static PlayerRef self(Interpreter in, Object s) {
        if (s instanceof PlayerRef r) return r;
        throw in.error("player method called without a player");
    }

    /** 온라인이 아니면 오류 대신 null 을 돌려줄 때 쓴다. */
    private static ServerPlayer onlineOrNull(Interpreter in, Object s) {
        return self(in, s).live();
    }

    private static ServerPlayer online(Interpreter in, Object s) {
        PlayerRef r = self(in, s);
        ServerPlayer p = r.live();
        if (p == null) throw in.error(r.name + " is offline (check player.isOnline() first)");
        return p;
    }

    static {
        def("isOnline", "() → boolean", (in, s, a) -> onlineOrNull(in, s) != null);
        def("pos", "() → {x,y,z} (exact position)", (in, s, a) -> {
            ServerPlayer p = online(in, s);
            return Args.posObj(p.getX(), p.getY(), p.getZ());
        });
        def("blockPos", "() → {x,y,z} (block under feet, integers)", (in, s, a) -> {
            BlockPos b = online(in, s).blockPosition();
            return Args.posObj(b.getX(), b.getY(), b.getZ());
        });
        def("eyePos", "() → {x,y,z}", (in, s, a) -> {
            ServerPlayer p = online(in, s);
            return Args.posObj(p.getX(), p.getEyeY(), p.getZ());
        });
        def("look", "() → {x,y,z} unit vector the player is facing", (in, s, a) -> {
            Vec3 v = online(in, s).getViewVector(1f);
            return Args.posObj(v.x, v.y, v.z);
        });
        def("dimension", "() → 'minecraft:overworld' …", (in, s, a) -> McUtil.dimId(online(in, s).level()));
        def("biome", "() → biome id at the player", (in, s, a) -> {
            ServerPlayer p = online(in, s);
            return p.level().getBiome(p.blockPosition()).unwrapKey().map(k -> k.identifier().toString()).orElse("unknown");
        });
        def("health", "() → number", (in, s, a) -> (double) online(in, s).getHealth());
        def("maxHealth", "() → number", (in, s, a) -> (double) online(in, s).getMaxHealth());
        def("food", "() → 0..20", (in, s, a) -> (double) online(in, s).getFoodData().getFoodLevel());
        def("xpLevel", "() → number", (in, s, a) -> (double) online(in, s).experienceLevel);
        def("gameMode", "() → survival/creative/adventure/spectator", (in, s, a) -> online(in, s).gameMode().getName());
        def("isSneaking", "() → boolean", (in, s, a) -> online(in, s).isShiftKeyDown());
        def("isSprinting", "() → boolean", (in, s, a) -> online(in, s).isSprinting());
        def("isOnGround", "() → boolean", (in, s, a) -> online(in, s).onGround());
        def("isInWater", "() → boolean", (in, s, a) -> online(in, s).isInWater());
        def("isAlive", "() → boolean", (in, s, a) -> { ServerPlayer p = onlineOrNull(in, s); return p != null && p.isAlive(); });
        def("heldItem", "() → {id, count, name, special, data}", (in, s, a) -> McUtil.itemInfo(online(in, s).getMainHandItem()));
        def("offhandItem", "() → item info", (in, s, a) -> McUtil.itemInfo(online(in, s).getOffhandItem()));
        def("armor", "() → [head, chest, legs, feet] item infos", (in, s, a) -> {
            ServerPlayer p = online(in, s);
            JsArray arr = new JsArray();
            for (net.minecraft.world.entity.EquipmentSlot slot : new net.minecraft.world.entity.EquipmentSlot[]{
                    net.minecraft.world.entity.EquipmentSlot.HEAD, net.minecraft.world.entity.EquipmentSlot.CHEST,
                    net.minecraft.world.entity.EquipmentSlot.LEGS, net.minecraft.world.entity.EquipmentSlot.FEET}) {
                arr.list.add(McUtil.itemInfo(p.getItemBySlot(slot)));
            }
            return arr;
        });
        def("inventory", "() → [{slot, id, count, name, special}] non-empty slots", (in, s, a) -> {
            ServerPlayer p = online(in, s);
            JsArray arr = new JsArray();
            for (int i = 0; i < p.getInventory().getContainerSize(); i++) {
                ItemStack st = p.getInventory().getItem(i);
                if (st.isEmpty()) continue;
                JsObject o = McUtil.itemInfo(st);
                o.put("slot", (double) i);
                arr.list.add(o);
            }
            in.budget.allocate(arr.list.size() * 6L);
            return arr;
        });
        def("countItem", "(id) → total count in inventory", (in, s, a) -> {
            ServerPlayer p = online(in, s);
            String want = McUtil.parseId(in, Args.str(in, a, 0, "item id"), "item").toString();
            int n = 0;
            for (int i = 0; i < p.getInventory().getContainerSize(); i++) {
                ItemStack st = p.getInventory().getItem(i);
                if (!st.isEmpty() && McUtil.itemId(st).equals(want)) n += st.getCount();
            }
            return (double) n;
        });
        def("hasItem", "(id, count=1) → boolean", (in, s, a) -> {
            Object c = METHODS.get("countItem").call(in, s, a.subList(0, Math.min(1, a.size())));
            return Interpreter.toNumber(c) >= Args.num(a, 1, 1);
        });
        def("give", "(id, count=1, opts?{name, lore, glow, color, data}) → number given. Items with name/data are 'special' items the script can recognise later", (in, s, a) -> {
            ScriptInstance inst = ScriptInstance.of(in);
            PlayerRef r = self(in, s);
            String id = Args.str(in, a, 0, "item id");
            int count = (int) Math.max(1, Math.min(Policy.maxGiveCount(), Args.num(a, 1, 1)));
            JsObject opts = Args.opts(a, 2);
            ItemStack proto = McUtil.makeStack(in, id, 1, opts, inst.rec.id);
            Policy.checkGive(in, inst, proto, count);
            if (inst.dry) { inst.effect("give " + count + "x " + McUtil.itemId(proto) + " to " + r.name); return (double) count; }
            ServerPlayer p = online(in, s);
            int left = count, given = 0;
            int max = proto.getMaxStackSize();
            while (left > 0) {
                int n = Math.min(max, left);
                ItemStack st = proto.copyWithCount(n);
                if (!p.getInventory().add(st)) p.drop(st, false, net.minecraft.util.Prediction.SERVER_ONLY);
                left -= n;
                given += n;
            }
            Policy.recordGive(inst, proto, given);
            return (double) given;
        });
        def("take", "(id, count=1) → number actually removed. Only the wish owner, or a player who consented", (in, s, a) -> {
            ScriptInstance inst = ScriptInstance.of(in);
            PlayerRef r = self(in, s);
            String want = McUtil.parseId(in, Args.str(in, a, 0, "item id"), "item").toString();
            int count = (int) Math.max(1, Math.min(2304, Args.num(a, 1, 1)));
            Policy.checkAffect(in, inst, r, "take items from", true);
            if (inst.dry) { inst.effect("take " + count + "x " + want + " from " + r.name); return (double) count; }
            ServerPlayer p = online(in, s);
            int removed = p.getInventory().clearOrCountMatchingItems(st -> McUtil.itemId(st).equals(want), false, count, p.inventoryMenu.getCraftSlots());
            return (double) removed;
        });
        def("effect", "(id, seconds, amplifier=0, opts?{particles:false}) e.g. effect('speed', 30, 1)", (in, s, a) -> {
            ScriptInstance inst = ScriptInstance.of(in);
            PlayerRef r = self(in, s);
            String eid = Args.str(in, a, 0, "effect id");
            Holder<MobEffect> eff = McUtil.effect(in, eid);
            boolean harmful = McUtil.HARMFUL_EFFECTS.contains(eff.unwrapKey().map(k -> k.identifier().getPath()).orElse(""));
            if (harmful) Policy.checkAffect(in, inst, r, "apply a harmful effect to", true);
            int secs = (int) Math.max(1, Math.min(Policy.maxEffectSeconds(), Args.num(a, 1, 10)));
            int amp = (int) Math.max(0, Math.min(Policy.maxAmplifier(), Args.num(a, 2, 0)));
            boolean particles = Args.optBool(Args.opts(a, 3), "particles", true);
            if (inst.dry) { inst.effect("effect " + eid + " " + secs + "s amp " + amp + " on " + r.name); return true; }
            return online(in, s).addEffect(new MobEffectInstance(eff, secs * 20, amp, false, particles, true));
        });
        def("clearEffect", "(id) remove one effect", (in, s, a) -> {
            ScriptInstance inst = ScriptInstance.of(in);
            Holder<MobEffect> eff = McUtil.effect(in, Args.str(in, a, 0, "effect id"));
            if (inst.dry) { inst.effect("clearEffect on " + self(in, s).name); return true; }
            return online(in, s).removeEffect(eff);
        });
        def("hasEffect", "(id) → boolean", (in, s, a) -> online(in, s).hasEffect(McUtil.effect(in, Args.str(in, a, 0, "effect id"))));
        def("effects", "() → [effect ids]", (in, s, a) -> {
            JsArray arr = new JsArray();
            for (MobEffectInstance e : online(in, s).getActiveEffects()) arr.list.add(e.getEffect().unwrapKey().map(k -> k.identifier().toString()).orElse("?"));
            return arr;
        });
        def("heal", "(amount) heal hearts*2", (in, s, a) -> {
            ScriptInstance inst = ScriptInstance.of(in);
            float amt = (float) Math.max(0, Math.min(40, Args.num(a, 0, 4)));
            if (inst.dry) { inst.effect("heal " + amt + " " + self(in, s).name); return true; }
            online(in, s).heal(amt);
            return true;
        });
        def("feed", "(food, saturation=0.5)", (in, s, a) -> {
            ScriptInstance inst = ScriptInstance.of(in);
            int f = (int) Math.max(0, Math.min(20, Args.num(a, 0, 4)));
            if (inst.dry) { inst.effect("feed " + f + " " + self(in, s).name); return true; }
            online(in, s).getFoodData().eat(f, (float) Math.max(0, Math.min(1, Args.num(a, 1, 0.5))));
            return true;
        });
        def("damage", "(amount) non-lethal damage; only the wish owner or a consenting player", (in, s, a) -> {
            ScriptInstance inst = ScriptInstance.of(in);
            PlayerRef r = self(in, s);
            Policy.checkAffect(in, inst, r, "damage", true);
            float amt = (float) Math.max(0, Math.min(6, Args.num(a, 0, 1)));
            if (inst.dry) { inst.effect("damage " + amt + " " + r.name); return true; }
            ServerPlayer p = online(in, s);
            float allowed = Math.min(amt, Math.max(0, p.getHealth() - 2));
            if (allowed > 0) p.hurtServer((ServerLevel) p.level(), p.damageSources().magic(), allowed);
            return true;
        });
        def("giveXp", "(points)", (in, s, a) -> {
            ScriptInstance inst = ScriptInstance.of(in);
            int pts = (int) Math.max(0, Math.min(2000, Args.num(a, 0, 10)));
            if (inst.dry) { inst.effect("xp " + pts + " " + self(in, s).name); return true; }
            online(in, s).giveExperiencePoints(pts);
            return true;
        });
        def("teleport", "(pos) short-range safe teleport; only owner/consenting players", (in, s, a) -> {
            ScriptInstance inst = ScriptInstance.of(in);
            PlayerRef r = self(in, s);
            Policy.checkAffect(in, inst, r, "teleport", true);
            double[] to = Args.pos(in, Args.get(a, 0), "teleport target");
            if (inst.dry) { inst.effect("teleport " + r.name + " to " + (int) to[0] + "," + (int) to[1] + "," + (int) to[2]); return true; }
            ServerPlayer p = online(in, s);
            Policy.checkTeleport(in, inst, p, to);
            p.teleportTo((ServerLevel) p.level(), to[0], to[1], to[2], Set.of(), p.getYRot(), p.getXRot(), false);
            return true;
        });
        def("push", "(vx, vy, vz) add velocity (max 3 per axis)", (in, s, a) -> {
            ScriptInstance inst = ScriptInstance.of(in);
            PlayerRef r = self(in, s);
            double vx = clampV(Args.num(a, 0, 0)), vy = clampV(Args.num(a, 1, 0)), vz = clampV(Args.num(a, 2, 0));
            if (!r.id.toString().equals(inst.rec.owner)) Policy.checkAffect(in, inst, r, "push", vy > 1.2 || Math.abs(vx) > 1.5 || Math.abs(vz) > 1.5);
            if (inst.dry) { inst.effect("push " + r.name); return true; }
            ServerPlayer p = online(in, s);
            p.push(vx, vy, vz);
            p.needsSync = true;
            return true;
        });
        def("msg", "(text, color?) chat message to this player only", (in, s, a) -> {
            ScriptInstance inst = ScriptInstance.of(in);
            String t = Args.str(in, a, 0, "text");
            if (inst.dry) { inst.effect("msg to " + self(in, s).name + ": " + t); return true; }
            ServerPlayer p = onlineOrNull(in, s);
            if (p != null) p.sendSystemMessage(McUtil.text(t, Args.str(a, 1, null)));
            return true;
        });
        def("actionbar", "(text, color?)", (in, s, a) -> {
            ScriptInstance inst = ScriptInstance.of(in);
            String t = Args.str(in, a, 0, "text");
            if (inst.dry) { inst.effect("actionbar " + self(in, s).name + ": " + t); return true; }
            ServerPlayer p = onlineOrNull(in, s);
            if (p != null) p.sendOverlayMessage(McUtil.text(t, Args.str(a, 1, null)));
            return true;
        });
        def("title", "(title, subtitle?, opts?{fadeIn, stay, fadeOut, color}) vanilla title", (in, s, a) -> {
            ScriptInstance inst = ScriptInstance.of(in);
            String t = Args.str(in, a, 0, "title");
            String sub = Args.str(a, 1, "");
            JsObject o = Args.opts(a, 2);
            if (inst.dry) { inst.effect("title " + self(in, s).name + ": " + t); return true; }
            ServerPlayer p = onlineOrNull(in, s);
            if (p == null) return false;
            p.connection.send(new ClientboundSetTitlesAnimationPacket((int) Args.optNum(o, "fadeIn", 10), (int) Math.min(200, Args.optNum(o, "stay", 50)), (int) Args.optNum(o, "fadeOut", 15)));
            if (!sub.isEmpty()) p.connection.send(new ClientboundSetSubtitleTextPacket(McUtil.text(sub, Args.optStr(o, "subColor", "#c9b8e8"))));
            p.connection.send(new ClientboundSetTitleTextPacket(McUtil.text(t, Args.optStr(o, "color", "#f3d98a"))));
            return true;
        });
        def("sound", "(id, volume=1, pitch=1) heard by this player only", (in, s, a) -> {
            ScriptInstance inst = ScriptInstance.of(in);
            var ev = McUtil.sound(in, Args.str(in, a, 0, "sound id"));
            if (inst.dry) { inst.effect("sound to " + self(in, s).name); return true; }
            ServerPlayer p = onlineOrNull(in, s);
            if (p == null) return false;
            p.connection.send(new ClientboundSoundPacket(BuiltInRegistries.SOUND_EVENT.wrapAsHolder(ev), SoundSource.MASTER,
                    p.getX(), p.getY(), p.getZ(), (float) Math.max(0, Math.min(2, Args.num(a, 1, 1))), (float) Math.max(0.5, Math.min(2, Args.num(a, 2, 1))), p.getRandom().nextLong()));
            return true;
        });
        def("distanceTo", "(player|entity|pos) → blocks (Infinity if other dimension/offline)", (in, s, a) -> {
            ServerPlayer p = onlineOrNull(in, s);
            if (p == null) return Double.POSITIVE_INFINITY;
            Object other = Args.get(a, 0);
            if (other instanceof PlayerRef pr) {
                ServerPlayer o = pr.live();
                if (o == null || o.level() != p.level()) return Double.POSITIVE_INFINITY;
                return (double) p.distanceTo(o);
            }
            if (other instanceof EntityRef er) {
                var e = er.live();
                if (e == null || e.level() != p.level()) return Double.POSITIVE_INFINITY;
                return (double) p.distanceTo(e);
            }
            double[] q = Args.pos(in, other, "target");
            return Math.sqrt(p.distanceToSqr(q[0], q[1], q[2]));
        });
        def("is", "(player|name|uuid) → boolean", (in, s, a) -> {
            PlayerRef r = self(in, s);
            Object o = Args.get(a, 0);
            if (o instanceof PlayerRef pr) return pr.id.equals(r.id);
            String t = Interpreter.toStr(o);
            return t.equalsIgnoreCase(r.name) || t.equals(r.id.toString());
        });
        def("lookingAt", "(maxDistance=6) → {x,y,z} of the block being looked at, or null", (in, s, a) -> {
            ServerPlayer p = online(in, s);
            var hit = p.pick(Math.max(1, Math.min(32, Args.num(a, 0, 6))), 1f, false);
            if (hit instanceof net.minecraft.world.phys.BlockHitResult bh && hit.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK) {
                BlockPos b = bh.getBlockPos();
                return Args.posObj(b.getX(), b.getY(), b.getZ());
            }
            return null;
        });
        def("data", "(key, value?) per-player storage inside this script's state (persists)", (in, s, a) -> {
            ScriptInstance inst = ScriptInstance.of(in);
            PlayerRef r = self(in, s);
            Object bag = inst.state.get("$players");
            JsObject players;
            if (bag instanceof JsObject jo) players = jo;
            else { players = new JsObject(); inst.state.put("$players", players); }
            Object mine = players.get(r.id.toString());
            JsObject my;
            if (mine instanceof JsObject jo) my = jo;
            else { my = new JsObject(); players.put(r.id.toString(), my); }
            String key = Args.str(in, a, 0, "key");
            if (a.size() >= 2) { my.put(key, a.get(1)); return a.get(1); }
            return my.get(key);
        });
    }

    private static double clampV(double v) {
        return Math.max(-3, Math.min(3, v));
    }
}
