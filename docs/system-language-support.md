# Android system-language support

BIPSS uses Android resource locales and the current system configuration. There is no in-app language preference or language selector. With no Android per-app override, changing the device language recreates the interface in that language. Unsupported languages fall back to English. Android's own per-app language override, if explicitly set by the user, takes precedence over the system language.

The bundled packs cover the 22 scheduled Indian languages: Assamese, Bengali, Bodo, Dogri, Gujarati, Hindi, Kannada, Kashmiri, Konkani, Maithili, Malayalam, Manipuri (Meitei Mayek), Marathi, Nepali, Odia, Punjabi, Sanskrit, Santali (Ol Chiki), Sindhi, Tamil, Telugu and Urdu. See `tools/localization/coverage.json` for actual completion counts. Do not infer completeness from this language list until every count equals its total.

App-owned interface copy, permissions explanations, accessibility labels, notification channels, score labels, insight methods and common deterministic Coach replies use bundled resources. Dates follow the device locale. Numeric keyboards accept Indic digits and local decimal separators; malformed grouping must not silently multiply a quantity. Canonical identifiers, routes, network queries, date parsing protocols and stored calculation metadata stay stable across languages.

Names, user messages, meal/activity/habit names, cycle notes, hospital names and external advisories retain their original content. The deterministic Coach recognizes bundled common phrases and selected natural variants; it is not a general multilingual language model. Arbitrary downloaded-model answers and external advisory text are not translated by a runtime cloud service.

## Build-time translation

`resources.py` generates Android resources and template assets from the frozen catalog, translation files and reviewed overrides. It uses no network. `coverage.json` tracks translated entries. Keep the original English catalog stable; supplement it deliberately when adding visible copy. Do not rerun the one-time migration against already migrated source.

`gemini_translate.py` generates missing app-copy translations using a user-authorized build-time credential in the ignored `build/localization-key.txt`. Never put a credential in source, resources, assets or the APK. The optional `--refine` mode reviews wearable terms. Both modes validate format placeholders and checkpoint results. Delete the temporary credential after generation. Generated translations need native-speaker editorial review before a public release, especially less widely supported languages. Automated coverage measures presence and format integrity, not linguistic or clinical validation.

## Verification

Unit checks cover literal/reordered substitution, Indic numeric input, malformed grouping and phrase matching. Android instrumentation checks requested language labels, cached enum labels, literal names, saved generated values, English fallback, Urdu layout direction and common Coach phrasing/negation. Actual device-language switching is also checked on the emulator. These checks do not validate all translated sentences or exercise SMS delivery, Drive authorization, band firmware or medical model accuracy.

## Completed build verification

All 22 packs contain all 2,213 frozen catalog entries (including retained technical extraction entries that are not used as translated UI). The final debug and minified release builds passed; 262 JVM tests and four targeted Android locale tests passed. The debug APK was installed on the emulator. System-language changes were exercised in Hindi, Tamil and Urdu; user profile names are excluded from translation. The temporary build credential was deleted and no credential file or translation tooling is packaged in the APK. The release APK is unsigned; the debug APK is installable for testing.
