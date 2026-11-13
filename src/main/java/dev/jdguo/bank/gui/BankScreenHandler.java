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
import dev.jdguo.bank.market.NoteTrade;
import dev.jdguo.bank.market.PersonalMarket;
import dev.jdguo.bank.market.PersonalNote;
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
    private boolean payeeSelf;
    private int paintTick = -100;

    public BankScreenHandler(int syncId, PlayerInventory playerInventory, SimpleInventory inventory,
                             String outletName, boolean remoteAdmin) {
        super(ScreenHandlerType.GENERIC_9X6, syncId, playerInventory, inventory, 6);
        this.inventory = inventory;
        this.outletName = outletName;
        this.remoteAdmin = remoteAdmin;
        this.owner = (ServerPlayerEntity) playerInventory.player;
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
        if (remoteAdmin) {
            this.page = Page.MANAGEMENT;
        }
        paint();
    }

    public void tick() {
        int now = owner.getServer().getTicks();
        if (now - paintTick >= 20) {
            paint();
        }
    }

    @Override
    public boolean canUse(PlayerEntity player) {
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
            return page == Page.DEPOSIT;
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
        nav(49, Items.WRITABLE_BOOK, "汇票", "nav:checks");
        nav(50, Items.NAME_TAG, "交易", "nav:market");
        nav(52, Items.NETHER_STAR, "个人发行", "nav:issuance");
        if (owner.hasPermissionLevel(2)) {
            nav(51, Items.RECOVERY_COMPASS, "管理", "nav:management");
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
                Text.literal("个人发行").formatted(Formatting.YELLOW, Formatting.BOLD),
                List.of(Text.literal("Personal issuance").formatted(Formatting.GRAY),
                        Text.literal("个人银行券。特殊数量用 /bank note").formatted(Formatting.DARK_GRAY)),
                "noop", 1));
        PersonalNote own = books.personalNote(uuid);
        String suggested = PersonalMarket.shortName(owner.getName().getString());
        String who = suggested.isBlank() ? "请用指令起名" : "名字 " + suggested;
        if (own == null) {
            inventory.setStack(10, marker(Items.DIAMOND,
                    Text.literal("开价 1 钻").formatted(Formatting.AQUA),
                    List.of(Text.literal(who).formatted(Formatting.GRAY),
                            Text.literal("Par 1 diamond").formatted(Formatting.DARK_GRAY)),
                    "pn:create:d:1", 1));
            inventory.setStack(11, marker(Items.DIAMOND,
                    Text.literal("开价 10 钻").formatted(Formatting.AQUA),
                    List.of(Text.literal(who).formatted(Formatting.GRAY),
                            Text.literal("Par 10 diamonds").formatted(Formatting.DARK_GRAY)),
                    "pn:create:d:10", 10));
            inventory.setStack(12, marker(Items.GOLD_INGOT,
                    Text.literal("开价 1000 铜").formatted(Formatting.GOLD),
                    List.of(Text.literal(who).formatted(Formatting.GRAY),
                            Text.literal("Par 1000 copper").formatted(Formatting.DARK_GRAY)),
                    "pn:create:c:1000", 1));
            return;
        }
        inventory.setStack(10, marker(Items.PAPER,
                Text.literal("「" + own.name() + "」 " + CompactFormat.formatRate(books.personalDiamondPrice(own)) + " 钻")
                        .formatted(Formatting.YELLOW),
                List.of(Text.literal("Personal note").formatted(Formatting.GRAY),
                        Text.literal("持有 " + CompactFormat.format(books.personalHolding(own.id(), uuid))).formatted(Formatting.WHITE),
                        Text.literal("M1 " + CompactFormat.format(own.circulation())).formatted(Formatting.DARK_GRAY),
                        Text.literal("准备金够了才能再发行").formatted(Formatting.DARK_GRAY)),
                "noop", 1));
        for (int i = 0; i < NOTE_BUTTONS.length; i++) {
            BigInteger amount = NOTE_BUTTONS[i];
            inventory.setStack(19 + i, marker(Items.GOLD_NUGGET,
                    Text.literal("发行 ×" + amount).formatted(Formatting.GREEN),
                    List.of(Text.literal("Issue ×" + amount).formatted(Formatting.GRAY)),
                    "pn:issue:" + amount, amount.intValue()));
        }
    }

    private void paintMarket() {
        BankBooks books = BankRuntime.books();
        inventory.setStack(4, marker(Items.NAME_TAG,
                Text.literal("个人交易").formatted(Formatting.YELLOW, Formatting.BOLD),
                List.of(Text.literal("Personal notes").formatted(Formatting.GRAY),
                        Text.literal("特殊数量用 /bank note buy 或 sell").formatted(Formatting.DARK_GRAY)),
                "noop", 1));
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
        inventory.setStack(28, marker(Items.EMERALD,
                Text.literal("交易 「" + listed.name() + "」").formatted(Formatting.GREEN),
                List.of(Text.literal("Trade").formatted(Formatting.GRAY),
                        Text.literal("现价 " + CompactFormat.formatRate(books.personalDiamondPrice(listed)) + " 钻").formatted(Formatting.WHITE),
                        Text.literal("点击切换").formatted(Formatting.DARK_GRAY)),
                "pn:cycle", 1));
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
        if ("pn:cycle".equals(action)) {
            if (!books.personalNotes().isEmpty()) {
                marketIndex++;
            }
            return;
        }
        if (action.startsWith("pn:create:")) {
            String[] parts = action.split(":");
            boolean copper = "c".equals(parts[2]);
            BigInteger par = new BigInteger(parts[3]);
            String name = PersonalMarket.shortName(player.getName().getString());
            NoteTrade result = books.createPersonalNote(uuid, name, copper, par);
            if (!result.success()) {
                tell(player, BankPhrases.noteRejected(result.code()));
                return;
            }
            BankRuntime.save();
            tell(player, BankPhrases.noteNamed(result.note()));
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

    private void paintSavings() {
        BankBooks books = BankRuntime.books();
        inventory.setStack(4, marker(Items.GOLD_INGOT,
                Text.literal("个人储蓄").formatted(Formatting.GOLD, Formatting.BOLD),
                List.of(Text.literal("Personal savings").formatted(Formatting.GRAY),
                        Text.literal("这是你的存款，不是央行库存").formatted(Formatting.DARK_GRAY),
                        Text.literal("Your deposits, not central stock").formatted(Formatting.DARK_GRAY)),
                "noop", 1));
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
        var own = books.personalNote(uuid);
        if (own != null) {
            inventory.setStack(22, marker(Items.PAPER,
                    Text.literal("个人银行券 " + own.name()).formatted(Formatting.YELLOW),
                    List.of(Text.literal("Personal note").formatted(Formatting.GRAY),
                            Text.literal("现价 " + CompactFormat.formatRate(books.personalDiamondPrice(own)) + " 钻").formatted(Formatting.WHITE),
                            Text.literal("持有 " + CompactFormat.format(books.personalHolding(own.id(), uuid))).formatted(Formatting.GRAY)),
                    "noop", 1));
        }
    }

    private void paintManagement() {
        BankBooks books = BankRuntime.books();
        inventory.setStack(4, marker(Items.GOLD_BLOCK,
                Text.literal("管理").formatted(Formatting.AQUA, Formatting.BOLD),
                List.of(Text.literal("Central bank").formatted(Formatting.GRAY),
                        Text.literal(books.statusLabel()).formatted(Formatting.YELLOW),
                        Text.literal("汇率 " + BankPhrases.copperPerDiamond(books) + " 铜/钻").formatted(Formatting.WHITE),
                        Text.literal("手续费 " + percent(books.feeFraction())).formatted(Formatting.GRAY)),
                "noop", 1));
        int slot = 10;
        for (CurrencyDef def : books.currencies()) {
            if (slot > 16) {
                break;
            }
            Supply supply = books.supply(def.id());
            inventory.setStack(slot, marker(icon(def),
                    Text.literal(def.name()).formatted(color(def)),
                    List.of(Text.literal(def.nameEn()).formatted(Formatting.GRAY),
                            Text.literal("准备金 " + CompactFormat.format(supply.reserve())).formatted(Formatting.WHITE),
                            Text.literal("发行额 " + CompactFormat.format(supply.liability())).formatted(Formatting.WHITE),
                            Text.literal("Issued " + CompactFormat.format(supply.liability())).formatted(Formatting.DARK_GRAY),
                            Text.literal("锁定 " + CompactFormat.format(supply.locked())).formatted(Formatting.YELLOW)),
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
                Text.literal("兑付能力 " + CompactFormat.formatRatio(tight)).formatted(Formatting.WHITE),
                List.of(Text.literal(books.statusLabel()).formatted(Formatting.YELLOW),
                        Text.literal(books.frozen() ? "兑付：暂停" : "兑付：正常").formatted(Formatting.GRAY),
                        Text.literal(books.frozen() ? "Redemption suspended" : "Redemption available").formatted(Formatting.DARK_GRAY)),
                "noop", 1));
        inventory.setStack(40, marker(Items.CLOCK,
                Text.literal("费率 " + percent(books.feeFraction())).formatted(Formatting.GOLD),
                List.of(), "noop", 1));
        paintCentralIssuance(books);
    }

    private void paintCentralIssuance(BankBooks books) {
        List<CurrencyDef> currencies = new ArrayList<>(books.currencies());
        if (currencies.isEmpty()) {
            return;
        }
        CurrencyDef note = currencies.get(Math.floorMod(adminIndex, currencies.size()));
        inventory.setStack(36, marker(Items.BOOK,
                Text.literal("央行发行 " + note.name()).formatted(color(note)),
                List.of(Text.literal("Central issuance").formatted(Formatting.GRAY),
                        Text.literal("点击切换币种").formatted(Formatting.DARK_GRAY),
                        Text.literal("剩余额度 " + CompactFormat.format(books.backedRoom(note))).formatted(Formatting.WHITE)),
                "ad:cur", 1));
        inventory.setStack(37, marker(Items.GOLD_NUGGET,
                Text.literal("无准备金 +1,000").formatted(Formatting.RED),
                List.of(Text.literal("Without reserves +1,000").formatted(Formatting.GRAY)),
                "ad:unbacked:1000", 1));
        inventory.setStack(38, marker(Items.GOLD_INGOT,
                Text.literal("无准备金 +100万").formatted(Formatting.RED),
                List.of(Text.literal("Without reserves +1,000,000").formatted(Formatting.GRAY)),
                "ad:unbacked:1000000", 1));
        inventory.setStack(39, marker(Items.GOLD_BLOCK,
                Text.literal("无准备金 +1亿").formatted(Formatting.DARK_RED),
                List.of(Text.literal("Without reserves +100,000,000").formatted(Formatting.GRAY)),
                "ad:unbacked:100000000", 1));
        inventory.setStack(41, marker(Items.DIAMOND,
                Text.literal("按准备金满额发行").formatted(Formatting.AQUA),
                List.of(Text.literal("Issue up to the reserve cap").formatted(Formatting.GRAY)),
                "ad:backed", 1));
        inventory.setStack(42, marker(Items.FLINT,
                Text.literal("注销 1,000").formatted(Formatting.DARK_GREEN),
                List.of(Text.literal("Retire 1,000 from your balance").formatted(Formatting.GRAY)),
                "ad:retire:1000", 1));
        inventory.setStack(43, marker(books.frozen() ? Items.RED_DYE : Items.LIME_DYE,
                Text.literal(books.frozen() ? "兑付暂停" : "兑付正常").formatted(books.frozen() ? Formatting.RED : Formatting.GREEN),
                List.of(Text.literal(books.frozen() ? "Redemption suspended" : "Redemption available").formatted(Formatting.GRAY),
                        Text.literal("点击切换。只暂停银行券兑换铜锭或钻石").formatted(Formatting.DARK_GRAY)),
                "ad:freeze", 1));
    }

    private void paintDeposit() {
        inventory.setStack(4, marker(Items.OAK_SIGN,
                Text.literal("把铜锭、钻石或银行券放进中间空格").formatted(Formatting.GOLD),
                List.of(Text.literal("同面额的纸可以叠放。复制件不能重复入账").formatted(Formatting.GRAY),
                        Text.literal("汇票请到「汇票」页兑付").formatted(Formatting.DARK_GRAY),
                        Text.literal("Bills are paid on the Bills page").formatted(Formatting.DARK_GRAY)),
                "noop", 1));
        inventory.setStack(13, marker(Items.SPECTRAL_ARROW,
                Text.literal("↓ 存入").formatted(Formatting.YELLOW),
                List.of(), "noop", 1));
        inventory.setStack(22, ItemStack.EMPTY);
    }

    private void paintWithdraw() {
        BankBooks books = BankRuntime.books();
        inventory.setStack(4, marker(Items.CHEST,
                Text.literal("取出").formatted(Formatting.GOLD),
                List.of(Text.literal("铜锭、钻石，以及可叠放的银行券").formatted(Formatting.GRAY),
                        Text.literal("单次最多 " + BankBooks.MAX_PHYSICAL + " 个").formatted(Formatting.DARK_GRAY)),
                "noop", 1));
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
            if (own != null) {
                BigInteger holding = books.personalHolding(own.id(), owner.getUuid());
                placePaper(base, Items.PAPER, "取出 「" + own.name() + "」", "wd:pn:" + own.id(), holding);
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
                Text.literal("支付 " + pay.name()).formatted(color(pay)),
                List.of(Text.literal("点击切换").formatted(Formatting.GRAY)),
                "ex:pay", 1));
        inventory.setStack(4, marker(Items.SPECTRAL_ARROW,
                Text.literal("对调").formatted(Formatting.WHITE),
                List.of(), "ex:swap", 1));
        inventory.setStack(6, marker(icon(recv),
                Text.literal("获得 " + recv.name()).formatted(color(recv)),
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
                Text.literal("币种 " + cur.name()).formatted(color(cur)),
                List.of(Text.literal("点击切换").formatted(Formatting.GRAY),
                        Text.literal("余额 " + CompactFormat.format(books.balance(owner.getUuid(), cur.id()))).formatted(Formatting.WHITE)),
                "ck:cur", 1));
        inventory.setStack(4, marker(payeeSelf ? Items.NAME_TAG : Items.PAPER,
                Text.literal(payeeSelf ? "仅本人兑付" : "持票人兑付").formatted(payeeSelf ? Formatting.LIGHT_PURPLE : Formatting.AQUA),
                List.of(Text.literal("点击切换抬头").formatted(Formatting.GRAY)),
                "ck:payee", 1));
        BigInteger[] amounts = {BigInteger.ONE, BigInteger.TEN, BigInteger.valueOf(64),
                BigInteger.valueOf(100), BigInteger.valueOf(1000), BigInteger.valueOf(10000)};
        for (int i = 0; i < amounts.length; i++) {
            inventory.setStack(10 + i, marker(Items.WRITTEN_BOOK,
                    Text.literal("开票 " + cur.name() + " ×" + CompactFormat.format(amounts[i])).formatted(Formatting.YELLOW),
                    List.of(Text.literal("从余额划出，生成成书").formatted(Formatting.GRAY)),
                    "ck:issue:" + amounts[i], 1));
        }
        inventory.setStack(22, marker(Items.SPECTRAL_ARROW,
                Text.literal("↓ 放入汇票兑付").formatted(Formatting.GOLD),
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
                Text.literal(label + " 尽可能多").formatted(Formatting.GOLD),
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
                Text.literal(label + " 尽可能多").formatted(Formatting.YELLOW),
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
            default -> -1;
        };
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
