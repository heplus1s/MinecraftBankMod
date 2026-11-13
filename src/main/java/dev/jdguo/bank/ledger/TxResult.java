package dev.jdguo.bank.ledger;

import java.math.BigInteger;
import java.util.UUID;

public record TxResult(boolean success, String code, String message, BigInteger amount, UUID instrumentId,
                       BigInteger copperPaid) {
    public TxResult {
        if (copperPaid == null) {
            copperPaid = BigInteger.ZERO;
        }
    }

    public static TxResult ok(BigInteger amount, String message) {
        return new TxResult(true, "ok", message, amount, null, BigInteger.ZERO);
    }

    public static TxResult ok(BigInteger amount, BigInteger copperPaid, String message) {
        return new TxResult(true, "ok", message, amount, null, copperPaid);
    }

    public static TxResult okInstrument(BigInteger amount, String message, UUID id) {
        return new TxResult(true, "ok", message, amount, id, BigInteger.ZERO);
    }

    public static TxResult fail(String code, String message) {
        return new TxResult(false, code, message, BigInteger.ZERO, null, BigInteger.ZERO);
    }
}
