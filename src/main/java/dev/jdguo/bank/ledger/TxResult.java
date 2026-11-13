package dev.jdguo.bank.ledger;

import java.math.BigInteger;
import java.util.UUID;

public record TxResult(boolean success, String code, String message, BigInteger amount, UUID instrumentId) {
    public static TxResult ok(BigInteger amount, String message) {
        return new TxResult(true, "ok", message, amount, null);
    }

    public static TxResult okInstrument(BigInteger amount, String message, UUID id) {
        return new TxResult(true, "ok", message, amount, id);
    }

    public static TxResult fail(String code, String message) {
        return new TxResult(false, code, message, BigInteger.ZERO, null);
    }
}
