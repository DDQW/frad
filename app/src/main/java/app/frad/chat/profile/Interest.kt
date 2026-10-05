package app.frad.chat.profile

/**
 * Interests someone can show on their profile - a fixed list rather than free text, so they can't
 * carry contact details or anything else a stranger shouldn't send, and so two people's choices
 * can be compared. Each comes with a question to start a chat with when both picked it.
 */
enum class Interest(val key: String, val label: String, val icebreaker: String) {
    MUSIC("music", "Music", "What's the last song you had on repeat?"),
    MOVIES("movies", "Movies & series", "Watched anything good lately?"),
    BOOKS("books", "Books", "What are you reading right now?"),
    GAMING("gaming", "Gaming", "What are you playing at the moment?"),
    SPORTS("sports", "Sports", "Which sport - watching or playing?"),
    FITNESS("fitness", "Fitness", "Gym, running or something else?"),
    OUTDOORS("outdoors", "Outdoors", "Best place around here to get some fresh air?"),
    TRAVEL("travel", "Travel", "Where was your last trip?"),
    FOOD("food", "Food & cooking", "What's the best thing you've eaten this week?"),
    COFFEE("coffee", "Coffee", "Best coffee around here?"),
    ART("art", "Art", "Do you make art yourself, or mostly look at it?"),
    PHOTOGRAPHY("photography", "Photography", "Phone or real camera?"),
    TECH("tech", "Tech", "What's the coolest gadget you own?"),
    SCIENCE("science", "Science", "Read anything that blew your mind recently?"),
    LANGUAGES("languages", "Languages", "Which languages do you speak - or want to?"),
    PETS("pets", "Pets", "Cat person or dog person?"),
    DANCING("dancing", "Dancing", "What do you like to dance to?"),
    BOARD_GAMES("boardgames", "Board games", "Favourite board game?"),
    FASHION("fashion", "Fashion", "What's your style?"),
    CARS("cars", "Cars & bikes", "Dream car or bike?");

    companion object {
        const val MAX_PER_PROFILE = 5

        fun fromKey(key: String): Interest? = entries.firstOrNull { it.key == key }

        /** Openers for an empty chat: the shared interests' questions first, then general ones. */
        fun icebreakers(common: Set<Interest>, count: Int = 3): List<String> =
            (common.sortedBy { it.ordinal }.map { it.icebreaker } + GENERAL).distinct().take(count)

        private val GENERAL = listOf(
            "What brings you out here today?",
            "Best thing that happened to you this week?",
            "If you could be anywhere right now, where?",
            "What's something you're looking forward to?",
        )
    }
}
