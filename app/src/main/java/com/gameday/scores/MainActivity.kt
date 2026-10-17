package com.gameday.scores

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
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private val Bg = Color(0xFF080D19)
private val Panel = Color(0xFF111B2D)
private val Panel2 = Color(0xFF17243A)
private val Blue = Color(0xFF3987FF)
private val Cyan = Color(0xFF49D9E8)
private val Muted = Color(0xFF91A2BC)
private val White = Color(0xFFF4F7FF)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { GameDayTheme { GameDayApp() } }
    }
}

@Composable private fun GameDayTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = darkColorScheme(primary = Blue, background = Bg, surface = Panel, onSurface = White), content = content)
}

enum class League(val label: String, val short: String, val path: String, val mark: String) {
    MLB("MLB", "MLB", "baseball/mlb", "⚾"), NBA("NBA", "NBA", "basketball/nba", "🏀"), NFL("NFL", "NFL", "football/nfl", "🏈")
}
enum class Tab(val label: String) { HOME("Home"), SCORES("Scores"), NEWS("News"), FAVORITES("Saved"), STANDINGS("Standings"), SETTINGS("More") }
data class NewsArticle(val league: League, val headline: String, val description: String, val url: String, val published: String)
data class Game(val id: String, val league: League, val away: String, val awayAbbr: String, val awayLogo: String, val awayScore: String, val home: String, val homeAbbr: String, val homeLogo: String, val homeScore: String, val status: String, val detail: String, val venue: String, val broadcast: String, val date: String) {
    val live get() = status.contains("progress", true) || status.contains("in progress", true) || status.equals("live", true)
    val final get() = status.contains("final", true) || status.contains("completed", true)
}

class ScoresModel : ViewModel() {
    private val client = OkHttpClient()
    var league by mutableStateOf(League.MLB); private set
    var tab by mutableStateOf(Tab.HOME); private set
    var dayOffset by mutableIntStateOf(0); private set
    var games by mutableStateOf<List<Game>>(emptyList()); private set
    var loading by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set
    var selected by mutableStateOf<Game?>(null); private set
    var search by mutableStateOf(""); private set
    var favorites by mutableStateOf<Set<String>>(emptySet()); private set
    var autoRefresh by mutableStateOf(true); private set
    var articles by mutableStateOf<List<NewsArticle>>(emptyList()); private set
    var newsLoading by mutableStateOf(false); private set
    var newsError by mutableStateOf<String?>(null); private set
    var gameSummary by mutableStateOf<List<String>>(emptyList()); private set
    var summaryLoading by mutableStateOf(false); private set
    var summaryFor by mutableStateOf<String?>(null); private set

    init { load() }
    fun loadNews() { viewModelScope.launch { newsLoading = true; newsError = null; try { articles = withContext(Dispatchers.IO) { League.entries.flatMap { l -> parseNews(l, request("https://site.api.espn.com/apis/site/v2/sports/${l.path}/news")) } }.take(40); if (articles.isEmpty()) newsError = "No news stories are available right now." } catch (_: Exception) { newsError = "News feed unavailable. Check your connection and retry." }; newsLoading = false } }
    private fun parseNews(l: League, root: JsonObject): List<NewsArticle> { val arr = root.getAsJsonArray("articles") ?: return emptyList(); return arr.mapNotNull { try { val o = it.asJsonObject; fun v(k: String) = o.get(k)?.takeUnless { x -> x.isJsonNull }?.asString ?: ""; val headline = v("headline"); if (headline.isBlank()) return@mapNotNull null; val web = o.getAsJsonObject("links")?.getAsJsonObject("web")?.get("href")?.asString ?: ""; NewsArticle(l, headline, v("description"), web, v("published")) } catch (_: Exception) { null } } }
    fun loadSummary(g: Game) { if (summaryFor == g.id) return; viewModelScope.launch { summaryLoading = true; summaryFor = g.id; gameSummary = emptyList(); try { gameSummary = withContext(Dispatchers.IO) { val root = request("https://site.api.espn.com/apis/site/v2/sports/${g.league.path}/summary?event=${g.id}"); val out = mutableListOf<String>(); val header = root.getAsJsonObject("header"); header?.getAsJsonArray("competitions")?.firstOrNull()?.asJsonObject?.getAsJsonArray("competitors")?.forEach { el -> val c = el.asJsonObject; val team = c.getAsJsonObject("team")?.get("displayName")?.asString ?: "Team"; out.add("$team • ${c.get("score")?.asString ?: "—"}") }; root.getAsJsonArray("plays")?.takeLast(20)?.forEach { el -> val text = el.asJsonObject.get("text")?.asString ?: ""; if (text.isNotBlank()) out.add("• $text") }; out }; if (gameSummary.isEmpty()) gameSummary = listOf("Detailed stats and play-by-play are not available for this matchup yet.") } catch (_: Exception) { gameSummary = listOf("Game details are temporarily unavailable. Try again later.") }; summaryLoading = false } }

    fun initialize(context: android.content.Context) { appContext = context.applicationContext; appNewsContext = context.applicationContext; favorites = context.getSharedPreferences("gameday", 0).getStringSet("favorites", emptySet())?.toSet() ?: emptySet() }
    fun setLeague(v: League) { league = v; load() }
    fun setTab(v: Tab) { tab = v; selected = null; if (v == Tab.STANDINGS) loadStandings() else if (v == Tab.NEWS) loadNews() else load() }
    fun shiftDay(v: Int) { dayOffset = (dayOffset + v).coerceIn(-7, 14); load() }
    fun today() { dayOffset = 0; load() }
    fun setSearch(v: String) { search = v }
    fun open(g: Game) { selected = g }
    fun back() { selected = null }
    fun toggleFavorite(g: Game) { val k = "${g.league.name}:${g.id}"; favorites = if (k in favorites) favorites - k else favorites + k; getPrefs()?.edit()?.putStringSet("favorites", favorites)?.apply() }
    fun isFavorite(g: Game) = "${g.league.name}:${g.id}" in favorites
    fun toggleRefresh() { autoRefresh = !autoRefresh }
    private fun getPrefs() = appContext?.getSharedPreferences("gameday", 0)

    fun load() {
        viewModelScope.launch {
            loading = true; error = null
            try {
                val result = withContext(Dispatchers.IO) { League.entries.flatMap { fetch(it, dayOffset) } }
                games = result.sortedBy { it.date }
                if (games.isEmpty()) error = "No games scheduled for this date. Try another day."
            } catch (e: Exception) { error = "Couldn't load scores. Check your connection and pull to refresh. (${e.message?.take(70) ?: "network error"})" }
            loading = false
        }
    }
    var standings by mutableStateOf<List<String>>(emptyList()); private set
    fun loadStandings() {
        viewModelScope.launch {
            loading = true; error = null
            try {
                val all = withContext(Dispatchers.IO) { League.entries.map { l ->
                    val url = "https://site.api.espn.com/apis/site/v2/sports/${l.path}/scoreboard"
                    val root = request(url); val events = root.getAsJsonArray("events")
                    "${l.mark}  ${l.label}  •  ${events?.size() ?: 0} scheduled / recent games\nSeason schedule and scores are provided by ESPN's public scoreboard feed."
                } }
                standings = all
            } catch (_: Exception) { error = "Standings summary unavailable. Try again later." }
            loading = false
        }
    }
    private fun fetch(l: League, offset: Int): List<Game> {
        val cal = Calendar.getInstance(); cal.add(Calendar.DAY_OF_YEAR, offset)
        val date = SimpleDateFormat("yyyyMMdd", Locale.US).format(cal.time)
        val root = request("https://site.api.espn.com/apis/site/v2/sports/${l.path}/scoreboard?dates=$date")
        val events = root.getAsJsonArray("events") ?: return emptyList()
        return events.mapNotNull { el -> try {
            val e = el.asJsonObject
            val comp = e.getAsJsonArray("competitions")?.get(0)?.asJsonObject ?: return@mapNotNull null
            val teams = comp.getAsJsonArray("competitors")
            var away: JsonObject? = null; var home: JsonObject? = null
            teams.forEach { t -> val o = t.asJsonObject; if (o.get("homeAway")?.asString == "home") home = o else away = o }
            val a = away ?: return@mapNotNull null; val h = home ?: return@mapNotNull null
            val statusObj = e.getAsJsonObject("status")?.getAsJsonObject("type")
            val status = statusObj?.get("description")?.asString ?: "Scheduled"
            val statusState = statusObj?.get("state")?.asString ?: "pre"
            val venue = comp.getAsJsonObject("venue")?.get("fullName")?.asString ?: "Venue TBD"
            val broadcast = comp.getAsJsonArray("broadcasts")?.firstOrNull()?.asJsonObject?.getAsJsonArray("names")?.firstOrNull()?.asString ?: ""
            fun str(o: JsonObject, key: String, fallback: String = "—") = o.get(key)?.takeUnless { it.isJsonNull }?.asString ?: fallback
            val whenDate = e.get("date")?.asString ?: ""
            val time = try { SimpleDateFormat("h:mm a", Locale.getDefault()).format(SimpleDateFormat("yyyy-MM-dd'T'HH:mm'Z'", Locale.US).parse(whenDate) ?: Date()) } catch (_: Exception) { "Time TBD" }
            Game(e.get("id")?.asString ?: "$date-${l.name}-${a.get("team").asJsonObject.get("abbreviation")}", l,
                str(a.getAsJsonObject("team"), "displayName"), str(a.getAsJsonObject("team"), "abbreviation"), str(a.getAsJsonObject("team"), "logo", ""), str(a,"score"),
                str(h.getAsJsonObject("team"), "displayName"), str(h.getAsJsonObject("team"), "abbreviation"), str(h.getAsJsonObject("team"), "logo", ""), str(h,"score"),
                if (statusState == "in") "In Progress" else status, "$time  •  ${l.label}", venue, broadcast, whenDate)
        } catch (_: Exception) { null } }
    }
    private fun request(url: String): JsonObject {
        val req = Request.Builder().url(url).header("User-Agent", "GameDayScores/1.0 (Android)").build()
        client.newCall(req).execute().use { response -> if (!response.isSuccessful) error("HTTP ${response.code}"); return JsonParser.parseString(response.body?.string() ?: "{}").asJsonObject }
    }
    companion object { var appContext: android.content.Context? = null }
}

@Composable fun GameDayApp(model: ScoresModel = viewModel()) {
    val context = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(Unit) { model.initialize(context) }
    LaunchedEffect(model.autoRefresh) { if (model.autoRefresh) { while (true) { delay(60_000); model.load() } } }
    Scaffold(containerColor = Bg, bottomBar = {
        NavigationBar(containerColor = Color(0xFF0D1525), contentColor = Muted) {
            Tab.entries.forEach { t -> NavigationBarItem(selected = model.tab == t, onClick = { model.setTab(t) }, icon = { Icon(when(t) { Tab.HOME -> Icons.Default.Home; Tab.SCORES -> Icons.Default.Sports; Tab.NEWS -> Icons.Default.Newspaper; Tab.FAVORITES -> Icons.Default.Star; Tab.STANDINGS -> Icons.Default.Leaderboard; Tab.SETTINGS -> Icons.Default.Tune }, null) }, label = { Text(t.label, fontSize = 10.sp) }, colors = NavigationBarItemDefaults.colors(selectedIconColor = Cyan, selectedTextColor = Cyan, indicatorColor = Color(0xFF1C3558), unselectedIconColor = Muted, unselectedTextColor = Muted)) }
        }
    }) { padding ->
        Column(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF102341), Bg, Bg))).padding(padding)) {
            Header(model)
            if (model.selected != null) GameDetail(model, model.selected!!) else when (model.tab) {
                Tab.HOME, Tab.SCORES -> MainScores(model)
                Tab.NEWS -> NewsScreen(model)
                Tab.FAVORITES -> FavoritesScreen(model)
                Tab.STANDINGS -> StandingsScreen(model)
                Tab.SETTINGS -> SettingsScreen(model)
            }
        }
    }
}

@Composable private fun Header(m: ScoresModel) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 18.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) { Text("GAME", color = White, fontSize = 25.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp); Text("DAY", color = Cyan, fontSize = 25.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp) }
            Text("YOUR DAILY SPORTS FIX", color = Muted, fontSize = 10.sp, letterSpacing = 2.sp)
        }
        Surface(shape = RoundedCornerShape(14.dp), color = Color(0xFF1B2D48), modifier = Modifier.size(44.dp).clickable { m.load() }) { Box(contentAlignment = Alignment.Center) { Icon(Icons.Default.Refresh, "Refresh scores", tint = Cyan) } }
    }
}

@Composable private fun MainScores(m: ScoresModel) {
    LeaguePicker(m)
    Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { Text(if (m.dayOffset == 0) "Today's slate" else if (m.dayOffset > 0) "Upcoming games" else "Recent games", color = White, fontSize = 21.sp, fontWeight = FontWeight.Bold); Text(dayLabel(m.dayOffset), color = Muted, fontSize = 12.sp) }
        IconButton(onClick = { m.shiftDay(-1) }) { Icon(Icons.Default.ChevronLeft, "Previous day", tint = White) }
        Surface(color = Color(0xFF1D3A61), shape = RoundedCornerShape(12.dp), modifier = Modifier.clickable { m.today() }) { Text("Today", color = Cyan, modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp), fontSize = 12.sp, fontWeight = FontWeight.SemiBold) }
        IconButton(onClick = { m.shiftDay(1) }) { Icon(Icons.Default.ChevronRight, "Next day", tint = White) }
    }
    Row(Modifier.padding(horizontal = 18.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("${m.games.count { it.live }} LIVE", color = if (m.games.any { it.live }) Color(0xFFFF5964) else Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(12.dp)); Text("${m.games.size} MATCHUPS", color = Muted, fontSize = 11.sp)
        Spacer(Modifier.weight(1f)); if (m.loading) CircularProgressIndicator(Modifier.size(16.dp), color = Cyan, strokeWidth = 2.dp)
    }
    if (m.error != null && m.games.isEmpty()) ErrorPanel(m.error!!) { m.load() }
    else LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        val shown = m.games.filter { it.league == m.league && (it.away.contains(m.search, true) || it.home.contains(m.search, true) || it.awayAbbr.contains(m.search, true) || it.homeAbbr.contains(m.search, true)) }
        if (shown.isEmpty() && !m.loading) item { EmptyPanel("No ${m.league.label} games found", "Try another date or league.") }
        items(shown, key = { it.id }) { GameCard(it, m.isFavorite(it), { m.toggleFavorite(it) }) { m.open(it) } }
    }
}

@Composable private fun LeaguePicker(m: ScoresModel) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp).clip(RoundedCornerShape(18.dp)).background(Color(0xFF0D1729)).padding(5.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        League.entries.forEach { l ->
            val selected = l == m.league
            Column(Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).background(if (selected) Brush.horizontalGradient(listOf(Blue, Color(0xFF3158E8))) else Brush.horizontalGradient(listOf(Color.Transparent, Color.Transparent)).let { if (selected) it else Brush.horizontalGradient(listOf(Color(0xFF0D1729), Color(0xFF0D1729))) }).clickable { m.setLeague(l) }.padding(vertical = 11.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(l.mark, fontSize = 20.sp); Text(l.label, color = if (selected) White else Muted, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable private fun GameCard(g: Game, favorite: Boolean, onFavorite: () -> Unit, onOpen: () -> Unit) {
    Surface(shape = RoundedCornerShape(22.dp), color = Panel, modifier = Modifier.fillMaxWidth().clickable { onOpen() }, tonalElevation = 2.dp) {
        Column(Modifier.background(Brush.horizontalGradient(listOf(Color(0xFF15233A), Panel))).padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(color = Color(0xFF243B5D), shape = RoundedCornerShape(7.dp)) { Text(g.league.label, color = Cyan, fontSize = 9.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp)) }
                Spacer(Modifier.width(8.dp))
                if (g.live) { Box(Modifier.size(6.dp).clip(RoundedCornerShape(50)).background(Color(0xFFFF5364))); Spacer(Modifier.width(5.dp)); Text("LIVE", color = Color(0xFFFF6676), fontSize = 10.sp, fontWeight = FontWeight.Bold) }
                else Text(if (g.final) "FINAL" else "UPCOMING", color = Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f)); IconButton(onClick = onFavorite, modifier = Modifier.size(30.dp)) { Icon(if (favorite) Icons.Default.Star else Icons.Default.StarBorder, "Toggle favorite", tint = if (favorite) Color(0xFFFFD166) else Muted, modifier = Modifier.size(20.dp)) }
            }
            Spacer(Modifier.height(12.dp))
            TeamLine(g.away, g.awayAbbr, g.awayScore, g.awayLogo, g.final && (g.awayScore.toIntOrNull() ?: -1) > (g.homeScore.toIntOrNull() ?: -1))
            Spacer(Modifier.height(10.dp))
            TeamLine(g.home, g.homeAbbr, g.homeScore, g.homeLogo, g.final && (g.homeScore.toIntOrNull() ?: -1) > (g.awayScore.toIntOrNull() ?: -1))
            Spacer(Modifier.height(14.dp)); HorizontalDivider(color = Color(0xFF263750)); Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Schedule, null, tint = Muted, modifier = Modifier.size(14.dp)); Spacer(Modifier.width(5.dp)); Text(if (g.live || g.final) g.status else g.detail.substringBefore(" •"), color = if (g.live) Cyan else Muted, fontSize = 11.sp)
                Spacer(Modifier.weight(1f)); if (g.broadcast.isNotBlank()) { Icon(Icons.Default.Tv, null, tint = Muted, modifier = Modifier.size(14.dp)); Spacer(Modifier.width(4.dp)); Text(g.broadcast, color = Muted, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                Spacer(Modifier.width(6.dp)); Icon(Icons.Default.ChevronRight, null, tint = Muted, modifier = Modifier.size(16.dp))
            }
        }
    }
}

@Composable private fun TeamLine(name: String, abbr: String, score: String, logo: String, winner: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Surface(color = Color(0xFF223550), shape = RoundedCornerShape(10.dp), modifier = Modifier.size(38.dp)) { Box(contentAlignment = Alignment.Center) { Text(abbr.take(3), color = White, fontWeight = FontWeight.Bold, fontSize = 11.sp) } }
        Spacer(Modifier.width(11.dp)); Column(Modifier.weight(1f)) { Text(name, color = if (winner) Cyan else White, fontWeight = if (winner) FontWeight.Bold else FontWeight.Medium, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis); Text(abbr, color = Muted, fontSize = 10.sp) }
        Text(if (score == "—" || score.isBlank()) "–" else score, color = if (winner) Cyan else White, fontSize = 21.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable private fun GameDetail(m: ScoresModel, g: Game) {
    LaunchedEffect(g.id) { m.loadSummary(g) }
    Column(Modifier.fillMaxSize().padding(18.dp)) {
        Text("‹  BACK TO SCORES", color = Cyan, fontWeight = FontWeight.Bold, modifier = Modifier.clickable { m.back() }.padding(vertical = 8.dp))
        Spacer(Modifier.height(20.dp))
        Surface(color = Panel, shape = RoundedCornerShape(26.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("${g.league.mark}  ${g.league.label} MATCHUP", color = Muted, fontSize = 11.sp, letterSpacing = 2.sp)
                Spacer(Modifier.height(20.dp)); Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) { TeamBadge(g.awayAbbr); Spacer(Modifier.height(8.dp)); Text(g.away, color = White, fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 2); Text(g.awayScore, color = Cyan, fontSize = 32.sp, fontWeight = FontWeight.Black) }
                    Text("@", color = Muted, fontSize = 20.sp, modifier = Modifier.padding(horizontal = 12.dp))
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) { TeamBadge(g.homeAbbr); Spacer(Modifier.height(8.dp)); Text(g.home, color = White, fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 2); Text(g.homeScore, color = Cyan, fontSize = 32.sp, fontWeight = FontWeight.Black) }
                }
                Spacer(Modifier.height(18.dp)); Text(g.status.uppercase(), color = if (g.live) Color(0xFFFF6676) else Cyan, fontWeight = FontWeight.Bold); Text(g.detail, color = Muted, fontSize = 12.sp)
            }
        }
        Spacer(Modifier.height(16.dp)); Text("GAME INFORMATION", color = Muted, fontSize = 11.sp, letterSpacing = 1.5.sp, fontWeight = FontWeight.Bold)
        InfoRow(Icons.Default.Stadium, "Venue", g.venue); InfoRow(Icons.Default.Tv, "Broadcast", g.broadcast.ifBlank { "Not listed" }); InfoRow(Icons.Default.Event, "Event ID", g.id)
        Spacer(Modifier.height(18.dp)); Text("GAME CENTRE", color = White, fontSize = 16.sp, fontWeight = FontWeight.Bold); Spacer(Modifier.height(8.dp)); if (m.summaryLoading) CircularProgressIndicator(color = Cyan, modifier = Modifier.size(22.dp)); m.gameSummary.forEach { Text(it, color = White, fontSize = 12.sp, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)); HorizontalDivider(color = Color(0xFF1D2A40)) }; Spacer(Modifier.height(10.dp)); Text("Detailed stats depend on the provider and may not be available for every game.", color = Muted, fontSize = 12.sp)
    }
}
@Composable private fun TeamBadge(abbr: String) { Surface(color = Color(0xFF223D62), shape = RoundedCornerShape(18.dp), modifier = Modifier.size(72.dp)) { Box(contentAlignment = Alignment.Center) { Text(abbr.take(3), color = White, fontSize = 19.sp, fontWeight = FontWeight.Black) } } }
@Composable private fun InfoRow(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, value: String) { Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) { Icon(icon, null, tint = Cyan, modifier = Modifier.size(20.dp)); Spacer(Modifier.width(12.dp)); Text(label, color = Muted, modifier = Modifier.width(90.dp), fontSize = 13.sp); Text(value, color = White, fontSize = 13.sp, modifier = Modifier.weight(1f)) }; HorizontalDivider(color = Color(0xFF1D2A40)) }

@Composable private fun FavoritesScreen(m: ScoresModel) {
    Text("YOUR SAVED MATCHUPS", color = White, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(18.dp))
    val list = m.games.filter { m.isFavorite(it) }
    if (list.isEmpty()) EmptyPanel("Your shortlist starts here", "Tap the star on any game to save it.")
    else LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { items(list, key = { it.id }) { GameCard(it, true, { m.toggleFavorite(it) }) { m.open(it) } } }
}
@Composable private fun NewsScreen(m: ScoresModel) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { Text("THE LATEST", color = White, fontSize = 22.sp, fontWeight = FontWeight.Bold); Text("MLB • NBA • NFL headlines", color = Muted, fontSize = 12.sp) }
            IconButton(onClick = { m.loadNews() }) { Icon(Icons.Default.Refresh, "Refresh news", tint = Cyan) }
        }
        if (m.newsLoading && m.articles.isEmpty()) Box(Modifier.fillMaxWidth().padding(30.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Cyan) }
        if (m.newsError != null && m.articles.isEmpty()) ErrorPanel(m.newsError!!) { m.loadNews() }
        LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(m.articles, key = { it.league.name + it.headline }) { article ->
                Surface(color = Panel, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth().clickable { if (article.url.isNotBlank()) appNewsContext?.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(article.url)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }) {
                    Column(Modifier.padding(16.dp)) {
                        Text("${article.league.mark}  ${article.league.label} NEWS", color = Cyan, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(7.dp)); Text(article.headline, color = White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        if (article.description.isNotBlank()) { Spacer(Modifier.height(5.dp)); Text(article.description, color = Muted, fontSize = 12.sp, maxLines = 3, overflow = TextOverflow.Ellipsis) }
                        if (article.url.isNotBlank()) { Spacer(Modifier.height(8.dp)); Text("READ STORY ↗", color = Cyan, fontSize = 10.sp, fontWeight = FontWeight.Bold) }
                    }
                }
            }
        }
    }
}
private var appNewsContext: android.content.Context? = null

@Composable private fun StandingsScreen(m: ScoresModel) {
    Column(Modifier.fillMaxSize().padding(18.dp)) {
        Text("LEAGUE CENTER", color = White, fontSize = 22.sp, fontWeight = FontWeight.Bold); Text("Schedule activity across your leagues", color = Muted, fontSize = 12.sp); Spacer(Modifier.height(16.dp))
        if (m.loading) CircularProgressIndicator(color = Cyan)
        if (m.error != null) ErrorPanel(m.error!!) { m.loadStandings() }
        m.standings.forEach { s -> Surface(color = Panel, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp)) { Text(s, color = White, fontSize = 14.sp, modifier = Modifier.padding(16.dp)) } }
        Text("Full division standings aren't exposed by this schedule endpoint. GameDay shows schedule activity here; standings support can be added with a dedicated standings feed.", color = Muted, fontSize = 12.sp)
    }
}
@Composable private fun SettingsScreen(m: ScoresModel) {
    Column(Modifier.fillMaxSize().padding(20.dp)) {
        Text("PREFERENCES", color = White, fontSize = 22.sp, fontWeight = FontWeight.Bold); Text("Make GameDay yours", color = Muted, fontSize = 13.sp); Spacer(Modifier.height(20.dp))
        Surface(color = Panel, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) { Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.Refresh, null, tint = Cyan); Spacer(Modifier.width(12.dp)); Column(Modifier.weight(1f)) { Text("Auto-refresh scores", color = White, fontWeight = FontWeight.SemiBold); Text("Update game data every 60 seconds", color = Muted, fontSize = 11.sp) }; Switch(checked = m.autoRefresh, onCheckedChange = { m.toggleRefresh() }) } }
        Spacer(Modifier.height(12.dp)); Surface(color = Panel, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) { Text("DATA SOURCE", color = Cyan, fontSize = 11.sp, fontWeight = FontWeight.Bold); Spacer(Modifier.height(6.dp)); Text("ESPN public scoreboard API", color = White); Text("Internet connection required. Data availability and timing depend on the provider.", color = Muted, fontSize = 12.sp) } }
        Spacer(Modifier.height(12.dp)); Surface(color = Panel, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) { Text("ABOUT GAME DAY", color = Cyan, fontSize = 11.sp, fontWeight = FontWeight.Bold); Spacer(Modifier.height(6.dp)); Text("Version 1.0.0 • Built for sports fans", color = White); Text("Scores, schedules, and saved games in one place.", color = Muted, fontSize = 12.sp) } }
    }
}
@Composable private fun EmptyPanel(title: String, body: String) { Column(Modifier.fillMaxWidth().padding(34.dp), horizontalAlignment = Alignment.CenterHorizontally) { Icon(Icons.Default.EventBusy, null, tint = Muted, modifier = Modifier.size(40.dp)); Spacer(Modifier.height(12.dp)); Text(title, color = White, fontWeight = FontWeight.Bold); Spacer(Modifier.height(5.dp)); Text(body, color = Muted, fontSize = 12.sp) } }
@Composable private fun ErrorPanel(message: String, retry: () -> Unit) { Column(Modifier.fillMaxWidth().padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally) { Icon(Icons.Default.CloudOff, null, tint = Color(0xFFFF6676), modifier = Modifier.size(32.dp)); Spacer(Modifier.height(8.dp)); Text(message, color = White, fontSize = 13.sp); Spacer(Modifier.height(12.dp)); Button(onClick = retry) { Text("Try again") } } }
private fun dayLabel(offset: Int): String { val c = Calendar.getInstance(); c.add(Calendar.DAY_OF_YEAR, offset); return SimpleDateFormat("EEEE, MMM d", Locale.getDefault()).format(c.time) }
