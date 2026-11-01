package dev.newyear.countdown.ai.agent;

import dev.newyear.countdown.ai.script.EventTypes;
import dev.newyear.countdown.ai.script.QuestRef;
import dev.newyear.countdown.ai.script.ScriptApi;
import dev.newyear.countdown.ai.script.ScriptValidator;
import dev.newyear.countdown.ai.script.api.EntityRef;
import dev.newyear.countdown.ai.script.api.PlayerRef;

import java.util.Map;

/** get_script_api 도구가 돌려주는 문서. 실제 API 표에서 생성하므로 구현과 어긋나지 않는다. */
public final class ApiDocs {
    private ApiDocs() {}

    public static final String TOPICS = "overview, events, world, players, player, entities, entity, goals, model, textures, items, attributes, snapshots, content, controls, projectiles, extensions, native, runtime_examples, fx, ui, quests, quest, payments, construction, memory, misc, examples";

    public static String topic(String t) {
        ScriptValidator.primeDocs();
        String key = t == null ? "overview" : t.trim().toLowerCase();
        return switch (key) {
            case "overview" -> OVERVIEW;
            case "events" -> events();
            case "player" -> table("Player handle (e.g. owner, e.player). Properties: name, uuid, isOwner.", PlayerRef.DOCS);
            case "entity" -> table("Entity handle (non-player). Properties: uuid, type, name. Behaviours (follow/orbit/stay) and AI goals (guard/flee/attack/wander/patrol/brain) persist across restarts.", EntityRef.DOCS);
            case "goals", "ai_goals", "brain" -> GOALS;
            case "model", "models" -> table("Model handle from entities.model(origin, parts, opts). Property: id, parts (count). Keep it in state (state.statue = entities.model(...)) — it comes back after restarts.",
                    dev.newyear.countdown.ai.script.api.ModelRef.DOCS) + MODEL_EXAMPLE;
            case "quest" -> table("Quest handle from quests.create / quests.get. Property: id, title.", QuestRef.DOCS);
            case "misc", "timers" -> MISC;
            case "examples" -> EXAMPLES;
            case "native" -> NATIVE;
            case "runtime_examples" -> RUNTIME_EXAMPLES;
            default -> {
                Map<String, String> docs = ScriptApi.DOCS.get(key);
                if (docs == null) yield "Unknown topic '" + t + "'. Topics: " + TOPICS;
                yield table("Global object `" + key + "`:", docs);
            }
        };
    }

    private static String table(String head, Map<String, String> docs) {
        StringBuilder sb = new StringBuilder(head).append('\n');
        for (Map.Entry<String, String> e : docs.entrySet()) sb.append("- ").append(e.getKey()).append(e.getValue().startsWith("(") ? "" : " ").append(e.getValue()).append('\n');
        return sb.toString();
    }

    private static String events() {
        StringBuilder sb = new StringBuilder("""
                Subscribe with on(event, handler, filter?) — filter may be {player: owner} (only that player), {item: 'diamond'},
                {entity: someEntity}, {region: 'name'}. once(...) fires a single time. off(id) unsubscribes.
                Handlers receive one payload object. Events:
                """);
        for (Map.Entry<String, String> e : EventTypes.ALL.entrySet()) sb.append("- ").append(e.getKey()).append(": ").append(e.getValue()).append('\n');
        return sb.toString();
    }

    private static final String OVERVIEW = """
            WishScript = sandboxed JavaScript subset that runs inside the Minecraft server.
            Lifecycle: top-level code runs on activation and after every server restart (script.restored === true, `state` keeps saved
            JSON-safe values incl. Player/Entity handles). Keep top-level code idempotent: `state.jumps ??= 0;` and never
            spawn/create quests unguarded at top level — `if (!state.cat) state.cat = entities.spawn(...)`.
            create_quest reserves a hidden draft. Read its handle at TOP LEVEL: const q = quests.get('q-…'); then register
            all condition/reward handlers before activation ends. The panel/HUD and time limit start only after successful startup.
            quests.create in a script follows the same publication barrier. Never announce a quest before its tracker works.
            Limits per handler run: ~150k steps, 25 ms; max 24 listeners, 32 timers, 24 entities, 4096 changed blocks per script
            (64 per tick by hand — use the gradual world.terraform/sphere/box/replace/clear for anything bigger).
            Positions are {x, y, z} objects (arrays [x,y,z] and handles also accepted). Item/entity/block ids accept 'diamond' or 'minecraft:diamond'.
            Globals: owner, state, world, players, entities, fx, ui, quests, memory, wish, script, ai, textures,
                     items, attributes, snapshots, content, controls, projectiles, extensions, payments, construction,
                     on/once/off/emit, after/every/cancel/timer/cancelTimer, region/removeRegion,
                     now, tick, random, randomInt, chance, pick, shuffle, pos, offset, distance, seconds, minutes, log,
                     plus Math, JSON, Object, Array, Number, String, parseInt, parseFloat, console.
            New looks and creatures without new registry types: textures.create (AI-drawn pixel textures used as item looks, in-world
            pictures and HUD icons), entities.model (procedural 3D models from display entities), entity goals (real mob AI: guard,
            flee, attack, wander, patrol, and brain() for your own goal logic).
            Topics for details: events, world, players, player, entities, entity, goals, model, textures, fx, ui, quests, quest, memory, misc, examples.
            """;

    private static final String NATIVE = """
            Native generation is for a missing engine capability, not the first choice for a simple wish.
            draft_extension accepts one Java 25 source (nested classes allowed), class_name wishai.generated.YourClass,
            and optional textual JSON resources. It compiles asynchronously without running generated code (-proc:none).
            Import dev.newyear.countdown.ai.extension.NativeExtension and ExtensionContext, com.google.gson.JsonObject.
            Implement NativeExtension:
              Map<String,String> operations(): 1..32 stable operation names and argument/result documentation.
              JsonObject preview(String operation,JsonObject args): validate arguments and describe effects; NO world access/mutation.
              JsonObject invoke(String operation,ExtensionContext ctx,JsonObject args): server-thread operation.
              Map<String,Boolean> selfTest(): nonempty unit assertions, no production resources.
              Map<String,Boolean> integrationTest(ExtensionContext ctx): real assertions in a disposable Minecraft world.
              void close(): remove module-level hooks/resources.
            Context: ctx.owner(), ctx.requireConsent(player,action), ctx.requireRange(x,y,z), ctx.onStop(Runnable),
            ctx.engine(), ctx.script(), ctx.interpreter(). Validate every argument, bound work; register per-script cleanup.
            Native code has full JVM authority once installed; it is NOT constrained by WishScript's sandbox or time meter.
            Never read secrets, spawn host processes, access other players without consent, or fetch remote code.
            The AI cannot run tests or approve installation. An operator configures an OS-isolated extensionTestCommand,
            runs /wishai extension ID test, reviews source + integration report, then uses
            /wishai extension ID approve FULL_SHA256. Only TESTED artifacts with unchanged hashes can install.
            COMPILED and NEEDS_INTEGRATION_TEST are not usable. Poll get_extension on a later turn; don't busy-loop.
            Ordinary NativeExtension modules hot-load and expose extensions.call(id,operation,args).
            Actual registry types, mixins and client bootstrap code require a fabric.mod.json resource and restart;
            tested/approved modules are staged with /wishai extension ID stage and installed after a coordinated restart.
            Existing clients need that mod too for client-dependent content. Never promise every change can hot-load.
            Imported factories and installed native modules retain explicit versions/hashes. Restore persistent native
            effects by invoking their setup operation at each script activation, including script.restored.
            """;

    private static final String RUNTIME_EXAMPLES = """
            Compose mechanics around player actions; use contracts/consent as required, then activate.
            Shield: on('before_damage', e => { if (e.amount > 3) return {amount: e.amount * 0.5}; }, {player: owner});
            Second chance: if (!state.saved) { snapshots.capture(owner,'home'); state.saved=true; }
              on('before_death', e => { if (!state.used) { snapshots.restore(owner,'home'); state.used=true; return {cancel:true}; } }, {player:owner});
            Charged ability input: controls.assign(owner,'charge','별빛 모으기');
              on('ability_input',e=>{ if(e.pressed) state.started=tick(); else { state.charge=tick()-state.started; } });
            HUD: controls.panel(owner,'charge',{x:0.5,y:0.9,elements:[{type:'bar',w:100,h:8,value:0.7,color:'#aaccff'},{type:'text',y:-12,text:'별빛'}]});
            Movement/size: attributes.modify(owner,'gravity','moon',-0.5,'multiply_total');
              attributes.modify(owner,'scale','tiny',-0.4,'multiply_total'); modifiers auto-remove on stop/suspend.
            Projectiles: projectiles.fire(offset(owner.pos(),0,1.5,0),{x:0,y:0,z:1},{seconds:3,gravity:0.01});
              on('projectile_hit',e=>{if(e.target) e.target.damage(2);}); player damage requires consent and is non-lethal.
            Items: items.get(owner,'mainhand') shows current codec JSON; items.components() lists editable keys.
              items.patch(owner,'mainhand',{enchantment_glint_override:true}); owner.give also accepts {components:{...}}.
            XP payment: if(!owner.takeXpLevels(3)) throw 'Not enough XP'; // only after agreed price, with reward in same handler.
            Factories: publish_content stores a body using params; include // @use EXACT_ID in a new script,
              then content.create('EXACT_ID',{...}); source is copied into that script so later versions cannot alter it.
            Before-event changes must be RETURNED. Returning {cancel:true} cancels; only before_damage accepts amount.
            Failed/over-budget handlers cannot return a change. Earlier world effects within a handler are not rolled back.
            Snapshots are single-use and do not recreate missing/moved items or restore XP. HUD keys are unbound initially;
            players choose bindings in Minecraft Controls. Max 4 slots; avoid taking a slot already used by another ability.
            Prefer controls.assign: it finds a free slot and keeps the slot for the same action when possible.
            controls.bind explicitly selects a slot and reports collisions, including suspended abilities' reservations.
            Register on('ability_input',...) at top level every time; binding without a receiver fails validation/activation.
            """;

    private static final String GOALS = """
            Entity AI goals (real vanilla goal-selector goals; they compete by priority with the mob's own goals, 0 = most urgent).
            All return a goal id, persist across chunk reloads and server restarts, and stop when the script ends. Calling the same kind
            again on the same entity replaces it. Works on script-spawned mobs and on world mobs (not other players' pets without consent).
            - e.guard(spotOrPlayer, {radius=8, speed=1.2}) stay near a spot / bodyguard a player, attack hostile mobs inside radius, return home.
            - e.flee('players'|'hostile'|'zombie'|player|entity, {distance=8, speed=1.3}) run away when it comes close.
            - e.attack('hostile'|'skeleton'|entity|player, {radius=16, speed=1.2}) hunt and melee. Other players only with consent (ui.consent).
              guard/attack need a mob that can attack (wolf, iron_golem, fox, zombie, bee, …) — chickens/cows cannot.
            - e.wander({center, radius=8}) roam; e.patrol([p1, p2, …]) loop through points; e.lookAtPlayers(8).
            - e.brain({canUse: (me) => bool, start: (me) => {}, tick: (me) => {}, stop: (me) => {}, every: 10, priority: 2}) your OWN goal:
              the mob runs it while canUse is true. Callbacks run every `every` ticks (min 5) with the normal step budget; `me` is the Entity.
              One brain per entity per script; it is saved with its closure and resumes after restarts.
            - e.clearGoals(id?) remove one or all goals this script gave.
            Example — a star wolf that stays close to the owner at night and circles a shrine by day:
              const wolf = entities.spawn('wolf', owner.pos(), {name: '별늑대', keep: true, tame: owner});
              wolf.brain({every: 10,
                canUse: () => true,
                tick: (me) => {
                  if (world.isNight()) { if (me.distanceTo(owner) > 3) me.moveTo(owner.pos(), 1.2); }
                  else me.moveTo(offset(state.shrine, Math.cos(tick() / 40) * 4, 0, Math.sin(tick() / 40) * 4));
                }});
            """;

    private static final String MODEL_EXAMPLE = """
            Example — a small glowing shrine that slowly spins, then follows the owner like a familiar:
              if (!state.shrine) state.shrine = entities.model(offset(owner.pos(), 2, 0, 0), [
                {block: 'oak_planks', x: 0, y: 0.25, z: 0, sx: 1, sy: 0.5, sz: 1},
                {block: 'lantern', x: 0, y: 0.9, z: 0, scale: 0.6},
                {item: 'paper', texture: 'shrine_seal', x: 0, y: 0.6, z: 0.52, scale: 0.5},
                {text: '소원의 사당', x: 0, y: 1.6, z: 0}
              ], {keep: true});
              every(20, () => state.shrine.rotate((tick() / 2) % 360, 20));
              // later: state.shrine.follow(owner, {x: 1.5, y: 1, z: 0}); state.shrine.part(1, {y: 1.2}, 10);
            Parts: block | item (+ texture) | text; x,y,z offset (blocks, within 16); scale or sx,sy,sz; rx,ry,rz degrees.
            Models count as the script's entities (removed when it ends unless keep:true). Max 8 models × 48 parts per script.
            """;

    private static final String MISC = """
            Timers: after(ticks, fn) → id; every(ticks, fn) → id (min 2 ticks); cancel(id).
              They survive restarts: pending timers and listeners registered later (inside handlers, or under `if (!script.restored)`)
              are saved WITH their closures (captured local variables) and resume with the time they had left; ones the top-level code
              re-creates are matched up instead of duplicated; one-shot timers that already fired are not repeated.
              A closure that captures a detached built-in function (e.g. `const k = Object.keys`) cannot be saved — call it through its object.
            Named persistent timers: timer('name', ticks, data?) → fires on('timer', e => e.name === 'name'); cancelTimer('name').
            Custom events between scripts: emit('name', data) → on('custom', e => e.name ...).
            Regions: region('shrine', center, radius, {height}) → region_enter / region_leave events with e.region.
            Helpers: now() ms, tick() engine tick, random(min, max), randomInt(min, max) inclusive, chance(0.3), pick(array),
              shuffle(array), pos(x,y,z), offset(pos, dx, dy, dz), distance(a, b), seconds(n) → ticks, minutes(n) → ticks, log(...).
            wish: wish.id, wish.text, wish.state(), wish.complete(summary), wish.fail(reason).
            script: script.id, script.name, script.restored, script.version, script.remaining(), script.stop(reason).
            ai.ask(text, data?) asks the wish spirit to react (rate limited) — use for story moments, not progress ticks.
            """;

    private static final String EXAMPLES = """
            // 1) One-shot (run_script): give bread with a little sparkle
            owner.give('bread', 1, {name: '새벽별의 빵', lore: '따뜻한 온기가 남아 있다'});
            fx.burst(owner.pos(), 'stars');
            owner.sound('entity.experience_orb.pickup');

            // 2) Lasting ability: stars on every jump, a cat on every 7th jump (activate_script, lasting: true)
            state.jumps ??= 0;
            ui.status(owner, 'stars', {label: '별빛 도약', kind: 'ability', counter: (state.jumps % 7) + '/7'});
            on('jump', (e) => {
              state.jumps++;
              fx.particles('end_rod', e.player.pos(), {count: 10, spread: 0.3});
              if (state.jumps % 7 === 0) {
                const cat = entities.spawn('cat', e.player.pos(), {name: '별고양이', glow: true});
                after(seconds(20), () => cat.remove());
              }
              ui.status(owner, 'stars', {label: '별빛 도약', kind: 'ability', counter: (state.jumps % 7) + '/7'});
            }, {player: owner});

            // 3) Contract tracker: gift 1 item to another player, then reward 16 diamonds
            // top-level code re-runs after a restart: create the quest only once
            if (!state.questId) {
              state.questId = quests.create({title: '나눔의 계약', description: '친구에게 물건 하나를 선물하라', reward: '다이아몬드 16개',
                objectives: [{id: 'gift', text: '다른 플레이어에게 아이템 선물하기', target: 1}], timeLimit: 1800}).id;
            }
            on('item_give', (e) => {
              if (!e.from.is(owner) || e.to.is(owner)) return;
              quests.get(state.questId)?.add('gift');
            });
            on('quest_completed', (e) => {
              if (e.quest.id !== state.questId) return;
              owner.give('diamond', 16);
              fx.firework(offset(owner.pos(), 0, 2, 0), {colors: ['#7fdcff', 'white'], shape: 'star'});
              memory.remember(owner.name + '는 친구에게 선물하고 다이아몬드 16개를 받았다', {importance: 5});
              wish.complete('친구에게 선물하고 다이아몬드 16개를 받음');
              script.stop('contract fulfilled');
            });

            // 4) World rule: at night chickens follow players unless they hold wheat seeds
            every(40, () => {
              if (!world.isNight()) return;
              for (const p of players.online()) {
                const seeds = p.heldItem().id === 'minecraft:wheat_seeds';
                for (const c of entities.near(p.pos(), 12, 'chicken')) {
                  if (seeds) c.stopBehaviour(); else c.follow(p, {speed: 1.1, distance: 2});
                }
              }
            });

            // 5) Reshape the world (after the player paid the price): a pond with trees around it, then a stone bridge
            if (!state.pond) {
              const c = offset(owner.pos(), 6, 0, 0);
              state.pond = world.terraform(c, 7, {shape: 'lake', height: 3});
              state.center = c;
            }
            on('world_edit_done', (e) => {
              if (e.id !== state.pond) return;
              for (let i = 0; i < 5; i++) {
                const a = i * Math.PI * 2 / 5;
                const p = offset(state.center, Math.cos(a) * 9, 0, Math.sin(a) * 9);
                world.grow(pos(p.x, world.surface(p.x, p.z), p.z), pick(['birch', 'cherry', 'oak']));
              }
              world.box(offset(state.center, -8, 0, 0), offset(state.center, 8, 0, 0), 'stone_brick_slab');
              ui.notify(owner, '연못이 생겼다', {kind: 'success'});
            });

            // 6) Riddle with a 30 s limit, all local
            ui.timer(owner, 'riddle', 30, '수수께끼');
            ui.input(owner, '낮에는 숨고 밤에는 빛나는 것은?', (answer) => {
              ui.clearStatus(owner, 'riddle');
              if (answer && answer.includes('별')) { owner.effect('luck', 300, 1); ui.cinematic(owner, {title: '정답이다', style: 'celebrate'}); }
              else ui.say(owner, '아쉽구나. 별은 언제나 거기 있었단다.');
            }, {timeout: 30});
            """;
}
