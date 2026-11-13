package dev.jdguo.bank.instruments;

import dev.jdguo.bank.ledger.TxResult;

import java.math.BigInteger;

/** A withdrawal of stackable paper. {@code sheets} items of {@code face} each, identical NBT. */
public record PaperTake(TxResult result, BigInteger face, BigInteger sheets) {
    public static PaperTake fail(String code, String message) {
        return new PaperTake(TxResult.fail(code, message), BigInteger.ZERO, BigInteger.ZERO);
    }

    public static PaperTake ok(BigInteger value, BigInteger face, BigInteger sheets, String message) {
        return new PaperTake(TxResult.ok(value, message), face, sheets);
    }
}
