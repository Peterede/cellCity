# Cell City

A cel-shaded isometric city builder for Android. Zone neighbourhoods, wire them to power and water, connect them with roads, rail and an airport, staff the police, fire and health services, watch homes and shops grow, and keep the budget (and the citizens) happy.

Everything is drawn with a custom `Canvas` renderer: flat colour blocks, thick ink outlines, two-tone shading and animated water — no game engine and no third-party art dependencies.

## Gameplay

You start with **$15,000** and a 24×24 island split by a river.

### Tools

| Tool | Cost | Notes |
| --- | --- | --- |
| Road | $15 | Bridges over water cost $90 |
| Highway | $120 | Wide four-lane road: counts as road access, moves traffic faster and draws trade; $300 over water |
| Rail line | $40 | Two or more connected tiles form a line that speeds up growth beside it |
| Train station | $700 | Must sit beside a rail line; spreads transit access 5 tiles from its door |
| Residential zone | $40 | Homes grow next to a connected road with power and water |
| Commercial zone | $50 | Shops create jobs for residents |
| Industrial zone | $60 | More jobs and taxes, but pollution |
| Park | $100 | Boosts happiness |
| Power plant | $2,500 | Powers up to 55 connected road/building tiles |
| Water tower | $500 | Pipes water to every built tile within 6 tiles |
| Water pipe | $12 | Buried main: runs under roads, zones and buildings; $70 under the river |
| Power cable | $10 | Buried cable: carries the grid across open ground; $50 under the river |
| Police station | $900 | Covers 7 tiles, cuts crime and lifts happiness |
| Fire station | $800 | Covers 6 tiles and protects the neighbourhood |
| Hospital | $1,200 | Covers 7 tiles and keeps citizens healthy |
| Airport | $6,000 | Takes a clear 2x2 block beside a road, hires 90 staff and boosts shops |
| Bulldoze | free | Clears a tile or a whole 2x2 block and refunds 25% |
| Pan | free | One-finger camera drag (pinch works any time) |

Tap once to build a tile, or drag to paint roads, rail and zones in a line. Utilities, services and airports always snap to a single tap. Zones display an **R / C / I** marker until a building grows, plus badges for a missing road (red **!**), power (amber **bolt**) and water (blue **droplet**).

### Utilities, layers and transit

- A **water tower** already waters everything within 6 tiles. **Water pipes** extend that reach: lay a main out from a tower and every lot beside a live pipe gets water, however far from the tower it sits.
- **Power cables** do the same job for the grid: power only travels through roads, buildings and parks, so a cable lets it cross open grass and water and reach a district that roads do not connect. It also fits under occupied tiles.
- Pipes and cables are layers, not zones: they share the tile with roads, zones, stations and buildings, can cross the river at higher prices, and take no surface space. Select the matching tool to lift that layer above the buildings. Bulldozing a tile pulls up every layer under it along with whatever sits on top.
- A **train station** must be built beside the rail line. It counts as transit access itself and pushes access out to every lot within 5 tiles, so a single station can serve a whole neighbourhood instead of only the blocks touching the track.
- A **highway** is a four-lane road. Lots beside it get road access exactly like a normal street, cars cross it faster, and the extra capacity feeds commercial and industrial demand.

### Big buildings

- An **airport** is a single 2x2 landmark: it needs four clear tiles, at least one of them beside a road, and is charged once. Bulldozing any of its tiles removes the whole airport for one refund.
- Four developed lots of the **same zone** that all reach level 2 merge into one large building. The merged block levels up as a single structure and every lot gains 35% extra capacity; the city block is drawn as one wide building across all four tiles.

### Simulation

Each in-game day (about 1.15 s at 1× speed) the engine:

1. Recomputes road access, rail access and water coverage for every tile — flooding water out from each tower through connected pipes, and transit out from each station on a line.
2. Flood-fills power from each plant through roads, highways, buildings, parks and buried cables, up to the plant's capacity. Empty grass and water block the flow unless a cable bridges them.
3. Measures police, fire and hospital coverage as the share of developed lots each service reaches.
4. Scores **R / C / I demand** against existing housing and job capacity, with rail, airport tourism and civic coverage feeding in.
5. Grows new buildings on zoned tiles that have power, road and water; occupied buildings upgrade toward level 3, faster when covered by police or fire services.
6. Moves residents and workers in, cuts off neglected buildings, and adjusts happiness from power/water coverage, parks, rail, the emergency services, pollution, unemployment and debt.
7. Merges four fully grown neighbouring lots into one large building.
8. Collects taxes ($0.16/resident, $0.10/job per day) and pays road, highway, rail, park, plant, layer, service and airport upkeep.
9. Emits a monthly finance report.

A building left without power, water or road access empties out and is eventually abandoned. The date, population, jobs, happiness, treasury and live demand bars are always visible in the top HUD.

The city saves automatically every month and whenever the app is paused; **NEW** starts over after a confirmation.

## Controls

- **Tap / drag on the map** — use the selected tool.
- **Two fingers** — pinch to zoom and drag to pan, even while building.
- **Speed button** (top right) — pause, 1×, 2×, 3×.
- **Toolbar** — four rows of tools; the hint line explains the next useful step.

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
    ├── Config.kt            # Tuning: costs, capacities, growth, power, services, colours
    ├── Models.kt            # Terrain, ZoneType, Tool, Tile, CityEvent
    ├── CityEngine.kt        # Terrain, build rules, power, water, services, growth, merges, economy, save/load
    ├── IsoArt.kt            # IsoCamera + cel-shaded renderer (tiles, buildings, trains, sea)
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
