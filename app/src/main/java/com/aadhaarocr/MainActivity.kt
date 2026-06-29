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
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.aadhaarocr.databinding.ActivityMainBinding
import kotlinx.coroutines.launch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Main Activity for the Aadhaar OCR Wizard.
 * Follows a strict step-by-step flow driven by the ViewModel state.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var cameraExecutor: ExecutorService
    private val viewModel: MainViewModel by viewModels()
    
    private var imageCapture: ImageCapture? = null

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

        cameraExecutor = Executors.newSingleThreadExecutor()
        
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
            if (binding.previewView.visibility == View.VISIBLE) {
                capturePhoto()
            } else {
                startCamera()
                binding.previewView.visibility = View.VISIBLE
                binding.imagePreview.visibility = View.GONE
                binding.btnCapture.text = "📸 CLICK NOW"
            }
        }

        binding.btnGallery.setOnClickListener {
            pickImageLauncher.launch("image/*")
        }

        binding.btnNext.setOnClickListener {
            handleNavigation()
        }

        binding.btnBack.setOnClickListener {
            resetWizard()
        }
    }

    private val pickImageLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let {
            contentResolver.openInputStream(it)?.use { stream ->
                val bitmap = BitmapFactory.decodeStream(stream)
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

    private fun startCamera() {
        if (!allPermissionsGranted()) {
            requestPermissionLauncher.launch(REQUIRED_PERMISSIONS)
            return
        }
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val cameraProvider = providerFuture.get()
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(binding.previewView.surfaceProvider) }
            imageCapture = ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build()
            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture)
            } catch (e: Exception) { Log.e(TAG, "Camera failed", e) }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun capturePhoto() {
        val capture = imageCapture ?: return
        capture.takePicture(ContextCompat.getMainExecutor(this), object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                val bitmap = image.toBitmap()
                image.close()
                processImage(bitmap)
            }
        })
    }

    private fun processImage(bitmap: Bitmap) {
        viewModel.processCapturedBitmap(bitmap) {
            // Callback handles any additional logic after processing, if needed.
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
                binding.previewView.visibility = View.GONE
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
                binding.tvManualInstructions.text = "Verify the extraction. Edit the fields if needed, or click RETAKE. Then click GENERATE CSV."
                workflow.aadhaarData?.let { displayResults(it) }
            }
            AppState.HMS_IMPORTED -> {
                binding.tvCurrentStep.text = "STEP 3: HMS Import"
                binding.btnNext.text = "HMS IMPORTED ✓"
                binding.tvManualInstructions.text = "CSV generated at ${workflow.csvPath}. Import it to HMS system and click HMS IMPORTED."
            }
            AppState.COMPLETED -> {
                resetWizard()
            }
        }
    }

    private fun resetWizard() {
        viewModel.startNewWorkflow()
        binding.imagePreview.visibility = View.GONE
        binding.previewView.visibility = View.GONE
        binding.btnCapture.text = "📷 CAPTURE PHOTO"
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    private val requestPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
        if (permissions.values.all { it }) startCamera()
    }
}