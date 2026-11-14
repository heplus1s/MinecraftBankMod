# 商店与市场的记账接口

商店和市场不写这本账。它们只调用下面两个公开类型。银行不依赖商店模组，也不依赖市场模组。

存档仍是 `data/bankmod.dat` 和旁边的 `data/bankmod-ledger.json`。新增的余额是可选字段。旧存档没有这些字段时，两个分户按 0 读入，玩家余额、发行准备金和个人发行金库保持原数。快照版本号仍是 1。

## 商店经营账户

类名是 `dev.jdguo.bank.market.ShopOperatingAccount`。商店用反射调用，方法如下。

```java
public static ShopOperatingAccount get()
public String currencyId()
public BigInteger balance()
public BigInteger playerBalance(UUID player)
public TxResult transfer(UUID player, BigInteger amount, boolean playerPaysShop)
```

`get()` 在世界账本还没装好时返回 null。`currencyId()` 是中央虚拟货币，默认 `note`。`playerPaysShop` 为 true 时，玩家购买，借记玩家活期存款，贷记商店经营账户。为 false 时方向相反。失败代码是 `insufficient_funds`、`insufficient_shop`、`bad_amount`。失败时不改任何余额。

这个账户是储蓄总池里的分户，和玩家活期存款记在同一笔总账下。过户只在总池内部进行。发行准备金、个人发行金库、纸币和土地出让账户都不变。它按活期存款计息，和玩家存款用同一个年利率、同一个 365 个游戏日，利息从手续费留存里支付。商店自己不计这笔息。

## 市场结算

类名是 `dev.jdguo.bank.api.MarketSettlement`。三个方法都是静态的，成功时返回的 `TxResult.success()` 为 true，并且在返回前写下存档。

```java
public static TxResult transfer(UUID from, UUID to, String currency, BigInteger amount, String reference)
public static TxResult creditLandGrant(UUID payer, String currency, BigInteger amount, String reference)
public static TxResult refundLandGrant(UUID payer, String currency, BigInteger amount, String reference)
```

`transfer` 是玩家付给卖方，或把这笔钱退回。货款不是中央银行的收入，商店经营账户不收这笔钱。储蓄总池总额不变。

`creditLandGrant` 只用于服务器出让土地，以及以后处置收回的土地。它借记付款人的储蓄，贷记土地出让账户。玩家之间的土地买卖仍走 `transfer`。`refundLandGrant` 把土地出让账户里的同一金额退回付款人。余额不够时返回 `insufficient_funds`，不扣一部分。

土地出让账户也是储蓄总池的分户，不是第三本资金池。银行券分户按活期存款计息。铜锭和钻石分户跟个人实物存款一样不计息。两个分户都不动发行准备金。

`reference` 由调用方传入，例如 `sale:` 加挂牌编号，或 `refund:` 加成交编号。空引用会拒绝。引用不写进世界存档。

管理员查看这两个分户用 `/bank admin accounts`。指令字是英文。聊天答复用现有的 `[银行]` 前缀。可选的银行界面在管理页显示「商店经营账户」和「土地出让账户」。

## 与市场安装口的差别

市场也可以接受它自己的 `BankSettlement.install` 和 `LandGrantAccount.install`。这两个类型在市场模组里。银行不引用市场模组，因此不调用它们。市场在没有安装口时会改用上面的 `MarketSettlement`。

# Shop and market settlement hooks

The shop and the market do not write this ledger. They call two public types. This mod does not depend on either of them.

World data stays in `data/bankmod.dat` and `data/bankmod-ledger.json`. The new balances are optional fields. An older save without them loads both sub-accounts as zero and keeps player balances, issuance reserves, and personal issuance vaults. The snapshot version stays 1.

`dev.jdguo.bank.market.ShopOperatingAccount` exposes `get`, `currencyId`, `balance`, `playerBalance`, and `transfer(UUID, BigInteger, boolean)`. A purchase debits the player's demand deposit and credits the shop operating account. A sale does the reverse. Failure codes are `insufficient_funds`, `insufficient_shop`, and `bad_amount`. Nothing moves on failure.

`dev.jdguo.bank.api.MarketSettlement` exposes static `transfer`, `creditLandGrant`, and `refundLandGrant`. Each saves the ledger before returning. `transfer` pays the seller. `creditLandGrant` is the server land sale, and later the disposal of recovered land. `refundLandGrant` reverses it. A short balance returns `insufficient_funds` and takes nothing.

Both accounts are sub-ledgers of the savings pool. Note balances earn the same demand-deposit interest as personal note savings, paid from retained fees over 365 game days. Commodity balances do not earn interest. Issuance reserves are not part of either movement. The bank's own bank-note market is unchanged.

The market's `BankSettlement.install` and `LandGrantAccount.install` live in the market mod. This bank does not call them. The market uses `MarketSettlement` when those installers were not called.

`/bank admin accounts` prints both balances. Command words are English. The reply uses the existing `[银行]` prefix. The optional bank UI shows 商店经营账户 and 土地出让账户 on the admin page.
