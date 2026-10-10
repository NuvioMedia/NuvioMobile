# Rating logos

The original PNGs live in `composeApp/src/commonMain/composeResources/drawable`.
The six density-qualified drawable directories contain Lanczos-resampled copies
for the 16dp rating badges, 24dp certified badges, and 30dp-wide IMDb badge.
This avoids reducing large images with bilinear filtering at draw time.

`rating_tmdb_badge` is separate from `rating_tmdb`, which is also displayed at
larger sizes in Integration settings. Keep the original PNGs when regenerating.

To regenerate, run `node assets/ratings/generate-density-variants.cjs` with
Sharp 0.35.4 available to Node. An absolute path to a separate Sharp installation
can be passed as the first argument. Sharp is only used to prepare the assets,
not by the app or its Gradle build.
