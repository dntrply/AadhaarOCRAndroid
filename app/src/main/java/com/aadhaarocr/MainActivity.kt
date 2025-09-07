package com.aadhaarocr

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.aadhaarocr.databinding.ActivityMainBinding
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
// OpenCV imports temporarily disabled due to disk space issues
// import org.opencv.android.OpenCVLoaderCallback
// import org.opencv.android.LoaderCallbackInterface
// import org.opencv.android.BaseLoaderCallback
// import org.opencv.android.OpenCVLoader

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var ocrProcessor: AadhaarOCRProcessor
    private lateinit var csvExporter: CSVExporter
    private lateinit var workflowManager: WorkflowManager
    
    private var imageCapture: ImageCapture? = null
    private var capturedBitmap: Bitmap? = null
    private var camera: Camera? = null
    private var isFlashOn: Boolean = false
    private var currentWorkflowId: String? = null

    companion object {
        private const val TAG = "MainActivity"
        private val REQUIRED_PERMISSIONS = arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
            Manifest.permission.READ_EXTERNAL_STORAGE
        )
    }

    // OpenCV loader callback temporarily disabled due to disk space issues
    // private val loaderCallback = object : BaseLoaderCallback(this) {
    //     override fun onManagerConnected(status: Int) {
    //         when (status) {
    //             LoaderCallbackInterface.SUCCESS -> {
    //                 Log.d(TAG, "OpenCV loaded successfully")
    //                 // OpenCV is ready to use
    //             }
    //             else -> {
    //                 super.onManagerConnected(status)
    //             }
    //         }
    //     }
    // }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions.values.all { it }
        if (granted) {
            startCamera()
        } else {
            Toast.makeText(
                this,
                getString(R.string.camera_permission_required),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ocrProcessor = AadhaarOCRProcessor()
        csvExporter = CSVExporter(this)
        workflowManager = WorkflowManager(this)
        cameraExecutor = Executors.newSingleThreadExecutor()

        setupClickListeners()
        initializeUI()
        setupWorkflowObservers()
        
        if (allPermissionsGranted()) {
            startCamera()
        } else {
            requestPermissionLauncher.launch(REQUIRED_PERMISSIONS)
        }
    }

    private fun setupWorkflowObservers() {
        // Observe workflow changes and update UI
        lifecycleScope.launch {
            workflowManager.currentWorkflow.collect { workflow ->
                workflow?.let { updateWorkflowUI(it) }
            }
        }
        
        lifecycleScope.launch {
            workflowManager.activeWorkflows.collect { workflows ->
                updateDashboardUI(workflows.values.toList())
            }
        }
    }
    
    private fun updateWorkflowUI(workflow: PatientWorkflowRecord) {
        binding.apply {
            // Show workflow progress card when there's an active workflow
            workflowProgress.root.visibility = View.VISIBLE
            
            // Update workflow progress card
            workflowProgress.apply {
                tvWorkflowId.text = workflow.id.takeLast(8)
                tvProgressStep.text = "Progress: Step ${getStepNumber(workflow.state)} of 7"
                tvProgressPercentage.text = "${workflow.getProgressPercentage()}%"
                progressBar.progress = workflow.getProgressPercentage()
                tvCurrentStepDescription.text = workflow.getCurrentStepDescription()
                
                // Update patient info if available
                if (!workflow.patientName.isNullOrEmpty()) {
                    layoutPatientInfo.visibility = View.VISIBLE
                    tvPatientName.text = "Current: ${workflow.patientName}"
                    tvAadhaarNumber.text = "Aadhaar: ${workflow.aadhaarNumber ?: "Processing..."}"
                } else {
                    layoutPatientInfo.visibility = View.GONE
                }
                
                // Update step indicators
                updateStepIndicators(workflow.state)
                
                // Set up button click listeners for current workflow
                setupWorkflowButtonListeners(workflow)
            }
        }
    }
    
    private fun updateDashboardUI(workflows: List<PatientWorkflowRecord>) {
        val completed = workflows.count { it.state == WorkflowState.COMPLETED }
        val inProgress = workflows.count { it.state != WorkflowState.COMPLETED && it.state != WorkflowState.ERROR }
        val pending = workflows.count { it.state == WorkflowState.PENDING }
        
        binding.workflowDashboard.apply {
            tvCompletedCount.text = completed.toString()
            tvInProgressCount.text = inProgress.toString()
            tvPendingCount.text = pending.toString()
        }
    }
    
    private fun updateStepIndicators(state: WorkflowState) {
        binding.workflowProgress.apply {
            // Reset all icons
            iconCapture.text = "⏳"
            iconProcess.text = "⏳"
            iconExport.text = "⏳"
            iconCopy.text = "⏳"
            iconHmsImport.text = "⏳"
            iconHmsVerify.text = "⏳"
            iconComplete.text = "⏳"
            
            // Update based on current state
            when (state) {
                WorkflowState.CAPTURED, WorkflowState.PROCESSED, WorkflowState.EXPORTED, 
                WorkflowState.COPIED, WorkflowState.HMS_IMPORTED, WorkflowState.HMS_VERIFIED, 
                WorkflowState.COMPLETED -> {
                    iconCapture.text = "✅"
                    textCapture.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.md_theme_light_onSurface))
                }
                WorkflowState.PENDING -> {
                    iconCapture.text = "🔄"
                    textCapture.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.md_theme_light_primary))
                }
                else -> {}
            }
            
            if (state == WorkflowState.PROCESSED || state == WorkflowState.EXPORTED || 
                state == WorkflowState.COPIED || state == WorkflowState.HMS_IMPORTED || 
                state == WorkflowState.HMS_VERIFIED || state == WorkflowState.COMPLETED) {
                iconProcess.text = "✅"
            } else if (state == WorkflowState.CAPTURED) {
                iconProcess.text = "🔄"
                textProcess.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.md_theme_light_primary))
            }
            
            if (state == WorkflowState.EXPORTED || state == WorkflowState.COPIED || 
                state == WorkflowState.HMS_IMPORTED || state == WorkflowState.HMS_VERIFIED || 
                state == WorkflowState.COMPLETED) {
                iconExport.text = "✅"
            } else if (state == WorkflowState.PROCESSED) {
                iconExport.text = "🔄"
                textExport.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.md_theme_light_primary))
            }
            
            if (state == WorkflowState.COPIED || state == WorkflowState.HMS_IMPORTED || 
                state == WorkflowState.HMS_VERIFIED || state == WorkflowState.COMPLETED) {
                iconCopy.text = "✅"
            } else if (state == WorkflowState.EXPORTED) {
                iconCopy.text = "🔄"
                textCopy.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.md_theme_light_primary))
            }
            
            if (state == WorkflowState.HMS_IMPORTED || state == WorkflowState.HMS_VERIFIED || 
                state == WorkflowState.COMPLETED) {
                iconHmsImport.text = "✅"
            } else if (state == WorkflowState.COPIED) {
                iconHmsImport.text = "🔄"
                textHmsImport.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.md_theme_light_primary))
            }
            
            if (state == WorkflowState.HMS_VERIFIED || state == WorkflowState.COMPLETED) {
                iconHmsVerify.text = "✅"
            } else if (state == WorkflowState.HMS_IMPORTED) {
                iconHmsVerify.text = "🔄"
                textHmsVerify.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.md_theme_light_primary))
            }
            
            if (state == WorkflowState.COMPLETED) {
                iconComplete.text = "✅"
            } else if (state == WorkflowState.HMS_VERIFIED) {
                iconComplete.text = "🔄"
                textComplete.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.md_theme_light_primary))
            }
        }
    }
    
    private fun getStepNumber(state: WorkflowState): Int {
        return when (state) {
            WorkflowState.PENDING -> 1
            WorkflowState.CAPTURED -> 2
            WorkflowState.PROCESSED -> 3
            WorkflowState.EXPORTED -> 4
            WorkflowState.COPIED -> 5
            WorkflowState.HMS_IMPORTED -> 6
            WorkflowState.HMS_VERIFIED -> 7
            WorkflowState.COMPLETED -> 7
            WorkflowState.ERROR -> 0
        }
    }
    
    private fun setupWorkflowButtonListeners(workflow: PatientWorkflowRecord) {
        binding.workflowProgress.apply {
            btnViewInstructions.setOnClickListener {
                showStepInstructions(workflow.state)
            }
            
            btnMarkComplete.setOnClickListener {
                markCurrentStepComplete(workflow)
            }
            
            // Update button text based on current step
            updateButtonsForCurrentStep(workflow.state)
        }
    }
    
    private fun updateButtonsForCurrentStep(state: WorkflowState) {
        binding.workflowProgress.apply {
            when (state) {
                WorkflowState.EXPORTED -> {
                    btnViewInstructions.text = "File Copy Instructions"
                    btnMarkComplete.text = "File Copied ✓"
                    btnMarkComplete.isEnabled = true
                }
                WorkflowState.COPIED -> {
                    btnViewInstructions.text = "HMS Import Guide"
                    btnMarkComplete.text = "HMS Imported ✓"
                    btnMarkComplete.isEnabled = true
                }
                WorkflowState.HMS_IMPORTED -> {
                    btnViewInstructions.text = "Verification Steps"
                    btnMarkComplete.text = "Patient Verified ✓"
                    btnMarkComplete.isEnabled = true
                }
                WorkflowState.HMS_VERIFIED -> {
                    btnViewInstructions.text = "Workflow Complete"
                    btnMarkComplete.text = "Finish Workflow"
                    btnMarkComplete.isEnabled = true
                }
                else -> {
                    btnViewInstructions.text = "View Instructions"
                    btnMarkComplete.text = "Mark Complete"
                    btnMarkComplete.isEnabled = false // Disable for automatic steps
                }
            }
        }
    }
    
    private fun showStepInstructions(state: WorkflowState) {
        val (title, instructions) = when (state) {
            WorkflowState.EXPORTED -> "File Copy Instructions" to listOf(
                "1. Open your file manager/explorer",
                "2. Navigate to the Downloads folder", 
                "3. Find the CSV file (named with current date/time)",
                "4. Copy the CSV file to your HMS import directory",
                "5. Common HMS import locations:",
                "   • Desktop/HMS_Import/",
                "   • Documents/HMS/Import/", 
                "   • Network drive (if configured)",
                "6. Verify the file copied successfully",
                "7. Click 'File Copied ✓' when done"
            )
            
            WorkflowState.COPIED -> "HMS Import Guide" to listOf(
                "1. Open your HMS system",
                "2. Navigate to Patient Management",
                "3. Look for 'Import Patients' or 'Bulk Import' option",
                "4. Click 'Browse' or 'Select File'",
                "5. Navigate to where you copied the CSV file",
                "6. Select the CSV file and click 'Open'",
                "7. Click 'Import' or 'Upload' to process",
                "8. Wait for import confirmation message",
                "9. Note any Patient ID generated by HMS",
                "10. Click 'HMS Imported ✓' when complete"
            )
            
            WorkflowState.HMS_IMPORTED -> "Verification Steps" to listOf(
                "1. In HMS, go to Patient Search",
                "2. Search using patient name: ${workflowManager.currentWorkflow.value?.patientName ?: "[Patient Name]"}",
                "3. OR search using Aadhaar: ${workflowManager.currentWorkflow.value?.aadhaarNumber ?: "[Aadhaar Number]"}",
                "4. Verify the patient appears in search results",
                "5. Open the patient record",
                "6. Check that all details match:",
                "   • Name is correct",
                "   • Aadhaar number matches", 
                "   • Gender and DOB are accurate",
                "   • Address information is complete",
                "7. Confirm patient record is accessible and complete",
                "8. Click 'Patient Verified ✓' when confirmed"
            )
            
            WorkflowState.HMS_VERIFIED -> "Workflow Complete" to listOf(
                "🎉 Patient registration workflow complete!",
                "",
                "Summary of completed steps:",
                "✅ Aadhaar card captured and processed",
                "✅ Patient data extracted with OCR",
                "✅ CSV file generated and exported",
                "✅ File copied to HMS import directory",
                "✅ Patient imported to HMS system",
                "✅ Patient record verified in HMS",
                "",
                "The patient is now fully registered!",
                "Click 'Finish Workflow' to complete."
            )
            
            else -> "Instructions" to listOf("No specific instructions for this step.")
        }
        
        showInstructionDialog(title, instructions)
    }
    
    private fun markCurrentStepComplete(workflow: PatientWorkflowRecord) {
        currentWorkflowId?.let { workflowId ->
            when (workflow.state) {
                WorkflowState.EXPORTED -> {
                    // Mark file copy as complete
                    val result = workflowManager.advanceWorkflow(workflowId, WorkflowState.COPIED)
                    if (result is WorkflowResult.Success) {
                        Toast.makeText(this, "✅ File copy marked as complete!", Toast.LENGTH_SHORT).show()
                    }
                }
                
                WorkflowState.COPIED -> {
                    // Mark HMS import as complete
                    val result = workflowManager.advanceWorkflow(workflowId, WorkflowState.HMS_IMPORTED)
                    if (result is WorkflowResult.Success) {
                        Toast.makeText(this, "✅ HMS import marked as complete!", Toast.LENGTH_SHORT).show()
                    }
                }
                
                WorkflowState.HMS_IMPORTED -> {
                    // Mark HMS verification as complete
                    val result = workflowManager.advanceWorkflow(workflowId, WorkflowState.HMS_VERIFIED)
                    if (result is WorkflowResult.Success) {
                        Toast.makeText(this, "✅ Patient verification complete!", Toast.LENGTH_SHORT).show()
                    }
                }
                
                WorkflowState.HMS_VERIFIED -> {
                    // Complete entire workflow
                    val result = workflowManager.completeWorkflow(workflowId)
                    if (result is WorkflowResult.Success) {
                        Toast.makeText(this, "🎉 Workflow completed! Patient fully registered.", Toast.LENGTH_LONG).show()
                        // Reset for next patient
                        currentWorkflowId = null
                    }
                }
                
                else -> {
                    Toast.makeText(this, "This step is completed automatically.", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
    
    private fun showInstructionDialog(title: String, instructions: List<String>) {
        val instructionText = instructions.joinToString("\n")
        
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(instructionText)
            .setPositiveButton("Got It") { dialog, _ -> 
                dialog.dismiss() 
            }
            .setNegativeButton("Close") { dialog, _ -> 
                dialog.dismiss() 
            }
            .show()
    }

    private fun initializeUI() {
        // Clear any previous images and reset UI state
        binding.apply {
            imagePreview.visibility = View.GONE
            cardValidation.visibility = View.GONE
            cardResults.visibility = View.GONE
            cardDebug.visibility = View.GONE
            previewView.visibility = View.GONE
            btnFlash.visibility = View.GONE
            
            btnProcess.isEnabled = false
            btnProcess.text = "Process Image"
            btnProcess.setBackgroundColor(ContextCompat.getColor(this@MainActivity, android.R.color.darker_gray))
            
            btnExportCsv.isEnabled = false
            
            btnCapture.text = "📷 Start Camera"
        }
        capturedBitmap = null
    }

    private fun clearPreviousCapture() {
        // Clear previous image and reset UI state
        capturedBitmap = null
        binding.apply {
            imagePreview.visibility = View.GONE
            cardValidation.visibility = View.GONE
            cardResults.visibility = View.GONE
            cardDebug.visibility = View.GONE
            btnFlash.visibility = View.GONE
            
            btnProcess.isEnabled = false
            btnProcess.text = "Process Image"
            btnProcess.setBackgroundColor(ContextCompat.getColor(this@MainActivity, android.R.color.darker_gray))
            
            btnExportCsv.isEnabled = false
        }
    }
    
    private fun startNewWorkflow() {
        currentWorkflowId = workflowManager.startNewWorkflow()
        Log.d(TAG, "Started new workflow: $currentWorkflowId")
    }

    private fun setupClickListeners() {
        binding.btnCapture.setOnClickListener {
            if (binding.previewView.visibility == View.VISIBLE) {
                capturePhoto()
            } else {
                // Check if this is a retake scenario
                if (binding.btnCapture.text.toString().contains("Retake")) {
                    // Clear previous image when retaking
                    clearPreviousCapture()
                } else {
                    // Start new workflow when starting camera for first time
                    startNewWorkflow()
                }
                startCamera()
                binding.previewView.visibility = View.VISIBLE
                binding.btnFlash.visibility = View.VISIBLE
                binding.btnCapture.text = "📷 Capture Aadhaar"
            }
        }

        binding.btnProcess.setOnClickListener {
            capturedBitmap?.let { bitmap ->
                processImage(bitmap)
            }
        }

        binding.btnExportCsv.setOnClickListener {
            exportCurrentDataToCSV()
        }

        binding.btnFlash.setOnClickListener {
            toggleFlash()
        }
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

            // Preview
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(binding.previewView.surfaceProvider)
            }

            // Image capture
            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()

            // Image analysis for real-time processing (optional)
            val imageAnalyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            // Select back camera as default
            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()
                camera = cameraProvider.bindToLifecycle(
                    this,
                    cameraSelector,
                    preview,
                    imageCapture,
                    imageAnalyzer
                )
                
                // Update flash button based on camera capabilities
                updateFlashButton()
            } catch (exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
            }

        }, ContextCompat.getMainExecutor(this))
    }

    private fun capturePhoto() {
        val imageCapture = imageCapture ?: return

        // Create output options
        val outputOptions = ImageCapture.OutputFileOptions.Builder(
            createTempImageFile()
        ).build()

        // Set up image capture listener
        imageCapture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onError(exception: ImageCaptureException) {
                    Log.e(TAG, "Photo capture failed: ${exception.message}", exception)
                    Toast.makeText(
                        baseContext,
                        "Photo capture failed: ${exception.message}",
                        Toast.LENGTH_SHORT
                    ).show()
                }

                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    // Load the captured image
                    output.savedUri?.let { uri ->
                        contentResolver.openInputStream(uri)?.use { inputStream ->
                            capturedBitmap = BitmapFactory.decodeStream(inputStream)
                            showCapturedImage()
                            
                            // Update workflow state to captured
                            currentWorkflowId?.let { workflowId ->
                                workflowManager.advanceWorkflow(workflowId, WorkflowState.CAPTURED)
                            }
                        }
                    }
                }
            }
        )
    }

    private fun createTempImageFile(): java.io.File {
        return java.io.File(externalCacheDir, "temp_aadhaar_${System.currentTimeMillis()}.jpg")
    }

    private fun showCapturedImage() {
        capturedBitmap?.let { bitmap ->
            binding.apply {
                // Show the captured image
                imagePreview.setImageBitmap(bitmap)
                imagePreview.visibility = View.VISIBLE
                previewView.visibility = View.GONE
                btnFlash.visibility = View.GONE
                
                // Turn off flash when hiding camera preview
                if (isFlashOn) {
                    camera?.cameraControl?.enableTorch(false)
                    isFlashOn = false
                }
                
                // Update capture button to allow retaking
                btnCapture.text = "📷 Retake Photo"
                
                // Make process button very obvious
                btnProcess.isEnabled = true
                btnProcess.text = "✨ PROCESS AADHAAR CARD ✨"
                btnProcess.setBackgroundColor(ContextCompat.getColor(this@MainActivity, android.R.color.holo_green_dark))
                btnProcess.setTextColor(ContextCompat.getColor(this@MainActivity, android.R.color.white))
                
                // Hide previous results
                cardValidation.visibility = View.GONE
                cardResults.visibility = View.GONE
                btnExportCsv.isEnabled = false
            }
            
            // Show helpful toast
            Toast.makeText(
                this,
                "✓ Photo captured! Tap 'PROCESS' to extract data",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun processImage(bitmap: Bitmap) {
        showProcessing(true)
        
        lifecycleScope.launch {
            try {
                val aadhaarData = ocrProcessor.processAadhaarCard(bitmap)
                
                // Update workflow with extracted data
                currentWorkflowId?.let { workflowId ->
                    when (val result = workflowManager.updateWorkflowWithAadhaarData(workflowId, aadhaarData)) {
                        is WorkflowResult.Success -> {
                            displayResults(aadhaarData)
                        }
                        is WorkflowResult.Error -> {
                            Log.e(TAG, "Workflow error: ${result.message}")
                            Toast.makeText(
                                this@MainActivity,
                                "Workflow error: ${result.message}",
                                Toast.LENGTH_LONG
                            ).show()
                            displayResults(aadhaarData) // Still show results even if workflow fails
                        }
                    }
                } ?: run {
                    // No workflow active, just display results
                    displayResults(aadhaarData)
                }
                
                showProcessing(false)
            } catch (e: Exception) {
                Log.e(TAG, "Error processing image", e)
                currentWorkflowId?.let { workflowId ->
                    workflowManager.setWorkflowError(workflowId, "OCR processing failed: ${e.message}")
                }
                Toast.makeText(
                    this@MainActivity,
                    "Error processing image: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
                showProcessing(false)
            }
        }
    }

    private fun showProcessing(isProcessing: Boolean) {
        if (isProcessing) {
            binding.progressBar.visibility = View.VISIBLE
            binding.tvProgress.visibility = View.VISIBLE
            binding.btnProcess.isEnabled = false
        } else {
            binding.progressBar.visibility = View.GONE
            binding.tvProgress.visibility = View.GONE
            binding.btnProcess.isEnabled = capturedBitmap != null
        }
    }

    private fun displayResults(aadhaarData: AadhaarData) {
        binding.apply {
            // Show validation status first
            displayValidationStatus(aadhaarData)
            
            // Only show extracted data if validation passed
            if (aadhaarData.isValidAadhaar) {
                tvName.text = aadhaarData.name.ifEmpty { "Not found" }
                tvGender.text = aadhaarData.gender.ifEmpty { "Not found" }
                tvDob.text = aadhaarData.dob.ifEmpty { "Not found" }
                tvUid.text = aadhaarData.uid.ifEmpty { "Not found" }
                tvAddress.text = aadhaarData.address.ifEmpty { "Not found" }
                
                cardResults.visibility = View.VISIBLE
                btnExportCsv.isEnabled = true
            } else {
                cardResults.visibility = View.GONE
                btnExportCsv.isEnabled = false
                
                // Show suggestion to retake photo
                Toast.makeText(
                    this@MainActivity,
                    "Please capture a clear photo of an Aadhaar card and try again",
                    Toast.LENGTH_LONG
                ).show()
            }
            
            // Always show debug text for troubleshooting
            if (aadhaarData.rawOcrText.isNotEmpty()) {
                val debugContent = if (aadhaarData.languageTaggedText.isNotEmpty()) {
                    "${aadhaarData.rawOcrText}\n\nLANGUAGE TAGGED:\n${aadhaarData.languageTaggedText}"
                } else {
                    aadhaarData.rawOcrText
                }
                tvDebugText.text = debugContent
                cardDebug.visibility = View.VISIBLE
            }
        }
    }
    
    private fun displayValidationStatus(aadhaarData: AadhaarData) {
        binding.apply {
            cardValidation.visibility = View.VISIBLE
            tvValidationMessage.text = aadhaarData.validationMessage
            tvConfidenceScore.text = "Confidence: ${aadhaarData.confidenceScore.toInt()}%"
            progressValidation.progress = aadhaarData.confidenceScore.toInt()
            
            // Set colors and icon based on validation result
            if (aadhaarData.isValidAadhaar) {
                tvValidationMessage.setTextColor(ContextCompat.getColor(this@MainActivity, android.R.color.holo_green_dark))
                progressValidation.progressTintList = ContextCompat.getColorStateList(this@MainActivity, android.R.color.holo_green_dark)
                ivValidationIcon.setImageResource(android.R.drawable.ic_menu_info_details)
                ivValidationIcon.setColorFilter(ContextCompat.getColor(this@MainActivity, android.R.color.holo_green_dark))
            } else {
                tvValidationMessage.setTextColor(ContextCompat.getColor(this@MainActivity, android.R.color.holo_red_dark))
                progressValidation.progressTintList = ContextCompat.getColorStateList(this@MainActivity, android.R.color.holo_red_dark)
                ivValidationIcon.setImageResource(android.R.drawable.ic_dialog_alert)
                ivValidationIcon.setColorFilter(ContextCompat.getColor(this@MainActivity, android.R.color.holo_red_dark))
            }
        }
    }

    private fun exportCurrentDataToCSV() {
        val name = binding.tvName.text.toString().let { if (it == "Not found") "" else it }
        val gender = binding.tvGender.text.toString().let { if (it == "Not found") "" else it }
        val dob = binding.tvDob.text.toString().let { if (it == "Not found") "" else it }
        val uid = binding.tvUid.text.toString().let { if (it == "Not found") "" else it }
        val address = binding.tvAddress.text.toString().let { if (it == "Not found") "" else it }

        val validationMessage = binding.tvValidationMessage.text.toString()
        val confidenceScore = binding.progressValidation.progress.toFloat()
        val rawOcrText = binding.tvDebugText.text.toString()
        val aadhaarData = AadhaarData(name, gender, dob, uid, address, true, confidenceScore, validationMessage, rawOcrText, "")
        
        lifecycleScope.launch {
            csvExporter.exportToCSV(aadhaarData)
                .onSuccess { filePath ->
                    // Update workflow with CSV file path
                    currentWorkflowId?.let { workflowId ->
                        workflowManager.updateCsvFilePath(workflowId, filePath)
                    }
                    
                    Toast.makeText(
                        this@MainActivity,
                        "${getString(R.string.data_exported)}\n$filePath",
                        Toast.LENGTH_LONG
                    ).show()
                }
                .onFailure { exception ->
                    // Set workflow error if export fails
                    currentWorkflowId?.let { workflowId ->
                        workflowManager.setWorkflowError(workflowId, "CSV export failed: ${exception.message}")
                    }
                    
                    Toast.makeText(
                        this@MainActivity,
                        "Export failed: ${exception.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
        }
    }

    private fun toggleFlash() {
        camera?.let { camera ->
            val cameraControl = camera.cameraControl
            val cameraInfo = camera.cameraInfo
            
            if (cameraInfo.hasFlashUnit()) {
                isFlashOn = !isFlashOn
                cameraControl.enableTorch(isFlashOn)
                updateFlashButtonAppearance()
            } else {
                Toast.makeText(this, "Flash not available on this device", Toast.LENGTH_SHORT).show()
            }
        }
    }
    
    private fun updateFlashButton() {
        camera?.let { camera ->
            val hasFlash = camera.cameraInfo.hasFlashUnit()
            binding.btnFlash.visibility = if (hasFlash) View.VISIBLE else View.GONE
            updateFlashButtonAppearance()
        }
    }
    
    private fun updateFlashButtonAppearance() {
        binding.btnFlash.apply {
            text = if (isFlashOn) "💡" else "🔦"
            alpha = if (isFlashOn) 1.0f else 0.7f
        }
    }

    // OpenCV initialization temporarily disabled due to disk space issues
    // override fun onResume() {
    //     super.onResume()
    //     if (!OpenCVLoader.initDebug()) {
    //         Log.d(TAG, "Internal OpenCV library not found. Using OpenCV Manager for initialization")
    //         OpenCVLoader.initAsync(OpenCVLoader.OPENCV_VERSION, this, loaderCallback)
    //     } else {
    //         Log.d(TAG, "OpenCV library found inside package. Using it!")
    //         loaderCallback.onManagerConnected(LoaderCallbackInterface.SUCCESS)
    //     }
    // }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        ocrProcessor.cleanup()
    }
}