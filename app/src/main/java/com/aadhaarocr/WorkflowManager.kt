package com.aadhaarocr

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.*

/**
 * Workflow states for patient registration process
 */
enum class WorkflowState {
    PENDING,           // Ready to capture Aadhaar
    CAPTURED,          // Photo taken, ready for OCR
    PROCESSED,         // OCR completed, data extracted
    EXPORTED,          // CSV file generated
    COPIED,            // File copied to target directory
    HMS_IMPORTED,      // File imported to HMS system
    HMS_VERIFIED,      // Patient verified in HMS
    COMPLETED,         // Workflow complete
    ERROR              // Error state requiring intervention
}

/**
 * Individual workflow step definition
 */
data class WorkflowStep(
    val id: String,
    val title: String,
    val description: String,
    val instructions: List<String>,
    val isCompleted: Boolean = false,
    val isSkippable: Boolean = false,
    val estimatedDuration: String = "1-2 minutes",
    val validationRequired: Boolean = false
)

/**
 * Complete patient workflow record
 */
data class PatientWorkflowRecord(
    val id: String,                    // Unique workflow ID
    val aadhaarNumber: String?,        // Extracted Aadhaar number
    val patientName: String?,          // Extracted patient name
    val patientGender: String?,        // Patient gender
    val patientDOB: String?,          // Patient date of birth
    val patientAddress: String?,       // Patient address
    val state: WorkflowState,          // Current workflow state
    val createdTimestamp: Long,        // Creation time
    val lastModified: Long,            // Last update time
    val csvFilePath: String?,          // Generated CSV file path
    val copiedFilePath: String?,       // Copied file location
    val hmsPatientId: String?,         // HMS system patient ID
    val errorMessage: String?,         // Error details if any
    val completedSteps: List<String> = emptyList(), // Completed step IDs
    val confidenceScore: Float = 0.0f, // OCR confidence score
    val rawOcrText: String? = null     // Original OCR text for debugging
) {
    /**
     * Get the next workflow state based on current state
     */
    fun getNextState(): WorkflowState? {
        return when (state) {
            WorkflowState.PENDING -> WorkflowState.CAPTURED
            WorkflowState.CAPTURED -> WorkflowState.PROCESSED
            WorkflowState.PROCESSED -> WorkflowState.EXPORTED
            WorkflowState.EXPORTED -> WorkflowState.COPIED
            WorkflowState.COPIED -> WorkflowState.HMS_IMPORTED
            WorkflowState.HMS_IMPORTED -> WorkflowState.HMS_VERIFIED
            WorkflowState.HMS_VERIFIED -> WorkflowState.COMPLETED
            WorkflowState.COMPLETED -> null // No next state
            WorkflowState.ERROR -> null // Requires manual intervention
        }
    }
    
    /**
     * Calculate workflow progress percentage
     */
    fun getProgressPercentage(): Int {
        return when (state) {
            WorkflowState.PENDING -> 0
            WorkflowState.CAPTURED -> 14
            WorkflowState.PROCESSED -> 28
            WorkflowState.EXPORTED -> 42
            WorkflowState.COPIED -> 57
            WorkflowState.HMS_IMPORTED -> 71
            WorkflowState.HMS_VERIFIED -> 85
            WorkflowState.COMPLETED -> 100
            WorkflowState.ERROR -> 0
        }
    }
    
    /**
     * Get human-readable current step description
     */
    fun getCurrentStepDescription(): String {
        return when (state) {
            WorkflowState.PENDING -> "Ready to capture Aadhaar card"
            WorkflowState.CAPTURED -> "Processing captured image..."
            WorkflowState.PROCESSED -> "Generating CSV file..."
            WorkflowState.EXPORTED -> "Copy CSV file to HMS directory"
            WorkflowState.COPIED -> "Import CSV file to HMS system"
            WorkflowState.HMS_IMPORTED -> "Verify patient in HMS system"
            WorkflowState.HMS_VERIFIED -> "Completing workflow..."
            WorkflowState.COMPLETED -> "Patient registration complete"
            WorkflowState.ERROR -> "Error - manual intervention required"
        }
    }
}

/**
 * Result of workflow operation
 */
sealed class WorkflowResult<out T> {
    data class Success<T>(val data: T) : WorkflowResult<T>()
    data class Error(val message: String, val throwable: Throwable? = null) : WorkflowResult<Nothing>()
}

/**
 * Manages the complete patient registration workflow
 */
class WorkflowManager(private val context: Context) {
    
    companion object {
        private const val TAG = "WorkflowManager"
        
        // Workflow step definitions
        val WORKFLOW_STEPS = mapOf(
            "capture" to WorkflowStep(
                id = "capture",
                title = "Capture Aadhaar Card",
                description = "Take a clear photo of the Aadhaar card",
                instructions = listOf(
                    "Ensure good lighting",
                    "Keep the card flat and straight",
                    "Make sure all text is clearly visible",
                    "Avoid shadows and glare"
                ),
                estimatedDuration = "30 seconds"
            ),
            "process" to WorkflowStep(
                id = "process",
                title = "Process & Validate",
                description = "Extract and validate patient information",
                instructions = listOf(
                    "Review extracted information",
                    "Correct any errors if needed",
                    "Verify confidence score is acceptable",
                    "Confirm all fields are filled"
                ),
                validationRequired = true,
                estimatedDuration = "1-2 minutes"
            ),
            "export" to WorkflowStep(
                id = "export",
                title = "Generate CSV File",
                description = "Create CSV file for HMS import",
                instructions = listOf(
                    "CSV file will be automatically generated",
                    "File includes all patient information",
                    "Unique filename prevents conflicts",
                    "File saved to app's export directory"
                ),
                estimatedDuration = "10 seconds"
            ),
            "copy" to WorkflowStep(
                id = "copy",
                title = "Copy to HMS Directory",
                description = "Copy CSV file to HMS import location",
                instructions = listOf(
                    "Navigate to HMS import folder",
                    "Copy the generated CSV file",
                    "Verify file copied successfully",
                    "Note the copied file location"
                ),
                validationRequired = true,
                estimatedDuration = "1 minute"
            ),
            "hms_import" to WorkflowStep(
                id = "hms_import",
                title = "Import to HMS",
                description = "Import CSV file into HMS system",
                instructions = listOf(
                    "Open HMS system",
                    "Navigate to patient import function",
                    "Select the copied CSV file",
                    "Execute import and wait for completion",
                    "Note any HMS patient ID generated"
                ),
                validationRequired = true,
                estimatedDuration = "2-3 minutes"
            ),
            "hms_verify" to WorkflowStep(
                id = "hms_verify",
                title = "Verify in HMS",
                description = "Confirm patient exists in HMS system",
                instructions = listOf(
                    "Search for patient in HMS using name or Aadhaar",
                    "Verify all details match the original card",
                    "Check that Aadhaar number is correct",
                    "Confirm patient record is complete and accessible"
                ),
                validationRequired = true,
                estimatedDuration = "1-2 minutes"
            )
        )
    }
    
    // In-memory storage for active workflows (could be replaced with database)
    private val _activeWorkflows = MutableStateFlow<Map<String, PatientWorkflowRecord>>(emptyMap())
    val activeWorkflows: StateFlow<Map<String, PatientWorkflowRecord>> = _activeWorkflows.asStateFlow()
    
    private val _currentWorkflow = MutableStateFlow<PatientWorkflowRecord?>(null)
    val currentWorkflow: StateFlow<PatientWorkflowRecord?> = _currentWorkflow.asStateFlow()
    
    /**
     * Start a new patient workflow
     */
    fun startNewWorkflow(): String {
        val workflowId = "WF_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(8)}"
        val timestamp = System.currentTimeMillis()
        
        val newRecord = PatientWorkflowRecord(
            id = workflowId,
            aadhaarNumber = null,
            patientName = null,
            patientGender = null,
            patientDOB = null,
            patientAddress = null,
            state = WorkflowState.PENDING,
            createdTimestamp = timestamp,
            lastModified = timestamp,
            csvFilePath = null,
            copiedFilePath = null,
            hmsPatientId = null,
            errorMessage = null
        )
        
        // Add to active workflows
        val current = _activeWorkflows.value.toMutableMap()
        current[workflowId] = newRecord
        _activeWorkflows.value = current
        
        // Set as current workflow
        _currentWorkflow.value = newRecord
        
        Log.d(TAG, "Started new workflow: $workflowId")
        return workflowId
    }
    
    /**
     * Update workflow with Aadhaar data after OCR processing
     */
    fun updateWorkflowWithAadhaarData(
        workflowId: String,
        aadhaarData: AadhaarData
    ): WorkflowResult<PatientWorkflowRecord> {
        return try {
            val current = _activeWorkflows.value[workflowId]
                ?: return WorkflowResult.Error("Workflow not found: $workflowId")
            
            // Check for duplicate Aadhaar number
            if (!aadhaarData.uid.isNullOrEmpty()) {
                val duplicate = findDuplicateAadhaar(aadhaarData.uid, workflowId)
                if (duplicate != null) {
                    return WorkflowResult.Error(
                        "Duplicate Aadhaar number found. Patient '${duplicate.patientName}' " +
                        "was already processed on ${formatTimestamp(duplicate.createdTimestamp)}"
                    )
                }
            }
            
            val updated = current.copy(
                aadhaarNumber = aadhaarData.uid,
                patientName = aadhaarData.name,
                patientGender = aadhaarData.gender,
                patientDOB = aadhaarData.dob,
                patientAddress = aadhaarData.address,
                state = WorkflowState.PROCESSED,
                lastModified = System.currentTimeMillis(),
                confidenceScore = aadhaarData.confidenceScore,
                rawOcrText = aadhaarData.rawOcrText,
                completedSteps = current.completedSteps + "capture" + "process"
            )
            
            updateWorkflowRecord(updated)
            WorkflowResult.Success(updated)
        } catch (e: Exception) {
            Log.e(TAG, "Error updating workflow with Aadhaar data", e)
            WorkflowResult.Error("Failed to update workflow: ${e.message}", e)
        }
    }
    
    /**
     * Advance workflow to next state
     */
    fun advanceWorkflow(workflowId: String, newState: WorkflowState): WorkflowResult<PatientWorkflowRecord> {
        return try {
            val current = _activeWorkflows.value[workflowId]
                ?: return WorkflowResult.Error("Workflow not found: $workflowId")
            
            val updated = current.copy(
                state = newState,
                lastModified = System.currentTimeMillis()
            )
            
            updateWorkflowRecord(updated)
            
            Log.d(TAG, "Advanced workflow $workflowId from ${current.state} to $newState")
            WorkflowResult.Success(updated)
        } catch (e: Exception) {
            Log.e(TAG, "Error advancing workflow", e)
            WorkflowResult.Error("Failed to advance workflow: ${e.message}", e)
        }
    }
    
    /**
     * Mark workflow step as completed
     */
    fun completeWorkflowStep(workflowId: String, stepId: String, notes: String? = null): WorkflowResult<Unit> {
        return try {
            val current = _activeWorkflows.value[workflowId]
                ?: return WorkflowResult.Error("Workflow not found: $workflowId")
            
            if (!current.completedSteps.contains(stepId)) {
                val updated = current.copy(
                    completedSteps = current.completedSteps + stepId,
                    lastModified = System.currentTimeMillis()
                )
                
                updateWorkflowRecord(updated)
                Log.d(TAG, "Completed step '$stepId' for workflow $workflowId")
            }
            
            WorkflowResult.Success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Error completing workflow step", e)
            WorkflowResult.Error("Failed to complete step: ${e.message}", e)
        }
    }
    
    /**
     * Set workflow to error state
     */
    fun setWorkflowError(workflowId: String, errorMessage: String): WorkflowResult<PatientWorkflowRecord> {
        return try {
            val current = _activeWorkflows.value[workflowId]
                ?: return WorkflowResult.Error("Workflow not found: $workflowId")
            
            val updated = current.copy(
                state = WorkflowState.ERROR,
                errorMessage = errorMessage,
                lastModified = System.currentTimeMillis()
            )
            
            updateWorkflowRecord(updated)
            
            Log.e(TAG, "Set workflow $workflowId to error state: $errorMessage")
            WorkflowResult.Success(updated)
        } catch (e: Exception) {
            Log.e(TAG, "Error setting workflow error", e)
            WorkflowResult.Error("Failed to set error state: ${e.message}", e)
        }
    }
    
    /**
     * Update CSV file path for workflow
     */
    fun updateCsvFilePath(workflowId: String, csvFilePath: String): WorkflowResult<PatientWorkflowRecord> {
        return try {
            val current = _activeWorkflows.value[workflowId]
                ?: return WorkflowResult.Error("Workflow not found: $workflowId")
            
            val updated = current.copy(
                csvFilePath = csvFilePath,
                state = WorkflowState.EXPORTED,
                lastModified = System.currentTimeMillis(),
                completedSteps = current.completedSteps + "export"
            )
            
            updateWorkflowRecord(updated)
            WorkflowResult.Success(updated)
        } catch (e: Exception) {
            Log.e(TAG, "Error updating CSV file path", e)
            WorkflowResult.Error("Failed to update CSV path: ${e.message}", e)
        }
    }
    
    /**
     * Update copied file path for workflow
     */
    fun updateCopiedFilePath(workflowId: String, copiedFilePath: String): WorkflowResult<PatientWorkflowRecord> {
        return try {
            val current = _activeWorkflows.value[workflowId]
                ?: return WorkflowResult.Error("Workflow not found: $workflowId")
            
            val updated = current.copy(
                copiedFilePath = copiedFilePath,
                state = WorkflowState.COPIED,
                lastModified = System.currentTimeMillis(),
                completedSteps = current.completedSteps + "copy"
            )
            
            updateWorkflowRecord(updated)
            WorkflowResult.Success(updated)
        } catch (e: Exception) {
            Log.e(TAG, "Error updating copied file path", e)
            WorkflowResult.Error("Failed to update copied path: ${e.message}", e)
        }
    }
    
    /**
     * Update HMS patient ID for workflow
     */
    fun updateHmsPatientId(workflowId: String, hmsPatientId: String): WorkflowResult<PatientWorkflowRecord> {
        return try {
            val current = _activeWorkflows.value[workflowId]
                ?: return WorkflowResult.Error("Workflow not found: $workflowId")
            
            val updated = current.copy(
                hmsPatientId = hmsPatientId,
                lastModified = System.currentTimeMillis()
            )
            
            updateWorkflowRecord(updated)
            WorkflowResult.Success(updated)
        } catch (e: Exception) {
            Log.e(TAG, "Error updating HMS patient ID", e)
            WorkflowResult.Error("Failed to update HMS patient ID: ${e.message}", e)
        }
    }
    
    /**
     * Complete entire workflow
     */
    fun completeWorkflow(workflowId: String): WorkflowResult<PatientWorkflowRecord> {
        return try {
            val current = _activeWorkflows.value[workflowId]
                ?: return WorkflowResult.Error("Workflow not found: $workflowId")
            
            val updated = current.copy(
                state = WorkflowState.COMPLETED,
                lastModified = System.currentTimeMillis(),
                completedSteps = WORKFLOW_STEPS.keys.toList() // Mark all steps complete
            )
            
            updateWorkflowRecord(updated)
            
            // Clear current workflow if this was the active one
            if (_currentWorkflow.value?.id == workflowId) {
                _currentWorkflow.value = null
            }
            
            Log.d(TAG, "Completed workflow $workflowId")
            WorkflowResult.Success(updated)
        } catch (e: Exception) {
            Log.e(TAG, "Error completing workflow", e)
            WorkflowResult.Error("Failed to complete workflow: ${e.message}", e)
        }
    }
    
    /**
     * Get workflow by ID
     */
    fun getWorkflow(workflowId: String): PatientWorkflowRecord? {
        return _activeWorkflows.value[workflowId]
    }
    
    /**
     * Get all workflows in a specific state
     */
    fun getWorkflowsByState(state: WorkflowState): List<PatientWorkflowRecord> {
        return _activeWorkflows.value.values.filter { it.state == state }
    }
    
    /**
     * Get today's completed workflows count
     */
    fun getTodayCompletedCount(): Int {
        val todayStart = getTodayStartTimestamp()
        return _activeWorkflows.value.values.count { 
            it.state == WorkflowState.COMPLETED && it.lastModified >= todayStart 
        }
    }
    
    /**
     * Get workflows in progress count
     */
    fun getInProgressCount(): Int {
        return _activeWorkflows.value.values.count { 
            it.state != WorkflowState.COMPLETED && it.state != WorkflowState.ERROR 
        }
    }
    
    /**
     * Find duplicate Aadhaar number (excluding current workflow)
     */
    private fun findDuplicateAadhaar(aadhaarNumber: String, excludeWorkflowId: String): PatientWorkflowRecord? {
        return _activeWorkflows.value.values.find { 
            it.id != excludeWorkflowId && it.aadhaarNumber == aadhaarNumber 
        }
    }
    
    /**
     * Update workflow record in storage
     */
    private fun updateWorkflowRecord(record: PatientWorkflowRecord) {
        val current = _activeWorkflows.value.toMutableMap()
        current[record.id] = record
        _activeWorkflows.value = current
        
        // Update current workflow if it's the active one
        if (_currentWorkflow.value?.id == record.id) {
            _currentWorkflow.value = record
        }
    }
    
    /**
     * Helper function to format timestamp
     */
    private fun formatTimestamp(timestamp: Long): String {
        val date = Date(timestamp)
        return java.text.SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault()).format(date)
    }
    
    /**
     * Get start of today timestamp
     */
    private fun getTodayStartTimestamp(): Long {
        val calendar = Calendar.getInstance()
        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        return calendar.timeInMillis
    }
}