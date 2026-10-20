# Cell City

A cel-shaded isometric city builder for Android. Zone neighbourhoods, wire them to a power plant, watch homes and shops grow, and keep the budget (and the citizens) happy.

Everything is drawn with a custom `Canvas` renderer: flat colour blocks, thick ink outlines, two-tone shading and animated water — no game engine and no third-party art dependencies.

## Gameplay

You start with **$15,000** and a 24×24 island split by a river.

### Tools

| Tool | Cost | Notes |
| --- | --- | --- |
| Road | $15 | Bridges over water cost $90 |
| Residential zone | $40 | Homes grow next to a connected road with power |
| Commercial zone | $50 | Shops create jobs for residents |
| Industrial zone | $60 | More jobs and taxes, but pollution |
| Park | $100 | Boosts happiness |
| Power plant | $2,500 | Powers up to 55 connected road/building tiles |
| Bulldoze | free | Clears a tile and refunds 25% |
| Pan | free | One-finger camera drag (pinch works any time) |

Tap once to build a tile, or drag to paint roads and zones in a line. Zones display an **R / C / I** marker until a building grows, a red **!** when they have no road, and an amber **bolt** when they have no power.

### Simulation

Each in-game day (about 1.15 s at 1× speed) the engine:

1. Recomputes road access for every tile.
2. Flood-fills power from each plant through roads, buildings and parks, up to the plant's capacity. Empty grass and water block the flow.
3. Scores **R / C / I demand** against existing housing and job capacity.
4. Grows new buildings on powered, road-connected zoned tiles; occupied buildings upgrade toward level 3.
5. Moves residents and workers in, cuts off neglected buildings, and adjusts happiness from power coverage, parks, pollution, unemployment and debt.
6. Collects taxes ($0.16/resident, $0.10/job per day) and pays road, park, plant and building upkeep.
7. Emits a monthly finance report.

A building left without power or road access empties out and is eventually abandoned. The date, population, jobs, happiness, treasury and live demand bars are always visible in the top HUD.

The city saves automatically every month and whenever the app is paused; **NEW** starts over after a confirmation.

## Controls

- **Tap / drag on the map** — use the selected tool.
- **Two fingers** — pinch to zoom and drag to pan, even while building.
- **Speed button** (top right) — pause, 1×, 2×, 3×.
- **Toolbar** — pick a tool; the hint line explains the next useful step.

## Tech stack

- Kotlin 1.9, Android minSdk 24 / targetSdk 34
- 100% custom 2D `Canvas` rendering with an isometric camera
- Pure-Kotlin simulation, decoupled from Android so it is unit-testable
- No external runtime dependencies beyond the Kotlin stdlib
- JUnit 4 for the engine tests

## Project structure

```
app/src/main/java/com/cellcity/
├── MainActivity.kt          # Immersive fullscreen host
└── game/
    ├── Config.kt            # Tuning: costs, capacities, growth, power, colours
    ├── Models.kt            # Terrain, ZoneType, Tool, Tile, CityEvent
    ├── CityEngine.kt        # Terrain generation, build rules, power, growth, economy, save/load
    ├── IsoArt.kt            # IsoCamera + cel-shaded renderer (tiles, buildings, traffic, sea)
    └── GameView.kt          # Loop, HUD, toolbar, gestures, persistence
app/src/test/java/com/cellcity/game/CityEngineTest.kt
```

## Build

Requires the Android SDK (API 34) and JDK 17 for Gradle.

```bash
./gradlew testDebugUnitTest   # engine tests
./gradlew assembleDebug       # debug APK
./gradlew installDebug        # install on a connected device
```

Set `sdk.dir` in `local.properties` (or `ANDROID_HOME`) before building.
# cellCity
