package dev.cinemar2.client;

/** Cinema seats keep their level; only the outer edge fades, with a bounded boost. */
public final class CinemaAudio {
    private CinemaAudio() {}
    public static float gain(float localVolume,int screenVolume,double distance) {
        return gain(localVolume,screenVolume,distance,256,128,1.5f);
    }
    public static float gain(float localVolume,int screenVolume,double distance,double range,double fullVolumeDistance,float boost) {
        if(!Float.isFinite(localVolume)||!Double.isFinite(distance)||!Double.isFinite(range)||!Double.isFinite(fullVolumeDistance)||!Float.isFinite(boost)||range<=0)return 0;
        double full=Math.clamp(fullVolumeDistance,0,range*.95);
        double progress=Math.clamp((Math.max(0,distance)-full)/(range-full),0,1);
        double fade=1-progress*progress*(3-2*progress);
        return (float)Math.clamp(Math.clamp(localVolume,0,1)*(Math.clamp(screenVolume,0,100)/100d)*Math.clamp(boost,1,3)*fade,0,1);
    }
}
