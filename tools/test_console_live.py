"""Publish an OBS-compatible RTMP signal, then decode the resulting HLS. No keys are logged."""
import json,time,urllib.request,subprocess
from pathlib import Path
BASE='http://127.0.0.1:3210/api'
def api(path,body=None):
    req=urllib.request.Request(BASE+path,data=None if body is None else json.dumps(body).encode(),headers={'Content-Type':'application/json'})
    with urllib.request.urlopen(req,timeout=10) as r:return json.load(r)
def main():
    api('/live/start',{})
    data=api('/state');key=api('/live/key')['key']
    publisher=subprocess.Popen(['ffmpeg','-hide_banner','-loglevel','quiet','-re','-stream_loop','-1','-i',str(Path('.tools/test-pattern.mp4').resolve()),'-c','copy','-f','flv',data['live']['rtmpUrl']+'/'+key],stdin=subprocess.PIPE,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
    try:
        deadline=time.time()+35
        while time.time()<deadline:
            data=api('/state')
            if data['live']['ready']:break
            if publisher.poll() is not None:raise AssertionError('RTMP publisher exited')
            time.sleep(1)
        assert data['live']['ready'],data['live']['error']
        url=data['live']['url']
        assert data['live']['delivery']=='direct' and not url.startswith(data['settings']['publicBase'])
        with urllib.request.urlopen(url,timeout=10) as response:manifest=response.read().decode()
        assert '#EXTM3U' in manifest and '.ts' in manifest
        args=['ffmpeg','-nostdin','-hide_banner','-loglevel','quiet','-protocol_whitelist','https,http,tls,tcp,crypto','-rw_timeout','15000000','-seg_max_retry','2','-live_start_index','-1','-i',url,'-map','0:v:0','-an','-vf','fps=24,scale=640:360','-threads','2','-pix_fmt','rgb24','-t','2','-f','rawvideo','pipe:1','-map','0:a:0','-vn','-ac','2','-ar','48000','-t','2','-f','s16le','pipe:2']
        decoded=subprocess.run(args,capture_output=True,timeout=30)
        assert decoded.returncode==0 and len(decoded.stdout)>=640*360*3 and len(decoded.stderr)>48000
        proof={'version':'0.2.0','rtmpAccepted':True,'hlsPlaylist':True,'videoBytes':len(decoded.stdout),'audioBytes':len(decoded.stderr),'r2Configured':data['settings']['r2']['configured'],'oneDecoderInput':True,'delivery':data['live']['delivery'],'mediaOrigin':urllib.parse.urlparse(url).netloc}
        Path('.tools/console-live-proof.json').write_text(json.dumps(proof,indent=2),encoding='utf-8')
        print('PASS: RTMP -> HLS -> video + audio decode; publishing stays active for preview (120 s)',flush=True)
        time.sleep(120)
    finally:
        if publisher.poll() is None:
            publisher.communicate(b'q\n',timeout=10)
        api('/live/stop',{})
if __name__=='__main__':main()
