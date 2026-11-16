package dev.jdguo.bank.ui;

import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

/** Lines for the central-bank management page. Missing fields stay missing. */
public final class BankBook {
    public static final String MISSING = "暂无数据";
    public static final String CIRCULATION_NOTE = "以上是流通量统计，不是准备金。";
    public static final String FREEZE_NOTE = "只暂停或恢复用银行券兑换铜锭或钻石。普通存取不受影响。";
    public static final String UNBACKED_NOTE = "不增加准备金。增发所选货币，并记入操作者的账户。";
    public static final String RETIRE_NOTE = "从操作者的账户扣减所选货币，并减少发行额。";
    public static final String BACKED_BUTTON = "按准备金满额发行";
    public static final String BACKED_EXPLANATION = "按管理列表里当前选中的货币，发行该货币按准备金尚可发行的剩余额度。这不是无准备金增发。";
    public static final String PAUSE = "暂停银行券兑付";
    public static final String RESUME = "恢复银行券兑付";

    private BankBook() {
    }

    public static List<String> preface(JsonObject data) {
        List<String> lines = new ArrayList<>();
        String status = BankSnapshot.text(data, "status");
        lines.add("兑付状态：" + (status == null ? MISSING : status));
        lines.add(redemption(data));
        String tightness = firstPresent(data, "tightness", "redemptionCapacity");
        if (tightness != null) {
            lines.add("兑付能力：" + BankHeader.figure(tightness));
        }
        String fee = BankSnapshot.text(data, "feePercent");
        if (fee != null) {
            lines.add("手续费：" + (fee.contains("%") ? fee : BankHeader.figure(fee) + "%"));
        }
        lines.addAll(BankHeader.managementAggregates(data));
        lines.add(CIRCULATION_NOTE);
        return lines;
    }

    public static String redemption(JsonObject data) {
        Boolean frozen = BankSnapshot.frozen(data);
        if (frozen != null) {
            return "银行券兑换铜锭或钻石：" + (frozen ? "已暂停" : "正常");
        }
        String status = BankSnapshot.text(data, "status");
        if (status == null) {
            return "银行券兑换铜锭或钻石：" + MISSING;
        }
        return "银行券兑换铜锭或钻石：" + (status.contains("暂停") ? "已暂停" : "正常");
    }

    public static String freezeButton(JsonObject data) {
        Boolean frozen = BankSnapshot.frozen(data);
        if (Boolean.TRUE.equals(frozen)) {
            return RESUME;
        }
        String status = BankSnapshot.text(data, "status");
        if (frozen == null && status != null && status.contains("暂停")) {
            return RESUME;
        }
        return PAUSE;
    }

    /**
     * Reserve and savings lines for 铜锭, 钻石, and 银行券.
     * They do not depend on which currency an adjustment will use.
     */
    public static List<String> visibleLedger(JsonObject data) {
        List<String> lines = new ArrayList<>();
        lines.addAll(preface(data));
        lines.addAll(currencyLines(data, "copper", "铜锭", "copperReserve", false));
        lines.addAll(currencyLines(data, "diamond", "钻石", "diamondReserve", false));
        lines.addAll(currencyLines(data, "note", "银行券", "noteReserve", true));
        for (BankSnapshot.CentralRow row : BankSnapshot.central(data)) {
            if (isCore(row)) {
                continue;
            }
            lines.addAll(linesFor(row.name(), false, null, row));
        }
        lines.addAll(institutions(data));
        return lines;
    }

    public static List<String> book(JsonObject data, String currencyId, String currencyName) {
        BankSnapshot.CentralRow row = find(data, currencyId, currencyName);
        List<String> lines = new ArrayList<>();
        lines.add(field("准备金", row == null ? null : row.reserve()));
        lines.add(field("央行储蓄", row == null ? null : row.savings()));
        lines.add(field("存款保管", row == null ? null : row.vault()));
        lines.add(field("发行额", row == null ? null : row.liability()));
        lines.add(field("已锁定准备金", row == null ? null : row.locked()));
        lines.add(field("按准备金尚可发行", row == null ? null : row.backedRoom()));
        return lines;
    }

    public static List<String> institutions(JsonObject data) {
        List<String> lines = new ArrayList<>();
        if (!BankSnapshot.has(data, "institutions")) {
            lines.add("商店经营账户和土地出让账户：" + MISSING);
            return lines;
        }
        if (BankSnapshot.institutions(data).isEmpty()) {
            lines.add("商店经营账户和土地出让账户：" + MISSING);
            return lines;
        }
        for (BankSnapshot.InstitutionRow row : BankSnapshot.institutions(data)) {
            lines.add(row.name() + "：" + (row.detail() == null ? MISSING : row.detail()));
        }
        return lines;
    }

    public static List<String> actionNotes() {
        return List.of(FREEZE_NOTE, UNBACKED_NOTE, RETIRE_NOTE, BACKED_EXPLANATION);
    }

    /** M0, M1, M2 and the line that says they are not reserves. */
    public static List<String> circulationLines(JsonObject data) {
        List<String> lines = new ArrayList<>(BankHeader.managementAggregates(data));
        lines.add(CIRCULATION_NOTE);
        return lines;
    }

    /**
     * Copper or diamond column. Comparison totals read {@code hardBooks} first.
     * 存款保管 is included only when that central field is present.
     */
    public static List<String> metalLines(JsonObject data, String id, String name, String reserveKey) {
        BankSnapshot.CentralRow row = find(data, id, name);
        List<String> lines = new ArrayList<>();
        lines.add(field(name + "储蓄账面总值", bookTotal(data, id, row)));
        lines.add(field(name + "实际硬通货储蓄总值", physicalTotal(data, id, row)));
        String reserve = BankSnapshot.text(data, reserveKey);
        if (reserve == null && row != null) {
            reserve = row.reserve();
        }
        lines.add(field(name + "准备金", reserve));
        lines.add(field(name + "央行储蓄", row == null ? null : row.savings()));
        if (row != null && row.vault() != null) {
            lines.add(field(name + "存款保管", row.vault()));
        }
        return lines;
    }

    /**
     * Bank-note column. 发行准备金 and 央行储蓄 are always shown.
     * 发行额, 已锁定准备金, and 按准备金尚可发行 appear only when the snapshot has them.
     */
    public static List<String> noteLines(JsonObject data) {
        BankSnapshot.CentralRow row = find(data, "note", "银行券");
        String reserve = BankSnapshot.text(data, "noteReserve");
        if (reserve == null && row != null) {
            reserve = row.reserve();
        }
        List<String> lines = new ArrayList<>();
        lines.add(field("银行券发行准备金", reserve));
        lines.add(field("银行券央行储蓄", row == null ? null : row.savings()));
        if (row != null && row.liability() != null) {
            lines.add(field("银行券发行额", row.liability()));
        }
        if (row != null && row.locked() != null) {
            lines.add(field("银行券已锁定准备金", row.locked()));
        }
        if (row != null && row.backedRoom() != null) {
            lines.add(field("银行券按准备金尚可发行", row.backedRoom()));
        }
        return lines;
    }

    private static List<String> currencyLines(JsonObject data, String id, String name, String rootReserve, boolean note) {
        BankSnapshot.CentralRow row = find(data, id, name);
        List<String> lines = new ArrayList<>();
        if (!note) {
            lines.add(field(name + "储蓄账面总值", bookTotal(data, id, row)));
            lines.add(field(name + "实际硬通货储蓄总值", physicalTotal(data, id, row)));
        }
        lines.addAll(linesFor(name, note, BankSnapshot.text(data, rootReserve), row));
        return lines;
    }

    private static String bookTotal(JsonObject data, String id, BankSnapshot.CentralRow row) {
        String hard = BankSnapshot.hardBook(data, id, "savingsBook");
        if (hard != null) {
            return hard;
        }
        String root = firstPresent(data, id + "BookSavings", id + "BookTotal");
        if (root != null) {
            return root;
        }
        return row == null ? null : row.bookSavings();
    }

    private static String physicalTotal(JsonObject data, String id, BankSnapshot.CentralRow row) {
        String hard = BankSnapshot.hardBook(data, id, "depositVault");
        if (hard != null) {
            return hard;
        }
        String root = firstPresent(data, id + "PhysicalSavings", id + "ActualSavings");
        if (root != null) {
            return root;
        }
        return row == null ? null : row.physicalSavings();
    }

    private static List<String> linesFor(String name, boolean note, String rootReserve, BankSnapshot.CentralRow row) {
        String reserve = rootReserve != null ? rootReserve : row == null ? null : row.reserve();
        List<String> lines = new ArrayList<>();
        lines.add(field(note ? name + "发行准备金" : name + "准备金", reserve));
        lines.add(field(name + "央行储蓄", row == null ? null : row.savings()));
        if (row != null && row.vault() != null) {
            lines.add(field(name + "存款保管", row.vault()));
        }
        if (row != null && row.liability() != null) {
            lines.add(field(name + "发行额", row.liability()));
        }
        if (row != null && row.locked() != null) {
            lines.add(field(name + "已锁定准备金", row.locked()));
        }
        if (row != null && row.backedRoom() != null) {
            lines.add(field(name + "按准备金尚可发行", row.backedRoom()));
        }
        return lines;
    }

    private static boolean isCore(BankSnapshot.CentralRow row) {
        return "copper".equals(row.id()) || "diamond".equals(row.id()) || "note".equals(row.id())
                || "铜锭".equals(row.name()) || "钻石".equals(row.name()) || "银行券".equals(row.name());
    }

    private static BankSnapshot.CentralRow find(JsonObject data, String currencyId, String currencyName) {
        BankSnapshot.CentralRow named = null;
        for (BankSnapshot.CentralRow row : BankSnapshot.central(data)) {
            if (currencyId != null && currencyId.equals(row.id())) {
                return row;
            }
            if (currencyName != null && currencyName.equals(row.name()) && named == null) {
                named = row;
            }
        }
        return named;
    }

    private static String field(String label, String raw) {
        return label + "：" + (raw == null ? MISSING : BankHeader.figure(raw));
    }

    private static String firstPresent(JsonObject data, String... keys) {
        for (String key : keys) {
            String value = BankSnapshot.text(data, key);
            if (value != null) {
                return value;
            }
        }
        return null;
    }
}
