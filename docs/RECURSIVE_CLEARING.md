# Recursive collateral clearing

## Accounting and migration

System terminal currencies are `diamond`, `copper`, and `note`. A personal currency may anchor a system currency or `personal:<UUID>`. A valid chain has at most 32 personal-currency legs and terminates in one of those system currencies. Missing currencies, cycles, paused/replacing reserves, bankruptcy, zero backing and excessive depth fail before mutation.

`circulation` remains the historical outstanding issued total (including recoverable inactive notes). `personalActiveExact` is that total less the non-spendable recovery pool. Nested pledged holdings remain part of the underlying currency's outstanding claims. M0 is outstanding paper; M1/M2 display uses the exact active total, not issuer balance and not collateral value added twice.

The additive `recursiveClearing` save object holds fractional reserves, recovery pools, policy targets, system assets, receipts and reserve-switch pause state. Absent fields on old saves start empty. Existing integer reserves and holdings retain their ownership; migration does not manufacture inventory or enable automatic policy interventions. Historical pre-clearing `drawn` debt remains readable.

## Incoming system payment

For each leg, use actual reserve / active supply, never target rates or speculative trade flow. Deduct actual reserve and move the paid private currency into its recovery pool. Repeat for the actual amount of nested collateral until reaching a base currency. Book that base asset to the destination system account. Round outgoing reserve amounts down to four decimals; reject below-precision payments.

Physical reserves deposited from savings already remain inside `depositVault`. Clearing changes ownership inside that custody pool and **must not add the same physical inventory again**. Central-note reserve funding removed public liability; releasing it adds back only the actual transferred central notes. The central-note-equivalent value of copper/diamond is display valuation, not additional spendable notes.

## New reverse system payment

`quotePersonalFromSystem` and `settlePersonalFromSystem` are new trades with new references, not refunds of earlier purchases. Every leg must have sufficient recovered currency. The system must own the real terminal assets; top-level private currency is credited to the recipient only after all validation. Each leg uses the current backing ratio and rounds required backing up to four decimals. An all-recovered currency with zero active supply has no verifiable current ratio and is not offered for reverse payment. No guessed historical/1:1 rate is used.

`personalSystemCapacity` intersects all recovery pools with actual terminal assets at four-decimal precision. It does not treat the target rate or appraised assets as spendable cash.

## Transactions and recovery

All core operations validate before mutation and run on the server thread. The runtime must execute them on a copy and durably save before publishing the new live ledger or acknowledging payment. Core success is not itself a filesystem durability guarantee.

References are bound to exact payer/payee, currency, amount, direction and destination. Identical successful replay is idempotent; conflicts and reuse of reversed references are rejected. Receipts retain every leg and terminal amount. Recent five-event UI history is a view, not receipt deletion.

`rollbackShopPayment` compensates only the named operation. It never restores a stale whole-world snapshot. It refuses if required funds were spent, recovered notes were released, or collateral anchors changed. External item delivery must be journaled by the shop; ambiguous delivery must not be automatically retried.

`redeemPersonalToSavings` follows the same chain and credits actual terminal-currency savings. Old direct physical redemption is refused with `route_to_savings`; paper must first be deposited into the personal-currency account. This prevents simultaneous savings credit and item delivery, and blocks obsolete fixed-par overpayment.

## Policy and reserve replacement

Reserve replacement returns the remaining designated collateral to issuer-owned savings/holdings, clears the old lock and pauses pricing/settlement until a real deposit arrives. Deposits cannot consume listed private holdings. Pauses and recovery pools survive restart.

Policy values are anchor-asset units per private-currency unit. Floating recovery return needs no top-up. Pegged return uses the current support ratio constrained to its configured band; fixed return uses its target. Funding uses only available unlisted designated savings; a shortfall leaves actual support below target, never creates assets.

Automatic rebalancing is opt-in via an explicitly saved policy. Below the band it first uses designated savings, then recovers only the issuer's unlisted holdings; public balances are never seized. Above the band it releases recovered notes first, then whole-unit new issuance. A no-op returns `no_change`, allowing the runtime to avoid unnecessary saves.

The historical bankruptcy routine cannot safely determine ownership for nested/fractional/recovered collateral and refuses those cases with `clearing_required`. It must not silently reuse the old integer forfeiture calculation for the new model.

## Verification

Pure Java core and snapshot compile independently of Minecraft. `RecursiveClearingTest` invokes the executable real-ledger regression plus tests for listed-collateral protection and old-save no-auto-policy migration. It covers two-leg incoming and new reverse clearing, custody conservation, idempotency/conflicts, reloading, exact compensation, short recovery, no-current-rate rejection, central liability, fixed policy/public-holder protection, switching pauses and fractional collateral.
