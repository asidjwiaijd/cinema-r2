import {test} from "node:test";
import assert from "node:assert/strict";
import {uploadResult} from "../src/upload-result.mjs";

test("upload requires both resource and job acknowledgement",()=>{
 const body={resource:{id:"r"},job:{id:"j"}};
 assert.deepEqual(uploadResult(202,JSON.stringify(body)),body);
 for(const text of ["null","{}","[]",'{"resource":{"id":"r"}}'])assert.throws(()=>uploadResult(202,text),/未获得.*HTTP 202/);
});
test("non-JSON errors keep their HTTP cause instead of hiding it",()=>{
 for(const [status,message] of [[401,"登录已过期"],[413,"超过入口"],[502,"连接后台"],[504,"超时"]])assert.throws(()=>uploadResult(status,"<html>gateway failure</html>"),new RegExp(message+".*HTTP "+status));
 assert.throws(()=>uploadResult(200,"<html>login page</html>"),/未返回有效.*HTTP 200/);
 assert.throws(()=>uploadResult(0,""),/连接中断/);
 assert.throws(()=>uploadResult(400,'{"error":"磁盘空间不足"}'),/磁盘空间不足.*HTTP 400/);
});
