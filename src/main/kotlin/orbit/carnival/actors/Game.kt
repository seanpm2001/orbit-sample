package orbit.carnival.actors

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.readValue
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async
import orbit.client.actor.AbstractActor
import orbit.client.actor.ActorWithStringKey
import orbit.client.addressable.DeactivationReason
import orbit.client.addressable.OnActivate
import orbit.client.addressable.OnDeactivate
import orbit.shared.addressable.Key
import orbit.carnival.actors.repository.GameStore
import orbit.carnival.actors.repository.toRecord
import kotlin.random.Random

interface Game : ActorWithStringKey {
    fun play(playerId: String): Deferred<PlayedGameResult>
    fun loadData(): Deferred<GameData>
}

class GameImpl(private val gameStore: GameStore) : AbstractActor(), Game {
    val id: String get() = (this.context.reference.key as Key.StringKey).key

    private lateinit var gameData: Catalog.Game

    private val baseWinningOdds = .5

    internal var results = mutableListOf<PlayedGameResult>()

    @OnActivate
    fun onActivate(): Deferred<Unit> = GlobalScope.async {
        println("Activating game ${id}")
        loadFromStore()
    }

    @OnDeactivate
    fun onDeactivate(deactivationReason: DeactivationReason): Deferred<Unit> = GlobalScope.async {
        println("Deactivating game ${id} because ${deactivationReason}")

        saveToStore()
    }

    suspend fun loadFromStore() {
        gameData = catalog.games.firstOrNull() { game ->
            game.id == (context.reference.key as Key.StringKey).key
        } ?: throw IllegalArgumentException("Game does not exist")

        val savedGame = gameStore.get(id)

        this.results = savedGame?.results?.toMutableList() ?: mutableListOf()
    }

    suspend fun saveToStore() {
        gameStore.put(this.toRecord())
    }

    companion object {
        @JvmStatic
        var catalog: Catalog

        init {
            val catalogContent = this::class.java.getResource("/games.yml").readText()
            catalog = ObjectMapper(YAMLFactory()).readValue(catalogContent)
        }
    }

    override fun play(playerId: String): Deferred<PlayedGameResult> = GlobalScope.async {

        val previousResult = results.lastOrNull()
        val replay = previousResult?.playerId == playerId && previousResult.level < 4
        var level = if (replay) previousResult!!.level else 0

        val win = Random.nextDouble() < (baseWinningOdds / (level + 1))
        if (win) level++

        val prize = if (win)
            (when (level) {
                1 -> gameData.prizes.small
                2 -> gameData.prizes.medium
                3 -> gameData.prizes.large
                4 -> gameData.prizes.grand
                else -> listOf()
            }).random() else ""

        val result = PlayedGameResult(
            name = gameData.name,
            playerId = playerId,
            winner = win,
            reward = prize,
            prizeLevel = if (win) (when (level) {
                1 -> "small"
                2 -> "medium"
                3 -> "large"
                4 -> "grand"
                else -> ""
            }) else "",
            level = level
        )

        results.add(result)

        saveToStore()
        return@async result
    }


    override fun loadData(): Deferred<GameData> = GlobalScope.async {
        GameData(
            name = gameData.name,
            timesPlayed = results.count()
        )
    }
}

data class Catalog(val games: List<Game> = listOf()) {
    data class Game(
        val id: String = "",
        val name: String = "",
        val theme: String = "",
        val prizes: PrizeList = PrizeList()
    )

    data class PrizeList(
        val small: List<String> = listOf(),
        val medium: List<String> = listOf(),
        val large: List<String> = listOf(),
        val grand: List<String> = listOf()
    )
}

data class GameData(
    val name: String,
    val timesPlayed: Int
)

data class PlayedGameResult(
    val name: String,
    val playerId: String,
    val winner: Boolean,
    val level: Int,
    val prizeLevel: String,
    val reward: String
)
