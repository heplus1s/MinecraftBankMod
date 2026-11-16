package dev.jdguo.bank.ui;

import java.util.List;

/**
 * Persistent chrome. The rate column stays on the left. Issuance and management
 * are separate top-right buttons. Close is alone at the bottom right.
 */
public final class BankChrome {
    public static final boolean SIDEBAR = false;
    public static final String SAVINGS_TITLE = "个人储蓄";
    public static final String ISSUANCE = "个人发行";
    public static final String MANAGEMENT = "中央银行管理";
    public static final String CLOSE = "关闭";
    public static final List<String> ROW_ONE = List.of("储蓄", "存入", "取出");
    public static final List<String> ROW_TWO = List.of("硬通货兑换", "开立汇票", "兑付汇票", "个人银行券交易");
    public static final List<String> FUNCTION_TABS = List.of(
            "储蓄", "存入", "取出", "硬通货兑换", "开立汇票", "兑付汇票", "个人银行券交易");

    private BankChrome() {
    }
}
