package com.amr3d.preview.pro

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.view.*
import android.widget.*
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.*
import java.io.File

class FileBrowserFragment : Fragment() {

    private lateinit var listView: ListView
    private lateinit var currentPathText: TextView
    private lateinit var btnBack: ImageButton
    private lateinit var progressBar: ProgressBar

    private val pathStack = ArrayDeque<File>()
    private var currentPath = Environment.getExternalStorageDirectory()
    private val supportedExtensions = setOf("stl", "dxf")
    private var loadJob: Job? = null

    interface OnFileSelectedListener { fun onFileSelected(file: File) }
    var fileSelectedListener: OnFileSelectedListener? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val view = inflater.inflate(R.layout.fragment_file_browser, container, false)
        AppTheme.applyThemeRecursively(view, requireContext())
        listView        = view.findViewById(R.id.fileList)
        currentPathText = view.findViewById(R.id.currentPath)
        btnBack         = view.findViewById(R.id.btnBackDir)
        progressBar     = view.findViewById(R.id.browserProgress)
        btnBack.setOnClickListener { navigateUp() }
        checkPermissionAndLoad()
        return view
    }

    private fun checkPermissionAndLoad() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (android.os.Environment.isExternalStorageManager()) {
                loadDirectory(currentPath)
            } else {
                showAllFilesAccessRequest()
            }
        } else {
            val perm = Manifest.permission.READ_EXTERNAL_STORAGE
            if (ContextCompat.checkSelfPermission(requireContext(), perm)
                == PackageManager.PERMISSION_GRANTED) {
                loadDirectory(currentPath)
            } else {
                @Suppress("DEPRECATION")
                requestPermissions(arrayOf(perm), 100)
            }
        }
    }

    private fun showAllFilesAccessRequest() {
        android.app.AlertDialog.Builder(requireContext())
            .setTitle("🔐  إذن الوصول للملفات")
            .setMessage("لعرض ملفات STL و DXF من كل مجلدات جهازك، يحتاج التطبيق إذن \"الوصول لكل الملفات\".\n\nسيتم فتح صفحة الإعدادات — فعّل الإذن ثم ارجع للتطبيق.")
            .setCancelable(false)
            .setPositiveButton("فتح الإعدادات") { _, _ ->
                try {
                    val intent = android.content.Intent(
                        android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        android.net.Uri.parse("package:${requireContext().packageName}")
                    )
                    startActivity(intent)
                } catch (_: Exception) {
                    try {
                        startActivity(android.content.Intent(
                            android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION
                        ))
                    } catch (_: Exception) {
                        Toast.makeText(context, getString(R.string.toast_settings_open_failed), Toast.LENGTH_LONG).show()
                    }
                }
            }
            .setNegativeButton("لاحقاً") { _, _ ->
                Toast.makeText(context, getString(R.string.toast_some_folders_hidden), Toast.LENGTH_LONG).show()
            }
            .show()
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        if (requestCode == 100 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED)
            loadDirectory(currentPath)
        else
            Toast.makeText(context, getString(R.string.toast_file_access_required), Toast.LENGTH_LONG).show()
    }

    private fun navigateUp() {
        if (pathStack.isNotEmpty()) {
            currentPath = pathStack.removeLast()
            loadDirectory(currentPath)
        } else {
            Toast.makeText(context, getString(R.string.toast_root_folder), Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateBackButton() {
        if (!isAdded) return
        btnBack.alpha = if (pathStack.isEmpty()) 0.4f else 1.0f
    }

    // ══ تحميل المجلد بشكل async لتجنب تجميد الـ UI ══
    private fun loadDirectory(dir: File) {
        if (!isAdded) return
        loadJob?.cancel()
        progressBar.visibility = View.VISIBLE
        listView.visibility = View.GONE
        currentPathText.text = dir.absolutePath

        loadJob = viewLifecycleOwner.lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    val allFiles = try { dir.listFiles() } catch (_: Exception) { null }
                        ?: return@withContext null

                    // المجلدات — بدون بحث عميق لتجنب التأخير
                    val dirs = allFiles
                        .filter { it.isDirectory && !it.isHidden && it.canRead() }
                        .sortedBy { it.name.lowercase() }

                    // الملفات المدعومة فقط
                    val files = allFiles
                        .filter { it.isFile && it.extension.lowercase() in supportedExtensions }
                        .sortedBy { it.name.lowercase() }

                    Pair(dirs, files)
                }

                if (!isAdded) return@launch
                progressBar.visibility = View.GONE
                listView.visibility = View.VISIBLE

                if (result == null) {
                    Toast.makeText(context, getString(R.string.toast_cannot_access_folder), Toast.LENGTH_SHORT).show()
                    return@launch
                }

                val (dirs, files) = result
                val entries = dirs + files

                if (entries.isEmpty()) {
                    Toast.makeText(context, getString(R.string.toast_no_stl_dxf_here), Toast.LENGTH_SHORT).show()
                }

                val names = entries.map { f ->
                    if (f.isDirectory) {
                        "📁  ${f.name}"
                    } else {
                        val ext  = f.extension.uppercase()
                        val kb   = f.length() / 1024
                        val size = if (kb >= 1024) "${"%.1f".format(kb/1024f)} MB" else "$kb KB"
                        val icon = if (ext == "STL") "🧊" else "📐"
                        "$icon  ${f.name}  •  $size"
                    }
                }

                val adapter = ArrayAdapter(requireContext(),
                    android.R.layout.simple_list_item_1, names)
                listView.adapter = adapter

                listView.setOnItemClickListener { _, _, pos, _ ->
                    val file = entries[pos]
                    if (file.isDirectory) {
                        pathStack.addLast(currentPath)
                        currentPath = file
                        updateBackButton()
                        loadDirectory(file)
                    } else {
                        fileSelectedListener?.onFileSelected(file)
                    }
                }

                updateBackButton()

            } catch (_: CancellationException) {
                // تم إلغاء الـ job — طبيعي
            } catch (e: Exception) {
                if (!isAdded) return@launch
                progressBar.visibility = View.GONE
                Toast.makeText(context, getString(R.string.toast_error_prefix, e.message), Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // عند العودة من إعدادات النظام، تحقق من الإذن وحمّل تلقائياً
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            android.os.Environment.isExternalStorageManager() &&
            listView.adapter == null) {
            loadDirectory(currentPath)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        loadJob?.cancel()
    }
}
