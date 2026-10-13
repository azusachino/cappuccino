package com.azusachino.cappuccino.io

import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class TemporaryCacheTest {
  @Test
  fun clearsOnlyTemporaryFilesWithoutFollowingLinksOrRemovingRoot() {
    val workspace = Files.createTempDirectory("cache-test")
    try {
      val cache = Files.createDirectory(workspace.resolve("cache"))
      val preserved = Files.write(workspace.resolve("draft"), byteArrayOf(1, 2, 3))
      Files.write(
        Files.createDirectory(cache.resolve("nested")).resolve("temporary"),
        byteArrayOf(4, 5),
      )
      Files.createSymbolicLink(cache.resolve("external"), preserved)
      assertEquals(2L, temporaryCacheBytes(cache.toFile()))
      assertEquals(2L, temporaryCacheBytes(cache.toFile(), clear = true))
      assertTrue(Files.isDirectory(cache))
      assertArrayEquals(byteArrayOf(1, 2, 3), Files.readAllBytes(preserved))
      assertEquals(0L, temporaryCacheBytes(cache.toFile()))
    } finally {
      workspace.toFile().deleteRecursively()
    }
  }

  @Test
  fun rejectsLinkedCacheRoot() {
    val workspace = Files.createTempDirectory("cache-root-test")
    try {
      val target = Files.createDirectory(workspace.resolve("target"))
      val linked = Files.createSymbolicLink(workspace.resolve("linked"), target)
      assertThrows(IllegalArgumentException::class.java) {
        temporaryCacheBytes(linked.toFile(), clear = true)
      }
      assertTrue(Files.isDirectory(target))
    } finally {
      workspace.toFile().deleteRecursively()
    }
  }
}
