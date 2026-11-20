package dev.jdguo.bank.ledger;

import com.google.gson.JsonParser;
import dev.jdguo.bank.currency.BankConfig;
import dev.jdguo.bank.market.NoteRegime;
import dev.jdguo.bank.net.BankUiSnapshot;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class CentralPledgeIssuanceTest {
    private static final UUID ISSUER = new UUID(0, 1);
    private static final UUID BUYER = new UUID(0, 2);
    private static BigDecimal d(String v) { return new BigDecimal(v); }
    private static void eq(String v, BigDecimal actual) { assertEquals(0, d(v).compareTo(actual)); }
    private static void ok(TxResult r) { assertTrue(r.success(), r.code()); }
    private static BankBooks bank() {
        BankBooks b = BankBooks.create(BankConfig.defaults());
        for (String c : new String[]{"note","copper","diamond"}) b.setAlpha(c, BigDecimal.ZERO);
        b.setReserve("diamond", BigInteger.valueOf(1000));
        b.setReserve("copper", BigInteger.ZERO);
        ok(b.creditUnbacked(ISSUER, "note", BigInteger.valueOf(1000)));
        return b;
    }

    @Test void lockedNotesCountExactlyOnceAcrossDepositReloadReleaseAndRetirement() {
        BankBooks b = bank();
        ok(b.transferTreasury(ISSUER, "note", d("250.5000"), false));
        assertTrue(b.createPersonalNote(ISSUER, "测试币", NoteRegime.FLOAT, "diamond", BigInteger.ONE).success());
        ok(b.switchPersonalReserve(ISSUER, "note"));
        ok(b.depositPersonalReserveExact(ISSUER, d("200.1250")));
        // Old stored liability is still the original outside-pledge bucket. The new total is derived.
        eq("799.8750", b.supply("note").liabilityExact());
        eq("1000", b.centralIssuedExact()); eq("200.1250", b.centralPledgedNotesExact());
        eq("50.3750", b.centralM1Exact()); eq("1", b.unitsPer("diamond","note"));
        eq("1000", b.supply("diamond").reserveExact()); eq("0", b.supply("copper").reserveExact());
        assertTrue(b.audit().contains("note issued=1000 accounted=1000 ok=true"), b.audit());
        String legacyWire = b.toJson();
        for (int i=0;i<3;i++) {
            b = BankBooks.fromJson(legacyWire, BankConfig.defaults());
            eq("1000", b.centralIssuedExact()); eq("1", b.unitsPer("diamond","note"));
            assertEquals(legacyWire, b.toJson());
        }
        var snapshot = JsonParser.parseString(BankUiSnapshot.json(b, ISSUER, true, "management", false)).getAsJsonObject();
        var rows = snapshot.getAsJsonArray("central");
        var note = java.util.stream.StreamSupport.stream(rows.spliterator(),false)
                .map(x -> x.getAsJsonObject()).filter(row -> row.get("id").getAsString().equals("note")).findFirst().orElseThrow();
        eq("1000", note.get("liability").getAsBigDecimal()); eq("200.125", note.get("pledgedNotes").getAsBigDecimal());
        var p = CentralAdjustment.preview(b,"quantity","decrease","200");
        assertTrue(p.allowed(),p.reason()); eq("1000",p.liabilitiesBefore()); eq("800",p.liabilitiesAfter());
        ok(p.confirm(b)); eq("1.25",b.unitsPer("diamond","note"));
        ok(b.withdrawPersonalReserveExact(ISSUER, d("200.125")));
        eq("0",b.centralPledgedNotesExact()); eq("800",b.centralIssuedExact()); eq("1.25",b.unitsPer("diamond","note"));
        eq("250.5000",b.balanceExact(ISSUER,"note")); eq("1000",b.supply("diamond").reserveExact());
        b.checkInvariants();
    }

    @Test void centralizedSettlementMovesPledgeToSystemWithoutMintingOrBurning() {
        BankBooks b = bank();
        ok(b.transferTreasury(ISSUER, "note", d("10"), false));
        assertTrue(b.createPersonalNote(ISSUER,"测试币",NoteRegime.FLOAT,"diamond",BigInteger.ONE).success());
        ok(b.switchPersonalReserve(ISSUER,"note"));
        ok(b.depositPersonalReserveExact(ISSUER,d("3")));
        assertTrue(b.issuePersonalNoteUnbacked(ISSUER,BigInteger.TEN).success());
        var id=b.personalNote(ISSUER).id();
        ok(b.transferPersonalExact(ISSUER,BUYER,"personal:"+id,d("1")));
        ok(b.settlePersonalToSystem(BUYER,id,d("1"),"shop","pledge-test"));
        ok(b.settlePersonalToSystem(BUYER,id,d("1"),"shop","pledge-test"));
        eq("2.7",b.centralPledgedNotesExact()); eq("0.3",b.systemSettlementBalance("shop","note"));
        eq("1000",b.centralIssuedExact()); eq("1",b.unitsPer("diamond","note"));
        ok(b.reversePersonalSettlement("pledge-test"));
        eq("3",b.centralPledgedNotesExact()); eq("0",b.systemSettlementBalance("shop","note"));
        eq("1000",b.centralIssuedExact());
        b.checkInvariants();
    }

    @Test void fractionalDiamondClearingIsAnExistingCustodyClaim() {
        BankBooks b = bank();
        ok(b.deposit(ISSUER,"diamond",BigInteger.TEN));
        assertTrue(b.createPersonalNote(ISSUER,"测试币",NoteRegime.FLOAT,"diamond",BigInteger.ONE).success());
        ok(b.depositPersonalReserveExact(ISSUER,d("3")));
        assertTrue(b.issuePersonalNoteUnbacked(ISSUER,BigInteger.TEN).success());
        var id=b.personalNote(ISSUER).id();
        var stock=b.commodityHoldingsExact("diamond");
        ok(b.transferPersonalExact(ISSUER,BUYER,"personal:"+id,d("1")));
        ok(b.settlePersonalToSystem(BUYER,id,d("1"),"shop","diamond-test"));
        eq("2.7",b.effectiveBackingExact(b.personalNote(ISSUER)));
        eq("0.3",b.systemSettlementBalance("shop","diamond"));
        eq("10",b.custodyExact("diamond")); eq("1000",b.supply("diamond").reserveExact());
        assertEquals(stock,b.commodityHoldingsExact("diamond")); eq("1000",b.centralIssuedExact());
        b.checkInvariants();
    }
}
