# Native Usenet engine provenance

NuvioTV's native engine adapts the shared progressive article/flight model,
NZB planning, lazy archive strategy, RAR volume naming and real NNTP benchmark
harness from [AltMount](https://github.com/kipsilabs/altmount), commit
`3fd79fad97772d193b6916e316753b9a267c6520` (MIT, Javier Blanco).
The original license is in `licenses/AltMount-MIT.txt` and is packaged in the APK.

Dependencies and adaptations:

| Source | Version | Use / local changes |
| --- | --- | --- |
| javi11/nntppool | v4.23.0, MIT | Vendored pipelined NNTP pool. Commit buffered replies before writing decoded bytes; suppress callbacks for abandoned attempts; join the reader before reusing a connection's slab. Regression test included. |
| javi11/nzbparser | v0.5.5, MIT | Subject parsing; XML handling adapted to decode one NZB file at a time and preserve addon file indices. |
| javi11/rardecode | v2.2.4, BSD | RAR4/RAR5 header fields, stored-data extent mapping, header/file encryption records and the RAR4/RAR5 AES key schedules adapted from archive15.go, archive50.go and archive_info.go. No decompression code is included in the engine. |
| mnightingale/rapidyenc | 7aafef1eaf1c, MIT | Chunked yEnc wrapper. Android link targets added; upstream desktop archives retained for host tests. |
| animetosho/rapidyenc | 480bd7b5896f8b3edecc721d23f1384d767ffe2f, public domain / CC0 | Native SIMD kernels compiled from source for every APK ABI. CRC component disabled; Go CRC32 validates articles. |
| dlclark/regexp2 | v2.7.2, MIT | ECMAScript addon file selectors, with bounded matching time, backtracking stack and retained filename buffers. |
| javi11/sevenzip | ca961b7f1239 (fork of bodgit/sevenzip), BSD | 7z directory parsing only, including LZMA-compressed and AES-encrypted headers. Entry payloads are mapped and AES-decrypted by the engine, never extracted through the library. Linked dependencies: andybalholm/brotli (MIT), bodgit/plumbing and bodgit/windows (BSD), hashicorp/golang-lru (MPL-2.0), klauspost/compress (BSD), pierrec/lz4 (BSD), spf13/afero (Apache-2.0), ulikunitz/xz (BSD), go4.org (Apache-2.0). |

AltMount's stored archive fixtures and NNTP test server are included under its
MIT license. `third_party` retains upstream source notices. `licenses` is an APK
assets source directory; Android packages these notices with the engine.

NuvioTV base checkout: `23d1fe478e380860dae3eb41c8770533361a0cc5`.

## Repository extraction

This repository was extracted from `native/usenet` in
[haaihond/NuvioTV](https://github.com/haaihond/NuvioTV), branch `full-nntp`,
at commit `f222d044f69967764804236a65c6ec7d877b377d`. The source snapshot
was verified byte-for-byte before the standalone repository adjustments.

The filtered history retains engine changes, the project license, the native
asset download tests, and the engine CI/publishing workflows. Original authors
and commit messages are retained; commit IDs change and original commit
signatures cannot survive the extraction. Original pull request discussions
remain in NuvioTV. The NuvioTV Android application is not included.

The inherited project license is included in `LICENSE` (GNU GPL version 3).
Upstream components retain their own licenses and notices as listed above.
