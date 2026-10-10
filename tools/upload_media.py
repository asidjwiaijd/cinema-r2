"""Private R2 upload with 8 GB storage ceiling, a local rolling operation budget,
single-writer lock, no retries, and explicit Standard multipart uploads.
Defaults to preflight only. Credentials are read from AWS_* environment variables.
"""
import argparse
import contextlib
import math
import os
from pathlib import Path
import sqlite3
import time

STORAGE_CEILING = 8_000_000_000
PART_SIZE = 64 * 1024 * 1024
ROLLING_WINDOW = 32 * 86400


class Budget:
    def __init__(self, path):
        self.db = sqlite3.connect(path)
        self.db.execute("CREATE TABLE IF NOT EXISTS calls (at REAL NOT NULL, class TEXT NOT NULL)")
        self.db.commit()

    def reserve(self, category):
        # Count attempted operations even when they fail. Never undo reservations.
        now = time.time()
        with self.db:
            used = self.db.execute("SELECT COUNT(*) FROM calls WHERE at > ? AND class = ?", (now - ROLLING_WINDOW, category)).fetchone()[0]
            if used >= 1000:
                raise RuntimeError(f"Local rolling {category} operation budget reached; refusing R2 call")
            self.db.execute("INSERT INTO calls VALUES (?, ?)", (now, category))

    def remaining(self, category):
        return 1000 - self.db.execute("SELECT COUNT(*) FROM calls WHERE at > ? AND class = ?", (time.time() - ROLLING_WINDOW, category)).fetchone()[0]


def call(client, budget, method, **kwargs):
    budget.reserve("B" if method.startswith("head_") else "A")
    return getattr(client, method)(**kwargs)


def preflight(client, budget, bucket, key, size):
    unfinished = call(client, budget, "list_multipart_uploads", Bucket=bucket, MaxUploads=1)
    if unfinished.get("Uploads") or unfinished.get("IsTruncated"):
        raise RuntimeError("Unfinished multipart upload exists; abort it in R2 before proceeding")
    used, token = 0, None
    while True:
        args = {"Bucket": bucket, "MaxKeys": 1000}
        if token:
            args["ContinuationToken"] = token
        page = call(client, budget, "list_objects_v2", **args)
        for obj in page.get("Contents", []):
            if obj.get("StorageClass", "STANDARD") != "STANDARD":
                raise RuntimeError("Bucket contains non-Standard objects; free allowance does not apply")
            if obj["Key"] == key:
                raise RuntimeError("Object already exists; use a new key to avoid replacing an active film")
            used += obj["Size"]
        if not page.get("IsTruncated"):
            break
        token = page.get("NextContinuationToken")
        if not token:
            raise RuntimeError("R2 returned truncated inventory without a cursor")
    if size <= 0 or used + size > STORAGE_CEILING:
        raise RuntimeError(f"Storage ceiling exceeded: existing {used:,} + new {size:,} > {STORAGE_CEILING:,} bytes")
    required = math.ceil(size / PART_SIZE) + 2
    if budget.remaining("A") < required:
        raise RuntimeError("Insufficient local operation budget for this multipart upload")
    return used, required


def upload(client, budget, bucket, key, file, expected_size=None):
    expected_size = file.stat().st_size if expected_size is None else expected_size
    created = call(client, budget, "create_multipart_upload", Bucket=bucket, Key=key, StorageClass="STANDARD", ContentType="video/mp4", CacheControl="private, no-store")
    upload_id = created["UploadId"]
    try:
        parts = []
        with file.open("rb") as stream:
            number, sent = 1, 0
            while data := stream.read(PART_SIZE):
                if sent + len(data) > expected_size:
                    raise RuntimeError("Source file grew after preflight; refusing to exceed reserved storage")
                result = call(client, budget, "upload_part", Bucket=bucket, Key=key, UploadId=upload_id, PartNumber=number, Body=data)
                parts.append({"PartNumber": number, "ETag": result["ETag"]})
                sent += len(data)
                print(f"Uploaded part {number}")
                number += 1
            if sent != expected_size:
                raise RuntimeError("Source file changed after preflight; aborting upload")
        call(client, budget, "complete_multipart_upload", Bucket=bucket, Key=key, UploadId=upload_id, MultipartUpload={"Parts": parts})
    except BaseException:
        # AbortMultipartUpload is free. Never print the credential or upload ID.
        try:
            client.abort_multipart_upload(Bucket=bucket, Key=key, UploadId=upload_id)
        except Exception:
            print("Abort failed; remove the unfinished multipart upload in the R2 dashboard.")
        raise


@contextlib.contextmanager
def writer_lock(path):
    try:
        fd = os.open(path, os.O_CREAT | os.O_EXCL | os.O_WRONLY)
    except FileExistsError:
        raise RuntimeError("Uploader lock exists; another upload is running or an interrupted run needs inspection") from None
    try:
        os.close(fd)
        yield
    finally:
        Path(path).unlink(missing_ok=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("file", type=Path)
    parser.add_argument("--key", required=True, help="media/film-name.mp4 (letters, digits, _ and -)")
    parser.add_argument("--upload", action="store_true", help="Actually upload; default only checks storage/budget")
    args = parser.parse_args()
    import re
    if not re.fullmatch(r"media/[A-Za-z0-9_-]+\.mp4", args.key) or not args.file.is_file():
        parser.error("Provide an existing file and a key like media/film-01.mp4")
    import boto3
    from botocore.config import Config
    account = os.environ["R2_ACCOUNT_ID"]
    bucket = os.environ.get("R2_BUCKET", "cinema-media")
    # Shared ledger for all buckets used by this uploader/account. Keep it; do not reset it.
    state_dir = Path(__file__).resolve().parent.parent / ".tools" / "upload-state"
    state_dir.mkdir(parents=True, exist_ok=True)
    if not re.fullmatch(r"[a-fA-F0-9]{32}", account):
        raise RuntimeError("R2_ACCOUNT_ID must be a 32-character account ID")
    with writer_lock(state_dir / f"{account}.lock"):
        budget = Budget(state_dir / f"{account}.sqlite")
        client = boto3.client("s3", endpoint_url=f"https://{account}.r2.cloudflarestorage.com", region_name="auto", config=Config(retries={"total_max_attempts": 1}, connect_timeout=15, read_timeout=120))
        size = args.file.stat().st_size
        used, operations = preflight(client, budget, bucket, args.key, size)
        print(f"Storage after upload: {(used + size) / 1e9:.3f} GB / 8 GB; upload operations: {operations}")
        print("This checks ONE bucket. Other account storage/operations and external uploads must fit the remaining allowance.")
        if args.upload:
            upload(client, budget, bucket, args.key, args.file, size)
            print("Upload complete. Revoke the write token after your upload session.")
        else:
            print("Preflight only. Add --upload to upload.")
        budget.db.close()


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        # SDK exception messages can contain request details: report only our validation messages.
        if isinstance(error, RuntimeError):
            print(str(error))
        else:
            print(f"Upload failed ({type(error).__name__}); check local settings and R2 permissions.")
        raise SystemExit(1)
