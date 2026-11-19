# Results

Workload: `Workload[accounts=10000, transfers=100000, hotAccounts=20, hotPercent=20, duplicatePercent=2, seed=20261119]`

Machine: Intel(R) Xeon(R) Processor @ 2.10GHz, 4 cores, Linux amd64, JDK 21.0.13.0.3 (Ubuntu)

| Ledger | Threads | Faults | Transfers | Throughput/s | p50 µs | p99 µs | Rejected | Duplicates | Retries | Breaker opens | Outbox peak | Invariants |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| LOCKING | PLATFORM | NONE | 100,000 | 248,861 | 183 | 2,008 | 853 | 2,006 | 0 | 0 | 0 | PASS |
| LOCKING | PLATFORM | FLAKY | 100,000 | 215,823 | 197 | 2,680 | 825 | 2,006 | 16,739 | 0 | 3 | PASS |
| LOCKING | PLATFORM | OUTAGE | 100,000 | 172,734 | 228 | 2,840 | 808 | 2,006 | 54 | 1 | 90,036 | PASS |
| LOCKING | VIRTUAL | NONE | 100,000 | 181,706 | 340 | 800 | 825 | 2,006 | 0 | 0 | 0 | PASS |
| LOCKING | VIRTUAL | FLAKY | 100,000 | 225,608 | 237 | 735 | 814 | 2,006 | 16,763 | 0 | 3 | PASS |
| LOCKING | VIRTUAL | OUTAGE | 100,000 | 165,981 | 368 | 1,342 | 832 | 2,006 | 52 | 1 | 86,480 | PASS |
| CAS | PLATFORM | NONE | 100,000 | 54,027 | 878 | 9,488 | 820 | 2,006 | 0 | 0 | 0 | PASS |
| CAS | PLATFORM | FLAKY | 100,000 | 49,865 | 886 | 8,784 | 830 | 2,006 | 16,777 | 0 | 3 | PASS |
| CAS | PLATFORM | OUTAGE | 100,000 | 59,859 | 829 | 6,282 | 813 | 2,006 | 52 | 1 | 94,167 | PASS |
| CAS | VIRTUAL | NONE | 100,000 | 55,465 | 1,011 | 3,779 | 826 | 2,006 | 0 | 0 | 0 | PASS |
| CAS | VIRTUAL | FLAKY | 100,000 | 51,813 | 1,016 | 3,507 | 821 | 2,006 | 16,762 | 0 | 3 | PASS |
| CAS | VIRTUAL | OUTAGE | 100,000 | 54,297 | 1,046 | 3,409 | 802 | 2,006 | 52 | 1 | 95,181 | PASS |
