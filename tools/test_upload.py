import importlib.util
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location("upload_media", Path(__file__).with_name("upload_media.py"))
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)

class R2:
    def __init__(self, size=0, storage="STANDARD", unfinished=False): self.size, self.storage, self.unfinished = size, storage, unfinished
    def list_multipart_uploads(self, **kw): return {"Uploads": [{"Key": "unfinished"}]} if self.unfinished else {}
    def list_objects_v2(self, **kw): return {"Contents": [{"Key": "media/old.mp4", "Size": self.size, "StorageClass": self.storage}]}

class UploadTests(unittest.TestCase):
    def setUp(self): self.temp = tempfile.TemporaryDirectory(); self.budget = module.Budget(Path(self.temp.name) / "calls.sqlite")
    def tearDown(self): self.budget.db.close(); self.temp.cleanup()
    def test_storage_at_ceiling_passes_but_next_byte_fails(self):
        self.assertEqual(module.preflight(R2(5_000_000_000), self.budget, "bucket", "media/new.mp4", 3_000_000_000)[0], 5_000_000_000)
        with self.assertRaises(RuntimeError): module.preflight(R2(5_000_000_001), self.budget, "bucket", "media/new.mp4", 3_000_000_000)
    def test_existing_key_or_infrequent_access_or_unfinished_upload_rejected(self):
        for client, key in [(R2(), "media/old.mp4"), (R2(storage="STANDARD_IA"), "media/new.mp4"), (R2(unfinished=True), "media/new.mp4")]:
            with self.assertRaises(RuntimeError): module.preflight(client, self.budget, "bucket", key, 100)
    def test_failed_calls_still_use_budget_and_ceiling_is_hard(self):
        self.budget.db.executemany("INSERT INTO calls VALUES (?, ?)", [(module.time.time(), "A")] * 999); self.budget.db.commit()
        self.budget.reserve("A")
        with self.assertRaises(RuntimeError): self.budget.reserve("A")
    def test_lock_rejects_second_writer(self):
        path = Path(self.temp.name) / "lock"
        with module.writer_lock(path):
            with self.assertRaises(RuntimeError):
                with module.writer_lock(path): pass
        self.assertFalse(path.exists())
    def test_failed_part_aborts_without_completing(self):
        class Failed:
            aborted = False
            def create_multipart_upload(self, **kw): return {"UploadId": "secret"}
            def upload_part(self, **kw): raise OSError("network")
            def abort_multipart_upload(self, **kw): self.aborted = True
        file = Path(self.temp.name) / "test.mp4"; file.write_bytes(b"test")
        client = Failed()
        with self.assertRaises(OSError): module.upload(client, self.budget, "bucket", "media/test.mp4", file)
        self.assertTrue(client.aborted)
    def test_growing_source_is_aborted_before_any_part_can_exceed_preflight(self):
        class Growing:
            aborted, writes = False, 0
            def create_multipart_upload(self, **kw): return {"UploadId": "secret"}
            def upload_part(self, **kw): self.writes += 1; return {"ETag": "test"}
            def abort_multipart_upload(self, **kw): self.aborted = True
        file = Path(self.temp.name) / "test.mp4"; file.write_bytes(b"grown")
        client = Growing()
        with self.assertRaises(RuntimeError): module.upload(client, self.budget, "bucket", "media/test.mp4", file, expected_size=2)
        self.assertTrue(client.aborted); self.assertEqual(client.writes, 0)

if __name__ == "__main__": unittest.main()
