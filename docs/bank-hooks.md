# 商店记账接口

商店不写银行账。它用反射调用 `dev.jdguo.bank.market.ShopOperatingAccount`。

```java
public static ShopOperatingAccount get()
public String currencyId()
public BigInteger balance()
public BigInteger playerBalance(UUID player)
public TxResult transfer(UUID player, BigInteger amount, boolean playerPaysShop)
public TxResult transfer(UUID player, String currency, BigDecimal amount, boolean playerPaysShop)
```

`get()` 在世界账本还没装好时返回 null。

四位小数的过户用第二个 `transfer`。`currency` 取 `note`，或 `personal:` 后面接个人银行券的 UUID。`amount` 按四位小数入账并比较。选中的余额不够支付这一整笔时，返回 `insufficient_funds` 或 `insufficient_shop`，一个单位都不划。`playerPaysShop` 为 true 时玩家购买，借记玩家、贷记商店；为 false 时方向相反。失败代码还有 `bad_amount`、`unknown_currency`、`bank_not_ready`。

`note` 走储蓄总池里的商店经营账户，和玩家的银行券活期存款对转。`personal:<uuid>` 转的是那一张个人银行券的持有额。两条路都不动发行准备金。

只传整数的旧方法仍在，它按 `currencyId()`（默认 `note`）把整数当成四位小数里的整数。

指令字保持英文。玩家看到的拒绝是完整中文。

# Shop settlement hook

The shop posts through `dev.jdguo.bank.market.ShopOperatingAccount` and does not write the ledger itself.

`transfer(UUID, String, BigDecimal, boolean)` settles a four-decimal amount. `currency` is `note` or `personal:<uuid>`. If the selected balance cannot cover the exact total, the whole transfer is refused (`insufficient_funds` or `insufficient_shop`) and nothing moves. `playerPaysShop` true debits the player and credits the shop. Neither path touches issuance reserves.

The older `transfer(UUID, BigInteger, boolean)` remains and posts that integer as an exact four-decimal amount of `currencyId()` (normally `note`).
