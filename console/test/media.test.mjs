import { test, after } from "node:test";
import assert from "node:assert/strict";
import { mkdtemp, open, rm, writeFile } from "node:fs/promises";
import {createHash} from "node:crypto";
import { tmpdir } from "node:os";
import { join, dirname, basename } from "node:path";
const dir = await mkdtemp(join(tmpdir(), "cinema-media-"));
process.env.CINEMA_DATA = dir;
const { putFile } = await import("../server/media.mjs");
const { settings } = await import("../server/model.mjs");
test('resume confirms matching R2 content without uploading it again',async()=>{
 const file=join(dir,'existing.ts'),data=Buffer.from('already uploaded');await writeFile(file,data);
 const calls=[],progress=[];const client={async send(command){calls.push(command.constructor.name);assert.equal(command.constructor.name,'HeadObjectCommand');return {ContentLength:data.length,Metadata:{sha256:createHash('sha256').update(data).digest('hex')}};}};
 await putFile(client,file,'media/test/existing.ts',undefined,{skipExisting:true,onProgress:n=>progress.push(n)});
 assert.deepEqual(calls,['HeadObjectCommand']);assert.deepEqual(progress,[data.length]);
});
test('resume refuses a conflicting R2 object and uploads a genuinely missing one',async()=>{
 const file=join(dir,'resume.ts'),data=Buffer.from('complete');await writeFile(file,data);
 await assert.rejects(putFile({async send(){return {ContentLength:data.length,Metadata:{sha256:'different'}};}},file,'media/test/resume.ts',undefined,{skipExisting:true}),e=>e.code==='R2_OBJECT_CONFLICT');
 const calls=[];await putFile({async send(command){calls.push(command.constructor.name);if(command.constructor.name==='HeadObjectCommand')throw Object.assign(Error('missing'),{$metadata:{httpStatusCode:404}});let bytes=0;for await(const chunk of command.input.Body)bytes+=chunk.length;assert.equal(bytes,data.length);return {};}},file,'media/test/resume.ts',undefined,{skipExisting:true});
 assert.deepEqual(calls,['HeadObjectCommand','PutObjectCommand']);
});
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
  const progress=[];
  await putFile(client, file, "media/test/original.mp4",undefined,{onProgress:(done,total)=>progress.push({done,total})});
  assert.deepEqual(progress.map(p=>p.done),[64*1024*1024,65*1024*1024+9]);
  assert.ok(progress.every(p=>p.total===65*1024*1024+9));
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
