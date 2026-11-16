package dev.jdguo.bank.ledger;

import org.junit.jupiter.api.Test;

final class SettlementRegressionTest {
    @Test void recursiveLedgerAndRecovery() {
        RecursiveClearingRegression.main(new String[0]);
    }
    @Test void snapshotSupplyAndInstitutionAccounts() {
        dev.jdguo.bank.net.PersonalSnapshotRegression.main(new String[0]);
    }
}
