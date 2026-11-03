package com.github.ssquadteam.kscaler

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ProfileTest {
    @Test
    fun `profile survives encode and decode`() {
        val profile = Profile(Fit.NARROW, 215, 1.081)
        assertEquals(profile, Profile.decode(profile.encode()))
    }

    @Test
    fun `broken values are ignored`() {
        assertNull(Profile.decode("NARROW|abc|1.0"))
        assertNull(Profile.decode("SIDEWAYS|200|1.0"))
        assertNull(Profile.decode(""))
    }
}
