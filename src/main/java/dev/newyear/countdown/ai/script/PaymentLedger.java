package dev.newyear.countdown.ai.script;

import java.util.Map;
import java.util.function.IntSupplier;

/** Server-thread receipts. Retried charges and refunds never repeat a completed transfer. */
public final class PaymentLedger {
    private PaymentLedger() {}
    public static final class Receipt {
        public String id, owner, item;
        public int requested, paid, refunded;
        public long created;
        public Receipt copy() {
            Receipt r = new Receipt();
            r.id=id; r.owner=owner; r.item=item; r.requested=requested; r.paid=paid; r.refunded=refunded; r.created=created;
            return r;
        }
        public boolean charged() { return paid == requested && refunded == 0; }
    }

    public static boolean charge(Map<String, Receipt> records, String id, String owner, String item, int count, IntSupplier take) {
        Receipt prior = records.get(id);
        if (prior != null) {
            if (!owner.equals(prior.owner) || !item.equals(prior.item) || count != prior.requested)
                throw new IllegalArgumentException("Payment key already used with different terms");
            return prior.charged();
        }
        if (count < 1 || count > 2304) throw new IllegalArgumentException("Payment count must be 1..2304");
        if (records.size() >= 100_000) throw new IllegalArgumentException("Payment receipt storage is full");
        int paid = take.getAsInt();
        if (paid <= 0) return false;
        Receipt r = new Receipt();
        r.id=id; r.owner=owner; r.item=item; r.requested=count; r.paid=paid; r.created=System.currentTimeMillis();
        records.put(id, r);
        return r.charged();
    }

    public static int refund(Map<String, Receipt> records, String id, String owner, java.util.function.IntUnaryOperator give) {
        Receipt r = records.get(id);
        if (r == null || !owner.equals(r.owner)) return 0;
        int remaining = r.paid - r.refunded;
        if (remaining <= 0) return 0;
        int n = give.applyAsInt(remaining);
        if (n < 0 || n > remaining) throw new IllegalStateException("Invalid refund transfer count");
        r.refunded += n;
        return n;
    }
}
