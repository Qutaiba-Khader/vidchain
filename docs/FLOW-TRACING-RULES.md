# Flow-tracing test: baseline, verdicts and the 5-attempt rule (T1.4)

After every task the flow-tracing test (`test-completeness`) runs on what the task changed, plus the
machine gates (Android lint with `app/lint-baseline-vidchain.xml`, the CI gates).

1. **Baseline.** Findings that already existed in the inherited code are frozen:
   - agent findings in `docs/baseline-findings.json` (fingerprint = rule + file);
   - lint findings in `app/lint-baseline-vidchain.xml`.

   Only NEW findings block a task.
2. **Touched files.** If a task changes a file that has a frozen finding, that finding is fixed in the same task
   (and its entry gets `"status": "fixed in Tn.m"`).
3. **Fix until clean.** Every new finding is fixed and the test re-run until it is clean.
4. **5 attempts (owner rule Q16).** If the same finding survives 5 fix attempts, it is a real blocker.
   - Record it in STATUS.md (NOW + LOG) and PLAN.md, and ask the owner.
   - Nothing is ever parked silently.
5. **Verdict ledger.** One row per finished task in `docs/verdict-ledger.jsonl`:
   `task, date, new_findings, fixed, attempts, verdict, commit` (and `carried_to` when a finding is handed to a later task
   by plan, never silently). `tools/ledger_check.py` validates it in CI.
6. **Lint.** New lint errors fail the build (`abortOnError`). New warnings are reviewed in the flow-tracing pass.
   Calendar-dependent checks (NewerVersionAvailable, GradleDependency, AndroidGradlePluginVersion) are disabled.
