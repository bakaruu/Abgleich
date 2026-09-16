## What changes

<!-- One or two sentences. -->

## Bug catalogue

<!-- Which bug IDs (B01–B45) does this change touch? Each one needs a test named with its ID. -->

| Bug ID | Test |
|--------|------|
|        |      |

## Checklist

- [ ] Domain and application still have no framework imports (ArchUnit is green)
- [ ] No money outside `Money`, no `now()` outside the injected `Clock`
- [ ] No real bank data, IBANs or names in fixtures or logs
- [ ] New constraints are in a Flyway migration, never edited in an applied one
