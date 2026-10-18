package com.nexauren.archiver

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.ProgressDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.graphics.pdf.PdfRenderer
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.Settings
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.webkit.MimeTypeMap
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.GridLayout
import android.widget.HorizontalScrollView
import android.widget.Switch
import android.widget.ImageButton
import android.widget.FrameLayout
import android.graphics.drawable.GradientDrawable
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.sevenz.SevenZMethod
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.text.DateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class MainActivity : Activity() {
    private lateinit var prefs: SharedPreferences
    private lateinit var rootDir: File
    private lateinit var currentDir: File
    private lateinit var page: LinearLayout
    private lateinit var rows: LinearLayout
    private lateinit var pathView: TextView
    private lateinit var selectionView: TextView
    private lateinit var countView: TextView
    private lateinit var breadcrumbRow: LinearLayout
    private var selectionBar: LinearLayout? = null
    private var addFab: ImageButton? = null
    private var gridRows: GridLayout? = null
    private var browserRendered = false
    private var settingsOpen = false

    private val selected = linkedSetOf<String>()
    private val favorites = linkedSetOf<String>()
    private var clipboard = emptyList<File>()
    private var clipboardIsCut = false
    private val worker: ExecutorService = Executors.newSingleThreadExecutor()
    private val thumbnailWorker: ExecutorService = Executors.newFixedThreadPool(2)
    private val workActive = AtomicBoolean(false)
    private val notificationChannelId = "archiver_file_operations"
    private val operationNotificationId = 7410
    private val thumbnailCache = object : LruCache<String, Bitmap>(2 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = maxOf(1, value.byteCount / 1024)
    }

    private var showHidden = false
    private var showSize = true
    private var showDate = true
    private var showTime = false
    private var showThumbnails = true
    private var showPdfThumbnails = false
    private var showVideoThumbnails = false
    private var showAlbumCovers = true
    private var showApkIcons = true
    private var squareThumbnails = false
    private var useParentEntry = true
    private var descending = false
    private var sortBy = "Nome"
    private var viewMode = "Detalhado"
    private var compressionLevel = 2

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        window.statusBarColor = 0xFF292D40.toInt()
        window.navigationBarColor = 0xFF101014.toInt()
        prefs = getSharedPreferences("archiver_preferences", MODE_PRIVATE)
        loadPreferences()
        rootDir = Environment.getExternalStorageDirectory().canonicalFile
        currentDir = rootDir
        if (hasStorageAccess()) renderBrowser() else showPermissionScreen()
    }

    override fun onResume() {
        super.onResume()
        if (!::prefs.isInitialized) return
        if (hasStorageAccess() && !browserRendered) renderBrowser()
        else if (!hasStorageAccess() && !browserRendered && !::page.isInitialized) showPermissionScreen()
    }

    private fun loadPreferences() {
        showHidden = prefs.getBoolean("hidden", false)
        showSize = prefs.getBoolean("size", true)
        showDate = prefs.getBoolean("date", true)
        showTime = prefs.getBoolean("time", false)
        showThumbnails = prefs.getBoolean("thumbnails", true)
        showPdfThumbnails = prefs.getBoolean("thumbnails_pdf", false)
        showVideoThumbnails = prefs.getBoolean("thumbnails_video", false)
        showAlbumCovers = prefs.getBoolean("album_covers", true)
        showApkIcons = prefs.getBoolean("apk_icons", true)
        squareThumbnails = prefs.getBoolean("square_thumbnails", false)
        useParentEntry = prefs.getBoolean("use_parent_entry", true)
        descending = prefs.getBoolean("descending", false)
        sortBy = prefs.getString("sort", "Nome") ?: "Nome"
        viewMode = prefs.getString("view_mode", "Detalhado") ?: "Detalhado"
        compressionLevel = prefs.getInt("compression", 2)
        favorites.clear()
        favorites.addAll(prefs.getStringSet("favorites", emptySet()) ?: emptySet())
    }

    private fun savePreferences() {
        prefs.edit()
            .putBoolean("hidden", showHidden)
            .putBoolean("size", showSize)
            .putBoolean("date", showDate)
            .putBoolean("time", showTime)
            .putBoolean("thumbnails", showThumbnails)
            .putBoolean("thumbnails_pdf", showPdfThumbnails)
            .putBoolean("thumbnails_video", showVideoThumbnails)
            .putBoolean("album_covers", showAlbumCovers)
            .putBoolean("apk_icons", showApkIcons)
            .putBoolean("square_thumbnails", squareThumbnails)
            .putBoolean("use_parent_entry", useParentEntry)
            .putBoolean("descending", descending)
            .putString("sort", sortBy)
            .putString("view_mode", viewMode)
            .putInt("compression", compressionLevel)
            .putStringSet("favorites", HashSet(favorites))
            .apply()
    }

    private fun hasStorageAccess(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun showPermissionScreen() {
        browserRendered = false
        page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(28), dp(24), dp(28), dp(24))
            setBackgroundColor(0xFF17171B.toInt())
        }
        val icon = ImageView(this).apply {
            setImageResource(com.nexauren.archiver.R.drawable.ic_archiver)
            contentDescription = "Archiver"
        }
        page.addView(icon, LinearLayout.LayoutParams(dp(96), dp(96)).apply { bottomMargin = dp(20) })
        page.addView(label("Archiver", 28, 0xFFFFFFFF.toInt(), true).apply { gravity = Gravity.CENTER },
            LinearLayout.LayoutParams(-1, -2))
        page.addView(label("Gestor de ficheiros para Android", 16, 0xFFB7B7C2.toInt(), false).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(20))
        }, LinearLayout.LayoutParams(-1, -2))
        page.addView(label(
            "Para explorar, copiar, mover, renomear, eliminar e comprimir ficheiros em diferentes pastas, o Archiver precisa de acesso aos ficheiros partilhados do dispositivo.",
            16, 0xFFE3E3E8.toInt(), false
        ).apply { gravity = Gravity.CENTER; setPadding(0, 0, 0, dp(24)) }, LinearLayout.LayoutParams(-1, -2))
        page.addView(actionButton("Conceder acesso aos ficheiros") { requestStorageAccess() },
            LinearLayout.LayoutParams(-1, dp(54)))
        page.addView(label(
            "O Android abre uma página especial de definições para confirmar este acesso. O sistema continua a bloquear algumas pastas privadas das aplicações.",
            13, 0xFF9999A4.toInt(), false
        ).apply { gravity = Gravity.CENTER; setPadding(0, dp(18), 0, 0) }, LinearLayout.LayoutParams(-1, -2))
        setContentView(page)
    }

    private fun requestStorageAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:" + packageName)))
            } catch (_: Exception) {
                try {
                    startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                } catch (_: Exception) {
                    toast("Não foi possível abrir as definições de acesso aos ficheiros.")
                }
            }
        } else {
            requestPermissions(arrayOf(
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            ), 501)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 501) {
            if (hasStorageAccess()) renderBrowser() else showPermissionScreen()
        }
    }

    private fun renderBrowser() {
        if (!hasStorageAccess()) {
            showPermissionScreen()
            return
        }
        settingsOpen = false
        browserRendered = true
        if (!::rootDir.isInitialized) rootDir = Environment.getExternalStorageDirectory().canonicalFile
        if (!::currentDir.isInitialized) currentDir = rootDir
        if (!currentDir.exists() || !currentDir.isDirectory) currentDir = rootDir

        page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF19191D.toInt())
        }
        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), 0, dp(4), 0)
            setBackgroundColor(0xFF292D40.toInt())
        }
        toolbar.addView(toolbarIcon(R.drawable.ic_arrow_back, "Pasta anterior") { goUp() },
            LinearLayout.LayoutParams(dp(48), dp(58)))
        val titleColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
        }
        pathView = label(currentDir.name?.ifBlank { "Armazenamento interno" } ?: "Armazenamento interno",
            19, 0xFFFFFFFF.toInt(), true).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.START
        }
        selectionView = label(currentDir.absolutePath, 12, 0xFFD0D0DF.toInt(), false).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.START
        }
        titleColumn.addView(pathView, LinearLayout.LayoutParams(-1, -2))
        titleColumn.addView(selectionView, LinearLayout.LayoutParams(-1, -2))
        toolbar.addView(titleColumn, LinearLayout.LayoutParams(0, dp(58), 1f))
        toolbar.addView(toolbarIcon(R.drawable.ic_search, "Pesquisar") { promptSearch() },
            LinearLayout.LayoutParams(dp(44), dp(58)))
        toolbar.addView(toolbarIcon(if (viewMode == "Grelha") R.drawable.ic_list else R.drawable.ic_grid,
            "Alterar modo de visualização") { cycleViewMode() }, LinearLayout.LayoutParams(dp(44), dp(58)))
        toolbar.addView(toolbarIcon(R.drawable.ic_more_vertical, "Mais opções") { showMainMenu() },
            LinearLayout.LayoutParams(dp(44), dp(58)))
        page.addView(toolbar, LinearLayout.LayoutParams(-1, dp(58)))
        page.addView(View(this).apply { setBackgroundColor(0xFFB8B5FF.toInt()) },
            LinearLayout.LayoutParams(-1, dp(2)))

        val breadcrumbScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            clipToPadding = false
            setPadding(dp(8), 0, dp(8), 0)
            setBackgroundColor(0xFF202025.toInt())
        }
        breadcrumbRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        breadcrumbScroll.addView(breadcrumbRow, FrameLayout.LayoutParams(-2, -1))
        page.addView(breadcrumbScroll, LinearLayout.LayoutParams(-1, dp(38)))

        countView = label("", 13, 0xFFB9B9C3.toInt(), false).apply {
            setPadding(dp(16), dp(7), dp(16), dp(7))
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0xFF202025.toInt())
        }
        page.addView(countView, LinearLayout.LayoutParams(-1, dp(34)))

        val body = FrameLayout(this)
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
        }
        rows = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(4), dp(8), dp(104))
        }
        scroll.addView(rows)
        body.addView(scroll, FrameLayout.LayoutParams(-1, -1))

        val fab = ImageButton(this).apply {
            setImageResource(R.drawable.ic_add)
            contentDescription = "Criar ficheiro ou arquivo"
            background = roundedBackground(0xFFB8B5FF.toInt(), 28)
            scaleType = ImageView.ScaleType.CENTER
            setPadding(dp(16), dp(16), dp(16), dp(16))
            elevation = dp(8).toFloat()
            setOnClickListener { showCreateMenu() }
        }
        val fabParams = FrameLayout.LayoutParams(dp(58), dp(58), Gravity.END or Gravity.BOTTOM)
        fabParams.setMargins(0, 0, dp(20), dp(20))
        body.addView(fab, fabParams)
        addFab = fab

        selectionBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(2), dp(4), dp(2))
            setBackgroundColor(0xFF292D40.toInt())
            elevation = dp(10).toFloat()
            visibility = View.GONE
        }
        selectionBar?.addView(selectionAction("▢", "Copiar") { stageClipboard(false) },
            LinearLayout.LayoutParams(0, dp(66), 1f))
        selectionBar?.addView(selectionAction("⇄", "Mover") { stageClipboard(true) },
            LinearLayout.LayoutParams(0, dp(66), 1f))
        selectionBar?.addView(selectionAction("ZIP", "Comprimir") { promptArchive(chosen(), "zip") },
            LinearLayout.LayoutParams(0, dp(66), 1f))
        selectionBar?.addView(selectionAction("⌫", "Eliminar") { confirmDelete(chosen()) },
            LinearLayout.LayoutParams(0, dp(66), 1f))
        selectionBar?.addView(selectionAction("⋮", "Mais") { showSelectionActions() },
            LinearLayout.LayoutParams(0, dp(66), 1f))
        body.addView(selectionBar, FrameLayout.LayoutParams(-1, dp(70), Gravity.BOTTOM))
        page.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(page)
        refreshFiles()
    }

    private fun refreshFiles() {
        if (!browserRendered || settingsOpen || !hasStorageAccess()) return
        rows.removeAllViews()
        gridRows = null
        pathView.text = currentDir.name?.ifBlank { "Armazenamento interno" } ?: "Armazenamento interno"
        selectionView.text = currentDir.absolutePath
        renderBreadcrumbs()
        if (useParentEntry && currentDir.canonicalFile != rootDir.canonicalFile) addParentRow()

        if (viewMode == "Grelha") {
            gridRows = GridLayout(this).apply { columnCount = 3; useDefaultMargins = false }
            rows.addView(gridRows, LinearLayout.LayoutParams(-1, -2))
        }
        val files = safeList(currentDir).filter { showHidden || !it.name.startsWith(".") }.toMutableList()
        val comparator = when (sortBy) {
            "Tamanho" -> compareBy<File> { if (it.isDirectory) -1L else it.length() }
            "Data" -> compareBy<File> { it.lastModified() }
            "Tipo" -> compareBy<File> { extension(it.name) }
            else -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.name }
        }
        files.sortWith { a, b ->
            val dirOrder = when {
                a.isDirectory && !b.isDirectory -> -1
                !a.isDirectory && b.isDirectory -> 1
                else -> 0
            }
            if (dirOrder != 0) dirOrder else {
                val order = comparator.compare(a, b)
                if (descending) -order else order
            }
        }
        countView.text = when {
            selected.isNotEmpty() -> selected.size.toString() + " selecionado(s) • " + files.size + " itens"
            files.isEmpty() -> "Pasta vazia"
            else -> files.size.toString() + if (files.size == 1) " item" else " itens"
        }
        files.forEach { addFileRow(it) }
        updateSelectionBar()
    }

    private fun renderBreadcrumbs() {
        if (!::breadcrumbRow.isInitialized || !::rootDir.isInitialized || !::currentDir.isInitialized) return
        breadcrumbRow.removeAllViews()
        val root = rootDir.canonicalFile
        val current = currentDir.canonicalFile
        val rootIsCurrent = pathKey(root) == pathKey(current)
        val rootSegment = TextView(this).apply {
            text = "Armazenamento interno"
            textSize = 12f
            setTextColor(if (rootIsCurrent) 0xFFD8D3FF.toInt() else 0xFFE2E2E8.toInt())
            setPadding(dp(8), dp(5), dp(8), dp(5))
            isClickable = true
            isFocusable = true
            setOnClickListener {
                currentDir = rootDir
                selected.clear()
                refreshFiles()
            }
        }
        breadcrumbRow.addView(rootSegment, LinearLayout.LayoutParams(-2, -2))

        val relative = if (pathKey(current) == pathKey(root)) "" else
            current.path.removePrefix(root.path).trimStart(File.separatorChar)
        var cursor = root
        relative.split(File.separatorChar).filter { it.isNotBlank() }.forEach { part ->
            breadcrumbRow.addView(TextView(this).apply {
                text = "›"
                textSize = 18f
                setTextColor(0xFF858596.toInt())
                gravity = Gravity.CENTER
            }, LinearLayout.LayoutParams(-2, -2))
            cursor = File(cursor, part)
            val target = cursor
            val isCurrent = pathKey(target) == pathKey(current)
            val segment = TextView(this).apply {
                text = part
                textSize = 12f
                setTextColor(if (isCurrent) 0xFFD8D3FF.toInt() else 0xFFE2E2E8.toInt())
                setPadding(dp(6), dp(5), dp(8), dp(5))
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    if (target.exists() && target.isDirectory && isWithinRoot(target)) {
                        currentDir = target
                        selected.clear()
                        refreshFiles()
                    }
                }
            }
            breadcrumbRow.addView(segment, LinearLayout.LayoutParams(-2, -2))
        }
    }

    private fun updateSelectionBar() {
        val bar = selectionBar ?: return
        val hasSelection = selected.isNotEmpty()
        bar.visibility = if (hasSelection) View.VISIBLE else View.GONE
        addFab?.visibility = if (hasSelection) View.GONE else View.VISIBLE
    }

    private fun safeList(dir: File): List<File> {
        return try { dir.listFiles()?.toList() ?: emptyList() } catch (_: SecurityException) { emptyList() }
    }

    private fun addParentRow() {
        val item = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(8), dp(12), dp(8))
            minimumHeight = dp(76)
            background = roundedBackground(0xFF24242A.toInt(), 12)
            setOnClickListener { goUp() }
        }
        val icon = ImageView(this).apply {
            setImageResource(R.drawable.ic_folder_up)
            contentDescription = "Subir um diretório"
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        item.addView(icon, LinearLayout.LayoutParams(dp(64), dp(54)))
        val textColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), 0, 0, 0)
        }
        textColumn.addView(label("..", 17, 0xFFF4F4F6.toInt(), true))
        textColumn.addView(label("Subir um diretório", 12, 0xFFACACB6.toInt(), false).apply {
            setPadding(0, dp(3), 0, 0)
        })
        item.addView(textColumn, LinearLayout.LayoutParams(0, -2, 1f))
        rows.addView(item, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(2) })
    }

    private fun addFileRow(file: File) {
        val key = pathKey(file)
        val isSelected = selected.contains(key)
        val inGrid = gridRows != null
        val item = LinearLayout(this).apply {
            orientation = if (inGrid) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            gravity = if (inGrid) Gravity.TOP or Gravity.CENTER_HORIZONTAL else Gravity.CENTER_VERTICAL
            setPadding(dp(if (inGrid) 8 else 4), dp(if (inGrid) 12 else 6),
                dp(if (inGrid) 8 else 6), dp(if (inGrid) 12 else 6))
            background = roundedBackground(if (isSelected) 0xFF353348.toInt() else if (inGrid) 0xFF24242A.toInt() else 0xFF19191D.toInt(), if (inGrid) 10 else 4)
            isClickable = true
            isFocusable = true
        }
        val icon = ImageView(this).apply {
            setImageResource(iconForFile(file))
            contentDescription = if (file.isDirectory) "Pasta " + file.name else file.name
            scaleType = if (squareThumbnails) ImageView.ScaleType.CENTER_CROP else ImageView.ScaleType.FIT_CENTER
        }
        val iconWidth = dp(if (inGrid) 72 else 48)
        val iconHeight = dp(if (inGrid) 66 else 48)
        if (inGrid) item.addView(icon, LinearLayout.LayoutParams(iconWidth, iconHeight).apply { gravity = Gravity.CENTER_HORIZONTAL })
        else item.addView(icon, LinearLayout.LayoutParams(iconWidth, iconHeight).apply { rightMargin = dp(8) })
        if (shouldLoadThumbnail(file)) loadThumbnailAsync(file, icon)

        val details = if (file.isDirectory) {
            val count = safeList(file).size
            count.toString() + if (count == 1) " item" else " itens"
        } else formatSize(file.length())
        val modified = if (file.lastModified() > 0L) DateFormat.getDateInstance(DateFormat.SHORT).format(Date(file.lastModified())) else ""
        val middle = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = if (inGrid) Gravity.CENTER_HORIZONTAL else Gravity.CENTER_VERTICAL
            setPadding(if (inGrid) 0 else dp(4), 0, if (inGrid) 0 else dp(4), 0)
        }
        val name = TextView(this).apply {
            text = file.name.ifBlank { "(sem nome)" }
            textSize = if (inGrid) 13f else if (viewMode == "Compacto") 13f else 15f
            setTextColor(if (isSelected) 0xFFD8D3FF.toInt() else 0xFFF3F3F5.toInt())
            typeface = if (isSelected) android.graphics.Typeface.DEFAULT_BOLD else android.graphics.Typeface.DEFAULT
            maxLines = if (inGrid) 2 else if (viewMode == "Detalhado") 2 else 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            gravity = if (inGrid) Gravity.CENTER else Gravity.START
        }
        val metaText = buildString {
            if (showSize) append(details)
            if (showTime && modified.isNotEmpty() && !inGrid) {
                if (isNotEmpty()) append("   •   ")
                append(DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(file.lastModified())))
            }
        }
        middle.addView(name, LinearLayout.LayoutParams(-1, -2))
        if (metaText.isNotEmpty()) middle.addView(TextView(this).apply {
            text = metaText
            textSize = 12f
            setTextColor(0xFFAAAAB4.toInt())
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            gravity = if (inGrid) Gravity.CENTER else Gravity.START
            setPadding(0, dp(4), 0, 0)
        }, LinearLayout.LayoutParams(-1, -2))
        if (inGrid) {
            item.addView(middle, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
            val params = GridLayout.LayoutParams(
                GridLayout.spec(GridLayout.UNDEFINED, 1f),
                GridLayout.spec(GridLayout.UNDEFINED, 1f)
            ).apply {
                width = 0; height = -2
                setMargins(dp(3), dp(3), dp(3), dp(3))
            }
            gridRows?.addView(item, params)
        } else {
            item.addView(middle, LinearLayout.LayoutParams(0, -2, 1f))
            if (showDate && modified.isNotEmpty()) item.addView(TextView(this).apply {
                text = modified
                textSize = 11f
                gravity = Gravity.END or Gravity.BOTTOM
                setTextColor(0xFF9999A3.toInt())
                maxLines = 1
            }, LinearLayout.LayoutParams(dp(72), -2).apply { leftMargin = dp(4) })
            rows.addView(item, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(2) })
            if (viewMode != "Compacto") addDivider()
        }
        item.setOnClickListener {
            if (selected.isNotEmpty()) toggleSelection(file)
            else if (file.isDirectory) {
                currentDir = file
                selected.clear()
                refreshFiles()
            } else showFileActions(file)
        }
        item.setOnLongClickListener { toggleSelection(file); true }
    }

    private fun iconForFile(file: File): Int {
        if (file.isDirectory) return R.drawable.ic_folder
        return when (extension(file.name)) {
            "zip", "7z", "rar", "tar", "gz", "bz2" -> R.drawable.ic_archive
            "jpg", "jpeg", "png", "webp", "gif", "bmp", "heic" -> R.drawable.ic_image
            "mp3", "wav", "ogg", "m4a", "flac", "aac", "opus" -> R.drawable.ic_audio
            "mp4", "mkv", "webm", "avi", "mov", "3gp" -> R.drawable.ic_video
            "apk" -> R.drawable.ic_android_file
            "pdf" -> R.drawable.ic_pdf
            else -> R.drawable.ic_file
        }
    }

    private fun fileGlyph(file: File): String {
        if (file.isDirectory) return "📁"
        return when (extension(file.name)) {
            "zip", "7z", "rar", "tar", "gz", "bz2" -> "🗜"
            "jpg", "jpeg", "png", "webp", "gif", "bmp", "heic" -> "🖼"
            "mp3", "wav", "ogg", "m4a", "flac", "aac", "opus" -> "♫"
            "mp4", "mkv", "webm", "avi", "mov", "3gp" -> "▶"
            "apk" -> "▣"
            "pdf" -> "PDF"
            else -> "📄"
        }
    }

    private fun addDivider() {
        rows.addView(View(this).apply { setBackgroundColor(0xFF333339.toInt()) },
            LinearLayout.LayoutParams(-1, dp(1)))
    }

    private fun toggleSelection(file: File) {
        val key = pathKey(file)
        if (!selected.add(key)) selected.remove(key)
        refreshFiles()
    }

    private fun goUp() {
        if (!hasStorageAccess()) { showPermissionScreen(); return }
        val parent = currentDir.parentFile
        if (parent != null && isWithinRoot(parent)) {
            currentDir = parent
            selected.clear()
            refreshFiles()
        } else {
            toast("Já estás na raiz do armazenamento partilhado.")
        }
    }

    private fun isWithinRoot(file: File): Boolean {
        val root = rootDir.canonicalPath
        val path = try { file.canonicalPath } catch (_: Exception) { return false }
        return path == root || path.startsWith(root + File.separator)
    }

    private fun displayPath(): String {
        val path = try { currentDir.canonicalPath } catch (_: Exception) { currentDir.absolutePath }
        val root = rootDir.absolutePath
        val relative = if (path == root) "Armazenamento interno" else path.removePrefix(root).trimStart(File.separatorChar)
        return relative.ifBlank { "Armazenamento interno" }
    }

    private fun pathKey(file: File): String = try { file.canonicalPath } catch (_: Exception) { file.absolutePath }

    private fun showMainMenu() {
        val options = arrayOf(
            "Selecionar tudo", "Limpar seleção", "Copiar seleção", "Recortar seleção", "Colar",
            "Criar novo…", "Comprimir em ZIP", "Comprimir em 7Z", "Pesquisar em subpastas", "Favoritos",
            "Ordenar", "Definições do explorador", "Atualizar", "Pasta de início"
        )
        AlertDialog.Builder(this).setTitle("Opções").setItems(options) { _, which ->
            when (which) {
                0 -> {
                    selected.clear()
                    safeList(currentDir).forEach { selected.add(pathKey(it)) }
                    refreshFiles()
                }
                1 -> { selected.clear(); refreshFiles() }
                2 -> stageClipboard(false)
                3 -> stageClipboard(true)
                4 -> paste()
                5 -> showCreateMenu()
                6 -> promptArchive(chosenOrVisible(), "zip")
                7 -> promptArchive(chosenOrVisible(), "7z")
                8 -> promptSearch()
                9 -> showFavorites()
                10 -> showSortMenu()
                11 -> showExplorerSettings()
                12 -> refreshFiles()
                13 -> { currentDir = rootDir; selected.clear(); refreshFiles() }
            }
        }.show()
    }

    private fun showSelectionActions() {
        val options = arrayOf("Comprimir em ZIP", "Comprimir em 7Z", "Renomear", "Informações", "Limpar seleção")
        AlertDialog.Builder(this)
            .setTitle("${selected.size} selecionado(s)")
            .setItems(options) { _, which ->
                val items = chosen()
                when (which) {
                    0 -> promptArchive(items, "zip")
                    1 -> promptArchive(items, "7z")
                    2 -> if (items.size == 1) promptRename(items[0]) else toast("Para renomear, seleciona apenas um item.")
                    3 -> if (items.size == 1) showInfo(items[0]) else toast("Seleciona apenas um item para ver as informações.")
                    4 -> { selected.clear(); refreshFiles() }
                }
            }
            .show()
    }

    private fun showSortMenu() {
        val items = arrayOf("Nome", "Tamanho", "Data", "Tipo", if (descending) "Ordem crescente" else "Ordem decrescente")
        AlertDialog.Builder(this).setTitle("Ordenar ficheiros").setItems(items) { _, which ->
            if (which < 4) sortBy = items[which] else descending = !descending
            savePreferences()
            if (settingsOpen) renderSettingsScreen() else refreshFiles()
        }.show()
    }

    private fun showExplorerSettings() {
        settingsOpen = true
        renderSettingsScreen()
    }

    private fun renderSettingsScreen() {
        page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF19191D.toInt())
        }
        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), 0, dp(12), 0)
            setBackgroundColor(0xFF292D40.toInt())
        }
        toolbar.addView(toolbarIcon(R.drawable.ic_arrow_back, "Voltar ao explorador") {
            settingsOpen = false
            renderBrowser()
        }, LinearLayout.LayoutParams(dp(48), dp(58)))
        toolbar.addView(label("Explorador", 22, 0xFFFFFFFF.toInt(), true),
            LinearLayout.LayoutParams(0, dp(58), 1f))
        page.addView(toolbar, LinearLayout.LayoutParams(-1, dp(58)))
        page.addView(View(this).apply { setBackgroundColor(0xFFB8B5FF.toInt()) },
            LinearLayout.LayoutParams(-1, dp(2)))

        val scroll = ScrollView(this).apply { isFillViewport = true }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(6), dp(16), dp(18))
        }
        scroll.addView(content)
        page.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        addSettingChoice(content, "Ordenar arquivos", sortBy, "Escolher a ordem dos ficheiros") { showSortMenu() }
        addSettingSwitch(content, "Decrescente", "Inverte a ordem de ordenação dos arquivos", descending) { descending = it }
        addSettingSwitch(content, "Usar item \"..\" ", "Permite subir para o diretório anterior a partir da lista", useParentEntry) { useParentEntry = it }
        addSettingSwitch(content, "Exibir arquivos ocultos", "Mostra ficheiros e diretórios cujo nome começa por ponto", showHidden) { showHidden = it }
        addSettingChoice(content, "Modo de exibição", viewMode, "Detalhado, compacto ou grelha") { showViewModeMenu() }
        addSettingSwitch(content, "Exibir tamanho", "Mostra o tamanho dos ficheiros na lista", showSize) { showSize = it }
        addSettingSwitch(content, "Exibir data", "Mostra a data da última modificação", showDate) { showDate = it }
        addSettingSwitch(content, "Exibir hora", "Mostra a hora da última modificação", showTime, showDate) { showTime = it }
        addSettingSwitch(content, "Miniaturas", "Permite a exibição de miniaturas", showThumbnails) { showThumbnails = it }
        addSettingSwitch(content, "Miniaturas do PDF", "Mostrar miniaturas de documentos PDF (pode causar erros em alguns dispositivos)", showPdfThumbnails) { showPdfThumbnails = it }
        addSettingSwitch(content, "Miniaturas de vídeo", "Mostrar miniaturas de vídeo", showVideoThumbnails) { showVideoThumbnails = it }
        addSettingSwitch(content, "Capa do álbum", "Mostrar a capa do álbum nos ficheiros de música", showAlbumCovers) { showAlbumCovers = it }
        addSettingSwitch(content, "Ícones do APK", "Exibir os ícones das aplicações APK", showApkIcons) { showApkIcons = it }
        addSettingSwitch(content, "Miniaturas quadradas", "Recorta as miniaturas para formato quadrado", squareThumbnails) { squareThumbnails = it }
        addSettingChoice(content, "Nível de compressão", compressionName(), "ZIP e 7Z") { showCompressionMenu() }
        setContentView(page)
    }

    private fun addSettingChoice(container: LinearLayout, title: String, value: String, summary: String, click: () -> Unit) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(15), dp(4), dp(15))
            isClickable = true
            isFocusable = true
            setOnClickListener { click() }
        }
        val copy = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
        }
        copy.addView(label(title, 16, 0xFFF0F0F3.toInt(), false))
        copy.addView(label(value, 14, 0xFFD0CBFF.toInt(), false).apply { setPadding(0, dp(4), 0, 0) })
        copy.addView(label(summary, 12, 0xFF9D9DA8.toInt(), false).apply { setPadding(0, dp(3), 0, 0) })
        row.addView(copy, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(TextView(this).apply {
            text = "›"
            textSize = 28f
            gravity = Gravity.CENTER
            setTextColor(0xFFB8B5FF.toInt())
        }, LinearLayout.LayoutParams(dp(32), dp(44)))
        container.addView(row, LinearLayout.LayoutParams(-1, -2))
        container.addView(View(this).apply { setBackgroundColor(0xFF333339.toInt()) }, LinearLayout.LayoutParams(-1, dp(1)))
    }

    private fun addSettingSwitch(
        container: LinearLayout,
        title: String,
        summary: String,
        checked: Boolean,
        enabled: Boolean = true,
        change: (Boolean) -> Unit
    ) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(14), dp(2), dp(14))
            isClickable = true
            isFocusable = true
        }
        val copy = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
        }
        copy.addView(label(title, 16, if (enabled) 0xFFF0F0F3.toInt() else 0xFF777780.toInt(), false))
        copy.addView(label(summary, 13, if (enabled) 0xFFABABB5.toInt() else 0xFF777780.toInt(), false).apply { setPadding(0, dp(4), 0, 0) })
        row.addView(copy, LinearLayout.LayoutParams(0, -2, 1f))
        val toggle = Switch(this).apply {
            isChecked = checked
            isEnabled = enabled
            contentDescription = title
            setOnCheckedChangeListener { _, value ->
                change(value)
                savePreferences()
                renderSettingsScreen()
            }
        }
        row.addView(toggle, LinearLayout.LayoutParams(dp(58), -2).apply { leftMargin = dp(10) })
        row.setOnClickListener { if (enabled) toggle.isChecked = !toggle.isChecked }
        container.addView(row, LinearLayout.LayoutParams(-1, -2))
        container.addView(View(this).apply { setBackgroundColor(0xFF333339.toInt()) }, LinearLayout.LayoutParams(-1, dp(1)))
    }

    private fun showViewModeMenu() {
        val modes = arrayOf("Detalhado", "Compacto", "Grelha")
        val checked = modes.indexOf(viewMode).coerceAtLeast(0)
        AlertDialog.Builder(this).setTitle("Modo de exibição").setSingleChoiceItems(modes, checked) { dialog, which ->
            viewMode = modes[which]
            savePreferences()
            dialog.dismiss()
            if (settingsOpen) renderSettingsScreen() else refreshFiles()
        }.setNegativeButton("Cancelar", null).show()
    }

    private fun cycleViewMode() {
        viewMode = when (viewMode) { "Detalhado" -> "Compacto"; "Compacto" -> "Grelha"; else -> "Detalhado" }
        savePreferences()
        refreshFiles()
        if (::pathView.isInitialized) pathView.text = displayPath()
    }

    private fun showCompressionMenu() {
        val levels = arrayOf("Sem compressão (mais rápido)", "Rápido", "Normal", "Ultra (mais lento)")
        AlertDialog.Builder(this).setTitle("Nível de compressão").setSingleChoiceItems(levels, compressionLevel.coerceIn(0, 3)) { dialog, which ->
            compressionLevel = which
            savePreferences()
            dialog.dismiss()
            toast("Nível guardado.")
            if (settingsOpen) renderSettingsScreen()
        }.setNegativeButton("Cancelar", null).show()
    }

    private fun showCreateMenu() {
        val options = arrayOf("Nova pasta", "Novo ficheiro vazio", "Criar arquivo ZIP", "Criar arquivo 7Z")
        AlertDialog.Builder(this).setTitle("Criar").setItems(options) { _, which ->
            when (which) {
                0 -> promptNewItem(true)
                1 -> promptNewItem(false)
                2 -> promptArchive(chosen(), "zip")
                3 -> promptArchive(chosen(), "7z")
            }
        }.show()
    }

    private fun promptNewItem(folder: Boolean) {
        val input = EditText(this).apply {
            hint = if (folder) "Nome da pasta" else "Nome do ficheiro"
            setSingleLine(true)
        }
        AlertDialog.Builder(this).setTitle(if (folder) "Nova pasta" else "Novo ficheiro")
            .setView(input).setNegativeButton("Cancelar", null).setPositiveButton("Criar") { _, _ ->
                val name = input.text.toString().trim()
                if (!validName(name)) { toast("Nome inválido."); return@setPositiveButton }
                val target = File(currentDir, name)
                if (target.exists()) { toast("Já existe um ficheiro com esse nome."); return@setPositiveButton }
                val ok = try { if (folder) target.mkdir() else target.createNewFile() } catch (_: Exception) { false }
                if (!ok) toast("Não foi possível criar. Verifica as permissões e o espaço livre.")
                refreshFiles()
            }.show()
    }

    private fun validName(name: String): Boolean =
        name.isNotBlank() && name != "." && name != ".." && !name.contains("/") && !name.contains("\\") && !name.contains('\u0000')

    private fun showFileActions(file: File) {
        val ext = extension(file.name)
        val options = mutableListOf<String>()
        if (file.isDirectory) options += "Abrir pasta" else options += "Abrir"
        if (ext == "zip" || ext == "7z") {
            options += "Ver conteúdo"
            options += "Testar arquivo"
            options += "Extrair aqui"
            options += "Extrair para nova pasta"
        }
        options += listOf("Partilhar", "Copiar", "Recortar", "Renomear", "Eliminar", "Informações")
        options += if (favorites.contains(pathKey(file))) "Remover dos Favoritos" else "Adicionar aos Favoritos"
        AlertDialog.Builder(this).setTitle(file.name).setItems(options.toTypedArray()) { _, which ->
            when (options[which]) {
                "Abrir pasta" -> { currentDir = file; selected.clear(); refreshFiles() }
                "Abrir" -> openFile(file)
                "Ver conteúdo" -> browseArchive(file)
                "Testar arquivo" -> if (ext == "7z") testSevenZip(file) else testZip(file)
                "Extrair aqui" -> if (ext == "7z") extractSevenZip(file, currentDir, false) else extractZip(file, currentDir, false)
                "Extrair para nova pasta" -> if (ext == "7z") extractSevenZip(file, currentDir, true) else extractZip(file, currentDir, true)
                "Partilhar" -> shareFile(file)
                "Copiar" -> { clipboard = chosenOrSingle(file); clipboardIsCut = false; selected.clear(); toast("Preparado para copiar."); refreshFiles() }
                "Recortar" -> { clipboard = chosenOrSingle(file); clipboardIsCut = true; selected.clear(); toast("Preparado para mover."); refreshFiles() }
                "Renomear" -> promptRename(file)
                "Eliminar" -> confirmDelete(listOf(file))
                "Informações" -> showInfo(file)
                "Adicionar aos Favoritos" -> { favorites.add(pathKey(file)); savePreferences(); toast("Adicionado aos Favoritos.") }
                "Remover dos Favoritos" -> { favorites.remove(pathKey(file)); savePreferences(); toast("Removido dos Favoritos.") }
            }
        }.show()
    }

    private fun openFile(file: File) {
        try {
            val uri = FileProvider.getUriForFile(this, packageName + ".fileprovider", file)
            val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension(file.name)) ?: "*/*"
            val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            toast("Não existe uma aplicação para abrir este ficheiro.")
        } catch (e: Exception) {
            toast("Não foi possível abrir: " + (e.message ?: "erro desconhecido"))
        }
    }

    private fun shareFile(file: File) {
        try {
            val uri = FileProvider.getUriForFile(this, packageName + ".fileprovider", file)
            val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension(file.name)) ?: "*/*"
            val intent = Intent(Intent.ACTION_SEND).setType(mime)
                .putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(Intent.createChooser(intent, "Partilhar ficheiro"))
        } catch (e: Exception) { toast("Não foi possível partilhar: " + (e.message ?: "erro")) }
    }

    private fun promptRename(file: File) {
        val input = EditText(this).apply { setSingleLine(true); setText(file.name) }
        AlertDialog.Builder(this).setTitle("Renomear").setView(input)
            .setNegativeButton("Cancelar", null).setPositiveButton("Guardar") { _, _ ->
                val name = input.text.toString().trim()
                if (!validName(name)) { toast("Nome inválido."); return@setPositiveButton }
                val dest = File(file.parentFile, name)
                if (dest.exists() && dest.canonicalFile != file.canonicalFile) {
                    toast("Já existe um item com esse nome."); return@setPositiveButton
                }
                if (!file.renameTo(dest)) toast("Não foi possível renomear o item.")
                selected.clear()
                refreshFiles()
            }.show()
    }

    private fun confirmDelete(files: List<File>) {
        if (files.isEmpty()) { toast("Seleciona pelo menos um item."); return }
        AlertDialog.Builder(this).setTitle("Eliminar")
            .setMessage("Eliminar " + files.size + if (files.size == 1) " item? Esta ação não pode ser anulada." else " itens? Esta ação não pode ser anulada.")
            .setNegativeButton("Cancelar", null).setPositiveButton("Eliminar") { _, _ ->
                selected.clear()
                val totals = measureFiles(files)
                runTask("A eliminar ficheiros…", 0L, totals.entries) { meter ->
                    files.forEach { file ->
                        meter.show(file)
                        if (!deleteRecursively(file) { deleted -> meter.itemDone(deleted) }) {
                            throw IOException("Não foi possível eliminar " + file.name)
                        }
                    }
                    "Eliminação concluída: " + totals.entries + " itens."
                }
            }.show()
    }

    private fun deleteRecursively(file: File, onDeleted: ((File) -> Unit)? = null): Boolean {
        return try {
            if (file.isDirectory) {
                val children = file.listFiles() ?: return false
                for (child in children) if (!deleteRecursively(child, onDeleted)) return false
            }
            val deleted = file.delete()
            if (deleted) onDeleted?.invoke(file)
            deleted
        } catch (_: Exception) { false }
    }

    private fun showInfo(file: File) {
        val type = if (file.isDirectory) "Pasta" else "Ficheiro"
        val message = "Nome: " + file.name +
            "\nTipo: " + type +
            "\nTamanho: " + if (file.isDirectory) "—" else formatSize(file.length()) +
            "\nModificado: " + DateFormat.getDateTimeInstance().format(Date(file.lastModified())) +
            "\nPode ler: " + file.canRead() + "\nPode escrever: " + file.canWrite()
        AlertDialog.Builder(this).setTitle("Informações").setMessage(message).setPositiveButton("OK", null).show()
    }

    private fun chosen(): List<File> {
        return safeList(currentDir).filter { selected.contains(pathKey(it)) }
    }

    private fun chosenOrSingle(file: File): List<File> = chosen().ifEmpty { listOf(file) }

    private fun chosenOrVisible(): List<File> = chosen().ifEmpty { emptyList<File>() }

    private fun stageClipboard(cut: Boolean) {
        val items = chosen()
        if (items.isEmpty()) { toast("Seleciona primeiro os ficheiros ou pastas."); return }
        clipboard = items
        clipboardIsCut = cut
        selected.clear()
        toast(if (cut) "Preparado para mover. Abre o destino e toca em Colar." else "Preparado para copiar. Abre o destino e toca em Colar.")
        refreshFiles()
    }

    private fun paste() {
        if (clipboard.isEmpty()) { toast("Nada para colar."); return }
        val sources = clipboard.toList()
        val target = currentDir
        val cut = clipboardIsCut
        for (source in sources) {
            if (pathKey(source) == pathKey(target) || (source.isDirectory && isDescendant(target, source))) {
                toast("Não podes copiar ou mover uma pasta para dentro de si própria.")
                return
            }
        }
        val effectiveSources = sources.filterNot { cut && it.parentFile?.canonicalFile == target.canonicalFile }
        if (effectiveSources.isEmpty()) { toast("Os itens já estão nesta pasta."); return }
        val totals = measureFiles(effectiveSources)
        val totalItems = totals.entries * if (cut) 2L else 1L
        runTask(if (cut) "A mover…" else "A copiar…", totals.bytes, totalItems) { meter ->
            var count = 0
            for (source in effectiveSources) {
                val destination = File(target, uniqueName(target, source.name))
                meter.show(source)
                copyFileTree(source, destination, meter)
                if (cut && !deleteRecursively(source) { deleted -> meter.itemDone(deleted) }) {
                    throw IOException("Copiado, mas não foi possível remover a origem: " + source.name)
                }
                count++
            }
            clipboard = emptyList()
            clipboardIsCut = false
            selected.clear()
            (if (cut) "Movidos " else "Copiados ") + count + " item(ns)."
        }
    }

    private fun isDescendant(child: File, parent: File): Boolean {

    private fun isDescendant(child: File, parent: File): Boolean {
        val p = pathKey(parent)
        val c = pathKey(child)
        return c.startsWith(p + File.separator)
    }

    private fun uniqueName(directory: File, original: String): String {
        if (!File(directory, original).exists()) return original
        val dot = original.lastIndexOf('.')
        val stem = if (dot > 0) original.substring(0, dot) else original
        val ext = if (dot > 0) original.substring(dot) else ""
        var index = 2
        while (index < 100000) {
            val name = stem + " (" + index + ")" + ext
            if (!File(directory, name).exists()) return name
            index++
        }
        throw IOException("Não foi possível gerar um nome único.")
    }

    private fun copyFileTree(source: File, dest: File, meter: TaskProgress? = null) {
        if (source.isDirectory) {
            if (!dest.mkdirs()) throw IOException("Não foi possível criar " + dest.name)
            meter?.show(source)
            meter?.itemDone(source)
            source.listFiles()?.forEach { child ->
                copyFileTree(child, File(dest, child.name), meter)
            } ?: throw IOException("Não foi possível ler a pasta " + source.name)
        } else {
            if (dest.exists()) throw IOException("O destino já existe: " + dest.name)
            BufferedInputStream(FileInputStream(source)).use { input ->
                BufferedOutputStream(FileOutputStream(dest)).use { output ->
                    val buffer = ByteArray(32 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        meter?.bytesRead(count.toLong(), source)
                    }
                }
            }
            meter?.itemDone(source)
        }
    }

    private fun promptSearch() {
        val input = EditText(this).apply { hint = "Nome ou padrão, por exemplo *.zip"; setSingleLine(true) }
        AlertDialog.Builder(this).setTitle("Pesquisar ficheiros").setView(input)
            .setNegativeButton("Cancelar", null).setPositiveButton("Pesquisar") { _, _ ->
                val query = input.text.toString().trim().lowercase(Locale.ROOT)
                if (query.isBlank()) { toast("Escreve um nome para pesquisar."); return@setPositiveButton }
                val start = currentDir
                val entries = countFilesForSearch(start).coerceAtLeast(1L)
                runTask("A pesquisar…", 0L, entries) { meter ->
                    val hits = mutableListOf<File>()
                    searchTree(start, query, hits, 0, meter)
                    runOnUiThread { showSearchResults(hits) }
                    "Pesquisa concluída: " + hits.size + " resultado(s)."
                }
            }.show()
    }

    private fun searchTree(directory: File, query: String, hits: MutableList<File>, depth: Int, meter: TaskProgress? = null) {
        if (depth > 60 || hits.size >= 300) return
        val children = try { directory.listFiles() } catch (_: Exception) { null } ?: return
        for (child in children) {
            if (hits.size >= 300) return
            if (!showHidden && child.name.startsWith(".")) continue
            val name = child.name.lowercase(Locale.ROOT)
            val matches = if (query.contains('*') || query.contains('?')) {
                val regex = query.replace(".", "\\.").replace("*", ".*").replace("?", ".")
                name.matches(Regex("^" + regex + "$"))
            } else name.contains(query)
            if (matches) hits.add(child)
            if (child.isDirectory) searchTree(child, query, hits, depth + 1, meter)
            meter?.itemDone(child)
        }
    }

    private fun showSearchResults(hits: List<File>) {
        if (isFinishing) return
        if (hits.isEmpty()) { toast("Nenhum resultado encontrado."); return }
        val labels = hits.map { it.absolutePath.removePrefix(rootDir.absolutePath).trimStart(File.separatorChar) }.toTypedArray()
        AlertDialog.Builder(this).setTitle("Resultados (" + hits.size + ")").setItems(labels) { _, which ->
            val found = hits[which]
            currentDir = if (found.isDirectory) found else found.parentFile ?: rootDir
            refreshFiles()
            if (!found.isDirectory) showFileActions(found)
        }.setPositiveButton("Fechar", null).show()
    }

    private fun showFavorites() {
        if (favorites.isEmpty()) { toast("Ainda não existem Favoritos."); return }
        val files = favorites.map { File(it) }.filter { it.exists() }
        if (files.isEmpty()) { toast("Os Favoritos guardados já não existem."); return }
        val labels = files.map { it.absolutePath.removePrefix(rootDir.absolutePath).trimStart(File.separatorChar) }.toTypedArray()
        AlertDialog.Builder(this).setTitle("Favoritos").setItems(labels) { _, which ->
            val file = files[which]
            currentDir = if (file.isDirectory) file else file.parentFile ?: rootDir
            refreshFiles()
            if (!file.isDirectory) showFileActions(file)
        }.setPositiveButton("Fechar", null).show()
    }

    private fun promptArchive(files: List<File>, format: String) {
        if (files.isEmpty()) { toast("Seleciona pelo menos um ficheiro ou pasta."); return }
        val suffix = if (format == "7z") ".7z" else ".zip"
        val input = EditText(this).apply {
            setSingleLine(true)
            val suggestion = if (files.size == 1) files[0].name else "Arquivo"
            setText(if (suggestion.lowercase(Locale.ROOT).endsWith(suffix)) suggestion else suggestion + suffix)
        }
        val deleteOriginals = CheckBox(this).apply { text = "Eliminar originais depois de comprimir com sucesso" }
        val form = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; addView(input); addView(deleteOriginals) }
        AlertDialog.Builder(this).setTitle(if (format == "7z") "Criar arquivo 7Z" else "Criar arquivo ZIP")
            .setMessage("Compressão: " + compressionName()).setView(form)
            .setNegativeButton("Cancelar", null).setPositiveButton("Criar") { _, _ ->
                var name = input.text.toString().trim()
                if (name.isBlank() || !validName(name)) { toast("Nome inválido."); return@setPositiveButton }
                if (!name.lowercase(Locale.ROOT).endsWith(suffix)) name += suffix
                try { name = uniqueName(currentDir, name) } catch (_: Exception) { toast("Nome de destino inválido."); return@setPositiveButton }
                val destination = File(currentDir, name)
                val removeSources = deleteOriginals.isChecked
                val sources = files.toList()
                val totals = measureFiles(sources)
                runTask("A criar " + suffix + "…", totals.bytes, totals.entries * if (removeSources) 2L else 1L) { meter ->
                    try {
                        if (format == "7z") createSevenZip(sources, destination, meter) else createZip(sources, destination, meter)
                        if (removeSources) sources.forEach { source ->
                            meter.show(source)
                            if (!deleteRecursively(source) { deleted -> meter.itemDone(deleted) }) {
                                throw IOException("Arquivo criado, mas não foi possível eliminar " + source.name)
                            }
                        }
                    } catch (e: Exception) {
                        destination.delete()
                        throw e
                    }
                    selected.clear()
                    if (removeSources) "Arquivo criado; originais eliminados." else "Arquivo criado: " + destination.name
                }
            }.show()
    }

    private fun compressionName(): String = when

    private fun compressionName(): String = when (compressionLevel) {
        0 -> "sem compressão"
        1 -> "rápida"
        3 -> "ultra"
        else -> "normal"
    }

    private fun createZip(sources: List<File>, destination: File, meter: TaskProgress) {
        ZipOutputStream(BufferedOutputStream(FileOutputStream(destination))).use { zip ->
            zip.setLevel(when (compressionLevel) {
                0 -> java.util.zip.Deflater.NO_COMPRESSION
                1 -> java.util.zip.Deflater.BEST_SPEED
                3 -> java.util.zip.Deflater.BEST_COMPRESSION
                else -> java.util.zip.Deflater.DEFAULT_COMPRESSION
            })
            sources.forEach { source ->
                if (source.canonicalFile != destination.canonicalFile) addToZip(zip, source, source.name, destination, meter)
            }
        }
    }

    private fun addToZip(zip: ZipOutputStream, source: File, entryName: String, destination: File, meter: TaskProgress) {
        if (source.canonicalFile == destination.canonicalFile) return
        meter.show(source)
        if (source.isDirectory) {
            val name = if (entryName.endsWith("/")) entryName else entryName + "/"
            zip.putNextEntry(ZipEntry(name))
            zip.closeEntry()
            meter.itemDone(source)
            val children = source.listFiles() ?: throw IOException("Não foi possível ler " + source.name)
            children.forEach { addToZip(zip, it, name + it.name, destination, meter) }
        } else {
            zip.putNextEntry(ZipEntry(entryName))
            BufferedInputStream(FileInputStream(source)).use { input ->
                val buffer = ByteArray(32 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    zip.write(buffer, 0, n)
                    meter.bytesRead(n.toLong(), source)
                }
            }
            zip.closeEntry()
            meter.itemDone(source)
        }
    }

    private fun createSevenZip(sources: List<File>, destination: File, meter: TaskProgress) {
        SevenZOutputFile(destination).use { seven ->
            when (compressionLevel) {
                0 -> seven.setContentCompression(SevenZMethod.COPY)
                1 -> seven.setContentCompression(SevenZMethod.DEFLATE)
                3 -> seven.setContentCompression(SevenZMethod.BZIP2)
                else -> seven.setContentCompression(SevenZMethod.LZMA2)
            }
            sources.forEach { source ->
                if (source.canonicalFile != destination.canonicalFile) addToSevenZip(seven, source, source.name, destination, meter)
            }
        }
    }

    private fun addToSevenZip(seven: SevenZOutputFile, source: File, entryName: String, destination: File, meter: TaskProgress) {
        if (source.canonicalFile == destination.canonicalFile) return
        meter.show(source)
        val entry = SevenZArchiveEntry()
        entry.name = entryName
        entry.lastModifiedDate = Date(maxOf(0L, source.lastModified()))
        if (source.isDirectory) {
            entry.isDirectory = true
            entry.setHasStream(false)
            seven.putArchiveEntry(entry)
            seven.closeArchiveEntry()
            meter.itemDone(source)
            val children = source.listFiles() ?: throw IOException("Não foi possível ler " + source.name)
            children.forEach { addToSevenZip(seven, it, entryName + "/" + it.name, destination, meter) }
        } else {
            entry.isDirectory = false
            entry.setHasStream(true)
            entry.size = source.length()
            seven.putArchiveEntry(entry)
            BufferedInputStream(FileInputStream(source)).use { input ->
                val buffer = ByteArray(32 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    seven.write(buffer, 0, n)
                    meter.bytesRead(n.toLong(), source)
                }
            }
            seven.closeArchiveEntry()
            meter.itemDone(source)
        }
    }

    private fun extractZip(archive: File, destinationDir: File, makeSubfolder: Boolean) {
        runTrackedTask("A extrair ZIP…", {
            try { measureZipArchive(archive) } catch (_: Exception) { TaskTotals(0L, 1L) }
        }().bytes, {
            try { measureZipArchive(archive) } catch (_: Exception) { TaskTotals(0L, 1L) }
        }().entries) { meter ->
            val destination = if (makeSubfolder) File(destinationDir, uniqueName(destinationDir, archive.nameWithoutExtension)) else destinationDir
            if (makeSubfolder && !destination.mkdirs()) throw IOException("Não foi possível criar a pasta de destino.")
            var count = 0
            ZipInputStream(BufferedInputStream(FileInputStream(archive))).use { zip ->
                val buffer = ByteArray(32 * 1024)
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val output = safeArchiveTarget(destination, entry.name, entry.isDirectory)
                    meter.show(output)
                    if (entry.isDirectory) {
                        if (!output.exists() && !output.mkdirs()) throw IOException("Não foi possível criar " + output.name)
                    } else {
                        output.parentFile?.let { if (!it.exists() && !it.mkdirs()) throw IOException("Não foi possível criar uma pasta de destino.") }
                        val finalOut = if (output.exists()) File(output.parentFile, uniqueName(output.parentFile!!, output.name)) else output
                        BufferedOutputStream(FileOutputStream(finalOut)).use { stream ->
                            while (true) {
                                val n = zip.read(buffer)
                                if (n <= 0) break
                                stream.write(buffer, 0, n)
                                meter.bytesRead(n.toLong(), finalOut)
                            }
                        }
                    }
                    zip.closeEntry()
                    meter.itemDone(output)
                    count++
                }
            }
            "Extração ZIP concluída: " + count + " entradas."
        }
    }

    private fun extractSevenZip(archive: File, destinationDir: File, makeSubfolder: Boolean) {
        val totals = try { measureSevenZipArchive(archive) } catch (_: Exception) { TaskTotals(0L, 1L) }
        runTrackedTask("A extrair 7Z…", totals.bytes, totals.entries) { meter ->
            val destination = if (makeSubfolder) File(destinationDir, uniqueName(destinationDir, archive.nameWithoutExtension)) else destinationDir
            if (makeSubfolder && !destination.mkdirs()) throw IOException("Não foi possível criar a pasta de destino.")
            var count = 0
            SevenZFile(archive).use { seven ->
                val buffer = ByteArray(32 * 1024)
                while (true) {
                    val entry = seven.nextEntry ?: break
                    val output = safeArchiveTarget(destination, entry.name, entry.isDirectory)
                    meter.show(output)
                    if (entry.isDirectory) {
                        if (!output.exists() && !output.mkdirs()) throw IOException("Não foi possível criar " + output.name)
                    } else {
                        output.parentFile?.let { if (!it.exists() && !it.mkdirs()) throw IOException("Não foi possível criar a pasta de destino.") }
                        val finalOut = if (output.exists()) File(output.parentFile, uniqueName(output.parentFile!!, output.name)) else output
                        BufferedOutputStream(FileOutputStream(finalOut)).use { stream ->
                            while (true) {
                                val n = seven.read(buffer, 0, buffer.size)
                                if (n < 0) break
                                stream.write(buffer, 0, n)
                                meter.bytesRead(n.toLong(), finalOut)
                            }
                        }
                    }
                    meter.itemDone(output)
                    count++
                }
            }
            "Extração 7Z concluída: " + count + " entradas."
        }
    }

    private fun safeArchiveTarget(destination: File, entryName: String, directory: Boolean): File {
        val normalized = entryName.replace('\\', '/')
        if (normalized.startsWith("/") || normalized.matches(Regex("^[A-Za-z]:.*")) ||
            normalized.split('/').any { it == ".." || it == "." }) {
            throw IOException("O arquivo contém um caminho inseguro.")
        }
        val base = destination.canonicalFile
        val target = File(base, normalized).canonicalFile
        if (target.path != base.path && !target.path.startsWith(base.path + File.separator)) {
            throw IOException("O arquivo tenta escrever fora da pasta de destino.")
        }
        return target
    }

    private fun testZip(archive: File) {
        val totals = try { measureZipArchive(archive) } catch (_: Exception) { TaskTotals(0L, 1L) }
        runTrackedTask("A testar ZIP…", totals.bytes, totals.entries) { meter ->
            var count = 0
            ZipInputStream(BufferedInputStream(FileInputStream(archive))).use { zip ->
                val buffer = ByteArray(32 * 1024)
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val current = File(archive.parentFile, entry.name)
                    meter.show(current)
                    while (true) {
                        val n = zip.read(buffer)
                        if (n < 0) break
                        if (n > 0) meter.bytesRead(n.toLong(), current)
                    }
                    zip.closeEntry()
                    meter.itemDone(current)
                    count++
                }
            }
            "Teste ZIP concluído: " + count + " entradas."
        }
    }

    private fun testSevenZip(archive: File) {
        val totals = try { measureSevenZipArchive(archive) } catch (_: Exception) { TaskTotals(0L, 1L) }
        runTrackedTask("A testar 7Z…", totals.bytes, totals.entries) { meter ->
            var count = 0
            SevenZFile(archive).use { seven ->
                val buffer = ByteArray(32 * 1024)
                while (true) {
                    val entry = seven.nextEntry ?: break
                    val current = File(archive.parentFile, entry.name)
                    meter.show(current)
                    while (true) {
                        val n = seven.read(buffer, 0, buffer.size)
                        if (n < 0) break
                        if (n > 0) meter.bytesRead(n.toLong(), current)
                    }
                    meter.itemDone(current)
                    count++
                }
            }
            "Teste 7Z concluído: " + count + " entradas."
        }
    }

    private data class ArchiveItem

    private data class ArchiveItem(val index: Int, val name: String, val directory: Boolean, val size: Long)

    private fun browseArchive(archive: File) {
        val ext = extension(archive.name)
        runTask("A ler arquivo…") {
            val entries = mutableListOf<ArchiveItem>()
            if (ext == "zip") {
                ZipFile(archive).use { zip ->
                    val en = zip.entries()
                    var index = 0
                    while (en.hasMoreElements() && entries.size < 300) {
                        val e = en.nextElement()
                        entries += ArchiveItem(index++, e.name, e.isDirectory, e.size)
                    }
                }
            } else if (ext == "7z") {
                SevenZFile(archive).use { seven ->
                    var index = 0
                    for (e in seven.entries) {
                        if (entries.size >= 300) break
                        entries += ArchiveItem(index++, e.name, e.isDirectory, e.size)
                    }
                }
            } else throw IOException("Formato não suportado.")
            runOnUiThread { showArchiveItems(archive, entries) }
            "Conteúdo lido: " + entries.size + " entradas."
        }
    }

    private fun showArchiveItems(archive: File, entries: List<ArchiveItem>) {
        if (entries.isEmpty()) { toast("O arquivo está vazio."); return }
        val labels = entries.map { (if (it.directory) "📁  " else "📄  ") + it.name + if (!it.directory && it.size >= 0) "  •  " + formatSize(it.size) else "" }.toTypedArray()
        AlertDialog.Builder(this).setTitle(archive.name + if (entries.size == 300) " (primeiras 300 entradas)" else "")
            .setItems(labels) { _, which ->
                val item = entries[which]
                if (item.directory) {
                    AlertDialog.Builder(this).setTitle("Pasta do arquivo")
                        .setMessage("Usa «Extrair aqui» para extrair esta pasta juntamente com o restante conteúdo.")
                        .setPositiveButton("OK", null).show()
                } else {
                    AlertDialog.Builder(this).setTitle("Extrair item")
                        .setMessage("Extrair " + item.name + " para a pasta atual?")
                        .setNegativeButton("Cancelar", null)
                        .setPositiveButton("Extrair") { _, _ -> extractArchiveEntry(archive, item) }.show()
                }
            }.setPositiveButton("Fechar", null).show()
    }

    private fun extractArchiveEntry(archive: File, item: ArchiveItem) {
        runTrackedTask("A extrair item…", item.size.coerceAtLeast(0L), 1L) { meter ->
            val destination = safeArchiveTarget(currentDir, item.name, item.directory)
            if (item.directory) {
                if (!destination.exists() && !destination.mkdirs()) throw IOException("Não foi possível criar a pasta.")
            } else {
                when (extension(archive.name)) {
                    "zip" -> ZipFile(archive).use { zip ->
                        val entry = zip.getEntry(item.name) ?: throw IOException("A entrada já não existe.")
                        val target = if (destination.exists()) File(destination.parentFile, uniqueName(destination.parentFile!!, destination.name)) else destination
                        target.parentFile?.let { if (!it.exists() && !it.mkdirs()) throw IOException("Não foi possível criar a pasta.") }
                        BufferedInputStream(zip.getInputStream(entry)).use { input ->
                            BufferedOutputStream(FileOutputStream(target)).use { output ->
                                val buffer = ByteArray(32 * 1024)
                                while (true) {
                                    val n = input.read(buffer)
                                    if (n < 0) break
                                    output.write(buffer, 0, n)
                                    meter.bytesRead(n.toLong(), target)
                                }
                            }
                        }
                    }
                    "7z" -> {
                        var found = false
                        SevenZFile(archive).use { seven ->
                            var index = 0
                            while (true) {
                                val entry = seven.nextEntry ?: break
                                if (index++ != item.index) {
                                    if (!entry.isDirectory) {
                                        val buffer = ByteArray(32 * 1024)
                                        while (seven.read(buffer, 0, buffer.size) >= 0) { }
                                    }
                                    continue
                                }
                                found = true
                                val target = if (destination.exists()) File(destination.parentFile, uniqueName(destination.parentFile!!, destination.name)) else destination
                                target.parentFile?.let { if (!it.exists() && !it.mkdirs()) throw IOException("Não foi possível criar a pasta.") }
                                BufferedOutputStream(FileOutputStream(target)).use { out ->
                                    val buffer = ByteArray(32 * 1024)
                                    while (true) {
                                        val n = seven.read(buffer, 0, buffer.size)
                                        if (n < 0) break
                                        out.write(buffer, 0, n)
                                        meter.bytesRead(n.toLong(), target)
                                    }
                                }
                                break
                            }
                        }
                        if (!found) throw IOException("A entrada já não existe.")
                    }
                    else -> throw IOException("Formato não suportado.")
                }
            }
            meter.itemDone(destination)
            "Item extraído: " + item.name
        }
    }

    private fun showInfoPlaceholder() {}

    private fun shouldLoadThumbnail(file: File): Boolean {
        if (file.isDirectory || !showThumbnails) return false
        return when (extension(file.name)) {
            "jpg", "jpeg", "png", "webp", "gif", "bmp", "heic" -> true
            "pdf" -> showPdfThumbnails
            "mp4", "mkv", "webm", "3gp", "avi", "mov", "m4v" -> showVideoThumbnails
            "mp3", "wav", "ogg", "m4a", "flac", "aac", "opus" -> showAlbumCovers
            "apk" -> showApkIcons
            else -> false
        }
    }

    private fun thumbnailKey(file: File): String = file.absolutePath + ":" + file.lastModified() + ":" + file.length()

    private fun loadThumbnailAsync(file: File, view: ImageView) {
        val key = thumbnailKey(file)
        view.tag = key
        thumbnailCache.get(key)?.let { view.setImageBitmap(it); return }
        thumbnailWorker.execute {
            val bitmap = try { loadThumbnail(file) } catch (_: Exception) { null }
            if (bitmap != null) thumbnailCache.put(key, bitmap)
            runOnUiThread { if (!isFinishing && view.tag == key && bitmap != null) view.setImageBitmap(bitmap) }
        }
    }

    private fun loadThumbnail(file: File): Bitmap? {
        return when (extension(file.name)) {
            "jpg", "jpeg", "png", "webp", "gif", "bmp", "heic" -> {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.absolutePath, bounds)
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) null else {
                    val options = BitmapFactory.Options()
                    var sample = 1
                    while (bounds.outWidth / sample > 256 || bounds.outHeight / sample > 256) sample *= 2
                    options.inSampleSize = sample
                    scaleThumbnail(BitmapFactory.decodeFile(file.absolutePath, options))
                }
            }
            "pdf" -> if (showPdfThumbnails) pdfThumbnail(file) else null
            "mp4", "mkv", "webm", "3gp", "avi", "mov", "m4v" -> if (showVideoThumbnails) videoThumbnail(file) else null
            "mp3", "wav", "ogg", "m4a", "flac", "aac", "opus" -> if (showAlbumCovers) albumThumbnail(file) else null
            "apk" -> if (showApkIcons) apkThumbnail(file) else null
            else -> null
        }
    }

    private fun scaleThumbnail(source: Bitmap?): Bitmap? {
        if (source == null) return null
        val max = dp(96)
        val ratio = minOf(1f, minOf(max / source.width.toFloat(), max / source.height.toFloat()))
        if (ratio >= 1f) return source
        val scaled = Bitmap.createScaledBitmap(source, maxOf(1, (source.width * ratio).toInt()), maxOf(1, (source.height * ratio).toInt()), true)
        if (scaled != source) source.recycle()
        return scaled
    }

    private fun videoThumbnail(file: File): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            scaleThumbnail(if (Build.VERSION.SDK_INT >= 27) retriever.getScaledFrameAtTime(-1, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 128, 128) else retriever.frameAtTime)
        } finally { retriever.release() }
    }

    private fun albumThumbnail(file: File): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            val bytes = retriever.embeddedPicture ?: return null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            while (bounds.outWidth / sample > 256 || bounds.outHeight / sample > 256) sample *= 2
            scaleThumbnail(BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }))
        } finally { retriever.release() }
    }

    private fun pdfThumbnail(file: File): Bitmap? {
        val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        PdfRenderer(descriptor).use { renderer ->
            if (renderer.pageCount == 0) return null
            renderer.openPage(0).use { page ->
                val ratio = minOf(1f, 128f / maxOf(page.width, page.height))
                val bitmap = Bitmap.createBitmap(maxOf(1, (page.width * ratio).toInt()), maxOf(1, (page.height * ratio).toInt()), Bitmap.Config.ARGB_8888)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                return bitmap
            }
        }
    }

    private fun apkThumbnail(file: File): Bitmap? {
        val info: PackageInfo = if (Build.VERSION.SDK_INT >= 33) {
            packageManager.getPackageArchiveInfo(file.absolutePath, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            packageManager.getPackageArchiveInfo(file.absolutePath, 0)
        } ?: return null
        val appInfo: ApplicationInfo = info.applicationInfo ?: return null
        appInfo.sourceDir = file.absolutePath
        appInfo.publicSourceDir = file.absolutePath
        val icon: Drawable = packageManager.getApplicationIcon(appInfo)
        val bitmap = Bitmap.createBitmap(dp(64), dp(64), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        icon.setBounds(0, 0, canvas.width, canvas.height)
        icon.draw(canvas)
        return bitmap
    }

    private data class TaskTotals(val bytes: Long, val entries: Long)

    private inner class TaskProgress(
        private val totalBytes: Long,
        private val totalEntries: Long,
        private val publish: (Int, String) -> Unit
    ) {
        private var completedBytes = 0L
        private var completedEntries = 0L
        private val totalUnits = (totalBytes.coerceAtLeast(0L) + totalEntries.coerceAtLeast(1L)).coerceAtLeast(1L)

        @Synchronized
        fun show(file: File) = publish(progressPercent(), file.absolutePath)

        @Synchronized
        fun bytesRead(count: Long, file: File) {
            if (count > 0L) completedBytes = (completedBytes + count).coerceAtMost(totalBytes.coerceAtLeast(0L))
            publish(progressPercent(), file.absolutePath)
        }

        @Synchronized
        fun itemDone(file: File) {
            completedEntries = (completedEntries + 1L).coerceAtMost(totalEntries.coerceAtLeast(1L))
            publish(progressPercent(), file.absolutePath)
        }

        private fun progressPercent(): Int {
            val done = completedBytes + completedEntries
            return ((done.toDouble() / totalUnits.toDouble()) * 100.0).toInt().coerceIn(0, 99)
        }

        fun finish(detail: String) = publish(100, detail)
    }

    private fun measureFiles(files: List<File>): TaskTotals {
        var bytes = 0L
        var entries = 0L
        val visited = HashSet<String>()
        fun walk(file: File, depth: Int) {
            if (depth > 80 || entries >= 200000L) return
            val key = pathKey(file)
            if (!visited.add(key)) return
            entries++
            if (file.isFile) {
                bytes += file.length().coerceAtLeast(0L)
            } else if (file.isDirectory) {
                safeList(file).forEach { walk(it, depth + 1) }
            }
        }
        files.forEach { walk(it, 0) }
        return TaskTotals(bytes, entries.coerceAtLeast(1L))
    }

    private fun countFilesForSearch(directory: File): Long = measureFiles(listOf(directory)).entries

    private fun measureZipArchive(archive: File): TaskTotals {
        var bytes = 0L
        var entries = 0L
        ZipFile(archive).use { zip ->
            val all = zip.entries()
            while (all.hasMoreElements()) {
                val entry = all.nextElement()
                entries++
                if (!entry.isDirectory && entry.size > 0L) bytes += entry.size
            }
        }
        return TaskTotals(bytes, entries.coerceAtLeast(1L))
    }

    private fun measureSevenZipArchive(archive: File): TaskTotals {
        var bytes = 0L
        var entries = 0L
        SevenZFile(archive).use { seven ->
            while (true) {
                val entry = seven.nextEntry ?: break
                entries++
                if (!entry.isDirectory && entry.size > 0L) bytes += entry.size
            }
        }
        return TaskTotals(bytes, entries.coerceAtLeast(1L))
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                notificationChannelId,
                "Operações de ficheiros",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Progresso e conclusão das operações do Archiver." }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun showOperationNotification(title: String, detail: String, percent: Int?, finished: Boolean = false, failed: Boolean = false) {
        try {
            ensureNotificationChannel()
            val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            val pendingIntent = PendingIntent.getActivity(
                this, operationNotificationId, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val builder = NotificationCompat.Builder(this, notificationChannelId)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentIntent(pendingIntent)
                .setContentTitle(if (finished) if (failed) "Archiver — erro" else "Archiver — concluído" else title)
                .setContentText(detail)
                .setOnlyAlertOnce(true)
                .setOngoing(!finished)
                .setAutoCancel(finished)
                .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            if (finished || percent == null) builder.setProgress(0, 0, false)
            else builder.setProgress(100, percent.coerceIn(0, 100), false)
            getSystemService(NotificationManager::class.java).notify(operationNotificationId, builder.build())
        } catch (_: SecurityException) {
            // Android 13+: notifications may be denied; in-app progress still works.
        } catch (_: Exception) { }
    }

    private fun runTask(message: String, action: () -> String) {
        runTrackedTask(message, 0L, 1L) { meter ->
            meter.show(currentDir)
            val result = action()
            meter.itemDone(currentDir)
            result
        }
    }

    private fun runTask(message: String, totalBytes: Long, totalEntries: Long, action: (TaskProgress) -> String) {
        runTrackedTask(message, totalBytes, totalEntries, action)
    }

    private fun runTrackedTask(message: String, totalBytes: Long, totalEntries: Long, action: (TaskProgress) -> String) {
        if (!workActive.compareAndSet(false, true)) {
            toast("Já existe uma operação em curso. Aguarda que termine.")
            return
        }
        ensureNotificationChannel()
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 742)
        }

        val progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
        }
        val percentView = label("0%", 14, 0xFFD8D3FF.toInt(), true).apply { gravity = Gravity.END }
        val detailView = label(message, 12, 0xFFB9B9C3.toInt(), false).apply {
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            setPadding(0, dp(10), 0, 0)
        }
        val dialogContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(12), dp(22), dp(12))
            minimumWidth = dp(280)
            addView(progressBar, LinearLayout.LayoutParams(-1, dp(8)))
            addView(percentView, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
            addView(detailView, LinearLayout.LayoutParams(-1, -2))
        }
        val dialog = AlertDialog.Builder(this).setTitle(message).setView(dialogContent).setCancelable(false).create()
        dialog.show()
        var lastPercent = -1
        var lastDetail = ""
        var lastNoticeAt = 0L
        val meter = TaskProgress(totalBytes, totalEntries) { percent, detail ->
            runOnUiThread {
                if (!isFinishing && dialog.isShowing) {
                    progressBar.progress = percent
                    percentView.text = "$percent%"
                    detailView.text = detail
                }
            }
            val now = android.os.SystemClock.elapsedRealtime()
            val notificationDue = synchronized(this) {
                val due = percent != lastPercent || (detail != lastDetail && now - lastNoticeAt >= 800L)
                if (due) {
                    lastPercent = percent
                    lastDetail = detail
                    lastNoticeAt = now
                }
                due
            }
            if (notificationDue) showOperationNotification(message, detail, percent)
        }
        showOperationNotification(message, currentDir.absolutePath, 0)
        worker.execute {
            var result: String
            try {
                result = action(meter)
            } catch (e: Exception) {
                result = "Erro: " + (e.message ?: e.javaClass.simpleName)
            } finally {
                workActive.set(false)
            }
            val finalResult = result
            runOnUiThread {
                if (dialog.isShowing) dialog.dismiss()
                if (!isFinishing) {
                    percentView.text = "100%"
                    progressBar.progress = 100
                    detailView.text = finalResult
                    Toast.makeText(this, finalResult, Toast.LENGTH_LONG).show()
                    if (hasStorageAccess() && browserRendered && !settingsOpen) refreshFiles()
                }
                showOperationNotification(message, finalResult, null, finished = true, failed = finalResult.startsWith("Erro:"))
            }
        }
    }

    private fun toolbarIcon(resource: Int, description: String, click: () -> Unit): ImageButton =
        ImageButton(this).apply {
            setImageResource(resource)
            contentDescription = description
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(11), dp(11), dp(11), dp(11))
            background = GradientDrawable().apply {
                setColor(0x00292D40)
                cornerRadius = dp(24).toFloat()
            }
            isClickable = true
            isFocusable = true
            setOnClickListener { click() }
        }

    private fun selectionAction(symbol: String, title: String, click: () -> Unit): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            setPadding(dp(1), dp(2), dp(1), dp(2))
            addView(TextView(this@MainActivity).apply {
                text = symbol
                textSize = if (symbol == "ZIP") 13f else 22f
                gravity = Gravity.CENTER
                setTextColor(0xFFD8D3FF.toInt())
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                maxLines = 1
            }, LinearLayout.LayoutParams(-1, dp(30)))
            addView(TextView(this@MainActivity).apply {
                text = title
                textSize = 10f
                gravity = Gravity.CENTER
                setTextColor(0xFFF0F0F4.toInt())
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(-1, dp(22)))
            setOnClickListener { click() }
        }

    private fun roundedBackground(color: Int, radiusDp: Int): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radiusDp).toFloat()
        }

    private fun label(text: String, size: Int, color: Int, bold: Boolean): TextView = TextView(this).apply {
        this.text = text
        textSize = size.toFloat()
        setTextColor(color)
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
    }

    private fun iconButton(symbol: String, description: String, click: () -> Unit): TextView =
        TextView(this).apply {
            text = symbol
            contentDescription = description
            textSize = 27f
            gravity = Gravity.CENTER
            setTextColor(0xFFFFFFFF.toInt())
            isClickable = true
            isFocusable = true
            setOnClickListener { click() }
        }

    private fun actionButton(text: String, click: () -> Unit): Button = Button(this).apply {
        this.text = text
        textSize = 12f
        isAllCaps = false
        setOnClickListener { click() }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    private fun extension(name: String): String = name.substringAfterLast('.', "").lowercase(Locale.ROOT)

    private fun formatSize(bytes: Long): String = when {
        bytes < 1024 -> bytes.toString() + " B"
        bytes < 1024L * 1024L -> String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0)
        bytes < 1024L * 1024L * 1024L -> String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024))
        else -> String.format(Locale.ROOT, "%.2f GB", bytes / (1024.0 * 1024 * 1024))
    }

    private fun onOff(value: Boolean): String = if (value) "Ligado" else "Desligado"

    private fun toast(message: String) {
        runOnUiThread { Toast.makeText(this, message, Toast.LENGTH_LONG).show() }
    }

    override fun onBackPressed() {
        if (settingsOpen) {
            settingsOpen = false
            renderBrowser()
            return
        }

        if (selected.isNotEmpty()) {
            selected.clear()
            refreshFiles()
        } else if (::currentDir.isInitialized && currentDir.canonicalFile != rootDir.canonicalFile) {
            goUp()
        } else {
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        worker.shutdown()
        thumbnailWorker.shutdown()
        super.onDestroy()
    }
}
