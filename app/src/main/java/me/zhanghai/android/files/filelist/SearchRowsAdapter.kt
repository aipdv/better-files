package me.zhanghai.android.files.filelist

import android.graphics.Outline
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.StateListDrawable
import android.text.SpannableString
import android.text.Spanned
import android.text.style.StyleSpan
import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import androidx.appcompat.widget.PopupMenu
import androidx.core.graphics.ColorUtils
import androidx.core.view.isVisible
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import androidx.viewbinding.ViewBinding
import coil.dispose
import coil.load
import com.google.android.material.chip.Chip
import me.zhanghai.android.files.R
import me.zhanghai.android.files.compat.foregroundCompat
import me.zhanghai.android.files.databinding.FileItemSearchBinding
import me.zhanghai.android.files.databinding.SearchChipsBinding
import me.zhanghai.android.files.databinding.SearchLessRelatedBinding
import me.zhanghai.android.files.databinding.SearchProgressBinding
import me.zhanghai.android.files.databinding.SearchSectionTitleBinding
import me.zhanghai.android.files.file.FileItem
import me.zhanghai.android.files.file.fileSize
import me.zhanghai.android.files.file.formatShort
import me.zhanghai.android.files.file.iconRes
import me.zhanghai.android.files.index.highlightRanges
import me.zhanghai.android.files.ui.AspectRatioFrameLayout
import me.zhanghai.android.files.ui.CheckableItemBackground
import me.zhanghai.android.files.util.dpToDimensionPixelSize
import me.zhanghai.android.files.util.getColorByAttr
import me.zhanghai.android.files.util.getDimensionPixelSize
import me.zhanghai.android.files.util.getDrawableByAttr
import me.zhanghai.android.files.util.isMaterial3Theme
import me.zhanghai.android.files.util.layoutInflater

/**
 * Rows of the search results screen around the upstream [FileListAdapter] (#19): progress line,
 * chips, section titles, document rows, the Less related fold and its rows.
 */
internal class SearchRowsAdapter(
    private val listener: FileListAdapter.Listener,
    private val onChipSelected: (SearchChip) -> Unit,
    private val onLessRelatedToggled: () -> Unit,
) : ListAdapter<SearchRow<SearchHit>, SearchRowsAdapter.Holder>(CALLBACK) {

    var selectedFiles: FileItemSet = fileItemSetOf()
        set(value) {
            field = value
            notifyItemRangeChanged(0, itemCount, PAYLOAD_STATE_CHANGED)
        }

    var pickOptions: PickOptions? = null
        set(value) {
            field = value
            notifyItemRangeChanged(0, itemCount, PAYLOAD_STATE_CHANGED)
        }

    override fun getItemViewType(position: Int): Int = when (getItem(position)) {
        is SearchRow.Progress -> TYPE_PROGRESS
        is SearchRow.Chips -> TYPE_CHIPS
        is SearchRow.Title -> TYPE_TITLE
        is SearchRow.Doc, is SearchRow.Weak -> TYPE_FILE
        is SearchRow.LessHeader -> TYPE_LESS
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val inflater = parent.context.layoutInflater
        return Holder(
            when (viewType) {
                TYPE_PROGRESS -> SearchProgressBinding.inflate(inflater, parent, false)
                TYPE_CHIPS -> SearchChipsBinding.inflate(inflater, parent, false)
                TYPE_TITLE -> SearchSectionTitleBinding.inflate(inflater, parent, false)
                TYPE_LESS -> SearchLessRelatedBinding.inflate(inflater, parent, false)
                else -> FileItemSearchBinding.inflate(inflater, parent, false).apply {
                    itemLayout.background = CheckableItemBackground.create(0f, 0f, root.context)
                }
            }
        )
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        onBindViewHolder(holder, position, emptyList())
    }

    override fun onBindViewHolder(holder: Holder, position: Int, payloads: List<Any>) {
        val row = getItem(position)
        when (val binding = holder.binding) {
            is SearchProgressBinding -> bindProgress(binding, row as SearchRow.Progress)
            is SearchChipsBinding -> bindChips(binding, row as SearchRow.Chips)
            is SearchSectionTitleBinding -> bindTitle(binding, row as SearchRow.Title)
            is SearchLessRelatedBinding -> bindLess(binding, row as SearchRow.LessHeader)
            is FileItemSearchBinding -> {
                val hit = (row as? SearchRow.Doc)?.item ?: (row as SearchRow.Weak).item
                binding.itemLayout.isChecked = hit.file in selectedFiles
                // A picker gets only the files it asked for: other rows are disabled, as upstream.
                val enabled = isSelectable(hit.file)
                binding.itemLayout.isEnabled = enabled
                binding.menuButton.isEnabled = enabled
                if (payloads.isEmpty()) bindFile(binding, hit)
            }
        }
    }

    private fun bindProgress(binding: SearchProgressBinding, row: SearchRow.Progress) {
        binding.progressText.text = binding.root.context.getString(
            R.string.search_reading_progress_format, row.done, row.total
        )
        binding.progressBar.max = row.total
        binding.progressBar.setProgressCompat(row.done, false)
    }

    private fun bindChips(binding: SearchChipsBinding, row: SearchRow.Chips) {
        val group = binding.chipGroup
        group.setOnCheckedStateChangeListener(null)
        if (group.childCount != row.chips.size ||
            row.chips.withIndex().any { (i, chip) -> group.getChildAt(i).tag != chip.first }) {
            group.removeAllViews()
            val context = ContextThemeWrapper(
                group.context,
                if (group.context.isMaterial3Theme) {
                    R.style.ThemeOverlay_BetterFiles_SearchChip
                } else {
                    R.style.ThemeOverlay_BetterFiles_SearchChip_Material2
                }
            )
            for ((chip, _) in row.chips) {
                group.addView(Chip(context).apply { id = ViewGroup.generateViewId(); tag = chip })
            }
        }
        for ((i, entry) in row.chips.withIndex()) {
            val (chip, count) = entry
            val view = group.getChildAt(i) as Chip
            val label = view.context.getString(chip.labelRes)
            view.text = if (count != null) {
                view.context.getString(R.string.search_chip_count_format, label, count)
            } else {
                label
            }
            if (chip == row.selected && !view.isChecked) group.check(view.id)
        }
        // A changed row comes back as a new view at scroll 0: keep the selected chip in view.
        val scrollView = binding.root
        val selectedView = group.getChildAt(row.chips.indexOfFirst { it.first == row.selected })
        if (selectedView != null) {
            scrollView.post {
                val edge = group.paddingStart
                val x = when {
                    selectedView.right + edge > scrollView.scrollX + scrollView.width ->
                        selectedView.right + edge - scrollView.width
                    selectedView.left - edge < scrollView.scrollX -> selectedView.left - edge
                    else -> return@post
                }
                scrollView.scrollTo(x, 0)
            }
        }
        group.setOnCheckedStateChangeListener { _, ids ->
            val chip = ids.firstOrNull()?.let { group.findViewById<Chip>(it)?.tag as? SearchChip }
            if (chip != null && chip != row.selected) onChipSelected(chip)
        }
    }

    private fun bindTitle(binding: SearchSectionTitleBinding, row: SearchRow.Title) {
        binding.titleText.setText(row.section.labelRes)
        binding.countText.text = row.count
        binding.seeAllButton.isVisible = row.count == null
        binding.seeAllButton.setOnClickListener { onChipSelected(row.section) }
    }

    private fun bindLess(binding: SearchLessRelatedBinding, row: SearchRow.LessHeader) {
        binding.lessText.text = binding.root.context.getString(
            R.string.search_less_related_format, row.count
        )
        binding.chevronImage.rotation = if (row.open) 180f else 0f
        binding.lessLayout.setOnClickListener { onLessRelatedToggled() }
    }

    private fun bindFile(binding: FileItemSearchBinding, hit: SearchHit) {
        val file = hit.file
        val context = binding.root.context
        binding.iconImage.isVisible = true
        binding.iconImage.setImageResource(file.mimeType.iconRes)
        binding.thumbnailImage.apply {
            dispose()
            setImageDrawable(null)
            isVisible = file.supportsThumbnail
            if (isVisible) {
                load(file.path to file.attributes) {
                    listener { _, _ -> binding.iconImage.isVisible = false }
                }
            }
        }
        binding.nameText.text = file.name
        // The snippet only when it shows a query word in bold. Else the upstream date and size line.
        binding.snippetText.text = boldTerms(hit.snippet, hit.matchedTerms) ?: listOf(
            file.attributes.lastModifiedTime().toInstant().formatShort(context),
            file.attributes.fileSize.formatHumanReadable(context),
        ).joinToString(context.getString(R.string.file_item_description_separator))
        binding.itemLayout.setOnClickListener {
            if (!isSelectable(file)) return@setOnClickListener
            if (selectedFiles.isEmpty()) listener.openFile(file) else toggleSelection(file)
        }
        binding.itemLayout.setOnLongClickListener {
            if (!isSelectable(file)) return@setOnLongClickListener true
            if (selectedFiles.isEmpty()) toggleSelection(file) else listener.openFile(file)
            true
        }
        binding.iconLayout.setOnClickListener { toggleSelection(file) }
        binding.menuButton.setOnClickListener { showMenu(binding, file) }
    }

    private fun isSelectable(file: FileItem) =
        pickAccepts(pickOptions, file.attributes.isDirectory, file.mimeType)

    private fun toggleSelection(file: FileItem) {
        if (!isSelectable(file)) return
        val pickOptions = pickOptions
        val selected = file in selectedFiles
        if (!selected && pickOptions != null && !pickOptions.allowMultiple) {
            listener.clearSelectedFiles()
        }
        listener.selectFile(file, !selected)
    }

    // ponytail: the upstream file_item menu for a local file, kept apart from FileListAdapter so
    // upstream code stays untouched. Folders and archives never come from meaning search.
    private fun showMenu(binding: FileItemSearchBinding, file: FileItem) {
        val popupMenu = PopupMenu(binding.menuButton.context, binding.menuButton)
        popupMenu.inflate(R.menu.file_item)
        val menu = popupMenu.menu
        val hasPickOptions = pickOptions != null
        val isReadOnly = file.path.fileSystem.isReadOnly
        menu.findItem(R.id.action_cut).isVisible = !hasPickOptions && !isReadOnly
        menu.findItem(R.id.action_copy).isVisible = !hasPickOptions
        menu.findItem(R.id.action_delete).isVisible = !isReadOnly
        menu.findItem(R.id.action_rename).isVisible = !isReadOnly
        menu.findItem(R.id.action_extract).isVisible = file.isArchiveFile
        menu.findItem(R.id.action_add_bookmark).isVisible = false
        popupMenu.setOnMenuItemClickListener {
            when (it.itemId) {
                R.id.action_open_with -> listener.openFileWith(file)
                R.id.action_cut -> listener.cutFile(file)
                R.id.action_copy -> listener.copyFile(file)
                R.id.action_delete -> listener.confirmDeleteFile(file)
                R.id.action_rename -> listener.showRenameFileDialog(file)
                R.id.action_extract -> listener.extractFile(file)
                R.id.action_archive -> listener.showCreateArchiveDialog(file)
                R.id.action_share -> listener.shareFile(file)
                R.id.action_copy_path -> listener.copyPath(file)
                R.id.action_create_shortcut -> listener.createShortcut(file)
                R.id.action_properties -> listener.showPropertiesDialog(file)
                else -> return@setOnMenuItemClickListener false
            }
            true
        }
        popupMenu.show()
    }

    class Holder(val binding: ViewBinding) : RecyclerView.ViewHolder(binding.root)

    companion object {
        private const val TYPE_PROGRESS = 0
        private const val TYPE_CHIPS = 1
        private const val TYPE_TITLE = 2
        private const val TYPE_FILE = 3
        private const val TYPE_LESS = 4
        private val PAYLOAD_STATE_CHANGED = Any()

        private val SearchChip.labelRes: Int
            get() = when (this) {
                SearchChip.ALL -> R.string.search_chip_all
                SearchChip.DOCUMENTS -> R.string.search_chip_documents
                SearchChip.PHOTOS -> R.string.search_chip_photos
                SearchChip.NAMES -> R.string.search_chip_names
            }

        /** [snippet] with the [terms] in bold, or null when it shows none of them. */
        private fun boldTerms(snippet: String, terms: Set<String>): CharSequence? {
            val ranges = highlightRanges(snippet, terms.toList())
            if (ranges.isEmpty()) return null
            val text = SpannableString(snippet)
            for (range in ranges) {
                text.setSpan(
                    StyleSpan(Typeface.BOLD), range.first, range.last + 1,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
            return text
        }

        private fun SearchRow<SearchHit>.fileKey() = when (this) {
            is SearchRow.Doc -> item.file.path
            is SearchRow.Weak -> item.file.path
            else -> null
        }

        private val CALLBACK = object : DiffUtil.ItemCallback<SearchRow<SearchHit>>() {
            override fun areItemsTheSame(
                oldItem: SearchRow<SearchHit>,
                newItem: SearchRow<SearchHit>
            ): Boolean = when {
                oldItem.fileKey() != null -> oldItem.fileKey() == newItem.fileKey()
                oldItem is SearchRow.Title && newItem is SearchRow.Title ->
                    oldItem.section == newItem.section
                else -> oldItem::class == newItem::class
            }

            override fun areContentsTheSame(
                oldItem: SearchRow<SearchHit>,
                newItem: SearchRow<SearchHit>
            ): Boolean = oldItem == newItem
        }
    }
}

/**
 * The search results screen (#19): a ConcatAdapter of our rows around the upstream adapters.
 * Shown only while meaning results match the current search; otherwise the RecyclerView keeps
 * the plain upstream [namesAdapter], exactly as before.
 */
internal class SearchScreen(
    private val recyclerView: RecyclerView,
    private val layoutManager: GridLayoutManager,
    private val namesAdapter: FileListAdapter,
    listener: FileListAdapter.Listener,
) {
    private val head = SearchRowsAdapter(listener, ::selectChip, ::toggleLessRelated)
    // The photos grid reuses the upstream grid item, as a thumbnail only (see init).
    val photosAdapter = FileListAdapter(listener).apply { viewType = FileViewType.GRID }
    private val mid = SearchRowsAdapter(listener, ::selectChip, ::toggleLessRelated)
    private val tail = SearchRowsAdapter(listener, ::selectChip, ::toggleLessRelated)
    private val rowAdapters = listOf(head, mid, tail)
    private val concatAdapter = ConcatAdapter(head, photosAdapter, mid, namesAdapter, tail)

    private var meaning: MeaningResults? = null
    private var names: List<FileItem> = emptyList()
    private var keep: (FileItem) -> Boolean = { true }
    private var chip = SearchChip.ALL
    private var lessOpen = false
    private var photoColumns = PHOTO_COLUMNS_ALL

    /** (done, total) while indexing reads files, else null. */
    var progress: Pair<Int, Int>? = null
        set(value) {
            if (field == value) return
            field = value
            if (isShown) render()
        }

    val isShown: Boolean
        get() = recyclerView.adapter === concatAdapter

    init {
        // ponytail: photo tiles reuse the upstream grid item as a thumbnail only: its bottom bar
        // (icon, name, menu) has no room at 3 or 4 columns. ConcatAdapter keeps these holders apart
        // from the names list, so the normal folder grid never gets this. clipToOutline = done.
        val context = recyclerView.context
        val corner = context.getDimensionPixelSize(R.dimen.search_photo_corner).toFloat()
        val primary = context.getColorByAttr(androidx.appcompat.R.attr.colorPrimary)
        recyclerView.addOnChildAttachStateChangeListener(
            object : RecyclerView.OnChildAttachStateChangeListener {
                override fun onChildViewAttachedToWindow(view: View) {
                    val holder = recyclerView.getChildViewHolder(view)
                    if (holder.bindingAdapter !== photosAdapter || view.clipToOutline) return
                    holder as FileListAdapter.ViewHolder
                    (holder.iconLayout.parent as View).isVisible = false
                    (holder.thumbnailImage.parent as AspectRatioFrameLayout).apply {
                        // marginStart/End win over left/right: clear both, else 16 dp stays.
                        (layoutParams as ViewGroup.MarginLayoutParams).apply {
                            setMargins(0, 0, 0, 0)
                            marginStart = 0
                            marginEnd = 0
                        }
                        ratio = 1f
                    }
                    holder.itemLayout.apply {
                        outlineProvider = object : ViewOutlineProvider() {
                            override fun getOutline(view: View, outline: Outline) =
                                outline.setRoundRect(0, 0, view.width, view.height, corner)
                        }
                        clipToOutline = true
                        // The check badge sat in the hidden bar: a selected tile gets a tint.
                        val checked = StateListDrawable().apply {
                            addState(
                                intArrayOf(android.R.attr.state_checked),
                                GradientDrawable().apply {
                                    // The stroke is drawn on a rect inset by half its width.
                                    val stroke = context.dpToDimensionPixelSize(3)
                                    cornerRadius = corner - stroke / 2f
                                    setColor(ColorUtils.setAlphaComponent(primary, 0x66))
                                    setStroke(stroke, primary)
                                }
                            )
                        }
                        // A full-tile ripple: the upstream M3 grid ripple is inset 4 dp.
                        val ripple = context.getDrawableByAttr(
                            androidx.appcompat.R.attr.selectableItemBackground
                        )
                        foregroundCompat = LayerDrawable(arrayOf(ripple, checked))
                    }
                }

                override fun onChildViewDetachedFromWindow(view: View) {}
            }
        )
        // Equal tiles with 16 dp outer edges and small gaps: each tile gets the same total inset.
        val edge = context.getDimensionPixelSize(R.dimen.screen_edge_margin)
        val gap = context.getDimensionPixelSize(R.dimen.search_photo_gap)
        recyclerView.addItemDecoration(object : RecyclerView.ItemDecoration() {
            override fun getItemOffsets(
                outRect: Rect,
                view: View,
                parent: RecyclerView,
                state: RecyclerView.State
            ) {
                if (parent.getChildViewHolder(view).bindingAdapter !== photosAdapter) return
                val lp = view.layoutParams as GridLayoutManager.LayoutParams
                val column = lp.spanIndex / lp.spanSize
                val inset = (2 * edge + (photoColumns - 1) * gap) / photoColumns
                val left = edge + column * (gap - inset)
                // As the mockup: 4 dp above the first row, 8 dp below the last, 4 dp between.
                val position = parent.getChildViewHolder(view).bindingAdapterPosition
                val lastRow = (photosAdapter.itemCount - 1) / photoColumns
                val top = if (position / photoColumns == 0) gap else gap / 2
                val bottom = if (position / photoColumns == lastRow) 2 * gap else gap / 2
                outRect.set(left, top, inset - left, bottom)
            }
        })
        layoutManager.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int {
                if (!isShown) return 1
                var local = position
                for (adapter in concatAdapter.adapters) {
                    if (local < adapter.itemCount) {
                        // Clamped: the span count catches up right after the adapter swap.
                        val spanCount = layoutManager.spanCount
                        return when {
                            adapter === namesAdapter -> SPAN_UNIT.coerceAtMost(spanCount)
                            adapter === photosAdapter -> (spanCount / photoColumns).coerceAtLeast(1)
                            else -> spanCount
                        }
                    }
                    local -= adapter.itemCount
                }
                return layoutManager.spanCount
            }
        }
    }

    /**
     * Shows [meaning] (already checked against the current search) with the name hits [names],
     * or the plain upstream list when [meaning] is null. Returns true when that switch happened:
     * the caller then updates the span count ([SPAN_UNIT]).
     */
    fun update(meaning: MeaningResults?, names: List<FileItem>, keep: (FileItem) -> Boolean): Boolean {
        val wasShown = isShown
        if (meaning == null) {
            this.meaning = null
            if (wasShown) recyclerView.adapter = namesAdapter
            return wasShown
        }
        val old = this.meaning
        if (old == null || old.path != meaning.path || old.query != meaning.query) {
            chip = SearchChip.ALL
            lessOpen = false
        }
        this.meaning = meaning
        this.names = names
        this.keep = keep
        if (!wasShown) recyclerView.adapter = concatAdapter
        render()
        return !wasShown
    }

    fun setPickOptions(pickOptions: PickOptions?) {
        photosAdapter.pickOptions = pickOptions
        rowAdapters.forEach { it.pickOptions = pickOptions }
    }

    fun setSelectedFiles(files: FileItemSet) {
        photosAdapter.replaceSelectedFiles(files)
        rowAdapters.forEach { it.selectedFiles = files }
    }

    private fun selectChip(chip: SearchChip) {
        this.chip = chip
        render()
        recyclerView.scrollToPosition(0)
    }

    private fun toggleLessRelated() {
        lessOpen = !lessOpen
        render()
    }

    private fun render() {
        val meaning = meaning ?: return
        val sections = meaning.sections(names, keep)
        val chips = searchChips(sections, meaning.photosAvailable)
        val page = searchPage(sections, chips, chip, lessOpen, progress)
        if (photoColumns != page.photoColumns) {
            photoColumns = page.photoColumns
            layoutManager.spanSizeLookup.invalidateSpanIndexCache()
        }
        head.submitList(page.head)
        photosAdapter.replaceListAndIsSearching(page.photos.map { it.file }, true)
        mid.submitList(page.mid)
        namesAdapter.replaceListAndIsSearching(page.names.map { it.file }, true)
        tail.submitList(page.tail)
        // The first and last photo rows get their own gaps: recompute them for the new list.
        recyclerView.invalidateItemDecorations()
    }

    companion object {
        /** Span count multiplier while shown: 4 or 3 photo columns and the names grid all fit. */
        const val SPAN_UNIT = 12
    }
}
