package dev.cinemar2;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
class ArenaLayoutTest {
 @Test void fiftyUniqueSeatsHaveCentralAisleAndFaceThePreservedScreen(){var seats=ArenaLayout.seats();assertEquals(50,seats.size());assertEquals(50,new HashSet<>(seats).size());assertTrue(seats.stream().allMatch(s->Math.abs(s.x())>=3&&Math.abs(s.x())<=11&&s.z()>-8&&s.y()>=100));var plan=ArenaLayout.plan();assertFalse(plan.stream().anyMatch(p->p.x()==0&&p.y()==100&&p.z()==-8));for(var s:seats){var last=plan.stream().filter(p->p.x()==s.x()&&p.y()==s.y()&&p.z()==s.z()).reduce((a,b)->b).orElseThrow();assertEquals("spruce_stairs",last.block());assertEquals("north",last.facing());}}
 @Test void planIsBoundedAndHasNaturalSceneryWithoutDangerousBlocks(){var p=ArenaLayout.plan();assertTrue(p.size()<160000);assertTrue(p.stream().allMatch(s->Math.abs(s.x())<=35&&s.z()>=-20&&s.z()<=50&&s.y()>=94&&s.y()<=118));assertTrue(p.stream().anyMatch(s->s.block().equals("oak_leaves")));assertTrue(p.stream().anyMatch(s->s.block().equals("water")));assertTrue(p.stream().anyMatch(s->s.block().equals("lantern")));assertFalse(p.stream().anyMatch(s->Set.of("tnt","lava","fire","command_block").contains(s.block())));}
}
