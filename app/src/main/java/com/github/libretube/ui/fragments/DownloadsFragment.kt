package com.github.libretube.ui.fragments

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.os.bundleOf
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.github.libretube.R
import com.github.libretube.constants.IntentData
import com.github.libretube.constants.PreferenceKeys
import com.github.libretube.databinding.FragmentDownloadContentBinding
import com.github.libretube.databinding.FragmentDownloadsBinding
import com.github.libretube.db.DatabaseHolder.Database
import com.github.libretube.db.obj.DownloadWithItems
import com.github.libretube.db.obj.filterByTab
import com.github.libretube.extensions.ceilHalf
import com.github.libretube.extensions.formatAsFileSize
import com.github.libretube.extensions.serializable
import com.github.libretube.helpers.DownloadHelper
import com.github.libretube.helpers.PreferenceHelper
import com.github.libretube.obj.DownloadStatus
import com.github.libretube.receivers.DownloadReceiver
import com.github.libretube.services.DownloadService
import com.github.libretube.ui.adapters.DownloadsAdapter
import com.github.libretube.ui.base.DynamicLayoutManagerFragment
import com.github.libretube.ui.sheets.BaseBottomSheet
import com.github.libretube.ui.viewholders.DownloadsViewHolder
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.tabs.TabLayoutMediator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.io.path.fileSize

enum class DownloadTab {
    VIDEO,
    AUDIO
}

class DownloadsFragment : Fragment() {
    private var _binding: FragmentDownloadsBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDownloadsBinding.inflate(inflater)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.downloadsPager.adapter = DownloadsFragmentAdapter(this)

        TabLayoutMediator(binding.tabLayout, binding.downloadsPager) { tab, position ->
            tab.text = when (position) {
                DownloadTab.VIDEO.ordinal -> getString(R.string.video)
                DownloadTab.AUDIO.ordinal -> getString(R.string.audio)
                else -> throw IllegalArgumentException()
            }
        }.attach()
    }

    fun bindDownloadService() {
        childFragmentManager.fragments.filterIsInstance<DownloadsFragmentPage>().forEach {
            it.bindDownloadService()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

class DownloadsFragmentAdapter(fragment: Fragment) : FragmentStateAdapter(fragment) {
    override fun getItemCount() = DownloadTab.entries.size

    override fun createFragment(position: Int): Fragment {
        return DownloadsFragmentPage().apply {
            arguments = bundleOf(IntentData.currentPosition to DownloadTab.entries[position])
        }
    }
}

class DownloadsFragmentPage : DynamicLayoutManagerFragment() {
    private lateinit var adapter: DownloadsAdapter
    private var _binding: FragmentDownloadContentBinding? = null
    private val binding get() = _binding!!

    private var binder: DownloadService.LocalBinder? = null
    private val downloads = mutableListOf<DownloadWithItems>()
    private val downloadReceiver = DownloadReceiver()
    private lateinit var downloadTab: DownloadTab

    private val serviceConnection = object : ServiceConnection {
        var isBound = false
        var job: Job? = null

        override fun onServiceConnected(name: ComponentName?, iBinder: IBinder?) {
            binder = iBinder as DownloadService.LocalBinder
            isBound = true
            job?.cancel()
            job = lifecycleScope.launch {
                binder?.getService()?.downloadFlow?.collectLatest {
                    updateProgress(it.first, it.second)
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            binder = null
            isBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        this.downloadTab = requireArguments().serializable(IntentData.currentPosition)!!
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDownloadContentBinding.inflate(layoutInflater)
        return binding.root
    }

    override fun setLayoutManagers(gridItems: Int) {
        _binding?.downloadsRecView?.layoutManager = GridLayoutManager(context, gridItems.ceilHalf())
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        var selectedSortType =
            PreferenceHelper.getInt(PreferenceKeys.SELECTED_DOWNLOAD_SORT_TYPE, 0)
        val filterOptions = resources.getStringArray(R.array.downloadSortOptions)
        binding.sortType.text = filterOptions[selectedSortType]
        binding.sortType.setOnClickListener {
            BaseBottomSheet().setSimpleItems(filterOptions.toList()) { index ->
                binding.sortType.text = filterOptions[index]
                if (::adapter.isInitialized) {
                    sortDownloadList(index, selectedSortType)
                    adapter.notifyDataSetChanged()
                }
                selectedSortType = index
                PreferenceHelper.putInt(
                    PreferenceKeys.SELECTED_DOWNLOAD_SORT_TYPE,
                    index
                )
            }.show(childFragmentManager)
        }

        lifecycleScope.launch {
            val dbDownloads = withContext(Dispatchers.IO) {
                Database.downloadDao().getAll()
            }

            downloads.clear()
            downloads.addAll(dbDownloads.filterByTab(downloadTab))

            if (downloads.isEmpty()) return@launch

            sortDownloadList(selectedSortType)

            adapter = DownloadsAdapter(requireContext(), downloadTab, downloads) {
                var isDownloading = false
                val ids = it.downloadItems
                    .filter { item -> item.path.fileSize() < item.downloadSize }
                    .map { item -> item.id }

                if (!serviceConnection.isBound) {
                    DownloadHelper.startDownloadService(requireContext())
                    bindDownloadService(ids.toIntArray())
                    return@DownloadsAdapter true
                }

                binder?.getService()?.let { service ->
                    isDownloading = ids.any { id -> service.isDownloading(id) }

                    ids.forEach { id ->
                        if (isDownloading) {
                            service.pause(id)
                        } else {
                            service.resume(id)
                        }
                    }
                }
                return@DownloadsAdapter isDownloading.not()
            }
            binding.downloadsRecView.adapter = adapter

            val itemTouchCallback =
                object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT) {
                    override fun getMovementFlags(
                        recyclerView: RecyclerView,
                        viewHolder: RecyclerView.ViewHolder
                    ): Int = makeMovementFlags(0, ItemTouchHelper.LEFT)

                    override fun onMove(
                        recyclerView: RecyclerView,
                        viewHolder: RecyclerView.ViewHolder,
                        target: RecyclerView.ViewHolder
                    ): Boolean = false

                    override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                        adapter.showDeleteDialog(
                            requireContext(),
                            viewHolder.absoluteAdapterPosition
                        )
                        // put the item back to the center, as it's currently out of the screen
                        adapter.restoreItem(viewHolder.absoluteAdapterPosition)
                    }
                }
            ItemTouchHelper(itemTouchCallback).attachToRecyclerView(binding.downloadsRecView)

            binding.downloadsRecView.adapter?.registerAdapterDataObserver(
                object : RecyclerView.AdapterDataObserver() {
                    override fun onItemRangeRemoved(positionStart: Int, itemCount: Int) {
                        super.onItemRangeRemoved(positionStart, itemCount)
                        toggleVisibilities()
                    }
                }
            )

            toggleVisibilities()
        }

        binding.deleteAll.setOnClickListener {
            showDeleteAllDialog(binding.root.context, adapter)
        }
    }

    private fun toggleVisibilities() {
        val binding = _binding ?: return

        val isEmpty = downloads.isEmpty()
        binding.downloadsEmpty.isVisible = isEmpty
        binding.downloadsContainer.isGone = isEmpty
        binding.deleteAll.isGone = isEmpty
    }

    private fun sortDownloadList(sortType: Int, previousSortType: Int? = null) {
        if (previousSortType == null && sortType == 1) {
            downloads.reverse()
        }
        if (previousSortType != null && sortType != previousSortType) {
            downloads.reverse()
        }
    }

    private fun showDeleteAllDialog(context: Context, adapter: DownloadsAdapter) {
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.delete_all)
            .setMessage(R.string.irreversible)
            .setPositiveButton(R.string.okay) { _, _ ->
                for (downloadIndex in downloads.size - 1 downTo 0) {
                    adapter.deleteDownload(downloadIndex)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    override fun onStart() {
        if (DownloadService.IS_DOWNLOAD_RUNNING) {
            val intent = Intent(requireContext(), DownloadService::class.java)
            context?.bindService(intent, serviceConnection, 0)
        }
        super.onStart()
    }

    override fun onResume() {
        super.onResume()

        val filter = IntentFilter().apply {
            addAction(DownloadService.ACTION_SERVICE_STARTED)
            addAction(DownloadService.ACTION_SERVICE_STOPPED)
        }
        ContextCompat.registerReceiver(
            requireContext(),
            downloadReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    fun bindDownloadService(ids: IntArray? = null) {
        if (serviceConnection.isBound) return

        val intent = Intent(context, DownloadService::class.java)
        intent.putExtra("ids", ids)
        context?.bindService(intent, serviceConnection, 0)
    }

    fun updateProgress(id: Int, status: DownloadStatus) {
        val index = downloads.indexOfFirst {
            it.downloadItems.any { item -> item.id == id }
        }
        val view =
            _binding?.downloadsRecView?.findViewHolderForAdapterPosition(index) as? DownloadsViewHolder

        view?.binding?.apply {
            when (status) {
                DownloadStatus.Paused -> {
                    resumePauseBtn.setImageResource(R.drawable.ic_download)
                }

                DownloadStatus.Completed -> {
                    downloadOverlay.isGone = true
                }

                DownloadStatus.Stopped -> Unit

                is DownloadStatus.Progress -> {
                    downloadOverlay.isVisible = true
                    resumePauseBtn.setImageResource(R.drawable.ic_pause)
                    if (progressBar.isIndeterminate) return
                    progressBar.incrementProgressBy(status.progress.toInt())
                    val progressInfo = progressBar.progress.formatAsFileSize() +
                            " /\n" + progressBar.max.formatAsFileSize()
                    fileSize.text = progressInfo
                }

                is DownloadStatus.Error -> {
                    resumePauseBtn.setImageResource(R.drawable.ic_restart)
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        context?.unregisterReceiver(downloadReceiver)
    }

    override fun onStop() {
        super.onStop()
        runCatching {
            context?.unbindService(serviceConnection)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}