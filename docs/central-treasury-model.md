# Central treasury and custody

`centralTreasury` is a new, independently owned savings ledger. Existing snapshots that lack it load it as zero. Existing player balances, custody, retained fees, issuance reserves and collateral are not reassigned or rounded away.

- Explicit administrator contributions debit the same administrator's savings and move the matching physical custody asset and commodity claim into central ownership. Physical contributions/withdrawals require whole units; existing fractional balances remain untouched.
- Withdrawals move treasury assets back to the administrator's savings and custody. Note transfers change ownership, not issued liability.
- Reserve transfers now move only treasury assets. The old command/action identifiers remain accepted for compatibility, but no longer authorize spending customer custody.
- Commodity quote inputs combine custody and treasury exactly once. Moving ownership between them is rate-neutral. The existing gap formula itself remains unchanged.
- Selling already issued central notes transfers the physical payment from custody to treasury. It does not create physical inventory. Central note inventory calculations and retirement exclude treasury-owned notes.

## Monetary aggregates

`centralM0Exact` and `centralM1Exact` preserve fractional balances. M1 includes M0 and must not be added to it. `broadMoneyComponentsExact` returns a native-unit vector; its different currencies must not simply be summed. Nested personal collateral is excluded from the independently circulating inner currency. Recovered personal currency remains outside circulation.

Existing M2 is a display valuation using current FX. It is not an independent input suitable for determining those same FX prices. Treasury is central-owned, separately reported, not public circulating M1.

Coverage is registered bank accounts, valid tracked paper, personal currency and integrated system accounts. No complete world inventory/blocks/chests/unloaded chunks scan is performed. This change does not claim to implement whole-world physical M0/M1/M2 or a new monetary-policy formula. Target-reserve adjustment remains unavailable until its accounting/price model is specified.

## Verification

Local pure-Java ledger and UI-helper tests cover treasury conservation, no access to customer custody, zero-value legacy migration, persistence, exact fractional notes, refusal without mutation, quote/requote exchange, recursive collateral, land settlement and reversal, and UI data labels.

Full Fabric compilation and live client/server behavior must still be verified in CI / Minecraft. In the available local environment Fabric Loom 1.6.12 cannot resolve in offline mode; the pure-Java tests are not a replacement for that build or visual testing.
