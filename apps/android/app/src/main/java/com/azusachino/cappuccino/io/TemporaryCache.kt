package com.azusachino.cappuccino.io

import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/** Only the supplied app cache directory; never follow links or touch preference/draft stores. */
fun temporaryCacheBytes(directory: File, clear: Boolean = false): Long {
  val root = directory.toPath()
  require(!Files.isSymbolicLink(root)) { "Cache root must not be a symbolic link" }
  if (!Files.exists(root)) return 0
  var bytes = 0L
  Files.walkFileTree(
    root,
    object : SimpleFileVisitor<Path>() {
      override fun visitFile(file: Path, attributes: BasicFileAttributes): FileVisitResult {
        if (attributes.isRegularFile) bytes += attributes.size()
        if (clear) Files.delete(file)
        return FileVisitResult.CONTINUE
      }

      override fun postVisitDirectory(directory: Path, error: IOException?): FileVisitResult {
        if (error != null) throw error
        if (clear && directory != root) Files.delete(directory)
        return FileVisitResult.CONTINUE
      }
    },
  )
  return bytes
}
