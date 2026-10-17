# GameDay Scores — Android

A dark, modern Android sports scores app for MLB, NBA, and NFL. Built with Kotlin + Jetpack Compose and the public ESPN scoreboard feed.

## Included
- Live, final, and upcoming games for all three leagues
- Date navigation (7 days back through 14 days ahead)
- Score cards with matchup, status, broadcast (when available), and venue in details
- League-specific news feed with article links
- Game-centre summary endpoint for available scoring and play-by-play entries
- Starred games saved on-device
- Retry / manual refresh and loading, empty, and network-error states
- Dark gradient UI, league selector, bottom navigation, game detail screen
- GitHub Actions workflow to build and publish an APK artifact

## Build an APK in GitHub
1. Create/open your GitHub repository.
2. Upload the contents of this folder to the repository root. Make sure `.github/workflows/build-apk.yml` is included (GitHub hides dot-folders in some upload flows; verify it exists in the repo).
3. Open **Actions** → **Build GameDay APK** → **Run workflow**. A push to `main` or `master` also starts a build.
4. Open the completed green run and scroll to **Artifacts**. Download **GameDay-debug-apk** and extract `app-debug.apk`.
5. Transfer the APK to your Galaxy S25 Ultra and open it. Android may ask you to allow installation from that source.

## Local build
Requires JDK 17 and Gradle 8.9. From this directory run:

```bash
gradle assembleDebug
```

APK output: `app/build/outputs/apk/debug/app-debug.apk`.

## Data and limitations
The app requests `https://site.api.espn.com/apis/site/v2/sports/{baseball/mlb|basketball/nba|football/nfl}/scoreboard`. This is an unofficial public feed, may change without notice, and does not come with an availability guarantee. Network access is required. News and game-centre details use ESPN public endpoints and may be incomplete or unavailable for some games. The standings screen remains a schedule-activity summary, not official division standings. Player-by-player box scores, push notifications, and home-screen widgets are not included yet. ESPN endpoints are unofficial and may change without notice.
