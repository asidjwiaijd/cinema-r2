import { test, after } from "node:test";
import assert from "node:assert/strict";
import { mkdtemp, open, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join, dirname, basename } from "node:path";
const dir = await mkdtemp(join(tmpdir(), "cinema-media-"));
process.env.CINEMA_DATA = dir;
const { putFile } = await import("../server/media.mjs");
const { settings } = await import("../server/model.mjs");
after(async () => {
  assert.equal(dirname(dir), tmpdir());
  assert.ok(basename(dir).startsWith("cinema-media-"));
  await rm(dir, { recursive: true, force: true });
});
test("multipart streams exact bounded parts and completes the upload", async () => {
  const file = join(dir, "big.mp4");
  const f = await open(file, "w");
  await f.truncate(65 * 1024 * 1024 + 9);
  await f.close();
  const calls = [];
  const client = {
    async send(command) {
      calls.push(command.constructor.name);
      const b = command.input;
      if (command.constructor.name === "CreateMultipartUploadCommand")
        return { UploadId: "upload-1" };
      if (command.constructor.name === "UploadPartCommand") {
        let bytes = 0;
        for await (const chunk of b.Body) bytes += chunk.length;
        assert.equal(bytes, b.ContentLength);
        return { ETag: "part-" + b.PartNumber };
      }
      if (command.constructor.name === "CompleteMultipartUploadCommand") {
        assert.equal(b.MultipartUpload.Parts.length, 2);
        assert.deepEqual(
          b.MultipartUpload.Parts.map((p) => p.PartNumber),
          [1, 2],
        );
      }
      return {};
    },
  };
  await putFile(client, file, "media/test/original.mp4");
  assert.deepEqual(calls, [
    "CreateMultipartUploadCommand",
    "UploadPartCommand",
    "UploadPartCommand",
    "CompleteMultipartUploadCommand",
  ]);
});
test("failed part aborts the incomplete multipart upload", async () => {
  const calls = [];
  const client = {
    async send(command) {
      calls.push(command.constructor.name);
      if (command.constructor.name === "CreateMultipartUploadCommand")
        return { UploadId: "failed" };
      if (command.constructor.name === "UploadPartCommand") {
        command.input.Body.destroy();
        throw Error("network test failure");
      }
      assert.equal(command.input.UploadId, "failed");
      return {};
    },
  };
  await assert.rejects(
    putFile(client, join(dir, "big.mp4"), "media/test/original.mp4"),
    /network test failure/,
  );
  assert.equal(calls.at(-1), "AbortMultipartUploadCommand");
});
test("live delivery remains direct even with R2 credentials configured", async () => {
  settings.r2.accessKeyId = "test-access";
  settings.r2.secretAccessKey = "test-secret";
  const { liveURL, liveResource } = await import("../server/live.mjs");
  assert.equal(new URL(liveURL()).origin, "http://127.0.0.1:3210");
  assert.equal(liveResource().storage, "direct");
  settings.live.publicBase = "https://live.example.com";
  assert.equal(new URL(liveURL()).origin, "https://live.example.com");
});
