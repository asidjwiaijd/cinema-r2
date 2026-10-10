package dev.cinemar2.client;

import com.google.gson.*;
import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

/** Private loopback adapter: every upstream remains HTTPS; mpv keeps one demuxer/cache. */
public final class MediaRelay implements AutoCloseable {
    public static volatile String identityToken="";
    private static final int CHUNK=1024*1024,MAX_BODY=32*1024*1024;
    private final URI primary,fallback,backup,policyBase,backupPolicyBase;
    private final boolean live;
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).followRedirects(HttpClient.Redirect.NEVER).build();
    private final HttpServer server;
    private final ExecutorService requests=Executors.newVirtualThreadPerTaskExecutor();
    private final ScheduledExecutorService maintenance=Executors.newSingleThreadScheduledExecutor(r->{var t=new Thread(r,"cinema-delivery-policy");t.setDaemon(true);return t;});
    private final Set<CompletableFuture<?>> inFlight=ConcurrentHashMap.newKeySet();
    private final Semaphore slots=new Semaphore(4);
    private final String prefix="/"+UUID.randomUUID()+"/";
    private final AtomicLong r2Bytes=new AtomicLong(),cdnBytes=new AtomicLong(),eoBytes=new AtomicLong(),backupBytes=new AtomicLong();
    private final int primaryTimeoutMs,cdnTimeoutMs;
    private volatile boolean closed,cdnAllowed,vodFallback,backupAllowed;
    private volatile long policyExpires,primaryRetryAt,cdnRetryAt,policyRetryAt,sessionLimit,reportR2Base,reportCdnBase,reportEoBase,reportBackupBase;
    private volatile URI activePolicyBase;
    private volatile String reportToken="",route="R2",lastFailure="";
    private volatile boolean cacheAware,warming,handoffPending;
    private volatile int startupCdnSeconds=60,emergencyCdnSeconds=30,minStartupSeconds,targetCacheSeconds=1200;
    private volatile long vodGeneration,lastBufferAt;
    private volatile double forwardSeconds,bootstrapSeconds;
    private volatile boolean consuming;
    private final Map<String,Double> segmentSeconds=new ConcurrentHashMap<>();
    private final Set<String> bootstrapSegments=ConcurrentHashMap.newKeySet();
    private volatile VodSegmentCache segmentCache;
    private volatile int prefetchSeconds=600,eoDeadlineSeconds=40,paidDeadlineSeconds=20;

    public void enableParallelCache(ClientSettings settings,double initialPosition)throws IOException {
        if(live||!primary.getPath().endsWith("m3u8"))return;
        segmentCache=new VodSegmentCache(Path.of(settings.cacheDirectory),(long)settings.segmentCacheMB*1024*1024,primary,fallback,backup,http,primaryTimeoutMs,cdnTimeoutMs,
                this::vodCdnPermitted,()->vodCdnPermitted()&&backupAllowed,(provider,count)->{if(provider==0)r2Bytes.addAndGet(count);else{cdnBytes.addAndGet(count);(provider==1?eoBytes:backupBytes).addAndGet(count);}},initialPosition);
        segmentCache.configure(prefetchSeconds,eoDeadlineSeconds,paidDeadlineSeconds);
    }
    public void updatePlayback(double position,boolean playing,boolean seeking){var spool=segmentCache;if(spool!=null)spool.playback(position,playing,seeking);}
    public int prefetchPeakR2(){var spool=segmentCache;return spool==null?0:spool.peakR2();}
    public long segmentDiskBytes(){var spool=segmentCache;return spool==null?0:spool.diskBytes();}

    /** Called before loadfile. Only registered VOD uses cache deadlines; live is unchanged. */
    public void enableCacheAware(int startSeconds,int cacheSeconds){
        if(live)return;minStartupSeconds=Math.max(1,startSeconds);targetCacheSeconds=Math.max(30,cacheSeconds);
        cacheAware=true;warming=startupCdnSeconds>0;handoffPending=warming;
    }
    public void updateBuffer(double seconds,boolean playing,boolean seeking){
        if(!cacheAware)return;
        forwardSeconds=seeking||!Double.isFinite(seconds)?0:Math.max(0,seconds);
        consuming=playing&&!seeking;lastBufferAt=System.nanoTime();
        if(!seeking&&forwardSeconds>=warmTarget())warming=false;
    }
    public void prepareSeek(){
        if(!cacheAware)return;
        vodGeneration++;forwardSeconds=0;lastBufferAt=0;bootstrapSeconds=0;bootstrapSegments.clear();
        warming=startupCdnSeconds>0;handoffPending=warming;inFlight.forEach(f->f.cancel(true));
    }
    public void prepareSeek(double position){prepareSeek();var spool=segmentCache;if(spool!=null)spool.seek(position);}
    private double warmTarget(){return Math.min(targetCacheSeconds,Math.max(minStartupSeconds,startupCdnSeconds));}
    void setCacheThresholds(int startup,int emergency){startupCdnSeconds=Math.max(0,Math.min(300,startup));emergencyCdnSeconds=Math.max(1,Math.min(300,emergency));}
    private double remainingBuffer(){
        long age=System.nanoTime()-lastBufferAt;
        if(lastBufferAt==0||age>TimeUnit.SECONDS.toNanos(2))return 0;
        return Math.max(0,forwardSeconds-(consuming?age/1e9:0));
    }
    private boolean vodCdnPermitted(){return fallback!=null&&vodFallback&&permitted();}
    private void checkGeneration(long generation)throws IOException{
        if(closed||generation!=vodGeneration)throw new InterruptedIOException("媒体已关闭或播放位置已改变");
    }

    public static MediaRelay open(ClientSettings settings,String source,boolean live)throws Exception {
        URI uri=URI.create(source),r2=URI.create(settings.primaryMediaBase),cdn=URI.create(settings.cdnMediaBase);
        if(!"https".equals(uri.getScheme())||uri.getUserInfo()!=null||uri.getRawPath().contains("%")||!uri.getPath().matches("/(media|live)/[A-Za-z0-9_-]+/[A-Za-z0-9_-]+\\.(m3u8|mp4)"))return null;
        if(live&&!sameOrigin(uri,cdn))return null;
        if(!live&&(!sameOrigin(uri,r2)||!settings.r2Failover))return null;
        var fallback=live?null:cdn.resolve(uri.getRawPath());
        URI paid=URI.create(settings.backupMediaBase);
        if(!"https".equals(paid.getScheme())||paid.getHost()==null||paid.getUserInfo()!=null||paid.getQuery()!=null||paid.getFragment()!=null||!(paid.getPath().isEmpty()||paid.getPath().equals("/")))throw new IOException("备用 CDN 必须为 HTTPS 域名根地址");
        return new MediaRelay(uri,fallback,paid.resolve(uri.getRawPath()),cdn,paid,live,settings.primaryTimeoutSeconds*1000,settings.cdnTimeoutSeconds*1000);
    }
    private static boolean sameOrigin(URI a,URI b){return Objects.equals(a.getScheme(),b.getScheme())&&Objects.equals(a.getHost(),b.getHost())&&a.getPort()==b.getPort();}
    // Package-private constructor permits deterministic HTTP fixtures without weakening production URL checks.
    MediaRelay(URI primary,URI fallback,URI policyBase,boolean live,int primaryTimeoutMs)throws Exception{
        this(primary,fallback,null,policyBase,null,live,primaryTimeoutMs,15000);
    }
    MediaRelay(URI primary,URI fallback,URI backup,URI policyBase,URI backupPolicyBase,boolean live,int primaryTimeoutMs,int cdnTimeoutMs)throws Exception{
        this.primary=primary;this.fallback=fallback;this.backup=backup;this.policyBase=policyBase;this.backupPolicyBase=backupPolicyBase;this.activePolicyBase=policyBase;this.live=live;this.primaryTimeoutMs=primaryTimeoutMs;this.cdnTimeoutMs=cdnTimeoutMs;
        server=HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"),0),0);server.setExecutor(requests);
        server.createContext(prefix,this::handle);server.start();
        refreshPolicy();maintenance.scheduleWithFixedDelay(this::refreshPolicy,10,10,TimeUnit.SECONDS);
    }
    public String url(){return "http://127.0.0.1:"+server.getAddress().getPort()+prefix+primary.getPath().substring(primary.getPath().lastIndexOf('/')+1);}
    public long r2Bytes(){return r2Bytes.get();}
    public long cdnBytes(){return cdnBytes.get();}
    public String route(){var spool=segmentCache;return spool==null?route:spool.route();}
    public String lastFailure(){var spool=segmentCache;return spool==null?lastFailure:spool.failure();}
    private HttpResponse<byte[]> request(URI uri,String method,String range,boolean cdn,int timeoutMs)throws Exception{
        return request(uri,method,range,cdn,timeoutMs,-1,false);
    }
    private HttpResponse<byte[]> request(URI uri,String method,String range,boolean cdn,int timeoutMs,long generation,boolean yieldAtEmergency)throws Exception{
        var builder=HttpRequest.newBuilder(uri).timeout(Duration.ofMillis(timeoutMs)).header("User-Agent","Cinema-R2/0.2.8");
        if(range!=null)builder.header("Range",range);
        if(method.equals("HEAD"))builder.method("HEAD",HttpRequest.BodyPublishers.noBody());else builder.GET();
        var future=http.sendAsync(builder.build(),info->new LimitedBody(cdn?cdnBytes:r2Bytes,cdn?(backup!=null&&sameOrigin(uri,backup)?backupBytes:eoBytes):null));inFlight.add(future);
        try{
            HttpResponse<byte[]> response;long deadline=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(timeoutMs);
            while(true){
                if(generation>=0){checkGeneration(generation);if(yieldAtEmergency&&remainingBuffer()<=emergencyCdnSeconds&&vodCdnPermitted())throw new IOException("缓存进入 CDN 兜底窗口");}
                long left=deadline-System.nanoTime();if(left<=0)throw new TimeoutException("媒体请求超时");
                try{response=future.get(Math.min(left,TimeUnit.MILLISECONDS.toNanos(100)),TimeUnit.NANOSECONDS);break;}catch(TimeoutException ignored){}
            }
            if(response.statusCode()!=200&&response.statusCode()!=206)throw new IOException("HTTP "+response.statusCode());
            if(!method.equals("HEAD")&&(response.body().length==0||response.headers().firstValue("Content-Type").orElse("").toLowerCase(Locale.ROOT).contains("text/html")))throw new IOException("媒体响应为空或被替换为网页");
            if(!method.equals("HEAD")&&uri.getPath().endsWith("m3u8")&&!new String(response.body(),StandardCharsets.UTF_8).startsWith("#EXTM3U"))throw new IOException("媒体清单无效");
            return response;}
        finally{inFlight.remove(future);if(!future.isDone())future.cancel(true);}
    }
    private final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> result=new CompletableFuture<>();
        private final ByteArrayOutputStream body=new ByteArrayOutputStream();
        private final AtomicLong counter,provider;private Flow.Subscription subscription;
        LimitedBody(AtomicLong counter,AtomicLong provider){this.counter=counter;this.provider=provider;}
        public CompletionStage<byte[]> getBody(){return result;}
        public void onSubscribe(Flow.Subscription value){subscription=value;value.request(1);}
        public void onNext(List<ByteBuffer> buffers){
            try{for(var buffer:buffers){int count=buffer.remaining();counter.addAndGet(count);if(provider!=null)provider.addAndGet(count);if(body.size()+count>MAX_BODY)throw new IOException("单次媒体响应超过 32MB");byte[] bytes=new byte[count];buffer.get(bytes);body.write(bytes);}subscription.request(1);}
            catch(Exception error){subscription.cancel();result.completeExceptionally(error);}
        }
        public void onError(Throwable error){result.completeExceptionally(error);}
        public void onComplete(){result.complete(body.toByteArray());}
    }
    private boolean permitted(){return cdnAllowed&&System.currentTimeMillis()<policyExpires&&(sessionLimit==0||cdnBytes.get()<sessionLimit);}
    private HttpResponse<byte[]> fetch(String file,String method,String range,long generation)throws Exception{
        if(closed)throw new IOException("播放器已关闭");
        if(live){if(!permitted())throw new IOException("CDN 已关闭、策略未连接或达到流量上限");return fetchCdn(primary,file,method,range);}
        if(cacheAware)return fetchBuffered(file,method,range,generation);
        if(System.currentTimeMillis()>=primaryRetryAt){
            try{var response=request(primary.resolve(file),method,range,false,primaryTimeoutMs);primaryRetryAt=0;route="R2";lastFailure="";return response;}
            catch(Exception error){primaryRetryAt=System.currentTimeMillis()+30000;lastFailure="R2 请求失败，30 秒后重试";}
        }
        if(fallback==null||!vodFallback||!permitted())throw new IOException("R2 无法读取，CDN 回退未获准或达到流量上限");
        return fetchCdn(fallback,file,method,range);
    }
    private HttpResponse<byte[]> fetchBuffered(String file,String method,String range,long generation)throws Exception{
        checkGeneration(generation);
        if(warming&&startupCdnSeconds>0&&vodCdnPermitted()){
            try{
                var response=fetchCdn(fallback,file,method,range);checkGeneration(generation);route=route.startsWith("备用")?"备用 CDN 快速起播":"CDN 快速起播";
                synchronized(this){if(file.endsWith("ts")&&!method.equals("HEAD")&&bootstrapSegments.add(file)){
                    bootstrapSeconds+=segmentSeconds.getOrDefault(file,0.0);
                    // Bound startup CDN even if the demuxer reads many tiny segments before the next stats tick.
                    if(bootstrapSeconds>=warmTarget())warming=false;
                }}
                return response;
            }catch(Exception error){checkGeneration(generation);warming=false;handoffPending=false;lastFailure="起播 CDN 不可用，尝试 R2";}
        }
        if(handoffPending){
            // The startup quota can be read before mpv has exposed its first cache
            // snapshot. Playback uses those startup packets while we await feedback.
            long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
            while(remainingBuffer()<=emergencyCdnSeconds&&System.nanoTime()<until){checkGeneration(generation);Thread.sleep(20);}
            checkGeneration(generation);handoffPending=false;
        }
        int attempts=0;
        while(true){
            checkGeneration(generation);double cache=remainingBuffer();
            if(attempts>0&&cache<=emergencyCdnSeconds&&vodCdnPermitted()){
                var response=fetchCdn(fallback,file,method,range);checkGeneration(generation);route=route.startsWith("备用")?"备用 CDN 临近播放兜底":"CDN 临近播放兜底";return response;
            }
            try{
                // A slow but progressing TS must be allowed to finish while cached
                // playback continues. The live cache deadline still cancels it early.
                int timeout=cache<=emergencyCdnSeconds&&vodCdnPermitted()?Math.min(primaryTimeoutMs,1500):
                        (int)Math.max(primaryTimeoutMs,Math.min(120000,Math.max(0,cache-emergencyCdnSeconds)*1000));
                var response=request(primary.resolve(file),method,range,false,timeout,generation,cache>emergencyCdnSeconds);
                route="R2";lastFailure="";return response;
            }catch(Exception error){
                checkGeneration(generation);attempts++;
                // IO can outrun the 250ms mpv stats poll. Before declaring an emergency,
                // let the demuxer report the data we have just handed it.
                if(remainingBuffer()<=emergencyCdnSeconds){
                    long sample=lastBufferAt,until=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(500);
                    while(lastBufferAt==sample&&System.nanoTime()<until){checkGeneration(generation);Thread.sleep(20);}
                }
                if(remainingBuffer()<=emergencyCdnSeconds){
                    if(vodCdnPermitted()){var response=fetchCdn(fallback,file,method,range);checkGeneration(generation);route=route.startsWith("备用")?"备用 CDN 临近播放兜底":"CDN 临近播放兜底";return response;}
                    throw new IOException("R2 无法读取且 CDN 未获准；等待网络恢复",error);
                }
                route="R2 重试";lastFailure="R2 请求失败，利用剩余缓存继续重试";
                long retryAt=System.nanoTime()+TimeUnit.SECONDS.toNanos(Math.min(8,1L<<Math.min(3,attempts-1)));
                while(System.nanoTime()<retryAt){checkGeneration(generation);if(remainingBuffer()<=emergencyCdnSeconds)break;Thread.sleep(100);}
            }
        }
    }
    private HttpResponse<byte[]> fetchCdn(URI eo,String file,String method,String range)throws Exception{
        Exception failure=null;
        if(System.currentTimeMillis()>=cdnRetryAt){
            try{var response=request(eo.resolve(file),method,range,true,cdnTimeoutMs);cdnRetryAt=0;route=live?"CDN 直播":"CDN 回退";return response;}
            catch(Exception error){failure=error;cdnRetryAt=System.currentTimeMillis()+30000;lastFailure="EO 请求失败，尝试最后备用 CDN";}
        }
        if(backup==null||!backupAllowed||!permitted())throw new IOException("EO 不可用，备用 CDN 未获准或达到流量上限",failure);
        var response=request(backup.resolve(file),method,range,true,15000);route=live?"备用 CDN 直播":"备用 CDN 回退";return response;
    }
    private void handle(HttpExchange exchange){
        boolean acquired=false;
        long generation=vodGeneration;
        try{
            if(!exchange.getRemoteAddress().getAddress().isLoopbackAddress())throw new IOException("仅允许本地播放器");
            String file=exchange.getRequestURI().getRawPath().substring(prefix.length());
            if(!file.matches("[A-Za-z0-9_-]+\\.(m3u8|ts|mp4)")){exchange.sendResponseHeaders(404,-1);return;}
            if(!List.of("GET","HEAD").contains(exchange.getRequestMethod())){exchange.sendResponseHeaders(405,-1);return;}
            slots.acquire();acquired=true;
            var spool=segmentCache;
            if(file.endsWith("ts")&&spool!=null){serveSegment(exchange,spool,file,generation);return;}
            if(file.endsWith("mp4")){mp4(exchange,file,generation);return;}
            var response=fetch(file,exchange.getRequestMethod(),exchange.getRequestHeaders().getFirst("Range"),generation);
            byte[] body=response.body();
            if(file.endsWith("m3u8")&&!exchange.getRequestMethod().equals("HEAD"))body=rewrite(new String(body,StandardCharsets.UTF_8),primary.resolve(file)).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type",file.endsWith("m3u8")?"application/vnd.apple.mpegurl":"video/mp2t");
            exchange.getResponseHeaders().set("Cache-Control","no-store");
            if(exchange.getRequestMethod().equals("HEAD")){exchange.getResponseHeaders().set("Content-Length",response.headers().firstValue("Content-Length").orElse("0"));exchange.sendResponseHeaders(200,-1);return;}
            response.headers().firstValue("Content-Range").ifPresent(value->exchange.getResponseHeaders().set("Content-Range",value));
            exchange.sendResponseHeaders(response.statusCode(),body.length);exchange.getResponseBody().write(body);
        }catch(Exception error){
            lastFailure=error.getMessage()==null?"媒体请求失败":error.getMessage();
            try{byte[] body=lastFailure.getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(503,body.length);exchange.getResponseBody().write(body);}catch(Exception ignored){}
        }finally{exchange.close();if(acquired)slots.release();}
    }
    private String rewrite(String manifest,URI source)throws IOException{
        if(!manifest.startsWith("#EXTM3U"))throw new IOException("媒体清单无效");
        var out=new StringBuilder();
        double duration=0;
        double position=0;var segments=new ArrayList<VodSegmentCache.Segment>();
        for(var line:manifest.split("\\r?\\n")){
            if(line.startsWith("#EXTINF:")){try{duration=Double.parseDouble(line.substring(8).split(",",2)[0]);if(!Double.isFinite(duration)||duration<0||duration>3600)throw new NumberFormatException();}catch(NumberFormatException error){throw new IOException("分片时长无效");}}
            if(line.startsWith("#")&&line.contains("URI="))throw new IOException("暂不支持带外部 URI 的清单");
            if(!line.isEmpty()&&!line.startsWith("#")){
                var target=source.resolve(line);
                if(!sameOrigin(target,primary)||!target.resolve(".").equals(primary.resolve("."))||!target.getRawPath().substring(target.getRawPath().lastIndexOf('/')+1).matches("[A-Za-z0-9_-]+\\.(ts|m3u8)"))throw new IOException("分片不在注册影片目录中");
                if(target.getPath().endsWith("ts")){
                    String name=target.getPath().substring(target.getPath().lastIndexOf('/')+1);segmentSeconds.put(name,duration);
                    if(segmentCache!=null){segments.add(new VodSegmentCache.Segment(name,position,duration));position+=duration;}
                }
                duration=0;
                line=prefix+target.getPath().substring(target.getPath().lastIndexOf('/')+1);
            }
            out.append(line).append('\n');
        }
        if(segmentCache!=null&&!segments.isEmpty()){
            if(!manifest.contains("#EXT-X-ENDLIST"))throw new IOException("并行点播需要完整 VOD 清单");
            segmentCache.register(segments);
        }
        return out.toString();
    }
    private void serveSegment(HttpExchange exchange,VodSegmentCache spool,String file,long generation)throws Exception{
        checkGeneration(generation);
        long epoch=spool.epoch();
        try(var input=spool.open(file,epoch)){
            long total=spool.size(file),start=0,end=total-1;String range=exchange.getRequestHeaders().getFirst("Range");
            if(range!=null){var match=Pattern.compile("bytes=(\\d*)-(\\d*)").matcher(range);
                if(!match.matches()||(match.group(1).isEmpty()&&match.group(2).isEmpty())){exchange.sendResponseHeaders(416,-1);return;}
                if(match.group(1).isEmpty())start=Math.max(0,total-Long.parseLong(match.group(2)));
                else{start=Long.parseLong(match.group(1));if(!match.group(2).isEmpty())end=Math.min(end,Long.parseLong(match.group(2)));}
                if(start>end||start>=total){exchange.getResponseHeaders().set("Content-Range","bytes */"+total);exchange.sendResponseHeaders(416,-1);return;}
            }
            long length=end-start+1;exchange.getResponseHeaders().set("Content-Type","video/mp2t");exchange.getResponseHeaders().set("Accept-Ranges","bytes");exchange.getResponseHeaders().set("Cache-Control","no-store");
            if(range!=null)exchange.getResponseHeaders().set("Content-Range","bytes "+start+"-"+end+"/"+total);
            if(exchange.getRequestMethod().equals("HEAD")){exchange.getResponseHeaders().set("Content-Length",Long.toString(length));exchange.sendResponseHeaders(range==null?200:206,-1);return;}
            exchange.sendResponseHeaders(range==null?200:206,length);input.skipNBytes(start);byte[] bytes=new byte[64*1024];
            while(length>0){checkGeneration(generation);if(epoch!=spool.epoch())throw new InterruptedIOException("播放位置已改变");int n=input.read(bytes,0,(int)Math.min(bytes.length,length));if(n<0)throw new EOFException("缓存分片不完整");exchange.getResponseBody().write(bytes,0,n);length-=n;}
        }
    }
    private void mp4(HttpExchange exchange,String file,long generation)throws Exception{
        String requested=exchange.getRequestHeaders().getFirst("Range");long start=0,end=Long.MAX_VALUE;
        if(requested!=null){
            var match=Pattern.compile("bytes=(\\d*)-(\\d*)").matcher(requested);
            if(!match.matches()||(match.group(1).isEmpty()&&match.group(2).isEmpty())){exchange.sendResponseHeaders(416,-1);return;}
            if(match.group(1).isEmpty()){long size=Long.parseLong(fetch(file,"HEAD",null,generation).headers().firstValue("Content-Length").orElseThrow());start=Math.max(0,size-Long.parseLong(match.group(2)));end=size-1;}
            else{start=Long.parseLong(match.group(1));if(!match.group(2).isEmpty())end=Long.parseLong(match.group(2));}
        }
        if(exchange.getRequestMethod().equals("HEAD")){
            var head=fetch(file,"HEAD",null,generation);exchange.getResponseHeaders().set("Content-Type","video/mp4");exchange.getResponseHeaders().set("Content-Length",head.headers().firstValue("Content-Length").orElseThrow());exchange.getResponseHeaders().set("Accept-Ranges","bytes");exchange.sendResponseHeaders(200,-1);return;
        }
        var first=fetch(file,"GET","bytes="+start+"-"+Math.min(end,start+CHUNK-1),generation);
        long total=total(first);end=Math.min(end,total-1);
        if(start>end||start>=total){exchange.sendResponseHeaders(416,-1);return;}
        exchange.getResponseHeaders().set("Content-Type","video/mp4");exchange.getResponseHeaders().set("Accept-Ranges","bytes");
        if(requested!=null)exchange.getResponseHeaders().set("Content-Range","bytes "+start+"-"+end+"/"+total);
        exchange.sendResponseHeaders(requested==null?200:206,end-start+1);
        long offset=start;var part=first;
        while(offset<=end&&!closed){
            byte[] data=part.body();
            var range=part.headers().firstValue("Content-Range");
            if(range.isPresent()){
                var match=Pattern.compile("bytes (\\d+)-(\\d+)/(\\d+)").matcher(range.get());
                if(!match.matches()||Long.parseLong(match.group(1))!=offset||Long.parseLong(match.group(3))!=total||Long.parseLong(match.group(2))-offset+1!=data.length)throw new IOException("分片 Range 长度或副本长度不一致");
            }else{if(data.length!=total)throw new IOException("源站未正确支持 Range");data=Arrays.copyOfRange(data,(int)offset,(int)Math.min(end+1,total));}
            int length=(int)Math.min(data.length,end-offset+1);if(length<=0)throw new IOException("媒体响应为空");
            exchange.getResponseBody().write(data,0,length);offset+=length;
            if(offset<=end)part=fetch(file,"GET","bytes="+offset+"-"+Math.min(end,offset+CHUNK-1),generation);
        }
    }
    private long total(HttpResponse<byte[]> response)throws IOException{
        var range=response.headers().firstValue("Content-Range");
        if(range.isPresent())return Long.parseLong(range.get().substring(range.get().lastIndexOf('/')+1));
        if(response.statusCode()==200)return response.body().length;
        throw new IOException("源站缺少媒体总长度");
    }
    private JsonObject json(String path,JsonObject body)throws Exception{
        if(System.currentTimeMillis()>=policyRetryAt){
            try{var result=jsonAt(policyBase,path,body);activePolicyBase=policyBase;policyRetryAt=0;return result;}
            catch(Exception error){policyRetryAt=System.currentTimeMillis()+30000;if(backupPolicyBase==null)throw error;}
        }
        if(backupPolicyBase==null)throw new IOException("传输策略暂时不可达");
        var result=jsonAt(backupPolicyBase,path,body);activePolicyBase=backupPolicyBase;return result;
    }
    private JsonObject jsonAt(URI base,String path,JsonObject body)throws Exception{
        var builder=HttpRequest.newBuilder(base.resolve(path)).timeout(Duration.ofSeconds(4)).header("Content-Type","application/json");
        if(body==null)builder.GET();else builder.POST(HttpRequest.BodyPublishers.ofString(body.toString()));
        var response=http.send(builder.build(),HttpResponse.BodyHandlers.ofString());if(response.statusCode()!=200)throw new IOException("传输策略 HTTP "+response.statusCode());return JsonParser.parseString(response.body()).getAsJsonObject();
    }
    private void refreshPolicy(){
        if(closed)return;
        try{
            if(!reportToken.isEmpty())try{json("/api/delivery/report",reportBody());}catch(Exception ignored){}
            var request=new JsonObject();request.addProperty("token",reportToken);request.addProperty("identityToken",identityToken);
            var policy=json("/api/delivery/policy",request);
            String token=policy.get("reportToken").getAsString();
            if(!token.equals(reportToken)){reportR2Base=r2Bytes.get();reportCdnBase=cdnBytes.get();reportEoBase=eoBytes.get();reportBackupBase=backupBytes.get();reportToken=token;}
            cdnAllowed=policy.get("cdnEnabled").getAsBoolean();vodFallback=policy.get("vodFallback").getAsBoolean();
            backupAllowed=backup!=null&&policy.has("backupEnabled")&&policy.get("backupEnabled").getAsBoolean();
            sessionLimit=(long)(policy.get("sessionLimitMB").getAsDouble()*1024*1024);
            if(policy.has("vodStartupCdnSeconds"))startupCdnSeconds=Math.max(0,Math.min(300,policy.get("vodStartupCdnSeconds").getAsInt()));
            if(policy.has("vodEmergencyCdnSeconds"))emergencyCdnSeconds=Math.max(1,Math.min(300,policy.get("vodEmergencyCdnSeconds").getAsInt()));
            if(policy.has("vodPrefetchSeconds"))prefetchSeconds=Math.max(60,Math.min(3600,policy.get("vodPrefetchSeconds").getAsInt()));
            if(policy.has("vodEoDeadlineSeconds"))eoDeadlineSeconds=Math.max(1,Math.min(300,policy.get("vodEoDeadlineSeconds").getAsInt()));
            if(policy.has("vodBackupDeadlineSeconds"))paidDeadlineSeconds=Math.max(1,Math.min(eoDeadlineSeconds,policy.get("vodBackupDeadlineSeconds").getAsInt()));
            var spool=segmentCache;if(spool!=null)spool.configure(prefetchSeconds,eoDeadlineSeconds,paidDeadlineSeconds);
            policyExpires=System.currentTimeMillis()+15000;
        }catch(Exception ignored){if(System.currentTimeMillis()>=policyExpires)cdnAllowed=false;}
    }
    private JsonObject reportBody(){var body=new JsonObject();body.addProperty("token",reportToken);long eo=Math.max(0,eoBytes.get()-reportEoBase),paid=Math.max(0,backupBytes.get()-reportBackupBase);body.addProperty("cdnBytes",eo+paid);body.addProperty("r2Bytes",Math.max(0,r2Bytes.get()-reportR2Base));body.addProperty("eoBytes",eo);body.addProperty("backupBytes",paid);return body;}
    @Override public void close(){
        if(closed)return;closed=true;var spool=segmentCache;if(spool!=null)spool.close();server.stop(0);maintenance.shutdownNow();inFlight.forEach(f->f.cancel(true));requests.shutdownNow();
        if(!reportToken.isEmpty())try{http.sendAsync(HttpRequest.newBuilder(activePolicyBase.resolve("/api/delivery/report")).timeout(Duration.ofSeconds(4)).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(reportBody().toString())).build(),HttpResponse.BodyHandlers.discarding());}catch(Exception ignored){}
    }
}
