package dev.jdguo.bank.api;
import dev.jdguo.bank.ledger.BankBooks;
import dev.jdguo.bank.currency.BankConfig;
import dev.jdguo.bank.market.NoteRegime;
import java.math.BigInteger;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class PersonalMarketCatalogTest {
    UUID issuer=UUID.randomUUID(),buyer=UUID.randomUUID();
    BankBooks books=BankBooks.create(BankConfig.defaults());
    void create(){assertTrue(books.createPersonalNote(issuer,"泉",NoteRegime.FLOAT,"diamond",BigInteger.ONE).success());}
    com.google.gson.JsonObject row(UUID viewer){return PersonalMarketCatalog.catalog(books,viewer).getAsJsonArray("notes").get(0).getAsJsonObject();}
    @Test void creationAddsEntryWithoutInventingSupply(){create();assertEquals("泉",row(buyer).get("name").getAsString());assertEquals("0",row(buyer).get("available").getAsString());assertEquals("0",row(buyer).get("issuerOffer").getAsString());}
    @Test void renamePreservesIdentity(){create();String id=row(buyer).get("id").getAsString();books.personalNote(issuer).rename("新泉");assertEquals(id,row(buyer).get("id").getAsString());assertEquals("新泉",row(buyer).get("name").getAsString());}
    @Test void supplyIsRealAndOwnOrdersNotPurchasable(){create();books.deposit(issuer,"diamond",BigInteger.TEN);assertTrue(books.postIssuanceReserve(issuer,BigInteger.TEN,true).success());assertTrue(books.issuePersonalNote(issuer,BigInteger.TEN).success());assertEquals("10",row(buyer).get("available").getAsString());assertEquals("0",row(issuer).get("available").getAsString());assertEquals("10",row(issuer).get("held").getAsString());assertTrue(books.setPersonalAutoListing(issuer,"泉",false).success());assertEquals("0",row(buyer).get("issuerOffer").getAsString());assertEquals(1,PersonalMarketCatalog.catalog(books,buyer).getAsJsonArray("notes").size());}
    @Test void bankruptEntryRemainsButCannotTrade(){create();books.personalNote(issuer).declareBankrupt(1,BigInteger.ZERO);assertFalse(row(buyer).get("tradable").getAsBoolean());assertEquals("",row(buyer).get("price").getAsString());}
}
