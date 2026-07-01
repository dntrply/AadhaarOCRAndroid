package com.aadhaarocr

import android.app.Application
import android.graphics.Bitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class AppState {
    PENDING,
    PROCESSING,
    PROCESSED,
    HMS_IMPORTED,
    COMPLETED
}

data class AppWorkflow(
    val state: AppState = AppState.PENDING,
    val aadhaarData: AadhaarData? = null,
    val csvPath: String? = null
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    
    private val ocrProcessor = AadhaarOCRProcessor()
    private val csvExporter = CSVExporter(application)
    
    private val _currentWorkflow = MutableStateFlow(AppWorkflow())
    val currentWorkflow: StateFlow<AppWorkflow> = _currentWorkflow.asStateFlow()
    
    private var _capturedBitmap: Bitmap? = null
    val capturedBitmap: Bitmap? get() = _capturedBitmap

    fun startNewWorkflow() {
        _capturedBitmap = null
        _currentWorkflow.value = AppWorkflow(state = AppState.PENDING)
    }

    fun processCapturedBitmap(bitmap: Bitmap, isBackOfCard: Boolean = false, onResult: () -> Unit) {
        _capturedBitmap = bitmap
        val existingData = _currentWorkflow.value.aadhaarData
        _currentWorkflow.value = _currentWorkflow.value.copy(state = AppState.PROCESSING)
        
        viewModelScope.launch {
            try {
                val extractedData = withContext(Dispatchers.Default) {
                    ocrProcessor.processAadhaarCard(bitmap, isBackOfCard)
                }
                
                val finalData = if (isBackOfCard && existingData != null) {
                    // Merge: Keep front demographics, only take Address from back image
                    existingData.copy(
                        address = extractedData.address,
                        rawOcrText = existingData.rawOcrText + "\n---BACK---\n" + extractedData.rawOcrText
                    )
                } else {
                    extractedData
                }

                _currentWorkflow.value = _currentWorkflow.value.copy(
                    state = AppState.PROCESSED,
                    aadhaarData = finalData
                )
            } catch (e: Exception) {
                _currentWorkflow.value = _currentWorkflow.value.copy(
                    state = AppState.PROCESSED,
                    aadhaarData = existingData ?: AadhaarData(
                        isValidAadhaar = false,
                        validationMessage = "Processing failed: ${e.message}",
                        rawOcrText = "Error: ${e.localizedMessage}"
                    )
                )
            }
            onResult()
        }
    }

    fun exportToCSV(aadhaarData: AadhaarData, onResult: (Result<String>) -> Unit) {
        viewModelScope.launch {
            val result = csvExporter.exportToCSV(aadhaarData)
            result.onSuccess { path ->
                _currentWorkflow.value = _currentWorkflow.value.copy(
                    state = AppState.HMS_IMPORTED,
                    csvPath = path
                )
            }
            onResult(result)
        }
    }

    fun advanceWorkflow(newState: AppState) {
        _currentWorkflow.value = _currentWorkflow.value.copy(state = newState)
    }

    override fun onCleared() {
        super.onCleared()
        ocrProcessor.cleanup()
    }
}