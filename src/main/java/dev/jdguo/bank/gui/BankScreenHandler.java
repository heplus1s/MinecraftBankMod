package dev.jdguo.bank.gui;

import dev.jdguo.bank.currency.CurrencyDef;
import dev.jdguo.bank.currency.CurrencyKind;
import dev.jdguo.bank.instruments.CheckItems;
import dev.jdguo.bank.instruments.NotePaper;
import dev.jdguo.bank.instruments.PaperTake;
import dev.jdguo.bank.instruments.PresentedCheck;
import dev.jdguo.bank.instruments.PresentedNote;
import dev.jdguo.bank.ledger.BankBooks;
import dev.jdguo.bank.ledger.CompactFormat;
import dev.jdguo.bank.ledger.Supply;
import dev.jdguo.bank.ledger.TxResult;
import dev.jdguo.bank.market.NoteRegime;
import dev.jdguo.bank.market.NoteTrade;
import dev.jdguo.bank.market.PersonalNote;
import dev.jdguo.bank.market.Redemption;
import dev.jdguo.bank.market.SwapTrade;
import dev.jdguo.bank.text.BankPhrases;
import dev.jdguo.bank.text.BankTalk;
import dev.jdguo.bank.text.Spoken;
import dev.jdguo.bank.persistence.BankRuntime;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Server-side subclass of the vanilla 9×6 chest. The client only sees
 * {@link ScreenHandlerType#GENERIC_9X6}. Button items never leave the server inventory.
 */
public final class BankScreenHandler extends GenericContainerScreenHandler {
    public enum Page { SAVINGS, DEPOSIT, WITHDRAW, EXCHANGE, CHECKS, MARKET, ISSUANCE, MANAGEMENT }

    private final SimpleInventory inventory;
    private final ServerPlayerEntity owner;
    private final String outletName;
    private final boolean remoteAdmin;
    private Page page = Page.SAVINGS;
    private int payIndex;
    private int receiveIndex = 1;
    private int checkIndex;
    private int adminIndex;
    private int marketIndex;
    private int marketMode;
    private int swapPayIndex;
    private int payoutIndex;
    private String createRegime = "float";
    /** First click only opens this view. Bankruptcy runs from the confirm button. */
    private boolean bankruptConfirm;
    private boolean payeeSelf;
    private static final int REDEEM_SLOT = 22;
    private int paintTick = -100;

    public BankScreenHandler(int syncId, PlayerInventory playerInventory, SimpleInventory inventory,
                             String outletName, boolean remoteAdmin) {
        this(syncId, playerInventory, inventory, outletName, remoteAdmin, Page.SAVINGS);
    }

    public BankScreenHandler(int syncId, PlayerInventory playerInventory, SimpleInventory inventory,
                             String outletName, boolean remoteAdmin, Page initial) {
        super(ScreenHandlerType.GENERIC_9X6, syncId, playerInventory, inventory, 6);
        this.inventory = inventory;
        this.outletName = outletName;
        this.remoteAdmin = remoteAdmin;
        this.owner = (ServerPlayerEntity) playerInventory.player;
        if (initial != null) {
            this.page = initial;
        }
        List<CurrencyDef> currencies = new ArrayList<>(BankRuntime.books().currencies());
        if (currencies.size() > 1) {
            receiveIndex = 1;
        }
        for (int i = 0; i < currencies.size(); i++) {
            if (currencies.get(i).issuable()) {
                adminIndex = i;
                checkIndex = i;
                break;
            }
        }
        if (remoteAdmin && this.page == Page.SAVINGS) {
            this.page = Page.MANAGEMENT;
        }
        paint();
    }

    public String outletName() {
        return outletName;
    }

    public boolean remoteAdmin() {
        return remoteAdmin;
    }

    public void tick() {
        int now = owner.getServer().getTicks();
        if (now - paintTick >= 20) {
            paint();
        }
    }

    @Override
    public boolean canUse(PlayerEntity player) {
        if (player instanceof ServerPlayerEntity serverPlayer && dev.jdguo.bank.net.BankUiNetwork.wantsUi(serverPlayer)) {
            return false;
        }
        if (remoteAdmin && player.hasPermissionLevel(2)) {
            return true;
        }
        var outlet = BankRuntime.books().outlet(outletName);
        if (outlet == null) {
            return false;
        }
        String world = player.getWorld().getRegistryKey().getValue().toString();
        double limit = Math.max(outlet.radius(), 6.0) + 1.5;
        return outlet.distanceSquared(world, player.getX(), player.getY(), player.getZ()) <= limit * limit;
    }

    @Override
    public void onSlotClick(int slotIndex, int button, SlotActionType action, PlayerEntity player) {
        if (!(player instanceof ServerPlayerEntity serverPlayer)) {
            return;
        }
        if (action == SlotActionType.QUICK_CRAFT) {
            return;
        }
        if (slotIndex < 0) {
            if (GuiItems.isMarker(getCursorStack())) {
                setCursorStack(ItemStack.EMPTY);
            } else {
                super.onSlotClick(slotIndex, button, action, player);
            }
            scrub(serverPlayer);
            return;
        }
        if (slotIndex >= this.slots.size()) {
            return;
        }
        int deposit = depositSlot();
        if (slotIndex < 54 && slotIndex != deposit) {
            String clicked = GuiItems.action(inventory.getStack(slotIndex));
            if ("nav:close".equals(clicked)
                    && (action == SlotActionType.PICKUP || action == SlotActionType.QUICK_MOVE || action == SlotActionType.THROW)) {
                serverPlayer.closeHandledScreen();
                return;
            }
            if (action == SlotActionType.PICKUP || action == SlotActionType.QUICK_MOVE || action == SlotActionType.THROW) {
                activate(serverPlayer, clicked);
            }
            paint();
            sendContentUpdates();
            scrub(serverPlayer);
            return;
        }
        if (slotIndex == deposit) {
            if (action == SlotActionType.THROW || action == SlotActionType.CLONE || action == SlotActionType.PICKUP_ALL) {
                return;
            }
            super.onSlotClick(slotIndex, button, action, player);
            settle(serverPlayer);
            paint();
            sendContentUpdates();
            scrub(serverPlayer);
            return;
        }
        ItemStack held = this.slots.get(slotIndex).getStack();
        if (GuiItems.isMarker(held) || GuiItems.isMarker(getCursorStack())) {
            if (GuiItems.isMarker(held)) {
                this.slots.get(slotIndex).setStack(ItemStack.EMPTY);
            }
            if (GuiItems.isMarker(getCursorStack())) {
                setCursorStack(ItemStack.EMPTY);
            }
            return;
        }
        if (action == SlotActionType.QUICK_MOVE) {
            moveIntoDeposit(slotIndex);
            settle(serverPlayer);
            paint();
            sendContentUpdates();
            return;
        }
        super.onSlotClick(slotIndex, button, action, player);
        scrub(serverPlayer);
    }

    @Override
    public ItemStack quickMove(PlayerEntity player, int slot) {
        return ItemStack.EMPTY;
    }

    @Override
    public void onClosed(PlayerEntity player) {
        returnDeposit(player);
        if (player instanceof ServerPlayerEntity serverPlayer) {
            scrub(serverPlayer);
        }
        if (GuiItems.isMarker(getCursorStack())) {
            setCursorStack(ItemStack.EMPTY);
        }
        super.onClosed(player);
    }

    private void moveIntoDeposit(int fromSlot) {
        int deposit = depositSlot();
        if (deposit < 0 || !inventory.getStack(deposit).isEmpty()) {
            return;
        }
        ItemStack stack = this.slots.get(fromSlot).getStack();
        if (stack.isEmpty() || GuiItems.isMarker(stack) || !canDeposit(stack)) {
            return;
        }
        inventory.setStack(deposit, stack.copy());
        this.slots.get(fromSlot).setStack(ItemStack.EMPTY);
    }

    private boolean canDeposit(ItemStack stack) {
        if (NotePaper.tagged(stack)) {
            return page == Page.DEPOSIT || (page == Page.MARKET && marketMode == 2);
        }
        if (CheckItems.read(stack) != null) {
            return page == Page.CHECKS || page == Page.DEPOSIT;
        }
        CurrencyDef def = itemCurrency(stack);
        return def != null && def.kind() == CurrencyKind.COMMODITY && page == Page.DEPOSIT;
    }

    private void settle(ServerPlayerEntity player) {
        int deposit = depositSlot();
        if (deposit < 0) {
            return;
        }
        ItemStack stack = inventory.getStack(deposit);
        if (stack.isEmpty() || GuiItems.isMarker(stack)) {
            if (GuiItems.isMarker(stack)) {
                inventory.setStack(deposit, ItemStack.EMPTY);
            }
            return;
        }
        if (NotePaper.tagged(stack)) {
            if (page == Page.MARKET && marketMode == 2) {
                redeemPresentedPaper(player, stack, deposit);
                return;
            }
            if (page != Page.DEPOSIT) {
                return;
            }
            PresentedNote presented = NotePaper.read(stack);
            if (presented == null) {
                inventory.setStack(deposit, ItemStack.EMPTY);
                tell(player, BankPhrases.failure(BankPhrases.Kind.DEPOSIT, "note_tampered", null, null));
                return;
            }
            BigInteger sheets = BigInteger.valueOf(stack.getCount());
            TxResult result = BankRuntime.books().redeemPaper(player.getUuid(), presented, sheets);
            boolean confiscate = result.success()
                    || "note_spent".equals(result.code())
                    || "note_tampered".equals(result.code());
            if (confiscate) {
                inventory.setStack(deposit, ItemStack.EMPTY);
            }
            if (result.success()) {
                BankRuntime.save();
                tell(player, BankPhrases.depositPaper(
                        BankRuntime.books(),
                        player.getUuid(),
                        paperNameZh(presented),
                        paperNameEn(presented),
                        result.amount(),
                        "trimmed".equals(result.code())));
            } else {
                tell(player, BankPhrases.failure(BankPhrases.Kind.DEPOSIT, result.code(), null, null));
            }
            return;
        }
        PresentedCheck check = CheckItems.read(stack);
        if (check != null) {
            TxResult result = BankRuntime.books().redeemCheck(player.getUuid(), check);
            boolean confiscate = result.success()
                    || "already_redeemed".equals(result.code())
                    || "tampered".equals(result.code());
            if (confiscate) {
                inventory.setStack(deposit, ItemStack.EMPTY);
            }
            if (result.success()) {
                BankRuntime.save();
            }
            tell(player, result.success()
                    ? BankPhrases.redeem(BankRuntime.books(), player.getUuid(), check.currency(), result.amount(), result.copperPaid())
                    : BankPhrases.failure(BankPhrases.Kind.REDEEM, result.code(), check.currency(), null));
            return;
        }
        if (page != Page.DEPOSIT) {
            return;
        }
        CurrencyDef def = itemCurrency(stack);
        if (def == null || def.kind() != CurrencyKind.COMMODITY) {
            tell(player, BankPhrases.failure(BankPhrases.Kind.DEPOSIT, "not_commodity", null, null));
            return;
        }
        BigInteger count = BigInteger.valueOf(stack.getCount());
        TxResult result = BankRuntime.books().deposit(player.getUuid(), def.id(), count);
        if (result.success()) {
            inventory.setStack(deposit, ItemStack.EMPTY);
            BankRuntime.save();
            tell(player, BankPhrases.deposit(BankRuntime.books(), player.getUuid(), def.id(), result.amount()));
        } else {
            tell(player, BankPhrases.failure(BankPhrases.Kind.DEPOSIT, result.code(), def.id(), null));
        }
    }

    private void activate(ServerPlayerEntity player, String action) {
        if (action == null || action.isBlank() || "noop".equals(action)) {
            return;
        }
        if (action.startsWith("nav:")) {
            switch (action.substring(4)) {
                case "savings" -> changePage(player, Page.SAVINGS);
                case "management" -> {
                    if (player.hasPermissionLevel(2)) {
                        changePage(player, Page.MANAGEMENT);
                    } else {
                        tell(player, BankPhrases.failure(BankPhrases.Kind.GENERIC, "denied", null, null));
                    }
                }
                case "deposit" -> changePage(player, Page.DEPOSIT);
                case "withdraw" -> changePage(player, Page.WITHDRAW);
                case "exchange" -> changePage(player, Page.EXCHANGE);
                case "checks" -> changePage(player, Page.CHECKS);
                case "market" -> changePage(player, Page.MARKET);
                case "issuance" -> changePage(player, Page.ISSUANCE);
                case "close" -> player.closeHandledScreen();
                default -> {
                }
            }
            return;
        }
        if (action.startsWith("pn:")) {
            personalAction(player, action);
            return;
        }
        BankBooks books = BankRuntime.books();
        List<CurrencyDef> currencies = new ArrayList<>(books.currencies());
        if (currencies.isEmpty()) {
            return;
        }
        switch (action) {
            case "ex:pay" -> payIndex = nextIndex(currencies, payIndex, receiveIndex);
            case "ex:recv" -> receiveIndex = nextIndex(currencies, receiveIndex, payIndex);
            case "ex:swap" -> {
                int tmp = payIndex;
                payIndex = receiveIndex;
                receiveIndex = tmp;
            }
            case "ck:cur" -> checkIndex = (checkIndex + 1) % currencies.size();
            case "ck:payee" -> payeeSelf = !payeeSelf;
            case "ad:cur" -> adminIndex = nextIssuable(currencies, adminIndex);
            case "ad:freeze" -> {
                if (!player.hasPermissionLevel(2)) {
                    return;
                }
                books.setFrozen(!books.frozen());
                BankRuntime.save();
                tell(player, BankPhrases.freeze(books.frozen()));
            }
            case "ad:backed" -> {
                if (!player.hasPermissionLevel(2)) {
                    return;
                }
                CurrencyDef note = currencies.get(Math.floorMod(adminIndex, currencies.size()));
                BigInteger room = books.backedRoom(note);
                TxResult result = room.signum() <= 0
                        ? TxResult.fail("over_cap", "over cap")
                        : books.creditBacked(player.getUuid(), note.id(), room);
                if (result.success()) {
                    BankRuntime.save();
                    tell(player, BankPhrases.credit(books, "backed", note.id(), result.amount(), player.getName().getString()));
                } else {
                    tell(player, BankPhrases.failure(BankPhrases.Kind.CREDIT_BACKED, result.code(), note.id(), null));
                }
            }
            default -> {
                if (action.startsWith("wd:")) {
                    String[] parts = action.split(":");
                    if (parts.length >= 4 && "c".equals(parts[1])) {
                        withdrawCentralPaper(player, books, parts);
                        return;
                    }
                    if (parts.length >= 4 && "pn".equals(parts[1])) {
                        withdrawPersonalPaper(player, books, parts);
                        return;
                    }
                    boolean max = "max".equals(parts[2]);
                    BigInteger amount = max ? books.balance(player.getUuid(), parts[1]) : new BigInteger(parts[2]);
                    TxResult result = books.withdraw(player.getUuid(), parts[1], amount, max);
                    if (result.success()) {
                        giveItem(player, books.currency(parts[1]), result.amount());
                        if (result.copperPaid().signum() > 0) {
                            giveItem(player, books.currency("copper"), result.copperPaid());
                        }
                        BankRuntime.save();
                        tell(player, BankPhrases.withdraw(books, player.getUuid(), parts[1], result.amount(), result.copperPaid()));
                    } else {
                        tell(player, BankPhrases.failure(BankPhrases.Kind.WITHDRAW, result.code(), parts[1], null));
                    }
                } else if (action.startsWith("ex:buy:")) {
                    CurrencyDef pay = currencies.get(Math.floorMod(payIndex, currencies.size()));
                    CurrencyDef recv = currencies.get(Math.floorMod(receiveIndex, currencies.size()));
                    BigInteger qty = new BigInteger(action.substring("ex:buy:".length()));
                    TxResult result = books.exchange(player.getUuid(), pay.id(), recv.id(), qty);
                    if (result.success()) {
                        BankRuntime.save();
                        BigInteger shown = qty;
                        if ("diamond".equals(recv.id()) && result.copperPaid().signum() > 0 && books.copperPerDiamond().signum() > 0) {
                            shown = qty.subtract(result.copperPaid().divide(books.copperPerDiamond()));
                        }
                        tell(player, BankPhrases.exchange(books, player.getUuid(), pay.id(), recv.id(), shown, result.copperPaid()));
                    } else {
                        tell(player, BankPhrases.failure(BankPhrases.Kind.EXCHANGE, result.code(), recv.id(), pay.id()));
                    }
                } else if (action.startsWith("ck:issue:")) {
                    CurrencyDef cur = currencies.get(Math.floorMod(checkIndex, currencies.size()));
                    BigInteger amount = new BigInteger(action.substring("ck:issue:".length()));
                    var payee = payeeSelf ? player.getUuid() : null;
                    TxResult result = books.issueCheck(player.getUuid(), cur.id(), amount, player.getName().getString(), payee, true);
                    if (result.success()) {
                        player.getInventory().offerOrDrop(CheckItems.create(books, books.check(result.instrumentId())));
                        BankRuntime.save();
                        tell(player, BankPhrases.issue(books, cur.id(), result.amount(), null));
                    } else {
                        tell(player, BankPhrases.failure(BankPhrases.Kind.ISSUE, result.code(), cur.id(), null));
                    }
                } else if (action.startsWith("ad:unbacked:") || action.startsWith("ad:retire:")) {
                    if (!player.hasPermissionLevel(2)) {
                        tell(player, BankPhrases.failure(BankPhrases.Kind.GENERIC, "denied", null, null));
                        return;
                    }
                    CurrencyDef note = currencies.get(Math.floorMod(adminIndex, currencies.size()));
                    boolean retire = action.startsWith("ad:retire:");
                    BigInteger amount = new BigInteger(action.substring(action.lastIndexOf(':') + 1));
                    TxResult result = retire
                            ? books.retire(player.getUuid(), note.id(), amount)
                            : books.creditUnbacked(player.getUuid(), note.id(), amount);
                    if (result.success()) {
                        BankRuntime.save();
                        tell(player, BankPhrases.credit(books, retire ? "retire" : "unbacked", note.id(), result.amount(), player.getName().getString()));
                    } else {
                        tell(player, BankPhrases.failure(retire ? BankPhrases.Kind.RETIRE : BankPhrases.Kind.CREDIT_UNBACKED, result.code(), note.id(), null));
                    }
                }
            }
        }
    }

    private void changePage(ServerPlayerEntity player, Page next) {
        returnDeposit(player);
        this.bankruptConfirm = false;
        this.page = next;
    }

    private void paint() {
        ItemStack kept = ItemStack.EMPTY;
        int deposit = depositSlot();
        if (deposit >= 0) {
            kept = inventory.getStack(deposit).copy();
        }
        ItemStack glass = marker(Items.BLACK_STAINED_GLASS_PANE, Text.literal(" "), List.of(), "noop", 1);
        for (int i = 0; i < 54; i++) {
            inventory.setStack(i, glass.copy());
        }
        paintNav();
        switch (page) {
            case SAVINGS -> paintSavings();
            case DEPOSIT -> paintDeposit();
            case WITHDRAW -> paintWithdraw();
            case EXCHANGE -> paintExchange();
            case CHECKS -> paintChecks();
            case MARKET -> paintMarket();
            case ISSUANCE -> paintIssuance();
            case MANAGEMENT -> {
                if (owner.hasPermissionLevel(2)) {
                    paintManagement();
                } else {
                    page = Page.SAVINGS;
                    paintSavings();
                }
            }
        }
        if (depositSlot() >= 0) {
            inventory.setStack(depositSlot(), GuiItems.isMarker(kept) ? ItemStack.EMPTY : kept);
        }
        if (owner.getServer() != null) {
            paintTick = owner.getServer().getTicks();
        }
    }

    private void paintNav() {
        nav(45, Items.GOLD_INGOT, "储蓄", "nav:savings");
        nav(46, Items.HOPPER, "存入", "nav:deposit");
        nav(47, Items.CHEST, "取出", "nav:withdraw");
        nav(48, Items.EMERALD, "兑换", "nav:exchange");
        nav(49, Items.WRITABLE_BOOK, "银行汇票", "nav:checks");
        nav(50, Items.NAME_TAG, "个人银行券交易", "nav:market");
        nav(52, Items.NETHER_STAR, "个人货币发行", "nav:issuance");
        if (owner.hasPermissionLevel(2)) {
            nav(51, Items.RECOVERY_COMPASS, "中央银行管理", "nav:management");
        }
        nav(53, Items.BARRIER, "关闭", "nav:close");
    }

    private static final BigInteger[] NOTE_BUTTONS = {
            BigInteger.ONE, BigInteger.TEN, BigInteger.valueOf(64), BigInteger.valueOf(100)
    };

    private void paintIssuance() {
        BankBooks books = BankRuntime.books();
        UUID uuid = owner.getUuid();
        inventory.setStack(4, marker(Items.NETHER_STAR,
                Text.literal("个人货币发行").formatted(Formatting.YELLOW, Formatting.BOLD),
                List.of(Text.literal("Personal issuance").formatted(Formatting.GRAY),
                        Text.literal("先选择汇率。名称在聊天中登记，不在此处填写。").formatted(Formatting.DARK_GRAY)),
                "noop", 1));
        PersonalNote own = books.personalNote(uuid);
        if (own == null) {
            paintCreate();
            return;
        }
        if (own.bankrupt()) {
            bankruptConfirm = false;
        } else if (bankruptConfirm) {
            paintBankruptConfirm(own);
            return;
        }
        inventory.setStack(10, marker(Items.PAPER,
                Text.literal("「" + own.name() + "」流通额 " + CompactFormat.formatAggregate(own.circulation()))
                        .formatted(Formatting.YELLOW),
                List.of(Text.literal(regimeLabel(own)).formatted(Formatting.GRAY),
                        Text.literal("现行价格 " + CompactFormat.formatRate(books.personalDiamondPrice(own)) + " 钻石").formatted(Formatting.WHITE),
                        Text.literal("持有 " + CompactFormat.format(books.personalHolding(own.id(), uuid))).formatted(Formatting.WHITE),
                        Text.literal("M1 " + CompactFormat.formatAggregate(own.circulation())).formatted(Formatting.WHITE),
                        Text.literal("利息池 " + CompactFormat.format(own.interestPool())).formatted(Formatting.GOLD),
                        Text.literal("年利率 " + percent(books.personalInterestFraction())).formatted(Formatting.GOLD),
                        Text.literal(issueHint(own)).formatted(Formatting.DARK_GRAY)),
                "noop", 1));
        if (own.bankrupt()) {
            inventory.setStack(19, marker(Items.BARRIER,
                    Text.literal("已经宣告破产").formatted(Formatting.RED),
                    List.of(Text.literal("Void. No issue, trade, or redemption").formatted(Formatting.GRAY),
                            Text.literal("抵押已收归中央银行").formatted(Formatting.DARK_GRAY)),
                    "noop", 1));
            return;
        }
        inventory.setStack(13, marker(Items.NAME_TAG,
                Text.literal(regimeLabel(own)).formatted(Formatting.AQUA),
                List.of(Text.literal("开价 " + CompactFormat.format(own.par()) + " " + anchorLabel(own.reserveCurrency())).formatted(Formatting.WHITE),
                        Text.literal("Par " + CompactFormat.format(own.par()) + " " + anchorEn(own.reserveCurrency())).formatted(Formatting.DARK_GRAY)),
                "noop", 1));
        for (int i = 0; i < NOTE_BUTTONS.length; i++) {
            BigInteger amount = NOTE_BUTTONS[i];
            inventory.setStack(19 + i, marker(Items.GOLD_NUGGET,
                    Text.literal("货币发行 ×" + amount).formatted(Formatting.GREEN),
                    List.of(Text.literal("按发行准备金办理货币发行，并锁定发行准备金").formatted(Formatting.GRAY),
                            Text.literal("Reserve-backed. Locks collateral.").formatted(Formatting.DARK_GRAY)),
                    "pn:issue:" + amount, amount.intValue()));
            inventory.setStack(28 + i, marker(Items.REDSTONE,
                    Text.literal("无发行准备金的货币发行 ×" + amount).formatted(Formatting.RED),
                    List.of(Text.literal("不锁定发行准备金").formatted(Formatting.RED),
                            Text.literal("Unbacked. No reserve is locked.").formatted(Formatting.DARK_GRAY)),
                    "pn:unbacked:" + amount, amount.intValue()));
        }
        inventory.setStack(15, marker(Items.GOLD_INGOT,
                Text.literal("准备金账户 " + CompactFormat.format(books.centralReserve(uuid))
                        + " / 应当存入 " + CompactFormat.format(books.requiredCentralReserve(own))).formatted(Formatting.GOLD),
                List.of(Text.literal("准备金账户只存银行券，不计利息").formatted(Formatting.GRAY),
                        Text.literal("Central-bank reserve account").formatted(Formatting.DARK_GRAY)),
                "noop", 1));
        inventory.setStack(23, marker(Items.HOPPER,
                Text.literal("存入准备金账户 ×10").formatted(Formatting.GREEN),
                List.of(Text.literal("从储蓄账户存入准备金账户").formatted(Formatting.GRAY)),
                "pn:reserve:deposit:10", 10));
        inventory.setStack(24, marker(Items.HOPPER,
                Text.literal("存入准备金账户 ×100").formatted(Formatting.GREEN),
                List.of(Text.literal("从储蓄账户存入准备金账户").formatted(Formatting.GRAY)),
                "pn:reserve:deposit:100", 64));
        inventory.setStack(33, marker(Items.CHEST,
                Text.literal("取出准备金账户 ×10").formatted(Formatting.YELLOW),
                List.of(Text.literal("从准备金账户取出至储蓄账户").formatted(Formatting.GRAY)),
                "pn:reserve:withdraw:10", 10));
        inventory.setStack(34, marker(Items.CHEST,
                Text.literal("取出准备金账户 ×100").formatted(Formatting.YELLOW),
                List.of(Text.literal("从准备金账户取出至储蓄账户").formatted(Formatting.GRAY)),
                "pn:reserve:withdraw:100", 64));
        inventory.setStack(8, marker(Items.BARRIER,
                Text.literal("宣告破产").formatted(Formatting.DARK_RED),
                List.of(Text.literal("Opens a confirmation. Nothing is voided yet.").formatted(Formatting.GRAY),
                        Text.literal("先进入确认，不会立刻破产").formatted(Formatting.DARK_RED)),
                "pn:bankrupt", 1));
    }

    /** Confirm stays in the top-right corner. Cancel stays in the top-left. Issue buttons are hidden. */
    private void paintBankruptConfirm(PersonalNote own) {
        inventory.setStack(4, marker(Items.BARRIER,
                Text.literal("确认宣告破产").formatted(Formatting.DARK_RED, Formatting.BOLD),
                List.of(Text.literal("「" + own.name() + "」将作废，不能再交易或发行").formatted(Formatting.RED),
                        Text.literal("This does nothing until you confirm.").formatted(Formatting.GRAY)),
                "noop", 1));
        inventory.setStack(0, marker(Items.ARROW,
                Text.literal("返回").formatted(Formatting.GREEN),
                List.of(Text.literal("取消，账目不变").formatted(Formatting.GRAY),
                        Text.literal("Cancel. Nothing changes.").formatted(Formatting.DARK_GRAY)),
                "pn:bankrupt:no", 1));
        inventory.setStack(8, marker(Items.TNT,
                Text.literal("确认宣告破产").formatted(Formatting.DARK_RED),
                List.of(Text.literal("第二次确认，才会宣告破产").formatted(Formatting.RED),
                        Text.literal("Second click. This voids the note.").formatted(Formatting.DARK_GRAY)),
                "pn:bankrupt:yes", 1));
    }

    private void paintMarket() {
        BankBooks books = BankRuntime.books();
        inventory.setStack(4, marker(Items.NAME_TAG,
                Text.literal("个人银行券交易").formatted(Formatting.YELLOW, Formatting.BOLD),
                List.of(Text.literal("Personal notes").formatted(Formatting.GRAY),
                        Text.literal("买入、卖出、互换，或按市场价格兑付").formatted(Formatting.DARK_GRAY),
                        Text.literal("特殊数量用 /bank note").formatted(Formatting.DARK_GRAY)),
                "noop", 1));
        inventory.setStack(10, marker(Items.EMERALD,
                Text.literal("买入或卖出").formatted(marketMode == 0 ? Formatting.AQUA : Formatting.WHITE),
                List.of(Text.literal("Against central notes").formatted(Formatting.GRAY)),
                "pn:mode:0", 1));
        inventory.setStack(11, marker(Items.PAPER,
                Text.literal("互换").formatted(marketMode == 1 ? Formatting.AQUA : Formatting.WHITE),
                List.of(Text.literal("Note for note").formatted(Formatting.GRAY)),
                "pn:mode:1", 1));
        inventory.setStack(12, marker(Items.GOLD_INGOT,
                Text.literal("兑付").formatted(marketMode == 2 ? Formatting.AQUA : Formatting.WHITE),
                List.of(Text.literal("Redeem at the market price").formatted(Formatting.GRAY)),
                "pn:mode:2", 1));
        List<PersonalNote> notes = new ArrayList<>(books.personalNotes());
        if (notes.isEmpty()) {
            inventory.setStack(28, marker(Items.BARRIER,
                    Text.literal("还没有个人银行券").formatted(Formatting.RED),
                    List.of(Text.literal("No personal notes yet").formatted(Formatting.GRAY)),
                    "noop", 1));
            return;
        }
        marketIndex = Math.floorMod(marketIndex, notes.size());
        PersonalNote listed = notes.get(marketIndex);
        if (listed.bankrupt()) {
            inventory.setStack(28, marker(Items.BARRIER,
                    Text.literal("「" + listed.name() + "」已经宣告破产").formatted(Formatting.RED),
                    List.of(Text.literal("Void record. Click to switch.").formatted(Formatting.GRAY)),
                    "pn:cycle", 1));
            return;
        }
        inventory.setStack(28, marker(Items.EMERALD,
                Text.literal((marketMode == 1 ? "换入个人银行券「" : "个人银行券「") + listed.name() + "」").formatted(Formatting.GREEN),
                List.of(Text.literal("Trade").formatted(Formatting.GRAY),
                        Text.literal("现行价格 " + CompactFormat.formatRate(books.personalDiamondPrice(listed)) + " 钻石").formatted(Formatting.WHITE),
                        Text.literal("点击切换").formatted(Formatting.DARK_GRAY)),
                "pn:cycle", 1));
        if (marketMode == 1) {
            swapPayIndex = Math.floorMod(swapPayIndex, notes.size());
            if (notes.size() > 1 && swapPayIndex == marketIndex) {
                swapPayIndex = (swapPayIndex + 1) % notes.size();
            }
            PersonalNote pay = notes.get(swapPayIndex);
            inventory.setStack(19, marker(Items.PAPER,
                    Text.literal("付出个人银行券「" + pay.name() + "」").formatted(Formatting.GOLD),
                    List.of(Text.literal("Pay with this note").formatted(Formatting.GRAY),
                            Text.literal("现行价格 " + CompactFormat.formatRate(books.personalDiamondPrice(pay)) + " 钻石").formatted(Formatting.WHITE)),
                    "pn:pay", 1));
            for (int i = 0; i < NOTE_BUTTONS.length; i++) {
                BigInteger amount = NOTE_BUTTONS[i];
                inventory.setStack(29 + i, marker(Items.LIME_DYE,
                        Text.literal("换入 ×" + amount).formatted(Formatting.GREEN),
                        List.of(Text.literal("Receive ×" + amount).formatted(Formatting.GRAY)),
                        "pn:swap:" + amount, amount.intValue()));
            }
            return;
        }
        if (marketMode == 2) {
            String payout = redeemPayout(listed);
            boolean fixed = listed.regime() != NoteRegime.FLOAT;
            inventory.setStack(19, marker(Items.GOLD_INGOT,
                    Text.literal(payoutLabel(payout)).formatted(Formatting.GOLD),
                    List.of(Text.literal(fixed ? "Fixed by this note" : "Click to change the payout").formatted(Formatting.GRAY),
                            Text.literal(fixed ? "按钉住汇率或面额兑付，发行准备金不足则停止" : "按市场价格兑付，从发行人的发行准备金支付").formatted(Formatting.DARK_GRAY)),
                    fixed ? "noop" : "pn:payout", 1));
            for (int i = 0; i < NOTE_BUTTONS.length; i++) {
                BigInteger amount = NOTE_BUTTONS[i];
                inventory.setStack(29 + i, marker(Items.GOLD_NUGGET,
                        Text.literal("兑付 ×" + amount).formatted(Formatting.YELLOW),
                        List.of(Text.literal("From your account").formatted(Formatting.GRAY)),
                        "pn:redeem:" + payout + ":" + amount,
                        amount.intValue()));
            }
            inventory.setStack(13, marker(Items.SPECTRAL_ARROW,
                    Text.literal("放入纸质银行券并兑付").formatted(Formatting.GOLD),
                    List.of(Text.literal("Copies are refused").formatted(Formatting.RED)),
                    "noop", 1));
            inventory.setStack(REDEEM_SLOT, ItemStack.EMPTY);
            return;
        }
        for (int i = 0; i < NOTE_BUTTONS.length; i++) {
            BigInteger amount = NOTE_BUTTONS[i];
            inventory.setStack(29 + i, marker(Items.LIME_DYE,
                    Text.literal("买入 ×" + amount).formatted(Formatting.GREEN),
                    List.of(Text.literal("Buy ×" + amount).formatted(Formatting.GRAY)),
                    "pn:buy:" + amount, amount.intValue()));
            inventory.setStack(38 + i, marker(Items.RED_DYE,
                    Text.literal("卖出 ×" + amount).formatted(Formatting.DARK_RED),
                    List.of(Text.literal("Sell ×" + amount).formatted(Formatting.GRAY)),
                    "pn:sell:" + amount, amount.intValue()));
        }
    }

    private void personalAction(ServerPlayerEntity player, String action) {
        BankBooks books = BankRuntime.books();
        UUID uuid = player.getUuid();
        if ("pn:bankrupt".equals(action)) {
            bankruptConfirm = true;
            tell(player, BankPhrases.noteBankruptAsk());
            return;
        }
        if ("pn:bankrupt:no".equals(action)) {
            bankruptConfirm = false;
            tell(player, BankPhrases.noteBankruptCancelled());
            return;
        }
        if ("pn:bankrupt:yes".equals(action)) {
            if (!bankruptConfirm) {
                return;
            }
            bankruptConfirm = false;
            NoteTrade result = books.declareBankruptcy(uuid, null, player.hasPermissionLevel(2));
            if (!result.success()) {
                tell(player, BankPhrases.noteRejected(result.code()));
                return;
            }
            BankRuntime.save();
            tell(player, BankPhrases.noteBankrupt(books, result.note()));
            return;
        }
        if ("pn:cycle".equals(action)) {
            if (!books.personalNotes().isEmpty()) {
                marketIndex++;
            }
            return;
        }
        if (action.startsWith("pn:mode:")) {
            if (marketMode == 2) {
                returnSlot(player, REDEEM_SLOT);
            }
            marketMode = Integer.parseInt(action.substring("pn:mode:".length()));
            return;
        }
        if ("pn:pay".equals(action)) {
            if (!books.personalNotes().isEmpty()) {
                swapPayIndex++;
            }
            return;
        }
        if ("pn:payout".equals(action)) {
            payoutIndex++;
            return;
        }
        if (action.startsWith("pn:swap:")) {
            List<PersonalNote> notes = new ArrayList<>(books.personalNotes());
            if (notes.size() < 2) {
                tell(player, BankPhrases.noteRejected("pair"));
                return;
            }
            marketIndex = Math.floorMod(marketIndex, notes.size());
            swapPayIndex = Math.floorMod(swapPayIndex, notes.size());
            if (swapPayIndex == marketIndex) {
                swapPayIndex = (swapPayIndex + 1) % notes.size();
            }
            BigInteger amount = new BigInteger(action.substring("pn:swap:".length()));
            SwapTrade result = books.swapPersonalNotes(uuid, notes.get(swapPayIndex).name(), notes.get(marketIndex).name(), amount);
            if (!result.success()) {
                tell(player, BankPhrases.noteRejected(result.code()));
                return;
            }
            BankRuntime.save();
            tell(player, BankPhrases.noteSwap(result, books, uuid));
            return;
        }
        if (action.startsWith("pn:redeem:")) {
            String[] parts = action.split(":");
            List<PersonalNote> notes = new ArrayList<>(books.personalNotes());
            if (notes.isEmpty()) {
                tell(player, BankPhrases.noteRejected("none"));
                return;
            }
            marketIndex = Math.floorMod(marketIndex, notes.size());
            BigInteger amount = new BigInteger(parts[3]);
            Redemption result = books.redeemPersonal(uuid, notes.get(marketIndex).name(), amount, parts[2]);
            if (!result.success()) {
                tell(player, BankPhrases.noteRejected(result.code()));
                return;
            }
            BankRuntime.save();
            tell(player, BankPhrases.noteRedeem(result, books, uuid));
            return;
        }
        if (action.startsWith("pn:reserve:")) {
            String[] parts = action.split(":");
            BigInteger amount = new BigInteger(parts[3]);
            TxResult result = "deposit".equals(parts[2])
                    ? books.depositCentralReserve(uuid, amount)
                    : books.withdrawCentralReserve(uuid, amount);
            if (!result.success()) {
                tell(player, BankPhrases.noteRejected(result.code()));
                return;
            }
            BankRuntime.save();
            tell(player, BankPhrases.centralReserveMoved("deposit".equals(parts[2]), amount));
            return;
        }
        if (action.startsWith("pn:regime:")) {
            createRegime = action.substring("pn:regime:".length());
            String command = BankPhrases.noteCreateCommand(createRegime);
            player.closeHandledScreen();
            player.sendMessage(BankTalk.suggest(player, BankPhrases.noteCreateFill(createRegime), command), false);
            return;
        }
        if (action.startsWith("pn:unbacked:")) {
            BigInteger amount = new BigInteger(action.substring("pn:unbacked:".length()));
            NoteTrade result = books.issuePersonalNoteUnbacked(uuid, amount);
            if (!result.success()) {
                tell(player, BankPhrases.noteRejected(result.code()));
                return;
            }
            BankRuntime.save();
            tell(player, BankPhrases.noteUnbacked(books, result.note(), amount));
            return;
        }
        if (action.startsWith("pn:issue:")) {
            BigInteger amount = new BigInteger(action.substring("pn:issue:".length()));
            NoteTrade result = books.issuePersonalNote(uuid, amount);
            if (!result.success()) {
                tell(player, BankPhrases.noteRejected(result.code()));
                return;
            }
            BankRuntime.save();
            tell(player, BankPhrases.noteIssued(books, result.note(), amount));
            return;
        }
        boolean buy = action.startsWith("pn:buy:");
        if (buy || action.startsWith("pn:sell:")) {
            List<PersonalNote> notes = new ArrayList<>(books.personalNotes());
            if (notes.isEmpty()) {
                tell(player, BankPhrases.noteRejected("none"));
                return;
            }
            marketIndex = Math.floorMod(marketIndex, notes.size());
            PersonalNote listed = notes.get(marketIndex);
            BigInteger amount = new BigInteger(action.substring(action.lastIndexOf(':') + 1));
            NoteTrade result = buy
                    ? books.buyPersonalNote(uuid, listed.name(), amount)
                    : books.sellPersonalNote(uuid, listed.name(), amount);
            if (!result.success()) {
                tell(player, BankPhrases.noteRejected(result.code()));
                return;
            }
            BankRuntime.save();
            tell(player, BankPhrases.noteTrade(buy, result.note().name(), amount, result.centralPaid(), result.copperPaid(), books, uuid));
        }
    }

    private void paintAccountRow(BankBooks books) {
        int[] slots = {0, 2, 6, 8};
        int index = 0;
        UUID uuid = owner.getUuid();
        for (CurrencyDef def : books.currencies()) {
            if (index >= slots.length) {
                break;
            }
            inventory.setStack(slots[index], marker(icon(def),
                    Text.literal(def.name() + "  " + CompactFormat.format(books.balance(uuid, def.id()))).formatted(color(def)),
                    List.of(Text.literal("储蓄账户").formatted(Formatting.DARK_GRAY)),
                    "noop", 1));
            index++;
        }
        for (PersonalNote note : books.personalNotes()) {
            if (index >= slots.length) {
                break;
            }
            BigInteger held = books.personalHolding(note.id(), uuid);
            if (held.signum() <= 0) {
                continue;
            }
            inventory.setStack(slots[index], marker(Items.PAPER,
                    Text.literal(note.name() + "  " + CompactFormat.format(held)).formatted(Formatting.YELLOW),
                    List.of(Text.literal("储蓄账户").formatted(Formatting.DARK_GRAY)),
                    "noop", 1));
            index++;
        }
    }

    private List<Text> accountLore(BankBooks books, String hint) {
        List<Text> lines = new ArrayList<>();
        lines.add(Text.literal(hint).formatted(Formatting.GRAY));
        lines.add(Text.literal("储蓄账户").formatted(Formatting.DARK_GRAY));
        UUID uuid = owner.getUuid();
        for (CurrencyDef def : books.currencies()) {
            lines.add(Text.literal(def.name() + "  " + CompactFormat.format(books.balance(uuid, def.id()))).formatted(Formatting.WHITE));
        }
        return lines;
    }

    private void paintSavings() {
        BankBooks books = BankRuntime.books();
        inventory.setStack(4, marker(Items.GOLD_INGOT,
                Text.literal("储蓄账户").formatted(Formatting.GOLD, Formatting.BOLD),
                accountLore(books, "这是储蓄账户，不是中央银行的库存"),
                "noop", 1));
        paintAccountRow(books);
        int slot = 10;
        var uuid = owner.getUuid();
        for (CurrencyDef def : books.currencies()) {
            if (slot > 16) {
                break;
            }
            BigInteger balance = books.balance(uuid, def.id());
            inventory.setStack(slot, marker(icon(def),
                    Text.literal(def.name() + "  " + CompactFormat.format(balance)).formatted(color(def)),
                    List.of(Text.literal(balance.toString()).formatted(Formatting.DARK_GRAY),
                            Text.literal(def.nameEn()).formatted(Formatting.GRAY)),
                    "noop", 1));
            slot += 2;
        }
        int holdSlot = 19;
        for (PersonalNote note : books.personalNotes()) {
            BigInteger held = books.personalHolding(note.id(), uuid);
            if (held.signum() <= 0) {
                continue;
            }
            if (holdSlot > 25 && holdSlot < 28) {
                holdSlot = 28;
            }
            if (holdSlot > 34) {
                break;
            }
            inventory.setStack(holdSlot, marker(Items.PAPER,
                    Text.literal((note.bankrupt() ? "已经宣告破产 " : "个人银行券 ") + note.name() + "  " + CompactFormat.format(held))
                            .formatted(note.bankrupt() ? Formatting.DARK_GRAY : Formatting.YELLOW),
                    List.of(Text.literal(note.bankrupt() ? "Void record" : "Book entry").formatted(Formatting.GRAY),
                            Text.literal("现行价格 " + CompactFormat.formatRate(books.personalDiamondPrice(note)) + " 钻石").formatted(Formatting.WHITE),
                            Text.literal("Held " + CompactFormat.format(held)).formatted(Formatting.DARK_GRAY)),
                    "noop", 1));
            holdSlot += 2;
        }
    }

    private void paintManagement() {
        BankBooks books = BankRuntime.books();
        inventory.setStack(0, marker(Items.PAPER,
                Text.literal("M0  " + CompactFormat.formatAggregate(books.centralM0())).formatted(Formatting.WHITE),
                List.of(Text.literal("流通中的银行券").formatted(Formatting.DARK_GRAY)),
                "noop", 1));
        inventory.setStack(2, marker(Items.PAPER,
                Text.literal("M1  " + CompactFormat.formatAggregate(books.centralM1())).formatted(Formatting.WHITE),
                List.of(Text.literal("流通中的银行券与活期存款").formatted(Formatting.DARK_GRAY)),
                "noop", 1));
        inventory.setStack(6, marker(Items.MAP,
                Text.literal("M2  " + CompactFormat.formatAggregate(books.centralM2())).formatted(Formatting.WHITE),
                List.of(Text.literal("M1 与按现行价格计算的准货币").formatted(Formatting.DARK_GRAY)),
                "noop", 1));
        inventory.setStack(8, marker(Items.GOLD_INGOT,
                Text.literal("储蓄总池  " + CompactFormat.format(books.savingsPool("note"))).formatted(Formatting.GREEN),
                List.of(Text.literal("银行券活期存款与手续费留存").formatted(Formatting.DARK_GRAY),
                        Text.literal("不含发行准备金").formatted(Formatting.DARK_GRAY)),
                "noop", 1));
        inventory.setStack(4, marker(Items.GOLD_BLOCK,
                Text.literal("中央银行管理").formatted(Formatting.AQUA, Formatting.BOLD),
                List.of(Text.literal("Central bank").formatted(Formatting.GRAY),
                        Text.literal("汇率 " + BankPhrases.copperPerDiamond(books) + " 铜锭/钻石").formatted(Formatting.WHITE),
                        Text.literal("手续费 " + percent(books.feeFraction())).formatted(Formatting.GRAY),
                        Text.literal("中央银行年利率 " + percent(books.interestFraction())).formatted(Formatting.GOLD),
                        Text.literal("个人银行券年利率 " + percent(books.personalInterestFraction())).formatted(Formatting.GRAY),
                        Text.literal("储蓄总池 " + CompactFormat.format(books.savingsPool("note"))).formatted(Formatting.WHITE)),
                "noop", 1));
        int slot = 10;
        for (CurrencyDef def : books.currencies()) {
            if (slot > 16) {
                break;
            }
            Supply supply = books.supply(def.id());
            List<Text> lore = new ArrayList<>();
            lore.add(Text.literal(def.nameEn()).formatted(Formatting.GRAY));
            if (def.kind() == CurrencyKind.COMMODITY) {
                lore.add(Text.literal("发行准备金 " + CompactFormat.format(supply.reserve())).formatted(Formatting.WHITE));
                lore.add(Text.literal("储蓄保管 " + CompactFormat.format(supply.depositVault())).formatted(Formatting.WHITE));
                lore.add(Text.literal("储蓄总池 " + CompactFormat.format(books.savingsPool(def.id()))).formatted(Formatting.WHITE));
                lore.add(Text.literal("手续费留存 " + CompactFormat.format(books.interestPool(def.id()))).formatted(Formatting.GRAY));
                lore.add(Text.literal("Issuance reserves stay out of the savings pool").formatted(Formatting.DARK_GRAY));
            } else {
                lore.add(Text.literal("M0 " + CompactFormat.formatAggregate(books.centralM0())).formatted(Formatting.WHITE));
                lore.add(Text.literal("M1 " + CompactFormat.formatAggregate(books.centralM1())).formatted(Formatting.WHITE));
                lore.add(Text.literal("M2 " + CompactFormat.formatAggregate(books.centralM2())).formatted(Formatting.WHITE));
                lore.add(Text.literal("储蓄总池 " + CompactFormat.format(books.savingsPool(def.id()))).formatted(Formatting.WHITE));
                lore.add(Text.literal("剩余货币发行额度 " + CompactFormat.format(books.backedRoom(def))).formatted(Formatting.YELLOW));
                lore.add(Text.literal("Issuable amount is not part of M2").formatted(Formatting.DARK_GRAY));
            }
            String headline = def.kind() == CurrencyKind.COMMODITY
                    ? def.name() + "  发行准备金 " + CompactFormat.format(supply.reserve())
                    : def.name() + "  M1 " + CompactFormat.formatAggregate(books.centralM1());
            inventory.setStack(slot, marker(icon(def),
                    Text.literal(headline).formatted(color(def)),
                    lore,
                    "noop", 1));
            slot += 2;
        }
        int rateSlot = 19;
        for (CurrencyDef def : books.currencies()) {
            if (def.id().equals(books.numeraire()) || rateSlot > 25) {
                continue;
            }
            BigDecimal rate = books.unitsPer(def.id(), books.numeraire());
            CurrencyDef base = books.currency(books.numeraire());
            inventory.setStack(rateSlot, marker(Items.PAPER,
                    Text.literal("1 " + base.name() + " = " + CompactFormat.formatRate(rate) + " " + def.name()).formatted(Formatting.WHITE),
                    List.of(Text.literal("手续费另计 " + percent(books.feeFraction())).formatted(Formatting.GRAY)),
                    "noop", 1));
            rateSlot += 2;
        }
        CurrencyDef note = books.primaryVirtual();
        BigDecimal tight = note == null ? null : books.tightness(note.id());
        inventory.setStack(31, marker(concrete(tight),
                Text.literal("银行券兑付能力 " + CompactFormat.formatRatio(tight)).formatted(Formatting.WHITE),
                List.of(),
                "noop", 1));
        inventory.setStack(40, marker(Items.CLOCK,
                Text.literal("中央银行年利率 " + percent(books.interestFraction())).formatted(Formatting.GOLD),
                List.of(Text.literal("个人银行券年利率 " + percent(books.personalInterestFraction())).formatted(Formatting.GRAY),
                        Text.literal("储蓄总池 银行券 " + CompactFormat.format(books.savingsPool("note"))).formatted(Formatting.WHITE),
                        Text.literal("利息从手续费留存里付，不动发行准备金").formatted(Formatting.DARK_GRAY)),
                "noop", 1));
        paintCentralIssuance(books);
    }

    private void paintCentralIssuance(BankBooks books) {
        List<CurrencyDef> currencies = new ArrayList<>(books.currencies());
        if (currencies.isEmpty()) {
            return;
        }
        CurrencyDef note = currencies.get(Math.floorMod(adminIndex, currencies.size()));
        inventory.setStack(36, marker(Items.BOOK,
                Text.literal("货币发行 " + note.name()).formatted(color(note)),
                List.of(Text.literal("Central issuance").formatted(Formatting.GRAY),
                        Text.literal("点击切换货币").formatted(Formatting.DARK_GRAY),
                        Text.literal("剩余货币发行额度 " + CompactFormat.format(books.backedRoom(note))).formatted(Formatting.WHITE)),
                "ad:cur", 1));
        inventory.setStack(37, marker(Items.GOLD_NUGGET,
                Text.literal("无发行准备金的货币发行 +1,000").formatted(Formatting.RED),
                List.of(Text.literal("Without reserves +1,000").formatted(Formatting.GRAY)),
                "ad:unbacked:1000", 1));
        inventory.setStack(38, marker(Items.GOLD_INGOT,
                Text.literal("无发行准备金的货币发行 +100万").formatted(Formatting.RED),
                List.of(Text.literal("Without reserves +1,000,000").formatted(Formatting.GRAY)),
                "ad:unbacked:1000000", 1));
        inventory.setStack(39, marker(Items.GOLD_BLOCK,
                Text.literal("无发行准备金的货币发行 +1亿").formatted(Formatting.DARK_RED),
                List.of(Text.literal("Without reserves +100,000,000").formatted(Formatting.GRAY)),
                "ad:unbacked:100000000", 1));
        inventory.setStack(41, marker(Items.DIAMOND,
                Text.literal("按发行准备金足额货币发行").formatted(Formatting.AQUA),
                List.of(Text.literal("Issue up to the reserve cap").formatted(Formatting.GRAY)),
                "ad:backed", 1));
        inventory.setStack(42, marker(Items.FLINT,
                Text.literal("注销银行券 1,000").formatted(Formatting.DARK_GREEN),
                List.of(Text.literal("Retire 1,000 from your balance").formatted(Formatting.GRAY)),
                "ad:retire:1000", 1));
        inventory.setStack(43, marker(books.frozen() ? Items.RED_DYE : Items.LIME_DYE,
                Text.literal(books.frozen() ? "银行券兑付已暂停" : "银行券兑付正常").formatted(books.frozen() ? Formatting.RED : Formatting.GREEN),
                List.of(Text.literal(books.frozen() ? "Redemption suspended" : "Redemption available").formatted(Formatting.GRAY),
                        Text.literal("点击切换。只暂停银行券兑换铜锭或钻石").formatted(Formatting.DARK_GRAY)),
                "ad:freeze", 1));
    }

    private void paintDeposit() {
        inventory.setStack(4, marker(Items.OAK_SIGN,
                Text.literal("把铜锭、钻石或银行券放进中间空格").formatted(Formatting.GOLD),
                accountLore(BankRuntime.books(), "同面额的纸可以叠放。复制件不能重复入账"),
                "noop", 1));
        paintAccountRow(BankRuntime.books());
        inventory.setStack(13, marker(Items.SPECTRAL_ARROW,
                Text.literal("↓ 存入").formatted(Formatting.YELLOW),
                List.of(), "noop", 1));
        inventory.setStack(22, ItemStack.EMPTY);
    }

    private void paintWithdraw() {
        BankBooks books = BankRuntime.books();
        inventory.setStack(4, marker(Items.CHEST,
                Text.literal("取出").formatted(Formatting.GOLD),
                accountLore(books, "铜锭、钻石，以及可叠放的银行券"),
                "noop", 1));
        paintAccountRow(books);
        int row = 0;
        for (CurrencyDef def : books.currencies()) {
            if (def.kind() != CurrencyKind.COMMODITY) {
                continue;
            }
            int base = 10 + row * 9;
            if (base > 36) {
                break;
            }
            BigInteger free = books.supply(def.id()).freeReserve();
            placeAmount(base, icon(def), "取出 " + def.name(), "wd:" + def.id(), free);
            row++;
        }
        int base = 10 + row * 9;
        CurrencyDef note = books.primaryVirtual();
        if (note != null && base <= 28) {
            BigInteger balance = books.balance(owner.getUuid(), note.id());
            placePaper(base, Items.PAPER, "取出 " + note.name(), "wd:c:" + note.id(), balance);
            base += 9;
        }
        if (base <= 37) {
            PersonalNote own = books.personalNote(owner.getUuid());
            if (own != null && !own.bankrupt()) {
                BigInteger holding = books.personalHolding(own.id(), owner.getUuid());
                placePaper(base, Items.PAPER, "取出 「" + own.name() + "」", "wd:pn:" + own.id(), holding);
            } else if (own != null) {
                inventory.setStack(base, marker(Items.PAPER,
                        Text.literal("「" + own.name() + "」已经宣告破产").formatted(Formatting.DARK_GRAY),
                        List.of(Text.literal("Void paper is not withdrawn").formatted(Formatting.GRAY)),
                        "noop", 1));
            }
        }
    }

    private void withdrawCentralPaper(ServerPlayerEntity player, BankBooks books, String[] parts) {
        CurrencyDef note = books.currency(parts[2]);
        if (note == null || note.kind() != CurrencyKind.VIRTUAL) {
            tell(player, BankPhrases.failure(BankPhrases.Kind.WITHDRAW, "unknown_currency", null, null));
            return;
        }
        BigInteger amount = paperAmount(player, books, note.id(), null, parts[3]);
        if (amount == null) {
            tell(player, BankPhrases.failure(BankPhrases.Kind.WITHDRAW, "insufficient_funds", note.id(), null));
            return;
        }
        PaperTake take = books.issueCentralPaper(player.getUuid(), amount);
        if (!take.result().success()) {
            tell(player, BankPhrases.failure(BankPhrases.Kind.WITHDRAW, take.result().code(), note.id(), null));
            return;
        }
        NotePaper.give(player, new PresentedNote(note.id(), null, take.face()), note.name(), take.sheets());
        BankRuntime.save();
        tell(player, BankPhrases.withdraw(books, player.getUuid(), note.id(), take.result().amount()));
    }

    private void withdrawPersonalPaper(ServerPlayerEntity player, BankBooks books, String[] parts) {
        UUID noteId;
        try {
            noteId = UUID.fromString(parts[2]);
        } catch (IllegalArgumentException error) {
            tell(player, BankPhrases.noteRejected("none"));
            return;
        }
        PersonalNote note = books.personalNoteById(noteId);
        if (note == null) {
            tell(player, BankPhrases.noteRejected("none"));
            return;
        }
        BigInteger amount = paperAmount(player, books, null, noteId, parts[3]);
        if (amount == null) {
            tell(player, BankPhrases.failure(BankPhrases.Kind.WITHDRAW, "insufficient_funds", null, null));
            return;
        }
        PaperTake take = books.issuePersonalPaper(player.getUuid(), noteId, amount);
        if (!take.result().success()) {
            Spoken spoken = "none".equals(take.result().code())
                    ? BankPhrases.noteRejected("none")
                    : BankPhrases.failure(BankPhrases.Kind.WITHDRAW, take.result().code(), null, null);
            tell(player, spoken);
            return;
        }
        NotePaper.give(player, new PresentedNote(null, noteId, take.face()), note.name(), take.sheets());
        BankRuntime.save();
        tell(player, BankPhrases.withdrawPersonal(
                books, player.getUuid(), note.name(), take.result().amount(), books.personalHolding(noteId, player.getUuid())));
    }

    private BigInteger paperAmount(ServerPlayerEntity player, BankBooks books, String currency, UUID personal, String raw) {
        BigInteger available = personal == null
                ? books.balance(player.getUuid(), currency)
                : books.personalHolding(personal, player.getUuid());
        if ("max".equals(raw)) {
            BigInteger amount = available.min(BankBooks.MAX_PHYSICAL);
            return amount.signum() > 0 ? amount : null;
        }
        return new BigInteger(raw);
    }

    private String paperNameZh(PresentedNote note) {
        if (note.central()) {
            CurrencyDef def = BankRuntime.books().currency(note.currency());
            return def == null ? note.currency() : def.name();
        }
        PersonalNote personal = BankRuntime.books().personalNoteById(note.personalNote());
        return personal == null ? "个人银行券" : "个人银行券「" + personal.name() + "」";
    }

    private String paperNameEn(PresentedNote note) {
        if (note.central()) {
            return "bank note";
        }
        PersonalNote personal = BankRuntime.books().personalNoteById(note.personalNote());
        return personal == null ? "personal note" : "personal note \"" + personal.name() + "\"";
    }

    private void paintExchange() {
        BankBooks books = BankRuntime.books();
        List<CurrencyDef> currencies = new ArrayList<>(books.currencies());
        CurrencyDef pay = currencies.get(Math.floorMod(payIndex, currencies.size()));
        CurrencyDef recv = currencies.get(Math.floorMod(receiveIndex, currencies.size()));
        inventory.setStack(2, marker(icon(pay),
                Text.literal("兑换付出 " + pay.name()).formatted(color(pay)),
                List.of(Text.literal("点击切换").formatted(Formatting.GRAY)),
                "ex:pay", 1));
        inventory.setStack(4, marker(Items.SPECTRAL_ARROW,
                Text.literal("交换付出与得到").formatted(Formatting.WHITE),
                List.of(), "ex:swap", 1));
        inventory.setStack(6, marker(icon(recv),
                Text.literal("兑换得到 " + recv.name()).formatted(color(recv)),
                List.of(Text.literal("点击切换").formatted(Formatting.GRAY)),
                "ex:recv", 1));
        BigInteger[] amounts = {BigInteger.ONE, BigInteger.TEN, BigInteger.valueOf(64),
                BigInteger.valueOf(100), BigInteger.valueOf(1000), BigInteger.valueOf(10000)};
        net.minecraft.item.Item[] icons = {Items.LIME_DYE, Items.LIME_DYE, Items.EMERALD, Items.EMERALD, Items.EMERALD_BLOCK, Items.EMERALD_BLOCK};
        for (int i = 0; i < amounts.length; i++) {
            BigInteger cost = books.cost(pay.id(), recv.id(), amounts[i]);
            String price = cost == null ? "无法报价" : "花费 " + CompactFormat.format(cost) + " " + pay.name();
            inventory.setStack(19 + i, marker(icons[i],
                    Text.literal("兑换 " + recv.name() + " ×" + amounts[i]).formatted(Formatting.GREEN),
                    List.of(Text.literal(price).formatted(Formatting.GRAY),
                            Text.literal("含手续费 " + percent(books.feeFraction())).formatted(Formatting.DARK_GRAY)),
                    "ex:buy:" + amounts[i], amounts[i].intValue()));
        }
    }

    private void paintChecks() {
        BankBooks books = BankRuntime.books();
        List<CurrencyDef> currencies = new ArrayList<>(books.currencies());
        CurrencyDef cur = currencies.get(Math.floorMod(checkIndex, currencies.size()));
        inventory.setStack(2, marker(icon(cur),
                Text.literal("货币 " + cur.name()).formatted(color(cur)),
                List.of(Text.literal("点击切换").formatted(Formatting.GRAY),
                        Text.literal("余额 " + CompactFormat.format(books.balance(owner.getUuid(), cur.id()))).formatted(Formatting.WHITE)),
                "ck:cur", 1));
        inventory.setStack(4, marker(payeeSelf ? Items.NAME_TAG : Items.PAPER,
                Text.literal(payeeSelf ? "仅本人兑付" : "持票人兑付").formatted(payeeSelf ? Formatting.LIGHT_PURPLE : Formatting.AQUA),
                List.of(Text.literal("点击切换收款人").formatted(Formatting.GRAY)),
                "ck:payee", 1));
        BigInteger[] amounts = {BigInteger.ONE, BigInteger.TEN, BigInteger.valueOf(64),
                BigInteger.valueOf(100), BigInteger.valueOf(1000), BigInteger.valueOf(10000)};
        for (int i = 0; i < amounts.length; i++) {
            inventory.setStack(10 + i, marker(Items.WRITTEN_BOOK,
                    Text.literal("开出银行汇票 " + cur.name() + " ×" + CompactFormat.format(amounts[i])).formatted(Formatting.YELLOW),
                    List.of(Text.literal("从储蓄账户划出，开出银行汇票").formatted(Formatting.GRAY)),
                    "ck:issue:" + amounts[i], 1));
        }
        inventory.setStack(22, marker(Items.SPECTRAL_ARROW,
                Text.literal("放入银行汇票并兑付").formatted(Formatting.GOLD),
                List.of(Text.literal("复制件会被拒付").formatted(Formatting.RED)),
                "noop", 1));
        inventory.setStack(31, ItemStack.EMPTY);
    }

    private void placePaper(int base, net.minecraft.item.Item item, String label, String actionPrefix, BigInteger available) {
        int[] amounts = {1, 10, 64, 100};
        for (int i = 0; i < amounts.length; i++) {
            inventory.setStack(base + i, marker(item,
                    Text.literal(label + " ×" + amounts[i]).formatted(Formatting.YELLOW),
                    List.of(Text.literal("面额 1，同面额叠成一摞").formatted(Formatting.GRAY),
                            Text.literal("可取出 " + CompactFormat.format(available)).formatted(Formatting.DARK_GRAY),
                            Text.literal("Face value 1, stacks to 64").formatted(Formatting.DARK_GRAY)),
                    actionPrefix + ":" + amounts[i], 1));
        }
        inventory.setStack(base + 4, marker(item,
                Text.literal(label + " 全部").formatted(Formatting.GOLD),
                List.of(Text.literal("单次最多 " + BankBooks.MAX_PHYSICAL + " 张").formatted(Formatting.GRAY),
                        Text.literal("更大的数用 /bank withdraw").formatted(Formatting.DARK_GRAY)),
                actionPrefix + ":max", 1));
    }

    private void placeAmount(int base, net.minecraft.item.Item item, String label, String actionPrefix, BigInteger free) {
        int[] amounts = {1, 10, 64};
        for (int i = 0; i < amounts.length; i++) {
            inventory.setStack(base + i, marker(item,
                    Text.literal(label + " ×" + amounts[i]).formatted(Formatting.GOLD),
                    List.of(Text.literal("可取出 " + CompactFormat.format(free)).formatted(Formatting.GRAY)),
                    actionPrefix + ":" + amounts[i], amounts[i]));
        }
        inventory.setStack(base + 3, marker(item,
                Text.literal(label + " 全部").formatted(Formatting.YELLOW),
                List.of(Text.literal("不超过库存，且单次最多 " + BankBooks.MAX_PHYSICAL).formatted(Formatting.GRAY)),
                actionPrefix + ":max", 1));
    }

    private void nav(int slot, net.minecraft.item.Item item, String name, String action) {
        boolean here = action.equals("nav:" + page.name().toLowerCase());
        inventory.setStack(slot, marker(item,
                Text.literal(name).formatted(here ? Formatting.AQUA : Formatting.WHITE),
                List.of(), action, 1));
    }

    private ItemStack marker(net.minecraft.item.Item item, Text name, List<Text> lore, String action, int count) {
        return GuiItems.marker(item, name, lore, action, count);
    }

    private int depositSlot() {
        return switch (page) {
            case DEPOSIT -> 22;
            case CHECKS -> 31;
            case MARKET -> marketMode == 2 ? REDEEM_SLOT : -1;
            default -> -1;
        };
    }

    private void redeemPresentedPaper(ServerPlayerEntity player, ItemStack stack, int deposit) {
        PresentedNote presented = NotePaper.read(stack);
        if (presented == null) {
            inventory.setStack(deposit, ItemStack.EMPTY);
            tell(player, BankPhrases.failure(BankPhrases.Kind.DEPOSIT, "note_tampered", null, null));
            return;
        }
        if (presented.central()) {
            tell(player, BankPhrases.failure(BankPhrases.Kind.DEPOSIT, "not_note", null, null));
            return;
        }
        PersonalNote note = BankRuntime.books().personalNoteById(presented.personalNote());
        String payout = note == null ? "diamond" : redeemPayout(note);
        Redemption result = BankRuntime.books().redeemPersonalPaper(
                player.getUuid(), presented.personalNote(), presented.face(), BigInteger.valueOf(stack.getCount()), payout);
        if ("note_spent".equals(result.code()) || "note_tampered".equals(result.code())) {
            inventory.setStack(deposit, ItemStack.EMPTY);
        }
        if (!result.success()) {
            tell(player, "note_spent".equals(result.code()) || "note_tampered".equals(result.code())
                    ? BankPhrases.failure(BankPhrases.Kind.DEPOSIT, result.code(), null, null)
                    : BankPhrases.noteRejected(result.code()));
            return;
        }
        int taken = result.sheetsTaken().intValue();
        int left = stack.getCount() - Math.max(0, taken);
        if (left <= 0) {
            inventory.setStack(deposit, ItemStack.EMPTY);
        } else {
            stack.setCount(left);
            inventory.setStack(deposit, stack);
        }
        BankRuntime.save();
        tell(player, BankPhrases.noteRedeem(result, BankRuntime.books(), player.getUuid()));
    }

    private void paintCreate() {
        inventory.setStack(10, marker(Items.EMERALD,
                Text.literal("浮动汇率").formatted("float".equals(createRegime) ? Formatting.AQUA : Formatting.WHITE),
                List.of(Text.literal("Market price").formatted(Formatting.GRAY),
                        Text.literal("点选后，再点聊天那一行，指令会填好").formatted(Formatting.DARK_GRAY)),
                "pn:regime:float", 1));
        inventory.setStack(11, marker(Items.GOLD_INGOT,
                Text.literal("钉住汇率").formatted("peg".equals(createRegime) ? Formatting.AQUA : Formatting.WHITE),
                List.of(Text.literal("Fixed peg").formatted(Formatting.GRAY),
                        Text.literal("点选后，再点聊天那一行，指令会填好").formatted(Formatting.DARK_GRAY)),
                "pn:regime:peg", 1));
        inventory.setStack(12, marker(Items.DIAMOND,
                Text.literal("定额兑付").formatted("hard".equals(createRegime) ? Formatting.AQUA : Formatting.WHITE),
                List.of(Text.literal("Face value only").formatted(Formatting.GRAY),
                        Text.literal("点选后，再点聊天那一行，指令会填好").formatted(Formatting.DARK_GRAY)),
                "pn:regime:hard", 1));
    }

    private static String regimeLabel(PersonalNote note) {
        String unit = anchorLabel(note.reserveCurrency());
        return switch (note.regime()) {
            case PEG -> "钉住汇率，每单位等于 " + CompactFormat.format(note.par()) + " " + unit;
            case HARD -> "定额兑付，每单位 " + CompactFormat.format(note.par()) + " " + unit;
            case FLOAT -> "浮动汇率";
        };
    }

    private static String issueHint(PersonalNote note) {
        return note.regime() == NoteRegime.FLOAT ? "发行准备金充足后才能继续货币发行" : "须按面额或钉住汇率锁足发行准备金";
    }

    private String redeemPayout(PersonalNote note) {
        if (note.regime() != NoteRegime.FLOAT) {
            return note.reserveCurrency();
        }
        String[] payouts = {"diamond", "copper", "note"};
        return payouts[Math.floorMod(payoutIndex, payouts.length)];
    }

    private String payoutLabel(String payout) {
        return switch (payout) {
            case "copper" -> "兑换为铜锭";
            case "note" -> "兑换为银行券";
            default -> "兑换为钻石";
        };
    }

    private static String anchorLabel(String anchor) {
        return switch (anchor) {
            case "copper" -> "铜锭";
            case "note" -> "银行券";
            default -> "钻石";
        };
    }

    private static String anchorEn(String anchor) {
        return switch (anchor) {
            case "copper" -> "copper";
            case "note" -> "central note";
            default -> "diamond";
        };
    }

    private void returnSlot(ServerPlayerEntity player, int slot) {
        ItemStack stack = inventory.getStack(slot);
        inventory.setStack(slot, ItemStack.EMPTY);
        if (stack.isEmpty() || GuiItems.isMarker(stack)) {
            return;
        }
        player.getInventory().offerOrDrop(stack);
    }

    private void returnDeposit(PlayerEntity player) {
        int slot = depositSlot();
        if (slot < 0) {
            return;
        }
        ItemStack stack = inventory.getStack(slot);
        inventory.setStack(slot, ItemStack.EMPTY);
        if (stack.isEmpty() || GuiItems.isMarker(stack)) {
            return;
        }
        if (player instanceof ServerPlayerEntity serverPlayer) {
            serverPlayer.getInventory().offerOrDrop(stack);
        }
    }

    private void scrub(ServerPlayerEntity player) {
        var inv = player.getInventory();
        for (int i = 0; i < inv.size(); i++) {
            if (GuiItems.isMarker(inv.getStack(i))) {
                inv.setStack(i, ItemStack.EMPTY);
            }
        }
        if (GuiItems.isMarker(getCursorStack())) {
            setCursorStack(ItemStack.EMPTY);
        }
    }

    private static void giveItem(ServerPlayerEntity player, CurrencyDef def, BigInteger amount) {
        if (def == null) {
            return;
        }
        var item = Registries.ITEM.get(new Identifier(def.itemId()));
        int left = amount.intValue();
        while (left > 0) {
            int count = Math.min(item.getMaxCount(), left);
            player.getInventory().offerOrDrop(new ItemStack(item, count));
            left -= count;
        }
    }

    private static CurrencyDef itemCurrency(ItemStack stack) {
        return BankRuntime.books().byItem(Registries.ITEM.getId(stack.getItem()).toString());
    }

    private static net.minecraft.item.Item icon(CurrencyDef def) {
        var item = Registries.ITEM.get(new Identifier(def.itemId()));
        return item == Items.AIR ? Items.PAPER : item;
    }

    private static Formatting color(CurrencyDef def) {
        Formatting formatting = Formatting.byName(def.color());
        return formatting == null ? Formatting.WHITE : formatting;
    }

    private static net.minecraft.item.Item concrete(BigDecimal tight) {
        if (tight == null || tight.compareTo(new BigDecimal("0.8")) >= 0) {
            return Items.LIME_CONCRETE;
        }
        if (tight.compareTo(new BigDecimal("0.25")) >= 0) {
            return Items.YELLOW_CONCRETE;
        }
        if (tight.compareTo(new BigDecimal("0.05")) >= 0) {
            return Items.ORANGE_CONCRETE;
        }
        return Items.RED_CONCRETE;
    }

    private static String percent(BigDecimal fee) {
        return fee.multiply(new BigDecimal("100")).stripTrailingZeros().toPlainString() + "%";
    }

    private static int nextIndex(List<CurrencyDef> currencies, int current, int other) {
        if (currencies.size() < 2) {
            return current;
        }
        int next = current;
        do {
            next = (next + 1) % currencies.size();
        } while (next == other && currencies.size() > 1);
        return next;
    }

    private static int nextIssuable(List<CurrencyDef> currencies, int current) {
        for (int step = 1; step <= currencies.size(); step++) {
            int next = (current + step) % currencies.size();
            if (currencies.get(next).issuable()) {
                return next;
            }
        }
        return (current + 1) % currencies.size();
    }

    private static void tell(ServerPlayerEntity player, Spoken spoken) {
        Text text = BankTalk.text(player, spoken);
        player.sendMessage(text, false);
        player.sendMessage(text, true);
    }
}
