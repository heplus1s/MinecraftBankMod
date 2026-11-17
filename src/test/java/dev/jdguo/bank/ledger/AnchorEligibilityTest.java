package dev.jdguo.bank.ledger;
import dev.jdguo.bank.currency.BankConfig;
import dev.jdguo.bank.market.*;
import java.math.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class AnchorEligibilityTest {
    private PersonalNote make(BankBooks b,String name) {
        UUID owner=UUID.randomUUID();
        assertTrue(b.createPersonalNote(owner,name,NoteRegime.FLOAT,"diamond",BigInteger.ONE).success());
        return b.personalNote(owner);
    }
    @Test void creationPhysicalOnlyAndExistingCurrenciesSurvive() {
        BankBooks b=BankBooks.create(BankConfig.defaults()); PersonalNote a=make(b,"甲");
        for(String anchor:List.of("note","personal:"+a.id())) {
            String before=b.toJson();
            assertFalse(b.createPersonalNote(UUID.randomUUID(),"乙",NoteRegime.FLOAT,anchor,BigInteger.ONE).success());
            assertEquals(before,b.toJson());
        }
        assertTrue(b.createPersonalNote(UUID.randomUUID(),"铜",NoteRegime.PEG,"copper",BigInteger.ONE).success());
        a.adopt(NoteRegime.FLOAT,"note",null); // Existing pre-rule save remains valid.
        BankBooks loaded=BankBooks.fromJson(b.toJson(),BankConfig.defaults());
        assertEquals("note",loaded.personalNoteById(a.id()).anchor());
    }
    @Test void everySwitchEntryRejectsTransitiveCyclesButSharedBranchesAreAllowed() {
        BankBooks b=BankBooks.create(BankConfig.defaults());
        PersonalNote a=make(b,"甲"),c=make(b,"丙"),d=make(b,"丁"),e=make(b,"戊");
        assertTrue(b.switchPersonalReserve(c.issuer(),"personal:"+a.id()).success());
        assertTrue(b.switchPersonalReserve(d.issuer(),"personal:"+c.id()).success());
        assertTrue(b.personalReserveEligibility(e.id(),"personal:"+c.id()).allowed());
        assertTrue(b.switchPersonalReserve(e.issuer(),"personal:"+c.id()).success());
        for(PersonalNote candidate:List.of(a,c,d,e)) {
            String before=b.toJson();
            assertEquals("cycle",b.personalReserveEligibility(a.id(),"personal:"+candidate.id()).code());
            assertFalse(b.switchPersonalReserve(a.issuer(),"personal:"+candidate.id()).success());
            assertFalse(b.switchPersonalRegime(a.issuer(),NoteRegime.PEG,"personal:"+candidate.id()).allowed());
            assertEquals(before,b.toJson());
        }
        assertTrue(b.personalReserveEligibility(a.id(),"note").allowed());
        assertEquals("missing_or_closed",b.personalReserveEligibility(a.id(),"personal:"+UUID.randomUUID()).code());
        // An unrelated pre-existing cyclic component cannot be adopted either.
        c.adopt(NoteRegime.FLOAT,"personal:"+d.id(),null);
        assertEquals("cycle",b.personalReserveEligibility(a.id(),"personal:"+c.id()).code());
    }
    @Test void excessiveLegacyDepthIsRejectedWithoutChangingExistingGraph() {
        BankBooks b=BankBooks.create(BankConfig.defaults()); PersonalNote root=make(b,"根");
        String anchor="diamond";
        for(int i=0;i<33;i++) {
            PersonalNote next=make(b,"深"+(char)('A'+i/26)+(char)('A'+i%26));
            next.adopt(NoteRegime.FLOAT,anchor,null); anchor="personal:"+next.id();
        }
        String before=b.toJson();
        assertEquals("depth",b.personalReserveEligibility(root.id(),anchor).code());
        assertFalse(b.switchPersonalReserve(root.issuer(),anchor).success());
        assertEquals(before,b.toJson());
    }
    @Test void renamePreservesIdentityMoneyAndReferencesAndRejectsUnauthorizedOrDuplicate() {
        BankBooks b=BankBooks.create(BankConfig.defaults()); PersonalNote a=make(b,"甲"),z=make(b,"ABC");
        b.issuePersonalNoteUnbacked(a.issuer(),BigInteger.TEN);
        b.listPersonal(a.issuer(),a.name(),BigInteger.valueOf(3));
        UUID id=a.id();
        z.adopt(NoteRegime.FLOAT,"personal:"+id,null);
        assertFalse(b.renamePersonalNote(z.issuer(),id,"新名",false).success());
        assertFalse(b.renamePersonalNote(a.issuer(),id,"abc",false).success());
        for(String reserved:List.of("央行券","钻石")) {
            assertEquals("reserved_name",b.renamePersonalNote(a.issuer(),id,reserved,false).code());
            assertEquals("reserved_name",b.createPersonalNote(UUID.randomUUID(),reserved,NoteRegime.FLOAT,"diamond",BigInteger.ONE).code());
        }
        assertFalse(b.renamePersonalNote(a.issuer(),id,"四个汉字",false).success());
        assertTrue(b.renamePersonalNote(a.issuer(),id,"新名",false).success());
        assertEquals(id,b.personalNoteByName("新名").id()); assertNull(b.personalNoteByName("甲"));
        assertEquals(BigInteger.TEN,b.personalHolding(id,a.issuer()));
        assertEquals(BigInteger.valueOf(3),b.personalOffered(id,a.issuer()));
        BankBooks loaded=BankBooks.fromJson(b.toJson(),BankConfig.defaults());
        assertEquals(id,loaded.personalNoteByName("新名").id());
        assertEquals("personal:"+id,loaded.personalNoteById(z.id()).anchor());
        assertTrue(loaded.renamePersonalNote(z.issuer(),id,"央",true).success());
        loaded.checkInvariants();
    }
}
