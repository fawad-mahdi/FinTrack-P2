# Android App Assets

This directory contains static assets that are bundled into the APK and will be available at runtime.

## Contents

### Web Frontend
- **index.html** - The complete FinTrack PK web interface
  - Single-page application with embedded CSS and JavaScript
  - Connects to the FastAPI server running on localhost:8000
  - Includes all features: transactions, budgets, reports, category rules
  - Responsive design optimized for mobile screens

### OAuth Configuration
- **credentials.json.example** - Template for Gmail OAuth configuration
  - Users must replace this with their actual credentials.json from Google Cloud Console
  - Required for Gmail sync functionality
  - Contains client_id, client_secret, and OAuth endpoints

## Python Backend

The Python backend files are located in `src/main/python/` and include:

### Core Server Files
- **server.py** - FastAPI application with all API endpoints
- **main.py** - Entry point for the Python server (Uvicorn)
- **database.py** - SQLAlchemy models and database operations

### Bank Processing
- **parsers.py** - Bank alert email parsers with regex patterns
  - SCB (Standard Chartered Bank) parser
  - Meezan Bank parser
  - Automatic transaction categorization
  - HTML email handling for Meezan

- **merchants.py** - Merchant name normalization and cleanup

### Gmail Integration
- **auth_gmail.py** - Gmail API OAuth flow and email fetching

## Python Dependencies

The following Python packages are configured in `build.gradle.kts` and will be bundled via Chaquopy:

### Web Framework
- **fastapi==0.104.1** - Modern web framework for building APIs
- **uvicorn==0.24.0** - ASGI server for running FastAPI
- **python-multipart==0.0.6** - Multipart form data parsing
- **pydantic==2.5.0** - Data validation using Python type annotations

### Database
- **sqlalchemy==2.0.23** - SQL toolkit and ORM

### Google API
- **google-api-python-client==2.108.0** - Google API client library
- **google-auth-httplib2==0.1.1** - Google authentication HTTP library
- **google-auth-oauthlib==1.1.0** - Google OAuth 2.0 library

## Runtime Behavior

### First Launch
1. Python runtime is extracted from APK to app-private storage
2. All Python packages are available via Chaquopy
3. Web frontend (index.html) is served from assets or copied to internal storage

### Server Startup
1. FastAPI server starts on localhost:8000
2. Server binds only to 127.0.0.1 (no external access)
3. WebView loads http://127.0.0.1:8000 and displays index.html

### Data Storage
- SQLite database: `/data/data/com.fintrack.pk/databases/fintrack.db`
- OAuth tokens: `/data/data/com.fintrack.pk/files/config/token.json`
- User credentials: `/data/data/com.fintrack.pk/files/config/credentials.json`
- Logs: `/data/data/com.fintrack.pk/files/logs/`

## Security Notes

- Server only accessible via localhost (127.0.0.1)
- All data stored in app-private storage (Android sandbox)
- OAuth tokens encrypted at rest
- PIN authentication required for app access
- No external network access to server endpoints

## Development Notes

### Updating Web Frontend
To update the web interface:
```bash
cp static/index.html android/app/src/main/assets/
```

### Updating Python Backend
Python files in `src/main/python/` are automatically included in the APK.
No additional steps needed after editing.

### Adding Python Dependencies
Edit `android/app/build.gradle.kts` and add to the `pip` block:
```kotlin
python {
    pip {
        install("package-name==version")
    }
}
```

## File Sizes (Approximate)

- index.html: ~50 KB (single file with embedded CSS/JS)
- Python backend: ~20 KB (all .py files)
- Python packages: ~50 MB (FastAPI, SQLAlchemy, Google APIs)
- Total APK size: ~80-100 MB (including Python runtime and dependencies)
