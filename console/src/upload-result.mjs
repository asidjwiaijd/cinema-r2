export function uploadResult(status,text){
  const code=status?`HTTP ${status}`:"连接中断";
  let result;
  try{result=JSON.parse(text);}catch{
    const reason={401:"登录已过期，请重新登录",403:"上传请求被拒绝",408:"上传超时",413:"上传文件超过入口允许的大小",502:"上传入口无法连接后台",503:"上传服务暂不可用",504:"上传入口等待后台超时"}[status];
    throw Error(`${reason||"服务器未返回有效的上传确认，请检查资源库后重试"}（${code}）`);
  }
  if(status<200||status>=300)throw Error(`${typeof result?.error==="string"?result.error:"上传失败"}（${code}）`);
  if(!result?.resource?.id||!result?.job?.id)throw Error(`上传未获得资源和任务确认（${code}）`);
  return result;
}
