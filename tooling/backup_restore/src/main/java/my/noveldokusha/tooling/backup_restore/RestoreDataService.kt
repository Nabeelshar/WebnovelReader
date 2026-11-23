package my.noveldokusha.tooling.backup_restore

import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import my.noveldoksuha.coreui.states.NotificationsCenter
import my.noveldoksuha.coreui.states.removeProgressBar
import my.noveldoksuha.coreui.states.text
import my.noveldoksuha.coreui.states.title
import my.noveldoksuha.data.AppRepository
import my.noveldoksuha.data.BookChaptersRepository
import my.noveldoksuha.data.ChapterBodyRepository
import my.noveldoksuha.data.DownloaderRepository
import my.noveldoksuha.data.LibraryBooksRepository
import my.noveldokusha.core.AppCoroutineScope
import my.noveldokusha.core.AppFileResolver
import my.noveldokusha.core.domain.BackgroundWorkTracker
import my.noveldokusha.core.tryAsResponse
import my.noveldokusha.core.utils.Extra_Uri
import my.noveldokusha.core.utils.isServiceRunning
import my.noveldokusha.feature.local_database.AppDatabase
import okhttp3.internal.closeQuietly
import timber.log.Timber
import java.io.File
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import javax.inject.Inject

@AndroidEntryPoint
class RestoreDataService : Service() {
    @Inject
    @ApplicationContext
    lateinit var context: Context

    @Inject
    lateinit var appRepository: AppRepository

    @Inject
    lateinit var appFileResolver: AppFileResolver

    @Inject
    lateinit var notificationsCenter: NotificationsCenter

    @Inject
    lateinit var appCoroutineScope: AppCoroutineScope

    @Inject
    lateinit var downloaderRepository: DownloaderRepository

    private class IntentData : Intent {
        var uri by Extra_Uri()

        constructor(intent: Intent) : super(intent)
        constructor(ctx: Context, uri: Uri) : super(ctx, RestoreDataService::class.java) {
            this.uri = uri
        }
    }

    companion object {
        fun start(ctx: Context, uri: Uri) {
            if (!isRunning(ctx))
                ContextCompat.startForegroundService(ctx, IntentData(ctx, uri))
        }

        private fun isRunning(context: Context): Boolean =
            context.isServiceRunning(RestoreDataService::class.java)
    }

    private val channelName by lazy { getString(R.string.notification_channel_name_restore_backup) }
    private val channelId = "Restore backup"
    private val notificationId = channelId.hashCode()


    private lateinit var notificationBuilder: NotificationCompat.Builder
    private var job: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        notificationBuilder = notificationsCenter.showNotification(
            notificationId = notificationId,
            channelId = channelId,
            channelName = channelName
        )
        startForeground(notificationId, notificationBuilder.build())
    }

    override fun onDestroy() {
        job?.cancel()
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            Timber.e("RestoreDataService: intent is null")
            return START_NOT_STICKY
        }
        val intentData = IntentData(intent)

        if (job?.isActive == true) {
            Timber.w("RestoreDataService: job already active")
            return START_NOT_STICKY
        }
        job = CoroutineScope(Dispatchers.IO).launch {
            // Background work: Cloudflare walls hit while restoring
            // must not pop the verification WebView over the user's
            // reading (see BackgroundWorkTracker).
            BackgroundWorkTracker.enter()
            try {
            tryAsResponse {
                Timber.d("RestoreDataService: Starting restore from URI: ${intentData.uri}")
                restoreData(intentData.uri)
                appRepository.eventDataRestored.emit(Unit)
                Timber.d("RestoreDataService: Restore completed successfully")
            }.onError {
                Timber.e(it.exception, "RestoreDataService: Restore failed with error")
                // Show error notification
                notificationsCenter.showNotification(
                    channelName = channelName,
                    channelId = channelId,
                    notificationId = "Backup restore failure".hashCode()
                ) {
                    removeProgressBar()
                    title = getString(R.string.failed_to_restore_cant_access_file)
                    text = "Error: ${it.exception.message?.take(100) ?: "Unknown error"}"
                }
            }
            } finally {
                BackgroundWorkTracker.exit()
            }

            stopSelf(startId)
        }
        return START_STICKY
    }

    /**
     * Restore data function. Restores the library and images data given an uri.
     * The uri must point to a zip file where there must be a root file
     * "database.sqlite3" and an optional "books" folder where all the images
     * are stored (each subfolder is a book with its own structure).
     *
     * This function assumes the READ_EXTERNAL_STORAGE permission is granted.
     * This function will also show a status notificaton of the restoration progress.
     */
    private suspend fun restoreData(uri: Uri) = withContext(Dispatchers.IO) {

        Timber.d("restoreData: Starting restore process")
        notificationsCenter.modifyNotification(
            notificationBuilder,
            notificationId = notificationId
        ) {
            title = getString(R.string.restore_data)
            text = getString(R.string.loading_data)
            setProgress(100, 0, true)
        }

        Timber.d("restoreData: Opening input stream from URI")
        val inputStream = context.contentResolver.openInputStream(uri)
        if (inputStream == null) {
            Timber.e("restoreData: Failed to open input stream from URI: $uri")
            notificationsCenter.showNotification(
                channelName = channelName,
                channelId = channelId,
                notificationId = "Backup restore failure".hashCode()
            ) {
                text = getString(R.string.failed_to_restore_cant_access_file)
            }
            return@withContext
        }

        // Read first few bytes to verify it's a ZIP file and check file size
        val bufferedStream = inputStream.buffered()
        bufferedStream.mark(8192) // Mark position to reset after checking
        
        val header = ByteArray(4)
        val bytesRead = try {
            bufferedStream.read(header)
        } catch (e: Exception) {
            Timber.e(e, "restoreData: Failed to read file header")
            -1
        }
        
        Timber.d("restoreData: Read $bytesRead bytes for header check")
        if (bytesRead == 4) {
            val headerHex = header.joinToString("") { "%02x".format(it) }
            Timber.d("restoreData: File header: $headerHex (ZIP should be 504b0304 or 504b0506)")
            
            // Check for ZIP signature (PK\x03\x04 or PK\x05\x06)
            val isZip = (header[0] == 0x50.toByte() && header[1] == 0x4B.toByte() && 
                        (header[2] == 0x03.toByte() || header[2] == 0x05.toByte()))
            
            if (!isZip) {
                Timber.e("restoreData: File is not a valid ZIP file. Header: $headerHex")
                notificationsCenter.showNotification(
                    channelName = channelName,
                    channelId = channelId,
                    notificationId = "Backup restore failure - invalid format".hashCode()
                ) {
                    removeProgressBar()
                    text = "Invalid backup file format. Expected ZIP file."
                }
                return@withContext
            }
        } else {
            Timber.e("restoreData: Failed to read file header, only got $bytesRead bytes")
        }
        
        // Reset stream to beginning
        try {
            bufferedStream.reset()
            Timber.d("restoreData: Stream reset to beginning after header check")
        } catch (e: Exception) {
            Timber.e(e, "restoreData: Failed to reset stream")
        }

        Timber.d("restoreData: Reading ZIP entries")
        val zipSequence = try {
            ZipInputStream(bufferedStream).use { zipStream ->
                val entries = mutableMapOf<ZipEntry, ByteArray>()
                var entryCount = 0
                Timber.d("restoreData: Starting to read ZIP entries from stream")
                
                generateSequence { 
                    try {
                        val entry = zipStream.nextEntry
                        if (entry != null) {
                            entryCount++
                            Timber.d("restoreData: Found ZIP entry #$entryCount: ${entry.name}, size: ${entry.size}, compressed: ${entry.compressedSize}, isDirectory: ${entry.isDirectory}")
                        } else {
                            Timber.d("restoreData: nextEntry returned null, end of ZIP stream")
                        }
                        entry
                    } catch (e: Exception) {
                        Timber.e(e, "restoreData: Error reading ZIP entry")
                        null
                    }
                }
                    .filterNotNull()
                    .filterNot { it.isDirectory }
                    .forEach { entry ->
                        try {
                            val bytes = zipStream.readBytes()
                            Timber.d("restoreData: Read ${bytes.size} bytes from ${entry.name}")
                            entries[entry] = bytes
                        } catch (e: Exception) {
                            Timber.e(e, "restoreData: Error reading bytes from ${entry.name}")
                            throw e
                        }
                    }
                Timber.d("restoreData: Finished reading ZIP, found $entryCount total entries, ${entries.size} file entries")
                entries.toMap()
            }
        } catch (e: Exception) {
            Timber.e(e, "restoreData: Failed to read ZIP file")
            notificationsCenter.showNotification(
                channelName = channelName,
                channelId = channelId,
                notificationId = "Backup restore failure - invalid zip".hashCode()
            ) {
                removeProgressBar()
                text = "Failed to read backup file: ${e.message}"
            }
            return@withContext
        }


        suspend fun mergeToDatabase(inputStream: InputStream) {
            tryAsResponse {
                Timber.d("mergeToDatabase: Starting database merge")
                notificationsCenter.modifyNotification(
                    notificationBuilder,
                    notificationId = notificationId
                ) {
                    text = getString(R.string.loading_database)
                }
                
                // Write database bytes to a temporary file
                val tempDbFile = File(context.cacheDir, "temp_restore_database.db")
                try {
                    tempDbFile.outputStream().use { output ->
                        inputStream.copyTo(output)
                    }
                    Timber.d("mergeToDatabase: Wrote database to temp file: ${tempDbFile.absolutePath}, size: ${tempDbFile.length()}")
                } catch (e: Exception) {
                    Timber.e(e, "mergeToDatabase: Failed to write temp database file")
                    throw e
                }
                
                val backupDatabase = object {
                    val newDatabase = try {
                        Timber.d("mergeToDatabase: Opening Room database from file")
                        AppDatabase.createRoom(context, tempDbFile.absolutePath).also { db ->
                            Timber.d("mergeToDatabase: Room database opened successfully, name: ${db.name}")
                        }
                    } catch (e: Exception) {
                        Timber.e(e, "mergeToDatabase: Failed to open Room database")
                        tempDbFile.delete()
                        throw e
                    }
                    val bookChapters = BookChaptersRepository(
                        chapterDao = newDatabase.chapterDao(),
                    )
                    val chapterBody = ChapterBodyRepository(
                        chapterBodyDao = newDatabase.chapterBodyDao(),
                        appDatabase = newDatabase,
                        bookChaptersRepository = bookChapters,
                        downloaderRepository = downloaderRepository
                    )
                    val libraryBooks = LibraryBooksRepository(
                        libraryDao = newDatabase.libraryDao(),
                        appDatabase = newDatabase,
                        context = context,
                        appFileResolver = appFileResolver,
                        appCoroutineScope = appCoroutineScope
                    )
                    fun close() {
                        Timber.d("mergeToDatabase: Closing backup database")
                        newDatabase.closeDatabase()
                    }
                    fun delete() {
                        Timber.d("mergeToDatabase: Deleting backup database and temp file")
                        try {
                            newDatabase.clearDatabase()
                            tempDbFile.delete()
                            // Also delete associated journal files
                            File(tempDbFile.absolutePath + "-shm").delete()
                            File(tempDbFile.absolutePath + "-wal").delete()
                        } catch (e: Exception) {
                            Timber.e(e, "mergeToDatabase: Error clearing database")
                        }
                    }
                }
                
                Timber.d("mergeToDatabase: Getting library books count")
                val allBooksFromBackup = try {
                    backupDatabase.libraryBooks.getAll().also { books ->
                        Timber.d("mergeToDatabase: getAll() returned ${books.size} books")
                        books.take(3).forEach { book ->
                            Timber.d("mergeToDatabase: Book sample: title='${book.title}', inLibrary=${book.inLibrary}, url=${book.url.take(50)}")
                        }
                    }
                } catch (e: Exception) {
                    Timber.e(e, "mergeToDatabase: Error calling getAll()")
                    throw e
                }
                
                val booksToAdd = allBooksFromBackup
                    .map { it.copy(inLibrary = true) } // Force all restored books to be in library
                    .also {
                        Timber.d("mergeToDatabase: Found ${it.size} books to restore (marking all as inLibrary=true)")
                    }
                
                notificationsCenter.modifyNotification(
                    notificationBuilder,
                    notificationId = notificationId
                ) {
                    text = getString(R.string.adding_books)
                }
                Timber.d("mergeToDatabase: Inserting ${booksToAdd.size} books")
                appRepository.libraryBooks.insertReplace(booksToAdd)
                
                Timber.d("mergeToDatabase: Getting chapters count")
                val chaptersToAdd = try {
                    backupDatabase.bookChapters.getAll().also {
                        Timber.d("mergeToDatabase: Found ${it.size} chapters to restore")
                    }
                } catch (e: Exception) {
                    Timber.e(e, "mergeToDatabase: Error getting chapters")
                    throw e
                }
                
                notificationsCenter.modifyNotification(
                    notificationBuilder,
                    notificationId = notificationId
                ) {
                    text = getString(R.string.adding_chapters)
                }
                Timber.d("mergeToDatabase: Inserting ${chaptersToAdd.size} chapters")
                appRepository.bookChapters.insert(chaptersToAdd)
                
                Timber.d("mergeToDatabase: Getting chapter bodies count")
                val chapterBodies = try {
                    backupDatabase.chapterBody.getAll().also {
                        Timber.d("mergeToDatabase: Found ${it.size} chapter bodies to restore")
                    }
                } catch (e: Exception) {
                    Timber.e(e, "mergeToDatabase: Error getting chapter bodies")
                    throw e
                }
                
                notificationsCenter.modifyNotification(
                    notificationBuilder,
                    notificationId = notificationId
                ) {
                    text = getString(R.string.adding_chapters_text)
                }
                Timber.d("mergeToDatabase: Inserting ${chapterBodies.size} chapter bodies")
                appRepository.chapterBody.insertReplace(chapterBodies)
                
                backupDatabase.close()
                backupDatabase.delete()
                Timber.d("mergeToDatabase: Database merge completed successfully")
            }.onError {
                Timber.e(it.exception, "mergeToDatabase: Failed to merge database")
                notificationsCenter.showNotification(
                    channelName = channelName,
                    channelId = channelId,
                    notificationId = "Backup restore failure - invalid database".hashCode()
                ) {
                    removeProgressBar()
                    text = getString(R.string.failed_to_restore_invalid_backup_database) + ": ${it.exception.message?.take(100)}"
                }
            }.onSuccess {
                Timber.d("mergeToDatabase: Success notification sent")
                notificationsCenter.showNotification(
                    channelName = channelName,
                    channelId = channelId,
                    notificationId = "Backup restore success".hashCode()
                ) {
                    title = getString(R.string.backup_restored)
                }
            }
        }

        fun mergeToBookFolder(entry: ZipEntry, inputStream: InputStream) {
            try {
                val file = File(appRepository.settings.folderBooks.parentFile, entry.name)
                Timber.d("mergeToBookFolder: Processing ${entry.name} -> ${file.absolutePath}")
                if (file.isDirectory) {
                    Timber.d("mergeToBookFolder: Skipping directory entry")
                    return
                }
                file.parentFile?.mkdirs()
                if (file.parentFile?.exists() != true) {
                    Timber.w("mergeToBookFolder: Parent directory does not exist and could not be created: ${file.parentFile?.absolutePath}")
                    return
                }
                file.outputStream().use { output ->
                    inputStream.use { it.copyTo(output) }
                }
                Timber.d("mergeToBookFolder: Successfully copied file: ${file.absolutePath}")
            } catch (e: Exception) {
                Timber.e(e, "mergeToBookFolder: Error processing entry: ${entry.name}")
            }
        }

        notificationsCenter.modifyNotification(
            notificationBuilder,
            notificationId = notificationId
        ) {
            text = getString(R.string.adding_images)
        }
        
        Timber.d("restoreData: Processing ${zipSequence.size} ZIP entries")
        for ((entry, file) in zipSequence) {
            try {
                when {
                    entry.name == "database.sqlite3" -> {
                        Timber.d("restoreData: Merging database from ${entry.name}, size: ${file.size} bytes")
                        // Verify SQLite header
                        val header = file.take(16).map { "%02x".format(it) }.joinToString("")
                        Timber.d("restoreData: Database file header: $header")
                        mergeToDatabase(file.inputStream())
                    }
                    entry.name.startsWith("books/") -> {
                        Timber.d("restoreData: Merging book file: ${entry.name}")
                        mergeToBookFolder(entry, file.inputStream())
                    }
                    else -> {
                        Timber.w("restoreData: Skipping unknown entry: ${entry.name}")
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "restoreData: Error processing entry ${entry.name}")
                // Continue with other entries
            }
        }

        inputStream.closeQuietly()
        Timber.d("restoreData: Restore process completed, closed input stream")
        notificationsCenter.modifyNotification(
            notificationBuilder,
            notificationId = notificationId
        ) {
            removeProgressBar()
            text = getString(R.string.data_restored)
        }
    }
}