package com.maxrave.data.io

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.driver.bundled.SQLITE_OPEN_READONLY
import androidx.sqlite.driver.bundled.SQLITE_OPEN_READWRITE
import androidx.sqlite.execSQL
import com.maxrave.common.DB_NAME
import com.maxrave.common.DESKTOP_DATA_DIR
import com.maxrave.common.LEGACY_DESKTOP_DATA_DIR
import com.maxrave.common.MERGING_DATA_TYPE
import com.maxrave.data.db.MUSIC_DATABASE_VERSION
import com.maxrave.domain.data.entities.DownloadState
import com.maxrave.logger.Logger
import java.io.File
import java.io.IOException
import java.nio.file.FileSystemLoopException
import java.nio.file.FileVisitOption
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.EnumSet
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively

private const val TAG = "LegacyDesktopDataImport"
private const val DOWNLOADS_DIR = "downloads"

/** SimpMusic's own log; left behind, or Musikku's App log screen and export would open on it. */
private const val LOGS_DIR = "logs"

/** `Ytmusic.parallelDownload`'s chunks of a download still in progress; useless to Musikku. */
private const val DOWNLOAD_CHUNKS_PREFIX = "temp_chunks_"

/** How long a snapshot waits on a lock held by a running SimpMusic before giving up. */
private const val BUSY_TIMEOUT_MS = 5_000

sealed interface LegacyImportResult {
    /** Musikku already has its own data, or there is no SimpMusic data to bring over. */
    data object Skipped : LegacyImportResult

    data object Imported : LegacyImportResult

    /**
     * SimpMusic's database was written by a newer schema than this build knows. Room cannot
     * downgrade, so importing it would crash every launch — nothing was imported.
     */
    data class NewerSchema(
        val found: Long,
        val supported: Int,
    ) : LegacyImportResult

    /** Nothing was imported; the next launch tries again unless the caller lets Koin run. */
    data class Failed(
        val cause: Throwable,
    ) : LegacyImportResult
}

private class NewerSchemaException(
    val found: Long,
) : Exception("Database schema $found is newer than $MUSIC_DATABASE_VERSION")

/**
 * Copies upstream SimpMusic's desktop data (`~/.simpmusic`) into Musikku's own folder
 * (`~/.musikku`) the first time Musikku runs, so the library comes along without the two apps
 * ever sharing a file. SimpMusic's folder is only ever read.
 *
 * Must run BEFORE Koin starts: the DataStore singleton is created at start and would create an
 * empty `~/.musikku`, after which this import never runs. For the same reason a caller that gets
 * anything other than [LegacyImportResult.Imported] or [LegacyImportResult.Skipped] must decide
 * before starting Koin: starting it gives up on the import for good, not quitting keeps it.
 *
 * SimpMusic may be running while this happens, so nothing is read as a raw file that it could be
 * halfway through writing:
 * - The database is taken with `VACUUM INTO`, a consistent snapshot under SQLite's own locking,
 *   rather than by copying the db, `-wal` and `-shm` files, which could land from different
 *   moments. VACUUM keeps `user_version`, so Room sees the same schema version.
 * - Everything is built in `~/.musikku.importing` and renamed into place only once complete, so
 *   a crash or a failure mid-way leaves nothing half-imported.
 *
 * Downloads are hard-linked, because they can run to gigabytes. One that cannot be linked is left
 * out and marked not downloaded rather than copied (see [hardLink]). Linking is safe only because
 * nothing rewrites a download in place: both of `Ytmusic.download`'s paths delete the old name
 * before they write it, which unlinks it and leaves the other app's copy alone. Any new writer
 * into `downloads/` must do the same; opening an existing file for writing would rewrite both
 * apps' copies. Every other file is copied: some (the yt-dlp cookie file, for one) are rewritten
 * in place, and a hard link would let one app's write change the other's.
 *
 * Links are followed, so a linked folder or file arrives as a real one: nothing in Musikku's
 * folder leads back into SimpMusic's, or to anything SimpMusic's links point at.
 */
fun importLegacyDesktopData(): LegacyImportResult {
    val home = File(System.getProperty("user.home"))
    val target = File(home, DESKTOP_DATA_DIR)
    val legacy = File(home, LEGACY_DESKTOP_DATA_DIR)
    if (target.exists() || !legacy.isDirectory) return LegacyImportResult.Skipped

    val staging = File(home, "$DESKTOP_DATA_DIR.importing")
    return try {
        deleteStaging(staging)
        val legacyDb = File(legacy, "db${File.separator}$DB_NAME")
        val unlinked = copyTree(legacy.toPath(), staging.toPath(), skip = databaseFiles(legacyDb))
        if (legacyDb.isFile) {
            val stagedDb = File(staging, "db${File.separator}$DB_NAME")
            stagedDb.parentFile.mkdirs()
            snapshotDatabase(legacyDb, stagedDb)
            val version = schemaVersion(stagedDb)
            if (version > MUSIC_DATABASE_VERSION) throw NewerSchemaException(version)
            repointFileUris(stagedDb, from = legacy, to = target)
            markNotDownloaded(stagedDb, unlinked)
        }
        Files.move(staging.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
        Logger.i(TAG, "Imported $legacy into $target")
        LegacyImportResult.Imported
    } catch (e: NewerSchemaException) {
        Logger.w(TAG, e.message.orEmpty())
        deleteStaging(staging)
        LegacyImportResult.NewerSchema(found = e.found, supported = MUSIC_DATABASE_VERSION)
    } catch (e: Exception) {
        Logger.e(TAG, "Could not import $legacy", e)
        deleteStaging(staging)
        LegacyImportResult.Failed(e)
    }
}

/**
 * Removes [staging] without following the links inside it. [File.deleteRecursively] descends
 * into a linked folder and deletes what is IN it. [copyTree] no longer copies links as links,
 * but a staging folder left by an earlier build can hold one — a `downloads` linked to a music
 * library would be emptied by the cleanup. The [Path] version deletes the link and leaves its
 * target alone.
 *
 * Never throws: two of its three callers are failure paths, and an exception there would escape
 * [importLegacyDesktopData] before Koin starts, taking the launch down with it.
 */
@OptIn(ExperimentalPathApi::class)
private fun deleteStaging(staging: File) {
    try {
        staging.toPath().deleteRecursively()
    } catch (e: Exception) {
        Logger.w(TAG, "Could not remove $staging: ${e.message}")
    }
}

/** The database and its sidecars, which [snapshotDatabase] replaces with one consistent file. */
private fun databaseFiles(db: File): Set<Path> =
    listOf("", "-wal", "-shm", "-journal").map { File(db.path + it).toPath() }.toSet()

/**
 * Copies [from] into [to], following links. Copying a link as a link would share its target
 * between the apps: a linked `db` would put the snapshot on top of SimpMusic's own database, and
 * a linked `downloads` would let Musikku delete SimpMusic's files.
 *
 * Returns the downloads [hardLink] could not link, which are left out.
 */
private fun copyTree(
    from: Path,
    to: Path,
    skip: Set<Path>,
): Set<Path> {
    val downloads = from.resolve(DOWNLOADS_DIR)
    val logs = from.resolve(LOGS_DIR)
    val unlinked = mutableSetOf<Path>()
    Files.walkFileTree(
        from,
        EnumSet.of(FileVisitOption.FOLLOW_LINKS),
        Int.MAX_VALUE,
        object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(
                dir: Path,
                attrs: BasicFileAttributes,
            ): FileVisitResult {
                if (dir == logs) return FileVisitResult.SKIP_SUBTREE
                if (dir.parent == downloads && dir.fileName.toString().startsWith(DOWNLOAD_CHUNKS_PREFIX)) {
                    return FileVisitResult.SKIP_SUBTREE
                }
                Files.createDirectories(to.resolve(from.relativize(dir)))
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(
                file: Path,
                attrs: BasicFileAttributes,
            ): FileVisitResult {
                if (file in skip) return FileVisitResult.CONTINUE
                val dest = to.resolve(from.relativize(file))
                try {
                    if (file.startsWith(downloads)) {
                        if (!hardLink(file, dest)) unlinked.add(file)
                    } else {
                        Files.copy(file, dest, StandardCopyOption.COPY_ATTRIBUTES)
                    }
                } catch (_: NoSuchFileException) {
                    // Deleted by a running SimpMusic between the walk and the copy (a DataStore
                    // temp file, say), or a broken link. Nothing to import.
                }
                return FileVisitResult.CONTINUE
            }

            override fun visitFileFailed(
                file: Path,
                exc: IOException,
            ): FileVisitResult =
                when (exc) {
                    // Gone between listing its folder and reading it, e.g. renamed by a running
                    // SimpMusic. Nothing to import.
                    is NoSuchFileException -> FileVisitResult.CONTINUE
                    // A link to a folder above itself; following it would never end.
                    is FileSystemLoopException -> FileVisitResult.CONTINUE
                    else -> throw exc
                }
        },
    )
    return unlinked
}

/**
 * Hard-links a download into Musikku's folder, or returns false if the filesystem will not (another
 * volume, or one without hard links). Such a download is left out rather than copied: downloads
 * can run to gigabytes, and this runs before any window is up, so a copy would look like a launch
 * that never finishes. SimpMusic keeps its copy, and Musikku can download the song again.
 */
private fun hardLink(
    source: Path,
    dest: Path,
): Boolean {
    // The real file: link() does not follow links, so a linked download would otherwise become a
    // second link to the same target, shared between the apps.
    val real = source.toRealPath()
    return try {
        Files.createLink(dest, real)
        true
    } catch (_: Exception) {
        false
    }
}

/**
 * Read-only first, so SimpMusic's files are left untouched: a read-write connection that closes
 * last checkpoints and trims SimpMusic's `-wal`. A read-only connection cannot open a WAL
 * database whose `-shm` is missing, though, so that one case — and only that one — falls back to
 * read-write. Any other failure (a lock held past the busy timeout, a damaged file) fails the
 * import instead of opening SimpMusic's database for writing.
 */
private fun snapshotDatabase(
    source: File,
    dest: File,
) {
    // [dest] is new in the staging folder; one that exists was reached through a link into
    // SimpMusic's folder, and the fallback below would delete it.
    check(!dest.exists()) { "$dest already exists" }
    try {
        vacuumInto(source, dest, SQLITE_OPEN_READONLY)
    } catch (e: Exception) {
        if (File(source.path + "-shm").exists()) throw e
        Logger.w(TAG, "Read-only snapshot failed (${e.message}); retrying read-write")
        dest.delete() // VACUUM INTO refuses an existing file.
        vacuumInto(source, dest, SQLITE_OPEN_READWRITE)
    }
}

private fun vacuumInto(
    source: File,
    dest: File,
    flags: Int,
) {
    val connection = BundledSQLiteDriver().open(source.absolutePath, flags)
    try {
        // Without it a write lock held by a running SimpMusic fails the snapshot at once.
        connection.execSQL("PRAGMA busy_timeout = $BUSY_TIMEOUT_MS")
        connection.execSQL("VACUUM INTO '${dest.absolutePath.replace("'", "''")}'")
    } finally {
        connection.close()
    }
}

private fun schemaVersion(db: File): Long {
    val connection = BundledSQLiteDriver().open(db.absolutePath, SQLITE_OPEN_READONLY)
    try {
        val statement = connection.prepare("PRAGMA user_version")
        try {
            return if (statement.step()) statement.getLong(0) else 0L
        } finally {
            statement.close()
        }
    } finally {
        connection.close()
    }
}

/**
 * Downloads [copyTree] left out are still marked downloaded in the snapshot; marking them not
 * downloaded lets Musikku offer them again instead of failing to play a file it does not have.
 * The video id is read off the file name as `DownloadUtils.removeAllDownloads` reads it.
 */
private fun markNotDownloaded(
    db: File,
    files: Set<Path>,
) {
    if (files.isEmpty()) return
    val videoIds =
        files.mapTo(mutableSetOf()) {
            it.fileName
                .toString()
                .substringBefore('.')
                .removePrefix(MERGING_DATA_TYPE.VIDEO)
        }
    Logger.w(TAG, "Left out ${files.size} downloads that could not be hard-linked")
    val connection = BundledSQLiteDriver().open(db.absolutePath, SQLITE_OPEN_READWRITE)
    try {
        connection.execSQL("BEGIN")
        val statement = connection.prepare("UPDATE song SET downloadState = ? WHERE videoId = ?")
        try {
            for (videoId in videoIds) {
                statement.bindLong(1, DownloadState.STATE_NOT_DOWNLOADED.toLong())
                statement.bindText(2, videoId)
                statement.step()
                statement.reset()
            }
        } finally {
            statement.close()
        }
        connection.execSQL("COMMIT")
    } finally {
        connection.close()
    }
}

/**
 * Custom playlist covers are stored as absolute `file:` URIs into the data folder, so after the
 * copy they would still point at SimpMusic's folder — and break the day it is deleted. They are
 * the only data-folder path the app persists; everything else is derived from `fileDir()` at
 * run time. Not fatal: a failure here costs a few covers, not the import.
 */
private fun repointFileUris(
    db: File,
    from: File,
    to: File,
) {
    val oldPrefix = from.dirUri()
    val newPrefix = to.dirUri()
    var connection: SQLiteConnection? = null
    try {
        connection = BundledSQLiteDriver().open(db.absolutePath, SQLITE_OPEN_READWRITE)
        // substr() rather than LIKE: `_` is a LIKE wildcard and home folders can contain one.
        val statement =
            connection.prepare(
                "UPDATE local_playlist SET thumbnail = ? || substr(thumbnail, ?) " +
                    "WHERE substr(thumbnail, 1, ?) = ?",
            )
        try {
            statement.bindText(1, newPrefix)
            statement.bindLong(2, oldPrefix.length + 1L)
            statement.bindLong(3, oldPrefix.length.toLong())
            statement.bindText(4, oldPrefix)
            statement.step()
        } finally {
            statement.close()
        }
    } catch (e: Exception) {
        Logger.w(TAG, "Could not repoint playlist covers to $to: ${e.message}")
    } finally {
        connection?.close()
    }
}

/** `file:` URI of a directory, always ending in `/` — [File.toURI] adds it only if it exists. */
private fun File.dirUri(): String = toURI().toString().let { if (it.endsWith("/")) it else "$it/" }
