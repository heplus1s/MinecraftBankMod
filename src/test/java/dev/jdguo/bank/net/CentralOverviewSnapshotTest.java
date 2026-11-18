package dev.jdguo.bank.net;
import com.google.gson.JsonParser;
import dev.jdguo.bank.currency.BankConfig;
import dev.jdguo.bank.ledger.BankBooks;
import org.junit.jupiter.api.Test;
import java.math.BigInteger;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
class CentralOverviewSnapshotTest {
    @Test void commoditiesDoNotAdvertiseCurrencyIssuanceAndCustodyIsAuthoritative() {
        var books=BankBooks.create(BankConfig.defaults()); var player=UUID.randomUUID();
        books.deposit(player,"diamond",BigInteger.valueOf(730));
        var before=books.balanceExact(player,"diamond");
        var data=JsonParser.parseString(BankUiSnapshot.json(books,player,true,"management",false)).getAsJsonObject();
        for(var item:data.getAsJsonArray("central")) {
            var row=item.getAsJsonObject(); String id=row.get("id").getAsString();
            if(id.equals("copper") || id.equals("diamond")) {
                assertFalse(row.has("liability")); assertFalse(row.has("backedRoom"));
            }
            assertEquals(books.custodyExact(id).stripTrailingZeros().toPlainString(),row.get("custody").getAsString());
        }
        assertEquals(before,books.balanceExact(player,"diamond"));
        assertEquals(books.unitsPer("copper","diamond").stripTrailingZeros().toPlainString(),data.get("copperPerDiamondExact").getAsString());
        var ordinary=JsonParser.parseString(BankUiSnapshot.json(books,player,false,"savings",false)).getAsJsonObject();
        assertFalse(ordinary.has("copperPerDiamondExact"));
    }
}
