# Third-party notices

## libmobi HUFF/CDIC reference

The bounded Kotlin HUFF/CDIC implementation in
`app/src/main/java/vn/nghetruyen/app/importers/HuffCdicDecoder.kt` was developed
with the public libmobi parser and decompressor as a format and algorithm reference.

- Project: libmobi
- Copyright: Bartek Fabiszewski and contributors
- License: GNU Lesser General Public License, version 3 or later
- License text: `LICENSES/LGPL-3.0.txt`

The application implementation is Kotlin code with application-specific bounds,
validation, recursion limits, and output limits.

## java-lame 1.0.0

- Artifact: `co.ntbl:lame:1.0.0`
- Purpose: pure-Java LAME MP3 encoding for audiobook export.
- License: GNU Lesser General Public License (LGPL), as published by the project.
- The app calls the low-level `co.ntbl.lame.mp3` API and does not use the desktop Java Sound wrappers.
## LuaJ JSE 3.0.1

- Artifact: `org.luaj:luaj-jse:3.0.1`
- Purpose: sandboxed Lua 5.x-compatible execution for Native Source API 2 import and pure-Lua hooks.
- License: MIT License, as declared by the Maven Central POM.
- The application disables `luajava`, unrestricted module loading, filesystem/process APIs and Lua bytecode for imported extensions.

