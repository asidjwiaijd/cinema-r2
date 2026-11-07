package dev.cinemar2.mixin;

import dev.cinemar2.client.CinemaClient;
import net.minecraft.client.sounds.MusicManager;
import net.minecraft.sounds.Music;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Suppress vanilla music only while viewing a cinema; never rewrite the user's options. */
@Mixin(MusicManager.class)
public abstract class CinemaMusicMixin {
    @Shadow public abstract void stopPlaying();
    @Inject(method="tick",at=@At("HEAD"),cancellable=true)
    private void cinema$musicTick(CallbackInfo ci){
        if(CinemaClient.suppressBackgroundMusic()){stopPlaying();ci.cancel();}
    }
    @Inject(method="startPlaying",at=@At("HEAD"),cancellable=true)
    private void cinema$musicStart(Music music,CallbackInfo ci){
        if(CinemaClient.suppressBackgroundMusic())ci.cancel();
    }
}
