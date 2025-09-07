# Aadhaar to HMS Workflow Management System

## Overview
A comprehensive workflow system to guide users from Aadhaar card capture to HMS system validation, ensuring no patient records are lost and all steps are completed.

## Current Workflow Problems
1. ❌ **No state tracking** - User forgets progress
2. ❌ **Manual file management** - Files get lost/overwritten
3. ❌ **No duplicate detection** - Same Aadhaar processed twice
4. ❌ **No HMS integration validation** - Can't verify successful import
5. ❌ **No guided process** - User must remember all steps
6. ❌ **No error recovery** - Failed steps restart entire process

## Proposed Solution: Guided Workflow System

### Workflow States
```
PENDING → CAPTURED → PROCESSED → EXPORTED → COPIED → HMS_IMPORTED → HMS_VERIFIED → COMPLETED
    ↓         ↓          ↓          ↓         ↓           ↓            ↓          ↓
 [Start]  [Photo]   [OCR Done] [CSV Ready] [File Mgmt] [HMS Import] [Manual Check] [Done]
```

### Core Components

#### 1. **Workflow State Machine**
```kotlin
enum class WorkflowState {
    PENDING,           // Initial state - ready to capture
    CAPTURED,          // Photo taken, ready to process
    PROCESSED,         // OCR completed, data extracted
    EXPORTED,          // CSV file created
    COPIED,            // File copied to designated location
    HMS_IMPORTED,      // File imported to HMS system
    HMS_VERIFIED,      // Patient verified in HMS system
    COMPLETED,         // Workflow fully complete
    ERROR              // Error state - needs manual intervention
}
```

#### 2. **Patient Record Tracking**
```kotlin
data class PatientWorkflowRecord(
    val id: String,                    // Unique workflow ID
    val aadhaarNumber: String?,        // Extracted Aadhaar number
    val patientName: String?,          // Extracted name
    val state: WorkflowState,          // Current workflow state
    val timestamp: Long,               // Creation time
    val lastModified: Long,            // Last update time
    val csvFilePath: String?,          // Generated CSV file path
    val hmsPatientId: String?,         // HMS system patient ID (after import)
    val errorMessage: String?,         // Error details if any
    val completedSteps: List<WorkflowStep>, // Completed steps
    val nextSteps: List<WorkflowStep>  // Remaining steps
)
```

#### 3. **Step-by-Step Guidance**
```kotlin
data class WorkflowStep(
    val id: String,
    val title: String,
    val description: String,
    val instructions: List<String>,
    val isCompleted: Boolean,
    val isSkippable: Boolean,
    val estimatedDuration: String,
    val validationRequired: Boolean
)
```

## Detailed Workflow Implementation

### Phase 1: Enhanced Capture & Processing
```
1. PENDING → CAPTURED
   ✓ Show "Ready to capture next Aadhaar" screen
   ✓ Display workflow progress (Step 1 of 7)
   ✓ Check for duplicate Aadhaar numbers before capture
   
2. CAPTURED → PROCESSED  
   ✓ Automatic OCR processing
   ✓ Data validation and confidence scoring
   ✓ Allow manual correction of extracted data
   ✓ Generate unique Patient Workflow ID
```

### Phase 2: Export & File Management
```
3. PROCESSED → EXPORTED
   ✓ Generate CSV with workflow metadata
   ✓ Unique filename: "aadhaar_YYYYMMDD_HHMMSS_[WorkflowID].csv"
   ✓ Show export success confirmation
   
4. EXPORTED → COPIED
   ✓ Guided file copy process
   ✓ Show target directory selection
   ✓ Verify file copied successfully
   ✓ Display copy confirmation with full path
```

### Phase 3: HMS Integration & Verification
```
5. COPIED → HMS_IMPORTED
   ✓ HMS import instructions with screenshots
   ✓ Import checklist for user to follow
   ✓ Manual confirmation: "File imported to HMS"
   ✓ Optional: HMS patient ID input field
   
6. HMS_IMPORTED → HMS_VERIFIED
   ✓ HMS verification checklist
   ✓ Instructions: "Search for patient in HMS using [Name/Aadhaar]"
   ✓ Verification questions:
     - "Can you find the patient in HMS?"
     - "Does the name match?"
     - "Does the Aadhaar number match?"
   ✓ Manual confirmation: "Patient verified in HMS"
   
7. HMS_VERIFIED → COMPLETED
   ✓ Success confirmation
   ✓ Workflow completion timestamp
   ✓ Ready for next Aadhaar card
```

## User Interface Design

### Main Workflow Screen
```
┌─────────────────────────────────────┐
│ 📋 Patient Registration Workflow    │
├─────────────────────────────────────┤
│ Current: Rohit Kumar                │
│ Aadhaar: 1234 5678 9012            │
│ Progress: Step 5 of 7               │
│                                     │
│ ████████████░░░░░ 71% Complete      │
│                                     │
│ ✅ Photo Captured                   │
│ ✅ Data Processed                   │
│ ✅ CSV Generated                    │
│ ✅ File Copied                      │
│ 🔄 HMS Import (In Progress)         │
│ ⏳ HMS Verification                 │
│ ⏳ Complete                         │
│                                     │
│ Next: Import CSV to HMS System      │
│ [View Instructions] [Mark Complete] │
└─────────────────────────────────────┘
```

### Workflow Dashboard
```
┌─────────────────────────────────────┐
│ 📊 Today's Progress                 │
├─────────────────────────────────────┤
│ Completed: 12 patients              │
│ In Progress: 3 patients             │
│ Pending: 1 patient                  │
│                                     │
│ Recent Activity:                    │
│ • Rohit Kumar - HMS Verification   │
│ • Priya Singh - Completed ✅        │
│ • Amit Patel - File Copy            │
│                                     │
│ [View All Records] [Export Report]  │
└─────────────────────────────────────┘
```

## Technical Implementation

### 1. **Workflow Manager Class**
```kotlin
class WorkflowManager(private val context: Context) {
    
    fun startNewWorkflow(): String {
        // Generate unique workflow ID
        // Initialize patient record
        // Set state to PENDING
    }
    
    fun advanceWorkflow(workflowId: String, newState: WorkflowState) {
        // Update workflow state
        // Log transition
        // Update UI
        // Check for next steps
    }
    
    fun getCurrentWorkflows(): List<PatientWorkflowRecord> {
        // Return all active workflows
    }
    
    fun validateStep(workflowId: String, stepId: String): Boolean {
        // Validate step completion
        // Return validation result
    }
}
```

### 2. **Workflow Database Schema**
```sql
CREATE TABLE patient_workflows (
    id TEXT PRIMARY KEY,
    aadhaar_number TEXT UNIQUE,
    patient_name TEXT,
    state TEXT NOT NULL,
    created_timestamp INTEGER,
    modified_timestamp INTEGER,
    csv_file_path TEXT,
    hms_patient_id TEXT,
    error_message TEXT,
    workflow_data TEXT -- JSON for additional data
);

CREATE TABLE workflow_steps (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    workflow_id TEXT,
    step_name TEXT,
    completed_timestamp INTEGER,
    notes TEXT,
    FOREIGN KEY(workflow_id) REFERENCES patient_workflows(id)
);
```

### 3. **Error Handling & Recovery**
- **Duplicate Detection**: Check existing Aadhaar numbers before processing
- **File Conflicts**: Handle CSV filename collisions automatically  
- **Workflow Recovery**: Resume interrupted workflows
- **Error States**: Clear error handling with recovery options

### 4. **Quality Assurance Features**
- **Audit Trail**: Complete log of all workflow steps
- **Data Validation**: Multiple validation checkpoints
- **Manual Override**: Allow admin users to skip/reset steps
- **Reporting**: Daily/weekly workflow completion reports

## Integration Points

### HMS System Integration (Future)
```kotlin
interface HMSIntegration {
    suspend fun importPatientCSV(filePath: String): HMSImportResult
    suspend fun searchPatient(aadhaarNumber: String): Patient?
    suspend fun validatePatientExists(aadhaarNumber: String): Boolean
}
```

### File Management Integration
```kotlin
class FileManager {
    fun copyToHMSDirectory(csvFile: File): Result<File>
    fun validateCopySuccess(originalFile: File, copiedFile: File): Boolean
    fun generateUniqueFileName(patientName: String, workflowId: String): String
}
```

## Benefits of This System

### For Users
1. **Guided Process** - Never forget a step
2. **Progress Tracking** - See exactly where you are
3. **Error Prevention** - Catch mistakes early
4. **Confidence** - Verify each step is complete

### For Operations
1. **No Lost Records** - Every Aadhaar is tracked
2. **Audit Trail** - Complete workflow history
3. **Quality Control** - Multiple validation points
4. **Reporting** - Track efficiency and errors

### for HMS Integration
1. **Clean Data** - Validated before import
2. **No Duplicates** - Prevent duplicate patients
3. **Traceability** - Link back to original Aadhaar
4. **Batch Processing** - Handle multiple imports

This system transforms the current manual, error-prone process into a guided, trackable, and reliable workflow that ensures every Aadhaar card results in a properly registered patient in the HMS system.