# Aerocon — JavaFX Prototype

A JavaFX air traffic monitoring interface prototype based on the supplied PDF mockups. It uses navy backgrounds, a cyan radar, red conflict warnings, and an English UI.

![Radar screen](docs/screenshots/radar.png)

Other screens: [login](docs/screenshots/login.png), [administration](docs/screenshots/admin.png).

## Run on Windows

Double-click **run.cmd**, or run the following command from the project directory:

```powershell
.\run.cmd
```

The script uses a local JDK 21 and Maven from `.tools`. On the first run on another computer, it downloads these tools and verifies their checksums. An internet connection is required to download tools and dependencies; a system-wide Java installation is not required.

Demo credentials:

| Username | Password | Role |
| --- | --- | --- |
| `maya.chen` | `aerocon` | Controller |
| `sarah.patel` | `aerocon` | Admin |

Select a role in the login form. Both demo usernames can access either role in this prototype. This is a demo login flow, not a production authentication system.

If JDK 21+ and Maven are already installed, you can use `mvn javafx:run`. In IntelliJ IDEA, open `pom.xml` as a Maven project and select JDK 21.

## Features

- Login, password visibility toggle, role selection, and logout through the user menu.
- Radar with eight initial flights, aircraft movement, selection on the radar or in the flight list, flight details, and trajectory display.
- Pause and resume, playback speeds from 1× to 8×, radar zoom, and full-screen mode.
- Flight creation with input validation and initial position selection on the radar.
- Generation of 1–50 aircraft at a time, with three presets, an optional conflict pair, randomized altitudes, commercial traffic, and an option to clear existing traffic.
- Independent warning acknowledgement for each conflict pair.
- Administration panel with operator search and role filtering, user creation and editing, deletion with confirmation, incident history filters, and CSV export of visible records.

Data is stored in memory and resets when the application closes. Aircraft movement and conflict scenarios are demonstrations; actual separation, CPA (closest point of approach), vertical motion, and time-to-CPA calculations are not implemented. Incident history and operator accounts are separate demo datasets, without a database or server-side authentication.

## Project Structure

```text
project_po2/
├── pom.xml                              # Maven: Java/JavaFX versions, dependencies, build
├── run.cmd                              # double-click launcher for Windows
├── run.ps1                              # local tool downloads, build, and launch
├── verify.ps1                           # runs functional UI checks
├── README.md                            # overview, instructions, and this directory tree
├── .gitignore                           # excludes build output, caches, and IDE files
├── src/
│   ├── main/
│   │   ├── java/pl/aerocon/              # Java source code; pl.aerocon is the package name
│   │   │   ├── AeroconApplication.java   # entry point, login, controller view, navigation, timer
│   │   │   ├── model/                   # domain data
│   │   │   │   └── Flight.java          # aircraft parameters, coordinates, and initial demo flights
│   │   │   └── ui/                      # UI components and dialogs
│   │   │       ├── RadarView.java       # Canvas: grid, rings, aircraft, paths, mouse selection
│   │   │       ├── SimulationDialogs.java # flight creation, traffic generator, input validation
│   │   │       ├── AdminView.java       # users, management dialogs, incident history, CSV export
│   │   │       └── Ui.java              # shared factories for buttons, labels, fields, and panels
│   │   └── resources/pl/aerocon/        # resources Maven includes in the application
│   │       └── aerocon.css              # main JavaFX styles: palette, fonts, and UI controls
│   └── test/java/pl/aerocon/
│       └── PrototypeSmokeTest.java       # 33 checks using actual UI controls
├── docs/screenshots/                     # saved application previews
│   ├── login.png                        # login screen
│   ├── radar.png                        # controller screen
│   └── admin.png                        # administration screen
├── .tools/                              # local JDK and Maven downloaded by the launch script
├── target/                              # Maven build output: classes, JAR, reports
└── tmp/                                 # temporary PDF renders and verification screenshots
```

`.tools`, `target`, and `tmp` are excluded through `.gitignore`. Edit source files in `src`; files in `target` are regenerated during builds.

## Styling and Layout

- **Main theme — `src/main/resources/pl/aerocon/aerocon.css`.** `.root` defines the base colors and font, `.primary-button` styles the cyan buttons, `.panel` styles panels, `.warning-card` styles warnings, and `.table-view` styles tables. The stylesheet is loaded in `AeroconApplication.start()` and separately for dialogs.
- **Radar graphics — `src/main/java/pl/aerocon/ui/RadarView.java`.** The `BACKGROUND`, `GRID`, `RING`, `CYAN`, and `RED` colors, together with Canvas drawing commands, define the grid, rings, aircraft, and lines. Changing the CSS theme alone does not recolor the Canvas drawing.
- **Additional administration styles — `src/main/java/pl/aerocon/ui/AdminView.java`.** `setStyle()` calls define compact control sizes, status colors, and table row colors. They supplement and sometimes override the main CSS.
- **Layout — the Java classes for each screen.** `VBox`, `HBox`, `GridPane`, and `BorderPane` containers, dimensions, and spacing are configured in `AeroconApplication`, `AdminView`, and `SimulationDialogs`. `Ui.java` creates shared controls and assigns CSS classes.

The project has no FXML files: the interface is built programmatically with JavaFX.

## Build and Verify

Build without launching the application:

```powershell
.\run.cmd -BuildOnly
```

Run functional UI checks (opens a JavaFX test window):

```powershell
powershell -ExecutionPolicy Bypass -File .\verify.ps1
```

The checks interact with actual UI controls to verify login, pause and resume, forms, traffic generation, independent warning acknowledgement, and search in the administration panel.

The project uses Java 21, JavaFX 21, and Maven. The main theme is defined in CSS; Canvas graphics and some additional styles are defined in Java code.
