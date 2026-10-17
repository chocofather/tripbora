# Airport master

`airports.json` is the single catalogue used by the TravelPlan flight autocomplete.
It is bundled with the application; visitors do not call an external airport API.

- Source: https://github.com/davidmegginson/ourairports-data (`airports.csv`).
- License: Unlicense / public domain (upstream `LICENSE`).
- Snapshot date: see `retrievedAt` in the JSON.
- Coverage: non-closed airports with a three-letter IATA code and scheduled service,
  plus non-closed Korean airports with a three-letter IATA code.
- `name.en`, `city.en` and IATA originate in the source CSV. Country labels use
  Unicode CLDR via `Intl.DisplayNames`. Korean names/cities/aliases supplement
  common travel airports; entries without a Korean translation fall back to English.
- `localizationSources` records supporting airport operator sources. A localized
  name is a display/search label, not a claim about current routes or operation.

To refresh, download the source CSV and apply the coverage filter above. Map
`iata_code`, `name`, `municipality`, `iso_country` and comma-separated `keywords`
to the existing JSON structure. Match by IATA and preserve reviewed Korean names,
city translations and aliases. Update the snapshot date, check IATA uniqueness,
and run `travel-plan-airport-autocomplete.test.cjs`. Additional translations belong
in the locale maps and aliases here, not in HTML or application JavaScript.

Airport selections are plain `공항명 (IATA)` strings saved through the existing
departure/arrival fields. Manual text remains valid; this catalogue does not add
an airport foreign key or change existing stored schedules.
