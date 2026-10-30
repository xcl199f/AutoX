package org.autojs.autojs.ui.codegeneration

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bignerdranch.expandablerecyclerview.ChildViewHolder
import com.bignerdranch.expandablerecyclerview.ExpandableRecyclerAdapter
import com.bignerdranch.expandablerecyclerview.ParentViewHolder
import com.bignerdranch.expandablerecyclerview.model.Parent
import com.stardust.app.DialogUtils
import com.stardust.autojs.codegeneration.CodeGenerator
import com.stardust.theme.util.ListBuilder
import com.stardust.util.ClipboardUtil
import com.stardust.view.accessibility.NodeInfo
import org.autojs.autoxjs.R
import org.autojs.autoxjs.databinding.DialogCodeGenerateBinding
import org.autojs.autoxjs.databinding.DialogCodeGenerateOptionBinding
import org.autojs.autoxjs.databinding.DialogCodeGenerateOptionGroupBinding
import org.autojs.autojs.theme.dialog.ThemeColorMaterialDialogBuilder
import org.autojs.autojs.ui.widget.CheckBoxCompat
import java.util.ArrayList

class CodeGenerateDialog(
    context: Context,
    private val rootNode: NodeInfo,
    private val targetNode: NodeInfo
) : ThemeColorMaterialDialogBuilder(context) {

    private val binding = DialogCodeGenerateBinding.inflate(
        LayoutInflater.from(context),
        null,
        false
    )

    private val optionGroups = ListBuilder<OptionGroup>()
        .add(OptionGroup(R.string.text_options, false)
            .addOption(R.string.text_using_id_selector, true)
            .addOption(R.string.text_using_text_selector, true)
            .addOption(R.string.text_using_desc_selector, true))
        .add(OptionGroup(R.string.text_select)
            .addOption(R.string.text_find_one, true)
            .addOption(R.string.text_until_find)
            .addOption(R.string.text_wait_for)
            .addOption(R.string.text_selector_exists))
        .add(OptionGroup(R.string.text_action)
            .addOption(R.string.text_click)
            .addOption(R.string.text_long_click)
            .addOption(R.string.text_set_text)
            .addOption(R.string.text_scroll_forward)
            .addOption(R.string.text_scroll_backward))
        .list()

    private lateinit var mAdapter: Adapter

    init {
        positiveText(R.string.text_generate)
        negativeText(R.string.text_cancel)
        onPositive { _, _ -> generateCodeAndShow() }
        setupViews()
    }

    private fun generateCodeAndShow() {
        val code = generateCode()
        if (code == null) {
            android.widget.Toast.makeText(context, R.string.text_generate_fail, android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        DialogUtils.showDialog(
            ThemeColorMaterialDialogBuilder(context)
                .title(R.string.text_generated_code)
                .content(code)
                .positiveText(R.string.text_copy)
                .onPositive { _, _ -> ClipboardUtil.setClip(context, code) }
                .build()
        )
    }

    private fun generateCode(): String? {
        val generator = CodeGenerator(rootNode, targetNode)
        val settings = getOptionGroup(R.string.text_options)
        generator.setUsingId(settings.getOption(R.string.text_using_id_selector).checked)
        generator.setUsingText(settings.getOption(R.string.text_using_text_selector).checked)
        generator.setUsingDesc(settings.getOption(R.string.text_using_desc_selector).checked)
        generator.setSearchMode(getSearchMode())
        setAction(generator)
        return generator.generateCode()
    }

    private fun setAction(generator: CodeGenerator) {
        val action = getOptionGroup(R.string.text_action)
        when {
            action.getOption(R.string.text_click).checked -> generator.setAction(AccessibilityNodeInfoCompat.ACTION_CLICK)
            action.getOption(R.string.text_long_click).checked -> generator.setAction(AccessibilityNodeInfoCompat.ACTION_LONG_CLICK)
            action.getOption(R.string.text_scroll_forward).checked -> generator.setAction(AccessibilityNodeInfoCompat.ACTION_SCROLL_FORWARD)
            action.getOption(R.string.text_scroll_backward).checked -> generator.setAction(AccessibilityNodeInfoCompat.ACTION_SCROLL_BACKWARD)
        }
    }

    private fun getSearchMode(): Int {
        val selectMode = getOptionGroup(R.string.text_select)
        return when {
            selectMode.getOption(R.string.text_find_one).checked -> CodeGenerator.FIND_ONE
            selectMode.getOption(R.string.text_until_find).checked -> CodeGenerator.UNTIL_FIND
            selectMode.getOption(R.string.text_wait_for).checked -> CodeGenerator.WAIT_FOR
            selectMode.getOption(R.string.text_selector_exists).checked -> CodeGenerator.EXISTS
            else -> CodeGenerator.FIND_ONE
        }
    }

    private fun setupViews() {
        customView(binding.root, false)
        binding.options.layoutManager = LinearLayoutManager(context)
        mAdapter = Adapter(optionGroups)
        binding.options.adapter = mAdapter
    }

    private fun getOptionGroup(title: Int): OptionGroup {
        for (group in optionGroups) {
            if (group.titleRes == title) {
                return group
            }
        }
        throw IllegalArgumentException()
    }

    private fun uncheckOthers(parentAdapterPosition: Int, child: Option) {
        val group = child.group ?: return
        var notify = false
        for (other in group.options) {
            if (other != child && other.checked) {
                other.checked = false
                notify = true
            }
        }
        if (notify) {
            mAdapter.notifyParentChanged(parentAdapterPosition)
        }
    }

    private class Option(
        val titleRes: Int,
        var checked: Boolean,
        var group: OptionGroup? = null
    )

    private class OptionGroup(
        val titleRes: Int,
        private val initialExpanded: Boolean = true
    ) : Parent<Option> {
        val options = ArrayList<Option>()

        fun getOption(titleRes: Int): Option {
            for (option in options) {
                if (option.titleRes == titleRes) {
                    return option
                }
            }
            throw IllegalArgumentException()
        }

        override fun getChildList(): List<Option> = options

        override fun isInitiallyExpanded(): Boolean = initialExpanded

        fun addOption(titleRes: Int): OptionGroup {
            return addOption(titleRes, false)
        }

        fun addOption(res: Int, checked: Boolean = false): OptionGroup {
            val option = Option(res, checked)
            option.group = this
            options.add(option)
            return this
        }
    }

    private inner class OptionViewHolder(itemView: View) : ChildViewHolder<Option>(itemView) {
        private val binding = DialogCodeGenerateOptionBinding.bind(itemView)

        init {
            itemView.setOnClickListener {
                binding.checkbox.toggle()
            }
            binding.checkbox.setOnCheckedChangeListener { _, _ ->
                val child = getChild()
                child.checked = binding.checkbox.isChecked
                if (binding.checkbox.isChecked && child.group?.titleRes != R.string.text_options) {
                    uncheckOthers(parentAdapterPosition, child)
                }
            }
        }

        fun bind(option: Option) {
            binding.title.setText(option.titleRes)
            binding.checkbox.setChecked(option.checked, false)
        }
    }

    private inner class OptionGroupViewHolder(itemView: View) : ParentViewHolder<OptionGroup, Option>(itemView) {
        private val binding = DialogCodeGenerateOptionGroupBinding.bind(itemView)

        init {
            itemView.setOnClickListener {
                if (isExpanded) {
                    collapseView()
                } else {
                    expandView()
                }
            }
        }

        override fun onExpansionToggled(expanded: Boolean) {
            binding.icon.rotation = if (expanded) -90f else 0f
        }

        fun bind(group: OptionGroup) {
            binding.title.setText(group.titleRes)
            binding.icon.rotation = if (isExpanded) 0f else -90f
        }
    }

    private inner class Adapter(parentList: List<OptionGroup>) :
        ExpandableRecyclerAdapter<OptionGroup, Option, OptionGroupViewHolder, OptionViewHolder>(parentList) {

        override fun onCreateParentViewHolder(parentViewGroup: ViewGroup, viewType: Int): OptionGroupViewHolder {
            val view = LayoutInflater.from(parentViewGroup.context)
                .inflate(R.layout.dialog_code_generate_option_group, parentViewGroup, false)
            return OptionGroupViewHolder(view)
        }

        override fun onCreateChildViewHolder(childViewGroup: ViewGroup, viewType: Int): OptionViewHolder {
            val view = LayoutInflater.from(childViewGroup.context)
                .inflate(R.layout.dialog_code_generate_option, childViewGroup, false)
            return OptionViewHolder(view)
        }

        override fun onBindParentViewHolder(viewHolder: OptionGroupViewHolder, parentPosition: Int, optionGroup: OptionGroup) {
            viewHolder.bind(optionGroup)
        }

        override fun onBindChildViewHolder(viewHolder: OptionViewHolder, parentPosition: Int, childPosition: Int, option: Option) {
            viewHolder.bind(option)
        }
    }
}