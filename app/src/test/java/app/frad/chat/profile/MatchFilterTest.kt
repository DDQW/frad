package app.frad.chat.profile

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MatchFilterTest {
    private fun someone(age: Int?, gender: Gender?) = RemoteProfile("x", gender, age, "", null)

    @Test
    fun `no filter accepts everyone`() {
        assertFalse(MatchFilter().isActive)
        assertTrue(MatchFilter().accepts(someone(16, Gender.MALE)))
    }

    @Test
    fun `age bounds are inclusive`() {
        val filter = MatchFilter(minAge = 25, maxAge = 35)
        assertFalse(filter.accepts(someone(24, null)))
        assertTrue(filter.accepts(someone(25, null)))
        assertTrue(filter.accepts(someone(35, null)))
        assertFalse(filter.accepts(someone(36, null)))
    }

    @Test
    fun `gender must match when both are known`() {
        val filter = MatchFilter(gender = Gender.FEMALE)
        assertTrue(filter.accepts(someone(30, Gender.FEMALE)))
        assertFalse(filter.accepts(someone(30, Gender.MALE)))
    }

    @Test
    fun `what someone doesn't share doesn't rule them out`() {
        assertTrue(MatchFilter(minAge = 30, maxAge = 40, gender = Gender.MALE).accepts(someone(null, null)))
    }
}
