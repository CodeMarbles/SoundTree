package com.treecast.app.ui.tree

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.treecast.app.databinding.FragmentTreeViewBinding
import com.treecast.app.ui.MainActivity
import com.treecast.app.ui.MainViewModel
import kotlinx.coroutines.launch

class TreeViewFragment : Fragment() {

    private var _binding: FragmentTreeViewBinding? = null
    private val binding get() = _binding!!
    private val viewModel: MainViewModel by activityViewModels()
    private lateinit var treeAdapter: TreeItemAdapter

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentTreeViewBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        treeAdapter = TreeItemAdapter(
            // Chevron click — toggle tree children collapse
            onCategoryClick = { node, isCollapsed ->
                viewModel.toggleCollapse(node.category.id, isCollapsed)
            },
            onPlayPause = { rec ->
                val nowPlaying = viewModel.nowPlaying.value
                if (nowPlaying?.recording?.id == rec.id) {
                    viewModel.togglePlayPause()
                } else {
                    viewModel.play(rec)
                    if (viewModel.autoNavigateToListen.value) {
                        (requireActivity() as? MainActivity)?.navigateTo(MainActivity.PAGE_LISTEN)
                    }
                }
            },
            onRename         = { id, title  -> viewModel.renameRecording(id, title) },
            onMove           = { id, catId  -> viewModel.moveRecording(id, catId) },
            onDelete         = { rec        -> viewModel.deleteRecording(rec) },
            onRenameCategory = { id, name   -> viewModel.renameCategory(id, name) },
            onCategoryIconClick = { cat ->
                EmojiPickerBottomSheet { emoji ->
                    viewModel.updateCategoryIcon(cat.id, emoji)
                }.show(childFragmentManager, "emoji_picker")
            },
            onDeleteCategory = { cat ->
                viewModel.deleteCategory(cat)
            }
        )

        binding.recyclerTree.apply {
            adapter = treeAdapter
            layoutManager = LinearLayoutManager(requireContext())
            setHasFixedSize(false)
        }

        binding.fabAddCategory.setOnClickListener {
            NewCategoryDialog(parentId = null) { name, icon, color ->
                viewModel.createCategory(name, null, icon, color)
            }.show(childFragmentManager, "new_cat")
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.allCategories.collect { cats -> treeAdapter.categories = cats }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.nowPlaying.collect { state ->
                treeAdapter.nowPlayingId = state?.recording?.id ?: -1L
                treeAdapter.isPlaying    = state?.isPlaying ?: false
            }
        }

        observeTree()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun observeTree() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.treeItems.collect { items ->
                treeAdapter.submitList(items)
                binding.tvEmpty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }
}