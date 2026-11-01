package dev.newyear.countdown.ai.script;

import com.google.gson.Gson;
import dev.newyear.countdown.ai.AiState;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class PaymentLedgerTest {
    @Test void repeatedChargeAndRefundAfterRestartTransferOnlyOnce() {
        AiState state=new AiState();AtomicInteger inventory=new AtomicInteger(10);
        assertTrue(PaymentLedger.charge(state.payments,"wish/cost","alice","diamond",3,()->{inventory.addAndGet(-3);return 3;}));
        state=new Gson().fromJson(new Gson().toJson(state),AiState.class);
        assertTrue(PaymentLedger.charge(state.payments,"wish/cost","alice","diamond",3,()->{fail("charged twice");return 3;}));
        assertEquals(3,PaymentLedger.refund(state.payments,"wish/cost","alice",n->{inventory.addAndGet(n);return n;}));
        assertEquals(0,PaymentLedger.refund(state.payments,"wish/cost","alice",n->{fail("refunded twice");return n;}));
        assertFalse(PaymentLedger.charge(state.payments,"wish/cost","alice","diamond",3,()->{fail("recharged refunded payment");return 3;}));
        assertEquals(10,inventory.get());
    }
    @Test void failedChargeCannotBeRefundedButCanBeRetried() {
        var records=new HashMap<String,PaymentLedger.Receipt>();
        assertFalse(PaymentLedger.charge(records,"cost","alice","dirt",64,()->0));
        assertEquals(0,PaymentLedger.refund(records,"cost","alice",n->{fail("invented refund");return n;}));
        assertTrue(PaymentLedger.charge(records,"cost","alice","dirt",64,()->64));
    }
    @Test void refundRequiresTheOriginalOwnerAndTermsCannotChange() {
        var records=new HashMap<String,PaymentLedger.Receipt>();PaymentLedger.charge(records,"cost","alice","diamond",3,()->3);
        assertEquals(0,PaymentLedger.refund(records,"cost","bob",n->{fail();return n;}));
        assertThrows(IllegalArgumentException.class,()->PaymentLedger.charge(records,"cost","alice","diamond",30,()->30));
    }
    @Test void partialRefundLeavesOnlyTheUnreturnedBalance() {
        var records=new HashMap<String,PaymentLedger.Receipt>();PaymentLedger.charge(records,"cost","alice","diamond",3,()->3);
        assertEquals(1,PaymentLedger.refund(records,"cost","alice",n->1));
        assertEquals(2,PaymentLedger.refund(records,"cost","alice",n->n));
    }
    @Test void simulationCopiesCannotChangeSavedReceipts() {
        var records=new HashMap<String,PaymentLedger.Receipt>();PaymentLedger.charge(records,"cost","alice","diamond",3,()->3);
        var dry=new HashMap<String,PaymentLedger.Receipt>();records.forEach((k,v)->dry.put(k,v.copy()));
        PaymentLedger.refund(dry,"cost","alice",n->n);assertEquals(0,records.get("cost").refunded);
    }
}
