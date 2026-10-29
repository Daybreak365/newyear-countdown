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

    public static final String TOPICS = "overview, events, world, players, player, entities, entity, fx, ui, quests, quest, memory, misc, examples";

    public static String topic(String t) {
        ScriptValidator.primeDocs();
        String key = t == null ? "overview" : t.trim().toLowerCase();
        return switch (key) {
            case "overview" -> OVERVIEW;
            case "events" -> events();
            case "player" -> table("Player handle (e.g. owner, e.player). Properties: name, uuid, isOwner.", PlayerRef.DOCS);
            case "entity" -> table("Entity handle (non-player). Properties: uuid, type, name. Behaviours (follow/orbit/stay) persist across restarts.", EntityRef.DOCS);
            case "quest" -> table("Quest handle from quests.create / quests.get. Property: id, title.", QuestRef.DOCS);
            case "misc", "timers" -> MISC;
            case "examples" -> EXAMPLES;
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
            Prefer the quest id from create_quest when the quest was created by the tool: quests.get('q-…').
            Limits per handler run: ~150k steps, 25 ms; max 24 listeners, 32 timers, 24 entities, 512 blocks per script.
            Positions are {x, y, z} objects (arrays [x,y,z] and handles also accepted). Item/entity/block ids accept 'diamond' or 'minecraft:diamond'.
            Globals: owner, state, world, players, entities, fx, ui, quests, memory, wish, script, ai,
                     on/once/off/emit, after/every/cancel/timer/cancelTimer, region/removeRegion,
                     now, tick, random, randomInt, chance, pick, shuffle, pos, offset, distance, seconds, minutes, log,
                     plus Math, JSON, Object, Array, Number, String, parseInt, parseFloat, console.
            Topics for details: events, world, players, player, entities, entity, fx, ui, quests, quest, memory, misc, examples.
            """;

    private static final String MISC = """
            Timers: after(ticks, fn) → id; every(ticks, fn) → id (min 2 ticks); cancel(id).
              These do not survive restarts by themselves — re-create them in top-level code (it re-runs on restore).
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

            // 5) Riddle with a 30 s limit, all local
            ui.timer(owner, 'riddle', 30, '수수께끼');
            ui.input(owner, '낮에는 숨고 밤에는 빛나는 것은?', (answer) => {
              ui.clearStatus(owner, 'riddle');
              if (answer && answer.includes('별')) { owner.effect('luck', 300, 1); ui.cinematic(owner, {title: '정답이다', style: 'celebrate'}); }
              else ui.say(owner, '아쉽구나. 별은 언제나 거기 있었단다.');
            }, {timeout: 30});
            """;
}
