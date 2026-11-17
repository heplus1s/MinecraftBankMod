package dev.jdguo.bank.ledger;

import java.math.BigDecimal;
import java.math.BigInteger;

/** Server calculated, immutable quote. Quantity always denotes whole physical items. */
public record HardExchangeQuote(String pay, String receive, BigInteger quantity,
                                BigDecimal debit, BigDecimal credit, BigDecimal rate,
                                BigDecimal feeFraction) {}
