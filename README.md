# 中央银行

[English version below.](#central-bank)

这是一个 Minecraft 1.20.1 的 Fabric 模组。连到专用服务器时，玩家用原版客户端就能来银行存钱、取钱、换汇、开汇票，不用再装模组或资源包。想自己单人试，把模组装进 Fabric 客户端，打开世界即可。

钱记在模组自己的账本里，不用记分板，数字再大也不会溢出。

## 安装

单人测试：用 Fabric 1.20.1 的客户端，把 jar 和 Fabric API 一起放进客户端的 `mods/`，然后打开一个世界。银行跑在这个世界自带的集成服务器上。

专用服务器还是原来的装法。服务器需要 Fabric Loader、Fabric API，以及 Java 17 或更高版本。

1. 在项目目录执行 `./gradlew build`
2. 把 `build/libs/bankmod-1.0.0.jar` 放到服务器的 `mods/`，并确保同目录里有 Fabric API
3. 启动一次服务器，会自动生成 `config/bank.json`

连到专用服务器的原版客户端不用装这个模组，也不用资源包。

## 先设一个网点

银行不是在世界里随便一个地方打开的，要先由管理员在建筑上指定一个柜台。

看准要当柜台的方块（大约 6 格以内），执行：

```
/bank outlet set 主楼
```

如果没看准方块，会用你脚下那一格。

设好以后，玩家右键这个方块就会打开箱子界面。如果这格本来就是箱子，这次右键会改由银行处理。站在网点附近（默认 4 格）也可以输入 `/bank`。全息字牌会浮在柜台上方，服务器重启后还在。

一个世界可以设多个网点。账是全服共用的，不是每个柜台一本。

| 命令 | 谁能用 | 做什么 |
| --- | --- | --- |
| `/bank outlet set <名字>` | 管理员 | 把准星指着的方块设成网点 |
| `/bank outlet move <名字>` | 管理员 | 把网点搬到准星下的新方块 |
| `/bank outlet remove <名字>` | 管理员 | 删掉网点，并拆掉全息字牌 |
| `/bank outlet radius <名字> <1-32>` | 管理员 | 附近可以用 `/bank` 的半径 |
| `/bank outlet lift <名字> <0.5-16>` | 管理员 | 全息字牌离方块的高度，默认 2.4 |
| `/bank outlet hologram <名字> on\|off` | 管理员 | 这个网点要不要显示全息字牌 |
| `/bank outlet tp <名字>` | 管理员 | 传送到网点 |
| `/bank outlets` | 所有人 | 列出网点 |

## 怎么用

界面是原版的大箱子。格子里的按钮是普通物品，拿不走；万一进了背包或掉在地上，也会被清掉。

底栏可以切换：总览、存入、取出、兑换、汇票。管理员会多一个「信贷」。普通玩家必须在网点才能开柜台；管理员用 `/bank admin` 可以在别处打开信贷页。

- **存入**：把铜锭或钻石放进空格。你的余额、银行准备金和账面负债会一起增加。
- **取出**：可以按 1、10、64 或「尽可能多」来取。金库不够会拒绝；选「尽可能多」时，会按实际库存少给。一次最多取 4096 个，再多请开汇票。
- **兑换**：先选付出哪种货币、得到哪种货币。按钮上写的是已经算上手续费的价格。
- **汇票**：从余额里开出一本成书，可以写成持票人或仅本人。把书放进兑现格，钱会回到账户。
- **信贷**（管理员）：可以无准备金增发、按准备金发行、注销，也可以暂停用纸币兑换硬通货。

做成或被拒绝时，会在聊天里说一声，动作栏也会闪一下。汇率不会刷在聊天里。

## 大厅里能看到什么

这些显示默认都是开着的。数字很大时会写成 `k`、`M`，或用科学计数法。

- **侧边栏**：每人一份，大概每秒更新一次。上面有余额、对钻石的汇率、手续费、兑现情况和最近的网点。1.20.1 的侧边栏行尾会带一个红色数字，那只是排列用的序号，不是余额。
- **全息字牌**：浮在网点上方，面向玩家，显示汇率和状态。
- **Boss 条**：银票对钻石的价格，进度表示还能兑出多少。紧了会变黄，挤兑或停止兑现会变红。
- **玩家列表**：页眉和页脚会显示银行名称和主要汇率。

```
/bank display sidebar on|off
/bank display bossbar on|off
/bank display tab on|off
/bank display global sidebar|bossbar|tab|hologram|join on|off
```

前三条只管自己。`global` 是管理员的总开关，关掉以后，个人再开也看不到。全息字牌只有总开关，以及每个网点自己的开关。

## 货币和汇率

默认有三种货币，写在 `config/bank.json`。第一次进世界时会抄进存档，之后以存档为准。改完配置后用 `/bank reload`，可以更新费率、权重和上下限，也能加上新货币，但不会清空账户。

| id | 种类 | 物品 | 初始准备 / 负债 | 说明 |
| --- | --- | --- | --- | --- |
| `copper` | 实物 | 铜锭 | 1,000,000 | 铜 |
| `diamond` | 实物 | 钻石 | 1,000 | 计价货币 |
| `note` | 纸币 | 用成书当汇票，不能把普通纸张存进去 | 100,000 | 银票，开局 100 张兑 1 钻 |

默认手续费是 2%。每种货币的差额权重 α 默认是 1000‰。汇率默认不设上下限，这样纸币贬值时，数字可以变得很大。如果要限制，可以用：

```
/bank clamp max 999999
/bank clamp min off
/bank fee 2
/bank alpha note 1000
```

如果把 α 设成 0，银票会钉在平价上，多印也不会改汇率。不过准备金率照样会下降，金库空了还是兑不出来。

和旧版银行数据包相比，这里把差额的方向改了一下：多印负债、准备金不动时，这种货币会变弱，而不是变硬。开局把种子准备金同时记作负债，所以账是平的。1 钻石一开始仍值 1000 铜，和原来看到的汇率一样。计算用高精度小数，付款时向上取整到整数。

有效数量按下面这个式子算：

```
V = R + (L - R) × α / 1000
```

其中 `R` 是准备金，`L` 是负债。α 为 1000 时，`V` 就等于负债。银票对钻石还会再看一层：负债相对准备金越高，1 钻要换的银票就越多；无准备增发如果把这个比例从 1 拉到 2，1 钻就会从 100 张变成 200 张。金库里的钻石被取光，且 α 不是 0 时，报价会记成崩溃，并拒绝再兑。

存入实物时，余额、准备金和负债一起加。取出时一起减，但已经锁给银票的那部分钻石取不走。兑换只调整负债，不动金库。用纸币换实物时，不能超过金库实际还能兑出的数量。

## 信贷

管理员（或权限等级 2）可以用：

```
/bank credit unbacked <玩家> note 1000000
/bank credit backed <玩家> note 1000
/bank credit retire <玩家> note 1000
/bank credit check <玩家> note 1e12
/bank freeze on
/bank supply
/bank reserve diamond 1000
```

金额可以写成 `1_000`、`1.5k`、`2m`、`1e40`。

- **无准备发行**：给玩家加余额和负债，准备金不变。银票会贬值。发得特别多时，Boss 条和全息字牌会显示挤兑。
- **按准备金发行**：额度按钻石库存和设置计算。默认情况下，开局 1000 钻正好覆盖已有的 10 万银票，所以一开始额度为 0，要先存进钻石才有新额度。这样发出去的银票会锁住对应的钻石，平价不变。
- **注销**：先销掉没有准备金的部分，汇率会好转；再往下销，才会解锁钻石。
- **停止兑现**：只阻止用纸币换铜或钻石。普通存取、用铜钻换银票、汇票进出账户都还可以。

`/bank reserve` 用来直接改库存数字，给运维用。锁住的部分不能改得比锁定数量更少。

## 汇票

开出来的是一本原版成书，里面记着编号、币种、金额和出票人等信息，并标成「破损」，不能用工作台再复制。就算用别的办法复制了，服务器也只认第一次兑现，同一编号的第二本会被没收。书上的金额如果和存根对不上，同样没收。如果汇票写了抬头，而你不是那个人，书会还给你，存根不动。

从自己余额开票，只是把存款换成一张纸，总负债不变。管理员的 `/bank credit check` 则是新印出来的负债，玩家必须在线才能拿到书。

## 存档和配置

账记在主世界的 `data/bankmod.dat`，旁边还有一份方便查看的 `data/bankmod-ledger.json`。卸掉模组不会自动删除这些文件。如果要清空银行，先停服，再删掉这两个文件。

如果以前用过记分板版的银行数据包，可以执行一次：

```
/bank import-scores
```

它会把能对上玩家的 `copper`、`diamond` 正数当作存款导进来，每个玩家只导一次。名字以 `#` 开头的条目会跳过。旧数据包里的金库数字不会自动过来，需要对齐时用 `/bank reserve`。

`config/bank.json` 里和默认经济设定对应的部分如下：

```json
{
  "numeraire": "diamond",
  "feePercent": "2",
  "clampMin": null,
  "clampMax": null,
  "outletRadius": 4.0,
  "currencies": [
    { "id": "copper", "kind": "commodity", "item": "minecraft:copper_ingot", "initialReserve": "1000000", "alphaPermille": "1000" },
    { "id": "diamond", "kind": "commodity", "item": "minecraft:diamond", "initialReserve": "1000", "alphaPermille": "1000" },
    { "id": "note", "kind": "virtual", "name": "银票", "initialReserve": "100000", "issuable": true, "backing": "diamond", "parPerBacking": "100", "haircut": "1", "minReserveRatio": "1", "alphaPermille": "1000" }
  ]
}
```

实物货币的物品 id 不能重复。纸币必须指定一种实物货币作为准备金。

## 还没有的功能

现在还没有股市。以后如果要做，会另开模块，不会把行情写进这本银行账里。

## 编译

```
./gradlew build
./gradlew test
```

`./gradlew test` 不会启动游戏。它主要检查存取、兑换、汇票是否只兑一次、滥发后银票会不会贬值、按准备金发行会不会锁钻，以及大数字会不会算爆。

许可是 MIT，版权归 heplus1s。Minecraft 是 Mojang 的商标，本项目与 Mojang 无关。

---

# Central Bank

[中文在上方。](#中央银行)

This is a Fabric mod for Minecraft 1.20.1. On a dedicated server, players can deposit, withdraw, exchange currencies, and issue bills of exchange with a vanilla client. No client mod or resource pack is needed. To try it alone, install the mod on a Fabric client and open a world.

Balances are stored in the mod's own ledger, not on the scoreboard, so the numbers can get arbitrarily large without overflowing.

## Installation

Singleplayer: use a Fabric 1.20.1 client, put the jar in the client's `mods/` folder together with Fabric API, then open a world. The bank runs on the integrated server that comes with that world.

A dedicated server is installed as before. The server needs Fabric Loader, Fabric API, and Java 17 or newer.

1. Run `./gradlew build` in the project directory
2. Put `build/libs/bankmod-1.0.0.jar` in the server's `mods/` folder, and make sure Fabric API is there too
3. Start the server once. It will create `config/bank.json`

A vanilla client connecting to a dedicated server still should not install this mod, and does not need a resource pack.

## Set up an outlet first

You can't just open the bank anywhere in the world. An operator has to mark a counter on a building first.

Look at the block you want as the counter (within about 6 blocks) and run:

```
/bank outlet set 主楼
```

If you aren't looking at a block, it uses the one under your feet.

After that, right-clicking the block opens the chest menu. If the block is already a chest, that right-click is handled by the bank instead. Players standing near the outlet (4 blocks by default) can also type `/bank`. A hologram floats above the counter and comes back after a restart.

A world can have more than one outlet. The ledger is shared across the server, not one book per counter.

| Command | Who can use it | What it does |
| --- | --- | --- |
| `/bank outlet set <name>` | operators | Turn the block you're looking at into an outlet |
| `/bank outlet move <name>` | operators | Move the outlet to the block you're looking at |
| `/bank outlet remove <name>` | operators | Remove the outlet and its hologram |
| `/bank outlet radius <name> <1-32>` | operators | How close you have to be to use `/bank` |
| `/bank outlet lift <name> <0.5-16>` | operators | How high the hologram sits above the block, default 2.4 |
| `/bank outlet hologram <name> on\|off` | operators | Whether this outlet shows a hologram |
| `/bank outlet tp <name>` | operators | Teleport to the outlet |
| `/bank outlets` | everyone | List outlets |

## How to use it

The interface is a vanilla large chest. The buttons in the slots are ordinary items and can't be taken. If one ends up in an inventory or on the ground, it gets cleared.

The bottom row switches pages: overview, deposit, withdraw, exchange, and bills. Operators also get a Credit page. Regular players can only open the counter at an outlet. Operators can use `/bank admin` to open the credit page elsewhere.

- **Deposit**: Put copper ingots or diamonds in an empty slot. Your balance, the bank's reserves, and recorded liabilities all go up together.
- **Withdraw**: You can take 1, 10, 64, or "as many as possible". If the vault doesn't have enough, the request is refused; "as many as possible" gives you whatever is actually in stock. The cap per withdrawal is 4096 items; for more, issue a bill.
- **Exchange**: First pick what you pay and what you get. The button already shows the price with the fee included.
- **Bills**: Issue a written book from your balance, payable to bearer or to yourself only. Put the book in the redeem slot and the money goes back to the account.
- **Credit** (operators): You can issue currency without reserves, issue against reserves, retire it, or temporarily stop redeeming paper for hard currency.

Successful or refused actions get a chat message and a flash on the action bar. Exchange rates are not spammed in chat.

## What you see in the lobby

These displays are on by default. Very large numbers are written as `k`, `M`, or in scientific notation.

- **Sidebar**: One per player, updated about once a second. It shows balances, rates against diamond, the fee, convertibility, and the nearest outlet. On 1.20.1 the end of each sidebar line has a red number. That's just a sort index, not a balance.
- **Hologram**: Floats above the outlet, faces the player, and shows rates and status.
- **Boss bar**: The note-to-diamond price. The bar fill shows how much can still be redeemed. It turns yellow when tight, and red on a bank run or when convertibility is frozen.
- **Player list**: The header and footer show the bank name and the main rates.

```
/bank display sidebar on|off
/bank display bossbar on|off
/bank display tab on|off
/bank display global sidebar|bossbar|tab|hologram|join on|off
```

The first three commands only affect you. `global` is the operator master switch; once it's off, turning your own display back on does nothing. Holograms only have the master switch, plus a per-outlet switch.

## Currencies and exchange rates

There are three default currencies, written in `config/bank.json`. They are copied into the world save the first time you enter it, and the save is what counts after that. After editing the config, `/bank reload` can update fees, weights, and limits, and can add new currencies, without wiping accounts.

| id | Kind | Item | Opening reserve / liability | Notes |
| --- | --- | --- | --- | --- |
| `copper` | commodity | copper ingot | 1,000,000 | copper |
| `diamond` | commodity | diamond | 1,000 | the unit of account |
| `note` | paper | Uses written books as bills; ordinary paper can't be deposited | 100,000 | 银票 (notes). At the start, 100 notes buy 1 diamond |

The default fee is 2%. The difference weight α for each currency defaults to 1000‰. Rates have no min/max clamp by default, so the numbers can get very large when paper money depreciates. If you want limits:

```
/bank clamp max 999999
/bank clamp min off
/bank fee 2
/bank alpha note 1000
```

If you set α to 0, notes stay at par and printing more doesn't change the rate. The reserve ratio still falls, and you still can't redeem once the vault is empty.

Compared with the old bank datapack, the sign of the difference term is flipped: when liabilities are printed without adding reserves, the currency gets weaker, not stronger. Opening books record the seed reserves as liabilities as well, so the books start square. One diamond is still worth 1000 copper at the start, same as the rate you would have seen before. Calculations use high-precision decimals; payments are rounded up to a whole number.

Effective quantity is:

```
V = R + (L - R) × α / 1000
```

`R` is reserves, `L` is liabilities. When α is 1000, `V` equals liabilities. Notes versus diamonds have an extra layer: the more liabilities there are relative to reserves, the more notes it takes to buy one diamond. If unbacked issuance pulls that ratio from 1 to 2, one diamond goes from 100 notes to 200. When the diamond vault is emptied and α is not 0, the quote is marked collapsed and further conversion is refused.

Depositing a commodity increases the balance, reserves, and liabilities together. Withdrawals decrease all three, but diamonds already locked to notes can't be taken out. Exchange only adjusts liabilities, not the vault. Converting paper into a commodity can't exceed what the vault can actually still redeem.

## Credit

Operators (or permission level 2) can use:

```
/bank credit unbacked <player> note 1000000
/bank credit backed <player> note 1000
/bank credit retire <player> note 1000
/bank credit check <player> note 1e12
/bank freeze on
/bank supply
/bank reserve diamond 1000
```

Amounts can be written as `1_000`, `1.5k`, `2m`, or `1e40`.

- **Unbacked issuance**: Adds to the player's balance and to liabilities, without changing reserves. Notes depreciate. If you issue a great deal, the boss bar and hologram will show a bank run.
- **Backed issuance**: The allowance is computed from diamond stock and the settings. By default, the opening 1000 diamonds already cover the existing 100,000 notes, so the starting allowance is 0. You have to deposit diamonds first to get more room. Notes issued this way lock the corresponding diamonds and stay at par.
- **Retirement**: Unbacked amounts are retired first, which improves the rate; only further retirement unlocks diamonds.
- **Freeze convertibility**: Only blocks exchanging paper for copper or diamonds. Ordinary deposits and withdrawals, exchanging copper or diamonds for notes, and bills in or out of accounts still work.

`/bank reserve` is for operators to change the stock figure directly. You can't set it below the locked amount.

## Bills of exchange

What you get is a vanilla written book. It records an id, the currency, the amount, the issuer, and so on, and is marked tattered so it can't be copied at a crafting table. Even if it is copied some other way, the server only honors the first redemption; a second book with the same id is confiscated. If the amount on the book doesn't match the stub, it's confiscated too. If the bill has a named payee and you aren't that person, the book is returned and the stub is left alone.

Issuing a bill from your own balance just turns a deposit into a piece of paper. Total liabilities don't change. An operator's `/bank credit check` is newly printed liability, and the player has to be online to receive the book.

## Saves and config

The ledger lives in the overworld at `data/bankmod.dat`, with a human-readable copy at `data/bankmod-ledger.json`. Uninstalling the mod does not delete these files. To wipe the bank, stop the server and delete both.

If you previously used the scoreboard bank datapack, run this once:

```
/bank import-scores
```

It imports positive `copper` and `diamond` scores for players it can match, once per player. Entries whose names start with `#` are skipped. Vault figures from the old datapack are not imported automatically; use `/bank reserve` if you need to line them up.

The part of `config/bank.json` that matches the default economy looks like this:

```json
{
  "numeraire": "diamond",
  "feePercent": "2",
  "clampMin": null,
  "clampMax": null,
  "outletRadius": 4.0,
  "currencies": [
    { "id": "copper", "kind": "commodity", "item": "minecraft:copper_ingot", "initialReserve": "1000000", "alphaPermille": "1000" },
    { "id": "diamond", "kind": "commodity", "item": "minecraft:diamond", "initialReserve": "1000", "alphaPermille": "1000" },
    { "id": "note", "kind": "virtual", "name": "银票", "initialReserve": "100000", "issuable": true, "backing": "diamond", "parPerBacking": "100", "haircut": "1", "minReserveRatio": "1", "alphaPermille": "1000" }
  ]
}
```

Commodity item ids can't be reused. A paper currency has to name a commodity as its backing.

## What's not here yet

There's no stock market yet. If that gets built later, it will be a separate module. Market prices won't be written into this bank ledger.

## Building

```
./gradlew build
./gradlew test
```

`./gradlew test` does not start the game. It mainly checks deposits, withdrawals, exchange, that bills redeem only once, that unbacked issuance depreciates notes, that backed issuance locks diamonds, and that huge numbers don't blow up the math.

The license is MIT, copyright heplus1s. Minecraft is a trademark of Mojang. This project is not affiliated with Mojang.
