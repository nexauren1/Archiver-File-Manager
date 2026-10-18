package com.nexauren.archiver

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.ProgressDialog
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
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
import android.media.MediaPlayer
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
import android.widget.AdapterView
import android.widget.ArrayAdapter
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
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.sevenz.SevenZMethod
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import net.lingala.zip4j.ZipFile as Zip4jFile
import net.lingala.zip4j.model.ZipParameters
import net.lingala.zip4j.progress.ProgressMonitor
import org.json.JSONObject
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
import java.security.MessageDigest
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
    private var settingsCategory: String? = null
    private var editorOpen = false
    private var editorDirty = false
    private var editorSavedText = ""
    private var editorFile: File? = null
    private var editorInput: EditText? = null
    private var closeEditorAfterSave = false
    private var appLanguage = "pt"
    private var uiTheme = "Escuro"
    private var accentName = "Lavanda"
    private val folderColors = linkedMapOf<String, String>()

    private val selected = linkedSetOf<String>()
    private val rowViews = linkedMapOf<String, View>()
    private val rowNameViews = linkedMapOf<String, TextView>()
    private var visibleFileCount = 0
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
        val launches = prefs.getInt("launch_count", 0) + 1
        prefs.edit().putInt("launch_count", launches).apply()
        if (browserRendered && launches >= 3 && !prefs.getBoolean("share_prompt_dismissed", false)) {
            page.postDelayed({ maybeAskToShareApp() }, 1600L)
        }
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
        appLanguage = prefs.getString("app_language", "pt") ?: "pt"
        uiTheme = prefs.getString("ui_theme", "Escuro") ?: "Escuro"
        accentName = prefs.getString("accent_color", "Lavanda") ?: "Lavanda"
        folderColors.clear()
        (prefs.getStringSet("folder_colors", emptySet()) ?: emptySet()).forEach { entry ->
            val index = entry.lastIndexOf('=')
            if (index > 0) folderColors[entry.substring(0, index)] = entry.substring(index + 1)
        }
        favorites.clear()
        favorites.addAll(prefs.getStringSet("favorites", emptySet()) ?: emptySet())
        applyPaletteToWindow()
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
            .putString("app_language", appLanguage)
            .putString("ui_theme", uiTheme)
            .putString("accent_color", accentName)
            .putStringSet("folder_colors", folderColors.map { (path, color) -> "$path=$color" }.toSet())
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
            setBackgroundColor(pageBackgroundColor())
            layoutDirection = if (appLanguage == "ar") View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
        }
        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), 0, dp(4), 0)
            setBackgroundColor(toolbarBackgroundColor())
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
        page.addView(View(this).apply { setBackgroundColor(accentColor()) },
            LinearLayout.LayoutParams(-1, dp(2)))

        val breadcrumbScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            clipToPadding = false
            setPadding(dp(8), 0, dp(8), 0)
            setBackgroundColor(panelBackgroundColor())
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
            setBackgroundColor(panelBackgroundColor())
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
            setBackgroundColor(toolbarBackgroundColor())
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
        if (!browserRendered || settingsOpen || editorOpen || !hasStorageAccess()) return
        rows.removeAllViews()
        rowViews.clear()
        rowNameViews.clear()
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
        visibleFileCount = files.size
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
            background = roundedBackground(
                if (isSelected) selectedRowColor() else if (inGrid) rowSurfaceColor() else pageBackgroundColor(),
                if (inGrid) 10 else 4
            )
            isClickable = true
            isFocusable = true
        }
        val icon = ImageView(this).apply {
            setImageResource(iconForFile(file))
            contentDescription = if (file.isDirectory) "Pasta " + file.name else file.name
            scaleType = if (squareThumbnails) ImageView.ScaleType.CENTER_CROP else ImageView.ScaleType.FIT_CENTER
            if (file.isDirectory && !shouldLoadThumbnail(file)) {
                folderColorValue(file)?.let { setColorFilter(it, android.graphics.PorterDuff.Mode.SRC_IN) }
            }
        }
        val iconWidth = dp(if (inGrid) 72 else 48)
        val iconHeight = dp(if (inGrid) 66 else 48)
        if (inGrid) item.addView(icon, LinearLayout.LayoutParams(iconWidth, iconHeight).apply { gravity = Gravity.CENTER_HORIZONTAL })
        else item.addView(icon, LinearLayout.LayoutParams(iconWidth, iconHeight).apply { rightMargin = dp(8) })
        if (shouldLoadThumbnail(file)) loadThumbnailAsync(file, icon)

        // Do not enumerate every child folder while drawing the list; that blocks scrolling on large directories.
        val details = if (file.isDirectory) tr("Pasta") else formatSize(file.length())
        val modified = if (file.lastModified() > 0L) DateFormat.getDateInstance(DateFormat.SHORT).format(Date(file.lastModified())) else ""
        val middle = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = if (inGrid) Gravity.CENTER_HORIZONTAL else Gravity.CENTER_VERTICAL
            setPadding(if (inGrid) 0 else dp(4), 0, if (inGrid) 0 else dp(4), 0)
        }
        val name = TextView(this).apply {
            text = file.name.ifBlank { "(sem nome)" }
            textSize = if (inGrid) 13f else if (viewMode == "Compacto") 13f else 15f
            setTextColor(if (isSelected) accentColor() else primaryTextColor())
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
            setTextColor(mutedTextColor())
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            gravity = if (inGrid) Gravity.CENTER else Gravity.START
            setPadding(0, dp(4), 0, 0)
        }, LinearLayout.LayoutParams(-1, -2))
        rowViews[key] = item
        rowNameViews[key] = name
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
        if (file.isDirectory) {
            return when (file.name.lowercase(Locale.ROOT)) {
                "android" -> R.drawable.ic_folder_android
                "dcim", "pictures", "picture", "collage", "myalbums" -> R.drawable.ic_folder_camera
                "download", "downloads" -> R.drawable.ic_folder_download
                "music", "musicas" -> R.drawable.ic_folder_music
                "movies", "videos" -> R.drawable.ic_folder_video
                "documents", "documentos" -> R.drawable.ic_folder_documents
                else -> R.drawable.ic_folder
            }
        }
        return when (extension(file.name)) {
            "zip" -> R.drawable.ic_zip_file
            "7z" -> R.drawable.ic_7z_file
            "rar" -> R.drawable.ic_rar_file
            "tar", "tgz", "tbz" -> R.drawable.ic_tar_file
            "gz", "bz2", "xz", "lz4", "zst", "iso", "cab", "arj" -> R.drawable.ic_archive
            "jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "avif", "tif", "tiff", "svg" -> R.drawable.ic_image
            "mp3", "wav", "ogg", "m4a", "flac", "aac", "opus", "wma", "mid", "midi" -> R.drawable.ic_audio
            "mp4", "mkv", "webm", "avi", "mov", "3gp", "m4v", "mpeg", "mpg" -> R.drawable.ic_video
            "apk", "aab", "apks", "xapk" -> R.drawable.ic_android_file
            "pdf" -> R.drawable.ic_pdf
            "txt", "text", "md", "markdown", "log", "readme", "nfo" -> R.drawable.ic_text_file
            "json", "xml", "yaml", "yml", "properties", "ini", "cfg", "conf", "toml",
            "java", "kt", "kts", "js", "ts", "tsx", "jsx", "py", "html", "htm", "css",
            "scss", "sql", "sh", "bat", "c", "cpp", "h", "hpp", "gradle", "gitignore" -> R.drawable.ic_code_file
            "doc", "docx", "odt", "rtf", "pages", "epub" -> R.drawable.ic_document_file
            "xls", "xlsx", "ods", "csv", "tsv" -> R.drawable.ic_spreadsheet_file
            "glb", "gltf", "obj", "stl", "fbx", "3mf", "dae", "ply", "blend" -> R.drawable.ic_model3d_file
            else -> R.drawable.ic_file
        }
    }

    private fun fileGlyph(file: File): String {
        if (file.isDirectory) return "📁"
        return when (extension(file.name)) {
            "zip", "7z", "rar", "tar", "gz", "bz2", "tgz", "tbz", "tbz2" -> "🗜"
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
        rowViews[key]?.background = roundedBackground(
            if (selected.contains(key)) selectedRowColor() else if (gridRows != null) rowSurfaceColor() else pageBackgroundColor(),
            if (gridRows != null) 10 else 4
        )
        rowNameViews[key]?.apply {
            setTextColor(if (selected.contains(key)) accentColor() else primaryTextColor())
            typeface = if (selected.contains(key)) android.graphics.Typeface.DEFAULT_BOLD else android.graphics.Typeface.DEFAULT
        }
        countView.text = when {
            selected.isNotEmpty() -> "${selected.size} selecionado(s) • ${visibleFileCount} itens"
            visibleFileCount == 0 -> tr("Pasta vazia")
            else -> visibleFileCount.toString() + if (visibleFileCount == 1) " item" else " itens"
        }
        updateSelectionBar()
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
            "Criar novo…", "Comprimir em ZIP", "Comprimir em 7Z", "Pesquisa avançada", "Favoritos",
            "Ordenar", "Definições do explorador", "Atualizar", "Pasta de início", "Partilhar o Archiver"
        )
        AlertDialog.Builder(this).setTitle(tr("Opções")).setItems(options.map { tr(it) }.toTypedArray()) { _, which ->
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
                14 -> shareApplication()
            }
        }.show()
    }

    private fun showSelectionActions() {
        val options = arrayOf("Comprimir em ZIP", "Comprimir em 7Z", "Renomear", "Informações", "Limpar seleção")
        AlertDialog.Builder(this)
            .setTitle("${selected.size} selecionado(s)")
            .setItems(options.map { tr(it) }.toTypedArray()) { _, which ->
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
        AlertDialog.Builder(this).setTitle(tr("Ordenar ficheiros")).setItems(items.map { tr(it) }.toTypedArray()) { _, which ->
            if (which < 4) sortBy = items[which] else descending = !descending
            savePreferences()
            if (settingsOpen) renderSettingsScreen() else refreshFiles()
        }.show()
    }

    private fun showExplorerSettings() {
        settingsOpen = true
        settingsCategory = null
        renderSettingsScreen()
    }

    private fun renderSettingsScreen() {
        val category = settingsCategory
        page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(pageBackgroundColor())
            layoutDirection = if (appLanguage == "ar") View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
        }
        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), 0, dp(12), 0)
            setBackgroundColor(toolbarBackgroundColor())
        }
        toolbar.addView(toolbarIcon(R.drawable.ic_arrow_back, tr("Voltar")) {
            if (settingsCategory != null) {
                settingsCategory = null
                renderSettingsScreen()
            } else {
                settingsOpen = false
                renderBrowser()
            }
        }, LinearLayout.LayoutParams(dp(48), dp(58)))
        toolbar.addView(label(tr(category ?: "Configurações"), 22, primaryTextColor(), true),
            LinearLayout.LayoutParams(0, dp(58), 1f))
        page.addView(toolbar, LinearLayout.LayoutParams(-1, dp(58)))
        page.addView(View(this).apply { setBackgroundColor(accentColor()) },
            LinearLayout.LayoutParams(-1, dp(2)))

        val scroll = ScrollView(this).apply { isFillViewport = true }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(6), dp(16), dp(18))
        }
        scroll.addView(content)
        page.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        when (category) {
            null -> {
                addSettingChoice(content, "Geral", "12 idiomas", "Idioma, notificações e informações") {
                    settingsCategory = "Geral"; renderSettingsScreen()
                }
                addSettingChoice(content, "Interface", uiTheme, "Tema, cor de destaque e aparência") {
                    settingsCategory = "Interface"; renderSettingsScreen()
                }
                addSettingChoice(content, "Explorador", tr(viewMode), "Ordenação, ficheiros ocultos e miniaturas") {
                    settingsCategory = "Explorador"; renderSettingsScreen()
                }
                addSettingChoice(content, "Compressão", compressionName(), "Formato, velocidade e opções de arquivos") {
                    settingsCategory = "Compressão"; renderSettingsScreen()
                }
            }
            "Geral" -> {
                addSettingChoice(content, "Idioma", languageDisplayName(appLanguage),
                    "Escolher o idioma da interface") { showLanguageMenu() }
                val notificationsEnabled = Build.VERSION.SDK_INT < 33 ||
                    ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
                addSettingChoice(content, "Notificações", if (notificationsEnabled) "Ligado" else "Desligado",
                    "Progresso e conclusão das operações de ficheiros") {
                    if (Build.VERSION.SDK_INT >= 33 && !notificationsEnabled) {
                        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 742)
                    } else {
                        toast(if (notificationsEnabled) "As notificações estão disponíveis." else "Ativa as notificações nas definições do Android.")
                    }
                }
                addSettingChoice(content, "Partilhar o Archiver", "Ajudar o projeto", "Convida alguém a experimentar o Archiver") {
                    shareApplication()
                }
                addSettingChoice(content, "Sobre o Archiver", "0.6.0", "Gestor de ficheiros Android nativo em Kotlin") {
                    AlertDialog.Builder(this).setTitle("Archiver 0.6.0")
                        .setMessage(tr("Gestor de ficheiros Android independente, inspirado em exploradores clássicos. ZIP/7Z, editor de texto, miniaturas e operações com progresso."))
                        .setPositiveButton(tr("Fechar"), null).show()
                }
            }
            "Interface" -> {
                addSettingChoice(content, "Tema da interface", tr(uiTheme), "Escolhe entre escuro, preto OLED e claro") { showThemeMenu() }
                addSettingChoice(content, "Cor de destaque", tr(accentName), "Cor dos botões, seleção e indicadores") { showAccentMenu() }
                addSettingChoice(content, "Cores das pastas", "Por pasta", "Escolhe uma cor no menu de cada pasta") {
                    toast("Abre o menu de uma pasta e escolhe Cor da pasta.")
                }
            }
            "Explorador" -> {
                addSettingChoice(content, "Ordenar arquivos", tr(sortBy), "Escolher a ordem dos ficheiros") { showSortMenu() }
                addSettingSwitch(content, "Decrescente", "Inverte a ordem de ordenação dos arquivos", descending) { descending = it }
                addSettingSwitch(content, "Usar item \"..\" ", "Permite subir para o diretório anterior a partir da lista", useParentEntry) { useParentEntry = it }
                addSettingSwitch(content, "Exibir arquivos ocultos", "Mostra ficheiros e diretórios cujo nome começa por ponto", showHidden) { showHidden = it }
                addSettingChoice(content, "Modo de exibição", tr(viewMode), "Detalhado, compacto ou grelha") { showViewModeMenu() }
                addSettingSwitch(content, "Exibir tamanho", "Mostra o tamanho dos ficheiros na lista", showSize) { showSize = it }
                addSettingSwitch(content, "Exibir data", "Mostra a data da última modificação", showDate) { showDate = it }
                addSettingSwitch(content, "Exibir hora", "Mostra a hora da última modificação", showTime, showDate) { showTime = it }
                addSettingSwitch(content, "Miniaturas", "Permite a exibição de miniaturas", showThumbnails) { showThumbnails = it }
                addSettingSwitch(content, "Miniaturas do PDF", "Mostrar miniaturas de documentos PDF", showPdfThumbnails) { showPdfThumbnails = it }
                addSettingSwitch(content, "Miniaturas de vídeo", "Mostrar miniaturas de vídeo", showVideoThumbnails) { showVideoThumbnails = it }
                addSettingSwitch(content, "Capa do álbum", "Mostrar a capa do álbum nos ficheiros de música", showAlbumCovers) { showAlbumCovers = it }
                addSettingSwitch(content, "Ícones do APK", "Exibir os ícones das aplicações APK", showApkIcons) { showApkIcons = it }
                addSettingSwitch(content, "Miniaturas quadradas", "Recorta as miniaturas para formato quadrado", squareThumbnails) { squareThumbnails = it }
            }
            "Compressão" -> {
                addSettingChoice(content, "Nível de compressão", compressionName(), "Sem compressão, rápida, normal ou ultra") { showCompressionMenu() }
                content.addView(label(
                    "ZIP: suporta ZipCrypto, AES-256 e divisão em volumes. 7Z: criação e extração com níveis de compressão; palavra-passe e volumes não estão ligados nesta versão.",
                    14, mutedTextColor(), false
                ).apply { setPadding(dp(8), dp(16), dp(8), dp(16)) }, LinearLayout.LayoutParams(-1, -2))
            }
        }
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
        copy.addView(label(tr(title), 16, primaryTextColor(), false))
        copy.addView(label(tr(value), 14, accentColor(), false).apply { setPadding(0, dp(4), 0, 0) })
        copy.addView(label(tr(summary), 12, mutedTextColor(), false).apply { setPadding(0, dp(3), 0, 0) })
        row.addView(copy, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(TextView(this).apply {
            text = "›"
            textSize = 28f
            gravity = Gravity.CENTER
            setTextColor(accentColor())
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
        copy.addView(label(tr(title), 16, if (enabled) primaryTextColor() else 0xFF777780.toInt(), false))
        copy.addView(label(tr(summary), 13, if (enabled) mutedTextColor() else 0xFF777780.toInt(), false).apply { setPadding(0, dp(4), 0, 0) })
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
        val archiveKind = archiveKind(file)
        if (archiveKind.isNotEmpty()) {
            options += "Ver conteúdo"
            options += "Testar arquivo"
            options += "Extrair aqui"
            options += "Extrair para nova pasta"
        }
        if (ext in setOf("glb", "gltf")) options += "Inspecionar modelo 3D"
        options += listOf("Comprimir para ZIP", "Comprimir para 7Z", "Comprimir…")
        options += listOf("Partilhar", "Copiar caminho", "Copiar", "Recortar", "Renomear", "Eliminar", "Informações")
        if (file.isFile) options += "Calcular SHA-256"
        options += if (favorites.contains(pathKey(file))) "Remover dos Favoritos" else "Adicionar aos Favoritos"
        if (file.isDirectory) options += "Calcular tamanho total"
        if (file.isDirectory) options += "Cor da pasta"
        AlertDialog.Builder(this).setTitle(file.name).setItems(options.map { tr(it) }.toTypedArray()) { _, which ->
            when (options[which]) {
                "Abrir pasta" -> { currentDir = file; selected.clear(); refreshFiles() }
                "Cor da pasta" -> showFolderColorMenu(file)
                "Calcular tamanho total" -> calculateFolderSize(file)
                "Abrir" -> openFile(file)
                "Inspecionar modelo 3D" -> showModelInspector(file)
                "Comprimir para ZIP" -> promptArchive(chosenOrSingle(file), "zip")
                "Comprimir para 7Z" -> promptArchive(chosenOrSingle(file), "7z")
                "Comprimir…" -> promptArchive(chosenOrSingle(file), if (ext == "7z") "7z" else "zip")
                "Ver conteúdo" -> browseArchive(file)
                "Testar arquivo" -> when (archiveKind) {
                    "7z" -> testSevenZip(file)
                    "zip" -> testZip(file)
                    else -> testTarArchive(file)
                }
                "Extrair aqui" -> when (archiveKind) {
                    "7z" -> extractSevenZip(file, currentDir, false)
                    "zip" -> extractZip(file, currentDir, false)
                    else -> extractTarArchive(file, currentDir, false)
                }
                "Extrair para nova pasta" -> when (archiveKind) {
                    "7z" -> extractSevenZip(file, currentDir, true)
                    "zip" -> extractZip(file, currentDir, true)
                    else -> extractTarArchive(file, currentDir, true)
                }
                "Partilhar" -> shareFile(file)
                "Copiar caminho" -> copyPath(file)
                "Calcular SHA-256" -> calculateSha256(file)
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
        val ext = extension(file.name)
        if (isEditableTextFile(file) && file.length() <= 4L * 1024L * 1024L) {
            showTextEditor(file)
            return
        }
        if (ext in setOf("glb", "gltf")) {
            showModelInspector(file)
            return
        }
        if (ext == "pdf") {
            showPdfReader(file)
            return
        }
        if (ext in setOf("jpg", "jpeg", "png", "webp", "gif", "bmp")) {
            showImageViewer(file)
            return
        }
        if (ext in setOf("mp3", "m4a", "aac", "ogg", "wav", "flac", "opus", "wma", "mid", "midi")) {
            showAudioPlayer(file)
            return
        }
        openFileExternally(file)
    }

    private fun showPdfReader(file: File) {
        val image = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            adjustViewBounds = true
            setBackgroundColor(0xFF303036.toInt())
        }
        val status = label("A abrir PDF…", 13, mutedTextColor(), false)
        val previous = Button(this).apply { text = tr("Anterior"); isAllCaps = false; isEnabled = false }
        val next = Button(this).apply { text = tr("Seguinte"); isAllCaps = false; isEnabled = false }
        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            addView(previous, LinearLayout.LayoutParams(0, dp(46), 1f).apply { rightMargin = dp(6) })
            addView(next, LinearLayout.LayoutParams(0, dp(46), 1f).apply { leftMargin = dp(6) })
        }
        val scroll = ScrollView(this).apply {
            isFillViewport = false
            addView(image, FrameLayout.LayoutParams(-1, -2))
        }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(6), dp(8), dp(8))
            addView(status, LinearLayout.LayoutParams(-1, -2).apply {
                bottomMargin = dp(6); leftMargin = dp(4); rightMargin = dp(4)
            })
            addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
            addView(controls, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        }
        var dialog: AlertDialog? = null
        var requestedPage = 0
        var pageCount = 0
        fun renderPage(index: Int) {
            requestedPage = index
            status.text = tr("A abrir PDF…")
            previous.isEnabled = false
            next.isEnabled = false
            image.setImageDrawable(null)
            thumbnailWorker.execute {
                var count = 0
                var rendered: Bitmap? = null
                var error: String? = null
                try {
                    ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                        PdfRenderer(descriptor).use { renderer ->
                            count = renderer.pageCount
                            if (index !in 0 until count) throw IOException("Página PDF inválida.")
                            renderer.openPage(index).use { pdfPage ->
                                val width = (resources.displayMetrics.widthPixels - dp(48)).coerceIn(320, 1200)
                                val height = (width.toDouble() * pdfPage.height / pdfPage.width).toInt().coerceIn(1, 2600)
                                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                                bitmap.eraseColor(0xFFFFFFFF.toInt())
                                pdfPage.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                rendered = bitmap
                            }
                        }
                    }
                } catch (e: Exception) {
                    error = e.message ?: tr("Não foi possível abrir PDF.")
                }
                runOnUiThread {
                    if (dialog?.isShowing == true && requestedPage == index) {
                        pageCount = count
                        if (rendered != null) {
                            image.setImageBitmap(rendered)
                            status.text = "${index + 1} / ${pageCount}"
                            previous.isEnabled = index > 0
                            next.isEnabled = index + 1 < pageCount
                        } else {
                            status.text = tr("Não foi possível abrir PDF.") + ": " + (error ?: "")
                        }
                    } else {
                        rendered?.recycle()
                    }
                }
            }
        }
        val builtDialog = AlertDialog.Builder(this)
            .setTitle(tr("Leitor de PDF") + " — " + file.name)
            .setView(body)
            .setPositiveButton(tr("Abrir noutra aplicação")) { _, _ -> openFileExternally(file) }
            .setNegativeButton(tr("Fechar"), null)
            .create()
        dialog = builtDialog
        previous.setOnClickListener { if (requestedPage > 0) renderPage(requestedPage - 1) }
        next.setOnClickListener { if (requestedPage + 1 < pageCount) renderPage(requestedPage + 1) }
        builtDialog.setOnDismissListener { image.setImageDrawable(null) }
        builtDialog.show()
        renderPage(0)
    }

    private fun showImageViewer(file: File) {
        val image = ImageView(this).apply {
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(pageBackgroundColor())
        }
        val status = label("A carregar imagem…", 13, mutedTextColor(), false)
        val scroll = ScrollView(this).apply {
            isFillViewport = false
            addView(image, FrameLayout.LayoutParams(-1, -2))
        }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
            addView(status, LinearLayout.LayoutParams(-1, -2).apply {
                bottomMargin = dp(6); leftMargin = dp(4)
            })
            addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(file.name)
            .setView(body)
            .setPositiveButton(tr("Abrir noutra aplicação")) { _, _ -> openFileExternally(file) }
            .setNegativeButton(tr("Fechar"), null)
            .create()
        var decoded: Bitmap? = null
        dialog.setOnDismissListener {
            image.setImageDrawable(null)
            decoded?.let { if (!it.isRecycled) it.recycle() }
        }
        dialog.show()
        thumbnailWorker.execute {
            var bitmap: Bitmap? = null
            var error: String? = null
            try {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.absolutePath, bounds)
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IOException("Ficheiro de imagem inválido.")
                val maxWidth = (resources.displayMetrics.widthPixels - dp(40)).coerceAtLeast(320)
                val maxHeight = (resources.displayMetrics.heightPixels - dp(200)).coerceAtLeast(320)
                var sample = 1
                while (bounds.outWidth / sample > maxWidth * 2 || bounds.outHeight / sample > maxHeight * 2) sample *= 2
                bitmap = BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply {
                    inSampleSize = sample.coerceAtLeast(1)
                }) ?: throw IOException("Ficheiro de imagem inválido.")
            } catch (e: Exception) {
                error = e.message ?: tr("Não foi possível abrir a imagem.")
            }
            runOnUiThread {
                if (dialog.isShowing && bitmap != null) {
                    decoded = bitmap
                    image.setImageBitmap(bitmap)
                    status.text = tr("Pré-visualização da imagem")
                } else if (dialog.isShowing) {
                    status.text = tr("Não foi possível abrir a imagem.") + ": " + (error ?: "")
                } else {
                    bitmap?.recycle()
                }
            }
        }
    }

    private fun showAudioPlayer(file: File) {
        val status = label("A preparar áudio…", 14, mutedTextColor(), false)
        val play = Button(this).apply { text = tr("Reproduzir"); isAllCaps = false; isEnabled = false }
        val stop = Button(this).apply { text = tr("Parar"); isAllCaps = false; isEnabled = false }
        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            addView(play, LinearLayout.LayoutParams(0, dp(48), 1f).apply { rightMargin = dp(6) })
            addView(stop, LinearLayout.LayoutParams(0, dp(48), 1f).apply { leftMargin = dp(6) })
        }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(8))
            addView(status, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(14) })
            addView(controls, LinearLayout.LayoutParams(-1, -2))
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(tr("Leitor de áudio") + " — " + file.name)
            .setView(body)
            .setPositiveButton(tr("Abrir noutra aplicação")) { _, _ -> openFileExternally(file) }
            .setNegativeButton(tr("Fechar"), null)
            .create()
        val player = MediaPlayer()
        var prepared = false
        play.setOnClickListener {
            if (!prepared) return@setOnClickListener
            try {
                if (player.isPlaying) {
                    player.pause()
                    play.text = tr("Reproduzir")
                } else {
                    player.start()
                    play.text = tr("Pausar")
                }
            } catch (e: Exception) {
                status.text = tr("Não foi possível reproduzir o áudio.") + ": " + (e.message ?: "")
            }
        }
        stop.setOnClickListener {
            if (!prepared) return@setOnClickListener
            runCatching { player.pause(); player.seekTo(0) }
            play.text = tr("Reproduzir")
        }
        player.setOnPreparedListener { preparedPlayer ->
            prepared = true
            play.isEnabled = true
            stop.isEnabled = true
            val seconds = (preparedPlayer.duration / 1000).coerceAtLeast(0)
            status.text = tr("Pronto") + " • ${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
        }
        player.setOnCompletionListener {
            play.text = tr("Reproduzir")
            status.text = tr("Reprodução concluída.")
        }
        player.setOnErrorListener { _, _, _ ->
            status.text = tr("Não foi possível reproduzir o áudio.")
            play.isEnabled = false
            stop.isEnabled = false
            true
        }
        dialog.setOnDismissListener {
            runCatching { if (player.isPlaying) player.stop() }
            runCatching { player.release() }
        }
        dialog.show()
        try {
            player.setDataSource(file.absolutePath)
            player.prepareAsync()
        } catch (e: Exception) {
            status.text = tr("Não foi possível reproduzir o áudio.") + ": " + (e.message ?: "")
        }
    }

    private fun openFileExternally(file: File) {
        try {
            val uri = FileProvider.getUriForFile(this, packageName + ".fileprovider", file)
            val mime = when (extension(file.name)) {
                "glb" -> "model/gltf-binary"
                "gltf" -> "model/gltf+json"
                "obj" -> "text/plain"
                "stl" -> "model/stl"
                else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension(file.name)) ?: "*/*"
            }
            val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(Intent.createChooser(intent, tr("Abrir ficheiro")))
        } catch (_: ActivityNotFoundException) {
            toast("Não existe uma aplicação compatível para abrir este ficheiro.")
        } catch (e: Exception) {
            toast("Não foi possível abrir: " + (e.message ?: "erro desconhecido"))
        }
    }

    private fun showModelInspector(file: File) {
        try {
            val jsonText = if (extension(file.name) == "gltf") {
                if (file.length() > 32L * 1024L * 1024L) throw IOException("O manifesto GLTF excede o limite de leitura de 32 MB.")
                file.readText(Charsets.UTF_8)
            } else {
                readGlbJsonChunk(file)
            }
            val json = JSONObject(jsonText)
            val asset = json.optJSONObject("asset")
            fun count(key: String): Int = json.optJSONArray(key)?.length() ?: 0
            val formatLabel = if (extension(file.name) == "glb") "GLB (binário)" else "GLTF (JSON)"
            val details = listOf(
                "Formato: $formatLabel",
                "Versão GLTF: " + (asset?.optString("version", "desconhecida") ?: "desconhecida"),
                "Cenas: " + count("scenes"),
                "Objetos/nós: " + count("nodes"),
                "Meshes: " + count("meshes"),
                "Materiais: " + count("materials"),
                "Texturas: " + count("textures"),
                "Imagens: " + count("images"),
                "Animações: " + count("animations"),
                "Buffers: " + count("buffers"),
                "Tamanho do ficheiro: " + formatSize(file.length()),
                "",
                "Esta versão lê os metadados do modelo; a renderização 3D interativa ainda requer uma aplicação compatível."
            ).joinToString("\n")
            AlertDialog.Builder(this)
                .setTitle("Leitor de modelo 3D")
                .setMessage(details)
                .setPositiveButton("Abrir noutra aplicação") { _, _ -> openFileExternally(file) }
                .setNegativeButton(tr("Fechar"), null)
                .show()
        } catch (e: Exception) {
            AlertDialog.Builder(this)
                .setTitle("Modelo 3D")
                .setMessage("Não foi possível ler os metadados do modelo.\n" + (e.message ?: "Formato inválido."))
                .setPositiveButton("Tentar aplicação externa") { _, _ -> openFileExternally(file) }
                .setNegativeButton(tr("Fechar"), null)
                .show()
        }
    }

    private fun readGlbJsonChunk(file: File): String {
        java.io.DataInputStream(BufferedInputStream(FileInputStream(file))).use { input ->
            val header = ByteArray(12)
            input.readFully(header)
            val headerBuffer = java.nio.ByteBuffer.wrap(header).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            if (headerBuffer.int != 0x46546C67) throw IOException("Assinatura GLB inválida.")
            val version = headerBuffer.int
            if (version != 2) throw IOException("Só é suportado GLB 2.0.")
            val declaredLength = headerBuffer.int.toLong() and 0xFFFFFFFFL
            if (declaredLength < 20L || declaredLength > file.length()) throw IOException("Comprimento GLB inválido.")
            var consumed = 12L
            while (consumed + 8L <= declaredLength) {
                val chunkHeader = ByteArray(8)
                input.readFully(chunkHeader)
                val chunkBuffer = java.nio.ByteBuffer.wrap(chunkHeader).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                val chunkLength = chunkBuffer.int.toLong() and 0xFFFFFFFFL
                val chunkType = chunkBuffer.int
                consumed += 8L
                if (chunkLength > declaredLength - consumed || chunkLength > 32L * 1024L * 1024L) {
                    throw IOException("Bloco JSON GLB demasiado grande ou inválido.")
                }
                if (chunkType == 0x4E4F534A) {
                    val data = ByteArray(chunkLength.toInt())
                    input.readFully(data)
                    return String(data, Charsets.UTF_8).trimEnd('\u0000', ' ', '\r', '\n', '\t')
                }
                var remaining = chunkLength
                val buffer = ByteArray(8192)
                while (remaining > 0L) {
                    val amount = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                    if (amount < 0) throw IOException("O ficheiro GLB terminou inesperadamente.")
                    remaining -= amount.toLong()
                }
                consumed += chunkLength
            }
        }
        throw IOException("O ficheiro GLB não contém um bloco JSON válido.")
    }

    private fun isEditableTextFile(file: File): Boolean = extension(file.name) in setOf(
        "txt", "text", "md", "markdown", "log", "nfo", "readme", "json", "xml", "yaml", "yml",
        "properties", "ini", "cfg", "conf", "toml", "java", "kt", "kts", "js", "ts", "tsx",
        "jsx", "py", "html", "htm", "css", "scss", "sql", "sh", "bat", "c", "cpp", "h",
        "hpp", "gradle", "gitignore", "csv", "tsv"
    )

    private fun showTextEditor(file: File) {
        val sourceText = try {
            file.readText(Charsets.UTF_8)
        } catch (e: Exception) {
            toast("Não foi possível ler o ficheiro: " + (e.message ?: "erro"))
            return
        }
        editorOpen = true
        editorDirty = false
        editorSavedText = sourceText
        editorFile = file
        closeEditorAfterSave = false

        page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(pageBackgroundColor())
            layoutDirection = if (appLanguage == "ar") View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
        }
        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), 0, dp(8), 0)
            setBackgroundColor(toolbarBackgroundColor())
        }
        toolbar.addView(toolbarIcon(R.drawable.ic_arrow_back, "Voltar ao gestor") { requestLeaveTextEditor() },
            LinearLayout.LayoutParams(dp(48), dp(58)))
        toolbar.addView(label(file.name, 16, 0xFFFFFFFF.toInt(), true).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
        }, LinearLayout.LayoutParams(0, dp(58), 1f))
        toolbar.addView(actionButton("Guardar") { saveTextEditor() }, LinearLayout.LayoutParams(-2, dp(44)))
        page.addView(toolbar, LinearLayout.LayoutParams(-1, dp(58)))
        page.addView(View(this).apply { setBackgroundColor(accentColor()) }, LinearLayout.LayoutParams(-1, dp(2)))

        val status = label("UTF-8  •  " + sourceText.count { it == '\n' }.plus(1) + " linhas", 11, 0xFFAAAAB4.toInt(), false)
            .apply { setPadding(dp(14), dp(8), dp(14), dp(8)) }
        val input = EditText(this).apply {
            setText(sourceText)
            setTextColor(primaryTextColor())
            setHintTextColor(0xFF8F8F99.toInt())
            textSize = 14f
            typeface = android.graphics.Typeface.MONOSPACE
            gravity = Gravity.TOP or Gravity.START
            setPadding(dp(14), dp(12), dp(14), dp(24))
            setBackgroundColor(pageBackgroundColor())
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI
            setHorizontallyScrolling(false)
        }
        editorInput = input
        input.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(text: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(text: CharSequence?, start: Int, before: Int, count: Int) {
                editorDirty = text?.toString() != editorSavedText
                status.text = "UTF-8  •  " + (text?.count { it == '\n' } ?: 0).plus(1) + " linhas" +
                    if (editorDirty) "  •  alterações por guardar" else ""
            }
            override fun afterTextChanged(text: android.text.Editable?) = Unit
        })
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(input, FrameLayout.LayoutParams(-1, -1))
        }
        page.addView(status, LinearLayout.LayoutParams(-1, -2))
        page.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(page)
        input.requestFocus()
    }

    private fun saveTextEditor() {
        val target = editorFile ?: return
        val content = editorInput?.text?.toString() ?: return
        val bytes = content.toByteArray(Charsets.UTF_8)
        runTask("A guardar texto…", bytes.size.toLong(), 1L) { meter ->
            meter.show(target)
            val parent = target.parentFile ?: throw IOException("A pasta do ficheiro não está disponível.")
            val temp = File.createTempFile(".archiver-", ".tmp", parent)
            val backup = File.createTempFile(".archiver-backup-", ".bak", parent)
            backup.delete()
            try {
                FileOutputStream(temp).use { output ->
                    var offset = 0
                    while (offset < bytes.size) {
                        val amount = minOf(32 * 1024, bytes.size - offset)
                        output.write(bytes, offset, amount)
                        offset += amount
                        meter.bytesRead(amount.toLong(), target)
                    }
                }
                if (!target.renameTo(backup)) throw IOException("Não foi possível proteger a versão anterior do ficheiro.")
                if (!temp.renameTo(target)) {
                    backup.renameTo(target)
                    throw IOException("Não foi possível substituir o ficheiro original.")
                }
                backup.delete()
                meter.itemDone(target)
                "Ficheiro guardado: " + target.name
            } finally {
                temp.delete()
                if (backup.exists() && target.exists()) backup.delete()
            }
        }
    }

    private fun requestLeaveTextEditor() {
        if (!editorDirty) {
            closeTextEditor()
            return
        }
        AlertDialog.Builder(this).setTitle("Alterações por guardar")
            .setMessage("Queres guardar as alterações antes de sair do editor?")
            .setPositiveButton("Sair sem guardar") { _, _ -> closeTextEditor() }
            .setNegativeButton("Continuar a editar", null)
            .setNeutralButton("Guardar") { _, _ ->
                closeEditorAfterSave = true
                saveTextEditor()
            }.show()
    }

    private fun closeTextEditor() {
        editorOpen = false
        editorDirty = false
        editorSavedText = ""
        editorFile = null
        editorInput = null
        closeEditorAfterSave = false
        renderBrowser()
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
                runTask("A eliminar ficheiros…", 0L, 1L) { meter ->
                    val totals = measureFiles(files) { meter.show(it) }
                    meter.setTotals(0L, totals.entries)
                    files.forEach { file ->
                        meter.show(file)
                        if (!deleteRecursively(
                                file,
                                onDeleting = { working -> meter.show(working) },
                                onDeleted = { deleted -> meter.itemDone(deleted) }
                            )) {
                            throw IOException("Não foi possível eliminar " + file.name)
                        }
                    }
                    "Eliminação concluída: " + totals.entries + " itens."
                }
            }.show()
    }

    private fun deleteRecursively(
        file: File,
        onDeleting: ((File) -> Unit)? = null,
        onDeleted: ((File) -> Unit)? = null
    ): Boolean {
        return try {
            onDeleting?.invoke(file)
            if (file.isDirectory) {
                val children = file.listFiles() ?: return false
                for (child in children) {
                    if (!deleteRecursively(child, onDeleting, onDeleted)) return false
                }
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
        runTask(if (cut) "A mover…" else "A copiar…", 0L, 1L) { meter ->
            val totals = measureFiles(effectiveSources) { meter.show(it) }
            meter.setTotals(totals.bytes, totals.entries * if (cut) 2L else 1L)
            var count = 0
            for (source in effectiveSources) {
                val destination = File(target, uniqueName(target, source.name))
                meter.show(source)
                copyFileTree(source, destination, meter)
                if (cut && !deleteRecursively(
                    source,
                    onDeleting = { working -> meter.show(working) },
                    onDeleted = { deleted -> meter.itemDone(deleted) }
                )) {
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
        val input = EditText(this).apply {
            hint = tr("Nome ou padrão, por exemplo *.zip")
            setSingleLine(true)
            textSize = 16f
        }
        val recurse = CheckBox(this).apply {
            text = tr("Incluir subpastas")
            isChecked = false
        }
        val typeLabels = arrayOf(
            tr("Todos os tipos"), tr("Pastas"), tr("Documentos"), tr("Imagens"),
            tr("Vídeo"), tr("Áudio"), tr("Arquivos"), tr("Aplicações")
        )
        val typeSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, typeLabels)
        }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(4), dp(20), dp(4))
            addView(input, LinearLayout.LayoutParams(-1, dp(52)))
            addView(typeSpinner, LinearLayout.LayoutParams(-1, dp(48)))
            addView(recurse, LinearLayout.LayoutParams(-1, dp(48)))
            addView(label(tr("A pesquisa começa na pasta atual e não procura fora dela."), 12, mutedTextColor(), false)
                .apply { setPadding(0, dp(5), 0, dp(8)) }, LinearLayout.LayoutParams(-1, -2))
        }
        AlertDialog.Builder(this)
            .setTitle(tr("Pesquisa avançada"))
            .setView(body)
            .setNegativeButton(tr("Cancelar"), null)
            .setPositiveButton(tr("Pesquisar")) { _, _ ->
                val query = input.text.toString().trim()
                if (query.isBlank()) {
                    toast("Escreve um nome para pesquisar.")
                    return@setPositiveButton
                }
                val start = try { currentDir.canonicalFile } catch (_: Exception) { currentDir }
                val includeSubfolders = recurse.isChecked
                val typeFilter = arrayOf("all", "folder", "document", "image", "video", "audio", "archive", "app")
                    .getOrElse(typeSpinner.selectedItemPosition) { "all" }
                runTask("A pesquisar…", 0L, 100L) { meter ->
                    val hits = mutableListOf<File>()
                    val processed = longArrayOf(0L)
                    val matcher = if (query.contains('*') || query.contains('?')) {
                        val pattern = buildString {
                            query.forEach { ch ->
                                when (ch) {
                                    '*' -> append(".*")
                                    '?' -> append('.')
                                    else -> append(Regex.escape(ch.toString()))
                                }
                            }
                        }
                        runCatching { Regex("^$pattern$", RegexOption.IGNORE_CASE) }.getOrNull()
                    } else null
                    searchTree(start, start, query, matcher, typeFilter, includeSubfolders, hits, 0, processed, meter)
                    runOnUiThread { showSearchResults(hits, start) }
                    "Pesquisa concluída: " + hits.size + " resultado(s)."
                }
            }
            .show()
    }

    private fun searchTree(
        directory: File,
        scope: File,
        query: String,
        matcher: Regex?,
        typeFilter: String,
        recursive: Boolean,
        hits: MutableList<File>,
        depth: Int,
        processed: LongArray,
        meter: TaskProgress? = null
    ) {
        if (depth > 50 || hits.size >= 100 || processed[0] >= 10000L) return
        // Search doesn't need display sorting; avoid extra work during recursive scans.
        val children = try { directory.listFiles()?.asList() ?: emptyList() }
            catch (_: SecurityException) { emptyList() }
        for (child in children) {
            if (hits.size >= 100 || processed[0] >= 10000L) return
            if (!showHidden && child.name.startsWith(".")) continue
            val matchesName = if (matcher != null) matcher.matches(child.name) else child.name.contains(query, ignoreCase = true)
            if (matchesName && matchesSearchType(child, typeFilter)) hits.add(child)
            processed[0]++
            if (processed[0] % 250L == 0L) {
                meter?.report(0, tr("Analisados") + ": " + processed[0] + " • " + child.name)
            }
            if (recursive && child.isDirectory && isWithinRoot(child) && isWithinScope(child, scope)) {
                searchTree(child, scope, query, matcher, typeFilter, true, hits, depth + 1, processed, meter)
            }
        }
    }

    private fun isWithinScope(file: File, scope: File): Boolean {
        val base = pathKey(scope).trimEnd(File.separatorChar)
        val path = pathKey(file)
        return path == base || path.startsWith(base + File.separator)
    }

    private fun matchesSearchType(file: File, filter: String): Boolean {
        if (filter == "all") return true
        if (filter == "folder") return file.isDirectory
        if (file.isDirectory) return false
        val ext = extension(file.name)
        return when (filter) {
            "document" -> ext in setOf("pdf", "txt", "md", "rtf", "doc", "docx", "odt", "xls", "xlsx", "ppt", "pptx", "csv")
            "image" -> ext in setOf("png", "jpg", "jpeg", "webp", "gif", "bmp", "svg", "heic", "avif")
            "video" -> ext in setOf("mp4", "mkv", "mov", "webm", "avi", "3gp", "m4v")
            "audio" -> ext in setOf("mp3", "m4a", "aac", "ogg", "wav", "flac", "opus", "wma")
            "archive" -> ext in setOf("zip", "7z", "rar", "tar", "gz", "bz2", "xz", "zst", "tgz", "tbz", "tbz2")
            "app" -> ext in setOf("apk", "apks", "xapk", "aab")
            else -> true
        }
    }

    private fun showSearchResults(hits: List<File>, searchedFolder: File) {
        if (isFinishing) return
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(6), dp(12), dp(8))
        }
        val summary = if (hits.isEmpty()) {
            tr("Nenhum resultado encontrado.")
        } else {
            tr("Pasta pesquisada") + ": " + searchedFolder.name + " • " + hits.size + " " + tr("resultados")
        }
        content.addView(label(summary, 13, mutedTextColor(), false)
            .apply { setPadding(dp(5), dp(5), dp(5), dp(10)) }, LinearLayout.LayoutParams(-1, -2))
        val scroll = ScrollView(this).apply { isFillViewport = false }
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val visibleHits = hits.take(100)
        var resultsDialog: AlertDialog? = null
        visibleHits.forEach { found ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(6), dp(7), dp(4), dp(7))
                background = roundedBackground(rowSurfaceColor(), 8)
                isClickable = true
                isFocusable = true
            }
            val icon = ImageView(this).apply {
                setImageResource(iconForFile(found))
                scaleType = ImageView.ScaleType.FIT_CENTER
                contentDescription = if (found.isDirectory) tr("Pasta") else tr("Ficheiro")
            }
            row.addView(icon, LinearLayout.LayoutParams(dp(42), dp(42)).apply { rightMargin = dp(8) })
            val details = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
            }
            details.addView(label(found.name, 14, primaryTextColor(), true).apply {
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            })
            details.addView(label(found.parentFile?.absolutePath ?: found.absolutePath, 11, mutedTextColor(), false).apply {
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
                setPadding(0, dp(3), 0, 0)
            })
            val meta = if (found.isFile) formatSize(found.length()) else tr("Pasta")
            details.addView(label(meta, 11, accentColor(), false).apply { setPadding(0, dp(3), 0, 0) })
            row.addView(details, LinearLayout.LayoutParams(0, -2, 1f))
            val more = TextView(this).apply {
                text = "⋮"
                textSize = 26f
                gravity = Gravity.CENTER
                setTextColor(accentColor())
                contentDescription = tr("Mais ações")
                setPadding(dp(8), 0, dp(8), 0)
                setOnClickListener { showFileActions(found) }
            }
            row.addView(more, LinearLayout.LayoutParams(dp(42), dp(52)))
            row.setOnClickListener {
                resultsDialog?.dismiss()
                if (found.isDirectory) {
                    currentDir = found
                    selected.clear()
                    refreshFiles()
                } else {
                    currentDir = found.parentFile ?: rootDir
                    selected.clear()
                    refreshFiles()
                    showFileActions(found)
                }
            }
            list.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(4) })
        }
        if (hits.size > visibleHits.size) {
            list.addView(label(tr("Mostrando os primeiros 100 resultados."), 12, mutedTextColor(), false)
                .apply { setPadding(dp(8), dp(8), dp(8), dp(8)) }, LinearLayout.LayoutParams(-1, -2))
        }
        scroll.addView(list)
        content.addView(scroll, LinearLayout.LayoutParams(-1, dp(420)))
        resultsDialog = AlertDialog.Builder(this)
            .setTitle(tr("Resultados da pesquisa") + " (" + hits.size + ")")
            .setView(content)
            .setPositiveButton(tr("Fechar"), null)
            .create()
        resultsDialog?.show()
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

    private fun promptArchive(files: List<File>, initialFormat: String) {
        if (files.isEmpty()) { toast("Seleciona pelo menos um ficheiro ou pasta."); return }

        val formatNames = arrayOf("ZIP", "7Z", "TAR", "TAR.GZ", "TAR.BZ2")
        val initialIndex = when (initialFormat) { "7z" -> 1; "tar" -> 2; "tgz" -> 3; "tbz2" -> 4; else -> 0 }
        val formatPicker = makeSpinner(formatNames, initialIndex)
        val levelNames = arrayOf("Sem compressão", "Rápida", "Normal", "Ultra")
        val levelPicker = makeSpinner(levelNames, compressionLevel.coerceIn(0, 3))
        val encryptionNames = arrayOf("Nenhuma", "ZipCrypto", "AES-256")
        val encryptionPicker = makeSpinner(encryptionNames)
        val volumeNames = arrayOf("Não", "64 MB", "100 MB", "250 MB", "500 MB", "1 GB")
        val volumeBytes = longArrayOf(
            0L,
            64L * 1024L * 1024L,
            100L * 1024L * 1024L,
            250L * 1024L * 1024L,
            500L * 1024L * 1024L,
            1024L * 1024L * 1024L
        )
        val volumePicker = makeSpinner(volumeNames)
        val passwordInput = EditText(this).apply {
            hint = "Palavra-passe (opcional)"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine(true)
        }
        val suffix = archiveSuffix(initialFormat)
        val input = EditText(this).apply {
            setSingleLine(true)
            val suggestion = if (files.size == 1) files[0].name else "Arquivo"
            setText(stripArchiveSuffix(suggestion) + suffix)
        }
        val deleteOriginals = CheckBox(this).apply { text = "Eliminar originais depois de comprimir com sucesso" }
        val separateArchives = CheckBox(this).apply { text = "Criar um arquivo separado por item selecionado" }

        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(4), dp(20), dp(6))
        }
        fun addField(title: String, view: View) {
            form.addView(label(title, 13, 0xFFAAAAB4.toInt(), false).apply {
                setPadding(0, dp(9), 0, dp(3))
            }, LinearLayout.LayoutParams(-1, -2))
            form.addView(view, LinearLayout.LayoutParams(-1, dp(46)))
        }
        addField("Arquivo", input)
        addField("Formato do arquivo", formatPicker)
        addField("Nível de compressão", levelPicker)
        addField("Criptografia (ZIP)", encryptionPicker)
        form.addView(passwordInput, LinearLayout.LayoutParams(-1, dp(48)))
        addField("Dividir em volumes (ZIP)", volumePicker)
        form.addView(deleteOriginals, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        form.addView(separateArchives, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })

        fun updateZipOptions() {
            val zipSelected = formatPicker.selectedItemPosition == 0
            encryptionPicker.isEnabled = zipSelected
            volumePicker.isEnabled = zipSelected
            passwordInput.isEnabled = zipSelected && encryptionPicker.selectedItemPosition > 0
            levelPicker.isEnabled = formatPicker.selectedItemPosition <= 1
            if (!zipSelected) {
                encryptionPicker.setSelection(0)
                volumePicker.setSelection(0)
                passwordInput.setText("")
            }
        }
        formatPicker.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                updateZipOptions()
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
        }
        encryptionPicker.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                passwordInput.isEnabled = formatPicker.selectedItemPosition == 0 && position > 0
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
        }
        updateZipOptions()

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(form, FrameLayout.LayoutParams(-1, -2))
        }
        AlertDialog.Builder(this).setTitle("Criação de arquivo")
            .setMessage("Escolhe o formato, a compressão e, para ZIP, opções de segurança e volumes.")
            .setView(scroll)
            .setNegativeButton(tr("Cancelar"), null)
            .setPositiveButton(tr("Criar"), null)
            .create().also { dialog ->
                dialog.setOnShowListener {
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val archiveFormat = when (formatPicker.selectedItemPosition) {
                            1 -> "7z"
                            2 -> "tar"
                            3 -> "tgz"
                            4 -> "tbz2"
                            else -> "zip"
                        }
                        val chosenSuffix = archiveSuffix(archiveFormat)
                        val encryption = encryptionNames[encryptionPicker.selectedItemPosition]
                        val password = passwordInput.text.toString()
                        val splitSize = if (archiveFormat == "zip") volumeBytes[volumePicker.selectedItemPosition] else 0L
                        if (archiveFormat == "7z" && (encryption != "Nenhuma" || splitSize > 0L)) {
                            toast("Senha e volumes divididos estão disponíveis apenas para ZIP nesta versão.")
                            return@setOnClickListener
                        }
                        if (encryption != "Nenhuma" && password.isBlank()) {
                            passwordInput.error = "Escreve uma palavra-passe."
                            passwordInput.requestFocus()
                            return@setOnClickListener
                        }

                        var name = input.text.toString().trim()
                        if (name.isBlank() || !validName(name)) { toast("Nome inválido."); return@setOnClickListener }
                        name = stripArchiveSuffix(name) + chosenSuffix
                        val sources = files.toList()
                        val removeSources = deleteOriginals.isChecked
                        val separate = separateArchives.isChecked
                        compressionLevel = levelPicker.selectedItemPosition
                        savePreferences()

                        val outputFiles = if (separate) {
                            val reserved = linkedSetOf<String>()
                            sources.map { source ->
                                val stem = source.nameWithoutExtension.ifBlank { source.name }
                                var candidate = stem + chosenSuffix
                                var index = 2
                                while (File(currentDir, candidate).exists() || !reserved.add(candidate)) {
                                    candidate = "$stem ($index)$chosenSuffix"
                                    index++
                                }
                                File(currentDir, candidate)
                            }
                        } else {
                            val unique = try { uniqueName(currentDir, name) } catch (_: Exception) {
                                toast("Nome de destino inválido.")
                                return@setOnClickListener
                            }
                            listOf(File(currentDir, unique))
                        }

                        dialog.dismiss()
                        runTask(if (separate) "A criar arquivos separados…" else "A criar $chosenSuffix…", 0L, 1L) { meter ->
                            val totals = measureFiles(sources) { meter.show(it) }
                            meter.setTotals(totals.bytes, totals.entries * if (removeSources) 2L else 1L)
                            try {
                                if (separate) {
                                    sources.zip(outputFiles).forEach { (source, destination) ->
                                        meter.show(source)
                                        createArchive(
                                            listOf(source), destination, meter, archiveFormat,
                                            password.takeIf { encryption != "Nenhuma" }, encryption, splitSize
                                        )
                                    }
                                } else {
                                    val destination = outputFiles.single()
                                    createArchive(
                                        sources, destination, meter, archiveFormat,
                                        password.takeIf { encryption != "Nenhuma" }, encryption, splitSize
                                    )
                                }
                            } catch (e: Exception) {
                                outputFiles.forEach { deleteArchiveOutputs(it) }
                                throw e
                            }

                            if (removeSources) sources.forEach { source ->
                                meter.show(source)
                                if (!deleteRecursively(
                                    source,
                                    onDeleting = { working -> meter.show(working) },
                                    onDeleted = { deleted -> meter.itemDone(deleted) }
                                )) {
                                    throw IOException("Arquivo criado, mas não foi possível eliminar " + source.name)
                                }
                            }
                            selected.clear()
                            val preview = outputFiles.take(3).joinToString(", ") { it.name }
                            val more = if (outputFiles.size > 3) " e mais ${outputFiles.size - 3}" else ""
                            if (removeSources) "Criado(s) ${outputFiles.size} arquivo(s); originais eliminados."
                            else "Criado(s) ${outputFiles.size} arquivo(s): $preview$more"
                        }
                    }
                }
                dialog.show()
            }
    }

    private fun makeSpinner(options: Array<String>, selectedIndex: Int = 0): Spinner =
        Spinner(this).apply {
            adapter = ArrayAdapter<String>(
                this@MainActivity,
                android.R.layout.simple_spinner_item,
                options.toList()
            ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
            setSelection(selectedIndex.coerceIn(0, options.lastIndex))
        }
    private fun compressionName(): String = when (compressionLevel) {
        0 -> "sem compressão"
        1 -> "rápida"
        3 -> "ultra"
        else -> "normal"
    }

    private fun createZip(
        sources: List<File>,
        destination: File,
        meter: TaskProgress,
        password: String? = null,
        encryption: String = "Nenhuma",
        splitBytes: Long = 0L
    ) {
        if (!password.isNullOrBlank() || splitBytes > 0L) {
            createZipWithZip4j(sources, destination, meter, password, encryption, splitBytes)
            return
        }
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

    private data class ZipFileSource(val file: File, val entryName: String)
    private data class ZipFolderSource(val file: File, val entryName: String, val empty: Boolean)

    private fun zip4jParameters(entryName: String? = null, encryption: String = "Nenhuma"): ZipParameters =
        ZipParameters().apply {
            setCompressionMethod(
                if (this@MainActivity.compressionLevel == 0) net.lingala.zip4j.model.enums.CompressionMethod.STORE
                else net.lingala.zip4j.model.enums.CompressionMethod.DEFLATE
            )
            if (this@MainActivity.compressionLevel != 0) {
                setCompressionLevel(when (this@MainActivity.compressionLevel) {
                    1 -> net.lingala.zip4j.model.enums.CompressionLevel.FASTEST
                    3 -> net.lingala.zip4j.model.enums.CompressionLevel.ULTRA
                    else -> net.lingala.zip4j.model.enums.CompressionLevel.NORMAL
                })
            }
            if (entryName != null) setFileNameInZip(entryName)
            if (encryption != "Nenhuma") {
                setEncryptFiles(true)
                setEncryptionMethod(
                    if (encryption == "ZipCrypto") net.lingala.zip4j.model.enums.EncryptionMethod.ZIP_STANDARD
                    else net.lingala.zip4j.model.enums.EncryptionMethod.AES
                )
                if (encryption == "AES-256") {
                    setAesKeyStrength(net.lingala.zip4j.model.enums.AesKeyStrength.KEY_STRENGTH_256)
                }
            }
        }

    private fun createZipWithZip4j(
        sources: List<File>,
        destination: File,
        meter: TaskProgress,
        password: String?,
        encryption: String,
        splitBytes: Long
    ) {
        val archive = Zip4jFile(destination, password?.toCharArray())
        archive.setRunInThread(true)
        val parameters = zip4jParameters(encryption = encryption)

        if (splitBytes > 0L) {
            when {
                sources.all { it.isFile } ->
                    archive.createSplitZipFile(sources, parameters, true, splitBytes)
                sources.size == 1 && sources[0].isDirectory ->
                    archive.createSplitZipFileFromFolder(sources[0], parameters, true, splitBytes)
                else -> throw IOException("Para dividir o ZIP, seleciona apenas ficheiros ou uma única pasta por arquivo.")
            }
            val monitor = archive.getProgressMonitor()
            while (monitor.getState() == ProgressMonitor.State.BUSY) {
                val current = monitor.getFileName()?.takeIf { it.isNotBlank() } ?: destination.name
                meter.report(monitor.getPercentDone(), current)
                Thread.sleep(120L)
            }
            if (monitor.getResult() == ProgressMonitor.Result.ERROR) {
                throw IOException(monitor.getException()?.message ?: "Falha ao criar ZIP dividido.")
            }
            val visited = mutableListOf<File>()
            measureFiles(sources) { visited.add(it) }
            val totalBytes = visited.filter { it.isFile }.sumOf { it.length().coerceAtLeast(0L) }
            if (totalBytes > 0L) meter.bytesRead(totalBytes, destination)
            visited.forEach { meter.itemDone(it) }
            return
        }

        val files = mutableListOf<ZipFileSource>()
        val directories = mutableListOf<ZipFolderSource>()
        val visited = HashSet<String>()
        fun collect(file: File, entryName: String, depth: Int) {
            if (depth > 80 || visited.size >= 200000) return
            val key = pathKey(file)
            if (!visited.add(key) || file.canonicalFile == destination.canonicalFile) return
            if (file.isDirectory) {
                val children = safeList(file)
                directories.add(ZipFolderSource(file, entryName, children.isEmpty()))
                children.forEach { child -> collect(child, entryName.trimEnd('/') + "/" + child.name, depth + 1) }
            } else {
                files.add(ZipFileSource(file, entryName))
            }
        }
        sources.forEach { source -> collect(source, source.name, 0) }

        val totalBytes = files.sumOf { it.file.length().coerceAtLeast(0L) }
        var completedBytes = 0L
        files.forEach { entry ->
            val entryParameters = zip4jParameters(entry.entryName, encryption)
            archive.addFile(entry.file, entryParameters)
            val monitor = archive.getProgressMonitor()
            var lastReported = 0L
            while (monitor.getState() == ProgressMonitor.State.BUSY) {
                val percent = monitor.getPercentDone().coerceIn(0, 100)
                val estimated = (entry.file.length().toDouble() * percent / 100.0).toLong()
                val delta = (estimated - lastReported).coerceAtLeast(0L)
                if (delta > 0L) {
                    meter.bytesRead(delta, entry.file)
                    lastReported = estimated
                } else {
                    meter.show(entry.file)
                }
                Thread.sleep(100L)
            }
            if (monitor.getResult() == ProgressMonitor.Result.ERROR) {
                throw IOException(monitor.getException()?.message ?: "Falha ao comprimir " + entry.file.name)
            }
            val remaining = (entry.file.length() - lastReported).coerceAtLeast(0L)
            if (remaining > 0L) meter.bytesRead(remaining, entry.file)
            completedBytes += entry.file.length().coerceAtLeast(0L)
            meter.itemDone(entry.file)
        }

        directories.filter { it.empty }.forEach { directory ->
            val folderParams = zip4jParameters(directory.entryName.trimEnd('/') + "/", "Nenhuma").apply {
                setCompressionMethod(net.lingala.zip4j.model.enums.CompressionMethod.STORE)
            }
            archive.addFolder(directory.file, folderParams)
            meter.itemDone(directory.file)
        }
        directories.filterNot { it.empty }.forEach { meter.itemDone(it.file) }
        if (files.isEmpty() && directories.isEmpty()) throw IOException("Não existem ficheiros para adicionar ao ZIP.")
        if (completedBytes < totalBytes) meter.bytesRead(totalBytes - completedBytes, destination)
    }

    private fun deleteArchiveOutputs(destination: File) {
        destination.delete()
        val parent = destination.parentFile ?: return
        val stem = destination.nameWithoutExtension
        val splitPattern = Regex(Regex.escape(stem) + "\\.z[0-9]{2,3}")
        safeList(parent).filter { candidate ->
            candidate.name == destination.name || splitPattern.matches(candidate.name)
        }.forEach { it.delete() }
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

    private fun createArchive(
        sources: List<File>,
        destination: File,
        meter: TaskProgress,
        format: String,
        password: String? = null,
        encryption: String = "Nenhuma",
        splitBytes: Long = 0L
    ) {
        when (format) {
            "7z" -> createSevenZip(sources, destination, meter)
            "tar", "tgz", "tbz2" -> createTarArchive(sources, destination, meter, format)
            else -> createZip(sources, destination, meter, password, encryption, splitBytes)
        }
    }

    private fun createTarArchive(sources: List<File>, destination: File, meter: TaskProgress, format: String) {
        val raw = BufferedOutputStream(FileOutputStream(destination))
        val wrapped: OutputStream = when (format) {
            "tgz" -> GzipCompressorOutputStream(raw)
            "tbz2" -> BZip2CompressorOutputStream(raw)
            else -> raw
        }
        TarArchiveOutputStream(wrapped).use { tar ->
            tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
            tar.setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_POSIX)
            sources.forEach { source ->
                if (source.canonicalFile != destination.canonicalFile) {
                    addToTar(tar, source, source.name, destination, meter)
                }
            }
        }
    }

    private fun addToTar(tar: TarArchiveOutputStream, source: File, entryName: String, destination: File, meter: TaskProgress) {
        if (source.canonicalFile == destination.canonicalFile) return
        meter.show(source)
        val safeName = entryName.replace('\\', '/').trimStart('/')
        if (source.isDirectory) {
            val dirName = safeName.trimEnd('/') + "/"
            tar.putArchiveEntry(TarArchiveEntry(source, dirName))
            tar.closeArchiveEntry()
            meter.itemDone(source)
            val children = source.listFiles() ?: throw IOException("Não foi possível ler " + source.name)
            children.forEach { child -> addToTar(tar, child, dirName + child.name, destination, meter) }
        } else {
            tar.putArchiveEntry(TarArchiveEntry(source, safeName))
            BufferedInputStream(FileInputStream(source)).use { input ->
                val buffer = ByteArray(32 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    tar.write(buffer, 0, n)
                    meter.bytesRead(n.toLong(), source)
                }
            }
            tar.closeArchiveEntry()
            meter.itemDone(source)
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
        val encrypted = try { Zip4jFile(archive).isEncrypted } catch (_: Exception) { false }
        if (encrypted) {
            val passwordInput = EditText(this).apply {
                hint = "Palavra-passe do ZIP"
                inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
                setSingleLine(true)
            }
            AlertDialog.Builder(this)
                .setTitle("ZIP protegido por palavra-passe")
                .setView(passwordInput)
                .setNegativeButton(tr("Cancelar"), null)
                .setPositiveButton("Extrair") { _, _ ->
                    val password = passwordInput.text.toString()
                    if (password.isEmpty()) {
                        toast("Escreve a palavra-passe do arquivo.")
                    } else {
                        extractZipContents(archive, destinationDir, makeSubfolder, password)
                    }
                }
                .show()
        } else {
            extractZipContents(archive, destinationDir, makeSubfolder, null)
        }
    }

    private fun extractZipContents(
        archive: File,
        destinationDir: File,
        makeSubfolder: Boolean,
        password: String?
    ) {
        runTask("A extrair ZIP…", 0L, 1L) { meter ->
            val zip = if (password.isNullOrEmpty()) Zip4jFile(archive) else Zip4jFile(archive, password.toCharArray())
            val headers = zip.fileHeaders ?: throw IOException("Não foi possível ler o índice do ZIP.")
            if (headers.isEmpty()) return@runTask "O arquivo ZIP está vazio."
            val totalBytes = headers.sumOf { if (it.isDirectory) 0L else it.uncompressedSize.coerceAtLeast(0L) }
            meter.setTotals(totalBytes, headers.size.toLong())
            val destination = if (makeSubfolder) {
                File(destinationDir, uniqueName(destinationDir, archive.nameWithoutExtension))
            } else destinationDir
            if (makeSubfolder && !destination.mkdirs()) throw IOException("Não foi possível criar a pasta de destino.")

            var count = 0
            headers.forEach { entry ->
                val output = safeArchiveTarget(destination, entry.fileName, entry.isDirectory)
                meter.show(output)
                if (entry.isDirectory) {
                    if (!output.exists() && !output.mkdirs()) throw IOException("Não foi possível criar " + output.name)
                } else {
                    output.parentFile?.let {
                        if (!it.exists() && !it.mkdirs()) throw IOException("Não foi possível criar uma pasta de destino.")
                    }
                    val target = if (output.exists()) File(output.parentFile, uniqueName(output.parentFile!!, output.name)) else output
                    zip.getInputStream(entry).use { input ->
                        BufferedOutputStream(FileOutputStream(target)).use { stream ->
                            val buffer = ByteArray(32 * 1024)
                            while (true) {
                                val n = input.read(buffer)
                                if (n < 0) break
                                if (n > 0) {
                                    stream.write(buffer, 0, n)
                                    meter.bytesRead(n.toLong(), target)
                                }
                            }
                        }
                    }
                }
                meter.itemDone(output)
                count++
            }
            "Extração ZIP concluída: $count entradas."
        }
    }

    private fun extractSevenZip(archive: File, destinationDir: File, makeSubfolder: Boolean) {
        runTask("A extrair 7Z…", 0L, 1L) { meter ->
            val totals = try { measureSevenZipArchive(archive) } catch (_: Exception) { TaskTotals(0L, 1L) }
            meter.setTotals(totals.bytes, totals.entries)
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
        runTask("A testar ZIP…", 0L, 1L) { meter ->
            val totals = try { measureZipArchive(archive) } catch (_: Exception) { TaskTotals(0L, 1L) }
            meter.setTotals(totals.bytes, totals.entries)
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
        runTask("A testar 7Z…", 0L, 1L) { meter ->
            val totals = try { measureSevenZipArchive(archive) } catch (_: Exception) { TaskTotals(0L, 1L) }
            meter.setTotals(totals.bytes, totals.entries)
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

    private data class ArchiveItem(val index: Int, val name: String, val directory: Boolean, val size: Long)

    private fun browseArchive(archive: File) {
        val kind = archiveKind(archive)
        runTask("A ler arquivo…") {
            val entries = mutableListOf<ArchiveItem>()
            var index = 0
            when (kind) {
                "zip" -> ZipFile(archive).use { zip ->
                    val it = zip.entries()
                    while (it.hasMoreElements() && entries.size < 10000) {
                        val e = it.nextElement()
                        val name = e.name.replace('\\', '/').trimStart('/')
                        entries += ArchiveItem(index++, if (e.isDirectory && !name.endsWith("/")) "$name/" else name, e.isDirectory, e.size)
                    }
                }
                "7z" -> SevenZFile(archive).use { seven ->
                    for (e in seven.entries) {
                        if (entries.size >= 10000) break
                        val name = e.name.replace('\\', '/').trimStart('/')
                        entries += ArchiveItem(index++, if (e.isDirectory && !name.endsWith("/")) "$name/" else name, e.isDirectory, e.size)
                    }
                }
                "tar", "tgz", "tbz2" -> openTarInput(archive).use { tar ->
                    while (entries.size < 10000) {
                        val e = tar.nextTarEntry ?: break
                        val name = e.name.replace('\\', '/').trimStart('/')
                        entries += ArchiveItem(index++, if (e.isDirectory && !name.endsWith("/")) "$name/" else name, e.isDirectory, e.size)
                        if (!e.isDirectory) {
                            val buffer = ByteArray(8192)
                            while (tar.read(buffer) >= 0) { /* advance to next header */ }
                        }
                    }
                }
                else -> throw IOException("Formato não suportado.")
            }
            runOnUiThread { showArchiveItems(archive, entries, "") }
            if (entries.isEmpty()) "O arquivo está vazio." else "Conteúdo lido: ${entries.size} entradas."
        }
    }

    private fun showArchiveItems(archive: File, entries: List<ArchiveItem>, prefix: String = "") {
        val children = linkedMapOf<String, ArchiveItem>()
        entries.forEach { entry ->
            val normalized = entry.name.replace('\\', '/').trimStart('/')
            val fullName = if (entry.directory && !normalized.endsWith("/")) "$normalized/" else normalized
            if (!fullName.startsWith(prefix)) return@forEach
            val remaining = fullName.removePrefix(prefix)
            if (remaining.isBlank()) return@forEach
            val first = remaining.substringBefore('/')
            if (first.isBlank()) return@forEach
            if (remaining.contains('/')) {
                val virtualPath = prefix + first + "/"
                if (!children.containsKey(virtualPath)) {
                    children[virtualPath] = ArchiveItem(-1, virtualPath, true, -1L)
                }
            } else {
                val childName = prefix + first + if (entry.directory) "/" else ""
                children[childName] = entry.copy(name = childName)
            }
        }
        val visible = children.values.sortedWith(
            compareByDescending<ArchiveItem> { it.directory }
                .thenBy { it.name.substringAfterLast('/').trimEnd('/').lowercase(Locale.ROOT) }
        )
        if (visible.isEmpty() && prefix.isBlank()) {
            toast("O arquivo está vazio.")
            return
        }
        val hasParent = prefix.isNotBlank()
        val labels = mutableListOf<String>()
        if (hasParent) labels += "⬆️  .."
        visible.forEach { item ->
            labels += (if (item.directory) "📁  " else "📄  ") +
                item.name.substringAfterLast('/').ifBlank { item.name } +
                if (!item.directory && item.size >= 0) "  •  " + formatSize(item.size) else ""
        }
        val builder = AlertDialog.Builder(this)
            .setTitle(archive.name + if (prefix.isNotBlank()) "  /  " + prefix.trimEnd('/') else "")
            .setItems(labels.toTypedArray()) { dialog, which ->
                dialog.dismiss()
                page.post {
                    if (hasParent && which == 0) {
                        val parent = prefix.trimEnd('/').substringBeforeLast('/', "")
                        showArchiveItems(archive, entries, if (parent.isBlank()) "" else "$parent/")
                        return@post
                    }
                    val itemIndex = which - if (hasParent) 1 else 0
                    val item = visible.getOrNull(itemIndex) ?: return@post
                    if (item.directory) {
                        showArchiveItems(archive, entries, item.name.trimEnd('/') + "/")
                    } else {
                        AlertDialog.Builder(this)
                            .setTitle(tr("Extrair item"))
                            .setMessage(tr("Extrair") + " " + item.name + " " + tr("para a pasta atual") + "?")
                            .setNegativeButton(tr("Cancelar"), null)
                            .setPositiveButton(tr("Extrair")) { _, _ -> extractArchiveEntry(archive, item) }
                            .show()
                    }
                }
            }
            .setPositiveButton(tr("Fechar"), null)
        if (hasParent) builder.setNeutralButton(tr("Extrair pasta atual")) { _, _ ->
            extractArchiveEntry(archive, ArchiveItem(-1, prefix, true, -1L))
        }
        builder.show()
    }

    private fun openTarInput(archive: File): TarArchiveInputStream {
        val raw = BufferedInputStream(FileInputStream(archive))
        val input: InputStream = when (archiveKind(archive)) {
            "tgz" -> GzipCompressorInputStream(raw)
            "tbz2" -> BZip2CompressorInputStream(raw)
            else -> raw
        }
        return TarArchiveInputStream(input)
    }

    private fun extractTarArchive(archive: File, destinationDir: File, makeSubfolder: Boolean) {
        val kind = archiveKind(archive)
        runTask("A extrair TAR…", 0L, 1L) { meter ->
            val destination = if (makeSubfolder) File(destinationDir, uniqueName(destinationDir, stripArchiveSuffix(archive.name))) else destinationDir
            if (makeSubfolder && !destination.mkdirs()) throw IOException("Não foi possível criar a pasta de destino.")
            var count = 0
            openTarInput(archive).use { tar ->
                val buffer = ByteArray(32 * 1024)
                while (true) {
                    val entry = tar.nextTarEntry ?: break
                    val output = safeArchiveTarget(destination, entry.name, entry.isDirectory)
                    meter.show(output)
                    if (entry.isDirectory) {
                        if (!output.exists() && !output.mkdirs()) throw IOException("Não foi possível criar " + output.name)
                    } else {
                        output.parentFile?.let { if (!it.exists() && !it.mkdirs()) throw IOException("Não foi possível criar uma pasta de destino.") }
                        val target = if (output.exists()) File(output.parentFile, uniqueName(output.parentFile!!, output.name)) else output
                        BufferedOutputStream(FileOutputStream(target)).use { stream ->
                            while (true) {
                                val n = tar.read(buffer)
                                if (n < 0) break
                                if (n > 0) {
                                    stream.write(buffer, 0, n)
                                    meter.bytesRead(n.toLong(), target)
                                }
                            }
                        }
                    }
                    meter.itemDone(output)
                    count++
                }
            }
            "Extração ${kind.uppercase(Locale.ROOT)} concluída: $count entradas."
        }
    }

    private fun testTarArchive(archive: File) {
        runTask("A testar TAR…", 0L, 1L) { meter ->
            var count = 0
            openTarInput(archive).use { tar ->
                val buffer = ByteArray(32 * 1024)
                while (true) {
                    val entry = tar.nextTarEntry ?: break
                    val current = File(archive.parentFile, entry.name)
                    meter.show(current)
                    if (!entry.isDirectory) {
                        while (true) {
                            val n = tar.read(buffer)
                            if (n < 0) break
                            if (n > 0) meter.bytesRead(n.toLong(), current)
                        }
                    }
                    meter.itemDone(current)
                    count++
                }
            }
            "Teste TAR concluído: $count entradas."
        }
    }

        private fun extractArchiveEntry(archive: File, item: ArchiveItem) {
        val kind = archiveKind(archive)
        runTrackedTask("A extrair item…", item.size.coerceAtLeast(0L), 1L) { meter ->
            if (item.directory) {
                val destination = safeArchiveTarget(currentDir, item.name.trimEnd('/'), true)
                if (destination.exists() && !destination.isDirectory) throw IOException("Já existe um ficheiro com esse nome.")
                if (!destination.exists() && !destination.mkdirs()) throw IOException("Não foi possível criar a pasta.")
                extractArchiveFolderContents(archive, item.name.trimEnd('/') + "/", destination, meter, kind)
                meter.itemDone(destination)
                "Pasta extraída: " + item.name.trimEnd('/')
            } else {
                when (kind) {
                    "zip" -> ZipFile(archive).use { zip ->
                        val entry = zip.getEntry(item.name) ?: throw IOException("A entrada já não existe.")
                        val destination = safeArchiveTarget(currentDir, item.name, false)
                        destination.parentFile?.let { if (!it.exists() && !it.mkdirs()) throw IOException("Não foi possível criar a pasta.") }
                        val target = if (destination.exists()) File(destination.parentFile, uniqueName(destination.parentFile!!, destination.name)) else destination
                        BufferedInputStream(zip.getInputStream(entry)).use { input ->
                            BufferedOutputStream(FileOutputStream(target)).use { output ->
                                val buffer = ByteArray(32 * 1024)
                                while (true) {
                                    val n = input.read(buffer)
                                    if (n < 0) break
                                    if (n > 0) {
                                        output.write(buffer, 0, n)
                                        meter.bytesRead(n.toLong(), target)
                                    }
                                }
                            }
                        }
                        meter.itemDone(target)
                    }
                    "7z" -> {
                        var found = false
                        SevenZFile(archive).use { seven ->
                            var index = 0
                            while (true) {
                                val entry = seven.nextEntry ?: break
                                val thisIndex = index++
                                if (thisIndex != item.index) {
                                    if (!entry.isDirectory) {
                                        val skipBuffer = ByteArray(8192)
                                        while (seven.read(skipBuffer, 0, skipBuffer.size) >= 0) { }
                                    }
                                    continue
                                }
                                found = true
                                val destination = safeArchiveTarget(currentDir, item.name, false)
                                destination.parentFile?.let { if (!it.exists() && !it.mkdirs()) throw IOException("Não foi possível criar a pasta.") }
                                val target = if (destination.exists()) File(destination.parentFile, uniqueName(destination.parentFile!!, destination.name)) else destination
                                BufferedOutputStream(FileOutputStream(target)).use { output ->
                                    val buffer = ByteArray(32 * 1024)
                                    while (true) {
                                        val n = seven.read(buffer, 0, buffer.size)
                                        if (n < 0) break
                                        if (n > 0) {
                                            output.write(buffer, 0, n)
                                            meter.bytesRead(n.toLong(), target)
                                        }
                                    }
                                }
                                meter.itemDone(target)
                                break
                            }
                        }
                        if (!found) throw IOException("A entrada já não existe.")
                    }
                    "tar", "tgz", "tbz2" -> {
                        var found = false
                        openTarInput(archive).use { tar ->
                            val buffer = ByteArray(32 * 1024)
                            while (true) {
                                val entry = tar.nextTarEntry ?: break
                                if (entry.name.replace('\\', '/').trimStart('/').trimEnd('/') != item.name.trimEnd('/')) {
                                    if (!entry.isDirectory) while (tar.read(buffer) >= 0) { }
                                    continue
                                }
                                found = true
                                val destination = safeArchiveTarget(currentDir, item.name, false)
                                destination.parentFile?.let { if (!it.exists() && !it.mkdirs()) throw IOException("Não foi possível criar a pasta.") }
                                val target = if (destination.exists()) File(destination.parentFile, uniqueName(destination.parentFile!!, destination.name)) else destination
                                BufferedOutputStream(FileOutputStream(target)).use { output ->
                                    while (true) {
                                        val n = tar.read(buffer)
                                        if (n < 0) break
                                        if (n > 0) {
                                            output.write(buffer, 0, n)
                                            meter.bytesRead(n.toLong(), target)
                                        }
                                    }
                                }
                                meter.itemDone(target)
                                break
                            }
                        }
                        if (!found) throw IOException("A entrada já não existe.")
                    }
                    else -> throw IOException("Formato não suportado.")
                }
                "Item extraído: " + item.name
            }
        }
    }

    private fun extractArchiveFolderContents(
        archive: File,
        prefix: String,
        destination: File,
        meter: TaskProgress,
        kind: String
    ) {
        val buffer = ByteArray(32 * 1024)
        fun createTarget(name: String, directory: Boolean): File? {
            val normalized = name.replace('\\', '/').trimStart('/')
            if (!normalized.startsWith(prefix) || normalized == prefix) return null
            val relative = normalized.removePrefix(prefix)
            if (relative.isBlank()) return null
            val output = safeArchiveTarget(destination, relative, directory)
            meter.show(output)
            if (directory) {
                if (!output.exists() && !output.mkdirs()) throw IOException("Não foi possível criar " + output.name)
                meter.itemDone(output)
                return null
            }
            output.parentFile?.let { if (!it.exists() && !it.mkdirs()) throw IOException("Não foi possível criar uma pasta de destino.") }
            return if (output.exists()) File(output.parentFile, uniqueName(output.parentFile!!, output.name)) else output
        }
        fun writeStream(input: InputStream, target: File) {
            BufferedOutputStream(FileOutputStream(target)).use { out ->
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    if (n > 0) {
                        out.write(buffer, 0, n)
                        meter.bytesRead(n.toLong(), target)
                    }
                }
            }
            meter.itemDone(target)
        }
        when (kind) {
            "zip" -> ZipFile(archive).use { zip ->
                val iterator = zip.entries()
                while (iterator.hasMoreElements()) {
                    val entry = iterator.nextElement()
                    val target = createTarget(entry.name, entry.isDirectory) ?: continue
                    zip.getInputStream(entry).use { input -> writeStream(input, target) }
                }
            }
            "7z" -> SevenZFile(archive).use { seven ->
                while (true) {
                    val entry = seven.nextEntry ?: break
                    val target = createTarget(entry.name, entry.isDirectory)
                    if (!entry.isDirectory) {
                        if (target != null) {
                            BufferedOutputStream(FileOutputStream(target)).use { out ->
                                while (true) {
                                    val n = seven.read(buffer, 0, buffer.size)
                                    if (n < 0) break
                                    if (n > 0) {
                                        out.write(buffer, 0, n)
                                        meter.bytesRead(n.toLong(), target)
                                    }
                                }
                            }
                            meter.itemDone(target)
                        } else while (seven.read(buffer, 0, buffer.size) >= 0) { }
                    }
                }
            }
            "tar", "tgz", "tbz2" -> openTarInput(archive).use { tar ->
                while (true) {
                    val entry = tar.nextTarEntry ?: break
                    val target = createTarget(entry.name, entry.isDirectory)
                    if (!entry.isDirectory) {
                        if (target != null) writeStream(tar, target)
                        else while (tar.read(buffer) >= 0) { }
                    }
                }
            }
            else -> throw IOException("Formato não suportado.")
        }
    }

        private fun showInfoPlaceholder() {}

    private fun shouldLoadThumbnail(file: File): Boolean {
        if (!showThumbnails) return false
        // Directory contents are inspected in thumbnailWorker, never while building the visible list.
        if (file.isDirectory) return true
        return when (extension(file.name)) {
            "jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "avif", "tif", "tiff" -> true
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
        if (file.isDirectory) return folderThumbnail(file)
        return when (extension(file.name)) {
            "jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "avif", "tif", "tiff" -> {
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

    private fun folderThumbnail(directory: File): Bitmap? {
        val sample = safeList(directory).firstOrNull { child ->
            !child.isDirectory && extension(child.name) in setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "avif", "tif", "tiff")
        } ?: return null
        val preview = loadThumbnail(sample) ?: return null
        val size = dp(64)
        val folderBitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(folderBitmap)
        val folderDrawable: Drawable = getDrawable(R.drawable.ic_folder) ?: return null
        folderColorValue(directory)?.let { folderDrawable.setColorFilter(it, android.graphics.PorterDuff.Mode.SRC_IN) }
        folderDrawable.setBounds(0, 0, size, size)
        folderDrawable.draw(canvas)
        val target = android.graphics.Rect(size / 3, size / 3, size * 3 / 4, size * 3 / 4)
        val framePaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF24242A.toInt() }
        val targetRect = android.graphics.RectF(target)
        canvas.drawRoundRect(targetRect, dp(3).toFloat(), dp(3).toFloat(), framePaint)
        canvas.save()
        canvas.clipRect(target)
        canvas.drawBitmap(preview, null, target, android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG or android.graphics.Paint.FILTER_BITMAP_FLAG))
        canvas.restore()
        framePaint.style = android.graphics.Paint.Style.STROKE
        framePaint.strokeWidth = dp(1).toFloat()
        framePaint.color = 0xFFFFFFFF.toInt()
        canvas.drawRoundRect(targetRect, dp(3).toFloat(), dp(3).toFloat(), framePaint)
        preview.recycle()
        return folderBitmap
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
        totalBytes: Long,
        totalEntries: Long,
        private val publish: (Int, String) -> Unit
    ) {
        private var totalBytes: Long = totalBytes.coerceAtLeast(0L)
        private var totalEntries: Long = totalEntries.coerceAtLeast(1L)
        private var completedBytes = 0L
        private var completedEntries = 0L
        private var totalUnits = (this.totalBytes + this.totalEntries).coerceAtLeast(1L)

        @Synchronized
        fun setTotals(bytes: Long, entries: Long) {
            totalBytes = bytes.coerceAtLeast(0L)
            totalEntries = entries.coerceAtLeast(1L)
            completedBytes = 0L
            completedEntries = 0L
            totalUnits = (totalBytes + totalEntries).coerceAtLeast(1L)
            publish(0, "A preparar operação…")
        }

        private fun detailFor(file: File): String =
            "${completedEntries.coerceAtMost(totalEntries)}/${totalEntries}  •  ${file.absolutePath}"

        @Synchronized
        fun show(file: File) = publish(progressPercent(), detailFor(file))

        @Synchronized
        fun bytesRead(count: Long, file: File) {
            if (count > 0L) completedBytes = (completedBytes + count).coerceAtMost(totalBytes.coerceAtLeast(0L))
            publish(progressPercent(), detailFor(file))
        }

        @Synchronized
        fun itemDone(file: File) {
            completedEntries = (completedEntries + 1L).coerceAtMost(totalEntries.coerceAtLeast(1L))
            publish(progressPercent(), detailFor(file))
        }

        private fun progressPercent(): Int {
            val done = completedBytes + completedEntries
            return ((done.toDouble() / totalUnits.toDouble()) * 100.0).toInt().coerceIn(0, 99)
        }

        @Synchronized
        fun report(percent: Int, detail: String) =
            publish(percent.coerceIn(0, 99), "${completedEntries.coerceAtMost(totalEntries)}/${totalEntries}  •  $detail")

        fun finish(detail: String) = publish(100, detail)
    }

    private fun measureFiles(files: List<File>, onVisit: ((File) -> Unit)? = null): TaskTotals {
        var bytes = 0L
        var entries = 0L
        val visited = HashSet<String>()
        fun walk(file: File, depth: Int) {
            if (depth > 80 || entries >= 200000L) return
            val key = pathKey(file)
            if (!visited.add(key)) return
            onVisit?.invoke(file)
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

    private fun countFilesForSearch(directory: File, onVisit: ((File) -> Unit)? = null): Long = measureFiles(listOf(directory), onVisit).entries

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
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            !prefs.getBoolean("notification_permission_requested", false)
        ) {
            prefs.edit().putBoolean("notification_permission_requested", true).apply()
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 742)
        }

        val searching = message.contains("pesquis", ignoreCase = true)
        val progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            isIndeterminate = searching
        }
        val percentView = label(if (searching) "…" else "0%", 14, 0xFFD8D3FF.toInt(), true).apply { gravity = Gravity.END }
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
        val dialog = AlertDialog.Builder(this)
            .setTitle(message)
            .setView(dialogContent)
            .setCancelable(false)
            .setPositiveButton(tr("Fechar"), null)
            .create()
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
        var latestPercent = 0
        var lastPercent = -1
        var lastDetail = ""
        var lastNoticeAt = 0L
        val meter = TaskProgress(totalBytes, totalEntries) { percent, detail ->
            latestPercent = percent.coerceIn(0, 99)
            runOnUiThread {
                if (!isFinishing && dialog.isShowing) {
                    if (!searching) progressBar.progress = percent
                    percentView.text = if (searching) "…" else "$percent%"
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
            }
            val finalResult = result
            val failed = finalResult.startsWith("Erro:")
            runOnUiThread {
                workActive.set(false)
                if (finalResult.startsWith("Ficheiro guardado:")) {
                    editorSavedText = editorInput?.text?.toString() ?: editorSavedText
                    editorDirty = false
                    if (closeEditorAfterSave) closeTextEditor()
                } else if (finalResult.startsWith("Erro:")) {
                    closeEditorAfterSave = false
                }
                if (!isFinishing) {
                    Toast.makeText(this, tr(finalResult), Toast.LENGTH_LONG).show()
                    if (hasStorageAccess() && browserRendered && !settingsOpen && !editorOpen) refreshFiles()
                }
                dialog.dismiss()
                runCatching { getSystemService(NotificationManager::class.java).cancel(operationNotificationId) }
            }
        }
    }

    private fun toolbarIcon(resource: Int, description: String, click: () -> Unit): ImageButton =
        ImageButton(this).apply {
            setImageResource(resource)
            contentDescription = tr(description)
            imageTintList = android.content.res.ColorStateList.valueOf(primaryTextColor())
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
                setTextColor(accentColor())
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                maxLines = 1
            }, LinearLayout.LayoutParams(-1, dp(30)))
            addView(TextView(this@MainActivity).apply {
                text = tr(title)
                textSize = 10f
                gravity = Gravity.CENTER
                setTextColor(primaryTextColor())
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
        this.text = tr(text)
        textSize = size.toFloat()
        setTextColor(resolveTextColor(color))
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
    }

    private fun iconButton(symbol: String, description: String, click: () -> Unit): TextView =
        TextView(this).apply {
            text = symbol
            contentDescription = description
            textSize = 27f
            gravity = Gravity.CENTER
            setTextColor(if (uiTheme == "Claro") primaryTextColor() else 0xFFFFFFFF.toInt())
            isClickable = true
            isFocusable = true
            setOnClickListener { click() }
        }

    private fun actionButton(text: String, click: () -> Unit): Button = Button(this).apply {
        this.text = tr(text)
        textSize = 12f
        isAllCaps = false
        setOnClickListener { click() }
    }


    private fun tr(text: String): String {
        if (appLanguage == "pt") return text
        val english = mapOf(
            "Explorador" to "Explorer", "Gestor de ficheiros para Android" to "Android file manager",
            "Configurações" to "Settings", "Geral" to "General", "Interface" to "Interface",
            "Compressão" to "Compression", "2 idiomas" to "2 languages",
            "Idioma, notificações e informações" to "Language, notifications and app information",
            "Tema, cor de destaque e aparência" to "Theme, accent color and appearance",
            "Ordenação, ficheiros ocultos e miniaturas" to "Sorting, hidden files and thumbnails",
            "Formato, velocidade e opções de arquivos" to "Formats, speed and archive options",
            "Notificações" to "Notifications", "Sobre o Archiver" to "About Archiver",
            "Progresso e conclusão das operações de ficheiros" to "File operation progress and completion",
            "Gestor de ficheiros Android nativo em Kotlin" to "Native Kotlin Android file manager",
            "Personaliza os controlos e realces" to "Customize controls and highlights",
            "Cores das pastas" to "Folder colors", "Por pasta" to "Per folder",
            "Escolhe uma cor no menu de cada pasta" to "Choose a color from each folder's menu",
            "Abre o menu de uma pasta e escolhe Cor da pasta." to "Open a folder menu and choose Folder color.",
            "O Archiver não implementa acesso ROOT nem Shizuku nesta versão. A permissão Android «Gerir todos os ficheiros» permite gerir o armazenamento partilhado, mas não desbloqueia as pastas privadas de outras aplicações nem concede root." to "Root or Shizuku access is not implemented in this version. Android's all-files permission manages shared storage, but it does not unlock other apps' private folders or grant root access.",
            "Ainda não existe um sistema de plugins nesta versão. Os formatos e leitores disponíveis são os integrados na aplicação ou nas aplicações instaladas no dispositivo." to "A plugin system is not available yet. Supported formats and readers are built into Archiver or provided by apps installed on the device.",
            "ZIP: suporta ZipCrypto, AES-256 e divisão em volumes. 7Z: criação e extração com níveis de compressão; palavra-passe e volumes não estão ligados nesta versão." to "ZIP supports ZipCrypto, AES-256 and split volumes. 7Z supports create/extract and compression levels; password protection and split volumes are not wired up for 7Z in this version.",
            "Leitor de modelo 3D" to "3D model inspector", "Abrir noutra aplicação" to "Open in another app",
            "Tentar aplicação externa" to "Try external app", "Modelo 3D" to "3D model",
            "Abrir ficheiro" to "Open file", "Formato" to "Format", "Versão GLTF" to "GLTF version",
            "Cenas" to "Scenes", "Objetos/nós" to "Objects/nodes", "Materiais" to "Materials",
            "Texturas" to "Textures", "Imagens" to "Images", "Animações" to "Animations",
            "Buffers" to "Buffers", "Tamanho do ficheiro" to "File size",
            "Esta versão lê os metadados do modelo; a renderização 3D interativa ainda requer uma aplicação compatível." to "This version reads model metadata; interactive 3D rendering still requires a compatible app.",
            "Não foi possível ler os metadados do modelo." to "Could not read model metadata.",
            "O ficheiro é demasiado grande para o editor integrado e não existe outra aplicação para o abrir." to "The file is too large for the built-in editor and no other app can open it.",
            "ZIP protegido por palavra-passe" to "Password-protected ZIP",
            "Palavra-passe do ZIP" to "ZIP password", "Escreve a palavra-passe do arquivo." to "Enter the archive password.",
            "Inspecionar modelo 3D" to "Inspect 3D model",
            "Comprimir para ZIP" to "Compress to ZIP", "Comprimir para 7Z" to "Compress to 7Z",
            "Comprimir…" to "Compress…", "text" to "text",
            "Conceder acesso aos ficheiros" to "Grant file access", "Opções" to "Options",
            "Selecionar tudo" to "Select all", "Limpar seleção" to "Clear selection",
            "Copiar seleção" to "Copy selection", "Recortar seleção" to "Cut selection",
            "Colar" to "Paste", "Criar novo…" to "Create new…", "Comprimir em ZIP" to "Compress to ZIP",
            "Comprimir em 7Z" to "Compress to 7Z", "Pesquisar em subpastas" to "Search subfolders",
            "Favoritos" to "Favorites", "Ordenar" to "Sort", "Definições do explorador" to "Explorer settings",
            "Atualizar" to "Refresh", "Pasta de início" to "Home folder", "Ordenar ficheiros" to "Sort files",
            "Nome" to "Name", "Tamanho" to "Size", "Data" to "Date", "Tipo" to "Type",
            "Ordem crescente" to "Ascending", "Ordem decrescente" to "Descending",
            "Detalhado" to "Detailed", "Compacto" to "Compact", "Grelha" to "Grid",
            "Idioma" to "Language", "Tema da interface" to "Interface theme", "Cor de destaque" to "Accent color",
            "Cores das pastas" to "Folder colors", "Escolher o idioma da interface" to "Choose interface language",
            "Escuro" to "Dark", "Preto OLED" to "OLED black", "Claro" to "Light",
            "Lavanda" to "Lavender", "Azul" to "Blue", "Verde" to "Green", "Laranja" to "Orange",
            "Rosa" to "Pink", "Vermelho" to "Red", "Amarelo" to "Yellow",
            "Miniaturas" to "Thumbnails", "Miniaturas do PDF" to "PDF thumbnails",
            "Miniaturas de vídeo" to "Video thumbnails", "Capa do álbum" to "Album covers",
            "Ícones do APK" to "APK icons", "Miniaturas quadradas" to "Square thumbnails",
            "Nível de compressão" to "Compression level", "Escolher a ordem dos ficheiros" to "Choose file sorting",
            "Inverte a ordem de ordenação dos arquivos" to "Reverse the sort order",
            "Usar item \"..\" " to "Show parent-directory entry",
            "Permite subir para o diretório anterior a partir da lista" to "Show parent directory in the list",
            "Exibir arquivos ocultos" to "Show hidden files",
            "Mostra ficheiros e diretórios cujo nome começa por ponto" to "Show files and folders beginning with a dot",
            "Modo de exibição" to "View mode", "Detalhado, compacto ou grelha" to "Detailed, compact or grid",
            "Exibir tamanho" to "Show size", "Mostra o tamanho dos ficheiros na lista" to "Show file sizes",
            "Exibir data" to "Show date", "Mostra a data da última modificação" to "Show last modified date",
            "Exibir hora" to "Show time", "Mostra a hora da última modificação" to "Show last modified time",
            "Permite a exibição de miniaturas" to "Enable file thumbnails",
            "Mostrar miniaturas de documentos PDF" to "Show PDF previews",
            "Mostrar miniaturas de vídeo" to "Show video previews",
            "Mostrar a capa do álbum nos ficheiros de música" to "Show music album art",
            "Exibir os ícones das aplicações APK" to "Show APK application icons",
            "Recorta as miniaturas para formato quadrado" to "Crop previews to a square",
            "Copiar" to "Copy", "Mover" to "Move", "Eliminar" to "Delete", "Renomear" to "Rename",
            "Informações" to "Information", "Abrir" to "Open", "Abrir pasta" to "Open folder",
            "Partilhar" to "Share", "Recortar" to "Cut", "Ver conteúdo" to "Browse archive",
            "Testar arquivo" to "Test archive", "Extrair aqui" to "Extract here",
            "Extrair para nova pasta" to "Extract to new folder", "Adicionar aos Favoritos" to "Add to Favorites",
            "Remover dos Favoritos" to "Remove from Favorites", "Cor da pasta" to "Folder color",
            "Predefinida" to "Default", "Pasta vazia" to "Empty folder",
            "Armazenamento interno" to "Internal storage", "Guardar" to "Save", "Cancelar" to "Cancel",
            "Continuar a editar" to "Continue editing", "Sair sem guardar" to "Discard changes",
            "Alterações por guardar" to "Unsaved changes",
            "Queres guardar as alterações antes de sair do editor?" to "Would you like to save changes before leaving the editor?",
            "Já existe uma operação em curso. Aguarda que termine." to "Another operation is running. Please wait.",
            "A operação está em curso. Acompanha o progresso na janela ou na notificação." to "An operation is running. Follow progress in the dialog or notification."
        ) + mapOf(
            "12 idiomas" to "12 languages",
            "Pesquisa avançada" to "Advanced search",
            "Incluir subpastas" to "Include subfolders",
            "Todos os tipos" to "All types",
            "Pastas" to "Folders",
            "Documentos" to "Documents",
            "Imagens" to "Images",
            "Vídeo" to "Video",
            "Áudio" to "Audio",
            "Arquivos" to "Archives",
            "Aplicações" to "Apps",
            "A pesquisa começa na pasta atual e não procura fora dela." to "Search starts in the current folder and never searches outside it.",
            "Pasta pesquisada" to "Searched folder",
            "resultados" to "results",
            "Pasta" to "Folder",
            "Ficheiro" to "File",
            "Mais ações" to "More actions",
            "Mostrando os primeiros 100 resultados." to "Showing the first 100 results.",
            "Analisados" to "Scanned",
            "Copiar caminho" to "Copy path",
            "Calcular SHA-256" to "Calculate SHA-256",
            "Caminho copiado." to "Path copied.",
            "SHA-256 copiado." to "SHA-256 copied.",
            "Seleciona um ficheiro para calcular o SHA-256." to "Choose a file to calculate SHA-256.",
            "Partilhar o Archiver" to "Share Archiver",
            "Ajudar o projeto" to "Support the project",
            "Convida alguém a experimentar o Archiver" to "Invite someone to try Archiver",
            "Experimenta o Archiver, um gestor de ficheiros Android independente:" to "Try Archiver, an independent Android file manager:",
            "Gostas do Archiver?" to "Enjoying Archiver?",
            "Estamos a melhorar a aplicação. Partilhar o projeto ajuda mais pessoas a conhecê-lo." to "We're improving the app. Sharing the project helps more people discover it.",
            "Agora não" to "Not now",
            "Escuro (recomendado)" to "Dark (recommended)",
            "Preto OLED (ecrãs OLED)" to "OLED black (OLED displays)",
            "Claro (boa visibilidade à luz do dia)" to "Light (best in bright conditions)",
            "O tema altera o fundo e o contraste. A cor de destaque controla os botões e a seleção." to "The theme changes background and contrast. The accent color controls buttons and selection.",
            "Escolhe um idioma. Os nomes são apresentados na própria língua." to "Choose a language. Language names are shown in their native form.",
            "Escolhe entre escuro, preto OLED e claro" to "Choose dark, OLED black or light",
            "Cor dos botões, seleção e indicadores" to "Color for buttons, selection and indicators",
            "Cópia de caminho" to "Copy path",
            "SHA-256" to "SHA-256",
            "Tamanho total" to "Total size",
            "Leitor de PDF" to "PDF reader",
            "Leitor de áudio" to "Audio player",
            "A abrir PDF…" to "Opening PDF…",
            "A carregar imagem…" to "Loading image…",
            "Pré-visualização da imagem" to "Image preview",
            "Não foi possível abrir PDF." to "Could not open PDF.",
            "Não foi possível abrir a imagem." to "Could not open image.",
            "Ficheiro de imagem inválido." to "Invalid image file.",
            "A preparar áudio…" to "Preparing audio…",
            "Não foi possível reproduzir o áudio." to "Could not play audio.",
            "Reprodução concluída." to "Playback complete.",
            "Reproduzir" to "Play",
            "Pausar" to "Pause",
            "Parar" to "Stop",
            "Pronto" to "Ready",
            "Anterior" to "Previous",
            "Seguinte" to "Next",
            "Extrair pasta atual" to "Extract current folder",
            "Extrair item" to "Extract item",
            "para a pasta atual" to "to the current folder",
            "Extrair" to "Extract",
            "Tamanho total" to "Total size",
            "Itens" to "Items",
            "itens" to "items",
            "Tamanho calculado" to "Calculated size",
            "Seleciona uma pasta." to "Choose a folder.",
            "A calcular tamanho da pasta…" to "Calculating folder size…"
        )
        if (appLanguage == "en") return english[text] ?: text
        val localized = when (appLanguage) {
            "es" -> mapOf(
            "Explorador" to "Explorador",
            "Gestor de ficheiros para Android" to "Gestor de archivos para Android",
            "Configurações" to "Ajustes",
            "Geral" to "General",
            "Interface" to "Interfaz",
            "Compressão" to "Compresión",
            "Idioma" to "Idioma",
            "Tema da interface" to "Tema de la interfaz",
            "Cor de destaque" to "Color de acento",
            "Cores das pastas" to "Colores de carpetas",
            "Ordenar ficheiros" to "Ordenar archivos",
            "Nome" to "Nombre",
            "Tamanho" to "Tamaño",
            "Data" to "Fecha",
            "Tipo" to "Tipo",
            "Detalhado" to "Detallado",
            "Compacto" to "Compacto",
            "Grelha" to "Cuadrícula",
            "Copiar" to "Copiar",
            "Mover" to "Mover",
            "Eliminar" to "Eliminar",
            "Renomear" to "Renombrar",
            "Informações" to "Información",
            "Abrir" to "Abrir",
            "Abrir pasta" to "Abrir carpeta",
            "Partilhar" to "Compartir",
            "Extrair aqui" to "Extraer aquí",
            "Extrair para nova pasta" to "Extraer en una carpeta nueva",
            "Ver conteúdo" to "Ver contenido",
            "Testar arquivo" to "Probar archivo",
            "Adicionar aos Favoritos" to "Añadir a favoritos",
            "Remover dos Favoritos" to "Quitar de favoritos",
            "Cancelar" to "Cancelar",
            "Guardar" to "Guardar",
            "Fechar" to "Cerrar",
            "Pasta vazia" to "Carpeta vacía",
            "Armazenamento interno" to "Almacenamiento interno",
            "Selecionar tudo" to "Seleccionar todo",
            "Limpar seleção" to "Quitar selección",
            "Colar" to "Pegar",
            "Favoritos" to "Favoritos",
            "Ordenar" to "Ordenar",
            "Atualizar" to "Actualizar",
            "Pasta de início" to "Carpeta inicial",
            "Pesquisa avançada" to "Búsqueda avanzada",
            "Incluir subpastas" to "Incluir subcarpetas",
            "Todos os tipos" to "Todos los tipos",
            "Pastas" to "Carpetas",
            "Documentos" to "Documentos",
            "Imagens" to "Imágenes",
            "Vídeo" to "Vídeo",
            "Áudio" to "Audio",
            "Arquivos" to "Archivos",
            "Aplicações" to "Aplicaciones",
            "Pasta pesquisada" to "Carpeta buscada",
            "resultados" to "resultados",
            "Nenhum resultado encontrado." to "No se encontraron resultados.",
            "Mais ações" to "Más acciones",
            "Copiar caminho" to "Copiar ruta",
            "Calcular SHA-256" to "Calcular SHA-256",
            "Caminho copiado." to "Ruta copiada.",
            "Partilhar o Archiver" to "Compartir Archiver",
            "Ajudar o projeto" to "Apoyar el proyecto",
            "Convida alguém a experimentar o Archiver" to "Invita a alguien a probar Archiver",
            "Gostas do Archiver?" to "¿Te gusta Archiver?",
            "Agora não" to "Ahora no",
            "Escuro (recomendado)" to "Oscuro (recomendado)",
            "Preto OLED (ecrãs OLED)" to "Negro OLED (pantallas OLED)",
            "Claro (boa visibilidade à luz do dia)" to "Claro (mejor con mucha luz)",
            "12 idiomas" to "12 idiomas"
            )
            "fr" -> mapOf(
            "Explorador" to "Explorateur",
            "Gestor de ficheiros para Android" to "Gestionnaire de fichiers Android",
            "Configurações" to "Paramètres",
            "Geral" to "Général",
            "Interface" to "Interface",
            "Compressão" to "Compression",
            "Idioma" to "Langue",
            "Tema da interface" to "Thème de l’interface",
            "Cor de destaque" to "Couleur d’accentuation",
            "Cores das pastas" to "Couleurs des dossiers",
            "Ordenar ficheiros" to "Trier les fichiers",
            "Nome" to "Nom",
            "Tamanho" to "Taille",
            "Data" to "Date",
            "Tipo" to "Type",
            "Detalhado" to "Détaillé",
            "Compacto" to "Compact",
            "Grelha" to "Grille",
            "Copiar" to "Copier",
            "Mover" to "Déplacer",
            "Eliminar" to "Supprimer",
            "Renomear" to "Renommer",
            "Informações" to "Informations",
            "Abrir" to "Ouvrir",
            "Abrir pasta" to "Ouvrir le dossier",
            "Partilhar" to "Partager",
            "Extrair aqui" to "Extraire ici",
            "Extrair para nova pasta" to "Extraire dans un nouveau dossier",
            "Ver conteúdo" to "Parcourir l’archive",
            "Testar arquivo" to "Tester l’archive",
            "Adicionar aos Favoritos" to "Ajouter aux favoris",
            "Remover dos Favoritos" to "Retirer des favoris",
            "Cancelar" to "Annuler",
            "Guardar" to "Enregistrer",
            "Fechar" to "Fermer",
            "Pasta vazia" to "Dossier vide",
            "Armazenamento interno" to "Stockage interne",
            "Selecionar tudo" to "Tout sélectionner",
            "Limpar seleção" to "Effacer la sélection",
            "Colar" to "Coller",
            "Favoritos" to "Favoris",
            "Ordenar" to "Trier",
            "Atualizar" to "Actualiser",
            "Pasta de início" to "Dossier de départ",
            "Pesquisa avançada" to "Recherche avancée",
            "Incluir subpastas" to "Inclure les sous-dossiers",
            "Todos os tipos" to "Tous les types",
            "Pastas" to "Dossiers",
            "Documentos" to "Documents",
            "Imagens" to "Images",
            "Vídeo" to "Vidéo",
            "Áudio" to "Audio",
            "Arquivos" to "Archives",
            "Aplicações" to "Applications",
            "Pasta pesquisada" to "Dossier recherché",
            "resultados" to "résultats",
            "Nenhum resultado encontrado." to "Aucun résultat trouvé.",
            "Mais ações" to "Plus d’actions",
            "Copiar caminho" to "Copier le chemin",
            "Calcular SHA-256" to "Calculer SHA-256",
            "Caminho copiado." to "Chemin copié.",
            "Partilhar o Archiver" to "Partager Archiver",
            "Ajudar o projeto" to "Soutenir le projet",
            "Convida alguém a experimentar o Archiver" to "Invitez quelqu’un à essayer Archiver",
            "Gostas do Archiver?" to "Archiver vous plaît-il ?",
            "Agora não" to "Pas maintenant",
            "Escuro (recomendado)" to "Sombre (recommandé)",
            "Preto OLED (ecrãs OLED)" to "Noir OLED (écrans OLED)",
            "Claro (boa visibilidade à luz do dia)" to "Clair (lisible en plein jour)",
            "12 idiomas" to "12 langues"
            )
            "de" -> mapOf(
            "Explorador" to "Dateimanager",
            "Gestor de ficheiros para Android" to "Android-Dateimanager",
            "Configurações" to "Einstellungen",
            "Geral" to "Allgemein",
            "Interface" to "Oberfläche",
            "Compressão" to "Komprimierung",
            "Idioma" to "Sprache",
            "Tema da interface" to "Oberflächenthema",
            "Cor de destaque" to "Akzentfarbe",
            "Cores das pastas" to "Ordnerfarben",
            "Ordenar ficheiros" to "Dateien sortieren",
            "Nome" to "Name",
            "Tamanho" to "Größe",
            "Data" to "Datum",
            "Tipo" to "Typ",
            "Detalhado" to "Detailliert",
            "Compacto" to "Kompakt",
            "Grelha" to "Raster",
            "Copiar" to "Kopieren",
            "Mover" to "Verschieben",
            "Eliminar" to "Löschen",
            "Renomear" to "Umbenennen",
            "Informações" to "Informationen",
            "Abrir" to "Öffnen",
            "Abrir pasta" to "Ordner öffnen",
            "Partilhar" to "Teilen",
            "Extrair aqui" to "Hier entpacken",
            "Extrair para nova pasta" to "In neuen Ordner entpacken",
            "Ver conteúdo" to "Archiv durchsuchen",
            "Testar arquivo" to "Archiv testen",
            "Adicionar aos Favoritos" to "Zu Favoriten hinzufügen",
            "Remover dos Favoritos" to "Aus Favoriten entfernen",
            "Cancelar" to "Abbrechen",
            "Guardar" to "Speichern",
            "Fechar" to "Schließen",
            "Pasta vazia" to "Ordner ist leer",
            "Armazenamento interno" to "Interner Speicher",
            "Selecionar tudo" to "Alles auswählen",
            "Limpar seleção" to "Auswahl aufheben",
            "Colar" to "Einfügen",
            "Favoritos" to "Favoriten",
            "Ordenar" to "Sortieren",
            "Atualizar" to "Aktualisieren",
            "Pasta de início" to "Startordner",
            "Pesquisa avançada" to "Erweiterte Suche",
            "Incluir subpastas" to "Unterordner einschließen",
            "Todos os tipos" to "Alle Dateitypen",
            "Pastas" to "Ordner",
            "Documentos" to "Dokumente",
            "Imagens" to "Bilder",
            "Vídeo" to "Video",
            "Áudio" to "Audio",
            "Arquivos" to "Archive",
            "Aplicações" to "Apps",
            "Pasta pesquisada" to "Durchsuchter Ordner",
            "resultados" to "Ergebnisse",
            "Nenhum resultado encontrado." to "Keine Ergebnisse gefunden.",
            "Mais ações" to "Weitere Aktionen",
            "Copiar caminho" to "Pfad kopieren",
            "Calcular SHA-256" to "SHA-256 berechnen",
            "Caminho copiado." to "Pfad kopiert.",
            "Partilhar o Archiver" to "Archiver teilen",
            "Ajudar o projeto" to "Projekt unterstützen",
            "Convida alguém a experimentar o Archiver" to "Lade jemanden ein, Archiver auszuprobieren",
            "Gostas do Archiver?" to "Gefällt dir Archiver?",
            "Agora não" to "Jetzt nicht",
            "Escuro (recomendado)" to "Dunkel (empfohlen)",
            "Preto OLED (ecrãs OLED)" to "OLED-Schwarz (OLED-Displays)",
            "Claro (boa visibilidade à luz do dia)" to "Hell (gut bei Tageslicht)",
            "12 idiomas" to "12 Sprachen"
            )
            "zh" -> mapOf(
            "Explorador" to "文件管理器",
            "Gestor de ficheiros para Android" to "Android 文件管理器",
            "Configurações" to "设置",
            "Geral" to "常规",
            "Interface" to "界面",
            "Compressão" to "压缩",
            "Idioma" to "语言",
            "Tema da interface" to "界面主题",
            "Cor de destaque" to "强调色",
            "Cores das pastas" to "文件夹颜色",
            "Ordenar ficheiros" to "文件排序",
            "Nome" to "名称",
            "Tamanho" to "大小",
            "Data" to "日期",
            "Tipo" to "类型",
            "Detalhado" to "详细列表",
            "Compacto" to "紧凑列表",
            "Grelha" to "网格",
            "Copiar" to "复制",
            "Mover" to "移动",
            "Eliminar" to "删除",
            "Renomear" to "重命名",
            "Informações" to "信息",
            "Abrir" to "打开",
            "Abrir pasta" to "打开文件夹",
            "Partilhar" to "分享",
            "Extrair aqui" to "解压到此处",
            "Extrair para nova pasta" to "解压到新文件夹",
            "Ver conteúdo" to "浏览压缩包",
            "Testar arquivo" to "测试压缩包",
            "Adicionar aos Favoritos" to "添加到收藏夹",
            "Remover dos Favoritos" to "从收藏夹移除",
            "Cancelar" to "取消",
            "Guardar" to "保存",
            "Fechar" to "关闭",
            "Pasta vazia" to "文件夹为空",
            "Armazenamento interno" to "内部存储",
            "Selecionar tudo" to "全选",
            "Limpar seleção" to "清除选择",
            "Colar" to "粘贴",
            "Favoritos" to "收藏夹",
            "Ordenar" to "排序",
            "Atualizar" to "刷新",
            "Pasta de início" to "起始文件夹",
            "Pesquisa avançada" to "高级搜索",
            "Incluir subpastas" to "搜索子文件夹",
            "Todos os tipos" to "所有类型",
            "Pastas" to "文件夹",
            "Documentos" to "文档",
            "Imagens" to "图片",
            "Vídeo" to "视频",
            "Áudio" to "音频",
            "Arquivos" to "压缩包",
            "Aplicações" to "应用",
            "Pasta pesquisada" to "搜索范围",
            "resultados" to "个结果",
            "Nenhum resultado encontrado." to "没有找到结果。",
            "Mais ações" to "更多操作",
            "Copiar caminho" to "复制路径",
            "Calcular SHA-256" to "计算 SHA-256",
            "Caminho copiado." to "路径已复制。",
            "Partilhar o Archiver" to "分享 Archiver",
            "Ajudar o projeto" to "支持项目",
            "Convida alguém a experimentar o Archiver" to "邀请他人试用 Archiver",
            "Gostas do Archiver?" to "喜欢 Archiver 吗？",
            "Agora não" to "暂时不了",
            "Escuro (recomendado)" to "深色（推荐）",
            "Preto OLED (ecrãs OLED)" to "OLED 纯黑（OLED 屏幕）",
            "Claro (boa visibilidade à luz do dia)" to "浅色（适合明亮环境）",
            "12 idiomas" to "12 种语言"
            )
            "hi" -> mapOf(
            "Explorador" to "फ़ाइल प्रबंधक",
            "Gestor de ficheiros para Android" to "Android फ़ाइल प्रबंधक",
            "Configurações" to "सेटिंग",
            "Geral" to "सामान्य",
            "Interface" to "इंटरफ़ेस",
            "Compressão" to "संपीड़न",
            "Idioma" to "भाषा",
            "Tema da interface" to "इंटरफ़ेस थीम",
            "Cor de destaque" to "मुख्य रंग",
            "Cores das pastas" to "फ़ोल्डर के रंग",
            "Ordenar ficheiros" to "फ़ाइलें क्रमबद्ध करें",
            "Nome" to "नाम",
            "Tamanho" to "आकार",
            "Data" to "तारीख",
            "Tipo" to "प्रकार",
            "Detalhado" to "विस्तृत",
            "Compacto" to "कॉम्पैक्ट",
            "Grelha" to "ग्रिड",
            "Copiar" to "कॉपी करें",
            "Mover" to "स्थानांतरित करें",
            "Eliminar" to "हटाएँ",
            "Renomear" to "नाम बदलें",
            "Informações" to "जानकारी",
            "Abrir" to "खोलें",
            "Abrir pasta" to "फ़ोल्डर खोलें",
            "Partilhar" to "साझा करें",
            "Extrair aqui" to "यहाँ निकालें",
            "Extrair para nova pasta" to "नए फ़ोल्डर में निकालें",
            "Ver conteúdo" to "आर्काइव देखें",
            "Testar arquivo" to "आर्काइव जाँचें",
            "Adicionar aos Favoritos" to "पसंदीदा में जोड़ें",
            "Remover dos Favoritos" to "पसंदीदा से हटाएँ",
            "Cancelar" to "रद्द करें",
            "Guardar" to "सहेजें",
            "Fechar" to "बंद करें",
            "Pasta vazia" to "फ़ोल्डर खाली है",
            "Armazenamento interno" to "आंतरिक स्टोरेज",
            "Selecionar tudo" to "सभी चुनें",
            "Limpar seleção" to "चयन हटाएँ",
            "Colar" to "पेस्ट करें",
            "Favoritos" to "पसंदीदा",
            "Ordenar" to "क्रमबद्ध करें",
            "Atualizar" to "रीफ़्रेश",
            "Pasta de início" to "होम फ़ोल्डर",
            "Pesquisa avançada" to "उन्नत खोज",
            "Incluir subpastas" to "सबफ़ोल्डर शामिल करें",
            "Todos os tipos" to "सभी प्रकार",
            "Pastas" to "फ़ोल्डर",
            "Documentos" to "दस्तावेज़",
            "Imagens" to "चित्र",
            "Vídeo" to "वीडियो",
            "Áudio" to "ऑडियो",
            "Arquivos" to "आर्काइव",
            "Aplicações" to "ऐप",
            "Pasta pesquisada" to "खोजा गया फ़ोल्डर",
            "resultados" to "परिणाम",
            "Nenhum resultado encontrado." to "कोई परिणाम नहीं मिला।",
            "Mais ações" to "और कार्रवाइयाँ",
            "Copiar caminho" to "पथ कॉपी करें",
            "Calcular SHA-256" to "SHA-256 निकालें",
            "Caminho copiado." to "पथ कॉपी हो गया।",
            "Partilhar o Archiver" to "Archiver साझा करें",
            "Ajudar o projeto" to "प्रोजेक्ट का समर्थन करें",
            "Convida alguém a experimentar o Archiver" to "किसी को Archiver आज़माने के लिए आमंत्रित करें",
            "Gostas do Archiver?" to "क्या आपको Archiver पसंद है?",
            "Agora não" to "अभी नहीं",
            "Escuro (recomendado)" to "डार्क (अनुशंसित)",
            "Preto OLED (ecrãs OLED)" to "OLED ब्लैक (OLED डिस्प्ले)",
            "Claro (boa visibilidade à luz do dia)" to "लाइट (दिन के उजाले में बेहतर)",
            "12 idiomas" to "12 भाषाएँ"
            )
            "ar" -> mapOf(
            "Explorador" to "مدير الملفات",
            "Gestor de ficheiros para Android" to "مدير ملفات أندرويد",
            "Configurações" to "الإعدادات",
            "Geral" to "عام",
            "Interface" to "الواجهة",
            "Compressão" to "الضغط",
            "Idioma" to "اللغة",
            "Tema da interface" to "مظهر الواجهة",
            "Cor de destaque" to "لون التمييز",
            "Cores das pastas" to "ألوان المجلدات",
            "Ordenar ficheiros" to "ترتيب الملفات",
            "Nome" to "الاسم",
            "Tamanho" to "الحجم",
            "Data" to "التاريخ",
            "Tipo" to "النوع",
            "Detalhado" to "تفصيلي",
            "Compacto" to "مضغوط",
            "Grelha" to "شبكة",
            "Copiar" to "نسخ",
            "Mover" to "نقل",
            "Eliminar" to "حذف",
            "Renomear" to "إعادة تسمية",
            "Informações" to "معلومات",
            "Abrir" to "فتح",
            "Abrir pasta" to "فتح المجلد",
            "Partilhar" to "مشاركة",
            "Extrair aqui" to "استخراج هنا",
            "Extrair para nova pasta" to "استخراج إلى مجلد جديد",
            "Ver conteúdo" to "استعراض الأرشيف",
            "Testar arquivo" to "اختبار الأرشيف",
            "Adicionar aos Favoritos" to "إضافة إلى المفضلة",
            "Remover dos Favoritos" to "إزالة من المفضلة",
            "Cancelar" to "إلغاء",
            "Guardar" to "حفظ",
            "Fechar" to "إغلاق",
            "Pasta vazia" to "المجلد فارغ",
            "Armazenamento interno" to "التخزين الداخلي",
            "Selecionar tudo" to "تحديد الكل",
            "Limpar seleção" to "مسح التحديد",
            "Colar" to "لصق",
            "Favoritos" to "المفضلة",
            "Ordenar" to "ترتيب",
            "Atualizar" to "تحديث",
            "Pasta de início" to "مجلد البداية",
            "Pesquisa avançada" to "بحث متقدم",
            "Incluir subpastas" to "تضمين المجلدات الفرعية",
            "Todos os tipos" to "كل الأنواع",
            "Pastas" to "مجلدات",
            "Documentos" to "مستندات",
            "Imagens" to "صور",
            "Vídeo" to "فيديو",
            "Áudio" to "صوت",
            "Arquivos" to "أرشيف",
            "Aplicações" to "تطبيقات",
            "Pasta pesquisada" to "المجلد الذي تم البحث فيه",
            "resultados" to "نتائج",
            "Nenhum resultado encontrado." to "لم يتم العثور على نتائج.",
            "Mais ações" to "مزيد من الإجراءات",
            "Copiar caminho" to "نسخ المسار",
            "Calcular SHA-256" to "حساب SHA-256",
            "Caminho copiado." to "تم نسخ المسار.",
            "Partilhar o Archiver" to "مشاركة Archiver",
            "Ajudar o projeto" to "دعم المشروع",
            "Convida alguém a experimentar o Archiver" to "ادعُ شخصًا لتجربة Archiver",
            "Gostas do Archiver?" to "هل أعجبك Archiver؟",
            "Agora não" to "ليس الآن",
            "Escuro (recomendado)" to "داكن (موصى به)",
            "Preto OLED (ecrãs OLED)" to "أسود OLED (شاشات OLED)",
            "Claro (boa visibilidade à luz do dia)" to "فاتح (أفضل في ضوء النهار)",
            "12 idiomas" to "12 لغات"
            )
            "ja" -> mapOf(
            "Explorador" to "ファイル管理",
            "Gestor de ficheiros para Android" to "Android ファイルマネージャー",
            "Configurações" to "設定",
            "Geral" to "一般",
            "Interface" to "インターフェース",
            "Compressão" to "圧縮",
            "Idioma" to "言語",
            "Tema da interface" to "テーマ",
            "Cor de destaque" to "アクセントカラー",
            "Cores das pastas" to "フォルダーの色",
            "Ordenar ficheiros" to "ファイルを並べ替え",
            "Nome" to "名前",
            "Tamanho" to "サイズ",
            "Data" to "日付",
            "Tipo" to "種類",
            "Detalhado" to "詳細",
            "Compacto" to "コンパクト",
            "Grelha" to "グリッド",
            "Copiar" to "コピー",
            "Mover" to "移動",
            "Eliminar" to "削除",
            "Renomear" to "名前を変更",
            "Informações" to "情報",
            "Abrir" to "開く",
            "Abrir pasta" to "フォルダーを開く",
            "Partilhar" to "共有",
            "Extrair aqui" to "ここに展開",
            "Extrair para nova pasta" to "新しいフォルダーに展開",
            "Ver conteúdo" to "アーカイブを表示",
            "Testar arquivo" to "アーカイブを検査",
            "Adicionar aos Favoritos" to "お気に入りに追加",
            "Remover dos Favoritos" to "お気に入りから削除",
            "Cancelar" to "キャンセル",
            "Guardar" to "保存",
            "Fechar" to "閉じる",
            "Pasta vazia" to "空のフォルダー",
            "Armazenamento interno" to "内部ストレージ",
            "Selecionar tudo" to "すべて選択",
            "Limpar seleção" to "選択を解除",
            "Colar" to "貼り付け",
            "Favoritos" to "お気に入り",
            "Ordenar" to "並べ替え",
            "Atualizar" to "更新",
            "Pasta de início" to "ホームフォルダー",
            "Pesquisa avançada" to "詳細検索",
            "Incluir subpastas" to "サブフォルダーも検索",
            "Todos os tipos" to "すべての種類",
            "Pastas" to "フォルダー",
            "Documentos" to "ドキュメント",
            "Imagens" to "画像",
            "Vídeo" to "動画",
            "Áudio" to "音声",
            "Arquivos" to "アーカイブ",
            "Aplicações" to "アプリ",
            "Pasta pesquisada" to "検索対象フォルダー",
            "resultados" to "件",
            "Nenhum resultado encontrado." to "結果が見つかりません。",
            "Mais ações" to "その他の操作",
            "Copiar caminho" to "パスをコピー",
            "Calcular SHA-256" to "SHA-256を計算",
            "Caminho copiado." to "パスをコピーしました。",
            "Partilhar o Archiver" to "Archiverを共有",
            "Ajudar o projeto" to "プロジェクトを応援",
            "Convida alguém a experimentar o Archiver" to "Archiverを誰かに紹介してください",
            "Gostas do Archiver?" to "Archiverを気に入りましたか？",
            "Agora não" to "あとで",
            "Escuro (recomendado)" to "ダーク（おすすめ）",
            "Preto OLED (ecrãs OLED)" to "OLEDブラック（OLED画面）",
            "Claro (boa visibilidade à luz do dia)" to "ライト（明るい場所向け）",
            "12 idiomas" to "12言語"
            )
            "ru" -> mapOf(
                "Explorador" to "Файловый менеджер", "Gestor de ficheiros para Android" to "Файловый менеджер Android",
                "Configurações" to "Настройки", "Geral" to "Общие", "Interface" to "Интерфейс",
                "Compressão" to "Сжатие", "Idioma" to "Язык", "Tema da interface" to "Тема интерфейса",
                "Cor de destaque" to "Акцентный цвет", "Cores das pastas" to "Цвета папок",
                "Ordenar ficheiros" to "Сортировка файлов", "Nome" to "Имя", "Tamanho" to "Размер",
                "Data" to "Дата", "Tipo" to "Тип", "Detalhado" to "Подробный", "Compacto" to "Компактный",
                "Grelha" to "Сетка", "Copiar" to "Копировать", "Mover" to "Переместить",
                "Eliminar" to "Удалить", "Renomear" to "Переименовать", "Informações" to "Сведения",
                "Abrir" to "Открыть", "Abrir pasta" to "Открыть папку", "Partilhar" to "Поделиться",
                "Extrair aqui" to "Извлечь сюда", "Extrair para nova pasta" to "Извлечь в новую папку",
                "Ver conteúdo" to "Просмотр архива", "Testar arquivo" to "Проверить архив",
                "Adicionar aos Favoritos" to "Добавить в избранное", "Remover dos Favoritos" to "Удалить из избранного",
                "Cancelar" to "Отмена", "Guardar" to "Сохранить", "Fechar" to "Закрыть",
                "Pasta vazia" to "Папка пуста", "Armazenamento interno" to "Внутренняя память",
                "Selecionar tudo" to "Выбрать всё", "Limpar seleção" to "Снять выделение",
                "Colar" to "Вставить", "Favoritos" to "Избранное", "Ordenar" to "Сортировать",
                "Atualizar" to "Обновить", "Pasta de início" to "Домашняя папка",
                "Pesquisa avançada" to "Расширенный поиск", "Incluir subpastas" to "Включать подпапки",
                "Todos os tipos" to "Все типы", "Pastas" to "Папки", "Documentos" to "Документы",
                "Imagens" to "Изображения", "Vídeo" to "Видео", "Áudio" to "Аудио",
                "Arquivos" to "Архивы", "Aplicações" to "Приложения",
                "Pasta pesquisada" to "Папка поиска", "resultados" to "результатов",
                "Nenhum resultado encontrado." to "Ничего не найдено.", "Mais ações" to "Другие действия",
                "Copiar caminho" to "Копировать путь", "Calcular SHA-256" to "Вычислить SHA-256",
                "Caminho copiado." to "Путь скопирован.", "Partilhar o Archiver" to "Поделиться Archiver",
                "Ajudar o projeto" to "Поддержать проект", "Convida alguém a experimentar o Archiver" to "Предложите друзьям попробовать Archiver",
                "Gostas do Archiver?" to "Нравится Archiver?", "Agora não" to "Не сейчас",
                "Escuro (recomendado)" to "Тёмная тема (рекомендуется)",
                "Preto OLED (ecrãs OLED)" to "Чёрная OLED-тема (OLED-дисплеи)",
                "Claro (boa visibilidade à luz do dia)" to "Светлая тема (для дневного света)",
                "12 idiomas" to "12 языков"
            )
            "bn" -> mapOf(
                "Explorador" to "ফাইল ম্যানেজার", "Gestor de ficheiros para Android" to "অ্যান্ড্রয়েড ফাইল ম্যানেজার",
                "Configurações" to "সেটিংস", "Geral" to "সাধারণ", "Interface" to "ইন্টারফেস",
                "Compressão" to "কমপ্রেশন", "Idioma" to "ভাষা", "Tema da interface" to "ইন্টারফেস থিম",
                "Cor de destaque" to "অ্যাকসেন্ট রং", "Cores das pastas" to "ফোল্ডারের রং",
                "Ordenar ficheiros" to "ফাইল সাজান", "Nome" to "নাম", "Tamanho" to "আকার",
                "Data" to "তারিখ", "Tipo" to "ধরন", "Detalhado" to "বিস্তারিত", "Compacto" to "কমপ্যাক্ট",
                "Grelha" to "গ্রিড", "Copiar" to "কপি", "Mover" to "সরান",
                "Eliminar" to "মুছুন", "Renomear" to "নাম পরিবর্তন", "Informações" to "তথ্য",
                "Abrir" to "খুলুন", "Abrir pasta" to "ফোল্ডার খুলুন", "Partilhar" to "শেয়ার",
                "Extrair aqui" to "এখানে আনজিপ করুন", "Extrair para nova pasta" to "নতুন ফোল্ডারে আনজিপ করুন",
                "Ver conteúdo" to "আর্কাইভ দেখুন", "Testar arquivo" to "আর্কাইভ পরীক্ষা করুন",
                "Adicionar aos Favoritos" to "পছন্দে যোগ করুন", "Remover dos Favoritos" to "পছন্দ থেকে সরান",
                "Cancelar" to "বাতিল", "Guardar" to "সংরক্ষণ", "Fechar" to "বন্ধ",
                "Pasta vazia" to "ফোল্ডার খালি", "Armazenamento interno" to "অভ্যন্তরীণ স্টোরেজ",
                "Selecionar tudo" to "সব নির্বাচন", "Limpar seleção" to "নির্বাচন বাতিল",
                "Colar" to "পেস্ট", "Favoritos" to "পছন্দ", "Ordenar" to "সাজান",
                "Atualizar" to "রিফ্রেশ", "Pasta de início" to "হোম ফোল্ডার",
                "Pesquisa avançada" to "উন্নত অনুসন্ধান", "Incluir subpastas" to "সাবফোল্ডার অন্তর্ভুক্ত করুন",
                "Todos os tipos" to "সব ধরন", "Pastas" to "ফোল্ডার", "Documentos" to "ডকুমেন্ট",
                "Imagens" to "ছবি", "Vídeo" to "ভিডিও", "Áudio" to "অডিও",
                "Arquivos" to "আর্কাইভ", "Aplicações" to "অ্যাপ",
                "Pasta pesquisada" to "অনুসন্ধানের ফোল্ডার", "resultados" to "টি ফলাফল",
                "Nenhum resultado encontrado." to "কোনো ফলাফল পাওয়া যায়নি।", "Mais ações" to "আরও কাজ",
                "Copiar caminho" to "পাথ কপি করুন", "Calcular SHA-256" to "SHA-256 গণনা করুন",
                "Caminho copiado." to "পাথ কপি হয়েছে।", "Partilhar o Archiver" to "Archiver শেয়ার করুন",
                "Ajudar o projeto" to "প্রকল্পকে সহায়তা করুন", "Convida alguém a experimentar o Archiver" to "বন্ধুকে Archiver ব্যবহার করতে বলুন",
                "Gostas do Archiver?" to "Archiver কি ভালো লেগেছে?", "Agora não" to "এখন নয়",
                "Escuro (recomendado)" to "গাঢ় থিম (প্রস্তাবিত)", "Preto OLED (ecrãs OLED)" to "OLED কালো থিম",
                "Claro (boa visibilidade à luz do dia)" to "হালকা থিম", "12 idiomas" to "12টি ভাষা"
            )
            "id" -> mapOf(
                "Explorador" to "Pengelola berkas", "Gestor de ficheiros para Android" to "Pengelola berkas Android",
                "Configurações" to "Pengaturan", "Geral" to "Umum", "Interface" to "Antarmuka",
                "Compressão" to "Kompresi", "Idioma" to "Bahasa", "Tema da interface" to "Tema antarmuka",
                "Cor de destaque" to "Warna aksen", "Cores das pastas" to "Warna folder",
                "Ordenar ficheiros" to "Urutkan berkas", "Nome" to "Nama", "Tamanho" to "Ukuran",
                "Data" to "Tanggal", "Tipo" to "Jenis", "Detalhado" to "Terperinci", "Compacto" to "Ringkas",
                "Grelha" to "Kisi", "Copiar" to "Salin", "Mover" to "Pindahkan",
                "Eliminar" to "Hapus", "Renomear" to "Ganti nama", "Informações" to "Informasi",
                "Abrir" to "Buka", "Abrir pasta" to "Buka folder", "Partilhar" to "Bagikan",
                "Extrair aqui" to "Ekstrak di sini", "Extrair para nova pasta" to "Ekstrak ke folder baru",
                "Ver conteúdo" to "Lihat isi arsip", "Testar arquivo" to "Uji arsip",
                "Adicionar aos Favoritos" to "Tambahkan ke Favorit", "Remover dos Favoritos" to "Hapus dari Favorit",
                "Cancelar" to "Batal", "Guardar" to "Simpan", "Fechar" to "Tutup",
                "Pasta vazia" to "Folder kosong", "Armazenamento interno" to "Penyimpanan internal",
                "Selecionar tudo" to "Pilih semua", "Limpar seleção" to "Hapus pilihan",
                "Colar" to "Tempel", "Favoritos" to "Favorit", "Ordenar" to "Urutkan",
                "Atualizar" to "Segarkan", "Pasta de início" to "Folder awal",
                "Pesquisa avançada" to "Pencarian lanjutan", "Incluir subpastas" to "Sertakan subfolder",
                "Todos os tipos" to "Semua jenis", "Pastas" to "Folder", "Documentos" to "Dokumen",
                "Imagens" to "Gambar", "Vídeo" to "Video", "Áudio" to "Audio",
                "Arquivos" to "Arsip", "Aplicações" to "Aplikasi",
                "Pasta pesquisada" to "Folder yang dicari", "resultados" to "hasil",
                "Nenhum resultado encontrado." to "Tidak ada hasil ditemukan.", "Mais ações" to "Tindakan lainnya",
                "Copiar caminho" to "Salin jalur", "Calcular SHA-256" to "Hitung SHA-256",
                "Caminho copiado." to "Jalur disalin.", "Partilhar o Archiver" to "Bagikan Archiver",
                "Ajudar o projeto" to "Dukung proyek", "Convida alguém a experimentar o Archiver" to "Ajak teman mencoba Archiver",
                "Gostas do Archiver?" to "Suka Archiver?", "Agora não" to "Nanti saja",
                "Escuro (recomendado)" to "Gelap (disarankan)", "Preto OLED (ecrãs OLED)" to "Hitam OLED",
                "Claro (boa visibilidade à luz do dia)" to "Terang (untuk siang hari)", "12 idiomas" to "12 bahasa"
            )
            else -> emptyMap()
        }
        val localizedExtras = when (appLanguage) {
            "es" -> mapOf("Tamanho total" to "Tamaño total", "Itens" to "Elementos", "itens" to "elementos", "Tamanho calculado" to "Tamaño calculado", "Seleciona uma pasta." to "Selecciona una carpeta.", "A calcular tamanho da pasta…" to "Calculando el tamaño de la carpeta…")
            "fr" -> mapOf("Tamanho total" to "Taille totale", "Itens" to "Éléments", "itens" to "éléments", "Tamanho calculado" to "Taille calculée", "Seleciona uma pasta." to "Choisissez un dossier.", "A calcular tamanho da pasta…" to "Calcul de la taille du dossier…")
            "de" -> mapOf("Tamanho total" to "Gesamtgröße", "Itens" to "Elemente", "itens" to "Elemente", "Tamanho calculado" to "Berechnete Größe", "Seleciona uma pasta." to "Wähle einen Ordner.", "A calcular tamanho da pasta…" to "Ordnergröße wird berechnet…")
            "zh" -> mapOf("Tamanho total" to "总大小", "Itens" to "项目", "itens" to "个项目", "Tamanho calculado" to "已计算大小", "Seleciona uma pasta." to "请选择文件夹。", "A calcular tamanho da pasta…" to "正在计算文件夹大小…")
            "hi" -> mapOf("Tamanho total" to "कुल आकार", "Itens" to "आइटम", "itens" to "आइटम", "Tamanho calculado" to "गणना किया गया आकार", "Seleciona uma pasta." to "एक फ़ोल्डर चुनें।", "A calcular tamanho da pasta…" to "फ़ोल्डर का आकार गणना हो रहा है…")
            "ar" -> mapOf("Tamanho total" to "الحجم الإجمالي", "Itens" to "العناصر", "itens" to "عناصر", "Tamanho calculado" to "الحجم المحسوب", "Seleciona uma pasta." to "اختر مجلدًا.", "A calcular tamanho da pasta…" to "جارٍ حساب حجم المجلد…")
            "ja" -> mapOf("Tamanho total" to "合計サイズ", "Itens" to "項目", "itens" to "項目", "Tamanho calculado" to "計算済みサイズ", "Seleciona uma pasta." to "フォルダーを選択してください。", "A calcular tamanho da pasta…" to "フォルダーサイズを計算中…")
            "ru" -> mapOf("Tamanho total" to "Общий размер", "Itens" to "Элементы", "itens" to "элементов", "Tamanho calculado" to "Размер рассчитан", "Seleciona uma pasta." to "Выберите папку.", "A calcular tamanho da pasta…" to "Вычисление размера папки…")
            "bn" -> mapOf("Tamanho total" to "মোট আকার", "Itens" to "আইটেম", "itens" to "টি আইটেম", "Tamanho calculado" to "গণনা করা আকার", "Seleciona uma pasta." to "একটি ফোল্ডার নির্বাচন করুন।", "A calcular tamanho da pasta…" to "ফোল্ডারের আকার গণনা হচ্ছে…")
            "id" -> mapOf("Tamanho total" to "Ukuran total", "Itens" to "Item", "itens" to "item", "Tamanho calculado" to "Ukuran dihitung", "Seleciona uma pasta." to "Pilih folder.", "A calcular tamanho da pasta…" to "Menghitung ukuran folder…")
            else -> emptyMap()
        }
        val readerTranslations = when (appLanguage) {
            "es" -> mapOf("Leitor de PDF" to "Lector de PDF", "Leitor de áudio" to "Reproductor de audio", "A abrir PDF…" to "Abriendo PDF…", "A carregar imagem…" to "Cargando imagen…", "Pré-visualização da imagem" to "Vista previa de imagen", "Não foi possível abrir PDF." to "No se pudo abrir el PDF.", "Não foi possível abrir a imagem." to "No se pudo abrir la imagen.", "Ficheiro de imagem inválido." to "Archivo de imagen no válido.", "A preparar áudio…" to "Preparando audio…", "Não foi possível reproduzir o áudio." to "No se pudo reproducir el audio.", "Reprodução concluída." to "Reproducción finalizada.", "Reproduzir" to "Reproducir", "Pausar" to "Pausar", "Parar" to "Detener", "Pronto" to "Listo", "Anterior" to "Anterior", "Seguinte" to "Siguiente", "Extrair pasta atual" to "Extraer carpeta actual", "Extrair item" to "Extraer elemento", "para a pasta atual" to "a la carpeta actual", "Extrair" to "Extraer")
            "fr" -> mapOf("Leitor de PDF" to "Lecteur PDF", "Leitor de áudio" to "Lecteur audio", "A abrir PDF…" to "Ouverture du PDF…", "A carregar imagem…" to "Chargement de l’image…", "Pré-visualização da imagem" to "Aperçu de l’image", "Não foi possível abrir PDF." to "Impossible d’ouvrir le PDF.", "Não foi possível abrir a imagem." to "Impossible d’ouvrir l’image.", "Ficheiro de imagem inválido." to "Fichier image invalide.", "A preparar áudio…" to "Préparation de l’audio…", "Não foi possível reproduzir o áudio." to "Impossible de lire l’audio.", "Reprodução concluída." to "Lecture terminée.", "Reproduzir" to "Lire", "Pausar" to "Pause", "Parar" to "Arrêter", "Pronto" to "Prêt", "Anterior" to "Précédent", "Seguinte" to "Suivant", "Extrair pasta atual" to "Extraire le dossier actuel", "Extrair item" to "Extraire l’élément", "para a pasta atual" to "dans le dossier actuel", "Extrair" to "Extraire")
            "de" -> mapOf("Leitor de PDF" to "PDF-Reader", "Leitor de áudio" to "Audioplayer", "A abrir PDF…" to "PDF wird geöffnet…", "A carregar imagem…" to "Bild wird geladen…", "Pré-visualização da imagem" to "Bildvorschau", "Não foi possível abrir PDF." to "PDF konnte nicht geöffnet werden.", "Não foi possível abrir a imagem." to "Bild konnte nicht geöffnet werden.", "Ficheiro de imagem inválido." to "Ungültige Bilddatei.", "A preparar áudio…" to "Audio wird vorbereitet…", "Não foi possível reproduzir o áudio." to "Audio kann nicht abgespielt werden.", "Reprodução concluída." to "Wiedergabe abgeschlossen.", "Reproduzir" to "Wiedergabe", "Pausar" to "Pause", "Parar" to "Stopp", "Pronto" to "Bereit", "Anterior" to "Zurück", "Seguinte" to "Weiter", "Extrair pasta atual" to "Aktuellen Ordner entpacken", "Extrair item" to "Element entpacken", "para a pasta atual" to "in den aktuellen Ordner", "Extrair" to "Entpacken")
            "zh" -> mapOf("Leitor de PDF" to "PDF 阅读器", "Leitor de áudio" to "音频播放器", "A abrir PDF…" to "正在打开 PDF…", "A carregar imagem…" to "正在加载图片…", "Pré-visualização da imagem" to "图片预览", "Não foi possível abrir PDF." to "无法打开 PDF。", "Não foi possível abrir a imagem." to "无法打开图片。", "Ficheiro de imagem inválido." to "无效的图片文件。", "A preparar áudio…" to "正在准备音频…", "Não foi possível reproduzir o áudio." to "无法播放音频。", "Reprodução concluída." to "播放完毕。", "Reproduzir" to "播放", "Pausar" to "暂停", "Parar" to "停止", "Pronto" to "就绪", "Anterior" to "上一页", "Seguinte" to "下一页", "Extrair pasta atual" to "解压当前文件夹", "Extrair item" to "解压项目", "para a pasta atual" to "到当前文件夹", "Extrair" to "解压")
            "hi" -> mapOf("Leitor de PDF" to "PDF रीडर", "Leitor de áudio" to "ऑडियो प्लेयर", "A abrir PDF…" to "PDF खोला जा रहा है…", "A carregar imagem…" to "चित्र लोड हो रहा है…", "Pré-visualização da imagem" to "चित्र पूर्वावलोकन", "Não foi possível abrir PDF." to "PDF नहीं खुल सका।", "Não foi possível abrir a imagem." to "चित्र नहीं खुल सका।", "Ficheiro de imagem inválido." to "अमान्य चित्र फ़ाइल।", "A preparar áudio…" to "ऑडियो तैयार हो रहा है…", "Não foi possível reproduzir o áudio." to "ऑडियो नहीं चल सका।", "Reprodução concluída." to "प्लेबैक पूरा हुआ।", "Reproduzir" to "चलाएँ", "Pausar" to "रोकें", "Parar" to "बंद करें", "Pronto" to "तैयार", "Anterior" to "पिछला", "Seguinte" to "अगला", "Extrair pasta atual" to "वर्तमान फ़ोल्डर निकालें", "Extrair item" to "आइटम निकालें", "para a pasta atual" to "वर्तमान फ़ोल्डर में", "Extrair" to "निकालें")
            "ar" -> mapOf("Leitor de PDF" to "قارئ PDF", "Leitor de áudio" to "مشغل الصوت", "A abrir PDF…" to "جارٍ فتح PDF…", "A carregar imagem…" to "جارٍ تحميل الصورة…", "Pré-visualização da imagem" to "معاينة الصورة", "Não foi possível abrir PDF." to "تعذّر فتح PDF.", "Não foi possível abrir a imagem." to "تعذّر فتح الصورة.", "Ficheiro de imagem inválido." to "ملف صورة غير صالح.", "A preparar áudio…" to "جارٍ تجهيز الصوت…", "Não foi possível reproduzir o áudio." to "تعذّر تشغيل الصوت.", "Reprodução concluída." to "اكتمل التشغيل.", "Reproduzir" to "تشغيل", "Pausar" to "إيقاف مؤقت", "Parar" to "إيقاف", "Pronto" to "جاهز", "Anterior" to "السابق", "Seguinte" to "التالي", "Extrair pasta atual" to "استخراج المجلد الحالي", "Extrair item" to "استخراج العنصر", "para a pasta atual" to "إلى المجلد الحالي", "Extrair" to "استخراج")
            "ja" -> mapOf("Leitor de PDF" to "PDF リーダー", "Leitor de áudio" to "音声プレーヤー", "A abrir PDF…" to "PDF を開いています…", "A carregar imagem…" to "画像を読み込み中…", "Pré-visualização da imagem" to "画像プレビュー", "Não foi possível abrir PDF." to "PDF を開けませんでした。", "Não foi possível abrir a imagem." to "画像を開けませんでした。", "Ficheiro de imagem inválido." to "無効な画像ファイルです。", "A preparar áudio…" to "音声を準備中…", "Não foi possível reproduzir o áudio." to "音声を再生できません。", "Reprodução concluída." to "再生が完了しました。", "Reproduzir" to "再生", "Pausar" to "一時停止", "Parar" to "停止", "Pronto" to "準備完了", "Anterior" to "前へ", "Seguinte" to "次へ", "Extrair pasta atual" to "現在のフォルダーを展開", "Extrair item" to "項目を展開", "para a pasta atual" to "現在のフォルダーへ", "Extrair" to "展開")
            "ru" -> mapOf("Leitor de PDF" to "Чтение PDF", "Leitor de áudio" to "Аудиоплеер", "A abrir PDF…" to "Открытие PDF…", "A carregar imagem…" to "Загрузка изображения…", "Pré-visualização da imagem" to "Предпросмотр изображения", "Não foi possível abrir PDF." to "Не удалось открыть PDF.", "Não foi possível abrir a imagem." to "Не удалось открыть изображение.", "Ficheiro de imagem inválido." to "Недопустимый файл изображения.", "A preparar áudio…" to "Подготовка аудио…", "Não foi possível reproduzir o áudio." to "Не удалось воспроизвести аудио.", "Reprodução concluída." to "Воспроизведение завершено.", "Reproduzir" to "Воспроизвести", "Pausar" to "Пауза", "Parar" to "Стоп", "Pronto" to "Готово", "Anterior" to "Назад", "Seguinte" to "Далее", "Extrair pasta atual" to "Извлечь текущую папку", "Extrair item" to "Извлечь элемент", "para a pasta atual" to "в текущую папку", "Extrair" to "Извлечь")
            "bn" -> mapOf("Leitor de PDF" to "PDF রিডার", "Leitor de áudio" to "অডিও প্লেয়ার", "A abrir PDF…" to "PDF খোলা হচ্ছে…", "A carregar imagem…" to "ছবি লোড হচ্ছে…", "Pré-visualização da imagem" to "ছবির পূর্বরূপ", "Não foi possível abrir PDF." to "PDF খোলা যায়নি।", "Não foi possível abrir a imagem." to "ছবি খোলা যায়নি।", "Ficheiro de imagem inválido." to "অবৈধ ছবির ফাইল।", "A preparar áudio…" to "অডিও প্রস্তুত হচ্ছে…", "Não foi possível reproduzir o áudio." to "অডিও চালানো যায়নি।", "Reprodução concluída." to "প্লেব্যাক শেষ।", "Reproduzir" to "চালান", "Pausar" to "বিরতি", "Parar" to "থামান", "Pronto" to "প্রস্তুত", "Anterior" to "আগের", "Seguinte" to "পরের", "Extrair pasta atual" to "বর্তমান ফোল্ডার এক্সট্র্যাক্ট করুন", "Extrair item" to "আইটেম এক্সট্র্যাক্ট করুন", "para a pasta atual" to "বর্তমান ফোল্ডারে", "Extrair" to "এক্সট্র্যাক্ট করুন")
            "id" -> mapOf("Leitor de PDF" to "Pembaca PDF", "Leitor de áudio" to "Pemutar audio", "A abrir PDF…" to "Membuka PDF…", "A carregar imagem…" to "Memuat gambar…", "Pré-visualização da imagem" to "Pratinjau gambar", "Não foi possível abrir PDF." to "PDF tidak dapat dibuka.", "Não foi possível abrir a imagem." to "Gambar tidak dapat dibuka.", "Ficheiro de imagem inválido." to "Berkas gambar tidak valid.", "A preparar áudio…" to "Menyiapkan audio…", "Não foi possível reproduzir o áudio." to "Audio tidak dapat diputar.", "Reprodução concluída." to "Pemutaran selesai.", "Reproduzir" to "Putar", "Pausar" to "Jeda", "Parar" to "Berhenti", "Pronto" to "Siap", "Anterior" to "Sebelumnya", "Seguinte" to "Berikutnya", "Extrair pasta atual" to "Ekstrak folder saat ini", "Extrair item" to "Ekstrak item", "para a pasta atual" to "ke folder saat ini", "Extrair" to "Ekstrak")
            else -> emptyMap()
        }
        // Long-tail labels use English until their dedicated translation is added.
        return localized[text] ?: localizedExtras[text] ?: readerTranslations[text] ?: english[text] ?: text
    }

    private fun accentColor(): Int = when (accentName) {
        "Azul" -> 0xFF64B5F6.toInt()
        "Verde" -> 0xFF81C784.toInt()
        "Laranja" -> 0xFFFFB74D.toInt()
        "Rosa" -> 0xFFF48FB1.toInt()
        "Vermelho" -> 0xFFE57373.toInt()
        "Amarelo" -> 0xFFFFD54F.toInt()
        else -> 0xFFB8B5FF.toInt()
    }

    private fun pageBackgroundColor(): Int = when (uiTheme) {
        "Claro" -> 0xFFF5F6FA.toInt()
        "Preto OLED" -> 0xFF000000.toInt()
        else -> 0xFF19191D.toInt()
    }

    private fun panelBackgroundColor(): Int = when (uiTheme) {
        "Claro" -> 0xFFE7E8EF.toInt()
        "Preto OLED" -> 0xFF08080A.toInt()
        else -> 0xFF202025.toInt()
    }

    private fun toolbarBackgroundColor(): Int = when (uiTheme) {
        "Claro" -> 0xFFE0E3F0.toInt()
        "Preto OLED" -> 0xFF050507.toInt()
        else -> 0xFF292D40.toInt()
    }

    private fun rowSurfaceColor(): Int = when (uiTheme) {
        "Claro" -> 0xFFECEEF4.toInt()
        "Preto OLED" -> 0xFF08080A.toInt()
        else -> 0xFF24242A.toInt()
    }

    private fun selectedRowColor(): Int = when (uiTheme) {
        "Claro" -> 0xFFD9DCF1.toInt()
        "Preto OLED" -> 0xFF242033.toInt()
        else -> 0xFF353348.toInt()
    }

    private fun primaryTextColor(): Int = if (uiTheme == "Claro") 0xFF22232A.toInt() else 0xFFF3F3F5.toInt()
    private fun mutedTextColor(): Int = if (uiTheme == "Claro") 0xFF676A75.toInt() else 0xFFAAAAB4.toInt()

    private fun resolveTextColor(requested: Int): Int = when (requested) {
        0xFFFFFFFF.toInt(), 0xFFF4F4F6.toInt(), 0xFFF3F3F5.toInt(), 0xFFF1F1F4.toInt(),
        0xFFF0F0F3.toInt(), 0xFFE3E3E8.toInt(), 0xFFE2E2E8.toInt() -> primaryTextColor()
        0xFFAAAAB4.toInt(), 0xFFACACB6.toInt(), 0xFF9999A4.toInt(), 0xFF9D9DA8.toInt(),
        0xFFB9B9C3.toInt(), 0xFFABABB5.toInt() -> mutedTextColor()
        0xFFB8B5FF.toInt(), 0xFFD8D3FF.toInt(), 0xFFD0CBFF.toInt() -> accentColor()
        else -> requested
    }

    private fun applyPaletteToWindow() {
        window.statusBarColor = toolbarBackgroundColor()
        window.navigationBarColor = if (uiTheme == "Preto OLED") 0xFF000000.toInt() else 0xFF101014.toInt()
        window.decorView.systemUiVisibility = if (uiTheme == "Claro") {
            View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        } else 0
    }

    private fun calculateFolderSize(folder: File) {
        if (!folder.isDirectory) { toast("Seleciona uma pasta."); return }
        runTask("A calcular tamanho da pasta…", 0L, 1L) { meter ->
            val totals = measureFiles(listOf(folder)) { file -> meter.show(file) }
            meter.setTotals(totals.bytes, totals.entries)
            runOnUiThread {
                AlertDialog.Builder(this)
                    .setTitle(folder.name)
                    .setMessage(
                        tr("Tamanho total") + ": " + formatSize(totals.bytes) + "\n" +
                            tr("Itens") + ": " + totals.entries
                    )
                    .setPositiveButton(tr("Fechar"), null)
                    .show()
            }
            tr("Tamanho calculado") + ": " + formatSize(totals.bytes) + " • " + totals.entries + " " + tr("itens")
        }
    }

    private fun copyPath(file: File) {
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Archiver path", file.absolutePath))
        toast("Caminho copiado.")
    }

    private fun calculateSha256(file: File) {
        if (!file.isFile) { toast("Seleciona um ficheiro para calcular o SHA-256."); return }
        runTask("A calcular SHA-256…", file.length(), 1L) { meter ->
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { stream ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = stream.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                    meter.bytesRead(count.toLong(), file)
                }
            }
            val hash = digest.digest().joinToString("") { "%02x".format(it) }
            runOnUiThread {
                AlertDialog.Builder(this)
                    .setTitle("SHA-256")
                    .setMessage(file.name + "\n\n" + hash)
                    .setPositiveButton(tr("Copiar")) { _, _ ->
                        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("SHA-256", hash))
                        toast("SHA-256 copiado.")
                    }
                    .setNegativeButton(tr("Fechar"), null)
                    .show()
            }
            "SHA-256: " + hash
        }
    }

    private fun shareApplication() {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Archiver — gestor de ficheiros Android")
            putExtra(
                Intent.EXTRA_TEXT,
                tr("Experimenta o Archiver, um gestor de ficheiros Android independente:") +
                    "\nhttps://github.com/nexauren1/Archiver-File-Manager"
            )
        }
        startActivity(Intent.createChooser(intent, tr("Partilhar o Archiver")))
    }

    private fun maybeAskToShareApp() {
        if (isFinishing || !browserRendered || prefs.getBoolean("share_prompt_dismissed", false)) return
        prefs.edit().putBoolean("share_prompt_dismissed", true).apply()
        AlertDialog.Builder(this)
            .setTitle(tr("Gostas do Archiver?"))
            .setMessage(tr("Estamos a melhorar a aplicação. Partilhar o projeto ajuda mais pessoas a conhecê-lo."))
            .setPositiveButton(tr("Partilhar")) { _, _ -> shareApplication() }
            .setNegativeButton(tr("Agora não"), null)
            .show()
    }

    private fun languageDisplayName(code: String): String = when (code) {
        "en" -> "English"
        "es" -> "Español"
        "fr" -> "Français"
        "de" -> "Deutsch"
        "zh" -> "中文（简体）"
        "hi" -> "हिन्दी"
        "ar" -> "العربية"
        "ja" -> "日本語"
        "ru" -> "Русский"
        "bn" -> "বাংলা"
        "id" -> "Bahasa Indonesia"
        else -> "Português"
    }

    private fun showLanguageMenu() {
        val codes = arrayOf("pt", "en", "es", "fr", "de", "zh", "hi", "ar", "ja", "ru", "bn", "id")
        val options = arrayOf(
            "Português", "English", "Español", "Français", "Deutsch", "中文（简体）",
            "हिन्दी", "العربية", "日本語", "Русский", "বাংলা", "Bahasa Indonesia"
        )
        AlertDialog.Builder(this).setTitle(tr("Idioma"))
            .setMessage(tr("Escolhe um idioma. Os nomes são apresentados na própria língua."))
            .setSingleChoiceItems(options, codes.indexOf(appLanguage).coerceAtLeast(0)) { dialog, which ->
                appLanguage = codes[which]
                savePreferences()
                if (Build.VERSION.SDK_INT >= 33) {
                    runCatching {
                        val localeTag = if (codes[which] == "zh") "zh-Hans" else codes[which]
                        getSystemService(android.app.LocaleManager::class.java).applicationLocales =
                            android.os.LocaleList.forLanguageTags(localeTag)
                    }
                }
                dialog.dismiss()
                if (settingsOpen) renderSettingsScreen() else renderBrowser()
            }.setNegativeButton(tr("Cancelar"), null).show()
    }

    private fun showThemeMenu() {
        val options = arrayOf("Escuro", "Preto OLED", "Claro")
        val descriptions = arrayOf(
            tr("Escuro (recomendado)"),
            tr("Preto OLED (ecrãs OLED)"),
            tr("Claro (boa visibilidade à luz do dia)")
        )
        val previewBackgrounds = intArrayOf(0xFF19191D.toInt(), 0xFF000000.toInt(), 0xFFF5F6FA.toInt())
        val previewPanels = intArrayOf(0xFF292D40.toInt(), 0xFF08080A.toInt(), 0xFFE0E3F0.toInt())
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(6), dp(16), dp(6))
        }
        var themeDialog: AlertDialog? = null
        options.forEachIndexed { index, option ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10), dp(10), dp(8), dp(10))
                background = roundedBackground(
                    if (uiTheme == option) selectedRowColor() else rowSurfaceColor(), 12
                )
                isClickable = true
                isFocusable = true
            }
            val sample = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(4), dp(4), dp(4), dp(4))
                background = roundedBackground(previewBackgrounds[index], 7)
            }
            sample.addView(View(this).apply {
                setBackgroundColor(previewPanels[index])
            }, LinearLayout.LayoutParams(dp(42), dp(5)).apply { bottomMargin = dp(4) })
            sample.addView(View(this).apply {
                setBackgroundColor(if (option == "Claro") 0xFFB5B8C6.toInt() else 0xFF454551.toInt())
            }, LinearLayout.LayoutParams(dp(29), dp(4)))
            row.addView(sample, LinearLayout.LayoutParams(dp(54), dp(42)).apply { rightMargin = dp(12) })
            val copy = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            copy.addView(label(tr(option), 15, primaryTextColor(), true))
            copy.addView(label(descriptions[index], 12, mutedTextColor(), false).apply {
                setPadding(0, dp(3), 0, 0)
            })
            row.addView(copy, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(label(if (uiTheme == option) "✓" else "›", 20, accentColor(), true),
                LinearLayout.LayoutParams(dp(24), dp(34)))
            row.setOnClickListener {
                uiTheme = option
                savePreferences()
                applyPaletteToWindow()
                themeDialog?.dismiss()
                if (settingsOpen) renderSettingsScreen() else renderBrowser()
            }
            content.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(7) })
        }
        themeDialog = AlertDialog.Builder(this)
            .setTitle(tr("Tema da interface"))
            .setMessage(tr("O tema altera o fundo e o contraste. A cor de destaque controla os botões e a seleção."))
            .setView(content)
            .setNegativeButton(tr("Fechar"), null)
            .create()
        themeDialog?.show()
    }

    private fun showAccentMenu() {
        val options = arrayOf("Lavanda", "Azul", "Verde", "Laranja", "Rosa", "Vermelho", "Amarelo")
        val colorValues = intArrayOf(
            0xFFB8B5FF.toInt(), 0xFF64B5F6.toInt(), 0xFF81C784.toInt(),
            0xFFFFB74D.toInt(), 0xFFF48FB1.toInt(), 0xFFE57373.toInt(), 0xFFFFD54F.toInt()
        )
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(6), dp(16), dp(6))
        }
        var accentDialog: AlertDialog? = null
        options.forEachIndexed { index, option ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10), dp(9), dp(8), dp(9))
                background = roundedBackground(
                    if (accentName == option) selectedRowColor() else rowSurfaceColor(), 10
                )
                isClickable = true
                isFocusable = true
            }
            val chip = View(this).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(colorValues[index])
                    setStroke(dp(1), if (uiTheme == "Claro") 0x55000000 else 0x55FFFFFF)
                }
            }
            row.addView(chip, LinearLayout.LayoutParams(dp(28), dp(28)).apply { rightMargin = dp(12) })
            row.addView(label(tr(option), 15, primaryTextColor(), accentName == option),
                LinearLayout.LayoutParams(0, -2, 1f))
            if (accentName == option) row.addView(label("✓", 18, colorValues[index], true))
            row.setOnClickListener {
                accentName = option
                savePreferences()
                accentDialog?.dismiss()
                if (settingsOpen) renderSettingsScreen() else renderBrowser()
            }
            content.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(5) })
        }
        accentDialog = AlertDialog.Builder(this)
            .setTitle(tr("Cor de destaque"))
            .setMessage(tr("Cor dos botões, seleção e indicadores"))
            .setView(content)
            .setNegativeButton(tr("Fechar"), null)
            .create()
        accentDialog?.show()
    }

    private fun showFolderColorMenu(file: File) {
        val options = arrayOf("Predefinida", "Lavanda", "Azul", "Verde", "Laranja", "Rosa", "Vermelho", "Amarelo")
        AlertDialog.Builder(this).setTitle(tr("Cor da pasta"))
            .setItems(options.map { tr(it) }.toTypedArray()) { _, which ->
                val key = pathKey(file)
                if (which == 0) folderColors.remove(key) else folderColors[key] = options[which]
                savePreferences()
                refreshFiles()
            }.setNegativeButton(tr("Cancelar"), null).show()
    }

    private fun folderColorValue(file: File): Int? = folderColors[pathKey(file)]?.let { color ->
        when (color) {
            "Lavanda" -> 0xFFB8B5FF.toInt()
            "Azul" -> 0xFF64B5F6.toInt()
            "Verde" -> 0xFF81C784.toInt()
            "Laranja" -> 0xFFFFB74D.toInt()
            "Rosa" -> 0xFFF48FB1.toInt()
            "Vermelho" -> 0xFFE57373.toInt()
            "Amarelo" -> 0xFFFFD54F.toInt()
            else -> null
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    private fun extension(name: String): String = name.substringAfterLast('.', "").lowercase(Locale.ROOT)

    private fun archiveKind(file: File): String {
        val name = file.name.lowercase(Locale.ROOT)
        return when {
            name.endsWith(".tar.gz") || name.endsWith(".tgz") -> "tgz"
            name.endsWith(".tar.bz2") || name.endsWith(".tbz2") || name.endsWith(".tbz") -> "tbz2"
            name.endsWith(".tar") -> "tar"
            name.endsWith(".zip") -> "zip"
            name.endsWith(".7z") -> "7z"
            else -> ""
        }
    }

    private fun archiveSuffix(kind: String): String = when (kind) {
        "7z" -> ".7z"
        "tar" -> ".tar"
        "tgz" -> ".tar.gz"
        "tbz2" -> ".tar.bz2"
        else -> ".zip"
    }

    private fun stripArchiveSuffix(name: String): String {
        val lower = name.lowercase(Locale.ROOT)
        val suffix = listOf(".tar.bz2", ".tar.gz", ".tbz2", ".tgz", ".tar", ".zip", ".7z")
            .firstOrNull { lower.endsWith(it) }
        return if (suffix != null) name.dropLast(suffix.length) else name.substringBeforeLast('.', name)
    }

    private fun formatSize(bytes: Long): String = when {
        bytes < 1024 -> bytes.toString() + " B"
        bytes < 1024L * 1024L -> String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0)
        bytes < 1024L * 1024L * 1024L -> String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024))
        else -> String.format(Locale.ROOT, "%.2f GB", bytes / (1024.0 * 1024 * 1024))
    }

    private fun onOff(value: Boolean): String = if (value) "Ligado" else "Desligado"

    private fun toast(message: String) {
        runOnUiThread { Toast.makeText(this, tr(message), Toast.LENGTH_LONG).show() }
    }

    override fun onBackPressed() {
        if (workActive.get()) {
            toast("A operação está em curso. Acompanha o progresso na janela ou na notificação.")
            return
        }
        if (editorOpen) {
            requestLeaveTextEditor()
            return
        }
        if (settingsOpen) {
            if (settingsCategory != null) {
                settingsCategory = null
                renderSettingsScreen()
            } else {
                settingsOpen = false
                renderBrowser()
            }
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
