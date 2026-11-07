package dev.cinemar2;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

public final class Packets {
    // Optional payload keeps the existing playback protocol compatible with 0.4.0 clients.
    public record Audio(BlockPos pos, int volume, boolean suppressMusic) implements CustomPacketPayload {
        public static final Type<Audio> ID = new Type<>(CinemaMod.id("audio"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Audio> CODEC = new StreamCodec<>() {
            public Audio decode(RegistryFriendlyByteBuf b) { return new Audio(b.readBlockPos(), Math.clamp(b.readInt(), 0, 100), b.readBoolean()); }
            public void encode(RegistryFriendlyByteBuf b, Audio p) { b.writeBlockPos(p.pos); b.writeInt(Math.clamp(p.volume, 0, 100)); b.writeBoolean(p.suppressMusic); }
        };
        public Type<? extends CustomPacketPayload> type() { return ID; }
    }
    // Optional geometry lets newer clients draw a screen even outside the controller chunk.
    public record Geometry(BlockPos pos, float yaw, int viewDistance) implements CustomPacketPayload {
        public static final Type<Geometry> ID = new Type<>(CinemaMod.id("geometry"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Geometry> CODEC = new StreamCodec<>() {
            public Geometry decode(RegistryFriendlyByteBuf b) { return new Geometry(b.readBlockPos(), b.readFloat(), CinemaCoverage.distance(b.readInt())); }
            public void encode(RegistryFriendlyByteBuf b, Geometry p) { b.writeBlockPos(p.pos); b.writeFloat(p.yaw); b.writeInt(CinemaCoverage.distance(p.viewDistance)); }
        };
        public Type<? extends CustomPacketPayload> type() { return ID; }
    }
    public record Control(BlockPos pos, String action, String text, long value) implements CustomPacketPayload {
        public static final Type<Control> ID = new Type<>(CinemaMod.id("control"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Control> CODEC = new StreamCodec<>() {
            public Control decode(RegistryFriendlyByteBuf b) { return new Control(b.readBlockPos(), b.readUtf(16), b.readUtf(2048), b.readLong()); }
            public void encode(RegistryFriendlyByteBuf b, Control p) { b.writeBlockPos(p.pos); b.writeUtf(p.action, 16); b.writeUtf(p.text, 2048); b.writeLong(p.value); }
        };
        public Type<? extends CustomPacketPayload> type() { return ID; }
    }
    public record State(BlockPos pos, String url, long positionMs, boolean playing, long serverMs,
                        long revision, boolean canControl, boolean open, int width, String error, boolean live, int liveLatencySeconds, boolean hasAudio, long durationMs, int quality, int cacheSeconds, int cacheStartSeconds, long seekRevision) implements CustomPacketPayload {
        public static final Type<State> ID = new Type<>(CinemaMod.id("state"));
        public static final StreamCodec<RegistryFriendlyByteBuf, State> CODEC = new StreamCodec<>() {
            public State decode(RegistryFriendlyByteBuf b) { return new State(b.readBlockPos(), b.readUtf(2048), b.readLong(), b.readBoolean(), b.readLong(), b.readLong(), b.readBoolean(), b.readBoolean(), b.readInt(), b.readUtf(256), b.readBoolean(), b.readInt(), b.readBoolean(), b.readLong(), b.readInt(), b.readInt(), b.readInt(), b.readLong()); }
            public void encode(RegistryFriendlyByteBuf b, State p) { b.writeBlockPos(p.pos); b.writeUtf(p.url, 2048); b.writeLong(p.positionMs); b.writeBoolean(p.playing); b.writeLong(p.serverMs); b.writeLong(p.revision); b.writeBoolean(p.canControl); b.writeBoolean(p.open); b.writeInt(p.width); b.writeUtf(p.error, 256); b.writeBoolean(p.live); b.writeInt(p.liveLatencySeconds); b.writeBoolean(p.hasAudio); b.writeLong(p.durationMs); b.writeInt(p.quality); b.writeInt(p.cacheSeconds); b.writeInt(p.cacheStartSeconds); b.writeLong(p.seekRevision); }
        };
        public Type<? extends CustomPacketPayload> type() { return ID; }
    }
    public record Playback(BlockPos pos,long revision,long positionMs,int cacheMs,boolean buffering,String error) implements CustomPacketPayload {
        public static final Type<Playback> ID=new Type<>(CinemaMod.id("playback"));
        public static final StreamCodec<RegistryFriendlyByteBuf,Playback> CODEC=new StreamCodec<>(){
            public Playback decode(RegistryFriendlyByteBuf b){return new Playback(b.readBlockPos(),b.readLong(),b.readLong(),b.readInt(),b.readBoolean(),b.readUtf(256));}
            public void encode(RegistryFriendlyByteBuf b,Playback p){b.writeBlockPos(p.pos);b.writeLong(p.revision);b.writeLong(p.positionMs);b.writeInt(p.cacheMs);b.writeBoolean(p.buffering);b.writeUtf(p.error,256);}
        };
        public Type<? extends CustomPacketPayload> type(){return ID;}
    }
    public record Identity(String token) implements CustomPacketPayload {
        public static final Type<Identity> ID=new Type<>(CinemaMod.id("identity"));
        public static final StreamCodec<RegistryFriendlyByteBuf,Identity> CODEC=new StreamCodec<>(){
            public Identity decode(RegistryFriendlyByteBuf b){return new Identity(b.readUtf(2048));}
            public void encode(RegistryFriendlyByteBuf b,Identity p){b.writeUtf(p.token,2048);}
        };
        public Type<? extends CustomPacketPayload> type(){return ID;}
    }
    public record Clock(long clientMs, long serverMs) implements CustomPacketPayload {
        public static final Type<Clock> ID = new Type<>(CinemaMod.id("clock"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Clock> CODEC = new StreamCodec<>() {
            public Clock decode(RegistryFriendlyByteBuf b) { return new Clock(b.readLong(), b.readLong()); }
            public void encode(RegistryFriendlyByteBuf b, Clock p) { b.writeLong(p.clientMs); b.writeLong(p.serverMs); }
        };
        public Type<? extends CustomPacketPayload> type() { return ID; }
    }
}
