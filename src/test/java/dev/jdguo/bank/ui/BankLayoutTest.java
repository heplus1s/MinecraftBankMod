package dev.jdguo.bank.ui;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.ToIntFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BankLayoutTest {
    @Test
    void chromeFitsAndTheTopButtonsDoNotOverlap() {
        for (int width : new int[] {360, 427, 640, 960}) {
            for (int height : new int[] {240, 360, 540}) {
                List<BankLayout.Box> tabs = BankLayout.functionTabs(width, height);
                BankLayout.Box close = BankLayout.closeBox(width, height);
                BankLayout.Box issue = BankLayout.issuanceBox(width);
                List<BankLayout.Box> boxes = new ArrayList<>(tabs);
                boxes.add(close);
                boxes.add(issue);
                for (BankLayout.Box box : boxes) {
                    assertTrue(box.fits(width, height), box.label() + " overflows " + width + "x" + height);
                }
                assertEquals(close.y(), tabs.get(3).y(), "lower nav row shares 关闭's row");
                assertEquals(close.bottom(), tabs.get(3).bottom());
                for (BankLayout.Box tab : tabs) {
                    if (tab.y() != close.y()) {
                        assertTrue(!tab.intersects(close), tab.label() + " hits 关闭");
                    }
                    assertTrue(!tab.intersects(issue), tab.label() + " hits 个人发行");
                }
                if (width >= 427) {
                    for (int i = 3; i < tabs.size(); i++) {
                        assertTrue(!tabs.get(i).intersects(close), tabs.get(i).label() + " hits 关闭");
                    }
                }
                assertTrue(issue.x() >= BankLayout.mainLeft(width));
                assertTrue(BankLayout.mainLeft(width) > BankLayout.columnRight(width));
                assertEquals(BankLayout.EDGE + BankLayout.BUTTON_HEIGHT + BankLayout.GAP, BankLayout.contentTop());
                assertTrue(BankLayout.contentBottom(height) > BankLayout.contentTop());
                assertEquals(7, tabs.size());
                assertEquals("硬通货兑换", tabs.get(3).label());
                assertEquals("开立汇票", tabs.get(4).label());
                assertEquals("兑付汇票", tabs.get(5).label());
                assertEquals("个人银行券交易", tabs.get(6).label());
                assertEquals(20, BankLayout.drawerHeight(false, 4));
                assertTrue(BankLayout.drawerHeight(true, 4) > 20);
                assertTrue(BankLayout.drawerHeight(true, 8) <= 20 + 72);
                for (int rows : new int[] {1, 2}) {
                    BankLayout.PlayerControls controls = BankLayout.playerControls(height, rows);
                    assertTrue(controls.drawerY() >= BankLayout.contentTop(), controls.toString());
                    assertTrue(controls.amountBelowDrawers(rows), width + "x" + height + " rows " + rows + " " + controls);
                    assertTrue(controls.actionY() + 18 <= BankLayout.contentBottom(height), controls.toString());
                    int openRoom = controls.fieldY() - 12 - (controls.drawerY() + (rows - 1) * 24);
                    assertTrue(openRoom >= 36, "open drawer room " + openRoom + " " + controls);
                }
                int headerBottom = BankLayout.contentTop() + 36;
                for (boolean firstOpen : new boolean[] {false, true}) {
                    for (boolean secondOpen : new boolean[] {false, true}) {
                        int limit = BankLayout.contentBottom(height);
                        BankLayout.MarketStack market = BankLayout.marketStack(headerBottom, limit, firstOpen, 4, secondOpen, 3);
                        assertTrue(market.firstY() >= headerBottom, market.toString());
                        assertTrue(market.secondY() >= market.firstY() + market.firstH() + 4, market.toString());
                        assertTrue(market.fieldY() >= market.secondY() + market.secondH() + 4, market.toString());
                        assertTrue(market.quickY() >= market.fieldY() + 16, market.toString());
                        assertTrue(market.actionY() >= market.quickY() + 18, market.toString());
                        if (height >= 360) {
                            assertTrue(market.separated(limit), width + "x" + height + " " + market);
                            if (firstOpen) {
                                assertTrue(market.firstH() >= 38, market.toString());
                            }
                            if (secondOpen) {
                                assertTrue(market.secondH() >= 38, market.toString());
                            }
                        }
                    }
                }
                int issuanceTop = BankLayout.contentTop() + 80;
                BankLayout.IssuanceStack issuance = BankLayout.issuanceStack(issuanceTop, BankLayout.contentBottom(height), true, true, 5);
                assertTrue(issuance.regimeY() >= issuanceTop, issuance.toString());
                assertTrue(issuance.anchorY() >= issuance.regimeY() + issuance.regimeH() + 4, issuance.toString());
                assertTrue(issuance.fieldY() >= issuance.anchorY() + issuance.anchorH() + 4, issuance.toString());
                assertTrue(issuance.depositY() >= issuance.quickY() + 18, issuance.toString());
                assertTrue(issuance.issueY() >= issuance.depositY() + 18, issuance.toString());
                if (height >= 360) {
                    assertTrue(issuance.separated(BankLayout.contentBottom(height)), issuance.toString());
                    assertTrue(issuance.regimeH() >= 38 && issuance.anchorH() >= 38, issuance.toString());
                }
                BankLayout.AdminBands admin = BankLayout.adminBands(width, height);
                assertTrue(admin.separated(), width + "x" + height + " admin " + admin);
                assertTrue(admin.centered(width), admin.toString());
                assertTrue(admin.fits(height), admin.toString());
                assertTrue(admin.listW() * 2 < width);
            }
        }
    }

    @Test
    void issuanceBodyScrollsInsideThePortAndDoesNotCoverTheNav() {
        ToIntFunction<String> measure = text -> text.codePointCount(0, text.length()) * 9;
        for (int width : new int[] {320, 360, 427, 640, 960}) {
            for (int height : new int[] {240, 360, 540}) {
                BankLayout.Box port = BankLayout.issuancePort(width, height, 16);
                assertTrue(port.height() >= 48, port.toString());
                assertTrue(port.y() >= BankLayout.contentTop(), port.toString());
                assertTrue(port.bottom() <= BankLayout.contentBottom(height), port.toString());
                assertTrue(port.fits(width, height), port.toString());
                assertTrue(!port.intersects(BankLayout.closeBox(width, height)), port.toString());
                assertTrue(!port.intersects(BankLayout.issuanceBox(width)), port.toString());
                for (BankLayout.Box tab : BankLayout.functionTabs(width, height)) {
                    assertTrue(!port.intersects(tab), port + " hits " + tab.label());
                }
                int left = port.x() + 4;
                int right = port.right() - 8;
                int textWidth = right - left;
                assertTrue(textWidth >= 40, textWidth + " " + port);
                List<String> help = BankText.lines(BankRegimes.FLOAT_RULE, textWidth, measure);
                assertEquals(BankRegimes.FLOAT_RULE, String.join("", help));
                if (measure.applyAsInt(BankRegimes.FLOAT_RULE) > textWidth) {
                    assertTrue(help.size() > 1, "help stays on one line at " + textWidth);
                }
                for (String line : help) {
                    assertTrue(measure.applyAsInt(line) <= textWidth, line);
                }
                int y = port.y() + 4;
                y = BankLayout.flow(left, y, right, 4, 18, List.of("宣告破产"), List.of(BankLayout.labelWidth("宣告破产"))).bottom() + 4;
                y = BankLayout.flow(left, y, right, 4, 18, List.of("查看能否切换发行制度"),
                        List.of(BankLayout.labelWidth("查看能否切换发行制度"))).bottom() + 6;
                y += 4 * 12 + 6 + 20;
                BankLayout.Flow chips = BankLayout.flow(left, y, right, BankLayout.GAP, 18,
                        List.of("1", "5", "10", "64"), List.of(28, 28, 28, 28));
                for (BankLayout.Box chip : chips.boxes()) {
                    assertTrue(chip.right() <= right, chip.toString());
                }
                y = chips.bottom() + 4;
                BankLayout.Flow issued = BankLayout.flow(left, y, right, 4, 18,
                        List.of("按数量增发", "按数量收回"),
                        List.of(BankLayout.labelWidth("按数量增发"), BankLayout.labelWidth("按数量收回")));
                y = issued.bottom() + 4;
                BankLayout.Flow freeze = BankLayout.flow(left, y, right, 4, 18,
                        List.of("暂停兑付"), List.of(BankLayout.labelWidth("暂停兑付")));
                y = freeze.bottom() + 4;
                BankLayout.Flow deposit = BankLayout.flow(left, y, right, 4, 18,
                        List.of("存入央行准备金", "取出央行准备金"),
                        List.of(BankLayout.labelWidth("存入央行准备金"), BankLayout.labelWidth("取出央行准备金")));
                y = deposit.bottom() + 4;
                BankLayout.Flow actions = BankLayout.flow(left, y, right, 4, 18,
                        List.of("按准备金发行", "确认切换"),
                        List.of(BankLayout.labelWidth("按准备金发行"), BankLayout.labelWidth("确认切换")));
                int controlsBottom = actions.bottom();
                y = controlsBottom + 8 + help.size() * 12 + 6;
                y += BankLayout.drawerHeight(false, 3) + 6;
                y += BankLayout.drawerHeight(false, 5);
                for (BankLayout.Box box : deposit.boxes()) {
                    assertTrue(box.x() >= left && box.right() <= right, box.toString());
                }
                for (BankLayout.Flow row : new BankLayout.Flow[] {issued, freeze, actions}) {
                    for (BankLayout.Box box : row.boxes()) {
                        assertTrue(box.x() >= left && box.right() <= right, box.toString());
                        assertTrue(box.bottom() < controlsBottom + 20, box.toString());
                    }
                }
                assertTrue(freeze.boxes().get(0).bottom() <= controlsBottom, freeze.toString());
                if (textWidth < BankLayout.labelWidth("存入央行准备金") * 2 + 4) {
                    assertTrue(deposit.boxes().get(1).y() >= deposit.boxes().get(0).bottom());
                }
                int bodyBottom = y + 6;
                if (width <= 360 && height <= 240) {
                    assertTrue(bodyBottom - port.y() > port.height(),
                            "body " + (bodyBottom - port.y()) + " port " + port.height());
                    assertTrue(freeze.boxes().get(0).y() - port.y() < port.height() + 160,
                            "freeze is a long hunt " + freeze);
                }
            }
        }
    }
}
