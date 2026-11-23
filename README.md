
# OfflineDraw - offline SD-Turbo text-to-image for Android

Engine: exact replay of the `word-image-litert-v1` plan published in
https://huggingface.co/frankdu/SD-Turbo-LiteRT (24 LiteRT graphs, 512x512, 1 step).

* CLIP byte-BPE tokenizer re-implemented in Java from `tokenizer0/bpe.json` (7 parity fixtures checked at runtime).
* Noise = one `java.util.Random` gaussian stream over all `normal` externals in manifest order (same as `java_noise.py`).
* Scheduler / latent transforms are *not* re-implemented: `initial`, `scale0`, `step0` graphs do it.
* Every graph is loaded, run through its `serving_default` signature, then closed; tensors are freed by refcount.

## What is inside the APK
`manifest.json`, `tokenizer0/bpe.json`, `constants/t17.bin`, licences, `reference.png`
and the three tiny graphs (`initial`, `scale0`, `step0`) - about 2.6 MB of assets.

## What is not inside the APK
The 21 big fp32 graphs: **5,025,450,192 bytes = 4.68 GiB**.
`apksigner` breaks above 2 GiB, GitHub release assets are capped at 2 GiB, and git blobs at 100 MB,
so a single 5 GB APK cannot be built, signed, delivered or installed.

Three ways to get them onto the phone (all of them leave the app fully offline afterwards):

1. **Download pack** button (resumable, SHA-256 verified against `manifest.json`).
2. **Offline sideload**: copy `graphs/*.tflite` to `/sdcard/OfflineDraw/bundle/graphs/`
   (grant "All-files access" in the app on Android 11+). Nothing is copied twice - the app mmaps them in place.
3. **Import folder**: pick any folder with the system picker; files are copied + hash-verified.

## Build
`.github/workflows/build.yml` builds with Gradle 8.7 / AGP 8.5.2 / JDK 17, then
`keytool` generates a throwaway key and `zipalign` + `apksigner` sign and verify the APK.
No repository secrets are used. Output: release tag `apk`, asset `OfflineDraw-signed.apk`.

Model weights keep the Stability AI Community License (see LICENSE.md).
