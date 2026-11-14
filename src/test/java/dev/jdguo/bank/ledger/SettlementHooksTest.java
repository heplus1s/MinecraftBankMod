package dev.jdguo.bank.ledger;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.jdguo.bank.api.MarketSettlement;
import dev.jdguo.bank.currency.BankConfig;
import dev.jdguo.bank.market.ShopOperatingAccount;
import dev.jdguo.bank.net.BankUiSnapshot;
import dev.jdguo.bank.persistence.BankRuntime;
import dev.jdguo.bank.persistence.BankWorldState;
import dev.jdguo.bank.text.BankPhrases;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SettlementHooksTest {
    private final UUID buyer = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbb1");
    private final UUID seller = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbb2");

    @AfterEach
    void detachLedger() {
        BankRuntime.state = null;
    }

    @Test
    void savingsTransferPaysTheSellerInsideThePool() {
        BankBooks books = funded();
        books.creditUnbacked(buyer, "note", new BigInteger("80"));
        books.deposit(buyer, "copper", new BigInteger("50"));
        books.setPersonalReserve(buyer, "diamond", new BigInteger("25"));
        Issuance before = Issuance.capture(books, buyer);
        BigInteger pool = books.savingsPool("note");
        BigInteger copperPool = books.savingsPool("copper");

        TxResult moved = books.transferSavings(buyer, seller, "note", new BigInteger("30"));
        TxResult copper = books.transferSavings(buyer, seller, "copper", new BigInteger("20"));

        assertTrue(moved.success(), moved.code());
        assertTrue(copper.success(), copper.code());
        assertEquals(new BigInteger("50"), books.balance(buyer, "note"));
        assertEquals(new BigInteger("30"), books.balance(seller, "note"));
        assertEquals(new BigInteger("30"), books.balance(buyer, "copper"));
        assertEquals(new BigInteger("20"), books.balance(seller, "copper"));
        assertEquals(BigInteger.ZERO, books.shopOperating("note"));
        assertEquals(BigInteger.ZERO, books.landGrant("note"));
        assertEquals(BigInteger.ZERO, books.landGrant("copper"));
        assertEquals(pool, books.savingsPool("note"));
        assertEquals(copperPool, books.savingsPool("copper"));
        before.assertUntouched(books, buyer);
        books.checkInvariants();
    }

    @Test
    void shopPurchaseAndSaleStayInsideTheSavingsPool() {
        BankBooks books = funded();
        books.creditUnbacked(buyer, "note", new BigInteger("80"));
        books.creditUnbacked(seller, "note", new BigInteger("5"));
        books.setPersonalReserve(buyer, "diamond", new BigInteger("25"));
        Issuance before = Issuance.capture(books, buyer);
        BigInteger pool = books.savingsPool("note");

        TxResult bought = books.transferShopOperating(buyer, new BigInteger("30"), true);
        TxResult sold = books.transferShopOperating(seller, new BigInteger("12"), false);

        assertTrue(bought.success(), bought.code());
        assertTrue(sold.success(), sold.code());
        assertEquals(new BigInteger("50"), books.balance(buyer, "note"));
        assertEquals(new BigInteger("17"), books.balance(seller, "note"));
        assertEquals(new BigInteger("18"), books.shopOperating("note"));
        assertEquals(BigInteger.ZERO, books.landGrant("note"));
        assertEquals(pool, books.savingsPool("note"));
        assertEquals(books.centralDeposits().add(books.interestPool("note")), books.savingsPool("note"));
        before.assertUntouched(books, buyer);
        books.checkInvariants();
    }

    @Test
    void landGrantCreditAndRefundDoNotTouchTheShopOrIssuance() {
        BankBooks books = funded();
        books.creditUnbacked(buyer, "note", new BigInteger("80"));
        books.deposit(buyer, "diamond", new BigInteger("9"));
        books.transferShopOperating(buyer, new BigInteger("10"), true);
        Issuance before = Issuance.capture(books, buyer);
        BigInteger notePool = books.savingsPool("note");
        BigInteger diamondPool = books.savingsPool("diamond");
        BigInteger shop = books.shopOperating("note");
        BigInteger vault = books.supply("diamond").depositVault();

        TxResult credited = books.creditLandGrant(buyer, "note", new BigInteger("20"));
        TxResult diamonds = books.creditLandGrant(buyer, "diamond", new BigInteger("4"));

        assertTrue(credited.success(), credited.code());
        assertTrue(diamonds.success(), diamonds.code());
        assertEquals(new BigInteger("50"), books.balance(buyer, "note"));
        assertEquals(new BigInteger("20"), books.landGrant("note"));
        assertEquals(new BigInteger("5"), books.balance(buyer, "diamond"));
        assertEquals(new BigInteger("4"), books.landGrant("diamond"));
        assertEquals(shop, books.shopOperating("note"));
        assertEquals(notePool, books.savingsPool("note"));
        assertEquals(diamondPool, books.savingsPool("diamond"));
        assertEquals(vault, books.supply("diamond").depositVault());
        before.assertUntouched(books, buyer);

        TxResult refunded = books.refundLandGrant(buyer, "note", new BigInteger("7"));
        TxResult diamondRefund = books.refundLandGrant(buyer, "diamond", new BigInteger("4"));
        assertTrue(refunded.success(), refunded.code());
        assertTrue(diamondRefund.success(), diamondRefund.code());
        assertEquals(new BigInteger("57"), books.balance(buyer, "note"));
        assertEquals(new BigInteger("13"), books.landGrant("note"));
        assertEquals(new BigInteger("9"), books.balance(buyer, "diamond"));
        assertEquals(BigInteger.ZERO, books.landGrant("diamond"));
        assertEquals(shop, books.shopOperating("note"));
        assertEquals(notePool, books.savingsPool("note"));
        assertEquals(diamondPool, books.savingsPool("diamond"));
        before.assertUntouched(books, buyer);
        books.checkInvariants();
    }

    @Test
    void aShortBalancePaysNothing() {
        BankBooks books = funded();
        books.creditUnbacked(buyer, "note", new BigInteger("5"));
        books.deposit(buyer, "diamond", BigInteger.TEN);
        String before = books.toJson();

        assertEquals("insufficient_funds", books.transferSavings(buyer, seller, "note", new BigInteger("6")).code());
        assertEquals("insufficient_funds", books.transferShopOperating(buyer, new BigInteger("6"), true).code());
        assertEquals("insufficient_shop", books.transferShopOperating(buyer, BigInteger.ONE, false).code());
        assertEquals("insufficient_funds", books.creditLandGrant(buyer, "diamond", new BigInteger("11")).code());
        assertEquals("insufficient_funds", books.refundLandGrant(buyer, "note", BigInteger.ONE).code());
        assertEquals("bad_amount", books.transferSavings(buyer, seller, "note", BigInteger.ZERO).code());
        assertEquals("bad_amount", books.transferShopOperating(null, BigInteger.ONE, true).code());
        assertEquals("bad_account", books.transferSavings(buyer, buyer, "note", BigInteger.ONE).code());
        assertEquals("unknown_currency", books.transferSavings(buyer, seller, "泉", BigInteger.ONE).code());
        assertEquals(before, books.toJson());
        books.checkInvariants();
    }

    @Test
    void shopAndLandNotesEarnTheSameDemandDepositInterest() {
        BankBooks books = fundFees(new BigInteger("20"));
        Issuance before = Issuance.capture(books, buyer);
        books.creditUnbacked(buyer, "note", new BigInteger("36500"));
        books.creditUnbacked(seller, "note", new BigInteger("36500"));
        assertTrue(books.transferShopOperating(seller, new BigInteger("36500"), true).success());
        assertTrue(books.creditLandGrant(buyer, "note", new BigInteger("36500")).success());
        BigInteger pool = books.savingsPool("note");
        books.accrueInterest(0);
        books.accrueInterest(1);

        assertEquals(new BigInteger("36502"), books.shopOperating("note"));
        assertEquals(new BigInteger("36502"), books.landGrant("note"));
        assertEquals(BigInteger.ZERO, books.balance(buyer, "note"));
        assertEquals(BigInteger.ZERO, books.balance(seller, "note"));
        assertEquals(new BigInteger("16"), books.interestPool("note"));
        assertEquals(pool, books.savingsPool("note"));
        before.assertUntouched(books, buyer);
        books.checkInvariants();
    }

    @Test
    void aShortFeePoolSharesInterestAndDoesNotMintTheRest() {
        BankBooks books = fundFees(new BigInteger("2"));
        BigInteger diamondIssuance = books.supply("diamond").reserve();
        books.creditUnbacked(buyer, "note", new BigInteger("36500"));
        books.creditUnbacked(seller, "note", new BigInteger("36500"));
        assertTrue(books.transferShopOperating(seller, new BigInteger("36500"), true).success());
        books.accrueInterest(0);
        books.accrueInterest(1);

        assertEquals(new BigInteger("36501"), books.balance(buyer, "note"));
        assertEquals(new BigInteger("36501"), books.shopOperating("note"));
        assertEquals(BigInteger.ZERO, books.interestPool("note"));
        assertEquals(BigInteger.ZERO, books.landGrant("note"));
        assertEquals(diamondIssuance, books.supply("diamond").reserve());
        books.checkInvariants();
    }

    @Test
    void commodityLandGrantEarnsNoInterest() {
        BankBooks books = BankBooks.create(BankConfig.defaults());
        books.deposit(buyer, "diamond", new BigInteger("10"));
        Issuance before = Issuance.capture(books, buyer);
        assertTrue(books.creditLandGrant(buyer, "diamond", new BigInteger("4")).success());
        books.accrueInterest(0);
        books.accrueInterest(5);

        assertEquals(new BigInteger("6"), books.balance(buyer, "diamond"));
        assertEquals(new BigInteger("4"), books.landGrant("diamond"));
        assertEquals(BigInteger.ZERO, books.balance(buyer, "note"));
        assertEquals(BigInteger.ZERO, books.shopOperating("note"));
        assertEquals(BigInteger.ZERO, books.landGrant("note"));
        before.assertUntouched(books, buyer);
        books.checkInvariants();
    }

    @Test
    void anOlderSnapshotKeepsBalancesWhenTheNewAccountsAreAbsent() {
        BankBooks books = BankBooks.create(BankConfig.defaults());
        books.deposit(buyer, "diamond", BigInteger.TEN);
        books.creditUnbacked(buyer, "note", new BigInteger("40"));
        books.setPersonalReserve(buyer, "copper", new BigInteger("7"));
        JsonObject root = JsonParser.parseString(books.toJson()).getAsJsonObject();
        root.remove("shopOperating");
        root.remove("landGrant");
        root.remove("shopInterestDust");
        root.remove("landInterestDust");

        BankBooks loaded = BankBooks.fromJson(root.toString(), BankConfig.defaults());

        assertEquals(BigInteger.TEN, loaded.balance(buyer, "diamond"));
        assertEquals(new BigInteger("40"), loaded.balance(buyer, "note"));
        assertEquals(new BigInteger("7"), loaded.personalReserve(buyer, "copper"));
        assertEquals(books.supply("diamond").reserve(), loaded.supply("diamond").reserve());
        assertEquals(books.supply("diamond").depositVault(), loaded.supply("diamond").depositVault());
        assertEquals(books.supply("note").reserve(), loaded.supply("note").reserve());
        assertEquals(books.supply("note").liability(), loaded.supply("note").liability());
        assertEquals(BigInteger.ZERO, loaded.shopOperating("note"));
        assertEquals(BigInteger.ZERO, loaded.landGrant("note"));
        assertEquals(BigInteger.ZERO, loaded.landGrant("diamond"));
        loaded.checkInvariants();
    }

    @Test
    void postedAccountsAndInterestDustRoundTrip() {
        BankBooks books = BankBooks.create(BankConfig.defaults());
        books.creditUnbacked(buyer, "note", new BigInteger("80"));
        books.deposit(buyer, "diamond", new BigInteger("9"));
        assertTrue(books.transferShopOperating(buyer, new BigInteger("30"), true).success());
        assertTrue(books.creditLandGrant(buyer, "note", new BigInteger("20")).success());
        assertTrue(books.creditLandGrant(buyer, "diamond", new BigInteger("4")).success());
        books.accrueInterest(0);
        books.accrueInterest(1);
        assertTrue(books.shopOperating("note").compareTo(new BigInteger("30")) >= 0);

        BankBooks loaded = BankBooks.fromJson(books.toJson(), BankConfig.defaults());

        assertEquals(books.balance(buyer, "note"), loaded.balance(buyer, "note"));
        assertEquals(books.balance(buyer, "diamond"), loaded.balance(buyer, "diamond"));
        assertEquals(books.shopOperating("note"), loaded.shopOperating("note"));
        assertEquals(books.landGrant("note"), loaded.landGrant("note"));
        assertEquals(books.landGrant("diamond"), loaded.landGrant("diamond"));
        assertEquals(books.interestPool("note"), loaded.interestPool("note"));
        assertEquals(books.supply("diamond").reserve(), loaded.supply("diamond").reserve());
        assertEquals(books.supply("note").liability(), loaded.supply("note").liability());
        JsonObject saved = JsonParser.parseString(loaded.toJson()).getAsJsonObject();
        assertTrue(saved.has("shopInterestDust"));
        assertEquals(books.toJson(), loaded.toJson());
        loaded.checkInvariants();
    }

    @Test
    void publicHooksMatchTheShopAndMarketMethodLists() throws Exception {
        assertNull(ShopOperatingAccount.get());
        assertEquals("bank_not_ready", MarketSettlement.transfer(buyer, seller, "note", BigInteger.ONE, "sale:1").code());
        assertEquals("bad_reference", MarketSettlement.creditLandGrant(buyer, "note", BigInteger.ONE, " ").code());

        BankBooks books = funded();
        books.creditUnbacked(buyer, "note", new BigInteger("40"));
        books.deposit(buyer, "diamond", new BigInteger("6"));
        BankRuntime.state = new BankWorldState(books);
        try {
            Class<?> shopType = Class.forName("dev.jdguo.bank.market.ShopOperatingAccount");
            Method get = shopType.getMethod("get");
            Object account = get.invoke(null);
            assertNotNull(account);
            assertEquals("note", shopType.getMethod("currencyId").invoke(account));
            assertEquals(BigInteger.ZERO, shopType.getMethod("balance").invoke(account));
            assertEquals(new BigInteger("40"), shopType.getMethod("playerBalance", UUID.class).invoke(account, buyer));
            Object bought = shopType.getMethod("transfer", UUID.class, BigInteger.class, boolean.class)
                    .invoke(account, buyer, new BigInteger("15"), true);
            assertTrue((Boolean) bought.getClass().getMethod("success").invoke(bought));
            assertEquals(new BigInteger("15"), shopType.getMethod("balance").invoke(account));
            assertEquals(new BigInteger("25"), shopType.getMethod("playerBalance", UUID.class).invoke(account, buyer));

            Class<?> market = Class.forName("dev.jdguo.bank.api.MarketSettlement");
            Method transfer = market.getMethod("transfer", UUID.class, UUID.class, String.class, BigInteger.class, String.class);
            Method credit = market.getMethod("creditLandGrant", UUID.class, String.class, BigInteger.class, String.class);
            Method refund = market.getMethod("refundLandGrant", UUID.class, String.class, BigInteger.class, String.class);
            Object paid = transfer.invoke(null, buyer, seller, "note", new BigInteger("5"), "sale:listing");
            Object land = credit.invoke(null, buyer, "diamond", new BigInteger("2"), "sale:land");
            Object undone = refund.invoke(null, buyer, "diamond", BigInteger.ONE, "refund:land");
            assertTrue((Boolean) paid.getClass().getMethod("success").invoke(paid));
            assertTrue((Boolean) land.getClass().getMethod("success").invoke(land));
            assertTrue((Boolean) undone.getClass().getMethod("success").invoke(undone));
            assertEquals("ok", paid.getClass().getMethod("code").invoke(paid));

            assertEquals(new BigInteger("20"), books.balance(buyer, "note"));
            assertEquals(new BigInteger("5"), books.balance(seller, "note"));
            assertEquals(new BigInteger("15"), books.shopOperating("note"));
            assertEquals(BigInteger.ONE, books.landGrant("diamond"));
            assertEquals(new BigInteger("5"), books.balance(buyer, "diamond"));
            assertEquals(new BigInteger("1000"), books.supply("diamond").reserve());
            assertEquals(new BigInteger("1000000"), books.supply("copper").reserve());
            books.checkInvariants();
        } finally {
            BankRuntime.state = null;
        }
    }

    @Test
    void adminSnapshotUsesTheFullAccountNames() {
        BankBooks books = funded();
        books.creditUnbacked(buyer, "note", new BigInteger("12"));
        books.transferShopOperating(buyer, new BigInteger("3"), true);
        books.deposit(buyer, "diamond", new BigInteger("2"));
        books.creditLandGrant(buyer, "diamond", BigInteger.ONE);
        String admin = BankUiSnapshot.json(books, buyer, true, "management", false);
        JsonObject snapshot = JsonParser.parseString(admin).getAsJsonObject();
        assertTrue(snapshot.has("institutions"));
        String labels = snapshot.getAsJsonArray("institutions").toString();
        assertTrue(labels.contains("商店经营账户"));
        assertTrue(labels.contains("土地出让账户"));
        assertFalse(labels.contains("商店户"));
        JsonObject plain = JsonParser.parseString(BankUiSnapshot.json(books, buyer, false, "savings", false)).getAsJsonObject();
        assertFalse(plain.has("institutions"));

        String zh = BankPhrases.settlementAccounts(books).zh();
        assertTrue(zh.startsWith("[银行] "));
        assertTrue(zh.contains("商店经营账户"));
        assertTrue(zh.contains("土地出让账户"));
    }

    private BankBooks funded() {
        return BankBooks.create(BankConfig.defaults());
    }

    private BankBooks fundFees(BigInteger feeNotes) {
        BankBooks books = BankBooks.create(BankConfig.defaults());
        books.setAlpha("note", BigDecimal.ZERO);
        BigInteger diamonds = feeNotes.multiply(new BigInteger("50"));
        BigInteger cost = diamonds.add(feeNotes);
        books.creditUnbacked(seller, "note", cost);
        TxResult result = books.exchange(seller, "note", "diamond", diamonds);
        assertTrue(result.success(), result.code());
        assertEquals(feeNotes, books.interestPool("note"));
        assertEquals(BigInteger.ZERO, books.balance(seller, "note"));
        return books;
    }

    private record Issuance(BigInteger diamondReserve, BigInteger diamondLocked, BigInteger copperReserve,
                            BigInteger noteReserve, BigInteger centralReserve, BigInteger personalDiamond,
                            BigInteger paper) {
        static Issuance capture(BankBooks books, UUID player) {
            return new Issuance(
                    books.supply("diamond").reserve(),
                    books.supply("diamond").locked(),
                    books.supply("copper").reserve(),
                    books.supply("note").reserve(),
                    books.centralReserve(player),
                    books.personalReserve(player, "diamond"),
                    books.centralM0());
        }

        void assertUntouched(BankBooks books, UUID player) {
            assertEquals(diamondReserve, books.supply("diamond").reserve());
            assertEquals(diamondLocked, books.supply("diamond").locked());
            assertEquals(copperReserve, books.supply("copper").reserve());
            assertEquals(noteReserve, books.supply("note").reserve());
            assertEquals(centralReserve, books.centralReserve(player));
            assertEquals(personalDiamond, books.personalReserve(player, "diamond"));
            assertEquals(paper, books.centralM0());
        }
    }
}
