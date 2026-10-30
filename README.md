# Cell City

A cel-shaded isometric city builder for Android. Zone neighbourhoods, wire them to power and water, connect them with roads, rail, subways and an airport, staff the police, fire and health services, watch homes and shops grow, and keep the budget (and the citizens) happy. An in-game advisor reads the mood of the city and tells you what to fix, and you set the tax rate that pays for it all.

Everything is drawn with a custom `Canvas` renderer: flat colour blocks, thick ink outlines, two-tone shading and animated water — no game engine and no third-party art dependencies.

## Gameplay

You start with **$15,000** and a 24×24 island split by a river.

### Tools

| Tool | Cost | Notes |
| --- | --- | --- |
| Road | $15 | Bridges over water cost $90 |
| Highway | $120 | Wide four-lane road: counts as road access, moves traffic faster and draws trade; $300 over water |
| Rail line | $40 | Two or more connected tiles form a line that speeds up growth beside it |
| Subway tunnel | $55 | Buried layer: two or more connected tunnels make a line; $160 under the river |
| Train station | $700 | Must sit beside a rail line; spreads transit access 5 tiles from its door |
| Subway station | $650 | Must sit on or beside a live subway line; spreads transit access 6 tiles |
| Residential zone | $40 | Homes grow next to a connected road with power and water |
| Commercial zone | $50 | Shops create jobs for residents |
| Industrial zone | $60 | More jobs and taxes, but pollution |
| Park | $100 | Boosts happiness |
| Power plant | $2,500 | Gas plant: powers up to 55 connected tiles, adds smog |
| Solar farm | $1,600 | 30 tiles of clean power |
| Wind farm | $2,200 | 45 tiles of clean power |
| Nuclear plant | $9,000 | 180 tiles of baseload power with only trace smog |
| Water tower | $500 | Pipes water to every built tile within 6 tiles |
| Water pipe | $12 | Buried main: runs under roads, zones and buildings; $70 under the river |
| Power cable | $10 | Buried cable: carries the grid across open ground; $50 under the river |
| Police station | $900 | Covers 7 tiles, cuts crime and lifts happiness |
| Fire station | $800 | Covers 6 tiles and protects the neighbourhood |
| Hospital | $1,200 | Covers 7 tiles and keeps citizens healthy |
| Airport | $6,000 | Takes a clear 2x2 block beside a road, hires 90 staff and boosts shops |
| Fill (reclaim) | $90 + $30 per water side | Dredges a water cell that touches the shore into buildable land; deeper water costs more |
| Canal | $100 + $30 per land side | Cuts a channel into empty land that touches water, so you can bring the river inland |
| Bulldoze | free | Clears a tile or a whole 2x2 block and refunds 25% |
| Pan | free | One-finger camera drag (pinch works any time) |

Tap once to build a tile, or drag to paint roads, rail, zones and canals in a line. **FILL** works the other way: drag out a rectangle and release to raise the whole patch. Utilities, services and airports always snap to a single tap. The **PLANT** button opens a picker for gas, solar, wind or nuclear plants, then shows the chosen type and its price. Zones display an **R / C / I** marker until a building grows, plus badges for a missing road (red **!**), power (amber **bolt**) and water (blue **droplet**).

### Utilities, layers and transit

- A **water tower** already waters everything within 6 tiles. **Water pipes** extend that reach: lay a main out from a tower and every lot beside a live pipe gets water, however far from the tower it sits.
- **Power cables** do the same job for the grid: power only travels through roads, buildings and parks, so a cable lets it cross open grass and water and reach a district that roads do not connect. It also fits under occupied tiles.
- Pipes and cables are layers, not zones: they share the tile with roads, zones, stations and buildings, can cross the river at higher prices, and take no surface space. Select the matching tool to lift that layer above the buildings. Bulldozing a tile pulls up every layer under it along with whatever sits on top.
- A **train station** must be built beside the rail line. It counts as transit access itself and pushes access out to every lot within 5 tiles, so a single station can serve a whole neighbourhood instead of only the blocks touching the track.
- **Subway tunnels** are a third buried layer. They run under buildings and under the river, and two or more connected tunnel tiles make a line. Tunnels alone carry nobody: a **subway station** standing on or beside a live line creates transit access for every lot within 6 tiles, and costs upkeep until you do. Transit access from either rail or subway speeds up growth and maturity.
- A **highway** is a four-lane road. Lots beside it get road access exactly like a normal street, cars cross it faster, and the extra capacity feeds commercial and industrial demand.

### Building on water

Water is not dead space, but it is not free either. There are three ways across it, and they trade price against permanence:

- **Bridge it** — a ROAD ($90) or HIGHWAY ($300) tile laid on water becomes a bridge. Cars, power and water mains all cross it, but the cell stays water, so nothing can be zoned on it.
- **Tunnel under it** — a SUBWAY ($160) is a buried layer; two or more connected tunnels make a line that stations can serve. It takes no surface space at all.
- **Fill it** — the **FILL** tool raises dry land out of the water. A cell costs $90 plus $30 for every water cell still beside it: filling a narrow creek costs $150, a typical waterfront cell $180, and a plot in the middle of the river $210. The cell has to touch existing land or land filled earlier, so you reclaim outward from the shore.

**FILL drags out a patch.** Touch the water, drag to the opposite corner and the cells the fill would reach light up while the hint row quotes the cell count and the total price; let go to pay and build. A tap fills a single cell. Nothing is charged while dragging, the whole patch is one undo step, and if the toll is more than the treasury the cells turn red and nothing is built. A rectangle may include dry land: only the water that can reach the shore is filled.

Filled cells become ordinary ground: zone them, water them, power them, park them. Filling is permanent — it is saved as reclaimed ground, bulldozing a filled cell leaves the land behind, and any pipe, cable or tunnel under it survives the fill. A bridge that gets filled turns into a plain road on dry land. Compare before you spend: a bridge across the channel is $90, while filling the same cell to zone it costs around $180 and is forever.

### Canals

The **CANAL** tool is the mirror image of FILL: it floods an empty land cell next to water. A cell costs $100 plus $30 for every land side it still has, so nibbling a bay inland is cheap and cutting deeper into the map costs more. It has to start at the water's edge and work outward, and it refuses occupied ground — roads, zones, buildings, or a buried pipe, cable or tunnel are in the way. Drag with the tool to cut a channel, and fill it back in later if you change your mind.

Canal or natural, water is worth money to the tax roll: every lot with open water on one of its four sides, and every lot standing on filled ground, holds **12% more** residents or jobs than the same lot inland. The info tool shows the bonus on those cells. A canal through a district is an expensive way to raise its capacity — and it costs you the cell the water sits on.

### Big buildings

- An **airport** is a single 2x2 landmark: it needs four clear tiles, at least one of them beside a road, and is charged once. Bulldozing any of its tiles removes the whole airport for one refund.

## Neighbourhoods and festivals

Enough developed lots close together grow into a named district on their own: **Little Italy**, **Chinatown**, **Little Havana**, **Little India** or **Little Spain**. The name comes from the cluster itself, so it is stable, and the district sign floats over the middle of it. A district lifts the mood a little, and the info tool names the neighbourhood a cell belongs to.

Each district keeps a feast day in the city calendar, on the first days of its own month: the Feast of San Gennaro in September, Lunar New Year in February, Carnaval in July, Diwali in November and the Feria de Abril in April. While the feast runs, banners go up over that neighbourhood's terraces, the mood climbs, and visitors spend in its shops.

July also brings the **Running of the Bulls** once the city has 80 residents: a citywide week of white shirts and red scarves, with a bull and its runners charging along the host neighbourhood's streets, tourist takings in the treasury and the occasional story about a tourist in flip-flops.

Districts pay their way. Shop jobs inside a district bring in **12% more tax** than the same shops on an anonymous street, and a district with a park, station, subway stop or airport within three tiles keeps its residents in a better mood. The **DISTRICTS** chip over the map lists every neighbourhood with its residents, jobs and feast month; tap a row to fly the camera there. Tap a district's sign on the map to rename it or give it another theme - the name and theme belong to that block and survive a reload.

## Growing the map

The city does not have to stay 24 x 24. The **GROW** tool buys one more column east and one more row south for a price that rises with the size of the city, up to 40 x 40. Existing tiles keep their coordinates, so every road, zone and building stays exactly where it was placed, and the new land arrives as open grass and forest ready to zone. The map size is stored in the save file, so an expanded city reloads at its full size.
- Four developed lots of the **same zone** that all reach level 2 merge into one large building. The merged block levels up as a single structure and every lot gains 35% extra capacity; the city block is drawn as one wide building across all four tiles.
- Four **park** tiles in a 2x2 block group into one large park as soon as the last one is placed. It is drawn as a single green space with paths, a fountain and a pond, and every tile still counts toward park coverage, contracts and the Green Belt award. Bulldozing any of its tiles removes the whole park for one refund.

### Simulation

Each in-game day (about 1.15 s at 1× speed) the engine:

1. Recomputes road access, rail and subway access, and water coverage for every tile — flooding water out from each tower through connected pipes, grouping tunnels into subway lines, and pushing transit out from each station on a line.
2. Flood-fills power from each plant through roads, highways, buildings, parks and buried cables, up to the plant's capacity. Empty grass and water block the flow unless a cable bridges them.
3. Measures police, fire and hospital coverage as the share of developed lots each service reaches.
4. Scores **R / C / I demand** against existing housing and job capacity, with rail, airport tourism and civic coverage feeding in.
5. Grows new buildings on zoned tiles that have power, road and water; occupied buildings upgrade toward level 3, faster when covered by police or fire services.
6. Moves residents and workers in, cuts off neglected buildings, and adjusts happiness from power/water coverage, parks, transit, the emergency services, pollution, unemployment, the tax rate and debt.
7. Merges four fully grown neighbouring lots into one large building.
8. Collects taxes at the rate you set (10% by default, which works out at $0.16/resident and $0.10/job per day) and pays road, highway, rail, subway, park, plant, layer, service and airport upkeep.
9. Emits a monthly finance report.

A building left without power, water or road access empties out and is eventually abandoned. The date, population, jobs, happiness, treasury and live demand bars are always visible in the top HUD.

### Fires and crime

Coverage buys more than happiness: it is what keeps a neighbourhood standing.

- A building that no **FIRE** station reaches can catch fire — about 0.4% per building per day, higher in smoggy districts, and up to three can burn at once. A crew that reaches it puts the blaze out the same day; left alone for 4 days the building is gutted and the lot is left empty. Fire crews reach 6 tiles.
- A building that no **POLICE** station reaches can be marked by crime. A mark lasts 12 days and drains residents and jobs every day it stands, drags happiness and demand down, and raises a "crime is rising" warning once enough buildings are hit. Police within 7 tiles clear a mark three times as fast.
- Both only begin once the matching tool has been earned (Steady Hands), so a young settlement is never punished for a service it cannot build yet. Fires are drawn as flames on the burning tile and crime as a chalk cross; the info tool explains either on the cell and the advisor ranks them as critical issues.

### Inspecting a cell

The **INFO** tool is free and builds nothing: tap a cell (or drag across the map) and a card pins itself beside it. The card says what the tile is and what it produces, whether it has road access, power and water, which emergency services reach it, its fire or crime state, and — for a zoned lot that will not grow — exactly which requirement is missing. Needs are ticked when met and crossed when not, and the card ends with the single most useful next action.

The city saves automatically every month and whenever the app is paused; **NEW** starts over after a confirmation.

## Progression & goals

A fresh city starts with a basic set of tools: roads, zones, parks, power, water, fill, canals and the buried mains/cables. Everything else — rail, highways, train and subway stations, subways, the emergency services and the airport — has to be **earned** by growing the city. Tap the goal counter (**e.g. `0/8`**) in the top bar to open the goals sheet.

Each milestone pays a cash bonus and, for the early ones, unlocks new tools:

| # | Milestone | Goal | Reward |
| --- | --- | --- | --- |
| 1 | First Residents | Reach 10 population | $250 + **Rail** |
| 2 | Hamlet | Reach 100 population | $500 + **Train station, Highway** |
| 3 | Steady Hands | 30 stable days in a row | $750 + **Police, Fire** |
| 4 | Town | Reach 500 population | $1,000 + **Hospital, Subway tunnel, Subway station** |
| 5 | Skyline | Merge four lots into one large building | $1,500 + **Airport** |
| 6 | City | Reach 1,000 population | $3,000 |
| 7 | Stable Cycle | 100 stable days in a row | $5,000 |
| 8 | Metropolis | Reach 5,000 population | $10,000 |

A day counts toward a **stable streak** when the city has at least 20 residents, happiness is at least 55 and the treasury is not losing money that day. Milestones only advance in order: a run of 100 stable days ticks the *Stable Hands* goal before it counts toward the *Stable Cycle*. Locked tools show a padlock in the toolbar; tapping one tells you to open GOALS.

## The advisor and the tax rate

Tap the speech bubble in the top right corner (the badge counts the open issues) to open the **advisor** sheet.

- **Mood** — the citizens are *Thriving*, *Content*, *Uneasy* or *Angry*, with their current happiness and a line of what they are saying. Debt, unemployment, heavy taxes and blackouts all drag the mood down.
- **What needs fixing** — a ranked list of problems: blackouts, dry taps, lots cut off from the roads, too few jobs, thin police/fire/health cover, smog, a treasury in the red, taxes that are too high, tunnels with no platforms, missing transit or parks, and zoned lots that will not grow. Critical issues come first, each with the number that matters and the tool that fixes it.
- **Going well** — a short list of what is working, from full service cover to a balanced budget.
- **Tax rate** — the same sheet carries a `-` / `+` stepper for the city's tax rate (0–25%, 10% by default). Tax income scales linearly with the rate, while every point above 10% costs 1.1 happiness and every point below it buys some back. The sheet shows the projected daily tax take and upkeep so you can see the trade-off before you commit.

The current rate is always shown next to the date in the top bar, and the treasury line turns coral once it is punitive.

## Sound

All audio is synthesised at runtime by `SoundSynth` - the game ships no sound files.

- **City hum** - a seamless eight-second drone loops quietly underneath the game.
- **Building** - a soft knock when you place something, a bright blip when a lot grows, a warmer chime when it upgrades, and a low fall when a building is abandoned.
- **Fires** - a roar with crackles when a building ignites, a quiet crackle bed while anything is still burning, and a steam hiss when crews knock it down.
- **Crime** - a two-tone police siren when crime is first reported and each time it escalates.
- **Crowd** - a hurray with claps when the city is happy (happiness 70+) or a milestone is reached, and a low grumble when the mood sours (happiness 45 or less). Both only start once at least 25 people live in the city, and neither repeats more than every few seconds.

The speaker button in the top right turns all of it off and on, and the setting is remembered. One-shots stop when the game is paused or backgrounded.

## Controls

- **Tap / drag on the map** — use the selected tool.
- **Two fingers** — pinch to zoom and drag to pan, even while building.
- **Speed button** (top right) — pause, 1×, 2×, 3×.
- **Sound button** (speaker, top right) — mute or unmute effects and the city hum.
- **UNDO button** (top right) — takes back the last tap or drag as one step, refunding what it cost or charging back a bulldoze refund. The last 15 steps are kept.
- **Event banner** — tap a fire or crime message and the camera glides to the cell it is about, with a bobbing arrow marking the spot for a few seconds.
- **Advisor button** (speech bubble, top right) — the mood, the city's problems and the tax stepper.
- **INFO tool** — tap a cell to read what it is and what it still needs; it costs nothing.
- **Toolbar** — four rows of tools; the hint line explains the next useful step.
- The line under the treasury shows the date and the current tax rate.

## Tech stack

- Kotlin 2.2, Android minSdk 24 / targetSdk 36 (Play requires API 36 for new apps since 31 Aug 2026)
- 100% custom 2D `Canvas` rendering with an isometric camera
- Pure-Kotlin simulation, decoupled from Android so it is unit-testable
- No external runtime dependencies beyond the Kotlin stdlib
- JUnit 4 for the engine and audio-synthesis tests

## Project structure

```
app/src/main/java/com/cellcity/
├── MainActivity.kt          # Immersive fullscreen host
└── game/
    ├── Config.kt            # Tuning: costs, capacities, growth, power, services, taxes, colours
    ├── Models.kt            # Terrain, ZoneType, Tool, Tile, CityEvent
    ├── CityEngine.kt        # Terrain, build rules, power, water, services, growth, merges, economy, save/load
    ├── Advisor.kt           # Mood and issue report read from the engine, pure Kotlin
    ├── IsoArt.kt            # IsoCamera + cel-shaded renderer (tiles, buildings, trains, subways, sea)
    ├── SoundSynth.kt        # Procedural audio buffers (blips, fire, siren, crowd, ambience), pure Kotlin
    ├── SoundManager.kt      # AudioTrack pool, scene reactions and the sound toggle
    └── GameView.kt          # Loop, HUD, advisor sheet, toolbar, gestures, persistence
app/src/test/java/com/cellcity/game/
├── CityEngineTest.kt
└── SoundSynthTest.kt
```

## Build

Requires the Android SDK platform 36 and JDK 17 for Gradle (AGP 8.13, Gradle 8.13).

```bash
./gradlew testDebugUnitTest   # engine and audio tests
./gradlew assembleDebug       # debug APK
./gradlew installDebug        # install on a connected device
```

Set `sdk.dir` in `local.properties` (or `ANDROID_HOME`) before building.

## Release builds

The Play upload is signed with the key described in `keystore.properties` at the
project root (never committed; `*.jks`/`*.keystore` are ignored too):

```
storeFile=keystore/upload-keystore.jks
storePassword=…
keyAlias=…
keyPassword=…
```

Without that file the release variant still builds, but unsigned. Then:

```bash
./gradlew assembleRelease   # signed APK, for review or side-loading only
./gradlew bundleRelease     # app/build/outputs/bundle/release/app-release.aab
./gradlew lintRelease       # Play-blocking lint errors
```

Upload the `.aab` to Play Console. Bump `versionCode` in `app/build.gradle` for
every upload; `versionName` is the user-visible number. Keep the keystore and
its passwords backed up — losing the upload key means a Play key reset.
# cellCity
