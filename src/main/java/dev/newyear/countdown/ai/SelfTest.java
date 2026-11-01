package dev.newyear.countdown.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.authlib.GameProfile;
import com.sun.net.httpserver.HttpServer;
import dev.newyear.countdown.NewYearCountdown;
import dev.newyear.countdown.ai.memory.MemoryRecord;
import dev.newyear.countdown.ai.script.ScriptInstance;
import dev.newyear.countdown.ai.script.ScriptRecord;
import dev.newyear.countdown.ai.script.ScriptValidator;
import dev.newyear.countdown.ai.script.api.McUtil;
import dev.newyear.countdown.ai.script.lang.Interpreter;
import dev.newyear.countdown.ai.wish.Quest;
import dev.newyear.countdown.ai.wish.Wish;
import net.fabricmc.fabric.api.entity.FakePlayer;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.levelgen.Heightmap;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * /wishai selftest — 실제 서버 안에서 소원 엔진 전체를 점검한다.
 * 가짜 플레이어 두 명과 (API 키 대신) 로컬 가짜 Responses API 서버로 소원 → 도구 호출 → 스크립트 검증·실행 → UI → 상태 저장까지
 * 실제 코드 경로를 그대로 지난다. 실제 OpenAI 모델의 판단력은 검증하지 않는다 (그건 진짜 키로만 가능).
 */
public final class SelfTest {
    private final WishEngine eng;
    private final CommandSourceStack src;
    private final List<String> results = new ArrayList<>();
    private int pass, fail;
    private FakePlayer a, b;
    private final List<JsonObject> sinkA = new CopyOnWriteArrayList<>();
    private final List<String> testScripts = new ArrayList<>();
    private HttpServer mock;
    private int savedCooldown, savedWishCooldown;
    private final List<JsonObject> sinkB = new CopyOnWriteArrayList<>();
    private final List<JsonObject> mockRequests = new CopyOnWriteArrayList<>();

    private interface Step {
        /** 실행 후 기다릴 조건과 검사. */
        void run(SelfTest t);
    }

    private final List<Runnable> queue = new ArrayList<>();

    private SelfTest(WishEngine eng, CommandSourceStack src) {
        this.eng = eng;
        this.src = src;
    }

    public static void run(WishEngine eng, CommandSourceStack src) {
        new SelfTest(eng, src).start();
    }

    private void log(String s) {
        NewYearCountdown.LOGGER.info("[WishAI selftest] {}", s);
        src.sendSuccess(() -> Component.literal(s), false);
    }

    private void check(String name, boolean ok, String detail) {
        if (ok) pass++;
        else fail++;
        String line = (ok ? "§aPASS " : "§cFAIL ") + "§f" + name + (detail == null || detail.isEmpty() ? "" : " §7— " + detail);
        results.add(line);
        log(line);
    }

    /** 다음 단계: delay 틱 뒤 실행. */
    private void then(int delay, Runnable r) {
        queue.add(() -> GameEvents.later(delay, () -> {
            try {
                r.run();
            } catch (Throwable ex) {
                check("step crashed", false, ex.toString());
                NewYearCountdown.LOGGER.error("[WishAI selftest] step crashed", ex);
            }
            next();
        }));
    }

    /** 조건이 참이 될 때까지 (최대 maxTicks) 기다린 뒤 검사. */
    private void waitFor(String name, int maxTicks, BooleanSupplier cond, Supplier<String> detail) {
        queue.add(() -> poll(name, maxTicks, cond, detail));
    }

    private void poll(String name, int left, BooleanSupplier cond, Supplier<String> detail) {
        GameEvents.later(5, () -> {
            boolean ok;
            try {
                ok = cond.getAsBoolean();
            } catch (Throwable ex) {
                ok = false;
            }
            if (ok || left <= 5) {
                check(name, ok, ok ? "" : detail.get());
                next();
            } else {
                poll(name, left - 5, cond, detail);
            }
        });
    }

    private void next() {
        if (queue.isEmpty()) return;
        queue.remove(0).run();
    }

    // ---------------------------------------------------------------- 도우미

    private ScriptRecord script(String name, String code, FakePlayer owner, long durationTicks) {
        ScriptRecord r = new ScriptRecord();
        r.id = "s-test-" + UUID.randomUUID().toString().substring(0, 6);
        r.name = name;
        r.code = code;
        r.created = System.currentTimeMillis();
        r.durationTicks = durationTicks;
        if (owner != null) {
            r.owner = owner.getUUID().toString();
            r.ownerName = owner.getGameProfile().name();
            r.dimension = McUtil.dimId(owner.level());
            r.anchor = new double[]{owner.getX(), owner.getY(), owner.getZ()};
        }
        testScripts.add(r.id);
        return r;
    }

    private ScriptManagerResult activate(ScriptRecord r) {
        ScriptValidator.Report rep = ScriptValidator.validate(r, List.of(), 0);
        if (!rep.ok) return new ScriptManagerResult(false, "validation: " + rep.errors);
        eng.state().scripts.put(r.id, r);
        var act = eng.scripts().activate(r, false);
        return new ScriptManagerResult(act.ok(), act.error());
    }

    private record ScriptManagerResult(boolean ok, String error) {}

    private int count(ServerPlayer p, net.minecraft.world.item.Item item) {
        int n = 0;
        for (int i = 0; i < p.getInventory().getContainerSize(); i++) {
            ItemStack st = p.getInventory().getItem(i);
            if (st.is(item)) n += st.getCount();
        }
        return n;
    }

    private Object stateOf(String scriptId, String key) {
        ScriptInstance inst = eng.scripts().instance(scriptId);
        return inst == null ? null : inst.state.get(key);
    }

    // ---------------------------------------------------------------- 시나리오

    private void start() {
        log("§6[WishAI] 자가 진단 시작 — 가짜 플레이어 2명, 로컬 가짜 AI 서버 사용");
        ServerLevel level = eng.server().overworld();
        // 26.x 에는 항상 로드된 스폰 청크가 없으므로 시험 구역을 잠시 강제 로드한다
        for (int cx = -1; cx <= 1; cx++) for (int cz = -1; cz <= 1; cz++) level.setChunkForced(cx, cz, true);
        savedCooldown = AiConfig.get().perPlayerCooldownSeconds;
        AiConfig.get().perPlayerCooldownSeconds = 0;
        // 가짜 AI 시나리오는 소원을 연달아 빈다 — 소원 쿨타임은 따로 시험한다
        savedWishCooldown = AiConfig.get().wishCooldownSeconds;
        AiConfig.get().wishCooldownSeconds = 0;
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, 0, 0);
        a = FakePlayer.get(level, new GameProfile(UUID.nameUUIDFromBytes("wishai-test-a".getBytes()), "WishTestA"));
        b = FakePlayer.get(level, new GameProfile(UUID.nameUUIDFromBytes("wishai-test-b".getBytes()), "WishTestB"));
        a.snapTo(0.5, y, 0.5);
        b.snapTo(2.5, y, 0.5);
        eng.addTestPlayer(a);
        eng.addTestPlayer(b);
        eng.state().profile(a.getUUID().toString(), "WishTestA");
        eng.state().profile(b.getUUID().toString(), "WishTestB");
        eng.ui().setTestSink(a.getUUID(), sinkA::add);
        eng.ui().setTestSink(b.getUUID(), sinkB::add);

        // 1. 일회성 스크립트: 빵 지급 (막 강제 로드한 청크가 엔티티를 받을 수 있게 2초 기다린 뒤)
        then(40, () -> {
            int before = count(a, Items.BREAD);
            var r = activate(script("bread", "owner.give('bread', 2, {name: '새벽별의 빵'}); fx.burst(owner.pos(), 'stars'); log('gave');", a, 2400));
            check("one-shot give bread", r.ok() && count(a, Items.BREAD) == before + 2, r.ok() ? "bread=" + count(a, Items.BREAD) : r.error());
        });

        // Runtime primitives: real codecs, checkpoint restore, input ownership and lifecycle cleanup.
        then(1, () -> {
            ItemStack old=a.getMainHandItem().copy();float health=a.getHealth();
            var speed=net.minecraft.world.entity.ai.attributes.Attributes.MOVEMENT_SPEED;
            double before=a.getAttributeValue(speed);
            ScriptRecord r=script("runtime-primitives", """
                    state.inputs=0;
                    items.patch(owner,'mainhand',{enchantment_glint_override:true});
                    attributes.modify(owner,'movement_speed','runtime_test',0.25,'multiply_total');
                    snapshots.capture(owner,'test');
                    owner.damage(2);
                    snapshots.restore(owner,'test');
                    controls.bind(owner,3,'test','시험');
                    controls.panel(owner,'test',{x:0.5,y:0.9,elements:[{type:'bar',value:0.5}]});
                    on('ability_input',e=>{if(e.pressed)state.inputs++;});
                    on('before_damage',e=>{return {amount:e.amount/2};},{player:owner});
                    """,a,200);
            try {
                a.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND,new ItemStack(Items.DIAMOND_SWORD));
                var result=activate(r);check("runtime primitives activate",result.ok(),result.error());
                if(result.ok()){
                    check("component patch applied",Boolean.TRUE.equals(a.getMainHandItem().get(net.minecraft.core.component.DataComponents.ENCHANTMENT_GLINT_OVERRIDE)),"glint");
                    check("checkpoint restored health",Math.abs(a.getHealth()-health)<0.01,"health="+a.getHealth());
                    check("attribute applied",a.getAttributeValue(speed)>before,"movement speed");
                    JsonObject input=new JsonObject();input.addProperty("script",r.id);input.addProperty("slot",3);input.addProperty("pressed",true);
                    dev.newyear.countdown.ai.script.RuntimeControls.input(b,input.toString());
                    check("foreign ability input rejected",Double.valueOf(0).equals(stateOf(r.id,"inputs")),"ownership");
                    dev.newyear.countdown.ai.script.RuntimeControls.input(a,input.toString());
                    check("owner ability input delivered",Double.valueOf(1).equals(stateOf(r.id,"inputs")),"input");
                    float adjusted=dev.newyear.countdown.ai.script.RuntimeHooks.damage(a,a.damageSources().magic(),8);
                    check("before damage return applied",Math.abs(adjusted-4)<0.01,"amount="+adjusted);
                }
            }finally{
                eng.scripts().stop(r.id,ScriptRecord.State.STOPPED,"runtime test finished",true);
                a.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND,old);a.setHealth(health);
            }
            check("attribute removed on stop",Math.abs(a.getAttributeValue(speed)-before)<0.00001,"cleanup");
        });

        // Player motion must reach the controlling client, not only nearby observers.
        then(1, () -> {
            var original=a.getDeltaMovement();boolean oldSync=a.syncVelocity,oldNeeds=a.needsSync;
            var delivered=new ArrayList<net.minecraft.network.protocol.Packet<? super net.minecraft.network.protocol.game.ClientGamePacketListener>>();
            var sync=new net.minecraft.server.level.ServerEntity.Synchronizer(){
                public void sendToTrackingPlayers(net.minecraft.network.protocol.Packet<? super net.minecraft.network.protocol.game.ClientGamePacketListener> packet){}
                public void sendToTrackingPlayersAndSelf(net.minecraft.network.protocol.Packet<? super net.minecraft.network.protocol.game.ClientGamePacketListener> packet){delivered.add(packet);}
                public void sendToTrackingPlayersFiltered(net.minecraft.network.protocol.Packet<? super net.minecraft.network.protocol.game.ClientGamePacketListener> packet,java.util.function.Predicate<ServerPlayer> filter){}
            };
            var tracker=new net.minecraft.server.level.ServerEntity((ServerLevel)a.level(),a,net.minecraft.world.entity.UpdateInterval.periodic(1),true,sync);
            ScriptRecord r=script("player-motion-sync","owner.push(0.5,0.1,0);",a,20);
            try{
                a.syncVelocity=false;
                var result=activate(r);check("player push activates",result.ok(),result.error());
                tracker.sendChanges();
                check("player motion delivered to self",delivered.stream().anyMatch(p->p instanceof net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket),"vanilla ServerEntity synchronizer");
            }finally{
                eng.scripts().stop(r.id,ScriptRecord.State.STOPPED,"motion test finished",true);
                a.setDeltaMovement(original);a.syncVelocity=oldSync;a.needsSync=oldNeeds;
            }
        });

        // 2. 이벤트 구독형 능력: 점프 이벤트 (믹스인 → 이벤트 버스 → 스크립트 → 상태·HUD)
        String[] jumpId = new String[1];
        then(1, () -> {
            sinkA.clear();
            ScriptRecord r = script("jump-stars", """
                    state.j ??= 0;
                    on('jump', (e) => {
                      state.j++;
                      fx.particles('end_rod', e.player.pos(), {count: 6});
                      ui.status(owner, 'jump', {label: '별빛 도약', counter: state.j + '/7'});
                    }, {player: owner});
                    """, a, -1);
            r.lasting = true;
            jumpId[0] = r.id;
            var res = activate(r);
            check("activate jump ability", res.ok(), res.error());
            for (int i = 0; i < 3; i++) a.jumpFromGround();
            b.jumpFromGround();   // 필터: 다른 플레이어 점프는 무시
        });
        waitFor("jump events counted via mixin (filter respected)", 20, () -> Interpreter.toNumber(stateOf(jumpId[0], "j")) == 3.0,
                () -> "state.j=" + stateOf(jumpId[0], "j"));
        then(1, () -> check("HUD status sent to client", sinkA.stream().anyMatch(o -> "status".equals(o.get("op").getAsString())), "ops=" + sinkA.size()));

        // 3. 엔티티 소환 + 행동 + 정리
        String[] entId = new String[2];
        then(1, () -> {
            ScriptRecord r = script("chicken-friend", """
                    const c = entities.spawn('chicken', offset(owner.pos(), 2, 0, 0), {name: '꼬꼬 경호원', glow: true});
                    c.follow(owner, {distance: 2});
                    state.c = c;
                    """, a, -1);
            entId[0] = r.id;
            var res = activate(r);
            ScriptRecord rec = eng.state().scripts.get(r.id);
            entId[1] = rec.entities.isEmpty() ? null : rec.entities.get(0);
            check("spawn named chicken with follow behaviour", res.ok() && entId[1] != null && rec.behaviours.size() == 1, res.error());
        });
        then(5, () -> {
            eng.scripts().stop(entId[0], ScriptRecord.State.STOPPED, "test", false);
            Entity e = entId[1] == null ? null : eng.server().overworld().getEntity(UUID.fromString(entId[1]));
            check("stopping script removes its entities", e == null || e.isRemoved(), "entity still present");
        });

        // 4. 블록 정책: 빈 공간만, 임시 블록 복구
        then(1, () -> {
            BlockPos above = a.blockPosition().above(3);
            ScriptRecord r = script("glass", "world.setBlock(offset(owner.blockPos(), 0, 3, 0), 'glass', {temporary: true});", a, -1);
            var res = activate(r);
            boolean placed = eng.server().overworld().getBlockState(above).is(net.minecraft.world.level.block.Blocks.GLASS);
            check("place temporary block in air", res.ok() && placed, res.error());
            eng.scripts().stop(r.id, ScriptRecord.State.STOPPED, "test", false);
            check("temporary block reverted on stop", eng.server().overworld().getBlockState(above).isAir(), McUtil.blockId(eng.server().overworld().getBlockState(above)));
            BlockPos below = a.blockPosition().below();
            boolean solid = !eng.server().overworld().getBlockState(below).isAir() && !eng.server().overworld().getBlockState(below).canBeReplaced();
            var r2 = activate(script("grief", "world.setBlock(offset(owner.blockPos(), 0, -1, 0), 'tnt');", a, -1));
            check("refuses to replace/grief existing blocks", !solid || (!r2.ok() && r2.error() != null && r2.error().contains("permission")), r2.ok() ? "was allowed!" : r2.error());
        });

        // 5. 자원 제한
        then(1, () -> {
            var st = ScriptValidator.validate(script("inf", "while (true) { }", a, -1), List.of(), 0);
            check("validator rejects infinite loop", !st.ok, String.valueOf(st.errors));
            var r = activate(script("heavy", "let i = 0; while (i >= 0) { i++; }", a, -1));
            check("runtime step budget stops runaway code", !r.ok() && r.error() != null && r.error().contains("budget"), r.error());
            var jv = ScriptValidator.validate(script("java", "const x = java.lang.System; x.exit(0);", a, -1), List.of(), 0);
            check("no Java access from scripts", !jv.ok, String.valueOf(jv.errors));
        });

        // 6. 퀘스트 + 아이템 선물 감지 + 보상
        String[] questScript = new String[1];
        then(1, () -> {
            ScriptRecord r = script("gift-contract", """
                    if (!state.q) state.q = quests.create({title: '나눔의 계약', description: '친구에게 선물하라', reward: '다이아몬드 1개',
                      objectives: [{id: 'gift', text: '선물하기', target: 1}], timeLimit: 600}).id;
                    on('item_give', (e) => { if (e.from.is(owner) && !e.to.is(owner)) quests.get(state.q).add('gift'); });
                    on('quest_completed', (e) => { if (e.quest.id === state.q) { owner.give('diamond', 1); state.rewarded = true; } });
                    """, a, -1);
            questScript[0] = r.id;
            var res = activate(r);
            check("quest tracker activated", res.ok(), res.error());
            GameEvents.onItemPickup(b, new ItemStack(Items.APPLE), 1, a);   // A 가 던진 사과를 B 가 주움
        });
        waitFor("item_give → quest progress → auto complete → reward", 20,
                () -> Boolean.TRUE.equals(stateOf(questScript[0], "rewarded")) && count(a, Items.DIAMOND) >= 1,
                () -> "rewarded=" + stateOf(questScript[0], "rewarded"));

        // 7. 재시작 복구 (상태 직렬화 → 재실행)
        then(1, () -> {
            ScriptRecord rec = eng.state().scripts.get(jumpId[0]);
            ScriptInstance inst = eng.scripts().instance(jumpId[0]);
            eng.scripts().saveState(inst);
            eng.scripts().live().remove(jumpId[0]);
            var res = eng.scripts().activate(rec, true);
            ScriptInstance again = eng.scripts().instance(jumpId[0]);
            a.jumpFromGround();
            check("restore keeps state and re-subscribes listeners", res.ok() && again != null && again.restored && Interpreter.toNumber(again.state.get("j")) == 4.0,
                    res.ok() ? "j=" + (again == null ? null : again.state.get("j")) : res.error());
        });

        // 7b. 주인이 접속해 있지 않을 때의 복구 → 접속하면 재개
        then(1, () -> {
            ScriptRecord r = script("offline-owner", "state.k ??= 0; state.k++; ui.status(owner, 'x', {label: '대기 능력'}); const here = owner.pos();", a, -1);
            r.lasting = true;
            var res = activate(r);
            ScriptInstance inst = eng.scripts().instance(r.id);
            eng.scripts().saveState(inst);
            eng.scripts().live().remove(r.id);
            r.state = ScriptRecord.State.ACTIVE;
            eng.removeTestPlayer(a.getUUID());          // 주인이 오프라인인 상태를 흉내
            var restored = eng.scripts().activate(r, true);
            boolean failedOffline = !restored.ok() && restored.error() != null && restored.error().contains("is offline");
            // 엔진이 서버 시작 시 하는 처리와 같게 대기 상태로 둔다
            if (failedOffline) { r.state = ScriptRecord.State.SUSPENDED; r.waitingForOwner = true; }
            eng.addTestPlayer(a);
            eng.onJoin(a);
            ScriptInstance again = eng.scripts().instance(r.id);
            check("restore while owner offline waits, resumes on join", res.ok() && failedOffline && again != null && Interpreter.toNumber(again.state.get("k")) == 2.0,
                    "first=" + res.ok() + " offlineFail=" + failedOffline + " resumed=" + (again != null));
        });

        // 8. UI 상호작용 검증 (다른 플레이어, 중복)
        String[] uiScript = new String[1];
        then(1, () -> {
            sinkA.clear();
            ScriptRecord r = script("choice", "ui.choices(owner, '운명을 골라라', ['별', '달', '해'], (i, text) => { state.pick = text; });", a, -1);
            uiScript[0] = r.id;
            var res = activate(r);
            String id = null;
            for (JsonObject o : sinkA) if ("interaction".equals(o.get("op").getAsString())) id = o.get("id").getAsString();
            check("choices UI sent", res.ok() && id != null, res.error());
            if (id == null) return;
            String wrong = eng.ui().handleInteract(b, id, 0, "", 11);
            check("other player's answer rejected", "not yours".equals(wrong), wrong);
            String okRes = eng.ui().handleInteract(a, id, 1, "", 12);
            String dup = eng.ui().handleInteract(a, id, 2, "", 13);
            check("answer accepted once, duplicate ignored", okRes == null && "already answered".equals(dup) && "달".equals(stateOf(r.id, "pick")),
                    "ok=" + okRes + " dup=" + dup + " pick=" + stateOf(r.id, "pick"));
        });

        // 9~11. 가짜 AI 서버로 전체 소원 흐름
        then(1, this::startMock);
        int[] breadBefore = new int[1];
        String[] wishId = new String[1];
        then(1, () -> {
            breadBefore[0] = count(a, Items.BREAD);
            sinkA.clear();
            eng.wishes().playerMessage(a, "", "빵 하나 줘", "chat");
            Wish w = eng.wishes().openWishOf(a);
            wishId[0] = w == null ? null : w.id;
            check("wish created and AI turn queued", w != null && w.state == Wish.State.ANALYZING, w == null ? "no wish" : w.state.name());
        });
        waitFor("AI tool call (run_script) gave bread, wish completed", 200, () -> {
            Wish w = wishId[0] == null ? null : eng.state().wishes.get(wishId[0]);
            return w != null && w.state == Wish.State.COMPLETED && count(a, Items.BREAD) == breadBefore[0] + 1;
        }, () -> {
            Wish w = wishId[0] == null ? null : eng.state().wishes.get(wishId[0]);
            return "state=" + (w == null ? null : w.state) + " bread=" + count(a, Items.BREAD) + " requests=" + mockRequests.size();
        });
        then(1, () -> {
            boolean line = sinkA.stream().anyMatch(o -> "line".equals(o.get("op").getAsString()) && o.get("text").getAsString().contains("옜다"));
            check("spirit reply delivered to dialogue UI", line, "ops=" + sinkA.size());
            boolean mem = eng.memory().ofPlayer(a.getUUID().toString()).stream().anyMatch(m -> "outcome".equals(m.kind));
            check("outcome stored in player memory", mem, "");
            boolean noKey = mockRequests.stream().noneMatch(r -> r.toString().contains(AiConfig.apiKey()));
            check("API key never sent in request bodies", noKey, "");
        });

        String[] w2 = new String[1];
        then(1, () -> {
            eng.wishes().playerMessage(a, "", "내가 점프할 때마다 폭죽이 터지게 해 줘", "chat");
            Wish w = eng.wishes().openWishOf(a);
            w2[0] = w == null ? null : w.id;
        });
        waitFor("AI fixes invalid script after validation error, then activates", 300, () -> {
            Wish w = w2[0] == null ? null : eng.state().wishes.get(w2[0]);
            if (w == null) return false;
            for (String sid : w.scriptIds) {
                ScriptRecord r = eng.state().scripts.get(sid);
                if (r != null && r.state.live() && r.subscriptions.contains("jump")) return true;
            }
            return false;
        }, () -> "requests=" + mockRequests.size());
        then(1, () -> {
            boolean refused = mockRequests.stream().anyMatch(r -> r.toString().contains("is never free"));
            check("server refuses a free permanent ability (no contract/quest yet)", refused, "");
            boolean sawError = false;
            for (JsonObject req : mockRequests) if (req.toString().contains("fireworkz")) sawError = req.toString().contains("did you mean") || req.toString().contains("has no member");
            check("validation error was returned to the model", sawError, "");
            Wish w = eng.state().wishes.get(w2[0]);
            if (w != null) for (String sid : w.scriptIds) testScripts.add(sid);
        });

        String[] w3 = new String[1];
        then(1, () -> {
            sinkA.clear();
            eng.wishes().playerMessage(a, "", "다이아몬드 줘", "chat");
            Wish w = eng.wishes().openWishOf(a);
            w3[0] = w == null ? null : w.id;
        });
        waitFor("AI proposes a contract (contract UI shown)", 200,
                () -> sinkA.stream().anyMatch(o -> "interaction".equals(o.get("op").getAsString()) && "contract".equals(o.get("kind").getAsString())),
                () -> "ops=" + sinkA.size());
        then(1, () -> {
            String id = null;
            for (JsonObject o : sinkA) if ("interaction".equals(o.get("op").getAsString()) && "contract".equals(o.get("kind").getAsString())) id = o.get("id").getAsString();
            String r = id == null ? "no id" : eng.ui().handleInteract(a, id, 0, "", 21);
            check("player accepts contract", r == null, r);
        });
        waitFor("after acceptance the AI creates a quest and tracker", 300, () -> {
            Wish w = w3[0] == null ? null : eng.state().wishes.get(w3[0]);
            if (w == null || w.questIds.isEmpty()) return false;
            Quest q = eng.state().quests.get(w.questIds.get(0));
            return q != null && q.state == Quest.State.ACTIVE && w.state == Wish.State.ACTIVE;
        }, () -> {
            Wish w = w3[0] == null ? null : eng.state().wishes.get(w3[0]);
            return w == null ? "no wish" : "state=" + w.state + " quests=" + w.questIds;
        });

        // 새 소원 쿨타임: 막힘 → OP 명령으로 초기화 → 다시 빌 수 있음 (대화 이어 가기는 막지 않음)
        then(1, () -> {
            AiConfig.get().wishCooldownSeconds = 60;
            var prof = eng.state().profile(b.getUUID().toString(), "WishTestB");
            prof.lastWishAt = 0;
            int before = wishesOf(b);
            sinkB.clear();
            eng.wishes().playerMessage(b, "", "사과 하나 줘", "chat");
            prof.openWishId = null;                              // "새 소원" 버튼과 같다
            eng.wishes().playerMessage(b, "", "사과 하나 더 줘", "chat");
            boolean blocked = wishesOf(b) == before + 1
                    && sinkB.stream().anyMatch(o -> "line".equals(o.get("op").getAsString()) && o.get("text").getAsString().contains("쿨타임"));
            eng.server().getCommands().performPrefixedCommand(src, "wishai cooldown reset WishTestB");
            prof.openWishId = null;
            eng.wishes().playerMessage(b, "", "사과 세 개 줘", "chat");
            check("wish cooldown blocks a new wish, OP reset lifts it", blocked && wishesOf(b) == before + 2, "wishes=" + (wishesOf(b) - before) + " blocked=" + blocked);
            AiConfig.get().wishCooldownSeconds = 0;
        });

        addConstraintAndFeatureChecks();
        then(1, this::finishAll);
        next();
    }

    /** §11·§12 로 새로 생긴 기능: 클로저 보존, 힙 측정, 블록 미리보기·롤백 충돌, AI 목표·두뇌, 조립 모델, 동적 텍스처, 기억 공유, 관리자 편집. */
    private void addConstraintAndFeatureChecks() {
        ServerLevel level = eng.server().overworld();

        // 12. 핸들러 안에서 만든 after() 가 재시작 뒤에도 클로저째 이어진다
        String[] cont = new String[1];
        then(1, () -> {
            ScriptRecord r = script("closure-resume", """
                    on('jump', (e) => {
                      const who = e.player.name;
                      let n = 0;
                      after(seconds(60), () => { n++; state.fired = who + ':' + n; });
                    }, {player: owner});
                    """, a, -1);
            r.lasting = true;
            cont[0] = r.id;
            var res = activate(r);
            a.jumpFromGround();
            check("closure test script active", res.ok(), res.error());
        });
        then(2, () -> {
            ScriptRecord rec = eng.state().scripts.get(cont[0]);
            ScriptInstance inst = eng.scripts().instance(cont[0]);
            boolean pending = inst != null && inst.timers.stream().anyMatch(t -> !t.cancelled);
            eng.scripts().saveState(inst);
            eng.scripts().live().remove(cont[0]);                       // "서버 재시작"
            var res = eng.scripts().activate(rec, true);
            ScriptInstance again = eng.scripts().instance(cont[0]);
            var t = again == null ? null : again.timers.stream().filter(x -> !x.cancelled).findFirst().orElse(null);
            long left = t == null ? -1 : t.due - eng.tick();
            check("handler-made after() survives restart with its remaining time", pending && res.ok() && t != null && left > 1000 && left <= 1200, "pending=" + pending + " left=" + left);
            if (t != null) t.due = eng.tick() + 1;                     // 기다리지 않고 바로 울리게
        });
        waitFor("revived closure still sees its captured locals", 20, () -> "WishTestA:1".equals(stateOf(cont[0], "fired")), () -> "fired=" + stateOf(cont[0], "fired"));

        // 13. 바이트 단위 힙 측정
        then(1, () -> {
            ScriptRecord r = script("heap", "const keep = []; for (let i = 0; i < 3000; i++) keep.push('새벽별의 기억 조각 ' + i); on('chat', () => keep.length);", a, -1);
            var res = activate(r);
            check("heap meter measures retained bytes", res.ok() && r.heapBytes > 150_000, "heap=" + r.heapBytes + " " + res.error());
            eng.scripts().stop(r.id, ScriptRecord.State.STOPPED, "test", false);
        });

        // 14. 블록 변경 미리보기: 모의 실행이 계획만 세우고 세계는 그대로
        then(1, () -> {
            sinkA.clear();
            BlockPos base = a.blockPosition().offset(3, 5, 3);
            ScriptRecord r = script("plan", "world.fill(offset(owner.blockPos(), 3, 5, 3), offset(owner.blockPos(), 5, 5, 5), 'glass');", a, -1);
            ScriptValidator.Report rep = ScriptValidator.validate(r, List.of(), 0);
            boolean untouched = level.getBlockState(base).isAir();
            eng.ui().blockPreview(a, "pv-test", "시험", rep.planned, 10);
            boolean sent = sinkA.stream().anyMatch(o -> "block_preview".equals(o.get("op").getAsString()) && o.get("total").getAsInt() == 9);
            check("dry run plans 9 blocks, world unchanged, ghost preview sent", rep.ok && rep.planned.size() == 9 && untouched && sent,
                    "planned=" + rep.planned.size() + " untouched=" + untouched + " sent=" + sent);
            check("validator reports a block summary for the AI", rep.toJson().has("block_preview") && rep.blockSummary().get("size").getAsString().equals("3x1x3"), rep.blockSummary().toString());
        });

        // 15. 롤백 충돌: 그 사이 플레이어가 바꾼 칸은 그대로, 관리자 강제 롤백만 되돌린다
        then(1, () -> {
            BlockPos p = a.blockPosition().above(6);
            ScriptRecord r = script("rollback", "world.setBlock(offset(owner.blockPos(), 0, 6, 0), 'glass');", a, -1);
            var res = activate(r);
            level.setBlock(p, net.minecraft.world.level.block.Blocks.STONE.defaultBlockState(), 3);   // 플레이어가 바꿨다고 치자
            var rep = eng.scripts().revertBlocks(r, false, false);
            boolean kept = level.getBlockState(p).is(net.minecraft.world.level.block.Blocks.STONE);
            var forced = eng.scripts().revertBlocks(r, false, true);
            boolean reverted = level.getBlockState(p).isAir();
            check("rollback leaves player-changed blocks, reports conflict; force reverts", res.ok() && rep.conflicts() == 1 && kept && forced.reverted() == 1 && reverted,
                    "conflicts=" + rep.conflicts() + " kept=" + kept + " forced=" + forced.reverted() + " reverted=" + reverted);
            eng.scripts().stop(r.id, ScriptRecord.State.STOPPED, "test", false);
        });

        // 16. 엔티티 AI 목표 + 스크립트 두뇌 (재시작 후에도)
        String[] goals = new String[1];
        then(1, () -> {
            ScriptRecord r = script("goals", """
                    state.wolf = entities.spawn('wolf', offset(owner.pos(), 3, 0, 0), {name: '파수꾼'});
                    state.wolf.guard(owner, {radius: 6});
                    state.cow = entities.spawn('cow', offset(owner.pos(), -3, 0, 0));
                    state.cow.flee('players', {distance: 4});
                    state.cow.brain({every: 5, canUse: () => true, tick: (me) => { state.brainTicks = (state.brainTicks ?? 0) + 1; }});
                    """, a, -1);
            r.lasting = true;
            goals[0] = r.id;
            var res = activate(r);
            check("guard/flee/brain goals attached", res.ok() && r.goals.size() == 3 && wishGoals(r, 0) > 0 && wishGoals(r, 1) > 0, res.error() + " goals=" + r.goals.size());
        });
        waitFor("scripted brain runs inside the mob's goal selector", 60, () -> Interpreter.toNumber(stateOf(goals[0], "brainTicks")) >= 2, () -> "ticks=" + stateOf(goals[0], "brainTicks"));
        then(1, () -> {
            ScriptRecord rec = eng.state().scripts.get(goals[0]);
            ScriptInstance inst = eng.scripts().instance(goals[0]);
            eng.scripts().saveState(inst);
            eng.scripts().live().remove(goals[0]);
            var res = eng.scripts().activate(rec, true);      // 최상위 코드는 다시 소환하지 않도록 쓰지 않았지만, 목표는 명세로 다시 붙는다
            ScriptInstance again = eng.scripts().instance(goals[0]);
            check("goals and brain restored after restart", res.ok() && again != null && again.brains.size() >= 1 && rec.goals.stream().anyMatch(g -> g.kind.equals("script")),
                    "brains=" + (again == null ? -1 : again.brains.size()) + " " + res.error());
        });
        then(1, () -> {
            ScriptRecord rec = eng.state().scripts.get(goals[0]);
            eng.scripts().stop(goals[0], ScriptRecord.State.STOPPED, "test", false);
            check("stopping removes goals", rec.goals.isEmpty(), "goals=" + rec.goals.size());
        });

        // 17. 조립 모델 (디스플레이 엔티티 묶음)
        String[] model = new String[1];
        then(1, () -> {
            ScriptRecord r = script("model", """
                    state.m = entities.model(offset(owner.pos(), 0, 4, 2), [{block: 'oak_planks'}, {block: 'glowstone', y: 1, scale: 0.5}, {text: '사당', y: 2}]);
                    state.m.move(offset(owner.pos(), 1, 4, 2), 5);
                    state.m.rotate(90, 10);
                    """, a, -1);
            model[0] = r.id;
            var res = activate(r);
            ScriptRecord.ModelSpec m = r.models.isEmpty() ? null : r.models.get(0);
            Entity part = m == null ? null : level.getEntity(UUID.fromString(m.parts.get(0).entity));
            check("model built from 3 display parts and moved as one", res.ok() && m != null && m.parts.size() == 3 && part != null && Math.abs(part.getX() - (a.getX() + 1)) < 0.6 && m.yaw == 90f,
                    res.ok() ? "parts=" + (m == null ? 0 : m.parts.size()) + " x=" + (part == null ? null : part.getX()) : res.error());
            eng.scripts().stop(r.id, ScriptRecord.State.STOPPED, "test", false);
            check("model parts removed with the script", part == null || part.isRemoved(), "still there");
        });

        // 18. 동적 텍스처 → 아이템 모습
        then(1, () -> {
            sinkA.clear();
            ScriptRecord r = script("texture", """
                    textures.create('selftest_star', {pixels: ['..#..', '.###.', '#####', '.###.', '..#..'], palette: {'#': '#ffcc00'}});
                    owner.give('paper', 1, {name: '별 부적', texture: 'selftest_star'});
                    """, a, 2400);
            var res = activate(r);
            boolean stored = eng.state().textures.containsKey("selftest_star");
            // 가짜 플레이어는 플레이어 목록에 없어 방송은 못 받으므로, 접속 동기화 경로로 확인한다
            sinkA.clear();
            eng.ui().syncPlayer(a);
            boolean sent = sinkA.stream().anyMatch(o -> "texture".equals(o.get("op").getAsString()) && "selftest_star".equals(o.get("id").getAsString()));
            boolean item = false;
            for (int i = 0; i < a.getInventory().getContainerSize(); i++) {
                ItemStack st = a.getInventory().getItem(i);
                if (st.is(Items.PAPER) && McUtil.TEXTURE_MODEL.equals(st.get(net.minecraft.core.component.DataComponents.ITEM_MODEL))) item = true;
            }
            check("AI texture stored, synced and worn by an item", res.ok() && stored && sent && item, "stored=" + stored + " sent=" + sent + " item=" + item + " " + res.error());
            var bad = ScriptValidator.validate(script("tex-bad", "owner.give('paper', 1, {texture: 'nope_missing'});", a, -1), List.of(), 0);
            check("dry run allows unknown texture names but real give needs one", bad.ok, String.valueOf(bad.errors));
            eng.state().textures.remove("selftest_star");
        });

        // 19. 사적 기억 공유
        then(1, () -> {
            MemoryRecord m = new MemoryRecord();
            m.owner = a.getUUID().toString();
            m.ownerName = "WishTestA";
            m.scope = MemoryRecord.Scope.PLAYER;
            m.text = "WishTestA 와 새벽별만 아는 비밀 정원의 위치";
            MemoryRecord saved = eng.memory().add(m, System.currentTimeMillis());
            boolean hiddenBefore = eng.memory().search("비밀 정원", b.getUUID().toString(), 5, null, true, System.currentTimeMillis()).stream().noneMatch(h -> h.record() == saved);
            var res = eng.memory().share(saved.id, b.getUUID().toString(), b.getUUID().toString());
            var ok = eng.memory().share(saved.id, b.getUUID().toString(), a.getUUID().toString());
            boolean visible = eng.memory().search("비밀 정원", b.getUUID().toString(), 5, null, true, System.currentTimeMillis()).stream().anyMatch(h -> h.record() == saved);
            String scope = dev.newyear.countdown.ai.agent.ContextBuilder.memoryJson(eng, saved, System.currentTimeMillis(), b.getUUID().toString()).get("scope").getAsString();
            check("private memory hidden → only its owner can share → shared player sees it", hiddenBefore && res == dev.newyear.countdown.ai.memory.MemoryStore.ShareResult.NOT_OWNER
                    && ok == dev.newyear.countdown.ai.memory.MemoryStore.ShareResult.OK && visible && scope.equals("shared_with_you_by:WishTestA"), "scope=" + scope);
            eng.memory().forget(saved.id);
        });

        // 20. 관리자 화면의 코드 편집 (검증 → 새 버전 적용 → 이전 버전 기록)
        then(1, () -> {
            ScriptRecord r = script("admin-edit", "state.v = 1; on('chat', () => {});", a, -1);
            r.lasting = true;
            var res = activate(r);
            var badEdit = eng.admin().scriptEdit(r.id, "state.v = ;", "test", "selftest", true);
            var edit = eng.admin().scriptEdit(r.id, "state.v = 2; on('chat', () => {});", "test", "selftest", true);
            ScriptInstance inst = eng.scripts().instance(r.id);
            check("admin edit validates, applies v2, keeps v1 in history", res.ok() && !badEdit.get("applied").getAsBoolean() && edit.get("applied").getAsBoolean()
                    && r.version == 2 && r.history.size() == 1 && inst != null && Interpreter.toNumber(inst.state.get("v")) == 2.0, edit.toString());
            eng.scripts().stop(r.id, ScriptRecord.State.STOPPED, "test", false);
        });

        // 22. 원래 세계 바꾸기: 자연 지형은 바꾸고, 지은 것과 그 옆은 보호
        then(1, () -> {
            BlockPos ground = a.blockPosition().offset(8, -1, 0);
            BlockPos plank = a.blockPosition().offset(12, 0, 0);
            level.setBlock(plank, net.minecraft.world.level.block.Blocks.OAK_PLANKS.defaultBlockState(), 3);   // 누가 지은 것
            ScriptRecord r = script("terrain", "world.setBlock(offset(owner.blockPos(), 8, -1, 0), 'stone');", a, -1);
            var res = activate(r);
            boolean changed = level.getBlockState(ground).is(net.minecraft.world.level.block.Blocks.STONE);
            var nextTo = ScriptValidator.validate(script("t2", "world.setBlock(offset(owner.blockPos(), 12, -1, 0), 'air');", a, -1), List.of(), 0);
            var built = ScriptValidator.validate(script("t3", "world.setBlock(offset(owner.blockPos(), 12, 0, 0), 'air');", a, -1), List.of(), 0);
            check("natural terrain can be changed; builds and the ground under them are protected", res.ok() && changed
                    && !nextTo.ok && String.valueOf(nextTo.errors).contains("touches something built")
                    && !built.ok && String.valueOf(built.errors).contains("looks built"), res.error() + " " + nextTo.errors + " " + built.errors);
            eng.scripts().stop(r.id, ScriptRecord.State.STOPPED, "test", true);
            check("terrain change rolls back", level.getBlockState(ground).is(net.minecraft.world.level.block.Blocks.GRASS_BLOCK), McUtil.blockId(level.getBlockState(ground)));
            level.setBlock(plank, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 3);
        });

        // 23. 점진적 지형 다듬기 (언덕) → world_edit_done → 롤백
        String[] hill = new String[1];
        then(1, () -> {
            ScriptRecord r = script("hill", """
                    state.e = world.terraform(offset(owner.pos(), 0, 0, -12), 4, {shape: 'hill', height: 3});
                    on('world_edit_done', (e) => { if (e.id === state.e) state.done = e.placed; });
                    """, a, -1);
            hill[0] = r.id;
            var res = activate(r);
            check("terraform job queued", res.ok() && r.edits.size() == 1 && r.edits.get(0).total() > 20, res.error());
        });
        waitFor("hill grows gradually and reports world_edit_done", 100, () -> Interpreter.toNumber(stateOf(hill[0], "done")) > 20, () -> "done=" + stateOf(hill[0], "done"));
        then(1, () -> {
            ScriptRecord r = eng.state().scripts.get(hill[0]);
            BlockPos top = a.blockPosition().offset(0, 2, -12);
            boolean raised = !level.getBlockState(top).isAir();
            int recorded = r.blocks.size();
            eng.scripts().stop(r.id, ScriptRecord.State.STOPPED, "test", true);
            check("hill blocks recorded and fully rolled back", raised && recorded > 20 && level.getBlockState(top).isAir(),
                    "raised=" + raised + " recorded=" + recorded);
        });

        // 24. 진짜 나무 키우기, 큰 모양은 승인 대상, 하늘은 서버 전체 간격
        then(1, () -> {
            ScriptRecord r = script("tree", "state.n = world.grow(offset(owner.blockPos(), -10, 0, 6), 'oak');", a, -1);
            var res = activate(r);
            boolean log = level.getBlockState(a.blockPosition().offset(-10, 0, 6)).is(net.minecraft.tags.BlockTags.LOGS);
            check("world.grow grows a real tree (recorded for rollback)", res.ok() && log && r.blocks.size() > 4, res.error() + " blocks=" + r.blocks.size());
            eng.scripts().stop(r.id, ScriptRecord.State.STOPPED, "test", true);
            check("tree removed by rollback", level.getBlockState(a.blockPosition().offset(-10, 0, 6)).isAir(), McUtil.blockId(level.getBlockState(a.blockPosition().offset(-10, 0, 6))));

            var big = ScriptValidator.validate(script("big", "world.sphere(offset(owner.pos(), 0, 30, 0), 8, 'glass');", a, -1), List.of(), 0);
            check("a big shape is planned in the dry run and needs admin approval", big.ok && big.planned.size() > 1024 && big.approvalNeeded, "planned=" + big.planned.size());

            long savedSky = eng.state().lastSkyChange;
            eng.state().lastSkyChange = 0;
            var first = activate(script("sky1", "world.setWeather('clear', 10);", a, -1));
            var second = activate(script("sky2", "world.setWeather('rain', 10);", a, -1));
            check("sky changes need no approval but are limited server-wide", first.ok() && !second.ok() && second.error() != null && second.error().contains("changed recently"),
                    first.error() + " / " + second.error());
            eng.state().lastSkyChange = savedSky;
        });

        // 21. 빈 서버 일시정지 방지 판단
        then(1, () -> {
            ScriptRecord r = script("world-rule", "timer('dawn-bell', 200); on('timer', () => {});", null, -1);
            var res = activate(r);
            boolean awake = eng.needsTickingWhileEmpty();
            eng.scripts().stop(r.id, ScriptRecord.State.STOPPED, "test", false);
            check("world scripts with timers keep an empty server awake", res.ok() && awake, res.error());
        });
    }

    private int wishesOf(ServerPlayer p) {
        String u = p.getUUID().toString();
        int n = 0;
        for (Wish w : eng.state().wishes.values()) if (u.equals(w.player)) n++;
        return n;
    }

    /** 이 스크립트가 목표 명세 index 번째 엔티티에 붙인 목표 수. */
    private int wishGoals(ScriptRecord r, int index) {
        if (r.goals.size() <= index) return 0;
        Entity e = eng.server().overworld().getEntity(UUID.fromString(r.goals.get(index).entity));
        if (!(e instanceof net.minecraft.world.entity.Mob m)) return 0;
        int n = 0;
        for (var g : m.getGoalSelector().getAvailableGoals()) if (g.getGoal() instanceof dev.newyear.countdown.ai.script.Goals.WishGoal w && w.scriptId().equals(r.id)) n++;
        return n;
    }

    // ---------------------------------------------------------------- 가짜 Responses API

    private void startMock() {
        try {
            mock = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (java.io.IOException e) {
            check("mock server", false, e.toString());
            return;
        }
        mock.createContext("/v1/responses", ex -> {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            JsonObject req = JsonParser.parseString(body).getAsJsonObject();
            mockRequests.add(req);
            String out = respond(req);
            byte[] bytes = out.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(bytes);
            }
        });
        mock.start();
        AiConfig.testApiBase = "http://127.0.0.1:" + mock.getAddress().getPort() + "/v1";
        AiConfig.testApiKey = "sk-selftest-" + UUID.randomUUID().toString().substring(0, 6);
        check("mock Responses API started", true, AiConfig.testApiBase);
    }

    /** 요청 내용을 보고 시나리오별 응답을 만든다 (함수 호출 → 결과를 보고 다음 단계). */
    private String respond(JsonObject req) {
        JsonArray input = req.getAsJsonArray("input");
        String lastUser = "";
        List<String> outputs = new ArrayList<>();
        for (JsonElement e : input) {
            JsonObject o = e.getAsJsonObject();
            if (o.has("role") && "user".equals(o.get("role").getAsString())) {
                lastUser = o.get("content").getAsString();
                outputs.clear();
            }
            if (o.has("type") && "function_call_output".equals(o.get("type").getAsString())) outputs.add(o.get("output").getAsString());
        }
        if (lastUser.contains("CONTRACT ACCEPTED")) {
            if (outputs.isEmpty()) {
                JsonObject args = obj("title", "나눔의 계약", "reward", "다이아몬드 16개");
                JsonArray objs = new JsonArray();
                objs.add(obj("id", "gift", "text", "친구에게 선물하기", "target", 1));
                args.add("objectives", objs);
                return call("create_quest", args);
            }
            if (outputs.size() == 1) {
                String qid = JsonParser.parseString(outputs.get(0)).getAsJsonObject().get("quest_id").getAsString();
                return call("activate_script", obj("name", "gift-tracker", "lasting", false,
                        "code", "const q = quests.get('" + qid + "'); on('item_give', (e) => { if (e.from.is(owner)) q.add('gift'); });\non('quest_completed', (e) => { if (e.quest.id === '" + qid + "') { owner.give('diamond', 16); wish.complete('선물하고 다이아몬드 16개'); } });"));
            }
            return finalJson("계약이 성립되었다.", false);
        }
        if (lastUser.contains("빵")) {
            if (outputs.isEmpty()) return call("run_script", obj("code", "owner.give('bread', 1); fx.burst(owner.pos(), 'stars');", "name", "bread"));
            return finalJson("옜다, 따끈한 빵이다. 맛있게 먹거라.", false);
        }
        if (lastUser.contains("폭죽")) {
            // ① 대가 없이 영구 능력을 주려다 서버에 거절당하고 ② 2분짜리 맛보기로 바꾸며 ③ 오타를 고친다
            if (outputs.isEmpty()) return call("activate_script", obj("name", "jump-fireworks", "lasting", true, "ability_label", "폭죽 도약",
                    "code", "on('jump', (e) => { fx.firework(e.player.pos()); }, {player: owner});"));
            if (outputs.size() == 1) return call("activate_script", obj("name", "jump-fireworks", "lasting", false, "duration_seconds", 120, "ability_label", "폭죽 도약 (맛보기)",
                    "code", "on('jump', (e) => { fx.fireworkz(e.player.pos()); }, {player: owner});"));
            if (outputs.size() == 2) return call("activate_script", obj("name", "jump-fireworks", "lasting", false, "duration_seconds", 120, "ability_label", "폭죽 도약 (맛보기)",
                    "code", "ui.status(owner, 'fw', {label: '폭죽 도약'});\non('jump', (e) => { fx.firework(offset(e.player.pos(), 0, 1, 0), {colors: ['gold'], shape: 'star'}); }, {player: owner});"));
            return finalJson("2분 동안만 맛보여 주마. 영원히 갖고 싶다면 그만한 대가를 치러야 한다.", false);
        }
        if (lastUser.contains("다이아몬드")) {
            if (outputs.isEmpty()) return call("propose_contract", obj("terms", "친구에게 아무 물건이나 하나 선물하라", "reward", "다이아몬드 16개"));
            return finalJson("계약서를 내밀겠다. 받아들이겠느냐?", true);
        }
        return finalJson("음… 무슨 말인지 모르겠구나.", false);
    }

    private static JsonObject obj(Object... kv) {
        JsonObject o = new JsonObject();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            Object v = kv[i + 1];
            if (v instanceof String s) o.addProperty((String) kv[i], s);
            else if (v instanceof Number n) o.addProperty((String) kv[i], n);
            else if (v instanceof Boolean bo) o.addProperty((String) kv[i], bo);
            else if (v instanceof JsonElement je) o.add((String) kv[i], je);
        }
        return o;
    }

    private static String call(String name, JsonObject args) {
        JsonObject r = new JsonObject();
        r.addProperty("id", "resp_" + UUID.randomUUID());
        r.addProperty("status", "completed");
        JsonArray out = new JsonArray();
        JsonObject fc = new JsonObject();
        fc.addProperty("type", "function_call");
        fc.addProperty("id", "fc_" + UUID.randomUUID());
        fc.addProperty("call_id", "call_" + UUID.randomUUID());
        fc.addProperty("name", name);
        fc.addProperty("arguments", args.toString());
        out.add(fc);
        r.add("output", out);
        r.add("usage", usage());
        return r.toString();
    }

    private static String finalJson(String say, boolean expect) {
        JsonObject j = new JsonObject();
        j.addProperty("say", say);
        j.addProperty("mood", "playful");
        j.addProperty("expect_reply", expect);
        j.add("quick_replies", new JsonArray());
        JsonObject r = new JsonObject();
        r.addProperty("id", "resp_" + UUID.randomUUID());
        r.addProperty("status", "completed");
        JsonArray out = new JsonArray();
        JsonObject msg = new JsonObject();
        msg.addProperty("type", "message");
        msg.addProperty("role", "assistant");
        JsonArray content = new JsonArray();
        JsonObject t = new JsonObject();
        t.addProperty("type", "output_text");
        t.addProperty("text", j.toString());
        content.add(t);
        msg.add("content", content);
        out.add(msg);
        r.add("output", out);
        r.add("usage", usage());
        return r.toString();
    }

    private static JsonObject usage() {
        JsonObject u = new JsonObject();
        u.addProperty("input_tokens", 1200);
        JsonObject d = new JsonObject();
        d.addProperty("cached_tokens", 900);
        u.add("input_tokens_details", d);
        u.addProperty("output_tokens", 80);
        return u;
    }

    // ---------------------------------------------------------------- 정리

    private void finishAll() {
        if (mock != null) mock.stop(0);
        AiConfig.get().perPlayerCooldownSeconds = savedCooldown;
        AiConfig.get().wishCooldownSeconds = savedWishCooldown;
        ServerLevel level = eng.server().overworld();
        for (int cx = -1; cx <= 1; cx++) for (int cz = -1; cz <= 1; cz++) level.setChunkForced(cx, cz, false);
        AiConfig.testApiBase = null;
        AiConfig.testApiKey = null;
        String ua = a.getUUID().toString(), ub = b.getUUID().toString();
        for (ScriptRecord r : new ArrayList<>(eng.state().scripts.values())) {
            if (ua.equals(r.owner) || ub.equals(r.owner) || testScripts.contains(r.id)) {
                if (r.state.live()) eng.scripts().stop(r.id, ScriptRecord.State.STOPPED, "selftest cleanup", true);
                eng.state().scripts.remove(r.id);
            }
        }
        eng.state().wishes.values().removeIf(w -> ua.equals(w.player) || ub.equals(w.player));
        eng.state().quests.values().removeIf(q -> ua.equals(q.owner) || ub.equals(q.owner));
        eng.state().statuses.values().removeIf(s -> ua.equals(s.owner) || ub.equals(s.owner));
        eng.memory().clearPlayer(ua);
        eng.memory().clearPlayer(ub);
        List<String> worldTest = new ArrayList<>();
        for (MemoryRecord m : eng.memory().all()) if (m.text.contains("WishTest")) worldTest.add(m.id);
        worldTest.forEach(eng.memory()::forget);
        eng.state().players.remove(ua);
        eng.state().players.remove(ub);
        eng.ui().setTestSink(a.getUUID(), null);
        eng.ui().setTestSink(b.getUUID(), null);
        eng.removeTestPlayer(a.getUUID());
        eng.removeTestPlayer(b.getUUID());
        a.discard();
        b.discard();
        log("§6[WishAI] 자가 진단 끝: §a" + pass + " 통과§f, " + (fail == 0 ? "§a" : "§c") + fail + " 실패");
    }
}
