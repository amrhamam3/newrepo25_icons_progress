package com.amr3d.preview.pro

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.text.InputType
import android.view.*
import android.view.animation.*
import android.widget.*
import androidx.cardview.widget.CardView
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

class ViewerFragment : Fragment() {

    // ═══ Views ═══
    private lateinit var glViewerView: GLViewerView
    private lateinit var dxf2DView: DXF2DView
    private lateinit var displayToolbar: View
    private lateinit var bottomToolbar: View
    private lateinit var emptyStateText: TextView
    private lateinit var welcomeText: TextView
    private lateinit var welcomeOverlay: TextView
    private lateinit var btnOpenFile: Button
    private lateinit var btnWhatsapp: ImageButton
    private lateinit var btnMeasureTool: ToggleButton
    private lateinit var btnInspect: Button
    private lateinit var btnResetView: Button
    private lateinit var btnGridFloor: ToggleButton
    private lateinit var btnWireframe: ToggleButton
    private lateinit var btnMaterial: Button
    private lateinit var btnUnit: Button
    private lateinit var btnExport: Button
    private lateinit var btnLightToggle: ToggleButton
    private lateinit var btnDirections: Button
    private lateinit var directionsPanel: View
    private lateinit var btnViewFront: Button
    private lateinit var btnViewBack: Button
    private lateinit var btnViewLeft: Button
    private lateinit var btnViewRight: Button
    private lateinit var btnViewTop: Button
    private lateinit var btnViewBottom: Button
    private lateinit var measurementCard: CardView
    private lateinit var measurementText: TextView
    private lateinit var inspectionCard: CardView
    private lateinit var inspectionText: TextView
    private lateinit var lightWheelContainer: ViewGroup
    private lateinit var lightWheel: SemiCircleLightView
    private lateinit var btnCloseLightWheel: ImageButton
    private lateinit var loadingContainer: View
    private lateinit var loadingProgress: ProgressBar
    private lateinit var loadingText: TextView

    private var currentModel: STLModel? = null
    private var currentModelFileSizeBytes: Long = -1L
    private var is2DMode = false
    private var measureModeOn = false
    private var currentUnit = MeasurementUnit.MM

    // ملف معلّق من MainActivity (قبل init الـ View)
    private var pendingUri: Uri? = null

    private val openDocumentLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                requireContext().contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: SecurityException) {}
            loadFile(uri)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_viewer, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        AppTheme.applyThemeRecursively(view, requireContext())
        bindViews(view)
        loadSavedSettings()
        setupWelcome()
        wireUpListeners()
        animateToolbarEntrance()

        // تحميل أي ملف كان معلقاً قبل init الـ View
        pendingUri?.let {
            pendingUri = null
            loadFile(it)
        }
    }

    /** يقرأ الإعدادات المحفوظة من صفحة الإعدادات ويطبّقها فعلياً */
    private fun loadSavedSettings() {
        val prefs = requireContext().getSharedPreferences("amr3d_prefs", Context.MODE_PRIVATE)
        currentUnit = when (prefs.getString("unit", "MM")) {
            "CM"   -> MeasurementUnit.CM
            "INCH" -> MeasurementUnit.INCH
            else   -> MeasurementUnit.MM
        }
        val quality = when (prefs.getString("quality", "HIGH")) {
            "LOW"    -> 0
            "MEDIUM" -> 1
            else     -> 2
        }
        glViewerView.stlRenderer.qualityLevel = quality
    }

    override fun onResume() {
        super.onResume()
        // إعادة قراءة الإعدادات عند الرجوع من صفحة الإعدادات
        if (::btnUnit.isInitialized) {
            loadSavedSettings()
            btnUnit.text = "📏 ${currentUnit.label}"
        }
    }

    /**
     * ملحوظة مهمة: MainActivity بيستخدم hide()/show() للتنقل بين التابات (مش replace)،
     * و hide()/show() ما بيستدعوش onPause()/onResume() على الفراجمنت — بيستدعوا onHiddenChanged() بس.
     * من غيرها، أي إعداد تغيّره في شاشة الإعدادات (زي وحدة القياس أو جودة العرض) كان
     * هيفضل باين إنه اتغيّر في الإعدادات، لكن شاشة العارض نفسها كانت مش بتاخده إلا لو
     * التطبيق كله اتقفل وفتح تاني (onResume حقيقي). دلوقتي بنعيد القراءة برضه هنا.
     */
    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden && ::btnUnit.isInitialized) {
            loadSavedSettings()
            btnUnit.text = "📏 ${currentUnit.label}"
        }
    }

    private fun bindViews(v: View) {
        glViewerView        = v.findViewById(R.id.glViewerView)
        dxf2DView           = v.findViewById(R.id.dxf2DView)
        displayToolbar      = v.findViewById(R.id.displayToolbar)
        bottomToolbar       = v.findViewById(R.id.bottomToolbar)
        emptyStateText      = v.findViewById(R.id.emptyStateText)
        welcomeText         = v.findViewById(R.id.welcomeText)
        welcomeOverlay      = v.findViewById(R.id.welcomeOverlay)
        btnOpenFile         = v.findViewById(R.id.btnOpenFile)
        btnWhatsapp         = v.findViewById(R.id.btnWhatsapp)
        btnMeasureTool      = v.findViewById(R.id.btnMeasureTool)
        btnInspect          = v.findViewById(R.id.btnInspect)
        btnResetView        = v.findViewById(R.id.btnResetView)
        btnGridFloor        = v.findViewById(R.id.btnGridFloor)
        btnWireframe        = v.findViewById(R.id.btnWireframe)
        btnMaterial         = v.findViewById(R.id.btnMaterial)
        btnUnit             = v.findViewById(R.id.btnUnit)
        btnExport           = v.findViewById(R.id.btnExport)
        btnLightToggle      = v.findViewById(R.id.btnLightToggle)
        btnDirections       = v.findViewById(R.id.btnDirections)
        directionsPanel     = v.findViewById(R.id.directionsPanel)
        btnViewFront        = v.findViewById(R.id.btnViewFront)
        btnViewBack         = v.findViewById(R.id.btnViewBack)
        btnViewLeft         = v.findViewById(R.id.btnViewLeft)
        btnViewRight        = v.findViewById(R.id.btnViewRight)
        btnViewTop          = v.findViewById(R.id.btnViewTop)
        btnViewBottom       = v.findViewById(R.id.btnViewBottom)
        measurementCard     = v.findViewById(R.id.measurementCard)
        measurementText     = v.findViewById(R.id.measurementText)
        inspectionCard      = v.findViewById(R.id.inspectionCard)
        inspectionText      = v.findViewById(R.id.inspectionText)
        lightWheelContainer = v.findViewById(R.id.lightWheelContainer)
        lightWheel          = v.findViewById(R.id.lightWheel)
        btnCloseLightWheel  = v.findViewById(R.id.btnCloseLightWheel)
        loadingContainer    = v.findViewById(R.id.loadingContainer)
        loadingProgress     = v.findViewById(R.id.loadingProgress)
        loadingText         = v.findViewById(R.id.loadingText)
    }

    private fun setupWelcome() {
        val ctx = requireContext()
        val savedName = MainActivity.getUserName(ctx)
        if (savedName.isEmpty()) {
            showNameDialog(ctx)
        } else {
            welcomeText.text = getString(R.string.welcome_greeting, savedName)
            welcomeText.visibility = View.VISIBLE
        }
    }

    private fun showNameDialog(ctx: Context) {
        val input = EditText(ctx).apply {
            hint = getString(R.string.dialog_name_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            setTextColor(0xFFF2F3F5.toInt())
            setHintTextColor(0xFF9CA3AF.toInt())
            setPadding(40, 24, 40, 24)
            textSize = 16f
        }
        AlertDialog.Builder(ctx)
            .setTitle(getString(R.string.dialog_welcome_title))
            .setMessage(getString(R.string.dialog_welcome_message))
            .setView(input)
            .setCancelable(false)
            .setPositiveButton(getString(R.string.dialog_start_btn)) { _, _ ->
                val name = input.text.toString().trim().ifEmpty { getString(R.string.default_friend_name) }
                MainActivity.saveUserName(ctx, name)
                welcomeText.text = getString(R.string.welcome_greeting, name)
                welcomeText.visibility = View.VISIBLE
                // Overlay ترحيبي
                welcomeOverlay.text = getString(R.string.welcome_overlay, name)
                welcomeOverlay.visibility = View.VISIBLE
                welcomeOverlay.alpha = 0f
                welcomeOverlay.animate().alpha(1f).setDuration(400).withEndAction {
                    welcomeOverlay.animate().alpha(0f).setStartDelay(1800).setDuration(500)
                        .withEndAction { welcomeOverlay.visibility = View.GONE }.start()
                }.start()
            }.show()
    }

    private fun animateToolbarEntrance() {
        listOf(
            view?.findViewById<View>(R.id.topBar),
            view?.findViewById<View>(R.id.displayToolbar),
            view?.findViewById<View>(R.id.bottomToolbar)
        ).forEachIndexed { i, v ->
            v ?: return@forEachIndexed
            val dir = if (i == 0) -180f else 180f
            v.translationY = dir; v.alpha = 0f
            v.animate().translationY(0f).alpha(1f).setDuration(350)
                .setStartDelay(i * 80L).setInterpolator(DecelerateInterpolator(2f)).start()
        }
    }

    private fun wireUpListeners() {
        btnOpenFile.setOnClickListener { animBtn(it); openDocumentLauncher.launch(arrayOf("*/*")) }

        btnMeasureTool.setOnCheckedChangeListener { btn, isChecked ->
            animBtn(btn)
            if (is2DMode) {
                dxf2DView.measureModeOn = isChecked
                if (isChecked) {
                    Toast.makeText(context, getString(R.string.toast_tap_two_points), Toast.LENGTH_LONG).show()
                }
            } else {
                measureModeOn = isChecked
                glViewerView.measurementModeActive = isChecked
                android.util.Log.d("Amr3D_MeasureDebug", "toggle -> measureModeOn=$measureModeOn measurementModeActive=${glViewerView.measurementModeActive} currentModel=${currentModel != null}")
                if (!isChecked) {
                    glViewerView.stlRenderer.clearMeasurementPoints()
                    measurementCard.visibility = View.GONE
                } else {
                    inspectionCard.visibility = View.GONE
                    Toast.makeText(context, getString(R.string.toast_tap_two_points), Toast.LENGTH_LONG).show()
                }
            }
        }

        glViewerView.onMeasureDrag = { x, y -> if (measureModeOn) handleMeasurementDrag(x, y) }

        dxf2DView.onDistanceMeasured = { dist ->
            Toast.makeText(context, getString(R.string.toast_dxf_distance, dist), Toast.LENGTH_LONG).show()
        }

        btnInspect.setOnClickListener {
            animBtn(it)
            currentModel?.let { m -> showInspectionReport(m) }
                ?: Toast.makeText(context, getString(R.string.toast_open_file_first), Toast.LENGTH_SHORT).show()
        }

        btnResetView.setOnClickListener  { animBtn(it); if (is2DMode) dxf2DView.resetView() else resetCamera() }
        btnWhatsapp.setOnClickListener   { animBtn(it); openWhatsapp() }

        btnGridFloor.setOnCheckedChangeListener { btn, c ->
            animBtn(btn); glViewerView.stlRenderer.showGridFloor = c
        }

        btnWireframe.setOnCheckedChangeListener { btn, c ->
            animBtn(btn); glViewerView.stlRenderer.wireframeMode = c
        }

        btnMaterial.setOnClickListener  { animBtn(it); showMaterialGrid() }
        btnUnit.setOnClickListener      { animBtn(it); cycleUnit() }
        btnExport.setOnClickListener    { animBtn(it); exportCurrentView() }

        btnLightToggle.setOnCheckedChangeListener { btn, c ->
            animBtn(btn)
            lightWheelContainer.visibility = if (c) View.VISIBLE else View.GONE
        }
        btnCloseLightWheel.setOnClickListener {
            lightWheelContainer.visibility = View.GONE
            btnLightToggle.isChecked = false
        }
        lightWheel.onAngleChanged = { angle ->
            glViewerView.queueEvent { glViewerView.stlRenderer.lightAngle = angle }
        }

        // أزرار الاتجاهات الـ 6
        btnDirections.setOnClickListener {
            animBtn(it)
            val showing = directionsPanel.visibility == View.VISIBLE
            if (showing) {
                directionsPanel.animate().alpha(0f).setDuration(150)
                    .withEndAction { directionsPanel.visibility = View.GONE }.start()
            } else {
                directionsPanel.alpha = 0f
                directionsPanel.visibility = View.VISIBLE
                directionsPanel.animate().alpha(1f).setDuration(200).start()
            }
        }
        btnViewFront.setOnClickListener  { jumpToView(-10f, 0f);   hideDirections() }
        btnViewBack.setOnClickListener   { jumpToView(-10f, 180f); hideDirections() }
        btnViewLeft.setOnClickListener   { jumpToView(-10f, 90f);  hideDirections() }
        btnViewRight.setOnClickListener  { jumpToView(-10f, -90f); hideDirections() }
        btnViewTop.setOnClickListener    { jumpToView(89f, 0f);    hideDirections() }
        btnViewBottom.setOnClickListener { jumpToView(-89f, 0f);   hideDirections() }

        glViewerView.onSingleTap = { x, y -> if (measureModeOn) handleMeasurementTap(x, y) else handleViewerTap(x, y) }

        inspectionCard.setOnClickListener  { cancelInfoAutoHide(); inspectionCard.visibility = View.GONE }
        measurementCard.setOnClickListener {
            measurementCard.visibility = View.GONE
            glViewerView.stlRenderer.clearMeasurementPoints()
        }
    }

    private fun hideDirections() {
        directionsPanel.visibility = View.GONE
    }

    private fun animBtn(v: View) {
        v.animate().scaleX(0.87f).scaleY(0.87f).setDuration(70)
            .setInterpolator(AccelerateInterpolator())
            .withEndAction {
                v.animate().scaleX(1f).scaleY(1f).setDuration(140)
                    .setInterpolator(OvershootInterpolator(2.2f)).start()
            }.start()
    }

    // ══ تحميل الملف — آمن حتى قبل onViewCreated ══
    fun loadFile(uri: Uri) {
        // إذا لم يكن الـ View جاهزاً بعد، نحفظ الـ URI ونحمّله لاحقاً
        if (!isAdded || view == null) {
            pendingUri = uri
            return
        }

        val ext = getFileExtension(uri)
        if (ext == "dxf") {
            loadDxfFile(uri)
        } else {
            loadStlFile(uri)
        }
    }

    /** بيحدد امتداد الملف الحقيقي (من اسم الملف، مش بس من الـ URI) */
    private fun getFileExtension(uri: Uri): String {
        var name: String? = null
        try {
            requireContext().contentResolver.query(
                uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null
            )?.use { c ->
                if (c.moveToFirst()) {
                    val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) name = c.getString(idx)
                }
            }
        } catch (_: Exception) {}
        val fileName = name ?: uri.lastPathSegment ?: ""
        return fileName.substringAfterLast('.', "").lowercase()
    }

    /** حجم الملف الحقيقي بالبايت (لعرضه في لوحة المعلومات) — بيرجع -1 لو تعذر تحديده */
    private fun getFileSizeBytes(uri: Uri): Long {
        return try {
            requireContext().contentResolver.query(
                uri, arrayOf(android.provider.OpenableColumns.SIZE), null, null, null
            )?.use { c ->
                if (c.moveToFirst()) {
                    val idx = c.getColumnIndex(android.provider.OpenableColumns.SIZE)
                    if (idx >= 0 && !c.isNull(idx)) c.getLong(idx) else -1L
                } else -1L
            } ?: -1L
        } catch (_: Exception) { -1L }
    }

    /** مسار تحميل ملفات STL — العارض ثلاثي الأبعاد (زي ما كان) */
    private fun loadStlFile(uri: Uri) {
        switchTo3DMode()
        showLoadingBar(getString(R.string.loading_file), 0)

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val model = withContext(Dispatchers.IO) {
                    STLParser.parse(requireContext(), uri) { percent ->
                        // بيتنادى من خيط IO — لازم ننقل التحديث للـ main thread
                        requireActivity().runOnUiThread {
                            updateLoadingBar(getString(R.string.loading_analyzing), percent)
                        }
                    }
                }

                if (!isAdded || view == null) return@launch

                updateLoadingBar(getString(R.string.loading_preparing), 95)
                delay(200)
                updateLoadingBar(getString(R.string.loading_done), 100)
                delay(300)
                hideLoadingBar()

                currentModel = model
                currentModelFileSizeBytes = getFileSizeBytes(uri)

                // رفع الموديل على GL thread
                glViewerView.queueEvent {
                    glViewerView.stlRenderer.setModel(model)
                }

                requireActivity().runOnUiThread {
                    emptyStateText.visibility  = View.GONE
                    welcomeText.visibility     = View.GONE
                    inspectionCard.visibility  = View.GONE
                    measurementCard.visibility = View.GONE
                    btnMeasureTool.isChecked   = false
                    btnWireframe.isChecked     = false
                    btnGridFloor.isChecked     = false
                    directionsPanel.visibility = View.GONE
                }

                // حفظ في التاريخ — المسار الحقيقي فقط
                saveToHistory(uri)

                Toast.makeText(context, getString(R.string.toast_triangle_count, model.triangleCount), Toast.LENGTH_SHORT).show()

                // لوحة المعلومات (الأبعاد/حجم الملف/عدد المثلثات) — تظهر تلقائي 3 ثواني بالظبط
                // وبعدها تختفي لوحدها، تماماً زي ما حصل مع نفس الموديل أثناء اللودينج
                showModelInfoBriefly(model)

            } catch (e: SecurityException) {
                if (!isAdded || view == null) return@launch
                hideLoadingBar()
                Toast.makeText(context, getString(R.string.toast_permission_error), Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                if (!isAdded || view == null) return@launch
                hideLoadingBar()
                Toast.makeText(context, getString(R.string.toast_file_read_error, e.message), Toast.LENGTH_LONG).show()
            }
        }
    }

    /** مسار تحميل ملفات DXF — شاشة عرض 2D حقيقية منفصلة تماماً عن محرك الـ 3D */
    private fun loadDxfFile(uri: Uri) {
        switchTo2DMode()
        showLoadingBar(getString(R.string.loading_file), 0)

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val dxfModel = withContext(Dispatchers.IO) {
                    DXFParser.parse(requireContext(), uri)
                }

                if (!isAdded || view == null) return@launch

                updateLoadingBar(getString(R.string.loading_preparing), 95)
                delay(150)
                updateLoadingBar(getString(R.string.loading_done), 100)
                delay(250)
                hideLoadingBar()

                dxf2DView.setModel(dxfModel)

                emptyStateText.visibility  = View.GONE
                welcomeText.visibility     = View.GONE

                saveToHistory(uri)

                Toast.makeText(context, getString(R.string.toast_entity_count, dxfModel.entityCount), Toast.LENGTH_SHORT).show()

            } catch (e: SecurityException) {
                if (!isAdded || view == null) return@launch
                hideLoadingBar()
                Toast.makeText(context, getString(R.string.toast_permission_error), Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                if (!isAdded || view == null) return@launch
                hideLoadingBar()
                Toast.makeText(context, getString(R.string.toast_file_read_error, e.message), Toast.LENGTH_LONG).show()
            }
        }
    }

    /** تفعيل وضع عرض DXF ثنائي الأبعاد — يخفي أدوات العارض ثلاثي الأبعاد اللي مالهاش معنى هنا */
    private fun switchTo2DMode() {
        if (is2DMode) return
        is2DMode = true
        currentModel = null
        glViewerView.visibility = View.GONE
        dxf2DView.visibility = View.VISIBLE

        displayToolbar.visibility = View.GONE   // مادة / شبكي / وحدة / تصدير / إضاءة — كلها خاصة بالـ 3D
        btnMeasureTool.visibility = View.VISIBLE // القياس دلوقتي متاح في DXF كمان (شغال على شاشة الـ 2D)
        btnMeasureTool.isChecked  = false
        btnInspect.visibility     = View.GONE   // الفحص (الأبعاد الكاملة) لسه خاص بالموديل ثلاثي الأبعاد بس
        btnDirections.visibility  = View.GONE
        directionsPanel.visibility = View.GONE
        lightWheelContainer.visibility = View.GONE
        measurementCard.visibility = View.GONE
        inspectionCard.visibility  = View.GONE

        Toast.makeText(context, getString(R.string.dxf_mode), Toast.LENGTH_SHORT).show()
    }

    /** الرجوع لوضع العارض ثلاثي الأبعاد العادي (لما نفتح ملف STL) */
    private fun switchTo3DMode() {
        if (!is2DMode) return
        is2DMode = false
        dxf2DView.measureModeOn = false
        dxf2DView.visibility = View.GONE
        dxf2DView.clear()
        glViewerView.visibility = View.VISIBLE

        displayToolbar.visibility = View.VISIBLE
        btnMeasureTool.isChecked  = false
        btnMeasureTool.visibility = View.VISIBLE
        btnInspect.visibility     = View.VISIBLE
        btnDirections.visibility  = View.VISIBLE
    }

    private fun saveToHistory(uri: Uri) {
        try {
            val cursor = requireContext().contentResolver.query(
                uri, arrayOf(android.provider.MediaStore.MediaColumns.DATA), null, null, null
            )
            cursor?.use { c ->
                if (c.moveToFirst()) {
                    val idx = c.getColumnIndex(android.provider.MediaStore.MediaColumns.DATA)
                    if (idx >= 0 && !c.isNull(idx)) {
                        val path = c.getString(idx)
                        if (path != null) HistoryFragment.addToHistory(requireContext(), path)
                    }
                }
            }
        } catch (_: Exception) {}
    }

    fun loadSTLFile(uri: Uri) = loadFile(uri)

    private var currentLoadingMsg = ""
    private var showLargeFileHint = false
    private var largeFileHintJob: Job? = null

    private fun renderLoadingText(msg: String, progress: Int) {
        currentLoadingMsg = msg
        val base = "$msg  $progress%"
        loadingText.text = if (showLargeFileHint)
            "$base\n${getString(R.string.loading_large_file_hint)}" else base
    }

    private fun showLoadingBar(msg: String, progress: Int) {
        if (view == null || !isAdded) return
        showLargeFileHint = false
        loadingContainer.visibility = View.VISIBLE
        loadingContainer.alpha = 1f
        loadingProgress.progress = progress
        renderLoadingText(msg, progress)

        // لو التحميل استغرق وقت طويل (ملف كبير نسبياً) بنوضّح ده للمستخدم بدل ما يفضل مستني من غير تفسير
        largeFileHintJob?.cancel()
        largeFileHintJob = viewLifecycleOwner.lifecycleScope.launch {
            delay(4000)
            if (isAdded && view != null && loadingContainer.visibility == View.VISIBLE) {
                showLargeFileHint = true
                renderLoadingText(currentLoadingMsg, loadingProgress.progress)
            }
        }
    }

    private fun updateLoadingBar(msg: String, progress: Int) {
        if (view == null || !isAdded) return
        loadingProgress.progress = progress
        renderLoadingText(msg, progress)
    }

    private fun hideLoadingBar() {
        largeFileHintJob?.cancel()
        if (view == null || !isAdded) return
        loadingContainer.animate().alpha(0f).setDuration(300).withEndAction {
            if (isAdded && view != null) {
                loadingContainer.visibility = View.GONE
                loadingContainer.alpha = 1f
            }
        }.start()
    }

    private fun jumpToView(targetRotX: Float, targetRotY: Float) {
        val r = glViewerView.stlRenderer
        val sx = r.rotationX; val sy = r.rotationY
        android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 300; interpolator = DecelerateInterpolator(2f)
            addUpdateListener { a ->
                val t = a.animatedValue as Float
                r.rotationX = sx + (targetRotX - sx) * t
                r.rotationY = sy + (targetRotY - sy) * t
            }
        }.start()
    }

    private fun resetCamera() {
        val r = glViewerView.stlRenderer
        r.rotationX = -25f; r.rotationY = 35f
        r.scaleFactor = 1f; r.panX = 0f; r.panY = 0f
        r.pivotOverride = null
        glViewerView.queueEvent { r.updateProjection() }
    }

    // ══ قائمة الخامات — كرات زجاجية ══
    private fun showMaterialGrid() {
        val materials = STLRenderer.Material.values()
        val ctx = requireContext()

        // ألوان الكرات (gradient start, gradient end, glow)
        val ballColors = listOf(
            Triple("#7DD4FF", "#0A3A7A", "#1A72CC"),  // بلاستيك
            Triple("#F0F0FF", "#383850", "#606088"),  // معدن
            Triple("#E09A5A", "#3A2210", "#8A5E38"),  // خشب
            Triple("#F8F6F0", "#808078", "#C8C4B8"),  // رخام
            Triple("#FFB070", "#602808", "#C06828"),  // نحاس
            Triple("#3A4850", "#060A0C", "#181E24"),  // كربون
            Triple("#FFE880", "#705000", "#D4A018"),  // ذهب
            Triple("#484E54", "#080A0C", "#202428"),  // مطاط
        )

        val bgColors = listOf(
            floatArrayOf(0.10f,0.11f,0.13f), floatArrayOf(0.02f,0.02f,0.02f),
            floatArrayOf(0.22f,0.24f,0.27f), floatArrayOf(0.92f,0.92f,0.92f),
            floatArrayOf(0.05f,0.08f,0.18f)
        )
        val bgNames  = listOf(
            getString(R.string.bg_name_dark), getString(R.string.bg_name_black),
            getString(R.string.bg_name_gray), getString(R.string.bg_name_white),
            getString(R.string.bg_name_navy)
        )
        val bgHex    = listOf("#1A1D24","#050607","#3A3D44","#EEEEF0","#0D1530")

        val dialog = android.app.Dialog(ctx)
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        val scroll = android.widget.ScrollView(ctx)
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 28, 24, 20)
            setBackgroundColor(0xFF1A1D24.toInt())
            // rounded corners via clip
        }

        // عنوان
        root.addView(TextView(ctx).apply {
            text = getString(R.string.dialog_choose_material)
            textSize = 17f
            setTextColor(0xFFFF8A1E.toInt())
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, 20)
        })

        // شبكة 4 × 2
        val grid = android.widget.GridLayout(ctx).apply {
            columnCount = 4
        }

        materials.forEachIndexed { i, mat ->
            val colors = ballColors.getOrNull(i)
            val cell = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = android.view.Gravity.CENTER
                setPadding(6, 6, 6, 6)
            }

            // الكرة — Canvas مرسومة
            val ball = object : android.view.View(ctx) {
                override fun onDraw(c: android.graphics.Canvas) {
                    val w = width.toFloat(); val h = height.toFloat()
                    val r = minOf(w,h)/2f - 3f
                    val cx = w/2f; val cy = h/2f
                    val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)

                    // توهج خارجي
                    if (colors != null) {
                        val glowColor = android.graphics.Color.parseColor(colors.third)
                        p.maskFilter = android.graphics.BlurMaskFilter(r*0.5f,
                            android.graphics.BlurMaskFilter.Blur.NORMAL)
                        p.color = (glowColor and 0x00FFFFFF) or 0x44000000
                        c.drawCircle(cx, cy+4f, r*0.85f, p)
                        p.maskFilter = null
                    }

                    // Gradient الرئيسي
                    if (colors != null) {
                        p.shader = android.graphics.RadialGradient(
                            cx - r*0.25f, cy - r*0.25f, r*1.1f,
                            intArrayOf(
                                android.graphics.Color.parseColor(colors.first),
                                android.graphics.Color.parseColor(colors.second)
                            ),
                            floatArrayOf(0f, 1f),
                            android.graphics.Shader.TileMode.CLAMP
                        )
                    } else {
                        p.color = 0xFF444444.toInt()
                    }
                    c.drawCircle(cx, cy, r, p)
                    p.shader = null

                    // بريق PS5 (أبيض ناعم من أعلى يسار)
                    p.shader = android.graphics.RadialGradient(
                        cx - r*0.22f, cy - r*0.30f, r*0.55f,
                        intArrayOf(0xAAFFFFFF.toInt(), 0x00FFFFFF),
                        floatArrayOf(0f, 1f),
                        android.graphics.Shader.TileMode.CLAMP
                    )
                    c.drawCircle(cx - r*0.12f, cy - r*0.18f, r*0.5f, p)
                    p.shader = null

                    // حلقة بريق خارجية
                    p.style = android.graphics.Paint.Style.STROKE
                    p.strokeWidth = 1.5f
                    p.color = 0x22FFFFFF
                    c.drawCircle(cx, cy, r, p)
                }
            }

            val size = (72 * ctx.resources.displayMetrics.density).toInt()
            ball.layoutParams = LinearLayout.LayoutParams(size, size)
            ball.setOnClickListener {
                glViewerView.stlRenderer.setMaterial(mat)
                Toast.makeText(ctx, mat.localizedName(ctx), Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }

            val name = TextView(ctx).apply {
                text = mat.localizedName(ctx); textSize = 10f
                setTextColor(0xFFBBBBBB.toInt())
                gravity = android.view.Gravity.CENTER
                setPadding(0, 4, 0, 0)
            }

            cell.addView(ball)
            cell.addView(name)

            val lp = android.widget.GridLayout.LayoutParams().apply {
                columnSpec = android.widget.GridLayout.spec(i % 4, 1f)
                rowSpec    = android.widget.GridLayout.spec(i / 4)
                width  = 0
                height = android.widget.GridLayout.LayoutParams.WRAP_CONTENT
                setMargins(4, 4, 4, 4)
            }
            cell.layoutParams = lp
            grid.addView(cell)
        }
        root.addView(grid)

        // فاصل خلفية
        root.addView(View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1).also { it.setMargins(0,20,0,12) }
            setBackgroundColor(0xFF2A2E38.toInt())
        })
        root.addView(TextView(ctx).apply {
            text = getString(R.string.dialog_background_label); textSize = 12f
            setTextColor(0xFF666666.toInt()); setPadding(0,0,0,10)
        })

        // صف الخلفيات
        val bgRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        bgNames.forEachIndexed { i, name ->
            val swatchSize = (48 * ctx.resources.displayMetrics.density).toInt()
            val cell2 = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = android.view.Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f).also { it.setMargins(3,0,3,0) }
            }
            val swatch = object : android.view.View(ctx) {
                override fun onDraw(c: android.graphics.Canvas) {
                    val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
                    val r = 10f * resources.displayMetrics.density
                    val rect = android.graphics.RectF(2f,2f,width-2f,height-2f)
                    p.color = android.graphics.Color.parseColor(bgHex[i])
                    c.drawRoundRect(rect, r, r, p)
                    p.style = android.graphics.Paint.Style.STROKE
                    p.strokeWidth = 1.5f
                    p.color = 0x44FFFFFF
                    c.drawRoundRect(rect, r, r, p)
                }
            }
            swatch.layoutParams = LinearLayout.LayoutParams(swatchSize, swatchSize)
            swatch.setOnClickListener {
                val c = bgColors[i]
                glViewerView.stlRenderer.setBackgroundColor(c[0], c[1], c[2])
                dialog.dismiss()
            }
            cell2.addView(swatch)
            cell2.addView(TextView(ctx).apply {
                text = name; textSize = 9f
                setTextColor(0xFF888888.toInt())
                gravity = android.view.Gravity.CENTER
                setPadding(0,3,0,0)
            })
            bgRow.addView(cell2)
        }
        root.addView(bgRow)

        scroll.addView(root)
        dialog.setContentView(scroll)
        dialog.window?.setLayout(
            (340 * ctx.resources.displayMetrics.density).toInt(),
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        )
        dialog.show()
    }

    private fun cycleUnit() {
        currentUnit = when (currentUnit) {
            MeasurementUnit.MM   -> MeasurementUnit.CM
            MeasurementUnit.CM   -> MeasurementUnit.INCH
            MeasurementUnit.INCH -> MeasurementUnit.MM
        }
        btnUnit.text = "📏 ${currentUnit.label}"
        currentModel?.let { if (inspectionCard.visibility == View.VISIBLE) showInspectionReport(it) }
        val pts = glViewerView.stlRenderer.getMeasurementPoints()
        if (pts.size == 2) updateMeasurementText(pts[0], pts[1])
    }

    private fun exportCurrentView() {
        if (currentModel == null) { Toast.makeText(context, getString(R.string.toast_open_file_first), Toast.LENGTH_SHORT).show(); return }
        val r = glViewerView.stlRenderer
        val w = r.getSurfaceWidth(); val h = r.getSurfaceHeight()
        if (w <= 0 || h <= 0) return
        glViewerView.queueEvent {
            val bmp = r.captureFrame(w, h)
            requireActivity().runOnUiThread { saveAndShareBitmap(bmp) }
        }
    }

    private fun saveAndShareBitmap(bitmap: Bitmap) {
        try {
            val file = File(requireContext().getExternalFilesDir(Environment.DIRECTORY_PICTURES),
                "Amr3D_${System.currentTimeMillis()}.png")
            FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            val uri = FileProvider.getUriForFile(requireContext(),
                "${requireContext().packageName}.fileprovider", file)
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = "image/png"; putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }, getString(R.string.share_export_title)))
        } catch (e: Exception) {
            Toast.makeText(context, getString(R.string.toast_save_failed), Toast.LENGTH_LONG).show()
        }
    }

    private fun openWhatsapp() {
        val phone = "201009172167"
        val msg = Uri.encode(getString(R.string.whatsapp_default_msg))
        try {
            startActivity(Intent(Intent.ACTION_VIEW,
                Uri.parse("whatsapp://send?phone=$phone&text=$msg")).apply { setPackage("com.whatsapp") })
        } catch (_: Exception) {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$phone?text=$msg")))
        }
    }

    private fun handleMeasurementTap(screenX: Float, screenY: Float) {
        val model = currentModel ?: run {
            // كان بيرجع من غير أي تنبيه — ده كان بيدّي إحساس إن الأداة "مش شغالة"
            // في حين إن المشكلة كانت إن الموديل لسه مش جاهز وقت اللمس (race condition)
            Toast.makeText(context, getString(R.string.toast_open_file_first), Toast.LENGTH_SHORT).show()
            android.util.Log.d("Amr3D_MeasureDebug", "handleMeasurementTap: currentModel == null")
            return
        }
        val r = glViewerView.stlRenderer
        android.util.Log.d("Amr3D_MeasureDebug", "tap at ($screenX,$screenY) surface=${r.getSurfaceWidth()}x${r.getSurfaceHeight()}")
        val ray = RayPicker.screenPointToRay(screenX, screenY,
            r.getSurfaceWidth(), r.getSurfaceHeight(),
            r.getCurrentModelMatrix(), r.getCurrentViewMatrix(), r.getCurrentProjectionMatrix())
        android.util.Log.d("Amr3D_MeasureDebug", "ray origin=${ray.origin.toList()} dir=${ray.direction.toList()}")
        val hit = RayPicker.findClosestIntersection(ray, model) ?: run {
            android.util.Log.d("Amr3D_MeasureDebug", "no triangle intersection found")
            Toast.makeText(context, getString(R.string.toast_no_point_selected), Toast.LENGTH_SHORT).show(); return
        }
        android.util.Log.d("Amr3D_MeasureDebug", "hit=${hit.toList()}")
        r.addMeasurementPoint(hit)
        val pts = r.getMeasurementPoints()
        if (pts.size == 2) updateMeasurementText(pts[0], pts[1])
        else {
            measurementText.text = getString(R.string.measure_first_point)
            measurementCard.visibility = View.VISIBLE
        }
    }

    /** بتتنادى باستمرار أثناء سحب الإصبع بعد اختيار أول نقطة قياس — بتحدّث معاينة حية
     * للنقطة التانية والمسافة من غير ما تثبّتها فعلياً (التثبيت بيحصل لما الإصبع يترفع) */
    private fun handleMeasurementDrag(screenX: Float, screenY: Float) {
        val model = currentModel ?: return
        val r = glViewerView.stlRenderer
        val pts = r.getMeasurementPoints()
        if (pts.size != 1) return
        val ray = RayPicker.screenPointToRay(screenX, screenY,
            r.getSurfaceWidth(), r.getSurfaceHeight(),
            r.getCurrentModelMatrix(), r.getCurrentViewMatrix(), r.getCurrentProjectionMatrix())
        val hit = RayPicker.findClosestIntersection(ray, model) ?: return
        r.setPreviewMeasurementPoint(hit)
        updateMeasurementText(pts[0], hit)
    }

    private fun updateMeasurementText(p1: FloatArray, p2: FloatArray) {
        val d = MeasurementTools.distanceBetween(p1, p2, currentUnit)
        measurementText.text = String.format(Locale.US, getString(R.string.measure_distance_label), d, currentUnit.label)
        measurementCard.visibility = View.VISIBLE
    }

    private var infoAutoHideRunnable: Runnable? = null

    private fun buildModelInfoText(model: STLModel): String {
        val report = MeasurementTools.inspect(model, currentUnit)
        val u = report.unit.label
        val sizeText = if (currentModelFileSizeBytes >= 0) {
            getString(R.string.filesize_mb_format, currentModelFileSizeBytes / (1024.0 * 1024.0))
        } else "—"
        return getString(R.string.inspection_report_header) + "\n" +
            getString(R.string.inspection_width_label, "%.2f".format(report.width), u) + "\n" +
            getString(R.string.inspection_depth_label, "%.2f".format(report.depth), u) + "\n" +
            getString(R.string.inspection_height_label, "%.2f".format(report.height), u) + "\n" +
            getString(R.string.inspection_filesize_label, sizeText) + "\n" +
            getString(R.string.inspection_triangles_label, model.triangleCount)
    }

    private fun showInspectionReport(model: STLModel) {
        if (inspectionCard.visibility == View.VISIBLE) { inspectionCard.visibility = View.GONE; return }
        cancelInfoAutoHide()
        inspectionCard.alpha = 1f
        inspectionText.text = buildModelInfoText(model)
        inspectionCard.visibility  = View.VISIBLE
        measurementCard.visibility = View.GONE
    }

    /** بتتنادى بعد نجاح تحميل موديل جديد — بتوري لوحة المعلومات تلقائياً لمدة 3 ثواني
     * بالظبط وبعدها تختفي (fade out) لوحدها، من غير ما المستخدم يحتاج يدوس على حاجة. */
    private fun showModelInfoBriefly(model: STLModel) {
        cancelInfoAutoHide()
        inspectionCard.alpha = 1f
        inspectionText.text = buildModelInfoText(model)
        inspectionCard.visibility = View.VISIBLE
        measurementCard.visibility = View.GONE
        val runnable = Runnable {
            inspectionCard.animate().alpha(0f).setDuration(400)
                .withEndAction { inspectionCard.visibility = View.GONE; inspectionCard.alpha = 1f }
                .start()
        }
        infoAutoHideRunnable = runnable
        inspectionCard.postDelayed(runnable, 3000L)
    }

    private fun cancelInfoAutoHide() {
        infoAutoHideRunnable?.let { inspectionCard.removeCallbacks(it) }
        infoAutoHideRunnable = null
        inspectionCard.animate().cancel()
    }

    /** تاب على أي مكان في شاشة العارض (خارج وضع القياس): يخفي لوحة المعلومات لو ظاهرة،
     * وكمان يحدّد مركز دوران جديد (Pivot) عند نقطة اللمسة على سطح الموديل — الحاجتين
     * مع بعض من غير تعارض، زي ما هو مطلوب. */
    private fun handleViewerTap(screenX: Float, screenY: Float) {
        if (inspectionCard.visibility == View.VISIBLE) {
            cancelInfoAutoHide()
            inspectionCard.visibility = View.GONE
        }
        val model = currentModel ?: return
        val r = glViewerView.stlRenderer
        val ray = RayPicker.screenPointToRay(screenX, screenY,
            r.getSurfaceWidth(), r.getSurfaceHeight(),
            r.getCurrentModelMatrix(), r.getCurrentViewMatrix(), r.getCurrentProjectionMatrix())
        val hit = RayPicker.findClosestIntersection(ray, model) ?: return
        r.pivotOverride = hit
    }
}
