# Localization (Russian)

Orbit's interface is localized with ordinary Android resources. English is the fallback and the
source of truth; Russian is chosen automatically when the phone's language is Russian.

- English: `app/src/main/res/values/strings*.xml`; Russian: `app/src/main/res/values-ru/strings*.xml`.
  Every translatable name exists in both folders (the checker fails otherwise).
- Only text the user sees is a resource. Prompts sent to AI models, JSON/preference/database keys,
  intent actions, ids, log lines, URLs and brand or model names stay in code, unchanged.
- Stored or compared values (statuses, kinds, routine and command ids) are never translated; the
  words shown for them are looked up where they are drawn.
- Counts use `<plurals>` (Russian needs `one`, `few`, `many`, `other`). Messages with variable parts
  are one format string with positional arguments (`%1$s`), never concatenated fragments.
- The Lelo easter egg is deliberately not in resources (a test enforces it).

## Checks

```bash
python3 tools/validate_localization.py --check-resources-only   # XML, en/ru pairs, format arguments, plurals
python3 tools/validate_localization.py --base origin/main       # the above + legacy token check of changed Java
python3 tools/audit_localization_diff.py --base origin/main     # lines changed beyond "literal -> resource lookup"
./gradlew :app:testDebugUnitTest                                # source-reading tests expand R.string via LocalizedSource
```

Source-reading unit tests used to look for English literals in `.java` files. Those literals now live
in `values/`, so tests read sources through `LocalizedSource.expand`, which puts the English text
back in place of each `R.string.name` before asserting.

## Adding a string

1. Add `<string name="area_meaning">English</string>` to a `values/strings_*.xml` file and the
   Russian text to the same file name in `values-ru/`.
2. Use `getString(R.string.area_meaning)` (or `getQuantityString` for counts).
3. Run the checks above.
