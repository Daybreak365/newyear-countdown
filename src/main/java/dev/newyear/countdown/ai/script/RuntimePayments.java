package dev.newyear.countdown.ai.script;

import dev.newyear.countdown.ai.WishEngine;
import dev.newyear.countdown.ai.script.api.*;
import dev.newyear.countdown.ai.script.lang.Values.JsObject;
import net.minecraft.world.item.ItemStack;
import java.util.*;

/** Contract item payments with persistent, owner-scoped receipts and isolated validation. */
public final class RuntimePayments {
    private RuntimePayments() {}
    public static ApiObject api(ScriptInstance inst) {
        ApiObject api = new ApiObject("payments");
        Map<String, PaymentLedger.Receipt> simulated = new LinkedHashMap<>();
        if (inst.dry && WishEngine.get() != null)
            WishEngine.get().state().payments.forEach((k,v) -> { if (Objects.equals(v.owner,inst.rec.owner)) simulated.put(k,v.copy()); });
        java.util.function.Supplier<Map<String, PaymentLedger.Receipt>> records = () -> inst.dry ? simulated : WishEngine.get().state().payments;
        api.fn("charge", "(key, item, count) → boolean. Owner's ordinary items only. Stable key per payment; retries never charge twice. Refunded keys cannot be charged again", (in,s,a) -> {
            if (inst.rec.owner == null) throw in.error("Payments need a player owner");
            String key = Args.str(in,a,0,"payment key");
            if (!key.matches("[a-zA-Z0-9_.-]{1,80}")) throw in.error("Payment key must be 1..80 letters, digits, _, . or -");
            String item = McUtil.parseId(in,Args.str(in,a,1,"item"),"item").toString();
            double count = Args.num(in,a,2,"count");
            if (count < 1 || count > 2304 || count != Math.floor(count)) throw in.error("Payment count must be an integer 1..2304");
            // Resolve and validate the item before admitting a simulated or real payment.
            McUtil.makeStack(in,item,1,new JsObject(),inst.rec.id);
            String id = prefix(inst) + "/" + key;
            try {
                return PaymentLedger.charge(records.get(),id,inst.rec.owner,item,(int)count,() -> {
                    if (inst.dry) { inst.effect("charge receipt " + id + ": " + (int)count + " " + item); return (int)count; }
                    var player = WishEngine.get().player(UUID.fromString(inst.rec.owner));
                    if (player == null) return 0;
                    java.util.function.Predicate<ItemStack> match = stack -> McUtil.itemId(stack).equals(item) && stack.getComponentsPatch().isEmpty();
                    int have = player.getInventory().clearOrCountMatchingItems(match,true,0,player.inventoryMenu.getCraftSlots());
                    if (have < count) return 0;
                    return player.getInventory().clearOrCountMatchingItems(match,false,(int)count,player.inventoryMenu.getCraftSlots());
                });
            } catch (IllegalArgumentException e) { throw in.error(e.getMessage()); }
        });
        api.fn("refund", "(keyOrReceiptId) → number returned. Only recorded payments by this owner; missing/already refunded receipts return 0. Full receipt ID works from a later wish", (in,s,a) -> {
            if (inst.rec.owner == null) throw in.error("Payments need a player owner");
            String key = Args.str(in,a,0,"receipt");
            String id = key.contains("/") ? key : prefix(inst) + "/" + key;
            PaymentLedger.Receipt receipt = records.get().get(id);
            return (double)PaymentLedger.refund(records.get(),id,inst.rec.owner,n -> {
                if (inst.dry) { inst.effect("refund receipt " + id + ": " + n); return n; }
                var player = WishEngine.get().player(UUID.fromString(inst.rec.owner));
                if (player == null) return 0;
                ItemStack proto = McUtil.makeStack(in,receipt.item,1,new JsObject(),inst.rec.id);
                int left=n;
                while (left>0) {
                    int amount=Math.min(left,proto.getMaxStackSize());
                    ItemStack stack=proto.copyWithCount(amount);
                    if (!player.getInventory().add(stack)) player.drop(stack,false,net.minecraft.util.Prediction.SERVER_ONLY);
                    left-=amount;
                }
                return n;
            });
        });
        api.fn("get", "(keyOrReceiptId) → {id,item,requested,paid,refunded,charged} or null. A quest objective is not proof of payment", (in,s,a) -> {
            String key=Args.str(in,a,0,"receipt");
            var r=records.get().get(key.contains("/") ? key : prefix(inst)+"/"+key);
            if(r==null || !Objects.equals(r.owner,inst.rec.owner)) return null;
            return new JsObject().put("id",r.id).put("item",r.item).put("requested",(double)r.requested)
                    .put("paid",(double)r.paid).put("refunded",(double)r.refunded).put("charged",r.charged());
        });
        return api;
    }
    private static String prefix(ScriptInstance inst) { return inst.rec.wishId == null ? inst.rec.id : inst.rec.wishId; }
}
