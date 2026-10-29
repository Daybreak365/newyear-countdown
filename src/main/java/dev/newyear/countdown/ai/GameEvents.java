package dev.newyear.countdown.ai;

import dev.newyear.countdown.ai.net.AiPackets;
import dev.newyear.countdown.ai.script.ScriptApi;
import dev.newyear.countdown.ai.script.ScriptManager;
import dev.newyear.countdown.ai.script.api.Args;
import dev.newyear.countdown.ai.script.api.EntityRef;
import dev.newyear.countdown.ai.script.api.McUtil;
import dev.newyear.countdown.ai.script.api.PlayerRef;
import dev.newyear.countdown.ai.script.lang.Values.JsObject;
import dev.newyear.countdown.ai.wish.Wish;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Minecraft/Fabric 이벤트 → 스크립트 이벤트 버스. 구독한 스크립트가 없으면 페이로드를 만들지도 않는다.
 * Fabric 이 직접 주지 않는 이벤트(점프, 아이템 줍기/버리기, 블록 설치, 상태 효과)는 믹스인이 아래 정적 메서드를 부른다.
 */
public final class GameEvents {
    private GameEvents() {}

    private record Later(long due, Runnable task) {}

    private static final List<Later> LATER = new ArrayList<>();
    private static long serverTicks;

    /** 몇 틱 뒤에 서버 스레드에서 실행. */
    public static void later(int ticks, Runnable r) {
        LATER.add(new Later(serverTicks + ticks, r));
    }

    private static ScriptManager scripts() {
        WishEngine e = WishEngine.get();
        return e == null ? null : e.scripts();
    }

    private static boolean wants(String event) {
        ScriptManager s = scripts();
        return s != null && !WishEngine.get().state().killSwitch && s.hasListeners(event);
    }

    public static void register() {
        AiPackets.registerTypes();

        ServerLifecycleEvents.SERVER_STARTED.register(WishEngine::start);
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> WishEngine.stop());
        ServerLifecycleEvents.AFTER_SAVE.register((server, flush, force) -> {
            WishEngine e = WishEngine.get();
            if (e != null) {
                e.scripts().saveAllStates();
                e.writeAsync();
            }
        });
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            serverTicks++;
            if (!LATER.isEmpty()) {
                for (Later l : new ArrayList<>(LATER)) {
                    if (l.due <= serverTicks) {
                        LATER.remove(l);
                        try { l.task.run(); } catch (RuntimeException ex) { dev.newyear.countdown.NewYearCountdown.LOGGER.warn("[WishAI] delayed task failed", ex); }
                    }
                }
            }
            WishEngine e = WishEngine.get();
            if (e != null) e.serverTick();
        });

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            WishEngine e = WishEngine.get();
            if (e == null) return;
            ServerPlayer p = handler.player;
            // 클라이언트가 채널 등록을 마친 다음에 동기화한다
            later(10, () -> {
                if (WishEngine.get() != null && p.connection != null) WishEngine.get().onJoin(p);
            });
            if (wants("player_join")) scripts().emit("player_join", new JsObject().put("player", PlayerRef.of(p)), ScriptManager.Ctx.player(p.getUUID()));
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            WishEngine e = WishEngine.get();
            if (e == null) return;
            e.onLeave(handler.player);
            if (wants("player_leave")) scripts().emit("player_leave", new JsObject().put("player", PlayerRef.of(handler.player)), ScriptManager.Ctx.player(handler.player.getUUID()));
        });

        ServerMessageEvents.CHAT_MESSAGE.register((message, sender, params) -> {
            if (!wants("chat")) return;
            scripts().emit("chat", new JsObject().put("player", PlayerRef.of(sender)).put("message", McUtil.clean(message.signedContent(), 256)),
                    ScriptManager.Ctx.player(sender.getUUID()));
        });

        UseItemCallback.EVENT.register((player, level, hand) -> {
            if (level.isClientSide() || !(player instanceof ServerPlayer sp) || !wants("item_use")) return InteractionResult.PASS;
            ItemStack st = player.getItemInHand(hand);
            scripts().emit("item_use", new JsObject().put("player", PlayerRef.of(sp)).put("item", McUtil.itemInfo(st)).put("hand", hand.name().toLowerCase()),
                    new ScriptManager.Ctx(sp.getUUID(), null, McUtil.itemId(st), null, null));
            return InteractionResult.PASS;
        });

        UseEntityCallback.EVENT.register((player, level, hand, entity, hit) -> {
            if (level.isClientSide() || !(player instanceof ServerPlayer sp) || !wants("interact_entity")) return InteractionResult.PASS;
            if (hand != net.minecraft.world.InteractionHand.MAIN_HAND) return InteractionResult.PASS;
            ItemStack st = player.getItemInHand(hand);
            scripts().emit("interact_entity", new JsObject().put("player", PlayerRef.of(sp)).put("entity", handle(entity)).put("hand", "main_hand").put("item", McUtil.itemInfo(st)),
                    new ScriptManager.Ctx(sp.getUUID(), entity.getUUID(), McUtil.itemId(st), null, null));
            return InteractionResult.PASS;
        });

        AttackEntityCallback.EVENT.register((player, level, hand, entity, hit) -> {
            if (level.isClientSide() || !(player instanceof ServerPlayer sp) || !wants("attack")) return InteractionResult.PASS;
            scripts().emit("attack", new JsObject().put("player", PlayerRef.of(sp)).put("target", handle(entity)),
                    new ScriptManager.Ctx(sp.getUUID(), entity.getUUID(), null, null, null));
            return InteractionResult.PASS;
        });

        PlayerBlockBreakEvents.AFTER.register((level, player, pos, state, be) -> {
            if (level.isClientSide() || !(player instanceof ServerPlayer sp) || !wants("block_break")) return;
            scripts().emit("block_break", new JsObject().put("player", PlayerRef.of(sp)).put("block", McUtil.blockId(state)).put("pos", Args.posObj(pos.getX(), pos.getY(), pos.getZ())),
                    new ScriptManager.Ctx(sp.getUUID(), null, McUtil.blockId(state), null, null));
        });

        ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
            WishEngine e = WishEngine.get();
            if (e == null) return;
            Entity killer = source.getEntity();
            String cause = source.getMsgId();
            if (entity instanceof ServerPlayer sp) {
                if (wants("player_death")) {
                    scripts().emit("player_death", new JsObject().put("player", PlayerRef.of(sp)).put("cause", cause).put("killer", killer == null ? null : handle(killer))
                            .put("pos", Args.posObj(sp.getX(), sp.getY(), sp.getZ())), ScriptManager.Ctx.player(sp.getUUID()));
                }
                if (!e.state().killSwitch) e.autonomy().onDeath(sp, cause);
            }
            if (wants("kill")) {
                JsObject ev = new JsObject().put("killer", killer == null ? null : handle(killer)).put("victim", handle(entity))
                        .put("victimType", McUtil.entityId(entity.getType())).put("cause", cause);
                scripts().emit("kill", ev, new ScriptManager.Ctx(killer instanceof ServerPlayer kp ? kp.getUUID() : null, entity.getUUID(), null, null, null));
            }
        });

        ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> {
            if (!wants("respawn")) return;
            scripts().emit("respawn", new JsObject().put("player", PlayerRef.of(newPlayer)), ScriptManager.Ctx.player(newPlayer.getUUID()));
        });

        ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> {
            ScriptManager s = scripts();
            if (s != null) s.onEntityLoad(entity);
        });

        // ---------------- 네트워크
        ServerPlayNetworking.registerGlobalReceiver(AiPackets.ChatC2S.ID, (payload, ctx) -> {
            WishEngine e = WishEngine.get();
            if (e == null) return;
            e.wishes().playerMessage(ctx.player(), payload.wishId(), payload.text(), "chat");
        });
        ServerPlayNetworking.registerGlobalReceiver(AiPackets.InteractC2S.ID, (payload, ctx) -> {
            WishEngine e = WishEngine.get();
            if (e == null) return;
            e.ui().handleInteract(ctx.player(), payload.interactionId(), payload.index(), payload.value(), payload.nonce());
        });
        ServerPlayNetworking.registerGlobalReceiver(AiPackets.ActionC2S.ID, (payload, ctx) -> {
            WishEngine e = WishEngine.get();
            if (e == null) return;
            ServerPlayer p = ctx.player();
            switch (payload.action()) {
                case "sync" -> e.ui().syncPlayer(p);
                case "cancel" -> {
                    Wish w = e.wishes().openWishOf(p);
                    if (w != null && (payload.arg().isEmpty() || payload.arg().equals(w.id))) {
                        e.wishes().cancel(w.id, "player cancelled");
                        e.ui().spiritLine(p, w.id, "그래, 이 소원은 하늘에 돌려보내마.", "neutral");
                    }
                }
                case "new" -> {
                    // 새 소원을 시작하려고 대화창을 비움: 열린 소원 연결만 끊는다
                    var prof = e.state().players.get(p.getUUID().toString());
                    if (prof != null) prof.openWishId = null;
                }
                default -> { }
            }
        });
    }

    public static Object handle(Entity e) {
        if (e instanceof ServerPlayer sp) return PlayerRef.of(sp);
        return EntityRef.of(e);
    }

    // ---------------------------------------------------------------- 믹스인에서 부르는 지점

    public static void onJump(ServerPlayer p) {
        if (!wants("jump")) return;
        scripts().emit("jump", new JsObject().put("player", PlayerRef.of(p)).put("pos", Args.posObj(p.getX(), p.getY(), p.getZ())), ScriptManager.Ctx.player(p.getUUID()));
    }

    public static void onItemPickup(ServerPlayer p, ItemStack stack, int count, Entity thrower) {
        WishEngine e = WishEngine.get();
        if (e == null || e.state().killSwitch) return;
        JsObject info = McUtil.itemInfo(stack);
        info.put("count", (double) count);
        String id = McUtil.itemId(stack);
        if (wants("item_pickup")) {
            scripts().emit("item_pickup", new JsObject().put("player", PlayerRef.of(p)).put("item", info).put("count", (double) count)
                    .put("thrower", thrower instanceof ServerPlayer tp ? PlayerRef.of(tp) : null), new ScriptManager.Ctx(p.getUUID(), null, id, null, null));
        }
        if (thrower instanceof ServerPlayer from && !from.getUUID().equals(p.getUUID()) && wants("item_give")) {
            JsObject ev = new JsObject().put("from", PlayerRef.of(from)).put("to", PlayerRef.of(p)).put("item", info).put("count", (double) count);
            // from/to 어느 쪽 필터든 맞게: player 필터는 주는 사람 기준
            scripts().emit("item_give", ev, new ScriptManager.Ctx(from.getUUID(), null, id, null, null));
        }
        if (Boolean.TRUE.equals(info.get("special")) && thrower == null) {
            Object script = info.get("script");
            e.autonomy().onSpecialItem(p, stack.getHoverName().getString(), script instanceof String s ? s : null);
        }
    }

    public static void onItemDrop(ServerPlayer p, ItemStack stack) {
        if (!wants("item_drop")) return;
        scripts().emit("item_drop", new JsObject().put("player", PlayerRef.of(p)).put("item", McUtil.itemInfo(stack)).put("count", (double) stack.getCount()),
                new ScriptManager.Ctx(p.getUUID(), null, McUtil.itemId(stack), null, null));
    }

    public static void onBlockPlace(Player player, BlockPos pos, BlockState state) {
        if (!(player instanceof ServerPlayer sp) || !wants("block_place")) return;
        scripts().emit("block_place", new JsObject().put("player", PlayerRef.of(sp)).put("block", McUtil.blockId(state)).put("pos", Args.posObj(pos.getX(), pos.getY(), pos.getZ())),
                new ScriptManager.Ctx(sp.getUUID(), null, McUtil.blockId(state), null, null));
    }

    public static void onEffectAdded(LivingEntity e, MobEffectInstance inst) {
        if (e.level().isClientSide() || !wants("effect_added")) return;
        JsObject ev = new JsObject().put(e instanceof ServerPlayer ? "player" : "entity", handle(e))
                .put("effect", inst.getEffect().unwrapKey().map(k -> k.identifier().toString()).orElse("?"))
                .put("amplifier", (double) inst.getAmplifier()).put("seconds", inst.getDuration() / 20.0);
        scripts().emit("effect_added", ev, new ScriptManager.Ctx(e instanceof ServerPlayer ? e.getUUID() : null, e instanceof ServerPlayer ? null : e.getUUID(), null, null, null));
    }

    public static void onEffectsRemoved(LivingEntity e, Collection<MobEffectInstance> removed) {
        if (e.level().isClientSide() || !wants("effect_removed")) return;
        for (MobEffectInstance inst : removed) {
            JsObject ev = new JsObject().put(e instanceof ServerPlayer ? "player" : "entity", handle(e))
                    .put("effect", inst.getEffect().unwrapKey().map(k -> k.identifier().toString()).orElse("?"));
            scripts().emit("effect_removed", ev, new ScriptManager.Ctx(e instanceof ServerPlayer ? e.getUUID() : null, e instanceof ServerPlayer ? null : e.getUUID(), null, null, null));
        }
    }

    /** 스크립트 연출용 폭죽은 피해를 주지 않는다. */
    public static boolean isHarmlessFirework(Entity e) {
        return e.entityTags().contains(ScriptApi.FX_TAG);
    }

    /** 연등 소원: 연등이 떠오른 뒤 새벽별이 응답한다. */
    public static void onLanternWish(ServerPlayer p, String text) {
        WishEngine e = WishEngine.get();
        if (e == null || !e.aiEnabled()) return;
        later(50, () -> {
            WishEngine en = WishEngine.get();
            if (en == null || p.connection == null || p.isRemoved()) return;
            en.wishes().playerMessage(p, "", text, "lantern");
        });
    }
}
