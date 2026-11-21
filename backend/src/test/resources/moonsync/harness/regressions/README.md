# Differential-harness regression cases

Differences the Moon+ differential harness found against readsync, saved with `just harness run --save`
(see `tools/moon-harness/README.md`). One folder per comparison; each case is a `<slug>.json` holding the input,
readsync's output as `expected` and the readsync revision, plus a copy of the input file when the input was a
file. `MoonHarnessRegressionTest` runs every case in the normal test suite.

A saved case fails until the Java side matches readsync. If the difference turns out to be a deliberate deviation
(spec "Where this deliberately differs from readsync"), declare it in the comparison (`exemption` or
`ignoredFields`) and delete the case instead. Check what an input file contains before committing it: corpus
inputs can be books that aren't free to redistribute.
