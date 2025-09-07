 i is it possible to 1. 1.is it# Aadhaar OCR Android

An Android application that extracts patient information from Aadhaar card images using Optical Character Recognition (OCR) and exports the data to CSV format.

## Features

- **Camera Integration**: Capture Aadhaar card images directly using the device camera
- **ML Kit OCR**: Uses Google ML Kit for accurate text recognition
- **Smart Data Parsing**: Intelligently extracts key information:
  - Name
  - Gender (M/F)  
  - Date of Birth
  - Aadhaar Number (UID)
  - Complete Address
- **CSV Export**: Saves extracted data to CSV files in Documents/AadhaarOCR/
- **Modern Android UI**: Material Design 3 components with responsive layout

## Technical Stack

- **Language**: Kotlin
- **Minimum SDK**: 24 (Android 7.0)
- **Target SDK**: 34 (Android 14)
- **OCR Engine**: Google ML Kit Text Recognition
- **Camera**: CameraX
- **UI**: Material Design 3, View Binding
- **Architecture**: MVVM with coroutines

## Prerequisites

- Android Studio Arctic Fox or later
- Android device with camera (API level 24+)
- Camera and storage permissions

## Installation

1. Clone the repository:
```bash
git clone <repository-url>
cd AadhaarOCRAndroid
```

2. Open the project in Android Studio

3. Build and run the project on your Android device

## Usage

1. **Launch the app** and grant camera and storage permissions
2. **Tap "Capture Aadhaar Card"** to open the camera
3. **Position the Aadhaar card** in the camera frame and tap capture
4. **Tap "Process Image"** to extract the data using OCR
5. **Review the extracted information** displayed on screen
6. **Tap "Export to CSV"** to save the data

## Project Structure

```
app/src/main/java/com/aadhaarocr/
├── MainActivity.kt              # Main activity with camera and UI logic
├── AadhaarOCRProcessor.kt       # Core OCR processing and data extraction
├── CSVExporter.kt              # CSV file creation and management
└── AadhaarData.kt              # Data class (included in AadhaarOCRProcessor.kt)

app/src/main/res/
├── layout/
│   └── activity_main.xml       # Main UI layout
├── values/
│   ├── strings.xml            # String resources
│   ├── colors.xml             # Color definitions
│   └── themes.xml             # Material themes
└── xml/
    ├── backup_rules.xml       # Backup configuration
    └── data_extraction_rules.xml # Data extraction rules
```

## Key Components

### AadhaarOCRProcessor
- Converts captured images to text using ML Kit
- Implements the same parsing logic as the original Python version
- Handles name extraction, gender detection, date parsing, and address extraction
- Uses regex patterns optimized for Aadhaar card format

### CSVExporter  
- Creates CSV files in the Documents/AadhaarOCR directory
- Handles proper CSV escaping and formatting
- Appends new records to existing files
- Includes timestamp for each record

### MainActivity
- Manages camera permissions and CameraX integration
- Handles image capture and preview
- Coordinates between OCR processing and UI updates
- Manages the complete user workflow

## Permissions

The app requires the following permissions:
- `CAMERA` - To capture Aadhaar card images
- `READ_EXTERNAL_STORAGE` - To read existing CSV files
- `WRITE_EXTERNAL_STORAGE` - To save CSV export files
- `READ_MEDIA_IMAGES` - For Android 13+ media access

## Output Format

CSV files are saved to: `Documents/AadhaarOCR/aadhaar_data.csv`

Columns:
- Name
- Gender
- DOB (YYYY-MM-DD format)
- UID (12-digit Aadhaar number)
- Address
- Timestamp

## Limitations

- Requires clear, well-lit images of Aadhaar cards
- Works best with standard Aadhaar card layouts
- OCR accuracy depends on image quality and lighting
- Currently optimized for English text extraction

## Security & Privacy

This application processes sensitive personal information. Ensure compliance with:
- Data protection regulations
- Privacy laws in your jurisdiction  
- Secure handling of personal identification documents
- Proper data storage and disposal practices

## Contributing

1. Fork the repository
2. Create a feature branch
3. Make your changes and test thoroughly
4. Create a Pull Request

## License

This project is intended for educational and legitimate administrative purposes only. Users must comply with applicable privacy laws and regulations when processing personal identification documents.

---

**Note**: This application is designed for legitimate document processing purposes. Users are responsible for ensuring compliance with all applicable laws and regulations regarding the processing of personal identification documents.