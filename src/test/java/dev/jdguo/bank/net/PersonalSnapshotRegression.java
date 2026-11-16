package dev.jdguo.bank.net;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.jdguo.bank.currency.BankConfig;
import dev.jdguo.bank.ledger.BankBooks;
import java.math.BigInteger;
import java.util.UUID;

/** Dependency-light executable regression; also usable when Loom is unavailable. */
public final class PersonalSnapshotRegression {
    private static void equal(JsonObject row, String key, String wanted) {
        String actual = row.get(key).getAsString();
        if (!wanted.equals(actual)) throw new AssertionError(key + ": " + actual + " != " + wanted);
    }
    private static void verify(BankBooks books, UUID issuer) {
        JsonObject row = JsonParser.parseString(BankUiSnapshot.json(books, issuer, false, "personal", false))
                .getAsJsonObject().getAsJsonObject("ownNote");
        equal(row, "m0", "3");
        equal(row, "m1", "10");
        equal(row, "m2", "10");
        equal(row, "holding", "7");
        equal(row, "reserveAmount", "25");
        equal(row, "reserveCurrency", "diamond");
    }
    public static void main(String[] args) {
        UUID issuer = UUID.randomUUID();
        BankBooks books = BankBooks.create(BankConfig.defaults());
        books.createPersonalNote(issuer, "泉", false, BigInteger.ONE);
        if (!books.issuePersonalNoteUnbacked(issuer, BigInteger.TEN).success()) throw new AssertionError("issue");
        books.setPersonalReserve(issuer, "diamond", BigInteger.valueOf(25));
        if (!books.issuePersonalPaper(issuer, books.personalNote(issuer).id(), BigInteger.valueOf(3)).result().success())
            throw new AssertionError("paper");
        verify(books, issuer);
        verify(BankBooks.fromJson(books.toJson(), BankConfig.defaults()), issuer);
        // Fractional shop proceeds stay visible in each original currency.
        books.creditHeld(issuer, "note", new java.math.BigDecimal("0.125"));
        if (!books.transferShop(issuer, "note", new java.math.BigDecimal("0.125"), true).success())
            throw new AssertionError("central shop transfer");
        if (!books.transferShop(issuer, "personal:" + books.personalNote(issuer).id(),
                new java.math.BigDecimal("0.5"), true).success()) throw new AssertionError("personal shop transfer");
        String snapshot = BankUiSnapshot.json(books, issuer, true, "admin", false);
        if (!snapshot.contains("银行券  0.125") || !snapshot.contains("泉  0.5"))
            throw new AssertionError("shop fractional subaccounts missing: " + snapshot);
        System.out.println("PASS: personal snapshot totals, paper no-double-count, reserves separate, reload");
    }
}
