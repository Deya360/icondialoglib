/*
 * Copyright 2019 Nicolas Maltais
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.maltaisn.icondialog.demo

import android.content.Context
import android.graphics.PorterDuff
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Filter
import android.widget.Toast
import androidx.annotation.ArrayRes
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.widget.addTextChangedListener
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.maltaisn.icondialog.IconDialog
import com.maltaisn.icondialog.IconDialogSettings
import com.maltaisn.icondialog.data.Icon
import com.maltaisn.icondialog.data.NamedTag
import com.maltaisn.icondialog.demo.databinding.FragmentMainBinding
import com.maltaisn.icondialog.demo.databinding.ItemIconBinding
import com.maltaisn.icondialog.filter.DefaultIconFilter
import com.maltaisn.icondialog.pack.IconPack
import com.maltaisn.icondialog.pack.IconPackLoader
import com.maltaisn.iconpack.defaultpack.createDefaultIconPack
import com.maltaisn.iconpack.fa.createFontAwesomeIconPack
import com.maltaisn.iconpack.mdi.createMaterialDesignIconPack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainFragment : Fragment(), IconDialog.Callback {

    private val app: DemoApp
        get() = requireActivity().application as DemoApp

    private val coroutineScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var packLoadJob: Job? = null

    private lateinit var iconDialog: IconDialog
    private lateinit var iconsAdapter: IconsAdapter

    private lateinit var iconPackLoader: IconPackLoader

    private var selectedIconIds = emptyList<Int>()
    private var selectedIcons = emptyList<Icon>()
    private var currentPackIndex = 0

    private var _binding: FragmentMainBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View? {
        _binding = FragmentMainBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, state: Bundle?) {
        val iconFilter = DefaultIconFilter()
        iconFilter.idSearchEnabled = true

        iconDialog = childFragmentManager.findFragmentByTag(ICON_DIALOG_TAG) as IconDialog?
            ?: IconDialog.newInstance(IconDialogSettings())

        iconPackLoader = IconPackLoader(requireContext())

        val optionsLayout = binding.optionsLayout

        setupDropdown(optionsLayout.iconPackDropdown, R.array.icon_packs) {
            changeIconPack(it)
        }

        var titleVisbIndex = 2
        setupDropdown(optionsLayout.titleVisibilityDropdown, R.array.title_visibility) {
            titleVisbIndex = it
        }

        var searchVisbIndex = 2
        setupDropdown(optionsLayout.searchVisibilityDropdown, R.array.search_visibility) {
            searchVisbIndex = it
        }

        var headersVisbIndex = 2
        setupDropdown(optionsLayout.headersVisibilityDropdown, R.array.headers_visibility) {
            headersVisbIndex = it
        }

        val maxSelCheck = optionsLayout.maxSelectionChk
        val maxSelInput = optionsLayout.maxSelectionInput
        maxSelInput.setText("1")
        maxSelInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) maxSelInput.clearFocus()
            false
        }
        maxSelInput.addTextChangedListener {
            if (it.toString() == "0") {
                maxSelInput.setText("1")
            }
        }
        maxSelCheck.setOnCheckedChangeListener { _, isChecked ->
            maxSelInput.isEnabled = isChecked
        }

        val showMaxSelMessCheck = optionsLayout.showMaxSelMessageChk
        val showClearBtnCheck = optionsLayout.showClearBtnChk

        val showSelectBtnCheck = optionsLayout.showSelectBtnChk
        showSelectBtnCheck.setOnCheckedChangeListener { _, isChecked ->
            if (!isChecked) {
                maxSelCheck.isChecked = true
                maxSelInput.setText("1")
            }
            maxSelCheck.isEnabled = isChecked
            maxSelInput.isEnabled = isChecked
            showMaxSelMessCheck.isEnabled = isChecked
            showClearBtnCheck.isEnabled = isChecked
        }

        val darkThemeCheck = optionsLayout.darkThemeChk
        darkThemeCheck.setOnCheckedChangeListener { _, isChecked ->
            // Enable or disable dark theme without restarting the app.
            AppCompatDelegate.setDefaultNightMode(if (isChecked) {
                AppCompatDelegate.MODE_NIGHT_YES
            } else {
                AppCompatDelegate.MODE_NIGHT_NO
            })
        }

        val iconsRcv = binding.iconListRcv
        iconsAdapter = IconsAdapter()
        iconsRcv.adapter = iconsAdapter
        iconsRcv.layoutManager = LinearLayoutManager(context)

        binding.fab.setOnClickListener {
            // Create new settings and set them.
            iconDialog.settings = IconDialogSettings {
                this.iconFilter = iconFilter
                titleVisibility = when (titleVisbIndex) {
                    0 -> IconDialog.TitleVisibility.NEVER
                    1 -> IconDialog.TitleVisibility.ALWAYS
                    else -> IconDialog.TitleVisibility.IF_SEARCH_HIDDEN
                }
                searchVisibility = when (searchVisbIndex) {
                    0 -> IconDialog.SearchVisibility.NEVER
                    1 -> IconDialog.SearchVisibility.ALWAYS
                    else -> IconDialog.SearchVisibility.IF_LANGUAGE_AVAILABLE
                }
                headersVisibility = when (headersVisbIndex) {
                    0 -> IconDialog.HeadersVisibility.SHOW
                    1 -> IconDialog.HeadersVisibility.HIDE
                    else -> IconDialog.HeadersVisibility.STICKY
                }
                maxSelection = if (maxSelCheck.isChecked) {
                    maxSelInput.text.toString().toIntOrNull() ?: 1
                } else {
                    IconDialogSettings.NO_MAX_SELECTION
                }
                showMaxSelectionMessage = showMaxSelMessCheck.isChecked
                showSelectBtn = showSelectBtnCheck.isChecked
                showClearBtn = showClearBtnCheck.isChecked
            }

            // Set previously selected icon IDs.
            iconDialog.selectedIconIds = selectedIconIds

            // Show icon dialog with child fragment manager.
            iconDialog.show(childFragmentManager, ICON_DIALOG_TAG)
        }

        if (state != null) {
            // Restore state
            selectedIconIds = state.getIntegerArrayList("selectedIconIds")!!
            currentPackIndex = state.getInt("currentPackIndex")
        }

        // Load icon pack.
        loadIconPack()
    }

    override fun onDestroy() {
        super.onDestroy()
        coroutineScope.cancel()
    }

    private inline fun setupDropdown(
        dropdown: AutoCompleteTextView,
        @ArrayRes items: Int,
        crossinline onItemSelected: (pos: Int) -> Unit = {}
    ) {
        val context = requireContext()
        val adapter = DropdownAdapter(context, context.resources.getStringArray(items).toList())
        dropdown.setAdapter(adapter)
        dropdown.setOnItemClickListener { _, _, pos, _ ->
            dropdown.requestLayout()
            onItemSelected(pos)
        }
    }

    private fun loadIconPack() {
        if (app.iconPack != null) {
            // Icon pack is already loaded.
            updateSelectedIcons()
            return
        }

        app.iconPack = null
        selectedIcons = emptyList()

        // Start new job to load icon pack.
        packLoadJob?.cancel()
        packLoadJob = coroutineScope.launch(Dispatchers.Main) {
            app.iconPack = withContext(Dispatchers.Default) {
                // Create pack from XML
                val pack = when (currentPackIndex) {
                    0 -> createDefaultIconPack(iconPackLoader)
                    1 -> createFontAwesomeIconPack(iconPackLoader)
                    2 -> createMaterialDesignIconPack(iconPackLoader)
                    else -> error("Invalid icon pack index.")
                }

                // Load drawables
                pack.loadDrawables(iconPackLoader.drawableLoader)

                if (binding.optionsLayout.fakeLoadingChk.isChecked) {
                    delay(4000)
                }

                pack
            }

            updateSelectedIcons()

            packLoadJob = null
        }
    }

    private fun changeIconPack(index: Int) {
        if (index != currentPackIndex) {
            // Clear selected icons list
            selectedIconIds = emptyList()
            selectedIcons = emptyList()
            iconsAdapter.notifyDataSetChanged()

            // Change icon pack
            currentPackIndex = index
            app.iconPack = null
            loadIconPack()
        }
    }

    private fun updateSelectedIcons() {
        val pack = app.iconPack ?: return
        selectedIcons = selectedIconIds.map { pack.getIcon(it)!! }
        iconsAdapter.notifyDataSetChanged()
    }

    override fun onSaveInstanceState(state: Bundle) {
        super.onSaveInstanceState(state)
        state.putIntegerArrayList("selectedIconIds", ArrayList(selectedIconIds))
        state.putInt("currentPackIndex", currentPackIndex)
    }

    // Called by icon dialog to get the icon pack.
    override val iconDialogIconPack: IconPack?
        get() = app.iconPack

    override fun onIconDialogIconsSelected(dialog: IconDialog, icons: List<Icon>) {
        // Called by icon dialog when icons were selected.
        selectedIconIds = icons.map { it.id }
        selectedIcons = icons
        iconsAdapter.notifyDataSetChanged()
    }

    /**
     * Custom AutoCompleteTextView adapter to disable filtering since we want it to act like a spinner.
     */
    private class DropdownAdapter(context: Context, items: List<String> = mutableListOf()) :
        ArrayAdapter<String>(context, R.layout.item_dropdown, items) {

        override fun getFilter() = object : Filter() {
            override fun performFiltering(constraint: CharSequence?) = null
            override fun publishResults(constraint: CharSequence?, results: FilterResults?) = Unit
        }
    }

    private inner class IconsAdapter : RecyclerView.Adapter<IconsAdapter.IconViewHolder>() {

        init {
            setHasStableIds(true)
        }

        private inner class IconViewHolder(private val binding: ItemIconBinding) :
            RecyclerView.ViewHolder(binding.root) {

            fun bind(icon: Icon) {
                // Set icon drawable with correct color
                val context = requireContext()

                binding.iconImv.setImageDrawable(icon.drawable!!.mutate())
                binding.iconImv.setColorFilter(AppCompatResources.getColorStateList(context,
                    R.color.material_on_background_emphasis_medium).defaultColor, PorterDuff.Mode.SRC_IN)

                // Set information
                binding.iconIdTxv.text = getString(R.string.icon_id_fmt, icon.id)
                binding.iconCatgTxv.text = app.iconPack?.getCategory(icon.categoryId)?.name

                // Prepare tags text for toast
                val tags = mutableListOf<String>()
                for (tagName in icon.tags) {
                    val tag = app.iconPack?.getTag(tagName) as? NamedTag ?: continue
                    tags += if (tag.values.size == 1) {
                        tag.values.first().value
                    } else {
                        "{${tag.values.joinToString { it.value }}}"
                    }
                }
                if (tags.isNotEmpty()) {
                    val text = tags.joinToString()
                    itemView.setOnClickListener {
                        Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
                    }
                } else {
                    itemView.setOnClickListener(null)
                }
            }
        }

        override fun getItemCount() = selectedIcons.size

        override fun getItemId(pos: Int) = selectedIcons[pos].id.toLong()

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            IconViewHolder(ItemIconBinding.inflate(layoutInflater, parent, false))

        override fun onBindViewHolder(holder: IconViewHolder, pos: Int) =
            holder.bind(selectedIcons[pos])
    }

    companion object {
        private const val ICON_DIALOG_TAG = "icon-dialog"
    }
}
