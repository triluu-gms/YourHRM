# ADR-0034: Money, rounding, dates and time-zone conventions

- **Status:** Proposed
- **Date:** 2026-10-16
- **Deciders:** Data architect, Payroll lead, Solution architect
- **Related:** NFR-PAY-002 (fixed-point, VND-exact determinism), NFR-PAY-011, NFR-ATT-005, NFR-CORE-011/015, H-07 (USD contracts); ADR-0009; [DESIGN.md](../../../database/postgresql/DESIGN.md) §4.6–4.7; DDL domains `sys.money_amount`, `sys.rate`, `sys.quantity`, `sys.month_date`

## Context

Payroll must be exact to the VND and deterministic. VND has no minor unit, but expat contracts may be in USD, and SI rates such as 8 % or 1.5 % and FX rates need exact decimals. Attendance spans midnight shifts and devices in several time zones, while work dates and effective dates are local business dates.

## Decision

1. **Money:** `numeric(19,4)` (`sys.money_amount`) plus `currency_code char(3)` on the row or its header. Rates use `numeric(12,8)` (`sys.rate`); quantities (hours, days) use `numeric(12,4)`. Floating-point types are forbidden for business values.
2. **Rounding is configuration:** each salary component has `rounding_mode` (`HALF_UP/DOWN/UP/HALF_EVEN`) and `rounding_unit` (1/10/100/1000). Statutory defaults come from the legal pack (`ROUNDING.*`) `[verify-legal]`. Payable and declared values are stored **rounded**; result lines also keep `amount_unrounded numeric(24,8)`.
3. **Instants** are `timestamptz`, and sessions and servers run in UTC. **Business dates** are `date` in the local calendar of the location or LE. Year-months are the first day of the month (`sys.month_date`). Shift times are `time` plus `crosses_midnight`. Punches keep `punch_at` (UTC), the device `local_ts` and `tz_name`.
4. **Ranges** are half-open `[from, to)` for effective dating (ADR-0024). Inclusive business end dates (contract end, termination date) are exposed as such and converted in generated ranges.
5. Text is UTF-8 with an ICU default collation; an ICU `vi-VN` collation is used for sorting names. Accent-insensitive search uses `sys.search_key()` (`unaccent` + `đ→d`) with trigram indexes.

## Alternatives considered

| Option | Why not |
|---|---|
| `bigint` VND amounts | Cannot represent USD cents or intermediate precision; a second representation would be needed for FX. |
| PostgreSQL `money` type | Locale-dependent and a fixed 2-decimal scale; not portable to Java `BigDecimal`. |
| `timestamp without time zone` for punches | Ambiguous across devices and DST-observing countries (expat travel); hard to normalise. |
| Hard-coded rounding per component type | Rounding rules vary by tenant policy and statutory form; NFR-PAY-007 requires change without release. |

## Consequences

- \+ Exact, portable arithmetic (`BigDecimal` ↔ `numeric`) and an explicit rounding audit trail.
- − `numeric` arithmetic in SQL aggregates is slower than integers. This is negligible relative to I/O at our volumes.
- − Developers must never derive business dates from `now()` without a zone; a lint rule and code review enforce this.
