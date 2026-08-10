# Sonic upstream provenance

This project uses Bill Cox's Sonic speech speed/pitch library rather than the previous project-local DSP implementation.

- Upstream: `waywardgeek/sonic`
- Upstream file: `master:Sonic.java`
- Upstream blob SHA verified on 2026-08-10: `3a2594009f45f9c432ab83bfb674cffb8f2f9d87`
- Local source: `app/src/main/java/sonic/Sonic.java`
- License: Apache License 2.0

The vendored Java source preserves the upstream Sonic algorithm and public API. Formatting/representation in this repository is not used as the provenance identity; the upstream blob SHA above is the source-of-truth reference. The Android/Kotlin integration lives separately in `SonicPcmProcessor.kt`, which only adapts PCM16 WAV input/output and maps the app's speed, pitch, quality and volume settings into Sonic.

For quality, the Java upstream API uses an integer `quality` setting. The app intentionally maps `Nhanh` to `0` and `Chính xác` to `1`. Upstream treats `quality == 0` as the downsampled/faster pitch-period search and non-zero quality as the full-resolution path, so `1` is the highest distinct quality behavior exposed by the current Java implementation.

The current Java source uses the upstream 12-point sinc FIR interpolation for resampling/pitch work. When refreshing Sonic, compare the current upstream `Sonic.java` blob and keep DSP changes out of the Kotlin adapter.
