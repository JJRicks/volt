# Standard-charger 99% stage (v9) evidence

`fit_stock_finish.py` fits estimator `ut5-stock-finish-v9` from data already published here:

- the five complete standard-charger charges (sessions 2, 3, 8, 9 and 10) in
  [`../fast-charge-taper-v7/sessions.csv`](../fast-charge-taper-v7/README.md), which fit the model;
- Scooter B in [`../stock-startup-v5/scooter-b/paired-input.csv`](../stock-startup-v5/README.md), another UT5 MAX
  on its own charger, which is held out entirely.

No new personal data is added.

```sh
python3 docs/validation/data/stock-finish-v9/fit_stock_finish.py           # fit, leave-one-out and offset results
python3 docs/validation/data/stock-finish-v9/fit_stock_finish.py --check   # also verify the Java constants (CI)
```

It needs Python 3.8+ and nothing else. Method, results and limits are in
[stock-finish-estimates.md](../../stock-finish-estimates.md).
