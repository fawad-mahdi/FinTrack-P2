# FinTrack PK — Personal Financial Tracker

Lightweight local app that reads bank alert emails from Gmail (SCB, Meezan) and auto-parses them into transactions.

**Everything runs locally. No cloud database. No data leaves your machine.**

---

## Setup (5 minutes)

### 1. Install dependencies

```bash
cd fintrack
pip install -r requirements.txt
```

### 2. Create Google Cloud OAuth credentials

This is needed so the app can read your Gmail (read-only access).

1. Go to [Google Cloud Console](https://console.cloud.google.com/)
2. Create a new project (or use existing)
3. Enable the **Gmail API**: APIs & Services → Library → search "Gmail API" → Enable
4. Create OAuth credentials:
   - APIs & Services → Credentials → Create Credentials → OAuth Client ID
   - Application type: **Desktop App**
   - Download the JSON file
5. Rename it to `credentials.json` and place it in the `fintrack/` directory

**Important:** Under OAuth consent screen, add yourself as a test user.

### 3. Set your PIN

Edit `.env` file:
```
PIN=your-pin-here
```

### 4. Run

```bash
python server.py
```

Open `http://127.0.0.1:8000` in your browser.

On first run, it will open a browser window for Google OAuth consent. Approve it once — the token is saved locally in `token.json`.

---

## Usage

1. Enter your PIN
2. Click **Sync Gmail** — it searches for emails from SCB and Meezan
3. View parsed transactions
4. Check **Pending Review** tab for low-confidence parses

---

## File structure

```
fintrack/
├── server.py          ← FastAPI app + routes
├── parsers.py         ← Regex parsers for SCB & Meezan
├── database.py        ← SQLite wrapper
├── auth_gmail.py      ← Gmail OAuth handler
├── static/
│   └── index.html     ← Frontend (single file)
├── .env               ← PIN config
├── credentials.json   ← Google OAuth creds (you create this)
├── token.json         ← Auto-generated after first auth
├── fintrack.db        ← Auto-generated SQLite database
└── requirements.txt
```

## Security

- Server binds to `127.0.0.1` only (not accessible from network)
- Gmail scope is `gmail.readonly` (cannot send/modify emails)
- All data stored in local SQLite file
- No external API calls for parsing (regex only)
- PIN gate on the web UI
