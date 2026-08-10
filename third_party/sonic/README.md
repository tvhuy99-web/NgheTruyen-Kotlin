# Sonic upstream provenance

This project uses Bill Cox's Sonic speech speed/pitch library rather than the previous project-local DSP implementation.

- Upstream: `waywardgeek/sonic`
- Upstream file: `Sonic.java`
- Upstream blob SHA: `3a2594009f45f9c432ab83bfb674cffb8f2f9d87`
- Local source: `app/src/main/java/sonic/Sonic.java`
- License: Apache License 2.0

The vendored Java source preserves the upstream Sonic algorithm and public API. The Android/Kotlin integration lives separately in `SonicPcmProcessor.kt`, which only adapts PCM16 WAV input/output and maps the app's speed, pitch, quality and volume settings into Sonic.

When refreshing Sonic, compare the upstream `Sonic.java` blob and keep DSP changes out of the Kotlin adapter.
