package com.tyranor.next.core.game.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files

class ImpactoDescriptorTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    private fun parse(text: String) = ImpactoDescriptor.parse(text.toByteArray(Charsets.UTF_8))

    @Test fun acceptsEitherKeyOrderAndJsonWhitespace() {
        assertEquals(ImpactoDescriptor("mo6tw"), parse("{\"version\":1,\"profile\":\"mo6tw\"}"))
        assertEquals(ImpactoDescriptor("cclcc-switch"), parse(" \n{\"profile\":\"cclcc-switch\", \"version\" : 1 }\t"))
        assertEquals(ImpactoDescriptor("mo6tw"), parse("{\"version\":1,\"profile\":\"mo\\u0036tw\"}"))
    }

    @Test fun rejectsCoercedVersionsAndNonStringProfiles() {
        listOf("0", "2", "-1", "+1", "01", "1.0", "1e0", "\"1\"", "true", "null").forEach { version ->
            assertNull(version, parse("{\"version\":$version,\"profile\":\"mo6tw\"}"))
        }
        listOf("1", "null", "{}", "[]", "true").forEach { profile ->
            assertNull(profile, parse("{\"version\":1,\"profile\":$profile}"))
        }
    }

    @Test fun rejectsDuplicateUnknownMissingAndTrailingFields() {
        listOf(
            "{}", "{\"version\":1}", "{\"profile\":\"mo6tw\"}",
            "{\"version\":1,\"version\":1}",
            "{\"profile\":\"mo6tw\",\"profile\":\"mo6tw\"}",
            "{\"version\":1,\"profile\":\"mo6tw\",\"profile\":\"darling\"}",
            "{\"version\":1,\"path\":\"mo6tw\"}",
            "{\"version\":1,\"profile\":\"mo6tw\"} {}",
            "{\"version\":1,\"profile\":\"mo6tw\",}",
            "{version:1,profile:'mo6tw'}",
            "{/* comment */\"version\":1,\"profile\":\"mo6tw\"}",
            "{\"version\":1,\"profile\":\"mo6tw\"}//comment",
        ).forEach { assertNull(it, parse(it)) }
    }

    @Test fun rejectsUnsupportedProfilesAndEscapedInjection() {
        listOf("MO6TW", "mo7", "modelviewer", "characterviewer", "../mo6tw", "mo6tw/game.lua", "--game", "", "mo6tw\\n", "mo6tw\\u0000", "mo6tw\\q", "\\ud800", "mo6tw\n").forEach {
            assertNull(it, parse("{\"version\":1,\"profile\":\"$it\"}"))
        }
    }

    @Test fun rejectsInvalidUtf8AndBom() {
        assertNull(ImpactoDescriptor.parse(byteArrayOf(0xc0.toByte(), 0xaf.toByte())))
        assertNull(parse("\uFEFF{\"version\":1,\"profile\":\"mo6tw\"}"))
    }

    @Test fun boundsBytesNotDecodedCharacters() {
        val valid = "{\"version\":1,\"profile\":\"mo6tw\"}"
        assertEquals(ImpactoDescriptor("mo6tw"), parse(valid.padEnd(ImpactoDescriptor.MAX_BYTES)))
        assertNull(parse(valid.padEnd(ImpactoDescriptor.MAX_BYTES + 1)))
        assertNull(ImpactoDescriptor.parse(byteArrayOf()))
    }

    @Test fun launchRereadHandlesUnicodeDirectoriesAndChanges() {
        val root = temporaryFolder.newFolder("空間 ' quoted")
        val descriptor = root.resolve(ImpactoDescriptor.FILE_NAME)
        descriptor.writeText("{\"version\":1,\"profile\":\"mo6tw\"}")
        assertEquals(ImpactoDescriptor("mo6tw"), ImpactoDescriptor.read(root))
        descriptor.writeText("{\"version\":1,\"profile\":\"darling\"}")
        assertEquals(ImpactoDescriptor("darling"), ImpactoDescriptor.read(root))
        descriptor.writeText(" ".repeat(ImpactoDescriptor.MAX_BYTES + 1))
        assertNull(ImpactoDescriptor.read(root))
    }

    @Test fun missingDirectoryAndSymlinkDescriptorFailClosed() {
        val root = temporaryFolder.newFolder()
        assertNull(ImpactoDescriptor.read(root))
        assertNull(ImpactoDescriptor.read(root.resolve("missing")))
        val outside = temporaryFolder.newFile("outside.json")
        outside.writeText("{\"version\":1,\"profile\":\"mo6tw\"}")
        try {
            Files.createSymbolicLink(root.resolve(ImpactoDescriptor.FILE_NAME).toPath(), outside.toPath())
        } catch (unsupported: UnsupportedOperationException) {
            org.junit.Assume.assumeNoException(unsupported)
            return
        } catch (unsupported: java.io.IOException) {
            org.junit.Assume.assumeNoException(unsupported)
            return
        }
        assertNull(ImpactoDescriptor.read(root))
    }
}
