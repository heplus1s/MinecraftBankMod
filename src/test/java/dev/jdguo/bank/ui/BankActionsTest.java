package dev.jdguo.bank.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BankActionsTest {
    @Test
    void usesTheActionsTheBankCoreAlreadyAccepts() {
        assertEquals("deposit-transfer diamond 5", BankActions.deposit("diamond", "5"));
        assertEquals("withdraw note all", BankActions.withdraw("note", "all"));
        assertEquals("exchange copper note 10", BankActions.exchange("copper", "note", "10"));
        assertEquals("check note 64 bearer", BankActions.check("note", "64", false));
        assertEquals("check note 64 self", BankActions.check("note", "64", true));
        assertEquals("deposit-held", BankActions.presentHeld());
        assertEquals("兑付手里拿着的那一张汇票。请把要兑付的汇票拿在主手。一次只兑付这一张，不会扫描背包，也不会存入铜锭、钻石或银行券。",
                BankActions.MAIN_HAND_BILL);
        assertEquals("确认兑付手里拿着的那一张汇票", BankActions.MAIN_HAND_BUTTON);
        assertEquals("note-buy 张三 1", BankActions.noteBuy("张三", "1"));
        assertEquals("note-sell 张三 5", BankActions.noteSell("张三", "5"));
        assertEquals("note-swap 张三 李四 10", BankActions.noteSwap("张三", "李四", "10"));
        assertEquals("note-redeem 张三 note 1", BankActions.noteRedeem("张三", "note", "1"));
        assertEquals("note-issue 64", BankActions.noteIssue("64"));
        assertEquals("note-unbacked 64", BankActions.noteUnbacked("64"));
        assertEquals("issue-unbacked 256", BankActions.issueUnbacked("256"));
        assertEquals("note-retire 256", BankActions.noteRetire("256"));
        assertEquals("note-freeze", BankActions.noteFreeze());
        assertEquals("note-unfreeze", BankActions.noteUnfreeze());
        assertEquals("admin-freeze", BankActions.adminFreeze());
        assertEquals("admin-unbacked note 1000", BankActions.adminUnbacked("note", "1000"));
        assertEquals("admin-unbacked note 200000", BankActions.adminUnbacked("note", "200000"));
        assertEquals("admin-retire note 200000", BankActions.adminRetire("note", "200000"));
        assertEquals("admin-backed note", BankActions.adminBacked("note"));
        assertEquals("admin-backed note 200000", BankActions.adminBackedAmount("note", "200000"));
        assertEquals("admin-reserve-from-deposits copper 200000", BankActions.adminReserveFromDeposits("copper", "200000"));
        assertEquals("admin-reserve-to-deposits diamond 200000", BankActions.adminReserveToDeposits("diamond", "200000"));
        assertEquals("admin-retire note 1000", BankActions.adminRetire("note", "1000"));
        assertEquals("admin-backed diamond", BankActions.adminBacked("diamond"));
        assertEquals("admin-backed note", BankActions.adminBacked("note"));
        assertEquals(2, BankActions.adminBacked("copper").split(" ").length);
    }

    @Test
    void namingFillsTheExistingCreateCommand() {
        assertTrue(BankActions.noteNamePrompt("float").contains("浮动汇率制"));
        assertTrue(BankActions.noteNamePrompt("联系汇率制").contains("个人银行券的名字"));
        assertTrue(BankActions.noteNamePrompt("固定汇率制").contains("固定汇率制"));
        assertEquals("/bank note create float ", BankActions.noteNameCommand("float"));
        assertEquals("/bank note create peg ", BankActions.noteNameCommand("钉住"));
        assertEquals("/bank note create hard ", BankActions.noteNameCommand("定额兑付"));
        assertEquals("/bank note create float ", BankActions.noteNameCommand("浮动汇率制"));
        assertEquals("/bank note create peg ", BankActions.noteNameCommand("联系汇率制"));
        assertEquals("/bank note create hard ", BankActions.noteNameCommand("固定汇率制"));
        assertEquals("note-switch float", BankActions.noteSwitch("FLOAT", null));
        assertEquals("note-switch float diamond", BankActions.noteSwitch("浮动汇率制", "钻石"));
        assertEquals("note-switch peg note", BankActions.noteSwitch("peg", "银行券"));
        assertEquals("note-switch hard diamond", BankActions.noteSwitch("hard", "diamond"));
        assertEquals("note-switch hard note", BankActions.noteSwitch("固定汇率制", "银行券"));
        assertEquals("note-switch peg copper", BankActions.noteSwitch("联系汇率制", "铜锭"));
    }

    @Test
    void managementButtonsUseTheTypedAmountAndDoNotInventOne() {
        assertEquals("从存款转入准备金 256 银行券", BankAdminScreen.amountButton("从存款转入准备金", "256", "银行券"));
        assertEquals("无准备金增发 3.5000 铜锭", BankAdminScreen.amountButton("无准备金增发", " 3.5000 ", "铜锭"));
        assertEquals("从准备金转回存款 钻石", BankAdminScreen.amountButton("从准备金转回存款", "", "钻石"));
        assertEquals("从准备金转回存款 钻石", BankAdminScreen.amountButton("从准备金转回存款", "   ", "钻石"));
        assertTrue(BankAdminScreen.amountChars("256"));
        assertTrue(BankAdminScreen.amountChars("0.2500"));
        assertTrue(BankAdminScreen.amountChars("10."));
        assertFalse(BankAdminScreen.amountChars("10.12345"));
        assertFalse(BankAdminScreen.amountChars("1a"));
        assertEquals("admin-backed note 256", BankActions.adminBackedAmount("note", "256"));
        assertEquals("admin-reserve-from-deposits note 256", BankActions.adminReserveFromDeposits("note", "256"));
        assertEquals("admin-backed note", BankActions.adminBacked("note"));
    }
}
