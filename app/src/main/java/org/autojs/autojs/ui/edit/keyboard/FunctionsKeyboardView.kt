package org.autojs.autojs.ui.edit.keyboard

import android.content.Context
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.os.Build
import android.text.TextUtils
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import org.autojs.autoxjs.R
import org.autojs.autoxjs.databinding.FunctionsKeyboardViewBinding
import org.autojs.autojs.model.indices.Module
import org.autojs.autojs.model.indices.Modules
import org.autojs.autojs.model.indices.Property
import org.autojs.autojs.ui.widget.GridDividerDecoration
import org.autojs.autojs.workground.WrapContentGridLayoutManger
import java.util.ArrayList
import java.util.HashMap

class FunctionsKeyboardView : FrameLayout {

    interface ClickCallback {
        fun onModuleLongClick(module: Module)
        fun onPropertyClick(m: Module, property: Property)
        fun onPropertyLongClick(m: Module, property: Property)
    }

    companion object {
        private const val SPAN_COUNT = 4
    }

    private val binding = FunctionsKeyboardViewBinding.inflate(
        LayoutInflater.from(context),
        this,
        true
    )

    private var modules: List<Module>? = null
    private val spanSizes = HashMap<Module, List<Int>>()
    private var selectedModule: Module? = null
    private var selectedModuleView: View? = null
    private var paint: Paint? = null
    private var clickCallback: ClickCallback? = null

    constructor(context: Context) : super(context) {
        init()
    }

    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs) {
        init()
    }

    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) : super(context, attrs, defStyleAttr) {
        init()
    }

    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int, defStyleRes: Int) : super(context, attrs, defStyleAttr, defStyleRes) {
        init()
    }

    fun setClickCallback(callback: ClickCallback) {
        clickCallback = callback
    }

    private fun init() {
        initModulesView()
        initPropertiesView()
    }

    private fun initPropertiesView() {
        val manager = WrapContentGridLayoutManger(context, SPAN_COUNT)
        binding.properties.layoutManager = manager
        binding.properties.adapter = PropertiesAdapter()
        manager.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int {
                val module = selectedModule ?: return 1
                return spanSizes[module]?.get(position) ?: 1
            }
        }
        val divider = ContextCompat.getDrawable(context, R.drawable.divider_functions_view)
        val dividerItemDecoration = GridDividerDecoration(context, divider)
        binding.properties.addItemDecoration(dividerItemDecoration)
    }

    private fun initSpanSizes(module: Module) {
        if (spanSizes.containsKey(module)) return
        if (measuredWidth == 0) throw IllegalStateException()

        val spanSizesList = ArrayList<Int>()
        for (property in module.properties) {
            val width = maxOf(getTextWidth(property.key), getTextWidth(property.summary ?: ""))
            var spanSize = (width / (measuredWidth.toDouble() / 4)).toInt()
            spanSizesList.add(spanSize.coerceAtMost(2))
        }

        var column = 0
        for (i in spanSizesList.indices) {
            val spanSize = spanSizesList[i]
            if (spanSize + column > SPAN_COUNT) {
                spanSizesList[i - 1] = 2
                column = spanSize
            } else {
                column += spanSize
            }
            if (column == 4) {
                column = 0
            }
        }
        spanSizes[module] = spanSizesList
    }

    private fun getDisplayText(property: Property): String {
        return if (TextUtils.isEmpty(property.summary)) {
            property.key
        } else {
            "${property.key}\n${property.summary}"
        }
    }

    private fun getTextWidth(text: String): Int {
        if (paint == null) {
            paint = Paint().apply {
                textSize = resources.getDimensionPixelSize(R.dimen.textSize_item_property).toFloat()
            }
        }
        val r = Rect()
        paint?.getTextBounds(text, 0, text.length, r)
        return r.width()
    }

    private fun initModulesView() {
        binding.moduleList.layoutManager = LinearLayoutManager(context, RecyclerView.HORIZONTAL, false)
        binding.moduleList.adapter = ModulesAdapter()
    }

    private fun loadModules() {
        Modules.getInstance().getModules(context)
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe { moduleList ->
                modules = moduleList
                if (moduleList.isNotEmpty()) {
                    setSelectedModule(moduleList[0], null)
                }
                binding.moduleList.adapter?.notifyDataSetChanged()
                binding.properties.adapter?.notifyDataSetChanged()
            }
    }

    private fun setSelectedModule(module: Module, moduleView: View?) {
        selectedModule = module
        selectedModuleView?.isSelected = false
        selectedModuleView = moduleView
        selectedModuleView?.isSelected = true
        initSpanSizes(module)
        binding.properties.adapter?.notifyDataSetChanged()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        if (modules == null) {
            loadModules()
        }
    }

    private inner class ModuleViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val textView: TextView = itemView as TextView
        private var module: Module? = null

        init {
            textView.setOnClickListener {
                val m = module ?: return@setOnClickListener
                setSelectedModule(m, textView)
            }
            textView.setOnLongClickListener {
                val m = module ?: return@setOnLongClickListener false
                clickCallback?.onModuleLongClick(m)
                true
            }
        }

        fun bind(module: Module) {
            this.module = module
            textView.text = module.summary
            textView.isSelected = module == selectedModule
            if (module == selectedModule) {
                selectedModuleView = textView
            }
        }
    }

    private inner class PropertyViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val textView: TextView = itemView as TextView
        private var property: Property? = null

        init {
            textView.setOnLongClickListener {
                val p = property ?: return@setOnLongClickListener false
                val m = selectedModule ?: return@setOnLongClickListener false
                clickCallback?.onPropertyLongClick(m, p)
                true
            }
            textView.setOnClickListener {
                val p = property ?: return@setOnClickListener
                val m = selectedModule ?: return@setOnClickListener
                clickCallback?.onPropertyClick(m, p)
            }
        }

        fun bind(property: Property) {
            this.property = property
            textView.text = getDisplayText(property)
        }
    }

    private inner class ModulesAdapter : RecyclerView.Adapter<ModuleViewHolder>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ModuleViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_module, parent, false) as TextView
            return ModuleViewHolder(view)
        }

        override fun onBindViewHolder(holder: ModuleViewHolder, position: Int) {
            holder.bind(modules!![position])
        }

        override fun getItemCount(): Int = modules?.size ?: 0
    }

    private inner class PropertiesAdapter : RecyclerView.Adapter<PropertyViewHolder>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PropertyViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_property, parent, false) as TextView
            return PropertyViewHolder(view)
        }

        override fun onBindViewHolder(holder: PropertyViewHolder, position: Int) {
            val module = selectedModule ?: return
            holder.bind(module.properties[position])
        }

        override fun getItemCount(): Int = selectedModule?.properties?.size ?: 0
    }
}