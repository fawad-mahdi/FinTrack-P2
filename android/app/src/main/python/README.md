# Python Backend for FinTrack PK Android

This directory contains the Python backend code that will be bundled with the Android APK using Chaquopy.

## Files

- `main.py` - Entry point for the Python server, sets up Android environment paths
- `server.py` - FastAPI application with all API endpoints
- `database.py` - SQLite database operations and schema
- `auth_gmail.py` - Gmail API authentication and email fetching
- `parsers.py` - Bank email parsing logic (SCB and Meezan)
- `merchants.py` - Merchant name normalization rules

## Python Runtime Configuration

The Python runtime is configured in `app/build.gradle.kts`:

```kotlin
python {
    version = "3.9"
    pip {
        install("fastapi==0.104.1")
        install("uvicorn==0.24.0")
        install("sqlalchemy==2.0.23")
        install("google-api-python-client==2.108.0")
        install("google-auth-httplib2==0.1.1")
        install("google-auth-oauthlib==1.1.0")
        install("python-multipart==0.0.6")
        install("pydantic==2.5.0")
    }
}
```

## Android Environment Paths

The Python code uses environment variables to locate Android-specific directories:

- `FINTRACK_APP_DIR` - Base app files directory (default: `/data/data/com.fintrack.pk/files`)
- `FINTRACK_CONFIG_DIR` - Configuration files (credentials.json, token.json)
- `FINTRACK_LOGS_DIR` - Server log files
- `FINTRACK_DB_DIR` - SQLite database location

These are set up by `main.py` on startup.

## Running the Server

The server is started by the Android `ServerProcessManager` component using:

```bash
python -m main
```

This will:
1. Set up Android environment paths
2. Create necessary directories
3. Start the FastAPI server on `127.0.0.1:8000`

## Database

The SQLite database (`fintrack.db`) is created automatically on first run in the `FINTRACK_DB_DIR` directory. The schema includes:

- `transactions` - Financial transactions
- `sync_log` - Gmail sync history
- `merchant_categories` - Learned merchant categorizations
- `merchant_rules` - Merchant name normalization rules
- `budgets` - Monthly budget allocations

## Gmail OAuth

Gmail authentication requires:
1. `credentials.json` in the config directory (from Google Cloud Console)
2. OAuth flow will create `token.json` after first authentication
3. The Android app handles the OAuth redirect flow

## API Endpoints

The server provides these main endpoints:

- `GET /health` - Health check for process monitoring
- `POST /api/auth` - PIN authentication
- `POST /api/sync` - Trigger Gmail sync
- `GET /api/transactions` - List transactions
- `POST /api/transactions` - Create manual transaction
- `GET /api/summary` - Financial summary
- `GET /api/budgets` - Budget management
- `GET /api/report` - Monthly report

See `server.py` for complete API documentation.
