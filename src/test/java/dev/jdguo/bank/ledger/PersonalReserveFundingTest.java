package dev.jdguo.bank.ledger;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.jdguo.bank.currency.BankConfig;
import dev.jdguo.bank.market.NoteRegime;
import dev.jdguo.bank.net.BankUiSnapshot;
import dev.jdguo.bank.text.BankPhrases;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PersonalReserveFundingTest {
    private final UUID issuer = UUID.fromString("abab0000-0000-0000-0000-000000000001");

    @Test
    void diamondSavingsFundTheReserveThatBackedIssueLocks() {
        BankBooks books = BankBooks.create(BankConfig.defaults());
        books.deposit(issuer, "diamond", new BigInteger("8"));
        books.deposit(issuer, "copper", new BigInteger("30"));
        assertTrue(books.createPersonalNote(issuer, "屎币", NoteRegime.FLOAT, "diamond", BigInteger.ONE).success());
        BigDecimal vault = books.supply("diamond").depositVaultExact();
        BigInteger centralReserve = books.supply("diamond").reserve();
        BigInteger noteLiability = books.supply("note").liability();

        TxResult posted = books.postIssuanceReserve(issuer, new BigInteger("5"), true);

        assertTrue(posted.success(), posted.code());
        assertEquals(new BigInteger("5"), books.personalReserve(issuer, "diamond"));
        assertEquals(new BigInteger("3"), books.balance(issuer, "diamond"));
        assertEquals(new BigInteger("30"), books.balance(issuer, "copper"));
        assertEquals(0, vault.compareTo(books.supply("diamond").depositVaultExact()));
        assertEquals(centralReserve, books.supply("diamond").reserve());
        assertEquals(noteLiability, books.supply("note").liability());
        assertEquals(BigInteger.ZERO, books.centralReserve(issuer));
        assertTrue(BankPhrases.personalReserveMoved(books, "diamond", true, new BigInteger("5")).zh().contains("钻石"));

        TxResult refused = books.postIssuanceReserve(issuer, new BigInteger("4"), true);
        assertEquals("funds", refused.code());
        assertTrue(BankPhrases.noteRejected(refused.code()).zh().contains("你的账户余额不足"));
        assertEquals(new BigInteger("5"), books.personalReserve(issuer, "diamond"));
        assertEquals(new BigInteger("3"), books.balance(issuer, "diamond"));

        assertTrue(books.issuePersonalNote(issuer, new BigInteger("4")).success());
        assertEquals(new BigInteger("4"), books.personalNote(issuer).locked());
        TxResult blocked = books.postIssuanceReserve(issuer, new BigInteger("2"), false);
        assertEquals("short_reserve", blocked.code());
        assertTrue(BankPhrases.personalReserveShort(books, "diamond").zh().contains("钻石"));
        assertEquals(new BigInteger("5"), books.personalReserve(issuer, "diamond"));
        assertEquals(new BigInteger("3"), books.balance(issuer, "diamond"));

        assertTrue(books.postIssuanceReserve(issuer, BigInteger.ONE, false).success());
        assertEquals(new BigInteger("4"), books.personalReserve(issuer, "diamond"));
        assertEquals(new BigInteger("4"), books.balance(issuer, "diamond"));

        JsonObject snapshot = JsonParser.parseString(BankUiSnapshot.json(books, issuer, false, "savings", false)).getAsJsonObject();
        JsonObject own = snapshot.getAsJsonObject("ownNote");
        assertEquals("diamond", snapshot.get("reserveCurrency").getAsString());
        assertEquals("钻石", snapshot.get("reserveCurrencyName").getAsString());
        assertEquals("4", snapshot.get("issuanceReserve").getAsString());
        assertEquals("4", snapshot.get("issuanceLocked").getAsString());
        assertEquals("0", snapshot.get("issuanceFree").getAsString());
        assertEquals("0", snapshot.get("reserve").getAsString());
        assertEquals("0", snapshot.get("centralReserve").getAsString());
        assertEquals("diamond", own.get("reserveCurrency").getAsString());
        assertEquals("4", own.get("issuanceReserve").getAsString());
        assertEquals("0", own.get("issuanceFree").getAsString());
    }

    @Test
    void aNotePegStillUsesTheCentralBankNoteReserveAccount() {
        BankBooks books = BankBooks.create(BankConfig.defaults());
        assertTrue(books.createPersonalNote(issuer, "券锚", NoteRegime.PEG, "note", BigInteger.ONE).success());
        assertTrue(books.creditHeld(issuer, "note", new BigInteger("12")).success());
        BigInteger liability = books.supply("note").liability();

        assertTrue(books.depositCentralReserve(issuer, new BigInteger("5")).success());
        assertTrue(books.postIssuanceReserve(issuer, new BigInteger("2"), true).success());

        assertEquals(new BigInteger("7"), books.centralReserve(issuer));
        assertEquals(new BigInteger("5"), books.balance(issuer, "note"));
        assertEquals(BigInteger.ZERO, books.personalReserve(issuer, "diamond"));
        assertEquals(liability, books.supply("note").liability().add(new BigInteger("7")));
        assertTrue(books.withdrawCentralReserve(issuer, new BigInteger("3")).success());
        assertEquals(new BigInteger("4"), books.centralReserve(issuer));
        assertEquals(new BigInteger("8"), books.balance(issuer, "note"));
    }
}
