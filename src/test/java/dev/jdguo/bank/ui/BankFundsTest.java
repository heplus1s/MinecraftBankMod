package dev.jdguo.bank.ui;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BankFundsTest {
    @Test
    void withdrawalAndPhysicalExchangeExplainAShortBalance() {
        JsonObject data = new JsonObject();
        JsonArray currencies = new JsonArray();
        JsonObject diamond = new JsonObject();
        diamond.addProperty("id", "diamond");
        diamond.addProperty("name", "钻石");
        diamond.addProperty("balance", "3");
        currencies.add(diamond);
        JsonObject copper = new JsonObject();
        copper.addProperty("id", "copper");
        copper.addProperty("name", "铜锭");
        copper.addProperty("balance", "3");
        currencies.add(copper);
        data.add("currencies", currencies);

        String withdrawal = BankFunds.withdrawalRefusal(data, "diamond", "钻石", "10");
        assertTrue(withdrawal.contains("要取出 10 钻石"));
        assertTrue(withdrawal.contains("账户里只有 3 钻石"));
        assertNull(BankFunds.withdrawalRefusal(data, "diamond", "钻石", "all"));
        assertNull(BankFunds.withdrawalRefusal(data, "diamond", "钻石", "2"));

        String exchange = BankFunds.physicalExchangeRefusal(data, "copper", "铜锭", "10");
        assertTrue(exchange.contains("要付出 10 铜锭"));
        assertTrue(exchange.contains("不能完成这笔兑换"));
        assertNull(BankFunds.physicalExchangeRefusal(data, "note", "银行券", "10"));
    }
}
