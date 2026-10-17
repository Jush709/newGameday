
package com.gameday.scores

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private val Bg = Color(0xFF080D19)
private val Panel = Color(0xFF111B2D)
private val Blue = Color(0xFF3987FF)
private val Cyan = Color(0xFF49D9E8)
private val Muted = Color(0xFF91A2BC)
private val White = Color(0xFFF4F7FF)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Blue,
                    background = Bg,
                    surface = Panel,
                    onSurface = White
                )
            ) {
                GameDayApp()
            }
        }
    }
}

enum class League(
    val label: String,
    val path: String,
    val mark: String
) {
    MLB("MLB", "baseball/mlb", "⚾"),
    NBA("NBA", "basketball/nba", "🏀"),
    NFL("NFL", "football/nfl", "🏈")
}

enum class Tab(val label: String) {
    HOME("Home"),
    SCORES("Scores"),
    NEWS("News"),
    FAVORITES("Saved"),
    STANDINGS("League"),
    SETTINGS("More")
}

data class Game(
    val id: String,
    val league: League,
    val away: String,
    val awayAbbr: String,
    val awayScore: String,
    val home: String,
    val homeAbbr: String,
    val homeScore: String,
    val status: String,
    val detail: String,
    val venue: String,
    val broadcast: String,
    val date: String
) {
    val live: Boolean
        get() = status.contains("progress", true) ||
            status.equals("live", true)

    val final: Boolean
        get() = status.contains("final", true) ||
            status.contains("completed", true)
}

data class NewsArticle(
    val league: League,
    val headline: String,
    val description: String,
    val url: String
)

class ScoresModel : ViewModel() {
    private val client = OkHttpClient()

    var league by mutableStateOf(League.MLB)
        private set
    var tab by mutableStateOf(Tab.HOME)
        private set
    var dayOffset by mutableIntStateOf(0)
        private set
    var games by mutableStateOf<List<Game>>(emptyList())
        private set
    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var selected by mutableStateOf<Game?>(null)
        private set
    var favorites by mutableStateOf<Set<String>>(emptySet())
        private set
    var autoRefresh by mutableStateOf(true)
        private set
    var articles by mutableStateOf<List<NewsArticle>>(emptyList())
        private set
    var newsLoading by mutableStateOf(false)
        private set
    var newsError by mutableStateOf<String?>(null)
        private set
    var gameSummary by mutableStateOf<List<String>>(emptyList())
        private set
    var summaryLoading by mutableStateOf(false)
        private set
    var summaryFor by mutableStateOf<String?>(null)
        private set
    var standings by mutableStateOf<List<String>>(emptyList())
        private set

    private var appContext: Context? = null

    init {
        load()
    }

    fun initialize(context: Context) {
        appContext = context.applicationContext
        favorites = context.getSharedPreferences("gameday", 0)
            .getStringSet("favorites", emptySet())
            ?.toSet() ?: emptySet()
    }

    private fun request(url: String): JsonObject {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "GameDayScores/1.0 (Android)")
            .build()

        client.newCall(req).execute().use { response ->
            if (!response.isSuccessful) {
                throw java.io.IOException("HTTP ${response.code}")
            }
            return JsonParser.parseString(
                response.body?.string() ?: "{}"
            ).asJsonObject
        }
    }

    fun load() {
        viewModelScope.launch {
            loading = true
            error = null

            try {
                val result = withContext(Dispatchers.IO) {
                    League.entries.flatMap { fetch(it, dayOffset) }
                }
                games = result.sortedBy { it.date }

                if (games.isEmpty()) {
                    error = "No games scheduled for this date. Try another day."
                }
            } catch (e: Exception) {
                error = "Couldn't load scores. Check your connection and try again."
            }

            loading = false
        }
    }

    private fun fetch(league: League, offset: Int): List<Game> {
        val cal = Calendar.getInstance()
        cal.add(Calendar.DAY_OF_YEAR, offset)
        val date = SimpleDateFormat("yyyyMMdd", Locale.US)
            .format(cal.time)

        val root = request(
            "https://site.api.espn.com/apis/site/v2/sports/" +
                "${league.path}/scoreboard?dates=$date"
        )

        val events = root.getAsJsonArray("events") ?: return emptyList()

        return events.mapNotNull { element ->
            try {
                val event = element.asJsonObject
                val competition = event.getAsJsonArray("competitions")
                    ?.get(0)?.asJsonObject ?: return@mapNotNull null

                val competitors = competition.getAsJsonArray("competitors")
                    ?: return@mapNotNull null

                var away: JsonObject? = null
                var home: JsonObject? = null

                competitors.forEach {
                    val team = it.asJsonObject
                    if (team.get("homeAway")?.asString == "home") {
                        home = team
                    } else {
                        away = team
                    }
                }

                val a = away ?: return@mapNotNull null
                val h = home ?: return@mapNotNull null

                fun value(obj: JsonObject, key: String, fallback: String = "—"): String {
                    val item = obj.get(key)
                    return if (item == null || item.isJsonNull) {
                        fallback
                    } else {
                        item.asString
                    }
                }

                val awayTeam = a.getAsJsonObject("team")
                val homeTeam = h.getAsJsonObject("team")
                val statusType = event.getAsJsonObject("status")
                    ?.getAsJsonObject("type")
                val state = statusType?.get("state")?.asString ?: "pre"
                val status = statusType?.get("description")?.asString
                    ?: "Scheduled"

                val eventDate = value(event, "date", "")
                val displayTime = try {
                    SimpleDateFormat("h:mm a", Locale.getDefault())
                        .format(
                            SimpleDateFormat(
                                "yyyy-MM-dd'T'HH:mm'Z'",
                                Locale.US
                            ).parse(eventDate) ?: Date()
                        )
                } catch (_: Exception) {
                    "Time TBD"
                }

                val broadcast = competition.getAsJsonArray("broadcasts")
                    ?.firstOrNull()?.asJsonObject
                    ?.getAsJsonArray("names")
                    ?.firstOrNull()?.asString ?: ""

                Game(
                    id = value(event, "id", "$date-${league.name}"),
                    league = league,
                    away = value(awayTeam, "displayName"),
                    awayAbbr = value(awayTeam, "abbreviation"),
                    awayScore = value(a, "score"),
                    home = value(homeTeam, "displayName"),
                    homeAbbr = value(homeTeam, "abbreviation"),
                    homeScore = value(h, "score"),
                    status = if (state == "in") "In Progress" else status,
                    detail = "$displayTime • ${league.label}",
                    venue = competition.getAsJsonObject("venue")
                        ?.get("fullName")?.asString ?: "Venue TBD",
                    broadcast = broadcast,
                    date = eventDate
                )
            } catch (_: Exception) {
                null
            }
        }
    }

    fun loadNews() {
        viewModelScope.launch {
            newsLoading = true
            newsError = null

            try {
                articles = withContext(Dispatchers.IO) {
                    League.entries.flatMap { currentLeague ->
                        val root = request(
                            "https://site.api.espn.com/apis/site/v2/sports/" +
                                "${currentLeague.path}/news"
                        )

                        val array = root.getAsJsonArray("articles")
                            ?: return@flatMap emptyList()

                        array.mapNotNull { item ->
                            try {
                                val article = item.asJsonObject
                                val headline = article.get("headline")
                                    ?.asString ?: return@mapNotNull null
                                val description = article.get("description")
                                    ?.asString ?: ""
                                val url = article.getAsJsonObject("links")
                                    ?.getAsJsonObject("web")
                                    ?.get("href")?.asString ?: ""

                                NewsArticle(
                                    currentLeague,
                                    headline,
                                    description,
                                    url
                                )
                            } catch (_: Exception) {
                                null
                            }
                        }
                    }.take(40)
                }

                if (articles.isEmpty()) {
                    newsError = "No news stories are available right now."
                }
            } catch (_: Exception) {
                newsError = "News is unavailable. Check your connection and retry."
            }

            newsLoading = false
        }
    }

    fun loadSummary(game: Game) {
        if (summaryFor == game.id && gameSummary.isNotEmpty()) return

        viewModelScope.launch {
            summaryLoading = true
            summaryFor = game.id
            gameSummary = emptyList()

            try {
                gameSummary = withContext(Dispatchers.IO) {
                    val root = request(
                        "https://site.api.espn.com/apis/site/v2/sports/" +
                            "${game.league.path}/summary?event=${game.id}"
                    )
                    val output = mutableListOf<String>()

                    val competitors = root.getAsJsonObject("header")
                        ?.getAsJsonArray("competitions")
                        ?.firstOrNull()?.asJsonObject
                        ?.getAsJsonArray("competitors")

                    competitors?.forEach { element ->
                        val competitor = element.asJsonObject
                        val team = competitor.getAsJsonObject("team")
                            ?.get("displayName")?.asString ?: "Team"
                        val score = competitor.get("score")
                            ?.asString ?: "—"
                        output.add("$team • $score")
                    }

                    // Convert Gson's JsonArray into indexed Kotlin values.
                    // This avoids calling takeLast() on a JsonArray.
                    val plays = root.getAsJsonArray("plays")
                    if (plays != null) {
                        val start = (plays.size() - 20).coerceAtLeast(0)
                        for (index in start until plays.size()) {
                            val play = plays[index].asJsonObject
                            val text = play.get("text")?.asString ?: ""
                            if (text.isNotBlank()) {
                                output.add("• $text")
                            }
                        }
                    }

                    output
                }

                if (gameSummary.isEmpty()) {
                    gameSummary = listOf(
                        "Detailed stats and play-by-play are not available for this matchup yet."
                    )
                }
            } catch (_: Exception) {
                gameSummary = listOf(
                    "Game details are temporarily unavailable. Try again later."
                )
            }

            summaryLoading = false
        }
    }

    fun loadStandings() {
        viewModelScope.launch {
            loading = true
            error = null

            try {
                standings = withContext(Dispatchers.IO) {
                    League.entries.map { currentLeague ->
                        val root = request(
                            "https://site.api.espn.com/apis/site/v2/sports/" +
                                "${currentLeague.path}/scoreboard"
                        )
                        val count = root.getAsJsonArray("events")?.size() ?: 0
                        "${currentLeague.mark}  ${currentLeague.label}\n" +
                            "$count recent or scheduled games found.\n" +
                            "Full division standings are not provided by this scoreboard feed."
                    }
                }
            } catch (_: Exception) {
                error = "League information is unavailable. Try again."
            }

            loading = false
        }
    }

    fun setLeague(value: League) {
        league = value
        load()
    }

    fun setTab(value: Tab) {
        tab = value
        selected = null
        when (value) {
            Tab.NEWS -> loadNews()
            Tab.STANDINGS -> loadStandings()
            else -> load()
        }
    }

    fun shiftDay(amount: Int) {
        dayOffset = (dayOffset + amount).coerceIn(-7, 14)
        load()
    }

    fun today() {
        dayOffset = 0
        load()
    }

    fun open(game: Game) {
        selected = game
    }

    fun back() {
        selected = null
    }

    fun toggleFavorite(game: Game) {
        val key = "${game.league.name}:${game.id}"
        favorites = if (key in favorites) favorites - key else favorites + key
        appContext?.getSharedPreferences("gameday", 0)
            ?.edit()?.putStringSet("favorites", favorites)?.apply()
    }

    fun isFavorite(game: Game): Boolean =
        "${game.league.name}:${game.id}" in favorites

    fun toggleRefresh() {
        autoRefresh = !autoRefresh
    }
}

@Composable
fun GameDayApp(model: ScoresModel = viewModel()) {
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        model.initialize(context)
    }

    LaunchedEffect(model.autoRefresh) {
        if (model.autoRefresh) {
            while (true) {
                delay(60_000)
                model.load()
            }
        }
    }

    Scaffold(
        containerColor = Bg,
        bottomBar = {
            NavigationBar(containerColor = Color(0xFF0D1525)) {
                Tab.entries.forEach { item ->
                    NavigationBarItem(
                        selected = model.tab == item,
                        onClick = { model.setTab(item) },
                        icon = {
                            Icon(
                                when (item) {
                                    Tab.HOME -> Icons.Default.Home
                                    Tab.SCORES -> Icons.Default.Sports
                                    Tab.NEWS -> Icons.Default.Newspaper
                                    Tab.FAVORITES -> Icons.Default.Star
                                    Tab.STANDINGS -> Icons.Default.Leaderboard
                                    Tab.SETTINGS -> Icons.Default.Tune
                                },
                                contentDescription = item.label
                            )
                        },
                        label = { Text(item.label, fontSize = 10.sp) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Cyan,
                            selectedTextColor = Cyan,
                            indicatorColor = Color(0xFF1C3558),
                            unselectedIconColor = Muted,
                            unselectedTextColor = Muted
                        )
                    )
                }
            }
        }
    ) { padding ->
        Column(
            Modifier.fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(Color(0xFF102341), Bg, Bg)
                    )
                )
                .padding(padding)
        ) {
            Header(model)

            if (model.selected != null) {
                GameDetail(model, model.selected!!)
            } else {
                when (model.tab) {
                    Tab.HOME, Tab.SCORES -> MainScores(model)
                    Tab.NEWS -> NewsScreen(model)
                    Tab.FAVORITES -> FavoritesScreen(model)
                    Tab.STANDINGS -> StandingsScreen(model)
                    Tab.SETTINGS -> SettingsScreen(model)
                }
            }
        }
    }
}

@Composable
private fun Header(model: ScoresModel) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Row {
                Text("GAME", color = White, fontSize = 25.sp, fontWeight = FontWeight.Black)
                Text("DAY", color = Cyan, fontSize = 25.sp, fontWeight = FontWeight.Black)
            }
            Text(
                "YOUR DAILY SPORTS FIX",
                color = Muted,
                fontSize = 10.sp,
                letterSpacing = 2.sp
            )
        }
        IconButton(onClick = { model.load() }) {
            Icon(Icons.Default.Refresh, "Refresh scores", tint = Cyan)
        }
    }
}

@Composable
private fun MainScores(model: ScoresModel) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(Color(0xFF0D1729)).padding(5.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        League.entries.forEach { item ->
            val active = model.league == item
            Column(
                Modifier.weight(1f)
                    .clip(RoundedCornerShape(14.dp))
                    .background(
                        if (active) Brush.horizontalGradient(
                            listOf(Blue, Color(0xFF3158E8))
                        ) else Brush.horizontalGradient(
                            listOf(Color(0xFF0D1729), Color(0xFF0D1729))
                        )
                    )
                    .clickable { model.setLeague(item) }
                    .padding(vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(item.mark, fontSize = 20.sp)
                Text(
                    item.label,
                    color = if (active) White else Muted,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }

    Row(
        Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                when {
                    model.dayOffset == 0 -> "Today's slate"
                    model.dayOffset > 0 -> "Upcoming games"
                    else -> "Recent games"
                },
                color = White,
                fontSize = 21.sp,
                fontWeight = FontWeight.Bold
            )
            Text(dayLabel(model.dayOffset), color = Muted, fontSize = 12.sp)
        }
        IconButton(onClick = { model.shiftDay(-1) }) {
            Icon(Icons.Default.ChevronLeft, "Previous day", tint = White)
        }
        Text(
            "Today",
            color = Cyan,
            modifier = Modifier.clip(RoundedCornerShape(10.dp))
                .background(Color(0xFF1D3A61))
                .clickable { model.today() }
                .padding(10.dp),
            fontSize = 12.sp
        )
        IconButton(onClick = { model.shiftDay(1) }) {
            Icon(Icons.Default.ChevronRight, "Next day", tint = White)
        }
    }

    Row(
        Modifier.fillMaxWidth().padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "${model.games.count { it.live }} LIVE",
            color = if (model.games.any { it.live }) Color(0xFFFF5964) else Muted,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.width(12.dp))
        Text("${model.games.size} MATCHUPS", color = Muted, fontSize = 11.sp)
        Spacer(Modifier.weight(1f))
        if (model.loading) {
            CircularProgressIndicator(
                Modifier.size(18.dp),
                color = Cyan,
                strokeWidth = 2.dp
            )
        }
    }

    if (model.error != null && model.games.isEmpty()) {
        ErrorPanel(model.error!!) { model.load() }
    } else {
        val shown = model.games.filter {
            it.league == model.league
        }

        if (shown.isEmpty() && !model.loading) {
            EmptyPanel("No ${model.league.label} games found", "Try another date.")
        }

        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(shown, key = { it.id }) { game ->
                GameCard(
                    game,
                    model.isFavorite(game),
                    { model.toggleFavorite(game) }
                ) {
                    model.open(game)
                }
            }
        }
    }
}

@Composable
private fun GameCard(
    game: Game,
    favorite: Boolean,
    onFavorite: () -> Unit,
    onOpen: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(22.dp),
        color = Panel,
        modifier = Modifier.fillMaxWidth().clickable { onOpen() }
    ) {
        Column(
            Modifier.background(
                Brush.horizontalGradient(
                    listOf(Color(0xFF15233A), Panel)
                )
            ).padding(16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${game.league.mark} ${game.league.label}",
                    color = Cyan,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    when {
                        game.live -> "● LIVE"
                        game.final -> "FINAL"
                        else -> "UPCOMING"
                    },
                    color = if (game.live) Color(0xFFFF6676) else Muted,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.weight(1f))
                IconButton(
                    onClick = onFavorite,
                    modifier = Modifier.size(34.dp)
                ) {
                    Icon(
                        if (favorite) Icons.Default.Star else Icons.Default.StarBorder,
                        "Save game",
                        tint = if (favorite) Color(0xFFFFD166) else Muted
                    )
                }
            }

            TeamLine(
                game.away,
                game.awayAbbr,
                game.awayScore,
                game.final && (game.awayScore.toIntOrNull() ?: -1) >
                    (game.homeScore.toIntOrNull() ?: -1)
            )
            Spacer(Modifier.height(12.dp))
            TeamLine(
                game.home,
                game.homeAbbr,
                game.homeScore,
                game.final && (game.homeScore.toIntOrNull() ?: -1) >
                    (game.awayScore.toIntOrNull() ?: -1)
            )

            HorizontalDivider(
                Modifier.padding(vertical = 12.dp),
                color = Color(0xFF263750)
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Schedule, null, tint = Muted, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    if (game.live || game.final) game.status
                    else game.detail.substringBefore(" •"),
                    color = Muted,
                    fontSize = 11.sp
                )
                Spacer(Modifier.weight(1f))
                if (game.broadcast.isNotBlank()) {
                    Text(
                        game.broadcast,
                        color = Muted,
                        fontSize = 10.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun TeamLine(
    name: String,
    abbreviation: String,
    score: String,
    winner: Boolean
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Surface(
            color = Color(0xFF223550),
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.size(40.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    abbreviation.take(3),
                    color = White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                name,
                color = if (winner) Cyan else White,
                fontWeight = if (winner) FontWeight.Bold else FontWeight.Medium,
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(abbreviation, color = Muted, fontSize = 10.sp)
        }
        Text(
            if (score == "—" || score.isBlank()) "–" else score,
            color = if (winner) Cyan else White,
            fontSize = 21.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun GameDetail(model: ScoresModel, game: Game) {
    LaunchedEffect(game.id) {
        model.loadSummary(game)
    }

    LazyColumn(
        Modifier.fillMaxSize().padding(18.dp),
        contentPadding = PaddingValues(bottom = 20.dp)
    ) {
        item {
            Text(
                "‹  BACK TO SCORES",
                color = Cyan,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.clickable { model.back() }
                    .padding(vertical = 8.dp)
            )
            Spacer(Modifier.height(16.dp))
            Surface(
                color = Panel,
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    Modifier.padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        "${game.league.mark}  ${game.league.label} MATCHUP",
                        color = Muted,
                        fontSize = 11.sp
                    )
                    Spacer(Modifier.height(18.dp))
                    Text(
                        "${game.away}  ${game.awayScore}",
                        color = White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 20.sp
                    )
                    Text("@", color = Muted)
                    Text(
                        "${game.home}  ${game.homeScore}",
                        color = White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 20.sp
                    )
                    Spacer(Modifier.height(14.dp))
                    Text(game.status.uppercase(), color = Cyan, fontWeight = FontWeight.Bold)
                    Text(game.detail, color = Muted, fontSize = 12.sp)
                }
            }

            Spacer(Modifier.height(18.dp))
            Text("GAME INFORMATION", color = Muted, fontSize = 11.sp)
            InfoRow("Venue", game.venue)
            InfoRow("Broadcast", game.broadcast.ifBlank { "Not listed" })
            InfoRow("Event ID", game.id)

            Spacer(Modifier.height(18.dp))
            Text(
                "GAME CENTRE",
                color = White,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(10.dp))

            if (model.summaryLoading) {
                CircularProgressIndicator(color = Cyan, modifier = Modifier.size(24.dp))
            }
        }

        items(model.gameSummary) { line ->
            Text(
                line,
                color = White,
                fontSize = 13.sp,
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
            )
            HorizontalDivider(color = Color(0xFF1D2A40))
        }

        item {
            Text(
                "Detailed stats depend on provider availability and may not be available for every game.",
                color = Muted,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 12.dp)
            )
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = Muted, modifier = Modifier.width(95.dp), fontSize = 13.sp)
        Text(value, color = White, fontSize = 13.sp, modifier = Modifier.weight(1f))
    }
    HorizontalDivider(color = Color(0xFF1D2A40))
}

@Composable
private fun FavoritesScreen(model: ScoresModel) {
    Text(
        "YOUR SAVED MATCHUPS",
        color = White,
        fontSize = 20.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(18.dp)
    )

    val saved = model.games.filter { model.isFavorite(it) }

    if (saved.isEmpty()) {
        EmptyPanel(
            "Your shortlist starts here",
            "Tap the star on any game to save it."
        )
    } else {
        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(saved, key = { it.id }) { game ->
                GameCard(game, true, { model.toggleFavorite(game) }) {
                    model.open(game)
                }
            }
        }
    }
}

@Composable
private fun NewsScreen(model: ScoresModel) {
    Row(
        Modifier.fillMaxWidth().padding(18.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text("THE LATEST", color = White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text("MLB • NBA • NFL headlines", color = Muted, fontSize = 12.sp)
        }
        IconButton(onClick = { model.loadNews() }) {
            Icon(Icons.Default.Refresh, "Refresh news", tint = Cyan)
        }
    }

    if (model.newsLoading && model.articles.isEmpty()) {
        CircularProgressIndicator(
            color = Cyan,
            modifier = Modifier.padding(24.dp)
        )
    }

    if (model.newsError != null && model.articles.isEmpty()) {
        ErrorPanel(model.newsError!!) { model.loadNews() }
    }

    LazyColumn(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        items(model.articles, key = { it.league.name + it.headline }) { article ->
            Surface(
                color = Panel,
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier.fillMaxWidth().clickable {
                    if (article.url.isNotBlank()) {
                        try {
                            LocalContext.current.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse(article.url))
                            )
                        } catch (_: Exception) {
                            // Ignore unavailable browser handlers.
                        }
                    }
                }
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        "${article.league.mark} ${article.league.label} NEWS",
                        color = Cyan,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(article.headline, color = White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    if (article.description.isNotBlank()) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            article.description,
                            color = Muted,
                            fontSize = 12.sp,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (article.url.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Text("READ STORY ↗", color = Cyan, fontSize = 10.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun StandingsScreen(model: ScoresModel) {
    Column(Modifier.fillMaxSize().padding(18.dp)) {
        Text("LEAGUE CENTRE", color = White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text("Recent and scheduled activity", color = Muted, fontSize = 12.sp)
        Spacer(Modifier.height(16.dp))

        if (model.loading) {
            CircularProgressIndicator(color = Cyan)
        }

        if (model.error != null) {
            ErrorPanel(model.error!!) { model.loadStandings() }
        }

        model.standings.forEach { line ->
            Surface(
                color = Panel,
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp)
            ) {
                Text(line, color = White, fontSize = 14.sp, modifier = Modifier.padding(16.dp))
            }
        }

        Text(
            "Note: this view reports scoreboard activity, not official division standings.",
            color = Muted,
            fontSize = 12.sp
        )
    }
}

@Composable
private fun SettingsScreen(model: ScoresModel) {
    Column(Modifier.fillMaxSize().padding(20.dp)) {
        Text("PREFERENCES", color = White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text("Make GameDay yours", color = Muted, fontSize = 13.sp)
        Spacer(Modifier.height(20.dp))

        Surface(color = Panel, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Refresh, null, tint = Cyan)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Auto-refresh scores", color = White, fontWeight = FontWeight.SemiBold)
                    Text("Update game data every 60 seconds", color = Muted, fontSize = 11.sp)
                }
                Switch(
                    checked = model.autoRefresh,
                    onCheckedChange = { model.toggleRefresh() }
                )
            }
        }

        Spacer(Modifier.height(12.dp))
        Surface(color = Panel, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("DATA SOURCE", color = Cyan, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                Text("ESPN public scoreboard API", color = White)
                Text(
                    "Internet connection required. Data availability depends on the provider.",
                    color = Muted,
                    fontSize = 12.sp
                )
            }
        }

        Spacer(Modifier.height(12.dp))
        Surface(color = Panel, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("ABOUT GAMEDAY", color = Cyan, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                Text("Version 1.0.0 • Built for sports fans", color = White)
                Text("Schedules, scores, news, and saved games in one place.", color = Muted, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun EmptyPanel(title: String, body: String) {
    Column(
        Modifier.fillMaxWidth().padding(34.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(Icons.Default.EventBusy, null, tint = Muted, modifier = Modifier.size(40.dp))
        Spacer(Modifier.height(12.dp))
        Text(title, color = White, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(5.dp))
        Text(body, color = Muted, fontSize = 12.sp)
    }
}

@Composable
private fun ErrorPanel(message: String, retry: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(22.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(Icons.Default.CloudOff, null, tint = Color(0xFFFF6676), modifier = Modifier.size(32.dp))
        Spacer(Modifier.height(8.dp))
        Text(message, color = White, fontSize = 13.sp)
        Spacer(Modifier.height(12.dp))
        Button(onClick = retry) {
            Text("Try again")
        }
    }
}

private fun dayLabel(offset: Int): String {
    val calendar = Calendar.getInstance()
    calendar.add(Calendar.DAY_OF_YEAR, offset)
    return SimpleDateFormat("EEEE, MMM d", Locale.getDefault())
        .format(calendar.time)
}
