package dev.jdguo.bank.instruments;

import java.math.BigInteger;
import java.util.UUID;

/** What the server read off a vanilla item. Compared with the redemption registry. */
public record PresentedCheck(UUID id, String currency, BigInteger amount, UUID issuer, UUID payee) {
}
