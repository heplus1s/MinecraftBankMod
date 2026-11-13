package dev.jdguo.bank.text;

import dev.jdguo.bank.currency.CurrencyDef;
import dev.jdguo.bank.ledger.BankBooks;
import dev.jdguo.bank.market.PersonalMarket;
import dev.jdguo.bank.market.PersonalNote;
import dev.jdguo.bank.market.Redemption;
import dev.jdguo.bank.market.SwapTrade;
import dev.jdguo.bank.ledger.CompactFormat;
import dev.jdguo.bank.ledger.Supply;
import dev.jdguo.bank.ledger.TxResult;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Player-facing chat. Chinese follows the original datapack tellraw lines.
 * English is the same information, not a word-for-word gloss.
 * Command names and placeholders stay ASCII in both languages.
 */
public final class BankPhrases {
    public enum Kind {
        DEPOSIT, WITHDRAW, EXCHANGE, REDEEM, ISSUE, CREDIT_UNBACKED, CREDIT_BACKED, RETIRE, GENERIC
    }

    private BankPhrases() {}

    public static Spoken feedback(Kind kind, TxResult result, BankBooks books, UUID player,
                                  String currency, String other) {
        if (result.success()) {
            return switch (kind) {
                case DEPOSIT -> deposit(books, player, currency, result.amount());
                case WITHDRAW -> withdraw(books, player, currency, result.amount());
                case EXCHANGE -> exchange(books, player, other, currency, result.amount());
                case REDEEM -> redeem(books, player, currency, result.amount());
                case ISSUE -> issue(books, currency, result.amount(), null);
                case CREDIT_UNBACKED -> credit(books, "unbacked", currency, result.amount(), null);
                case CREDIT_BACKED -> credit(books, "backed", currency, result.amount(), null);
                case RETIRE -> credit(books, "retire", currency, result.amount(), null);
                case GENERIC -> line(Tone.GREEN, "已完成。", "Done.");
            };
        }
        return failure(kind, result.code(), currency, other);
    }

    public static Spoken deposit(BankBooks books, UUID player, String currency, BigInteger amount) {
        String n = CompactFormat.format(amount);
        Tone tone = "diamond".equals(currency) ? Tone.AQUA : Tone.GOLD;
        String zhName = depositNameZh(books, currency);
        String enName = shortEn(books, currency);
        return line(tone,
                "存入 " + zhName + " ×" + n + " 成功" + balancesZh(books, player),
                "Deposited " + enName + " ×" + n + balancesEn(books, player));
    }

    public static Spoken withdraw(BankBooks books, UUID player, String currency, BigInteger amount) {
        return withdraw(books, player, currency, amount, BigInteger.ZERO);
    }

    public static Spoken depositPaper(BankBooks books, UUID player, String zhName, String enName, BigInteger amount, boolean trimmed) {
        String n = CompactFormat.format(amount);
        String extra = trimmed ? "，多出的是复制件，已收回" : "";
        String extraEn = trimmed ? ". Extra copies were taken back" : "";
        return line(Tone.GOLD,
                "存入 " + zhName + " ×" + n + " 成功" + extra + balancesZh(books, player),
                "Deposited " + enName + " ×" + n + extraEn + balancesEn(books, player));
    }

    public static Spoken withdrawPersonal(BankBooks books, UUID player, String name, BigInteger amount, BigInteger left) {
        String n = CompactFormat.format(amount);
        return line(Tone.GOLD,
                "取出 个人银行券「" + name + "」×" + n + " 成功 ｜账户剩余 " + CompactFormat.format(left) + balancesZh(books, player),
                "Withdrew personal note \"" + name + "\" ×" + n + " | " + CompactFormat.format(left) + " still on the account" + balancesEn(books, player));
    }

    public static Spoken withdraw(BankBooks books, UUID player, String currency, BigInteger amount, BigInteger copper) {
        String n = CompactFormat.format(amount);
        Tone tone = "diamond".equals(currency) ? Tone.AQUA : Tone.GOLD;
        return line(tone,
                "取出 " + withdrawNameZh(books, currency) + "×" + n + changeZh(copper) + balancesZh(books, player),
                "Withdrew " + shortEn(books, currency) + " ×" + n + changeEn(copper) + balancesEn(books, player));
    }

    /** {@code pay} is what the player spends, {@code receive} is what they get, {@code qty} is the received amount. */
    public static Spoken exchange(BankBooks books, UUID player, String pay, String receive, BigInteger qty) {
        return exchange(books, player, pay, receive, qty, BigInteger.ZERO);
    }

    public static Spoken exchange(BankBooks books, UUID player, String pay, String receive, BigInteger qty, BigInteger copper) {
        String n = CompactFormat.format(qty);
        boolean buyDiamond = "diamond".equals(receive);
        boolean sellDiamond = "diamond".equals(pay);
        if (buyDiamond && !sellDiamond) {
            return line(Tone.GREEN,
                    "买入 钻×" + n + " 成功" + changeZh(copper) + balancesZh(books, player),
                    "Bought diamond ×" + n + changeEn(copper) + balancesEn(books, player));
        }
        if (sellDiamond && !buyDiamond) {
            return line(Tone.DARK_RED,
                    "卖出 钻×" + n + " 成功" + changeZh(copper) + balancesZh(books, player),
                    "Sold diamond ×" + n + changeEn(copper) + balancesEn(books, player));
        }
        return line(Tone.GREEN,
                "兑换 " + nameZh(books, receive) + "×" + n + " 成功" + changeZh(copper) + balancesZh(books, player),
                "Exchanged for " + shortEn(books, receive) + " ×" + n + changeEn(copper) + balancesEn(books, player));
    }

    public static Spoken failure(Kind kind, String code, String currency, String other) {
        if ("insufficient_funds".equals(code) && kind == Kind.EXCHANGE) {
            if ("copper".equals(other)) {
                return line(Tone.RED, "铜锭不足。", "Not enough copper.");
            }
            if ("diamond".equals(other)) {
                return line(Tone.RED, "你的钻石不足。", "You don't have enough diamonds.");
            }
        }
        return switch (code == null ? "" : code) {
            case "insufficient_funds" -> line(Tone.RED, "你的账户余额不足。", "Your balance isn't enough.");
            case "insufficient_reserves" -> line(Tone.RED, "银行准备金不足。", "The vault doesn't have enough reserves.");
            case "unknown_currency" -> line(Tone.RED, "没有这种货币。", "No such currency.");
            case "bad_amount" -> line(Tone.RED, "金额无效。", "That amount isn't valid.");
            case "not_commodity" -> line(Tone.RED, "这里只收铜锭、钻石，或纸质银行券。", "This slot only takes copper ingots, diamonds, or bank-note paper.");
            case "not_note" -> line(Tone.RED, "手上要拿着纸质银行券。", "Hold bank-note paper in your main hand.");
            case "bankrupt" -> line(Tone.RED, "这张个人银行券已经破产，纸还在，但不能入账。", "This personal note is bankrupt. The paper stays, but it cannot be deposited.");
            case "note_spent" -> line(Tone.RED, "这些银行券已经入过账，或是复制件，不能再存。", "These bank notes were already deposited, or they are copies.");
            case "note_tampered" -> line(Tone.RED, "这张纸和账对不上。", "This paper doesn't match the books.");
            case "too_many_items" -> line(Tone.RED, "一次取太多了。大额请开汇票。", "That's too many items at once. Issue a bill for a large amount.");
            case "frozen" -> line(Tone.RED, "已暂停银行券兑付。", "Bank-note redemption is suspended.");
            case "no_market" -> line(Tone.RED, "这种货币无法报价。", "That currency cannot be quoted.");
            case "not_issuable" -> line(Tone.RED, "这种货币不能这样发行。", "This currency can't be issued that way.");
            case "over_cap" -> line(Tone.RED, "没有剩余的准备金额度。", "No reserve room left.");
            case "invalid_check" -> line(Tone.RED, "认不出这张票据。", "Unrecognized bill.");
            case "tampered" -> line(Tone.RED, "票据和存根对不上。", "The bill doesn't match the stub.");
            case "already_redeemed" -> line(Tone.RED, "这张汇票已经兑付过。", "This bill has already been paid.");
            case "wrong_payee" -> line(Tone.RED, "这张票据不是开给你的。", "This bill isn't made out to you.");
            case "denied" -> line(Tone.RED, "只有管理员能做这件事。", "Only an operator can do that.");
            default -> line(Tone.RED, "办不到。", "That didn't work.");
        };
    }

    public static Spoken redeem(BankBooks books, UUID player, String currency, BigInteger amount) {
        return redeem(books, player, currency, amount, BigInteger.ZERO);
    }

    public static Spoken redeem(BankBooks books, UUID player, String currency, BigInteger amount, BigInteger copper) {
        return line(Tone.AQUA,
                "兑付 " + nameZh(books, currency) + " ×" + CompactFormat.format(amount) + " 成功" + changeZh(copper) + balancesZh(books, player),
                "Paid " + shortEn(books, currency) + " ×" + CompactFormat.format(amount) + changeEn(copper) + balancesEn(books, player));
    }

    public static Spoken issue(BankBooks books, String currency, BigInteger amount, String playerName) {
        String tail = playerName == null || playerName.isBlank() ? "" : " → " + playerName;
        String n = CompactFormat.format(amount);
        return line(Tone.GREEN,
                "开出 " + nameZh(books, currency) + " 汇票 ×" + n + tail,
                "Issued a " + shortEn(books, currency) + " bill ×" + n + tail);
    }

    public static Spoken credit(BankBooks books, String mode, String currency, BigInteger amount, String playerName) {
        String tail = playerName == null || playerName.isBlank() ? "" : " → " + playerName;
        String n = CompactFormat.format(amount);
        String zhName = nameZh(books, currency);
        String enName = shortEn(books, currency);
        return switch (mode) {
            case "unbacked" -> line(Tone.GREEN, "无准备金发行 " + zhName + " ×" + n + tail, "Issued " + enName + " ×" + n + " without reserves" + tail);
            case "backed" -> line(Tone.GREEN, "按准备金发行 " + zhName + " ×" + n + tail, "Issued reserve-backed " + enName + " ×" + n + tail);
            case "retire" -> line(Tone.GREEN, "注销 " + zhName + " ×" + n + tail, "Retired " + enName + " ×" + n + tail);
            default -> line(Tone.GREEN, "已处理 " + zhName + " ×" + n + tail, "Updated " + enName + " ×" + n + tail);
        };
    }

    public static Spoken reserveSet(BankBooks books, String currency) {
        Supply supply = books.supply(currency);
        String rate = copperPerDiamond(books);
        return line(Tone.AQUA,
                nameZh(books, currency) + "发行准备金开局设为 " + CompactFormat.format(supply.anchor())
                        + " ｜发行准备金 " + CompactFormat.format(supply.reserve())
                        + " ｜储蓄保管 " + CompactFormat.format(supply.depositVault())
                        + " ｜锁定 " + CompactFormat.format(supply.locked())
                        + " ｜汇率 " + rate + " 铜/钻",
                nameEn(books, currency) + " opening issuance reserve is now " + CompactFormat.format(supply.anchor())
                        + " | Issuance reserves " + CompactFormat.format(supply.reserve())
                        + " | Deposit vault " + CompactFormat.format(supply.depositVault())
                        + " | Locked " + CompactFormat.format(supply.locked())
                        + " | Rate " + rate + " copper per diamond");
    }

    public static Spoken reserveRejected(String code) {
        return switch (code == null ? "" : code) {
            case "below_locked" -> line(Tone.RED, "准备金不能小于已经锁定的部分。", "Reserve can't be less than the locked amount.");
            case "negative" -> line(Tone.RED, "准备金不能是负数。", "Reserve can't be negative.");
            default -> line(Tone.RED, "没有这种货币。", "No such currency.");
        };
    }

    public static Spoken personalSet(String playerName, BankBooks books, String currency, BigInteger amount) {
        return line(Tone.AQUA,
                "已记下 " + playerName + " 的个人准备金：" + nameZh(books, currency) + " " + CompactFormat.format(amount) + "。这不是存款。",
                "Recorded " + playerName + "'s personal reserve: " + nameEn(books, currency) + " " + CompactFormat.format(amount) + ". This is not a deposit.");
    }

    public static Spoken personalGet(String playerName, BankBooks books, UUID player) {
        return line(Tone.AQUA,
                playerName + " 的个人准备金：" + personalZh(books, player) + "。这不是存款，也不是央行库存。",
                playerName + "'s personal reserves: " + personalEn(books, player) + ". Not a deposit, and not central stock.");
    }

    public static List<Spoken> supply(BankBooks books) {
        List<Spoken> lines = new ArrayList<>();
        String fee = books.feeFraction().movePointRight(2).stripTrailingZeros().toPlainString();
        lines.add(line(Tone.AQUA,
                "状态 " + books.statusLabel() + "  费率 " + fee + "%  汇率 " + copperPerDiamond(books) + " 铜/钻",
                "Status " + books.statusLabelEn() + "  fee " + fee + "%  rate " + copperPerDiamond(books) + " copper per diamond"));
        for (CurrencyDef def : books.currencies()) {
            Supply supply = books.supply(def.id());
            BigDecimal rate = def.id().equals(books.numeraire()) ? null : books.unitsPer(def.id(), books.numeraire());
            String rateZh = rate == null ? "" : "  1" + nameZh(books, books.numeraire()) + "=" + CompactFormat.formatRate(rate) + nameZh(books, def.id());
            String rateEn = rate == null ? "" : "  1 " + books.numeraire() + " = " + CompactFormat.formatRate(rate) + " " + shortEn(books, def.id());
            String bookZh = def.kind() == dev.jdguo.bank.currency.CurrencyKind.COMMODITY
                    ? "  发行准备金 " + CompactFormat.format(supply.reserve())
                            + "  储蓄保管 " + CompactFormat.format(supply.depositVault())
                    : "  M0 " + CompactFormat.format(books.centralM0())
                            + "  M1 " + CompactFormat.format(books.centralM1())
                            + "  M2 " + CompactFormat.formatRate(books.centralM2())
                            + "  尚可发行 " + CompactFormat.format(books.backedRoom(def));
            String bookEn = def.kind() == dev.jdguo.bank.currency.CurrencyKind.COMMODITY
                    ? "  issuance " + CompactFormat.format(supply.reserve())
                            + "  vault " + CompactFormat.format(supply.depositVault())
                    : "  M0 " + CompactFormat.format(books.centralM0())
                            + "  M1 " + CompactFormat.format(books.centralM1())
                            + "  M2 " + CompactFormat.formatRate(books.centralM2())
                            + "  issuable " + CompactFormat.format(books.backedRoom(def));
            lines.add(line(Tone.GRAY,
                    nameZh(books, def.id())
                            + bookZh
                            + "  锁定 " + CompactFormat.format(supply.locked())
                            + "  覆盖 " + CompactFormat.formatRatio(books.tightness(def.id()))
                            + rateZh,
                    nameEn(books, def.id())
                            + bookEn
                            + "  locked " + CompactFormat.format(supply.locked())
                            + "  cover " + CompactFormat.formatRatio(books.tightness(def.id()))
                            + rateEn));
        }
        return lines;
    }

    public static Spoken noOutletsYet() {
        return line(Tone.GRAY, "还没有网点。管理员看着方块执行 /bank outlet set <name>", "No outlets yet. An operator should look at a block and run /bank outlet set <name>");
    }

    public static Spoken notInRange() {
        return line(Tone.GRAY, "你不在网点范围内。/bank outlets 可以看坐标", "You aren't within an outlet. /bank outlets lists the coordinates");
    }

    public static Spoken noOutlets() {
        return line(Tone.GRAY, "还没有网点", "No outlets yet");
    }

    public static Spoken outletLine(String name, String world, int x, int y, int z, double radius) {
        return line(Tone.AQUA,
                name + "  " + world + "  " + x + " " + y + " " + z + "  半径 " + radius,
                name + "  " + world + "  " + x + " " + y + " " + z + "  radius " + radius);
    }

    public static Spoken badOutletName() {
        return line(Tone.RED, "网点名字只能是 1 到 32 个 ASCII 字符：字母或数字开头，后面只能是字母、数字、下划线或连字符。", "Outlet names must be 1-32 characters: a letter or digit, then letters, digits, underscore, or hyphen.");
    }

    public static Spoken noSuchOutlet() {
        return line(Tone.RED, "没有这个网点", "No outlet with that name");
    }

    public static Spoken outletExists() {
        return line(Tone.RED, "已经有这个名字。移动请用 /bank outlet move", "That name is already used. Move it with /bank outlet move");
    }

    public static Spoken outletSet(String name, int x, int y, int z) {
        return line(Tone.GREEN,
                "网点 " + name + " → " + x + " " + y + " " + z + "。那里停着一辆写着名字的箱子矿车，右键方块或矿车开柜台",
                "Outlet " + name + " -> " + x + " " + y + " " + z + ". A named chest minecart marks the counter. Right-click the block or the cart to open it.");
    }

    public static Spoken outletRemoved(String name) {
        return line(Tone.GREEN, "已移除网点 " + name, "Removed outlet " + name);
    }

    public static Spoken dimensionUnloaded() {
        return line(Tone.RED, "网点所在维度没加载", "That outlet's dimension isn't loaded");
    }

    public static Spoken teleported(String name) {
        return line(Tone.GREEN, "已传送到 " + name, "Teleported to " + name);
    }

    public static Spoken radiusSet(String name, double blocks) {
        return line(Tone.GREEN, name + " 的交互半径改为 " + blocks, name + " radius is now " + blocks);
    }

    public static Spoken liftSet(String name, double blocks) {
        return line(Tone.GREEN, name + " 的全息高度改为 " + blocks + " 格", name + " hologram height is now " + blocks + " blocks");
    }

    public static Spoken hologramSet(String name, boolean on) {
        return line(Tone.GREEN, name + (on ? " 显示全息" : " 关闭全息"), name + (on ? " hologram on" : " hologram off"));
    }

    public static Spoken nameOnePlayer() {
        return line(Tone.RED, "请指定一名玩家", "Name one player");
    }

    public static Spoken playerOffline() {
        return line(Tone.RED, "玩家不在线，汇票发不出去", "That player is offline, so the bill can't be given");
    }

    public static Spoken amountProblem(String detail) {
        if (detail != null && detail.startsWith("Unknown suffix:")) {
            String suffix = detail.substring("Unknown suffix:".length()).trim();
            return line(Tone.RED, "不认识这个后缀：" + suffix, "Unknown suffix: " + suffix);
        }
        if ("Empty amount".equals(detail)) {
            return line(Tone.RED, "金额是空的。", "The amount is empty.");
        }
        return line(Tone.RED, "金额必须是正数。", "Amount must be positive.");
    }

    public static Spoken alphaNotNumber() {
        return line(Tone.RED, "alpha 不是数字", "alpha isn't a number");
    }

    public static Spoken alphaRange() {
        return line(Tone.RED, "alpha 请设在 0 到 100000‰", "alpha must be from 0 to 100000 permille");
    }

    public static Spoken alphaSet(String currency, String value) {
        return line(Tone.GREEN, currency + " 的 α 设为 " + value + "‰", currency + " alpha is now " + value + " permille");
    }

    public static Spoken clampCleared(boolean min) {
        return line(Tone.GREEN, (min ? "汇率下限" : "汇率上限") + "已取消", (min ? "Lower" : "Upper") + " rate limit cleared");
    }

    public static Spoken clampNotNumber() {
        return line(Tone.RED, "不是数字。取消限制请写 off", "Not a number. Write off to clear the limit.");
    }

    public static Spoken clampPositive() {
        return line(Tone.RED, "汇率界限必须为正，或写 off", "The rate limit must be positive, or off");
    }

    public static Spoken clampSet(boolean min, String value) {
        return line(Tone.GREEN, (min ? "汇率下限 " : "汇率上限 ") + value, (min ? "Lower rate limit " : "Upper rate limit ") + value);
    }

    public static Spoken missingConfig() {
        return line(Tone.RED, "没有 config/bank.json", "config/bank.json is missing");
    }

    public static Spoken reloaded() {
        return line(Tone.GREEN, "已重读手续费、alpha、汇率界限和新增货币。旧账户没有被清掉", "Reloaded fees, alpha, rate limits, and any new currencies. Existing accounts were kept.");
    }

    public static Spoken configUnreadable(String detail) {
        return line(Tone.RED, "配置读不了: " + detail, "Couldn't read the config: " + detail);
    }

    public static Spoken displayUpdated() {
        return line(Tone.GREEN, "个人显示已更新", "Personal display updated");
    }

    public static Spoken globalToggle(String which, boolean on) {
        return line(Tone.GREEN, which + (on ? " 已打开" : " 已关闭"), which + (on ? " on" : " off"));
    }

    public static Spoken freeze(boolean on) {
        return line(on ? Tone.RED : Tone.GREEN, on ? "已暂停银行券兑付" : "银行券兑付已恢复", on ? "Bank-note redemption is suspended" : "Bank-note redemption has resumed");
    }

    public static Spoken feeSet(double percent) {
        return line(Tone.GREEN, "手续费已设为 " + percent + "%", "Fee is now " + percent + "%");
    }

    public static Spoken interestSet(double percent) {
        return line(Tone.GREEN, "银行券活期存款年利率已设为 " + percent + "%", "Bank-note demand-deposit rate is now " + percent + "% a year");
    }

    public static Spoken interestPaid(BankBooks books, java.util.UUID player, java.math.BigInteger amount) {
        String n = CompactFormat.format(amount);
        return line(Tone.GREEN,
                "存款利息 银行券 ×" + n + balancesZh(books, player),
                "Savings interest bank note ×" + n + balancesEn(books, player));
    }

    public static Spoken imported(int count) {
        return line(Tone.GREEN, "已导入 " + count + " 条记分板余额（每人每种货币只导入一次）", "Imported " + count + " scoreboard balances (once per player per currency)");
    }

    public static Spoken noteNamed(PersonalNote note) {
        String par = CompactFormat.format(note.par()) + " " + anchorZh(note.reserveCurrency());
        String parEn = CompactFormat.format(note.par()) + " " + anchorEn(note.reserveCurrency());
        String zh = switch (note.regime()) {
            case PEG -> "个人银行券「" + note.name() + "」已记名，钉住每张 " + par + "。买卖不改这个官价。";
            case HARD -> "个人银行券「" + note.name() + "」已记名，每张定额兑 " + par + "。发行要锁足这笔准备金。";
            case FLOAT -> "个人银行券「" + note.name() + "」已记名，开价每张 " + par + "。个人准备金够了才能发行。";
        };
        String en = switch (note.regime()) {
            case PEG -> "Personal note \"" + note.name() + "\" is pegged at " + parEn + " each. Trades do not move that rate.";
            case HARD -> "Personal note \"" + note.name() + "\" converts at " + parEn + " each. Issuance locks that reserve in full.";
            case FLOAT -> "Personal note \"" + note.name() + "\" is registered at " + parEn + " each. You can issue once your personal reserve covers it.";
        };
        return line(Tone.AQUA, zh, en);
    }

    public static Spoken noteIssued(BankBooks books, PersonalNote note, BigInteger amount) {
        String backing = anchorZh(note.reserveCurrency());
        String backingEn = anchorEn(note.reserveCurrency());
        return line(Tone.GREEN,
                "已发行「" + note.name() + "」×" + CompactFormat.format(amount)
                        + "。流通 M1 " + CompactFormat.format(note.circulation())
                        + "，锁定" + backing + " " + CompactFormat.format(note.locked()) + "。",
                "Issued \"" + note.name() + "\" ×" + CompactFormat.format(amount)
                        + ". M1 " + CompactFormat.format(note.circulation())
                        + ", " + backingEn + " locked " + CompactFormat.format(note.locked()) + ".");
    }

    public static Spoken noteBankrupt(BankBooks books, PersonalNote note) {
        return noteBankrupt(note, books.issuerDebt(note.issuer(), note.reserveCurrency()));
    }

    public static Spoken noteBankrupt(PersonalNote note, BigInteger debt) {
        String unit = anchorZh(note.reserveCurrency());
        String unitEn = anchorEn(note.reserveCurrency());
        BigInteger owed = debt == null ? BigInteger.ZERO : debt;
        return line(Tone.RED,
                "「" + note.name() + "」宣告破产。央行收走抵押" + unit + " ×" + CompactFormat.format(note.released())
                        + "，余下欠款" + unit + " ×" + CompactFormat.format(owed)
                        + "。持有和纸券还在账上，但不能再交易、通兑或发行。",
                "\"" + note.name() + "\" is bankrupt. The central bank took collateral " + unitEn + " ×"
                        + CompactFormat.format(note.released()) + ". Remaining debt " + unitEn + " ×"
                        + CompactFormat.format(owed)
                        + ". Holdings and paper stay on the books as void records.");
    }

    public static Spoken noteRate(BankBooks books, PersonalNote note) {
        if (note.bankrupt()) {
            return line(Tone.RED,
                    "「" + note.name() + "」已经破产，不再报价。账上的持有已作废。",
                    "\"" + note.name() + "\" is bankrupt and has no quote. Holdings on the books are void.");
        }
        BigDecimal diamonds = books.personalDiamondPrice(note);
        BigInteger issuable = note.par().signum() <= 0
                ? BigInteger.ZERO
                : books.personalReserve(note.issuer(), note.reserveCurrency()).subtract(note.locked()).max(BigInteger.ZERO)
                        .divide(note.par());
        String price = CompactFormat.formatRate(diamonds);
        String kind = switch (note.regime()) {
            case PEG -> "钉住价 ";
            case HARD -> "面额 ";
            case FLOAT -> "现价 ";
        };
        String kindEn = switch (note.regime()) {
            case PEG -> "pegged at ";
            case HARD -> "face value ";
            case FLOAT -> "";
        };
        return line(Tone.AQUA,
                "「" + note.name() + "」" + kind + price + " 钻（约 " + price + " 银行券）。M1 "
                        + CompactFormat.format(books.personalM1(note)) + "，纸币 "
                        + CompactFormat.format(books.personalM0(note)) + "，尚可发行 "
                        + CompactFormat.format(issuable) + "。",
                "\"" + note.name() + "\" is " + kindEn + price + " diamond each (about " + price
                        + " central bank notes). M1 " + CompactFormat.format(books.personalM1(note))
                        + ", paper " + CompactFormat.format(books.personalM0(note))
                        + ", still issuable " + CompactFormat.format(issuable) + ".");
    }

    public static Spoken noteTrade(boolean buy, String name, BigInteger amount, BigInteger central, BigInteger copper, BankBooks books, UUID player) {
        String zhVerb = buy ? "买入" : "卖出";
        String enVerb = buy ? "Bought" : "Sold";
        String pay = "支付银行券 ×" + CompactFormat.format(central);
        String payEn = "paid central notes ×" + CompactFormat.format(central);
        if (!buy) {
            pay = "收到银行券 ×" + CompactFormat.format(central);
            payEn = "received central notes ×" + CompactFormat.format(central);
        }
        return line(buy ? Tone.GREEN : Tone.DARK_RED,
                zhVerb + "「" + name + "」×" + CompactFormat.format(amount) + "，" + pay + changeZh(copper) + balancesZh(books, player),
                enVerb + " \"" + name + "\" ×" + CompactFormat.format(amount) + ", " + payEn + changeEn(copper) + balancesEn(books, player));
    }

    public static Spoken noteSwap(SwapTrade trade, BankBooks books, UUID player) {
        String extra = "";
        String extraEn = "";
        if (trade.central().signum() > 0) {
            extra += "，支付银行券 ×" + CompactFormat.format(trade.central());
            extraEn += ", paid bank notes ×" + CompactFormat.format(trade.central());
        }
        if (trade.copper().signum() > 0) {
            extra += "，支付铜锭 ×" + CompactFormat.format(trade.copper());
            extraEn += ", paid copper ×" + CompactFormat.format(trade.copper());
        }
        return line(Tone.GREEN,
                "互换「" + trade.paid().name() + "」×" + CompactFormat.format(trade.paidNotes())
                        + " 换成「" + trade.received().name() + "」×" + CompactFormat.format(trade.receivedNotes())
                        + extra + balancesZh(books, player),
                "Swapped \"" + trade.paid().name() + "\" ×" + CompactFormat.format(trade.paidNotes())
                        + " for \"" + trade.received().name() + "\" ×" + CompactFormat.format(trade.receivedNotes())
                        + extraEn + balancesEn(books, player));
    }

    public static Spoken noteRedeem(Redemption redemption, BankBooks books, UUID player) {
        String got = redeemGotZh(redemption);
        String gotEn = redeemGotEn(redemption);
        String head = redemption.discounted()
                ? "准备金不够，按库存通兑"
                : "capped".equals(redemption.code())
                ? "按原价通兑，准备金只够这些"
                : switch (redemption.note().regime()) {
                    case PEG -> "按钉住价通兑";
                    case HARD -> "按面额通兑";
                    case FLOAT -> "通兑";
                };
        String headEn = redemption.discounted()
                ? "Reserve was short, so redemption paid what was posted"
                : "capped".equals(redemption.code())
                ? "Redeemed at the official rate, only as far as reserves covered"
                : switch (redemption.note().regime()) {
                    case PEG -> "Redeemed at the peg";
                    case HARD -> "Redeemed at face value";
                    case FLOAT -> "Redeemed";
                };
        return line(redemption.discounted() ? Tone.GOLD : Tone.AQUA,
                head + "「" + redemption.note().name() + "」×" + CompactFormat.format(redemption.retired())
                        + "，得到" + got + balancesZh(books, player),
                headEn + " \"" + redemption.note().name() + "\" ×" + CompactFormat.format(redemption.retired())
                        + ", received " + gotEn + balancesEn(books, player));
    }

    private static String redeemGotZh(Redemption redemption) {
        List<String> parts = new ArrayList<>();
        if (redemption.diamonds().signum() > 0) {
            parts.add("钻石 ×" + CompactFormat.format(redemption.diamonds()));
        }
        if (redemption.copper().signum() > 0) {
            parts.add("铜锭 ×" + CompactFormat.format(redemption.copper()));
        }
        if (redemption.central().signum() > 0) {
            parts.add("银行券 ×" + CompactFormat.format(redemption.central()));
        }
        return parts.isEmpty() ? "0" : String.join("，", parts);
    }

    private static String redeemGotEn(Redemption redemption) {
        List<String> parts = new ArrayList<>();
        if (redemption.diamonds().signum() > 0) {
            parts.add("diamond ×" + CompactFormat.format(redemption.diamonds()));
        }
        if (redemption.copper().signum() > 0) {
            parts.add("copper ×" + CompactFormat.format(redemption.copper()));
        }
        if (redemption.central().signum() > 0) {
            parts.add("bank note ×" + CompactFormat.format(redemption.central()));
        }
        return parts.isEmpty() ? "0" : String.join(", ", parts);
    }

    public static Spoken noteRejected(String code) {
        return switch (code == null ? "" : code) {
            case "name" -> line(Tone.RED, "名字最多三个汉字或英文字母。", "The name can be at most three Chinese characters or English letters.");
            case "taken" -> line(Tone.RED, "这个名字已经有人用了。", "That name is already taken.");
            case "exists" -> line(Tone.RED, "你已经有一张个人银行券了。", "You already have a personal note.");
            case "reserve" -> line(Tone.RED, "个人准备金不够，还不能发行。", "Your personal reserve is not enough to issue that.");
            case "funds" -> line(Tone.RED, "你的账户余额不足。", "Your balance isn't enough.");
            case "empty" -> line(Tone.RED, "对方没有这么多券可成交。", "The other side doesn't have that many notes.");
            case "self" -> line(Tone.RED, "不能和自己买卖自己的券。", "You can't trade your own note with yourself.");
            case "pair" -> line(Tone.RED, "不能用同一张个人银行券互换。", "Pick two different personal notes.");
            case "quote" -> line(Tone.RED, "这个数量换不成整张，换一个数量。", "That amount doesn't buy a whole note. Try another amount.");
            case "payout" -> line(Tone.RED, "这种券不能兑成那种货币。", "That note cannot be redeemed into that currency.");
            case "anchor" -> line(Tone.RED, "这种汇率不能用这种货币。", "That regime cannot use that currency.");
            case "suspended" -> line(Tone.RED, "准备金不够按原价兑付，通兑暂停。", "Reserves cannot honor the official rate, so redemption is suspended.");
            case "bankrupt" -> line(Tone.RED, "这张个人银行券已经破产，不能再交易或兑付。", "This personal note is bankrupt and cannot be traded or redeemed.");
            case "closed" -> line(Tone.RED, "这张个人银行券已经破产过了。", "This personal note is already bankrupt.");
            case "none" -> line(Tone.RED, "没有这张个人银行券。", "No personal note by that name.");
            case "market" -> line(Tone.RED, "中央银行券现在报不出价。", "The central note has no quote right now.");
            case "par" -> line(Tone.RED, "金额无效。", "That amount isn't valid.");
            case "coverage" -> line(Tone.RED, "覆盖倍数要大于 0，并且不超过 100。", "Coverage must be above 0 and at most 100.");
            default -> line(Tone.RED, "办不到。", "That didn't work.");
        };
    }

    public static Spoken debtSeized(String takenZh, String takenEn, String leftZh, String leftEn) {
        return line(Tone.GOLD,
                "破产清偿：扣下" + takenZh + "。尚欠" + leftZh + "。",
                "Bankruptcy levy: took " + takenEn + ". Still owed " + leftEn + ".");
    }

    public static Spoken noteCoverage(BigDecimal coverage) {
        return line(Tone.AQUA,
                "个人银行券发行覆盖设为 " + coverage.stripTrailingZeros().toPlainString() + " 倍准备金。",
                "Personal note issue coverage is now " + coverage.stripTrailingZeros().toPlainString() + " times reserves.");
    }

    public static Spoken joinHint() {
        return line(Tone.AQUA, "找到网点，右键柜台。站在附近也可以 /bank", "Find an outlet and right-click the counter. You can also use /bank while standing nearby.");
    }

    public static String copperPerDiamond(BankBooks books) {
        if (books.currency("copper") == null || books.currency("diamond") == null) {
            return "—";
        }
        BigDecimal rate = books.unitsPer("copper", "diamond");
        if (rate == null) {
            return "—";
        }
        return CompactFormat.formatRate(rate);
    }

    private static Spoken line(Tone tone, String zhBody, String enBody) {
        return new Spoken("[银行] " + zhBody, "[Bank] " + enBody, tone);
    }

    private static String balancesZh(BankBooks books, UUID player) {
        return " ｜余额：" + stockZh(books, player);
    }

    private static String balancesEn(BankBooks books, UUID player) {
        return " | Balance: " + stockEn(books, player);
    }

    private static String stockZh(BankBooks books, UUID player) {
        return figuresZh(books, id -> books.balance(player, id));
    }

    private static String stockEn(BankBooks books, UUID player) {
        return figuresEn(books, id -> books.balance(player, id));
    }

    private static String personalZh(BankBooks books, UUID player) {
        return figuresZh(books, id -> books.personalReserve(player, id));
    }

    private static String personalEn(BankBooks books, UUID player) {
        return figuresEn(books, id -> books.personalReserve(player, id));
    }

    private static String figuresZh(BankBooks books, java.util.function.Function<String, BigInteger> amount) {
        List<String> parts = new ArrayList<>();
        if (books.currency("copper") != null) {
            parts.add("铜 " + CompactFormat.format(amount.apply("copper")));
        }
        if (books.currency("diamond") != null) {
            parts.add("钻 " + CompactFormat.format(amount.apply("diamond")));
        }
        if (books.currency("note") != null) {
            parts.add("银行券 " + CompactFormat.format(amount.apply("note")));
        }
        return String.join("，", parts);
    }

    private static String figuresEn(BankBooks books, java.util.function.Function<String, BigInteger> amount) {
        List<String> parts = new ArrayList<>();
        if (books.currency("copper") != null) {
            parts.add("Cu " + CompactFormat.format(amount.apply("copper")));
        }
        if (books.currency("diamond") != null) {
            parts.add("Dia " + CompactFormat.format(amount.apply("diamond")));
        }
        if (books.currency("note") != null) {
            parts.add("bank note " + CompactFormat.format(amount.apply("note")));
        }
        return String.join(", ", parts);
    }

    private static String changeZh(BigInteger copper) {
        if (copper == null || copper.signum() <= 0) {
            return "";
        }
        return "，找零铜锭 ×" + CompactFormat.format(copper);
    }

    private static String changeEn(BigInteger copper) {
        if (copper == null || copper.signum() <= 0) {
            return "";
        }
        return ", change copper ×" + CompactFormat.format(copper);
    }

    private static String depositNameZh(BankBooks books, String id) {
        return switch (id) {
            case "copper" -> "铜锭";
            case "diamond" -> "钻石";
            default -> nameZh(books, id);
        };
    }

    private static String withdrawNameZh(BankBooks books, String id) {
        return switch (id) {
            case "copper" -> "铜";
            case "diamond" -> "钻";
            default -> nameZh(books, id);
        };
    }

    private static String nameZh(BankBooks books, String id) {
        CurrencyDef def = books.currency(id);
        if (def == null || def.name() == null || def.name().isBlank()) {
            return id;
        }
        return def.name();
    }

    private static String nameEn(BankBooks books, String id) {
        CurrencyDef def = books.currency(id);
        if (def == null || def.nameEn() == null || def.nameEn().isBlank()) {
            return id;
        }
        return def.nameEn();
    }

    private static String anchorZh(String anchor) {
        return switch (anchor) {
            case "copper" -> "铜";
            case "note" -> "银行券";
            default -> "钻";
        };
    }

    private static String anchorEn(String anchor) {
        return switch (anchor) {
            case "copper" -> "copper";
            case "note" -> "bank note";
            default -> "diamond";
        };
    }

    private static String shortEn(BankBooks books, String id) {
        return switch (id) {
            case "copper" -> "copper";
            case "diamond" -> "diamond";
            case "note" -> "bank note";
            default -> nameEn(books, id);
        };
    }
}
