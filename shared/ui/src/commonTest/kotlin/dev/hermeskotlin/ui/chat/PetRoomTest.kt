package dev.hermeskotlin.ui.chat

import androidx.compose.ui.unit.dp
import dev.hermeskotlin.ui.pet.petSize
import kotlin.test.Test
import kotlin.test.assertEquals

class PetRoomTest {

    @Test
    fun thePetIsDesktopsSizeWithinWhatTheComposerCarries() {
        assertEquals(64.dp, petSize(64, 64, 1.0).height)
        // A Desktop sprite at its default scale: 208 × 0.33 tall, as wide as its frame's shape says.
        assertEquals(68.64, petSize(192, 208, 0.33).height.value.toDouble(), 0.01)
        assertEquals(63.36, petSize(192, 208, 0.33).width.value.toDouble(), 0.01)
        // Never taller than 96dp, nor shorter than 48dp.
        assertEquals(96.dp, petSize(100, 200, 1.0).height)
        assertEquals(48.dp, petSize(100, 100, 0.1).width)
    }

    @Test
    fun theLastRepliesActionsMoveLeftByWhatThePetCovers() {
        // The pet stands 28dp in, Copy reaches 48dp in: a 64dp pet needs 44dp more before Copy.
        assertEquals(44.dp, petRoom(64.dp))
        assertEquals(0.dp, petRoom(16.dp))
    }
}
