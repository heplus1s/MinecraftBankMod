package dev.jdguo.bank.ui;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BankHeaderTest {
    @Test
    void chromeKeepsRateAndStatusAndManagementKeepsTheAggregates() {
        JsonObject data = new JsonObject();
        data.addProperty("m0", "1");
        data.addProperty("m1", "4");
        data.addProperty("m2", "3.5");
        data.addProperty("status", "兑付正常");
        data.addProperty("copperPerDiamond", "1000");

        assertEquals(List.of("汇率  1000.000000 铜/钻", "兑付正常"), BankHeader.lines(data));
        assertEquals(List.of(
                "流通中现金（M0）：1",
                "狭义货币（M1）：4",
                "广义货币（M2）：3.5000"), BankHeader.managementAggregates(data));
        assertFalse(String.join("\n", BankHeader.lines(data)).contains("M0"));
        assertFalse(String.join("\n", BankHeader.lines(data)).contains("兑付能力"));
        assertFalse(BankChrome.SIDEBAR);
        assertEquals(List.of("储蓄", "存入", "取出", "硬通货兑换", "开立汇票", "兑付汇票", "个人银行券交易"), BankChrome.FUNCTION_TABS);
        assertFalse(BankChrome.FUNCTION_TABS.contains("汇票"));
        assertFalse(BankChrome.FUNCTION_TABS.contains("兑换"));
    }

    @Test
    void chestHeaderKeepsRateAndDropsExtraRedemptionLines() {
        List<String> kept = BankHeader.keepChestHeader(List.of(
                "Central bank",
                "兑付正常",
                "汇率 1000 铜/钻",
                "手续费 2%",
                "兑付能力 80%",
                "兑付：正常",
                "兑付：暂停",
                "Redemption available",
                "汇票请到「汇票」页兑付",
                "定额兑付"));

        assertEquals(List.of("汇率  1000.000000 铜/钻", "兑付正常"), kept);
    }

    @Test
    void missingRateSaysSo() {
        JsonObject data = new JsonObject();
        data.addProperty("status", "准备金紧张");

        List<String> lines = BankHeader.lines(data);

        assertTrue(lines.contains("汇率  暂无牌价"));
        assertTrue(lines.contains("准备金紧张"));
        assertFalse(lines.stream().anyMatch(line -> line.startsWith("M0")));
        assertEquals("1.234568", BankHeader.fixed6("1.2345678"));
        assertEquals("1004.500000", BankHeader.fixed6("1,004.5"));
    }
}
