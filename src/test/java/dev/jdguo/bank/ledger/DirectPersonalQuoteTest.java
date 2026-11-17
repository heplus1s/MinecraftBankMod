package dev.jdguo.bank.ledger;

import dev.jdguo.bank.currency.BankConfig;
import dev.jdguo.bank.market.NoteRegime;
import org.junit.jupiter.api.Test;
import java.math.BigInteger;
import java.math.BigDecimal;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class DirectPersonalQuoteTest {
    @Test void noteCollateralIsQuotedDirectlyAndQuoteDoesNotMutateBalances() {
        BankBooks books = BankBooks.create(BankConfig.defaults());
        UUID issuer = UUID.randomUUID();
        books.creditHeld(issuer, "note", BigInteger.valueOf(100));
        assertTrue(books.createPersonalNote(issuer, "直", NoteRegime.FLOAT, "diamond", BigInteger.ONE).success());
        assertTrue(books.issuePersonalNoteUnbacked(issuer, BigInteger.TEN).success());
        assertTrue(books.switchPersonalReserve(issuer, "note").success());
        assertTrue(books.depositPersonalReserveExact(issuer, new BigDecimal("20")).success());
        String before = books.toJson();
        assertEquals(0, books.personalCentralPrice(books.personalNote(issuer)).compareTo(new BigDecimal("2")));
        assertEquals(before, books.toJson());
    }
}
