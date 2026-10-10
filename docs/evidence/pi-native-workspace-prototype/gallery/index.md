# Native workspace prototype — accepted originals (12)

Synthetic fixtures only. Not live Pi parity, physical-device acceptance or production adoption. Owner accepted fit and chose to proceed to D1 on this basis; the record is frozen evidence, see the [record README](../README.md).

Common 24-file source manifest SHA-256: `1a1a61e41d6da3c79354362abe62f1109c4694ba8c56883432affe8970aada0c`.

32 current JVM tests green; 13 UI tests green in each approved profile/theme. Runs (owner-approved reuse): `141744-final-source-short-light`, `141814-final-source-short-dark`, `141118-normal-light`, `141158-normal-dark`; app APK `3662c3ae…84f9`, test APK `259db677…65c6` — full values in [provenance.json](provenance.json) and the per-run XMLs under [../evidence/](../evidence/).

| Preview | Dimensions / density | Theme | SHA-256 |
|---|---|---|---|
| [roster](roster.png) | 320×640 @160 | light | `8c7f085fe110eab0594c026575c8528346bb6e4a82bf5cb1f9e22e5d7b2f0118` |
| [workspace-default](workspace-default.png) | 1080×2340 @440 | dark | `d135102652430ab958ef7d54b4a0429d1e127f63ceebbec917de02fb65eec974` |
| [ime-short-light](ime-short-light.png) | 320×640 @160 | light | `52db547b9bc82f33414638ca4e2c341bdfcc8bfcf3fa9433c1d2be6fb0445ca7` |
| [ime-normal-dark](ime-normal-dark.png) | 1080×2340 @440 | dark | `b34cb1fd5b3058ef857c4d253397214767121746bbd950a1d4b770524032cddd` |
| [thinking-expanded](thinking-expanded.png) | 1080×2340 @440 | light | `686ee0eb0560110b013678d70153aa6cea5cff06fa644603b753bef131a4c198` |
| [tool-expanded](tool-expanded.png) | 1080×2340 @440 | light | `c8f46fc57915e344fdc5ac9fdef458c2b4d6fd56b73dfb880589060d6bfc3eab` |
| [unavailable-capabilities](unavailable-capabilities.png) | 1080×2340 @440 | dark | `17500c97d20e64d4e15a710b7729712dafe90122df0cb722eec4b6d849ea939e` |
| [growth-during-jump](growth-during-jump.png) | 320×640 @160 | light | `b6e0cdc50b8e7217f97547e5546279ff9044f07a9fd49f928d25276b1a512ab5` |
| [manual-reading](manual-reading.png) | 320×640 @160 | dark | `556b080e315d179646938622b93f206fc2c832d78d2a3229fa4603c25636a849` |
| [readonly](readonly.png) | 320×640 @160 | light | `f39b6dcf6b6a5c56e76c011707045befb746ebaccb99477020edd2f591253a37` |
| [disconnected](disconnected.png) | 320×640 @160 | dark | `acf18c654c09ab98c69c1415dc4e8a2849a26d2e296288ce28a3ad0833c413df` |
| [stream-structured](stream-structured.png) | 1080×2340 @440 | light | `a6cc2e6afae1c87fb09207e5c38411d87f73bbf36ba693b1ad889ed6a7688ff7` |

Full per-image source/APK/run/XML/method/configuration provenance: [provenance.json](provenance.json).

Passive images use a truthful URL fallback when the native alt resolver returns null, observed in both static and streaming paths. No remote image fetching or link navigation.
