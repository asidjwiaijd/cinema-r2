package dev.cinemar2.client;

import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/** Session-owned VOD TS spool. Download order is independent of demux order. */
final class VodSegmentCache implements AutoCloseable {
    record Segment(String file,double start,double duration) {}
    private static final long MAX_SEGMENT=32L*1024*1024,FREE_RESERVE=1024L*1024*1024;
    private final Path directory;
    private final URI[] origins;
    private final HttpClient http;
    private final BooleanSupplier eoAllowed,paidAllowed;
    private final BiConsumer<Integer,Long> account;
    private final ExecutorService r2Pool=pool(6,"cinema-r2-prefetch"),eoPool=pool(1,"cinema-eo-rescue"),paidPool=pool(1,"cinema-paid-rescue");
    private final ScheduledExecutorService ticker=Executors.newSingleThreadScheduledExecutor(r->{var t=new Thread(r,"cinema-segment-deadlines");t.setDaemon(true);return t;});
    private final Map<String,Entry> entries=new LinkedHashMap<>();
    private final Set<Job> jobs=new HashSet<>();
    private final long diskLimit;
    private final int r2TimeoutMs,eoTimeoutMs;
    private volatile double position;
    private volatile boolean closed;
    private boolean advancing;
    private long clockAt=System.nanoTime(),generation,diskBytes,reservedBytes;
    private int peakR2;
    private double horizon=600,eoWindow=40,paidWindow=20;
    private String route="R2 · 6 路预取",failure="";
    private static ExecutorService pool(int count,String name){return Executors.newFixedThreadPool(count,r->{var t=new Thread(r,name);t.setDaemon(true);return t;});}
    private static final class Entry {
        final Segment segment; final Path file; final int[] attempts=new int[3]; final long[] retryAt=new long[3],beganAt=new long[3]; final Job[] jobs=new Job[3];
        boolean ready; int provider=-1,readers,waiters; long size;
        Entry(Segment s,Path d){segment=s;file=d.resolve(s.file());}
    }
    private final class Job {
        final Entry entry;final int provider;final long epoch,startedAt=System.nanoTime();
        volatile boolean canceled;volatile CompletableFuture<?> network;volatile InputStream stream;Path partial;
        Job(Entry entry,int provider){this.entry=entry;this.provider=provider;epoch=generation;}
        void cancel(){canceled=true;if(network!=null)network.cancel(true);if(stream!=null)try{stream.close();}catch(IOException ignored){}}
        void check()throws IOException{if(canceled||closed||epoch!=generation)throw new InterruptedIOException("片段任务已取消");}
    }
    VodSegmentCache(Path base,long diskLimit,URI r2,URI eo,URI paid,HttpClient http,int r2TimeoutMs,int eoTimeoutMs,
                    BooleanSupplier eoAllowed,BooleanSupplier paidAllowed,BiConsumer<Integer,Long> account,double initialPosition)throws IOException{
        Files.createDirectories(base);directory=Files.createTempDirectory(base.toAbsolutePath().normalize(),"vod-segments-");
        this.diskLimit=Math.max(256L*1024*1024,diskLimit);origins=new URI[]{r2,eo,paid};this.http=http;
        this.r2TimeoutMs=r2TimeoutMs;this.eoTimeoutMs=eoTimeoutMs;this.eoAllowed=eoAllowed;this.paidAllowed=paidAllowed;this.account=account;position=Math.max(0,initialPosition);
        ticker.scheduleWithFixedDelay(this::tick,0,100,TimeUnit.MILLISECONDS);
    }
    synchronized void configure(double horizon,double eoWindow,double paidWindow){
        this.horizon=Math.max(60,Math.min(3600,horizon));this.eoWindow=Math.max(1,Math.min(300,eoWindow));this.paidWindow=Math.max(1,Math.min(this.eoWindow,paidWindow));
    }
    synchronized void register(List<Segment> segments)throws IOException{
        if(closed)throw new IOException("片段缓存已关闭");
        var seen=new HashMap<String,Segment>();for(var s:segments){
            if(!s.file().matches("[A-Za-z0-9_-]+\\.ts")||!Double.isFinite(s.start())||!Double.isFinite(s.duration())||s.duration()<=0)throw new IOException("点播分片时间无效");
            var old=entries.get(s.file());if(old!=null&&!old.segment.equals(s))throw new IOException("同名分片时间冲突");
            if(seen.put(s.file(),s)!=null)throw new IOException("清单不能重复使用分片文件名");
        }
        for(var s:segments)entries.computeIfAbsent(s.file(),k->new Entry(s,directory));notifyAll();
    }
    synchronized void playback(double seconds,boolean playing,boolean seeking){
        if(seeking)return;
        if(Double.isFinite(seconds))position=Math.max(0,seconds);advancing=playing;clockAt=System.nanoTime();
    }
    private double nowPosition(){long age=System.nanoTime()-clockAt;return position+(advancing&&age<TimeUnit.SECONDS.toNanos(2)?age/1e9:0);}
    synchronized void seek(double seconds){
        generation++;position=Math.max(0,seconds);advancing=false;clockAt=System.nanoTime();
        for(var j:List.copyOf(jobs))j.cancel();
        for(var e:entries.values()){Arrays.fill(e.retryAt,0);Arrays.fill(e.beganAt,0);Arrays.fill(e.attempts,0);}
        evict();notifyAll();
    }
    private boolean inWindow(Entry e,double p){return e.segment.start()+e.segment.duration()>p-12&&e.segment.start()<p+horizon;}
    // libavformat probes early TS packets even when loadfile starts in the middle.
    // Honor explicit past reads without extending speculative future prefetch.
    private boolean wanted(Entry e,double p){return inWindow(e,p)&&e.segment.start()+e.segment.duration()>p||e.waiters>0&&e.segment.start()+e.segment.duration()<=p;}
    private void evict(){
        double p=nowPosition();for(var e:entries.values())if(e.ready&&e.readers==0&&e.waiters==0&&!inWindow(e,p)){
            try{Files.deleteIfExists(e.file);diskBytes-=e.size;e.ready=false;e.size=0;e.provider=-1;}catch(IOException ignored){}
        }
    }
    private void tick(){
        try{synchronized(this){
            if(closed)return;evict();double p=nowPosition();long now=System.nanoTime();
            for(var j:List.copyOf(jobs)){
                int limit=j.provider==0?Math.max(r2TimeoutMs,120000):j.provider==1?eoTimeoutMs:15000;
                if(j.epoch!=generation||j.entry.ready||!wanted(j.entry,p)||now-j.startedAt>TimeUnit.MILLISECONDS.toNanos(limit)||j.provider==1&&!eoAllowed.getAsBoolean()||j.provider==2&&!paidAllowed.getAsBoolean())j.cancel();
            }
            var wanted=entries.values().stream().filter(e->!e.ready&&wanted(e,p)).sorted(Comparator.comparingDouble(e->e.segment.start())).toList();
            for(var e:wanted){
                double distance=e.segment.start()-p;
                schedule(e,0,now);
                if(distance<eoWindow&&eoAllowed.getAsBoolean()&&(e.attempts[0]==0||e.jobs[0]==null||now-e.beganAt[0]>=TimeUnit.MILLISECONDS.toNanos(100)))schedule(e,1,now);
                // Give EO a first attempt even at an immediately due startup/seek.
                // A pending EO read cannot hold paid rescue indefinitely.
                if(distance<paidWindow&&paidAllowed.getAsBoolean()&&e.attempts[1]>0&&(e.jobs[1]==null||now-e.beganAt[1]>=TimeUnit.MILLISECONDS.toNanos(1500)))schedule(e,2,now);
            }
        }}catch(Exception error){synchronized(this){failure="片段调度失败："+error.getMessage();notifyAll();}}
    }
    private void schedule(Entry e,int provider,long now)throws IOException{
        if(origins[provider]==null||e.jobs[provider]!=null||now<e.retryAt[provider])return;
        long active=jobs.stream().filter(j->j.provider==provider).count();if(active>=(provider==0?6:1))return;
        if(diskBytes+reservedBytes+MAX_SEGMENT>diskLimit||Files.getFileStore(directory).getUsableSpace()<reservedBytes+MAX_SEGMENT+FREE_RESERVE){failure="片段磁盘预算或剩余空间不足";return;}
        var job=new Job(e,provider);jobs.add(job);e.jobs[provider]=job;e.attempts[provider]++;e.beganAt[provider]=now;reservedBytes+=MAX_SEGMENT;
        if(provider==0)peakR2=Math.max(peakR2,(int)active+1);
        (provider==0?r2Pool:provider==1?eoPool:paidPool).execute(()->download(job));
    }
    private void download(Job job){
        long count=0;String error="";
        try{
            job.check();job.partial=directory.resolve("part-"+UUID.randomUUID());
            int timeout=job.provider==0?Math.max(r2TimeoutMs,120000):job.provider==1?eoTimeoutMs:15000;
            var request=HttpRequest.newBuilder(origins[job.provider].resolve(job.entry.segment.file())).timeout(Duration.ofMillis(timeout)).header("User-Agent","Cinema-R2/0.2.8").GET().build();
            var future=http.sendAsync(request,HttpResponse.BodyHandlers.ofInputStream());job.network=future;
            HttpResponse<InputStream> response;
            while(true){job.check();try{response=future.get(100,TimeUnit.MILLISECONDS);break;}catch(TimeoutException ignored){}}
            job.stream=response.body();job.check();
            if(response.statusCode()!=200||response.headers().firstValue("Content-Type").orElse("").toLowerCase(Locale.ROOT).contains("text/html"))throw new IOException("片段 HTTP "+response.statusCode());
            long declared=response.headers().firstValueAsLong("Content-Length").orElse(-1);
            if(declared>MAX_SEGMENT)throw new IOException("片段超过 32 MiB");
            try(var input=job.stream;var output=Files.newOutputStream(job.partial,StandardOpenOption.CREATE_NEW)){
                byte[] bytes=new byte[64*1024];int n;
                while((n=input.read(bytes))>=0){job.check();if(n==0)continue;count+=n;account.accept(job.provider,(long)n);if(count>MAX_SEGMENT)throw new IOException("片段超过 32 MiB");output.write(bytes,0,n);}
            }
            if(count==0||declared>=0&&declared!=count)throw new IOException("片段为空或长度不完整");
            synchronized(this){
                job.check();if(job.entry.ready)return;
                Files.move(job.partial,job.entry.file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
                job.entry.ready=true;job.entry.provider=job.provider;job.entry.size=count;diskBytes+=count;
                for(var other:job.entry.jobs)if(other!=null&&other!=job)other.cancel();notifyAll();
            }
        }catch(Exception e){if(!job.canceled&&!closed)error=e.getMessage()==null?"片段请求失败":e.getMessage();}
        finally{
            job.cancel();if(job.partial!=null)try{Files.deleteIfExists(job.partial);}catch(IOException ignored){}
            synchronized(this){
                jobs.remove(job);reservedBytes-=MAX_SEGMENT;if(job.entry.jobs[job.provider]==job)job.entry.jobs[job.provider]=null;
                if(job.epoch==generation&&!job.entry.ready&&!closed){int attempts=job.entry.attempts[job.provider];job.entry.retryAt[job.provider]=System.nanoTime()+TimeUnit.SECONDS.toNanos(Math.min(8,1L<<Math.min(3,Math.max(0,attempts-1))));}
                if(!error.isEmpty())failure=error;notifyAll();if(closed&&jobs.isEmpty())cleanup();
            }
        }
    }
    synchronized InputStream open(String file,long epoch)throws Exception{
        var e=entries.get(file);if(e==null)throw new IOException("片段未登记在点播清单中");
        e.waiters++;
        try{
        while(!e.ready){if(closed||epoch!=generation)throw new InterruptedIOException("播放位置已改变");wait(100);}
        if(closed||epoch!=generation)throw new InterruptedIOException("播放位置已改变");
        route=switch(e.provider){case 1->"EO · 40 秒内补片";case 2->"阿里云 · 20 秒内兜底";default->"R2 · 6 路预取";};failure="";
        var stream=Files.newInputStream(e.file);e.readers++;
        return new FilterInputStream(stream){boolean released;@Override public void close()throws IOException{try{super.close();}finally{synchronized(VodSegmentCache.this){if(!released){released=true;e.readers--;if(closed&&jobs.isEmpty())cleanup();}}}}};
        }finally{e.waiters--;}
    }
    synchronized long epoch(){return generation;}
    synchronized long size(String file){var e=entries.get(file);return e==null?0:e.size;}
    synchronized String route(){return route;}
    synchronized String failure(){return failure;}
    synchronized int peakR2(){return peakR2;}
    synchronized long diskBytes(){return diskBytes;}
    synchronized boolean cached(String file){var e=entries.get(file);return e!=null&&e.ready;}
    Path directory(){return directory;}
    private void cleanup(){
        try(var paths=Files.walk(directory)){
            for(var p:paths.sorted(Comparator.reverseOrder()).toList())if(p.toAbsolutePath().normalize().startsWith(directory))try{Files.deleteIfExists(p);}catch(IOException ignored){}
        }catch(IOException ignored){}
    }
    @Override public synchronized void close(){
        if(closed)return;closed=true;generation++;advancing=false;ticker.shutdownNow();for(var j:List.copyOf(jobs))j.cancel();
        r2Pool.shutdown();eoPool.shutdown();paidPool.shutdown();notifyAll();if(jobs.isEmpty())cleanup();
    }
}
