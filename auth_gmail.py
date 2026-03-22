import os
import json
import base64
from google.oauth2.credentials import Credentials
from google_auth_oauthlib.flow import InstalledAppFlow
from google.auth.transport.requests import Request
from googleapiclient.discovery import build

SCOPES = ["https://www.googleapis.com/auth/gmail.readonly"]
TOKEN_PATH = os.path.join(os.path.dirname(__file__), "token.json")
CREDS_PATH = os.path.join(os.path.dirname(__file__), "credentials.json")


def get_gmail_service():
    """Get authenticated Gmail API service. Opens browser on first run."""
    creds = None

    if os.path.exists(TOKEN_PATH):
        creds = Credentials.from_authorized_user_file(TOKEN_PATH, SCOPES)

    if not creds or not creds.valid:
        if creds and creds.expired and creds.refresh_token:
            creds.refresh(Request())
        else:
            if not os.path.exists(CREDS_PATH):
                raise FileNotFoundError(
                    f"Missing {CREDS_PATH}. Download OAuth credentials from Google Cloud Console "
                    f"and save as credentials.json in the fintrack directory."
                )
            flow = InstalledAppFlow.from_client_secrets_file(CREDS_PATH, SCOPES)
            creds = flow.run_local_server(port=8090)

        with open(TOKEN_PATH, "w") as f:
            f.write(creds.to_json())

    return build("gmail", "v1", credentials=creds)


def search_emails(service, query: str, max_results: int = 50) -> list:
    """Search Gmail and return list of message dicts with id, sender, snippet, body."""
    from email.utils import parsedate_to_datetime

    results = (
        service.users()
        .messages()
        .list(userId="me", q=query, maxResults=max_results)
        .execute()
    )

    messages = results.get("messages", [])
    emails = []

    for msg_meta in messages:
        msg = (
            service.users()
            .messages()
            .get(userId="me", id=msg_meta["id"], format="full")
            .execute()
        )

        headers = {h["name"].lower(): h["value"] for h in msg["payload"]["headers"]}
        sender = headers.get("from", "")
        subject = headers.get("subject", "")
        date_raw = headers.get("date", "")

        # Parse email date header into ISO format
        try:
            date_iso = parsedate_to_datetime(date_raw).isoformat()
        except Exception:
            date_iso = date_raw

        # Extract body text
        body = extract_body(msg["payload"])

        emails.append(
            {
                "gmail_id": msg_meta["id"],
                "sender": sender,
                "subject": subject,
                "date": date_iso,
                "body": body,
                "snippet": msg.get("snippet", ""),
            }
        )

    return emails


def extract_body(payload: dict) -> str:
    """Extract plain text body from Gmail message payload."""
    # Simple single-part message
    if payload.get("body", {}).get("data"):
        return base64.urlsafe_b64decode(payload["body"]["data"]).decode("utf-8", errors="replace")

    # Multipart — look for text/plain first, then text/html
    parts = payload.get("parts", [])
    for mime in ["text/plain", "text/html"]:
        for part in parts:
            if part.get("mimeType") == mime and part.get("body", {}).get("data"):
                return base64.urlsafe_b64decode(part["body"]["data"]).decode("utf-8", errors="replace")
            # Check nested parts
            for sub in part.get("parts", []):
                if sub.get("mimeType") == mime and sub.get("body", {}).get("data"):
                    return base64.urlsafe_b64decode(sub["body"]["data"]).decode("utf-8", errors="replace")

    return ""
