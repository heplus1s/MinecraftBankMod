package dev.jdguo.bank.ui;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import java.util.function.ToIntFunction;
import static org.junit.jupiter.api.Assertions.*;

class BankOverviewTest {
    private static final ToIntFunction<String> MEASURE = value -> value.codePoints().map(c -> c < 128 ? 6 : 9).sum();
    private JsonObject sample() {
        var data = new JsonObject(); data.addProperty("centralCurrencyName","央行券");
        data.addProperty("m0","0"); data.addProperty("m1","211563.6923"); data.addProperty("m2","211,563.6923");
        data.addProperty("copperPerDiamond","100"); data.addProperty("diamondsPerNote","1");
        var central = new JsonArray();
        for (String id : new String[]{"copper","diamond","note"}) {
            var row = new JsonObject(); row.addProperty("id",id);
            row.addProperty("reserve","500000"); row.addProperty("treasury","501300");
            row.addProperty("custody","730"); row.addProperty("liability","428");
            row.addProperty("backedRoom","999"); central.add(row);
        }
        data.add("central",central); return data;
    }
    @Test void allNormalGuiSizesFitOnePageWithNoClippedTextOrOverlap() {
        for (int width : new int[]{250,320,427,512,640,960,1280}) for (int height : new int[]{230,240,268,360,540}) {
            var layout=BankOverview.layout(sample(),Math.min(720,width-20),height-100,MEASURE);
            assertTrue(layout.readable(),width+"x"+height+" "+layout.scale());
            assertTrue(layout.width()*layout.scale()<=width-20+.01f);
            assertTrue(layout.height()*layout.scale()<=height-100+.01f);
            for (var cell:layout.cells()) {
                assertTrue(cell.x()>=0 && cell.y()>=0 && cell.x()+cell.width()<=layout.width());
                assertTrue(cell.y()+cell.height()<=layout.height());
                assertTrue(cell.lines().size()*12+4<=cell.height());
                for(String line:cell.lines()) assertTrue(MEASURE.applyAsInt(line)<=cell.width()-10,line);
            }
            for(int i=0;i<layout.cells().size();i++) for(int j=i+1;j<layout.cells().size();j++) {
                var a=layout.cells().get(i);var b=layout.cells().get(j);
                assertFalse(a.x()<b.x()+b.width() && a.x()+a.width()>b.x() && a.y()<b.y()+b.height() && a.y()+a.height()>b.y());
            }
        }
    }
    @Test void physicalIssuanceCountersAreNotShownAndCustodyIsSeparate() {
        var layout=BankOverview.layout(sample(),420,220,MEASURE);
        String text=layout.cells().stream().flatMap(c->c.lines().stream()).reduce("",(a,b)->a+"\n"+b);
        assertTrue(text.contains("500000")); assertTrue(text.contains("501300")); assertTrue(text.contains("730"));
        assertFalse(text.contains("428")); assertFalse(text.contains("999"));
        assertFalse(text.contains("按准备金尚可发行")); assertFalse(text.contains("发行额"));
    }
    @Test void exactRatesAndLongAmountsDoNotGetRoundedOrAbbreviated() {
        var data=sample(); data.addProperty("diamondsPerNoteExact","0.0000000123456789");
        var layout=BankOverview.layout(data,420,240,MEASURE);
        assertTrue(layout.cells().stream().anyMatch(c->String.join("",c.lines()).contains("0.0000000123456789")));
        String amount="123456789012345678901234567890.1234";
        data.addProperty("m2",amount);
        layout=BankOverview.layout(data,320,240,MEASURE);
        assertTrue(layout.cells().stream().anyMatch(c->String.join("",c.lines()).endsWith(amount)));
        assertEquals(amount,BankOverview.exact(amount));
        assertEquals("0",BankOverview.exact("0.0000"));
    }
    @Test void unknownIsNotInventedZeroAndZeroQuoteIsNotMissing() {
        var data=sample(); data.remove("diamondsPerNote");
        assertEquals("暂无牌价",BankOverview.quote(data,"diamondsPerNoteExact","diamondsPerNote"));
        data.addProperty("diamondsPerNoteExact","0");
        assertEquals("0",BankOverview.quote(data,"diamondsPerNoteExact","diamondsPerNote"));
        assertEquals("暂无数据",BankOverview.field(data,"absent","treasury"));
        data.getAsJsonArray("central").get(0).getAsJsonObject().remove("custody");
        assertEquals("暂无数据",BankOverview.field(data,"copper","custody"));
    }
}
