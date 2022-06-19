package com.luk.saucenao

import android.app.ProgressDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.provider.MediaStore
import android.util.AttributeSet
import android.util.Log
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatSpinner
import androidx.core.content.FileProvider
import androidx.core.content.res.getTextOrThrow
import androidx.core.util.Pair
import com.afollestad.materialdialogs.MaterialDialog
import com.afollestad.materialdialogs.list.listItemsMultiChoice
import org.jsoup.Connection
import org.jsoup.Jsoup
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.Callable
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private val executorService = Executors.newSingleThreadExecutor()

    private val databasesValues by lazy { resources.getIntArray(R.array.databases_values) }
    private val selectDatabasesSpinner by lazy { findViewById<FauxSpinner>(R.id.select_databases) }
    private val progressDialog by lazy {
        ProgressDialog(this).apply {
            setTitle(R.string.loading_results)
            setMessage(getString(R.string.please_wait))
        }
    }

    private var selectedDatabases = intArrayOf()
        set(value) {
            field = value
            selectDatabasesSpinner.text = when {
                value.isEmpty() -> getString(R.string.all_databases)
                value.size == 1 -> resources.getStringArray(R.array.databases_entries)[value.first()]
                else -> getString(R.string.selected_databases, value.size)
            }
        }

    private var getResultsFromCameraUri: Uri? = null
    private val getResultsFromCamera =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result: ActivityResult? ->
            result?.let {
                if (it.resultCode == RESULT_OK) {
                    waitForResults(getResultsFromCameraUri!!)
                }
            }
        }

    private val getResultsFromFile =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            uri?.let { waitForResults(it) }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val selectImageButton = findViewById<Button>(R.id.select_image)
        selectImageButton.setOnClickListener {
            getResultsFromFile.launch("image/*")
        }

        val cameraButton = findViewById<ImageView>(R.id.camera)
        cameraButton.setOnClickListener {
            getResultsFromCamera.launch(Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
                getResultsFromCameraUri = FileProvider.getUriForFile(
                    this@MainActivity,
                    "${packageName}.FileProvider",
                    getExternalFilesDir(Environment.DIRECTORY_PICTURES)!!.resolve("file.jpg")
                )

                // Grant Uri R/W permissions for applications that handle these activities
                packageManager.queryIntentActivities(this, PackageManager.MATCH_DEFAULT_ONLY)
                    .forEach {
                        grantUriPermission(
                            it.activityInfo.packageName,
                            getResultsFromCameraUri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                        )
                    }

                putExtra(MediaStore.EXTRA_OUTPUT, getResultsFromCameraUri)
            })
        }

        selectDatabasesSpinner.onPerformClick = {
            MaterialDialog(this)
                .title(R.string.select_databases)
                .listItemsMultiChoice(
                    R.array.databases_entries,
                    initialSelection = selectedDatabases,
                    allowEmptySelection = true
                ) { _, ints, _ ->
                    selectedDatabases = ints
                }
                .positiveButton(android.R.string.ok)
                .show()
            true
        }

        if (Intent.ACTION_SEND == intent.action) {
            if (intent.hasExtra(Intent.EXTRA_STREAM)) {
                waitForResults(intent.getParcelableExtra(Intent.EXTRA_STREAM)!!)
            } else if (intent.hasExtra(Intent.EXTRA_TEXT)) {
                waitForResults(intent.getStringExtra(Intent.EXTRA_TEXT)!!)
            }
        }
    }

    private fun waitForResults(data: Any) {
        val future = executorService.submit(GetResultsTask(data))
        progressDialog.setOnCancelListener {
            future.cancel(true)
        }
        progressDialog.show()
    }

    inner class GetResultsTask(private val data: Any?) : Callable<Void?> {
        override fun call(): Void? {
            if (isFinishing) {
                return null
            }

            val result = fetchResult()

            val handler = Handler(mainLooper)
            handler.post { progressDialog.dismiss() }

            when (result.first) {
                REQUEST_RESULT_OK -> {
                    val bundle = Bundle()
                    bundle.putString(ResultsActivity.EXTRA_RESULTS, result.second)

                    val intent = Intent(this@MainActivity, ResultsActivity::class.java)
                    intent.putExtras(bundle)

                    handler.post { startActivity(intent) }
                }
                REQUEST_RESULT_GENERIC_ERROR -> {
                    handler.post {
                        Toast.makeText(
                            this@MainActivity,
                            R.string.error_cannot_load_results,
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
                REQUEST_RESULT_TOO_MANY_REQUESTS -> {
                    handler.post {
                        Toast.makeText(
                            this@MainActivity,
                            R.string.error_too_many_requests,
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }

            return null
        }

        private fun fetchResult(): Pair<Int, String?> {
            try {
                val connection = Jsoup.connect("https://saucenao.com/search.php")
                    .method(Connection.Method.POST)
                    .data("hide", BuildConfig.SAUCENAO_HIDE)
                selectedDatabases.forEach {
                    connection.data("dbs[]", databasesValues[it].toString())
                }

                if (data is Uri) {
                    val stream = ByteArrayOutputStream()
                    try {
                        MediaStore.Images.Media.getBitmap(contentResolver, data)
                            .compress(Bitmap.CompressFormat.PNG, 100, stream)
                    } catch (e: IOException) {
                        Log.e(LOG_TAG, "Unable to read image bitmap", e)
                        return Pair(REQUEST_RESULT_GENERIC_ERROR, null)
                    }
                    connection.data("file", "image.png", ByteArrayInputStream(stream.toByteArray()))
                } else if (data is String) {
                    connection.data("url", data)
                }

                val response = connection.execute()
                if (response.statusCode() != 200) {
                    Log.e(LOG_TAG, "HTTP request returned code: ${response.statusCode()}")
                    return when (response.statusCode()) {
                        429 -> Pair(REQUEST_RESULT_TOO_MANY_REQUESTS, null)
                        else -> Pair(REQUEST_RESULT_GENERIC_ERROR, null)
                    }
                }

                val body = response.body()
                if (body.isEmpty()) {
                    return Pair(REQUEST_RESULT_INTERRUPTED, null)
                }

                return Pair(REQUEST_RESULT_OK, body)
            } catch (e: InterruptedIOException) {
                return Pair(REQUEST_RESULT_INTERRUPTED, null)
            } catch (e: IOException) {
                Log.e(LOG_TAG, "Unable to send HTTP request", e)
                return Pair(REQUEST_RESULT_GENERIC_ERROR, null)
            }
        }
    }

    class FauxSpinner(context: Context, attrs: AttributeSet?) : AppCompatSpinner(context, attrs) {
        var onPerformClick: (() -> Boolean)? = null
        var text: CharSequence? = null
            set(value) {
                field = value
                adapter = ArrayAdapter(
                    context,
                    android.R.layout.simple_spinner_dropdown_item,
                    listOf(value)
                )
            }

        init {
            context.obtainStyledAttributes(attrs, intArrayOf(android.R.attr.text)).let {
                runCatching { text = it.getTextOrThrow(0) }
                it.recycle()
            }
        }

        override fun performClick(): Boolean {
            return when {
                onPerformClick != null -> onPerformClick!!()
                else -> super.performClick()
            }
        }
    }

    companion object {
        private val LOG_TAG = MainActivity::class.java.simpleName

        private const val REQUEST_RESULT_OK = 0
        private const val REQUEST_RESULT_INTERRUPTED = 1
        private const val REQUEST_RESULT_GENERIC_ERROR = 2
        private const val REQUEST_RESULT_TOO_MANY_REQUESTS = 3
    }
}