package dev.jdguo.bank.market;

/** Exchange-rate rule for one personal note. The issuer may switch when the target rule's reserves are already in place. */
public enum NoteRegime {
    /** P = P0 × strength × flow. Redemption follows the live quote. */
    FLOAT,
    /** Official quote is a fixed peg. Redemption pays the peg or it is capped. */
    PEG,
    /** Face amount of the backing commodity. No premium, and only that commodity is paid. */
    HARD
}
