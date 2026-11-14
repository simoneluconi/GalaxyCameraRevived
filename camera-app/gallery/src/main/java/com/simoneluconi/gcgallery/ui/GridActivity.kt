// SPDX-License-Identifier: GPL-3.0-or-later
package com.simoneluconi.gcgallery.ui

import android.Manifest
import android.app.Activity
import android.app.ActivityOptions
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.Settings
import android.view.KeyEvent
import android.view.View
import android.view.WindowInsets
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.simoneluconi.gcgallery.GalleryApp
import com.simoneluconi.gcgallery.R
import com.simoneluconi.gcgallery.data.MediaIndex
import com.simoneluconi.gcgallery.data.Photo
import com.simoneluconi.gcgallery.edit.Edits
import com.simoneluconi.gcgallery.image.Orientation
import com.simoneluconi.gcgallery.ui.widget.ThumbGridView
import java.util.concurrent.Executors

/** Albums as chips and the day-sectioned grid; selection actions replace the bar while selecting. */
class GridActivity : Activity(), ThumbGridView.Callback {

    private lateinit var app: GalleryApp
    private lateinit var grid: ThumbGridView
    private lateinit var topBar: View
    private lateinit var selectionBar: ViewGroup
    private lateinit var selBottom: ViewGroup
    private lateinit var selActions: View
    private lateinit var albums: LinearLayout
    private lateinit var emptyView: View
    private lateinit var emptyText: TextView
    private lateinit var emptyAction: TextView
    private lateinit var emptyTrash: View
    private lateinit var selCount: TextView
    private lateinit var message: MessageBar
    private lateinit var access: MediaAccess

    private lateinit var storage: View
    private lateinit var storageDivider: View
    private lateinit var storageSd: TextView
    private lateinit var storagePhone: TextView

    private var scope = MediaIndex.Scope.SD
    /** The user picked the phone while a card is in: do not jump back to the card. */
    private var phoneChosen = false
    private var albumKey = MediaIndex.CAMERA
    private var restoreIndex = -1
    private val main = Handler(Looper.getMainLooper())
    private val edits = Executors.newSingleThreadExecutor { r -> Thread(r, "grid-edit") }
    private var diskTrimmed = false
    /** Between onStart and onStop. */
    private var visible = false

    private val onIndex: () -> Unit = { refresh(keepPosition = true) }
    private val onThumb: (Long) -> Unit = { grid.onThumbArrived(it) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        app = GalleryApp.of(this)
        setContentView(R.layout.activity_grid)
        grid = findViewById(R.id.grid)
        topBar = findViewById(R.id.top_bar)
        selectionBar = findViewById(R.id.selection_bar)
        selBottom = findViewById(R.id.sel_bottom)
        selActions = findViewById(R.id.sel_actions)
        albums = findViewById(R.id.albums)
        emptyView = findViewById(R.id.empty)
        emptyText = findViewById(R.id.empty_text)
        emptyAction = findViewById(R.id.empty_action)
        emptyTrash = findViewById(R.id.empty_trash)
        selCount = findViewById(R.id.sel_count)
        message = MessageBar(findViewById(R.id.message), findViewById(R.id.message_text), findViewById(R.id.message_action))
        access = MediaAccess(this)

        storage = findViewById(R.id.storage)
        storageDivider = findViewById(R.id.storage_divider)
        storageSd = findViewById(R.id.storage_sd)
        storagePhone = findViewById(R.id.storage_phone)
        storageSd.icon(R.drawable.ic_sd_card)
        storagePhone.icon(R.drawable.ic_memory)
        storageSd.setOnClickListener { selectScope(MediaIndex.Scope.SD, byUser = true) }
        storagePhone.setOnClickListener { selectScope(MediaIndex.Scope.PHONE, byUser = true) }

        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        // A fresh start always opens the card's camera folder; a recreate keeps what was shown
        scope = savedInstanceState?.getString(STATE_SCOPE)?.let { MediaIndex.Scope.valueOf(it) }
            ?: if (MediaIndex.sdMounted(this)) MediaIndex.Scope.SD else MediaIndex.Scope.PHONE
        phoneChosen = savedInstanceState?.getBoolean(STATE_PHONE_CHOSEN) ?: false
        albumKey = savedInstanceState?.getString(STATE_ALBUM) ?: MediaIndex.CAMERA
        restoreIndex = savedInstanceState?.getInt(STATE_POSITION, -1) ?: -1
        grid.callback = this
        grid.loader = app.thumbs
        grid.ratings = app.ratings

        window.setDecorFitsSystemWindows(false)
        findViewById<View>(R.id.root).setOnApplyWindowInsetsListener { _, insets ->
            val b = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            val m = dp(8)
            for (bar in listOf(topBar, selectionBar)) {
                (bar.layoutParams as FrameLayout.LayoutParams).setMargins(b.left + m, b.top + m, b.right + m, m)
                bar.requestLayout()
            }
            (selBottom.layoutParams as FrameLayout.LayoutParams).setMargins(b.left + m, m, b.right + m, b.bottom + m)
            selBottom.requestLayout()
            (message.root.layoutParams as FrameLayout.LayoutParams).bottomMargin = b.bottom + dp(16)
            message.root.requestLayout()
            val barH = dp(BAR_DP)
            // Held vertically the last row can scroll clear of the bottom selection bar
            val bottom = b.bottom + m + if (portrait) barH + m else 0
            grid.setPadding(b.left + m / 2, b.top + m + barH + m / 2, b.right + m / 2, bottom)
            grid.topScrim = b.top
            insets
        }
        applyOrientation()

        findViewById<View>(R.id.camera).setOnClickListener { Keys.openCamera(this) }
        findViewById<View>(R.id.transfer).setOnClickListener { startActivity(Intent(this, TransferActivity::class.java)) }
        emptyTrash.setOnClickListener { confirmEmptyTrash() }
        findViewById<View>(R.id.sel_close).setOnClickListener { grid.clearSelection() }
        findViewById<View>(R.id.sel_all).setOnClickListener { grid.selectAll() }
        findViewById<View>(R.id.sel_rate).setOnClickListener { rateSelection() }
        findViewById<View>(R.id.sel_rotate).setOnClickListener { rotateSelection() }
        findViewById<View>(R.id.sel_share).setOnClickListener { share(grid.selectedPhotos()) }
        findViewById<View>(R.id.sel_raw).setOnClickListener { trashRaw() }
        findViewById<View>(R.id.sel_trash).setOnClickListener { if (albumKey == MediaIndex.TRASH) confirmDelete() else trashSelection() }
        findViewById<View>(R.id.sel_restore).setOnClickListener { restoreSelection() }

        app.index.addListener(onIndex)
        app.thumbs.addListener(onThumb)
        if (hasPermission()) start() else showPermission()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyOrientation()
    }

    /** Level: the selection actions in the top bar. Vertical: in a bar of their own at the bottom, and fewer columns. */
    private fun applyOrientation() {
        val port = portrait
        selActions.moveTo(if (port) selBottom else selectionBar)
        placeSelectionBottom()
        if (app.index.loaded) updateStorage(MediaIndex.sdMounted(this))
        grid.setColumns(getSharedPreferences(PREFS, MODE_PRIVATE).getInt(columnsKey(), if (port) 4 else 6))
        findViewById<View>(R.id.root).requestApplyInsets()
    }

    private fun columnsKey() = if (portrait) PREF_COLUMNS_PORTRAIT else PREF_COLUMNS

    /** The bottom selection bar, and messages above it, while selecting held vertically. */
    private fun placeSelectionBottom() {
        val shown = portrait && grid.selecting
        selBottom.animate().cancel()
        selBottom.alpha = 1f
        selBottom.visibility = if (shown) View.VISIBLE else View.GONE
        message.root.translationY = if (shown) -dp(BAR_DP + 8).toFloat() else 0f
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onStart() {
        super.onStart()
        visible = true
        // Cells evicted while away (the viewer's thumbnails, a memory trim) are asked for again
        grid.refreshWanted()
        if (app.index.loaded) app.thumbs.setIdle(app.index.album(MediaIndex.ALL, scope, app.ratings))
    }

    override fun onStop() {
        visible = false
        app.thumbs.setIdle(emptyList())
        super.onStop()
    }

    override fun onDestroy() {
        app.index.removeListener(onIndex)
        app.thumbs.removeListener(onThumb)
        edits.shutdown()
        super.onDestroy()
    }

    override fun onSaveInstanceState(out: Bundle) {
        super.onSaveInstanceState(out)
        out.putString(STATE_ALBUM, albumKey)
        out.putString(STATE_SCOPE, scope.name)
        out.putBoolean(STATE_PHONE_CHOSEN, phoneChosen)
        out.putInt(STATE_POSITION, grid.firstVisibleIndex())
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (grid.selecting) grid.clearSelection() else @Suppress("DEPRECATION") super.onBackPressed()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (!access.onActivityResult(requestCode, resultCode)) @Suppress("DEPRECATION") super.onActivityResult(requestCode, resultCode, data)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (hasPermission()) start() else showPermission()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when {
            Keys.isShutter(keyCode) -> {
                if (event.repeatCount == 0) Keys.openCamera(this)
                return true
            }
            // Lever: tele shows bigger cells, wide shows more of them
            keyCode == KeyEvent.KEYCODE_ZOOM_IN -> {
                if (event.repeatCount == 0) grid.setColumns(grid.columns - 1)
                return true
            }
            keyCode == KeyEvent.KEYCODE_ZOOM_OUT -> {
                if (event.repeatCount == 0) grid.setColumns(grid.columns + 1)
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (Keys.isShutter(keyCode) || keyCode == KeyEvent.KEYCODE_ZOOM_IN || keyCode == KeyEvent.KEYCODE_ZOOM_OUT) return true
        return super.onKeyUp(keyCode, event)
    }

    // --- Index

    private fun hasPermission() = checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED

    private fun start() {
        emptyView.visibility = View.GONE
        app.index.start()
        if (app.index.loaded) refresh(keepPosition = false)
    }

    private fun showPermission() {
        emptyView.visibility = View.VISIBLE
        emptyText.setText(R.string.permission_needed)
        emptyAction.visibility = View.VISIBLE
        emptyAction.setText(R.string.allow)
        emptyAction.setOnClickListener {
            requestPermissions(arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.ACCESS_MEDIA_LOCATION), 1)
        }
    }

    private fun refresh(keepPosition: Boolean) {
        val index = app.index
        if (!index.loaded) return
        // The card came out (or went in) while the gallery was open
        val sd = MediaIndex.sdMounted(this)
        if (scope == MediaIndex.Scope.SD && !sd) {
            scope = MediaIndex.Scope.PHONE
            albumKey = MediaIndex.CAMERA
            keepPositionOff()
            message.show(getString(R.string.sd_removed))
            return refresh(keepPosition = false)
        }
        if (scope == MediaIndex.Scope.PHONE && sd && !phoneChosen) {
            scope = MediaIndex.Scope.SD
            albumKey = MediaIndex.CAMERA
            return refresh(keepPosition = false)
        }
        updateStorage(sd)
        val keys = albumKeys()
        if (albumKey !in keys) albumKey = keys.first()
        buildChips(keys)
        val list = index.album(albumKey, scope, app.ratings)
        grid.setPhotos(list, keepPosition)
        if (restoreIndex >= 0) {
            grid.scrollToIndex(restoreIndex)
            restoreIndex = -1
        }
        emptyTrash.visibility = if (albumKey == MediaIndex.TRASH && list.isNotEmpty() && !grid.selecting) View.VISIBLE else View.GONE
        if (list.isEmpty()) {
            emptyView.visibility = View.VISIBLE
            emptyAction.visibility = View.GONE
            emptyText.setText(if (albumKey == MediaIndex.TRASH) R.string.trash_is_empty else R.string.no_photos)
        } else {
            emptyView.visibility = View.GONE
        }
        updateSelectionBar()
        // Make the rest of this storage's thumbnails while the user looks at the first screen;
        // not while the gallery is in the background (GC Camera may be writing to the card)
        if (visible) app.thumbs.setIdle(index.album(MediaIndex.ALL, scope, app.ratings))
        // Never after a failed query: an empty index would wipe the whole cache
        if (!diskTrimmed && index.queryOk) {
            diskTrimmed = true
            val keep = index.photos + index.trash
            val mounted = MediaStore.getExternalVolumeNames(this)
            edits.execute { app.thumbs.trimDisk(keep, mounted) }
        }
    }

    private fun keepPositionOff() {
        restoreIndex = -1
        grid.clearSelection()
    }

    private fun selectScope(s: MediaIndex.Scope, byUser: Boolean) {
        if (byUser) phoneChosen = s == MediaIndex.Scope.PHONE
        if (s == scope) {
            grid.scrollToIndex(0)
            return
        }
        scope = s
        albumKey = MediaIndex.CAMERA
        keepPositionOff()
        refresh(keepPosition = false)
    }

    /** The microSD | Phone switch, with counts; only when a card is in. */
    private fun updateStorage(sd: Boolean) {
        val v = if (sd) View.VISIBLE else View.GONE
        storage.visibility = v
        storageDivider.visibility = v
        if (!sd) return
        // Held vertically the albums need the room: icon and count only
        fun label(s: TextView, name: Int, scope: MediaIndex.Scope) {
            val count = app.index.count(scope)
            s.text = if (portrait) "$count" else "${getString(name)}  $count"
            s.contentDescription = "${getString(name)} $count"
        }
        label(storageSd, R.string.storage_sd, MediaIndex.Scope.SD)
        label(storagePhone, R.string.storage_phone, MediaIndex.Scope.PHONE)
        storageSd.isActivated = scope == MediaIndex.Scope.SD
        storagePhone.isActivated = scope == MediaIndex.Scope.PHONE
    }

    /** In the current storage: camera folder, All, Rated (when any), the other folders, Trash (when not empty). */
    private fun albumKeys(): List<String> {
        val index = app.index
        val out = ArrayList<String>()
        val folders = index.folders(scope).map { it.key }
        val camera = folders.firstOrNull { MediaIndex.isCameraFolder(it) }
        if (camera != null) out.add(camera)
        out.add(MediaIndex.ALL)
        if (index.album(MediaIndex.RATED, scope, app.ratings).isNotEmpty()) out.add(MediaIndex.RATED)
        out.addAll(folders.filter { it != camera })
        if (index.trash(scope).isNotEmpty()) out.add(MediaIndex.TRASH)
        return out
    }

    private fun buildChips(keys: List<String>) {
        val index = app.index
        if (albums.childCount != keys.size) {
            albums.removeAllViews()
            for (k in keys) {
                val chip = layoutInflater.inflate(R.layout.chip, albums, false) as TextView
                albums.addView(chip)
            }
        }
        for ((i, k) in keys.withIndex()) {
            val chip = albums.getChildAt(i) as TextView
            val (title, count) = when (k) {
                MediaIndex.ALL -> getString(R.string.album_all) to index.count(scope)
                MediaIndex.RATED -> getString(R.string.album_rated) to index.album(k, scope, app.ratings).size
                MediaIndex.TRASH -> getString(R.string.album_trash) to index.trash(scope).size
                else -> index.folders(scope).firstOrNull { it.key == k }?.let { it.title to it.count } ?: (MediaIndex.titleOf(k) to 0)
            }
            chip.text = "$title  $count"
            chip.icon(
                when {
                    k == MediaIndex.ALL -> R.drawable.ic_photo_library
                    k == MediaIndex.RATED -> R.drawable.ic_star_filled
                    k == MediaIndex.TRASH -> R.drawable.ic_delete
                    MediaIndex.isCameraFolder(k) -> R.drawable.ic_camera
                    else -> R.drawable.ic_folder
                },
            )
            chip.isActivated = k == albumKey
            chip.setOnClickListener { selectAlbum(k) }
        }
        // The active chip stays in view (Trash sits last, and "Empty trash" takes room)
        albums.getChildAt(keys.indexOf(albumKey))?.let { c ->
            c.post { c.requestRectangleOnScreen(Rect(0, 0, c.width, c.height), false) }
        }
    }

    /** An 18 dp icon before the label, tinted with the text by the style's drawableTint. */
    private fun TextView.icon(res: Int) {
        if (tag == res) return
        tag = res
        val size = (18 * resources.displayMetrics.density).toInt()
        val d = getDrawable(res)?.mutate()?.apply { setBounds(0, 0, size, size) }
        setCompoundDrawablesRelative(d, null, null, null)
    }

    private fun selectAlbum(key: String) {
        if (key == albumKey) {
            grid.scrollToIndex(0)
            return
        }
        grid.clearSelection()
        albumKey = key
        refresh(keepPosition = false)
        if (key == MediaIndex.TRASH && app.index.trash(scope).isNotEmpty()) message.show(getString(R.string.trash_hint))
    }

    // --- Grid callbacks

    override fun onOpen(index: Int, cell: Rect) {
        val p = grid.photos.getOrNull(index) ?: return
        val intent = Intent(this, ViewerActivity::class.java)
            .putExtra(ViewerActivity.EXTRA_ALBUM, albumKey)
            .putExtra(ViewerActivity.EXTRA_SCOPE, scope.name)
            .putExtra(ViewerActivity.EXTRA_ID, p.id)
        val opts = if (cell.isEmpty) null else {
            val loc = IntArray(2)
            grid.getLocationOnScreen(loc)
            ActivityOptions.makeScaleUpAnimation(grid, cell.left - loc[0], cell.top - loc[1], cell.width(), cell.height())
        }
        startActivity(intent, opts?.toBundle())
    }

    override fun onSelectionChanged() = updateSelectionBar()

    override fun onColumnsChanged(columns: Int) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putInt(columnsKey(), columns).apply()
    }

    private fun updateSelectionBar() {
        val selecting = grid.selecting
        if (selecting) selCount.text = getString(R.string.selected_count, grid.selected.size)
        if (selecting == (selectionBar.visibility == View.VISIBLE)) return
        val inTrash = albumKey == MediaIndex.TRASH
        for (id in intArrayOf(R.id.sel_rate, R.id.sel_rotate, R.id.sel_share, R.id.sel_raw)) {
            findViewById<View>(id).visibility = if (inTrash) View.GONE else View.VISIBLE
        }
        findViewById<View>(R.id.sel_restore).visibility = if (inTrash) View.VISIBLE else View.GONE
        crossfade(if (selecting) selectionBar else topBar, if (selecting) topBar else selectionBar)
        if (portrait) {
            if (selecting) crossfade(selBottom, null) else crossfade(null, selBottom)
            message.root.translationY = if (selecting) -dp(BAR_DP + 8).toFloat() else 0f
        }
        emptyTrash.visibility = if (inTrash && !selecting && grid.photos.isNotEmpty()) View.VISIBLE else View.GONE
    }

    private fun crossfade(show: View?, hide: View?) {
        show?.apply {
            animate().cancel()
            alpha = 0f
            visibility = View.VISIBLE
            animate().alpha(1f).setDuration(150).start()
        }
        hide?.animate()?.alpha(0f)?.setDuration(120)?.withEndAction { hide.visibility = View.GONE }?.start()
    }

    // --- Actions

    private fun uris(photos: List<Photo>) = photos.flatMap { it.files() }.map { it.uri }

    private fun trashSelection() {
        val photos = grid.selectedPhotos()
        if (photos.isEmpty()) return
        val uris = uris(photos)
        access.trash(uris, true) { ok ->
            if (!ok) return@trash
            grid.clearSelection()
            message.show(getString(R.string.moved_to_trash, photos.size), getString(R.string.undo)) {
                access.trash(uris, false) {}
            }
            hintManageMedia()
        }
    }

    private fun trashRaw() {
        val raws = grid.selectedPhotos().mapNotNull { it.raw }
        if (raws.isEmpty()) {
            message.show(getString(R.string.no_raw_selected))
            return
        }
        val uris = raws.map { it.uri }
        access.trash(uris, true) { ok ->
            if (!ok) return@trash
            grid.clearSelection()
            message.show(getString(R.string.raw_moved_to_trash, raws.size), getString(R.string.undo)) {
                access.trash(uris, false) {}
            }
        }
    }

    private fun restoreSelection() {
        val photos = grid.selectedPhotos()
        access.trash(uris(photos), false) { ok ->
            if (!ok) return@trash
            grid.clearSelection()
            message.show(getString(R.string.restored, photos.size))
        }
    }

    private fun confirmDelete() {
        val photos = grid.selectedPhotos()
        if (photos.isEmpty()) return
        AlertDialog.Builder(this)
            .setMessage(getString(R.string.delete_forever_q, photos.size))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                access.delete(uris(photos)) { ok ->
                    if (!ok) return@delete
                    grid.clearSelection()
                    message.show(getString(R.string.deleted, photos.size))
                }
            }
            .show()
    }

    private fun confirmEmptyTrash() {
        val photos = app.index.trash(scope)
        if (photos.isEmpty()) return
        AlertDialog.Builder(this)
            .setMessage(R.string.empty_trash_q)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                access.delete(uris(photos)) { ok -> if (ok) message.show(getString(R.string.deleted, photos.size)) }
            }
            .show()
    }

    private fun rotateSelection() {
        val photos = grid.selectedPhotos().filter { it.inMediaStore }
        if (photos.isEmpty()) return
        access.write(uris(photos)) { ok ->
            if (!ok) return@write
            grid.clearSelection()
            runEdits(photos, pixels = true, R.string.edit_failed) { p -> Edits.rotate(contentResolver, p, Orientation.ROTATE_CW) >= 0 }
        }
    }

    private fun rateSelection() {
        val photos = grid.selectedPhotos().filter { it.inMediaStore }
        if (photos.isEmpty()) return
        val labels = arrayOf(getString(R.string.no_rating), "★", "★★", "★★★", "★★★★", "★★★★★")
        AlertDialog.Builder(this)
            .setTitle(R.string.rate)
            .setItems(labels) { _, rating ->
                access.write(uris(photos)) { ok ->
                    if (!ok) return@write
                    for (p in photos) app.ratings.set(p.id, rating)
                    grid.clearSelection()
                    grid.invalidate()
                    runEdits(photos, pixels = false, R.string.rating_store_only) { p -> Edits.setRating(contentResolver, p, rating) }
                }
            }
            .show()
    }

    /**
     * Applies [edit] to each photo on a background thread, with progress for long batches.
     * [pixels]: the edit changes how the photo looks (a turn), so its thumbnail is redone.
     */
    private fun runEdits(photos: List<Photo>, pixels: Boolean, failedText: Int, edit: (Photo) -> Boolean) {
        edits.execute {
            var failed = 0
            for ((i, p) in photos.withIndex()) {
                if (photos.size > 3) main.post { message.show(getString(R.string.working, i + 1, photos.size), durationMs = 10_000) }
                if (!edit(p)) failed++
                if (pixels) app.thumbs.invalidate(p)
            }
            main.post {
                if (isDestroyed) return@post
                if (failed > 0) message.show(getString(failedText)) else message.dismiss()
                grid.invalidate()
                hintManageMedia()
            }
        }
    }

    private fun share(photos: List<Photo>) {
        if (photos.isEmpty()) return
        val uris = ArrayList<Uri>(photos.map { it.uri })
        val intent = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0]).setType(photos[0].mime)
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris).setType("image/*")
        }
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(intent, getString(R.string.share)))
    }

    /** Once: without MANAGE_MEDIA every action asks the system for consent. */
    private fun hintManageMedia() {
        if (access.silent) return
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        if (prefs.getBoolean(PREF_HINTED, false)) return
        prefs.edit().putBoolean(PREF_HINTED, true).apply()
        message.show(getString(R.string.manage_media_hint), getString(R.string.settings), 8000) {
            startActivity(Intent(Settings.ACTION_REQUEST_MANAGE_MEDIA, Uri.parse("package:$packageName")))
        }
    }

    companion object {
        const val PREFS = "gallery"
        private const val PREF_COLUMNS = "columns"
        private const val PREF_COLUMNS_PORTRAIT = "columns_portrait"
        /** The floating bars' height. */
        private const val BAR_DP = 52
        private const val PREF_HINTED = "manage_media_hinted"
        private const val STATE_ALBUM = "album"
        private const val STATE_POSITION = "position"
        private const val STATE_SCOPE = "scope"
        private const val STATE_PHONE_CHOSEN = "phone_chosen"
    }
}
