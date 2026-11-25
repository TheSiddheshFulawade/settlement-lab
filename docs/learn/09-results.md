# 09 · README and the committed results (L-09)

## What it is, in plain words

`results/results.md` and `results/results.json` are one real run of the matrix, committed with the
machine named (4-core cloud container, Intel Xeon 2.10 GHz, JDK 21.0.13). The README's Results
section reads them and says what they show and what they do not.

- **Locking beat CAS by 2.9x to 4.6x.** Most likely cause: each CAS write copies the whole 10,000-entry
  balance array (80 KB), and a lost swap throws the copy away. Locks touch two accounts only.
- **Platform vs virtual: no clear throughput winner; virtual had the lower p99 in every pair** and a
  somewhat higher p50. The work is short and CPU-bound, so there is little blocking to hide.
- **The largest fault cost was 30.6 % (OUTAGE, locking on platform threads), not a collapse**, because GL publishing runs off
  the worker threads; every posting still reached the GL exactly once.
- **Honesty rules:** one run, one machine, JDK 21 not 25; say "most likely" for causes the run did
  not isolate; never present lab numbers as production numbers.

## Interview questions

1. **Why did CAS lose, and when might it win?**
   Its write cost grows with the table (a full copy per transfer) and with contention (failed swaps
   redo the copy). With a small table, few writers and many readers, its free consistent snapshots
   and no lock overhead could win. This run did not test that case.
2. **Virtual threads did not raise throughput. Does that mean they are useless here?**
   No: they help when threads block (I/O, waiting). This work is CPU-bound and capped at the same N,
   so there is little to gain. Their lower p99 in this run is worth a repeat on more cores.
3. **How do you know the numbers are not just noise?**
   I do not fully: it is one run. The large effect (locking vs CAS) is consistent across all six
   pairs; the small ones (platform vs virtual throughput) are not, so I report only the direction.
4. **Why commit results at all instead of only the code?**
   So the write-up's claims can be checked against the exact output, machine and workload, and so a
   later run (JDK 25, more cores) can be compared with it.
5. **What would you measure next?**
   The same matrix on JDK 25 and on more cores; a CAS run with a small account table; repeated runs
   with confidence intervals; and the CAS `conflicts()` count, to confirm the copy-cost explanation.

<!-- TODO-ME: run `./mvnw -q compile exec:java` on your laptop with JDK 25, save the output as
     results/results-jdk25.md, and add one paragraph to the README's Results comparing it with the
     JDK 21 run (locking vs CAS ratio, platform vs virtual p99). Check it: every cell still PASSes,
     and your paragraph names your machine (CPU, cores, JDK). -->
