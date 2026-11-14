package dev.jdguo.bank.net;

import dev.jdguo.bank.instruments.CheckItems;
import dev.jdguo.bank.instruments.NotePaper;
import dev.jdguo.bank.instruments.PaperTake;
import dev.jdguo.bank.instruments.PresentedCheck;
import dev.jdguo.bank.instruments.PresentedNote;
import dev.jdguo.bank.ledger.Amounts;
import dev.jdguo.bank.ledger.BankBooks;
import dev.jdguo.bank.ledger.TxResult;
import dev.jdguo.bank.market.NoteRegime;
import dev.jdguo.bank.market.NoteTrade;
import dev.jdguo.bank.market.PersonalNote;
import dev.jdguo.bank.market.Redemption;
import dev.jdguo.bank.market.SwapTrade;
import dev.jdguo.bank.outlet.OutletPoint;
import dev.jdguo.bank.persistence.BankRuntime;
import dev.jdguo.bank.text.BankPhrases;
import dev.jdguo.bank.text.BankTalk;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Server-side actions for the optional UI. Every amount is checked here, not on the client. */
public final class BankUiService {
    private BankUiService() {}

    public static void handle(ServerPlayerEntity player, String raw) {
        BankUiNetwork.Session session = BankUiNetwork.session(player);
        if (session == null || raw == null || raw.isBlank() || raw.length() > 200) {
            return;
        }
        if (!session.admin && !near(player, session.outlet)) {
            tell(player, BankPhrases.notInRange());
            BankUiNetwork.forget(player);
            return;
        }
        String[] parts = raw.trim().split(" ", 6);
        BankBooks books = BankRuntime.books();
        switch (parts[0]) {
            case "page" -> page(player, session, parts);
            case "close" -> BankUiNetwork.forget(player);
            case "withdraw" -> withdraw(player, books, parts);
            case "exchange" -> exchange(player, books, parts);
            case "check" -> check(player, books, parts);
            case "deposit-held" -> depositHeld(player, books);
            case "deposit-transfer" -> depositTransfer(player, books, parts);
            case "note-create" -> create(player, books, parts);
            case "note-issue" -> issue(player, books, parts, false);
            case "note-unbacked" -> issue(player, books, parts, true);
            case "note-buy" -> trade(player, books, parts, true);
            case "note-sell" -> trade(player, books, parts, false);
            case "note-swap" -> swap(player, books, parts);
            case "note-redeem" -> redeem(player, books, parts);
            case "note-bankrupt" -> {
                session.confirm = true;
                tell(player, BankPhrases.noteBankruptAsk());
            }
            case "note-bankrupt-no" -> {
                session.confirm = false;
                tell(player, BankPhrases.noteBankruptCancelled());
            }
            case "note-bankrupt-yes" -> bankrupt(player, books, session);
            case "reserve-deposit" -> reserve(player, books, parts, true);
            case "reserve-withdraw" -> reserve(player, books, parts, false);
            case "admin-freeze" -> freeze(player, books, session);
            case "admin-unbacked" -> adminCredit(player, books, session, parts, false);
            case "admin-retire" -> adminCredit(player, books, session, parts, true);
            case "admin-backed" -> adminBacked(player, books, session, parts);
            default -> tell(player, BankPhrases.failure(BankPhrases.Kind.GENERIC, "bad_amount", null, null));
        }
        if (BankUiNetwork.session(player) != null) {
            BankUiNetwork.sync(player);
        }
    }

    private static void page(ServerPlayerEntity player, BankUiNetwork.Session session, String[] parts) {
        if (parts.length < 2) {
            return;
        }
        if ("management".equals(parts[1]) && !player.hasPermissionLevel(2)) {
            tell(player, BankPhrases.failure(BankPhrases.Kind.GENERIC, "denied", null, null));
            return;
        }
        session.page = parts[1];
        session.confirm = false;
    }

    private static void withdraw(ServerPlayerEntity player, BankBooks books, String[] parts) {
        if (parts.length < 3) {
            return;
        }
        boolean all = "max".equals(parts[2]) || "all".equals(parts[2]);
        BigInteger amount = amount(player, parts[2], books, parts[1]);
        if (amount == null) {
            return;
        }
        if ("note".equals(parts[1])) {
            PaperTake take = books.issueCentralPaper(player.getUuid(), amount);
            if (!take.result().success()) {
                tell(player, BankPhrases.failure(BankPhrases.Kind.WITHDRAW, take.result().code(), "note", null));
                return;
            }
            var def = books.currency("note");
            NotePaper.give(player, new PresentedNote("note", null, take.face()), def.name(), take.sheets());
            BankRuntime.save();
            tell(player, BankPhrases.withdraw(books, player.getUuid(), "note", take.result().amount()));
            return;
        }
        TxResult result = books.withdraw(player.getUuid(), parts[1], amount, all);
        if (!result.success()) {
            tell(player, BankPhrases.failure(BankPhrases.Kind.WITHDRAW, result.code(), parts[1], null));
            return;
        }
        give(player, parts[1], result.amount());
        if (result.copperPaid().signum() > 0) {
            give(player, "copper", result.copperPaid());
        }
        BankRuntime.save();
        tell(player, BankPhrases.withdraw(books, player.getUuid(), parts[1], result.amount(), result.copperPaid()));
    }

    private static void exchange(ServerPlayerEntity player, BankBooks books, String[] parts) {
        if (parts.length < 4) {
            return;
        }
        BigInteger qty = amount(player, parts[3], null, null);
        if (qty == null) {
            return;
        }
        TxResult result = books.exchange(player.getUuid(), parts[1], parts[2], qty);
        if (!result.success()) {
            tell(player, BankPhrases.failure(BankPhrases.Kind.EXCHANGE, result.code(), parts[2], parts[1]));
            return;
        }
        BankRuntime.save();
        tell(player, BankPhrases.exchange(books, player.getUuid(), parts[1], parts[2], qty, result.copperPaid()));
    }

    private static void check(ServerPlayerEntity player, BankBooks books, String[] parts) {
        if (parts.length < 3) {
            return;
        }
        BigInteger amount = amount(player, parts[2], null, null);
        if (amount == null) {
            return;
        }
        UUID payee = parts.length > 3 && "self".equals(parts[3]) ? player.getUuid() : null;
        TxResult result = books.issueCheck(player.getUuid(), parts[1], amount, player.getName().getString(), payee, true);
        if (!result.success()) {
            tell(player, BankPhrases.failure(BankPhrases.Kind.ISSUE, result.code(), parts[1], null));
            return;
        }
        player.getInventory().offerOrDrop(CheckItems.create(books, books.check(result.instrumentId())));
        BankRuntime.save();
        tell(player, BankPhrases.issue(books, parts[1], result.amount(), null));
    }

    private static void depositHeld(ServerPlayerEntity player, BankBooks books) {
        var inventory = player.getInventory();
        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (stack.isEmpty()) {
                continue;
            }
            if (NotePaper.tagged(stack)) {
                PresentedNote presented = NotePaper.read(stack);
                if (presented == null) {
                    continue;
                }
                TxResult result = books.redeemPaper(player.getUuid(), presented, BigInteger.valueOf(stack.getCount()));
                if (result.success() || "note_spent".equals(result.code()) || "note_tampered".equals(result.code())) {
                    inventory.setStack(slot, ItemStack.EMPTY);
                }
                if (result.success()) {
                    tell(player, BankPhrases.depositPaper(books, player.getUuid(), "银行券", "bank note", result.amount(), "trimmed".equals(result.code())));
                }
                continue;
            }
            PresentedCheck check = CheckItems.read(stack);
            if (check != null) {
                TxResult result = books.redeemCheck(player.getUuid(), check);
                if (result.success() || "already_redeemed".equals(result.code()) || "tampered".equals(result.code())) {
                    inventory.setStack(slot, ItemStack.EMPTY);
                }
                tell(player, result.success()
                        ? BankPhrases.redeem(books, player.getUuid(), check.currency(), result.amount(), result.copperPaid())
                        : BankPhrases.failure(BankPhrases.Kind.REDEEM, result.code(), check.currency(), null));
                continue;
            }
            var def = books.byItem(Registries.ITEM.getId(stack.getItem()).toString());
            if (def != null && def.kind() == dev.jdguo.bank.currency.CurrencyKind.COMMODITY) {
                TxResult result = books.deposit(player.getUuid(), def.id(), BigInteger.valueOf(stack.getCount()));
                if (result.success()) {
                    inventory.setStack(slot, ItemStack.EMPTY);
                    tell(player, BankPhrases.deposit(books, player.getUuid(), def.id(), result.amount()));
                }
            }
        }
        BankRuntime.save();
    }

    /** Move a typed quantity of one currency from the backpack into the account. */
    private static void depositTransfer(ServerPlayerEntity player, BankBooks books, String[] parts) {
        if (parts.length < 3) {
            return;
        }
        var def = books.currency(parts[1]);
        if (def == null) {
            return;
        }
        boolean all = "all".equals(parts[2]) || "max".equals(parts[2]);
        BigInteger amount = all ? heldCommodityOrNotes(player, def) : amount(player, parts[2], null, null);
        if (amount == null) {
            return;
        }
        if (amount.signum() <= 0) {
            tell(player, BankPhrases.failure(BankPhrases.Kind.DEPOSIT, "empty_hand", def.id(), null));
            return;
        }
        if (def.kind() == dev.jdguo.bank.currency.CurrencyKind.COMMODITY) {
            int need = amount.min(BigInteger.valueOf(Integer.MAX_VALUE)).intValue();
            int have = countCommodity(player, def.itemId());
            if (have < need) {
                tell(player, BankPhrases.failure(BankPhrases.Kind.DEPOSIT, "empty_hand", def.id(), null));
                return;
            }
            int taken = takeCommodity(player, def.itemId(), need);
            if (taken <= 0) {
                tell(player, BankPhrases.failure(BankPhrases.Kind.DEPOSIT, "empty_hand", def.id(), null));
                return;
            }
            TxResult result = books.deposit(player.getUuid(), def.id(), BigInteger.valueOf(taken));
            if (!result.success()) {
                give(player, def.id(), BigInteger.valueOf(taken));
                tell(player, BankPhrases.failure(BankPhrases.Kind.DEPOSIT, result.code(), def.id(), null));
                return;
            }
            BankRuntime.save();
            tell(player, BankPhrases.deposit(books, player.getUuid(), def.id(), result.amount()));
            return;
        }
        List<Pile> piles = centralPiles(player);
        List<Bite> bites = pickSheets(piles, amount);
        if (bites == null) {
            tell(player, BankPhrases.failure(BankPhrases.Kind.DEPOSIT, "empty_hand", "note", null));
            return;
        }
        BigInteger deposited = BigInteger.ZERO;
        var inventory = player.getInventory();
        for (Bite bite : bites) {
            ItemStack stack = inventory.getStack(bite.slot());
            PresentedNote presented = NotePaper.read(stack);
            if (presented == null) {
                continue;
            }
            TxResult result = books.redeemPaper(player.getUuid(), presented, BigInteger.valueOf(bite.sheets()));
            if (!result.success()) {
                continue;
            }
            stack.decrement(bite.sheets());
            deposited = deposited.add(result.amount());
        }
        if (deposited.signum() == 0) {
            tell(player, BankPhrases.failure(BankPhrases.Kind.DEPOSIT, "empty_hand", "note", null));
            return;
        }
        BankRuntime.save();
        tell(player, BankPhrases.depositPaper(books, player.getUuid(), "银行券", "bank note", deposited, deposited.compareTo(amount) < 0));
    }

    private static BigInteger heldCommodityOrNotes(ServerPlayerEntity player, dev.jdguo.bank.currency.CurrencyDef def) {
        if (def.kind() == dev.jdguo.bank.currency.CurrencyKind.COMMODITY) {
            return BigInteger.valueOf(countCommodity(player, def.itemId()));
        }
        BigInteger sum = BigInteger.ZERO;
        for (Pile pile : centralPiles(player)) {
            sum = sum.add(pile.face().multiply(BigInteger.valueOf(pile.count())));
        }
        return sum;
    }

    private static List<Pile> centralPiles(ServerPlayerEntity player) {
        List<Pile> piles = new ArrayList<>();
        var inventory = player.getInventory();
        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (!NotePaper.tagged(stack)) {
                continue;
            }
            PresentedNote presented = NotePaper.read(stack);
            if (presented == null || !presented.central() || presented.face() == null) {
                continue;
            }
            piles.add(new Pile(slot, presented.face(), stack.getCount()));
        }
        return piles;
    }

    /** Whole sheets only. Returns null when the piles cannot make the target exactly. */
    static List<Bite> pickSheets(List<Pile> piles, BigInteger target) {
        if (target == null || target.signum() <= 0) {
            return null;
        }
        BigInteger left = target;
        List<Bite> bites = new ArrayList<>();
        for (Pile pile : piles) {
            if (left.signum() == 0) {
                break;
            }
            if (pile.face() == null || pile.face().signum() <= 0 || pile.count() <= 0) {
                continue;
            }
            int sheets = left.divide(pile.face()).min(BigInteger.valueOf(pile.count())).intValue();
            if (sheets <= 0) {
                continue;
            }
            bites.add(new Bite(pile.slot(), sheets));
            left = left.subtract(pile.face().multiply(BigInteger.valueOf(sheets)));
        }
        return left.signum() == 0 ? bites : null;
    }

    record Pile(int slot, BigInteger face, int count) {}

    record Bite(int slot, int sheets) {}

    private static int countCommodity(ServerPlayerEntity player, String itemId) {
        int count = 0;
        var inventory = player.getInventory();
        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (!stack.isEmpty() && itemId.equals(Registries.ITEM.getId(stack.getItem()).toString())) {
                count += stack.getCount();
            }
        }
        return count;
    }

    private static int takeCommodity(ServerPlayerEntity player, String itemId, int need) {
        int left = need;
        var inventory = player.getInventory();
        for (int slot = 0; slot < inventory.size() && left > 0; slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (stack.isEmpty() || !itemId.equals(Registries.ITEM.getId(stack.getItem()).toString())) {
                continue;
            }
            int take = Math.min(left, stack.getCount());
            stack.decrement(take);
            left -= take;
        }
        return need - left;
    }

    private static void create(ServerPlayerEntity player, BankBooks books, String[] parts) {
        if (parts.length < 5) {
            return;
        }
        NoteRegime regime = switch (parts[1]) {
            case "peg" -> NoteRegime.PEG;
            case "hard" -> NoteRegime.HARD;
            case "float" -> NoteRegime.FLOAT;
            default -> null;
        };
        if (regime == null) {
            tell(player, BankPhrases.noteRejected("anchor"));
            return;
        }
        BigInteger par = amount(player, parts[4], null, null);
        if (par == null) {
            return;
        }
        NoteTrade result = books.createPersonalNote(player.getUuid(), parts[3], regime, parts[2], par);
        if (!result.success()) {
            tell(player, BankPhrases.noteRejected(result.code()));
            return;
        }
        BankRuntime.save();
        tell(player, BankPhrases.noteNamed(result.note()));
    }

    private static void issue(ServerPlayerEntity player, BankBooks books, String[] parts, boolean unbacked) {
        if (parts.length < 2) {
            return;
        }
        BigInteger amount = amount(player, parts[1], null, null);
        if (amount == null) {
            return;
        }
        NoteTrade result = unbacked
                ? books.issuePersonalNoteUnbacked(player.getUuid(), amount)
                : books.issuePersonalNote(player.getUuid(), amount);
        if (!result.success()) {
            tell(player, BankPhrases.noteRejected(result.code()));
            return;
        }
        BankRuntime.save();
        tell(player, unbacked
                ? BankPhrases.noteUnbacked(books, result.note(), amount)
                : BankPhrases.noteIssued(books, result.note(), amount));
    }

    private static void trade(ServerPlayerEntity player, BankBooks books, String[] parts, boolean buy) {
        if (parts.length < 3) {
            return;
        }
        BigInteger amount = amount(player, parts[2], null, null);
        if (amount == null) {
            return;
        }
        NoteTrade result = buy
                ? books.buyPersonalNote(player.getUuid(), parts[1], amount)
                : books.sellPersonalNote(player.getUuid(), parts[1], amount);
        if (!result.success()) {
            tell(player, BankPhrases.noteRejected(result.code()));
            return;
        }
        BankRuntime.save();
        tell(player, BankPhrases.noteTrade(buy, result.note().name(), amount, result.centralPaid(), result.copperPaid(), books, player.getUuid()));
    }

    private static void swap(ServerPlayerEntity player, BankBooks books, String[] parts) {
        if (parts.length < 4) {
            return;
        }
        BigInteger amount = amount(player, parts[3], null, null);
        if (amount == null) {
            return;
        }
        SwapTrade result = books.swapPersonalNotes(player.getUuid(), parts[1], parts[2], amount);
        if (!result.success()) {
            tell(player, BankPhrases.noteRejected(result.code()));
            return;
        }
        BankRuntime.save();
        tell(player, BankPhrases.noteSwap(result, books, player.getUuid()));
    }

    private static void redeem(ServerPlayerEntity player, BankBooks books, String[] parts) {
        if (parts.length < 4) {
            return;
        }
        BigInteger amount = amount(player, parts[3], null, null);
        if (amount == null) {
            return;
        }
        Redemption result = books.redeemPersonal(player.getUuid(), parts[1], amount, parts[2]);
        if (!result.success()) {
            tell(player, BankPhrases.noteRejected(result.code()));
            return;
        }
        BankRuntime.save();
        tell(player, BankPhrases.noteRedeem(result, books, player.getUuid()));
    }

    private static void bankrupt(ServerPlayerEntity player, BankBooks books, BankUiNetwork.Session session) {
        if (!session.confirm) {
            return;
        }
        session.confirm = false;
        NoteTrade result = books.declareBankruptcy(player.getUuid(), null, player.hasPermissionLevel(2));
        if (!result.success()) {
            tell(player, BankPhrases.noteRejected(result.code()));
            return;
        }
        BankRuntime.save();
        tell(player, BankPhrases.noteBankrupt(books, result.note()));
    }

    private static void reserve(ServerPlayerEntity player, BankBooks books, String[] parts, boolean deposit) {
        if (parts.length < 2) {
            return;
        }
        BigInteger amount = amount(player, parts[1], null, null);
        if (amount == null) {
            return;
        }
        TxResult result = deposit
                ? books.depositCentralReserve(player.getUuid(), amount)
                : books.withdrawCentralReserve(player.getUuid(), amount);
        if (!result.success()) {
            tell(player, BankPhrases.noteRejected(result.code()));
            return;
        }
        BankRuntime.save();
        tell(player, BankPhrases.centralReserveMoved(deposit, amount));
    }

    private static void freeze(ServerPlayerEntity player, BankBooks books, BankUiNetwork.Session session) {
        if (!session.admin || !player.hasPermissionLevel(2)) {
            tell(player, BankPhrases.failure(BankPhrases.Kind.GENERIC, "denied", null, null));
            return;
        }
        books.setFrozen(!books.frozen());
        BankRuntime.save();
        tell(player, BankPhrases.freeze(books.frozen()));
    }

    /**
     * {@code admin-backed <currency>} issues the whole remaining reserve room, the same posting as the
     * chest button {@code ad:backed}. {@code admin-backed <currency> <amount>} issues that amount
     * through {@link BankBooks#creditBacked}.
     */
    private static void adminBacked(ServerPlayerEntity player, BankBooks books, BankUiNetwork.Session session, String[] parts) {
        if (!session.admin || !player.hasPermissionLevel(2) || parts.length < 2) {
            tell(player, BankPhrases.failure(BankPhrases.Kind.GENERIC, "denied", null, null));
            return;
        }
        if (books.currency(parts[1]) == null) {
            tell(player, BankPhrases.failure(BankPhrases.Kind.CREDIT_BACKED, "unknown_currency", parts[1], null));
            return;
        }
        BigInteger amount;
        if (parts.length >= 3) {
            amount = amount(player, parts[2], null, null);
            if (amount == null) {
                return;
            }
        } else {
            amount = books.backedRoom(books.currency(parts[1]));
            if (amount.signum() <= 0) {
                tell(player, BankPhrases.failure(BankPhrases.Kind.CREDIT_BACKED, "over_cap", parts[1], null));
                return;
            }
        }
        TxResult result = books.creditBacked(player.getUuid(), parts[1], amount);
        if (!result.success()) {
            tell(player, BankPhrases.failure(BankPhrases.Kind.CREDIT_BACKED, result.code(), parts[1], null));
            return;
        }
        BankRuntime.save();
        tell(player, BankPhrases.credit(books, "backed", parts[1], result.amount(), player.getName().getString()));
    }

    private static void adminCredit(ServerPlayerEntity player, BankBooks books, BankUiNetwork.Session session, String[] parts, boolean retire) {
        if (!session.admin || !player.hasPermissionLevel(2) || parts.length < 3) {
            tell(player, BankPhrases.failure(BankPhrases.Kind.GENERIC, "denied", null, null));
            return;
        }
        BigInteger amount = amount(player, parts[2], null, null);
        if (amount == null) {
            return;
        }
        TxResult result = retire
                ? books.retire(player.getUuid(), parts[1], amount)
                : books.creditUnbacked(player.getUuid(), parts[1], amount);
        if (!result.success()) {
            tell(player, BankPhrases.failure(retire ? BankPhrases.Kind.RETIRE : BankPhrases.Kind.CREDIT_UNBACKED, result.code(), parts[1], null));
            return;
        }
        BankRuntime.save();
        tell(player, BankPhrases.credit(books, retire ? "retire" : "unbacked", parts[1], result.amount(), player.getName().getString()));
    }

    private static BigInteger amount(ServerPlayerEntity player, String raw, BankBooks books, String currency) {
        try {
            if (("max".equals(raw) || "all".equals(raw)) && books != null) {
                return books.balance(player.getUuid(), currency);
            }
            return Amounts.parse(raw);
        } catch (RuntimeException error) {
            tell(player, BankPhrases.amountProblem(error.getMessage()));
            return null;
        }
    }

    private static void give(ServerPlayerEntity player, String currency, BigInteger amount) {
        var def = BankRuntime.books().currency(currency);
        if (def == null || amount.signum() <= 0) {
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

    private static boolean near(ServerPlayerEntity player, String outletName) {
        OutletPoint outlet = BankRuntime.books().outlet(outletName);
        if (outlet == null) {
            return false;
        }
        String world = player.getWorld().getRegistryKey().getValue().toString();
        double limit = Math.max(outlet.radius(), 6.0) + 1.5;
        return outlet.distanceSquared(world, player.getX(), player.getY(), player.getZ()) <= limit * limit;
    }

    private static void tell(ServerPlayerEntity player, dev.jdguo.bank.text.Spoken spoken) {
        player.sendMessage(BankTalk.text(player, spoken), false);
    }
}
