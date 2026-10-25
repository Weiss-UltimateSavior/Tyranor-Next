package com.tyranor.next.core.game.save.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SavePublicationPolicyTest {
    private val selectedHash = "1".repeat(64)
    private val playedHash = "2".repeat(64)
    private val unrelatedHash = "3".repeat(64)

    @Test
    fun selectedSThenPlayedNPublishesNAndSupersedesOnlyItsHistory() {
        val selected = remote("selected-S", selectedHash, 100)
        val oldPlayed = remote("old-N", playedHash, 200)
        val unrelated = remote("unrelated", unrelatedHash, 150)
        val newerConcurrent = remote("newer-N", playedHash, 400)
        val publication = SaveVersion("promotion-N", playedHash, 300, 10)
        val exactPublication = remote(publication.id, playedHash, publication.createdAt)
        val playedVersion = SaveVersion("local-N", playedHash, 250, 10)

        assertTrue(
            SavePublicationPolicy.needsUpload(
                playedVersion,
                uploadedHash = selectedHash,
                promoting = true,
                remote = listOf(selected, oldPlayed),
            ),
        )
        assertFalse(
            SavePublicationPolicy.needsUpload(
                playedVersion,
                uploadedHash = playedHash,
                promoting = false,
                remote = listOf(newerConcurrent),
            ),
        )
        assertEquals(
            setOf(selected.name, oldPlayed.name),
            SavePublicationPolicy.superseded(
                listOf(selected, oldPlayed, unrelated, newerConcurrent, exactPublication),
                publication,
                fromHash = selectedHash,
            ).map { it.name }.toSet(),
        )
    }

    @Test
    fun missingCloudCopyRepairsCheckpointButPresentCopyDoesNotRepublish() {
        val version = SaveVersion("local-N", playedHash, 300, 10)
        val unrelated = remote("unrelated", unrelatedHash, 100)
        val present = remote("cloud-N", playedHash, 250)

        assertTrue(
            SavePublicationPolicy.needsUpload(
                version,
                uploadedHash = playedHash,
                promoting = false,
                remote = listOf(unrelated),
            ),
        )
        assertFalse(
            SavePublicationPolicy.needsUpload(
                version,
                uploadedHash = playedHash,
                promoting = false,
                remote = listOf(unrelated, present),
            ),
        )
    }

    @Test
    fun concurrentCopiesAfterPublicationCutoffArePreserved() {
        val publication = SaveVersion("promotion-N", playedHash, 300, 10)
        val selectedAtCutoff = remote("selected-at-cutoff", selectedHash, 300)
        val playedAtCutoff = remote("played-at-cutoff", playedHash, 300)
        val selectedAfterCutoff = remote("selected-after-cutoff", selectedHash, 301)
        val playedAfterCutoff = remote("played-after-cutoff", playedHash, 301)
        val unrelated = remote("unrelated", unrelatedHash, 100)
        val exactPublication = remote(publication.id, playedHash, 100)

        assertEquals(
            setOf(selectedAtCutoff.name, playedAtCutoff.name),
            SavePublicationPolicy.superseded(
                listOf(
                    selectedAtCutoff,
                    playedAtCutoff,
                    selectedAfterCutoff,
                    playedAfterCutoff,
                    unrelated,
                    exactPublication,
                ),
                publication,
                fromHash = selectedHash,
            ).map { it.name }.toSet(),
        )
    }

    private fun remote(name: String, hash: String, createdAt: Long) =
        RemoteSaveVersion(name = name, contentHash = hash, createdAt = createdAt, size = 10)
}
