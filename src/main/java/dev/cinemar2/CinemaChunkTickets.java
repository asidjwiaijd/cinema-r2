package dev.cinemar2;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.core.BlockPos;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

/** Keep only occupied screens' controller chunks ticking, not the surrounding terrain. */
final class CinemaChunkTickets {
    private record Ticket(ServerLevel world, long chunk) {}
    private record Anchor(ServerLevel world, BlockPos pos) {}
    private static final Set<Ticket> owned = new HashSet<>();
    private static final Set<Anchor> known = new HashSet<>(), invalid = new HashSet<>();
    static void learn(ServerLevel world,BlockPos pos) {
        var anchor=new Anchor(world,pos.immutable());
        if(!invalid.contains(anchor))known.add(anchor);
    }
    private static void remember(CinemaEntity screen) {
        if(screen.getLevel() instanceof ServerLevel world&&!screen.isRemoved()){
            var anchor=new Anchor(world,screen.getBlockPos().immutable());invalid.remove(anchor);known.add(anchor);
        }
    }
    static void update(Collection<CinemaEntity> screens) {
        screens.forEach(CinemaChunkTickets::remember);
        Set<Ticket> wanted = new HashSet<>();
        double range = CinemaMod.settings.viewDistance + 32;
        for (var anchor : java.util.List.copyOf(known)) {
            var world=anchor.world;
            if(world.players().stream().noneMatch(p -> p.distanceToSqr(anchor.pos.getCenter()) <= range*range))continue;
            var ticket=new Ticket(world,ChunkPos.pack(anchor.pos));
            if (!owned.contains(ticket) && !world.getForceLoadedChunks().contains(ticket.chunk)) {
                var pos=ChunkPos.unpack(ticket.chunk);
                if(world.setChunkForced(pos.x(),pos.z(),true))owned.add(ticket);
            }
            if(world.getBlockEntity(anchor.pos) instanceof CinemaEntity screen){
                wanted.add(ticket);ManagementBridge.track(screen);
            }else{known.remove(anchor);invalid.add(anchor);}
        }
        owned.removeIf(ticket -> {
            if (wanted.contains(ticket)) return false;
            var pos = ChunkPos.unpack(ticket.chunk);
            ticket.world.setChunkForced(pos.x(), pos.z(), false);
            return true;
        });
    }
    static void close() {
        for (var ticket : owned) {
            var pos = ChunkPos.unpack(ticket.chunk);
            ticket.world.setChunkForced(pos.x(), pos.z(), false);
        }
        owned.clear();
        known.clear();invalid.clear();
    }
}
