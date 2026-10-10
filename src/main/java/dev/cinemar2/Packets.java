package dev.cinemar2;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.math.BlockPos;

public final class Packets {
    public record Control(BlockPos pos, String action, String text, long value) implements CustomPayload {
        public static final Id<Control> ID = new Id<>(CinemaMod.id("control"));
        public static final PacketCodec<RegistryByteBuf, Control> CODEC = new PacketCodec<>() {
            public Control decode(RegistryByteBuf b) { return new Control(b.readBlockPos(), b.readString(16), b.readString(2048), b.readLong()); }
            public void encode(RegistryByteBuf b, Control p) { b.writeBlockPos(p.pos); b.writeString(p.action, 16); b.writeString(p.text, 2048); b.writeLong(p.value); }
        };
        public Id<? extends CustomPayload> getId() { return ID; }
    }
    public record State(BlockPos pos, String url, long positionMs, boolean playing, long serverMs,
                        long revision, boolean canControl, boolean open, int width, String error, boolean live, int liveLatencySeconds, boolean hasAudio, long durationMs, int quality, int cacheSeconds, int cacheStartSeconds, long seekRevision) implements CustomPayload {
        public static final Id<State> ID = new Id<>(CinemaMod.id("state"));
        public static final PacketCodec<RegistryByteBuf, State> CODEC = new PacketCodec<>() {
            public State decode(RegistryByteBuf b) { return new State(b.readBlockPos(), b.readString(2048), b.readLong(), b.readBoolean(), b.readLong(), b.readLong(), b.readBoolean(), b.readBoolean(), b.readInt(), b.readString(256), b.readBoolean(), b.readInt(), b.readBoolean(), b.readLong(), b.readInt(), b.readInt(), b.readInt(), b.readLong()); }
            public void encode(RegistryByteBuf b, State p) { b.writeBlockPos(p.pos); b.writeString(p.url, 2048); b.writeLong(p.positionMs); b.writeBoolean(p.playing); b.writeLong(p.serverMs); b.writeLong(p.revision); b.writeBoolean(p.canControl); b.writeBoolean(p.open); b.writeInt(p.width); b.writeString(p.error, 256); b.writeBoolean(p.live); b.writeInt(p.liveLatencySeconds); b.writeBoolean(p.hasAudio); b.writeLong(p.durationMs); b.writeInt(p.quality); b.writeInt(p.cacheSeconds); b.writeInt(p.cacheStartSeconds); b.writeLong(p.seekRevision); }
        };
        public Id<? extends CustomPayload> getId() { return ID; }
    }
    public record Playback(BlockPos pos,long revision,long positionMs,int cacheMs,boolean buffering,String error) implements CustomPayload {
        public static final Id<Playback> ID=new Id<>(CinemaMod.id("playback"));
        public static final PacketCodec<RegistryByteBuf,Playback> CODEC=new PacketCodec<>(){
            public Playback decode(RegistryByteBuf b){return new Playback(b.readBlockPos(),b.readLong(),b.readLong(),b.readInt(),b.readBoolean(),b.readString(256));}
            public void encode(RegistryByteBuf b,Playback p){b.writeBlockPos(p.pos);b.writeLong(p.revision);b.writeLong(p.positionMs);b.writeInt(p.cacheMs);b.writeBoolean(p.buffering);b.writeString(p.error,256);}
        };
        public Id<? extends CustomPayload> getId(){return ID;}
    }
    public record Identity(String token) implements CustomPayload {
        public static final Id<Identity> ID=new Id<>(CinemaMod.id("identity"));
        public static final PacketCodec<RegistryByteBuf,Identity> CODEC=new PacketCodec<>(){
            public Identity decode(RegistryByteBuf b){return new Identity(b.readString(2048));}
            public void encode(RegistryByteBuf b,Identity p){b.writeString(p.token,2048);}
        };
        public Id<? extends CustomPayload> getId(){return ID;}
    }
    public record Clock(long clientMs, long serverMs) implements CustomPayload {
        public static final Id<Clock> ID = new Id<>(CinemaMod.id("clock"));
        public static final PacketCodec<RegistryByteBuf, Clock> CODEC = new PacketCodec<>() {
            public Clock decode(RegistryByteBuf b) { return new Clock(b.readLong(), b.readLong()); }
            public void encode(RegistryByteBuf b, Clock p) { b.writeLong(p.clientMs); b.writeLong(p.serverMs); }
        };
        public Id<? extends CustomPayload> getId() { return ID; }
    }
}
