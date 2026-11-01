package dev.newyear.countdown.ai.agent;

import java.util.*;

/** Server-thread-only ownership. A player's turns cannot overlap, even across different wishes. */
public final class TurnQueue<T> {
    public record Active<T>(TurnRequest request, T token) {}
    private final Map<String, Active<T>> active = new LinkedHashMap<>();
    private final Deque<TurnRequest> pending = new ArrayDeque<>();

    private static String key(TurnRequest r) {
        return r.player() != null ? "player:" + r.player() : "wish:" + r.wishId();
    }
    public boolean busy(UUID player) { return player != null && active.containsKey("player:" + player); }
    public boolean busyWish(String id) { return id != null && active.values().stream().anyMatch(a -> id.equals(a.request.wishId())); }
    public Collection<Active<T>> active() { return List.copyOf(active.values()); }
    private boolean available(TurnRequest r) { return !active.containsKey(key(r)) && !busyWish(r.wishId()); }
    public boolean start(TurnRequest r, T token) {
        if (!available(r)) return false;
        active.put(key(r), new Active<>(r, token));
        return true;
    }
    public boolean current(TurnRequest r, T token) {
        Active<T> a = active.get(key(r));
        return a != null && a.token == token;
    }
    public boolean finish(TurnRequest r, T token) {
        if (!current(r, token)) return false;
        active.remove(key(r));
        return true;
    }
    /** Player messages are rejected, never silently merged. System events retain their original kind and wish. */
    public boolean defer(TurnRequest r) {
        if (r.kind() == TurnRequest.Kind.PLAYER) return false;
        if (pending.contains(r)) return true;
        if (pending.size() >= 64 || pending.stream().filter(p -> key(p).equals(key(r))).count() >= 8) return false;
        pending.addLast(r);
        return true;
    }
    public TurnRequest nextReady() {
        for (Iterator<TurnRequest> i = pending.iterator(); i.hasNext();) {
            TurnRequest r = i.next();
            if (available(r)) { i.remove(); return r; }
        }
        return null;
    }
    public void discardWish(String wishId) { pending.removeIf(r -> Objects.equals(r.wishId(), wishId)); }
    public void clear() { active.clear(); pending.clear(); }
}
