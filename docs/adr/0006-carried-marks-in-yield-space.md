# 0006. Carry recent trades in yield space with partial rate pass-through

Status: Accepted

## Context

The first version carried a bond's last z-spread forward, which passes the full change in the G-sec curve into the bond's yield. In the backtest from May 2026 it was worse than repeating the last traded yield. Regressing changes in a bond's yield between consecutive trades on the change in the G-sec par yield at its tenor gave a slope of 0.531 (correlation 0.185) for April to June 2026.

## Decision

Carry the last traded yield plus 25% of the change in the risk-free par yield since the trade, and blend towards the model yield with a 20 business day half-life. The value 0.25 was set from the April to June sample, below the estimated slope to allow for its instability, and was not changed after evaluating July to October.

## Consequences

- Out of sample (July to October 2026), the carried mark has about the same mean absolute error as the last traded yield (15.8 bp against 15.9 bp), a slightly higher median (7.4 bp against 7.0 bp), and is better than carrying the spread (17.2 bp mean).
- The slope fell to 0.165 in the test period. The parameter should be re-estimated periodically on a rolling window; this is not automated.
