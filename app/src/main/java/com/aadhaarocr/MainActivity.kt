package com.aadhaarocr

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.aadhaarocr.databinding.ActivityMainBinding
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException

/**
 * Main Activity for the Aadhaar OCR Wizard.
 * Follows a strict step-by-step flow driven by the ViewModel state.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()
    
    private var isCapturingBack = false
    private var currentPhotoUri: android.net.Uri? = null

    companion object {
        private const val TAG = "MainActivity"
        private val REQUIRED_PERMISSIONS = mutableListOf(Manifest.permission.CAMERA).apply {
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }.toTypedArray()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
        
        setupClickListeners()
        setupObservers()
        
        viewModel.startNewWorkflow()
    }

    private fun setupObservers() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.currentWorkflow.collect { workflow ->
                    updateWizardUI(workflow)
                }
            }
        }
    }

    private fun setupClickListeners() {
        binding.btnCapture.setOnClickListener {
            startCamera()
        }

        binding.btnGallery.setOnClickListener {
            isCapturingBack = false
            pickImageLauncher.launch("image/*")
        }
        
        if (BuildConfig.ENABLE_CRASH_BUTTON) {
            binding.btnCrashTest.visibility = View.VISIBLE
            binding.btnCrashTest.setOnClickListener {
                throw RuntimeException("Test Crash - Simulated via Crashlytics Test Button")
            }
        }

        binding.btnNext.setOnClickListener {
            handleNavigation()
        }

        binding.btnBack.setOnClickListener {
            resetWizard()
        }

        binding.btnAbout.setOnClickListener {
            showAboutDialog()
        }
        
        binding.cardResultsInclude.btnCaptureBack.setOnClickListener {
            isCapturingBack = true
            binding.layoutCapture.visibility = View.VISIBLE
            binding.cardResultsInclude.cardResults.visibility = View.GONE
            binding.btnNext.visibility = View.INVISIBLE
            startCamera()
        }

        binding.cardResultsInclude.btnGalleryBack.setOnClickListener {
            isCapturingBack = true
            pickImageLauncher.launch("image/*")
        }
    }

    private fun showAboutDialog() {
        val version = BuildConfig.VERSION_NAME
        val code = BuildConfig.VERSION_CODE
        val releaseNotes = BuildConfig.RELEASE_NOTES

        val message = "Version: $version ($code)\n\nRecent Updates:\n$releaseNotes"

        AlertDialog.Builder(this)
            .setTitle("About Aadhaar OCR")
            .setMessage(message)
            .setPositiveButton("Close", null)
            .show()
    }

    private val pickImageLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let {
            val bitmap = loadAndRotateBitmap(it)
            if (bitmap != null) {
                processImage(bitmap)
            }
        }
    }

    private fun handleNavigation() {
        val workflow = viewModel.currentWorkflow.value
        
        when (workflow.state) {
            AppState.PROCESSED -> {
                val data = getDisplayedData()
                viewModel.exportToCSV(data) { result ->
                    result.onSuccess { 
                        Toast.makeText(this, "CSV Saved Successfully", Toast.LENGTH_SHORT).show()
                    }.onFailure { e ->
                        Toast.makeText(this, "Export Error: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
            AppState.HMS_IMPORTED -> {
                Toast.makeText(this, "Registration Finished!", Toast.LENGTH_SHORT).show()
                resetWizard()
            }
            else -> {}
        }
    }

    private val takePictureLauncher = registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        if (success) {
            currentPhotoUri?.let { uri ->
                try {
                    val fullBitmap = loadAndRotateBitmap(uri)
                    if (fullBitmap != null) {
                        // Scale down the bitmap to avoid OutOfMemoryError and Canvas too large exceptions
                        val maxDimension = 1920f
                        val scale = minOf(maxDimension / fullBitmap.width, maxDimension / fullBitmap.height, 1f)
                        
                        val finalBitmap = if (scale < 1f) {
                            Bitmap.createScaledBitmap(
                                fullBitmap, 
                                (fullBitmap.width * scale).toInt(), 
                                (fullBitmap.height * scale).toInt(), 
                                true
                            )
                        } else {
                            fullBitmap
                        }
                        
                        processImage(finalBitmap)
                    } else {
                        Toast.makeText(this, "Failed to load captured image", Toast.LENGTH_SHORT).show()
                        resetWizard()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error processing captured image", e)
                    Toast.makeText(this, "Failed to process image: ${e.message}", Toast.LENGTH_SHORT).show()
                    resetWizard()
                }
            }
        } else {
            // User cancelled
            resetWizard()
        }
    }

    private fun startCamera() {
        if (!allPermissionsGranted()) {
            requestPermissionLauncher.launch(REQUIRED_PERMISSIONS)
            return
        }
        
        try {
            val photoFile = File.createTempFile(
                "JPEG_${System.currentTimeMillis()}_",
                ".jpg",
                cacheDir
            )
            currentPhotoUri = FileProvider.getUriForFile(
                this,
                "${packageName}.fileprovider",
                photoFile
            )
            takePictureLauncher.launch(currentPhotoUri!!)
        } catch (ex: IOException) {
            Log.e(TAG, "Error creating file for camera", ex)
            Toast.makeText(this, "Error creating temp file", Toast.LENGTH_SHORT).show()
        }
    }

    private fun processImage(bitmap: Bitmap) {
        viewModel.processCapturedBitmap(bitmap, isCapturingBack) {
            isCapturingBack = false
        }
    }

    private fun loadAndRotateBitmap(uri: android.net.Uri): Bitmap? {
        val bitmap = contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream)
        } ?: return null

        var orientation = android.media.ExifInterface.ORIENTATION_NORMAL
        try {
            contentResolver.openInputStream(uri)?.use { stream ->
                val exif = android.media.ExifInterface(stream)
                orientation = exif.getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, android.media.ExifInterface.ORIENTATION_NORMAL)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading EXIF", e)
        }

        val matrix = android.graphics.Matrix()
        when (orientation) {
            android.media.ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            android.media.ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            android.media.ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
        }

        return if (matrix.isIdentity) {
            bitmap
        } else {
            val rotatedBitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            if (rotatedBitmap != bitmap) {
                bitmap.recycle()
            }
            rotatedBitmap
        }
    }

    private fun displayResults(data: AadhaarData) {
        binding.cardResultsInclude.apply {
            cardResults.visibility = View.VISIBLE
            tvName.setText(data.name.ifEmpty { "Not detected" })
            tvGender.setText(data.gender.ifEmpty { "Not detected" })
            tvDob.setText(data.dob.ifEmpty { "Not detected" })
            tvUid.setText(data.uid.ifEmpty { "Not detected" })
            tvAddress.setText(data.address.ifEmpty { "Not detected" })
        }
    }
    
    private fun getDisplayedData(): AadhaarData {
        return AadhaarData(
            name = binding.cardResultsInclude.tvName.text.toString(),
            gender = binding.cardResultsInclude.tvGender.text.toString(),
            dob = binding.cardResultsInclude.tvDob.text.toString(),
            uid = binding.cardResultsInclude.tvUid.text.toString(),
            address = binding.cardResultsInclude.tvAddress.text.toString(),
            isValidAadhaar = true
        )
    }

    private fun updateWizardUI(workflow: AppWorkflow) {
        val state = workflow.state
        Log.d(TAG, "Updating UI for state: $state")

        // Visibility Toggles
        binding.layoutCapture.visibility = if (state == AppState.PENDING || state == AppState.PROCESSING) View.VISIBLE else View.GONE
        binding.loadingOverlay.visibility = if (state == AppState.PROCESSING) View.VISIBLE else View.GONE
        binding.cardResultsInclude.cardResults.visibility = if (state.ordinal >= AppState.PROCESSED.ordinal) View.VISIBLE else View.GONE
        binding.cardInstructions.visibility = if (state.ordinal >= AppState.PROCESSED.ordinal) View.VISIBLE else View.GONE
        
        // Navigation Bar Toggles
        binding.btnBack.visibility = if (state != AppState.PENDING) View.VISIBLE else View.GONE
        binding.btnNext.isEnabled = state.ordinal >= AppState.PROCESSED.ordinal
        binding.btnNext.visibility = if (state == AppState.PROCESSING) View.INVISIBLE else View.VISIBLE

        // Image View Update
        if (state == AppState.PROCESSING || state == AppState.PROCESSED) {
            viewModel.capturedBitmap?.let { 
                binding.imagePreview.setImageBitmap(it)
                binding.imagePreview.visibility = View.VISIBLE
            }
        }

        when (state) {
            AppState.PENDING -> {
                binding.tvCurrentStep.text = "STEP 1: Capture Aadhaar"
                binding.btnNext.text = "PROCEED"
            }
            AppState.PROCESSING -> {
                binding.tvCurrentStep.text = "Processing Image..."
            }
            AppState.PROCESSED -> {
                binding.tvCurrentStep.text = "STEP 2: Preview & Edit"
                binding.btnNext.text = "GENERATE CSV"
                workflow.aadhaarData?.let { 
                    displayResults(it)
                    if (it.address.isBlank() || it.address.contains("Not detected", ignoreCase = true)) {
                        binding.cardResultsInclude.layoutCaptureBack.visibility = View.VISIBLE
                    } else {
                        binding.cardResultsInclude.layoutCaptureBack.visibility = View.GONE
                    }
                }
            }
            AppState.HMS_IMPORTED -> {
                binding.tvCurrentStep.text = "STEP 3: HMS Import"
                binding.btnNext.text = "HMS IMPORTED ✓"
            }
            AppState.COMPLETED -> {
                resetWizard()
            }
        }
    }

    private fun resetWizard() {
        isCapturingBack = false
        viewModel.startNewWorkflow()
        binding.imagePreview.visibility = View.GONE
        binding.btnCapture.text = "📷 CAPTURE PHOTO"
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    private val requestPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
        if (permissions.values.all { it }) {
            startCamera()
        } else {
            Toast.makeText(this, "Camera permissions are required to scan Aadhaar cards.", Toast.LENGTH_LONG).show()
            resetWizard()
        }
    }
}