package app.frad.chat.profile

import androidx.annotation.StringRes
import app.frad.chat.R

/**
 * Interests someone can show on their profile - a fixed list rather than free text, so they can't
 * carry contact details or anything else a stranger shouldn't send, and so two people's choices
 * can be compared. Each comes with a question to start a chat with when both picked it.
 */
enum class Interest(val key: String, @StringRes val labelRes: Int, @StringRes val icebreakerRes: Int) {
    MUSIC("music", R.string.interest_music, R.string.icebreaker_music),
    MOVIES("movies", R.string.interest_movies, R.string.icebreaker_movies),
    BOOKS("books", R.string.interest_books, R.string.icebreaker_books),
    GAMING("gaming", R.string.interest_gaming, R.string.icebreaker_gaming),
    SPORTS("sports", R.string.interest_sports, R.string.icebreaker_sports),
    FITNESS("fitness", R.string.interest_fitness, R.string.icebreaker_fitness),
    OUTDOORS("outdoors", R.string.interest_outdoors, R.string.icebreaker_outdoors),
    TRAVEL("travel", R.string.interest_travel, R.string.icebreaker_travel),
    FOOD("food", R.string.interest_food, R.string.icebreaker_food),
    COFFEE("coffee", R.string.interest_coffee, R.string.icebreaker_coffee),
    ART("art", R.string.interest_art, R.string.icebreaker_art),
    PHOTOGRAPHY("photography", R.string.interest_photography, R.string.icebreaker_photography),
    TECH("tech", R.string.interest_tech, R.string.icebreaker_tech),
    SCIENCE("science", R.string.interest_science, R.string.icebreaker_science),
    LANGUAGES("languages", R.string.interest_languages, R.string.icebreaker_languages),
    PETS("pets", R.string.interest_pets, R.string.icebreaker_pets),
    DANCING("dancing", R.string.interest_dancing, R.string.icebreaker_dancing),
    BOARD_GAMES("boardgames", R.string.interest_boardgames, R.string.icebreaker_boardgames),
    FASHION("fashion", R.string.interest_fashion, R.string.icebreaker_fashion),
    CARS("cars", R.string.interest_cars, R.string.icebreaker_cars);

    companion object {
        const val MAX_PER_PROFILE = 5

        fun fromKey(key: String): Interest? = entries.firstOrNull { it.key == key }

        /** Openers for an empty chat (string resources): the shared interests' questions first,
         *  then general ones. */
        fun icebreakers(common: Set<Interest>, count: Int = 3): List<Int> =
            (common.sortedBy { it.ordinal }.map { it.icebreakerRes } + GENERAL).distinct().take(count)

        private val GENERAL = listOf(
            R.string.icebreaker_general_1,
            R.string.icebreaker_general_2,
            R.string.icebreaker_general_3,
            R.string.icebreaker_general_4,
        )
    }
}
