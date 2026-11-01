package dev.newyear.countdown.ai.agent;

import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class TurnQueueTest {
    final UUID alice=UUID.randomUUID(), bob=UUID.randomUUID();
    TurnRequest request(String wish, UUID player, TurnRequest.Kind kind) { return new TurnRequest(wish,player,kind,"message","event"); }
    @Test void onePlayerCannotOverlapDifferentWishesButOtherPlayersCanRun() {
        var q=new TurnQueue<Object>(); var token=new Object();
        assertTrue(q.start(request("a",alice,TurnRequest.Kind.PLAYER),token));
        assertFalse(q.start(request("b",alice,TurnRequest.Kind.UI),new Object()));
        assertTrue(q.start(request("c",bob,TurnRequest.Kind.PLAYER),new Object()));
        assertTrue(q.busyWish("a")); assertFalse(q.busyWish("b"));
    }
    @Test void backgroundNotificationsKeepOrderKindAndWishAndDoNotBlockOtherPlayers() {
        var q=new TurnQueue<Object>(); var first=request("a",alice,TurnRequest.Kind.PLAYER); var token=new Object();
        q.start(first,token);
        var script=request("b",alice,TurnRequest.Kind.SCRIPT); var ui=request("a",alice,TurnRequest.Kind.UI);
        q.defer(script);q.defer(ui);
        var other=request("c",bob,TurnRequest.Kind.AUTONOMOUS);q.defer(other);
        assertEquals(other,q.nextReady());assertNull(q.nextReady());
        q.finish(first,token);assertEquals(script,q.nextReady());assertEquals(ui,q.nextReady());
    }
    @Test void staleCompletionCannotUnlockReplacementAfterCancel() {
        var q=new TurnQueue<Object>(); var req=request("a",alice,TurnRequest.Kind.PLAYER);
        var old=new Object();var fresh=new Object();q.start(req,old);q.clear();q.start(req,fresh);
        assertFalse(q.finish(req,old));assertTrue(q.current(req,fresh));assertTrue(q.busy(alice));
    }
    @Test void anotherPlayersReplyToTheSameWishWaitsForTheOriginalTurn() {
        var q=new TurnQueue<Object>();var first=request("shared",alice,TurnRequest.Kind.PLAYER);var token=new Object();
        q.start(first,token);
        var reply=request("shared",bob,TurnRequest.Kind.UI);
        assertFalse(q.start(reply,new Object()));assertTrue(q.defer(reply));
        assertNull(q.nextReady()); // must not repeatedly dequeue/requeue a blocked wish
        q.finish(first,token);assertEquals(reply,q.nextReady());
    }
    @Test void playerInputsAreNeverMergedAndDuplicateNotificationsAreCoalesced() {
        var q=new TurnQueue<Object>();var req=request("a",alice,TurnRequest.Kind.PLAYER);
        assertFalse(q.defer(req));
        var script=request("a",alice,TurnRequest.Kind.SCRIPT);q.defer(script);q.defer(script);
        assertEquals(script,q.nextReady());assertNull(q.nextReady());
    }
    @Test void cancellingWishDiscardsOnlyItsQueuedNotifications() {
        var q=new TurnQueue<Object>();var a=request("a",alice,TurnRequest.Kind.SCRIPT);var b=request("b",alice,TurnRequest.Kind.SCRIPT);
        q.defer(a);q.defer(b);q.discardWish("a");assertEquals(b,q.nextReady());assertNull(q.nextReady());
    }
    @Test void notificationQueueIsBoundedPerPlayer() {
        var q=new TurnQueue<Object>();
        for(int i=0;i<8;i++) assertTrue(q.defer(request("w"+i,alice,TurnRequest.Kind.SCRIPT)));
        assertFalse(q.defer(request("overflow",alice,TurnRequest.Kind.SCRIPT)));
        q.clear();assertNull(q.nextReady());
    }
}
