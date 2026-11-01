package dev.newyear.countdown.ai.script;

import java.util.LinkedHashMap;
import java.util.Map;

/** 스크립트가 구독할 수 있는 게임 이벤트와 페이로드 설명 (AI 문서와 검증에 같이 쓴다). */
public final class EventTypes {
    private EventTypes() {}

    public static final Map<String, String> ALL = new LinkedHashMap<>();

    static {
        ALL.put("before_damage", "{target, player? (victim), attacker, amount, cause}; RETURN {cancel:true} or {amount:number 0..40}. Synchronous, scoped to owner/consent. No recursive interception.");
        ALL.put("before_death", "{target, player?, cause, pos}; RETURN {cancel:true} to prevent this death (minimum 1 health). Runs before drops.");
        ALL.put("before_item_use", "{player,item,hand}; RETURN {cancel:true} to consume this interaction instead of vanilla use.");
        ALL.put("before_attack", "{player,target}; RETURN {cancel:true} to cancel the default melee action.");
        ALL.put("before_block_break", "{player,block,pos}; RETURN {cancel:true} to cancel breaking before drops.");
        ALL.put("ability_input", "{player,action,pressed}; registered client control pressed/released; filtered by script and player.");
        ALL.put("projectile_hit", "{id,owner,target|null,pos}; script projectile collided with a block or entity.");
        ALL.put("player_join", "{player}");
        ALL.put("player_leave", "{player}");
        ALL.put("chat", "{player, message}");
        ALL.put("jump", "{player, pos}");
        ALL.put("move", "{player, from, to} (sampled every 10 ticks when the player moved > 0.5 blocks)");
        ALL.put("item_use", "{player, item:{id,count,name,special,data}, hand}");
        ALL.put("item_pickup", "{player, item, count, thrower: Player|null}");
        ALL.put("item_give", "{from: Player, to: Player, item, count} (an item thrown by one player was picked up by another)");
        ALL.put("item_drop", "{player, item, count}");
        ALL.put("block_place", "{player, block, pos}");
        ALL.put("block_break", "{player, block, pos}");
        ALL.put("attack", "{player, target: Entity|Player}");
        ALL.put("kill", "{killer: Player|Entity|null, victim: Entity|Player, victimType, cause}");
        ALL.put("player_death", "{player, cause, killer, pos}");
        ALL.put("respawn", "{player}");
        ALL.put("interact_entity", "{player, entity, hand, item}");
        ALL.put("region_enter", "{player, region}");
        ALL.put("region_leave", "{player, region}");
        ALL.put("effect_added", "{player|entity, effect, amplifier, seconds}");
        ALL.put("effect_removed", "{player|entity, effect}");
        ALL.put("dawn", "{day} (time reached sunrise)");
        ALL.put("dusk", "{day} (time reached sunset)");
        ALL.put("night", "{day}");
        ALL.put("noon", "{day}");
        ALL.put("timer", "{name, data} (named timers created with timer(name, ticks, data); survive restarts)");
        ALL.put("custom", "{name, data, source} (emit(name, data) from any script)");
        ALL.put("quest_completed", "{quest, player}");
        ALL.put("quest_failed", "{quest, player, reason}");
        ALL.put("quest_progress", "{quest, objective, progress, target}");
        ALL.put("wish_state", "{wish, state}");
        ALL.put("ui_response", "{player, interaction, value} (any UI answer belonging to this script's wish)");
        ALL.put("world_edit_done", "{id, kind, placed, unchanged, skipped, total, complete} a gradual change finished. unchanged cells already matched. complete means no skipped cells; placed alone need not equal total. Verify final blocks for construction");
    }
}
