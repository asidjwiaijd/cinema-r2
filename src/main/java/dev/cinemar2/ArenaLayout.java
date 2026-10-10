package dev.cinemar2;
import java.util.*;

/** Deterministic, bounded forest amphitheatre plan, preserving the main controller. */
public final class ArenaLayout {
 public record Placement(int x,int y,int z,String block,String facing){}
 public record Seat(int x,int y,int z){}
 public static List<Seat> seats(){var result=new ArrayList<Seat>();for(int row=0;row<5;row++)for(int side:new int[]{-1,1})for(int n=0;n<5;n++)result.add(new Seat(side*(3+2*n),100+row,5+5*row));return List.copyOf(result);}
 public static List<Placement> plan(){var out=new ArrayList<Placement>();
  for(int x=-35;x<=35;x++)for(int z=-20;z<=50;z++){
   double distance=x*x/(35.0*35)+(z-15)*(z-15)/(35.0*35);if(distance>1)continue;
   int top=99;if(Math.abs(x)<=16&&z>=4&&z<=29)top=99+(z-4)/5;else if(distance>0.64)top=99+((x*13+z*7)&1);
   for(int y=94;y<=top;y++)put(out,x,y,z,y<top-2?"stone":y<top?"dirt":"grass_block");
   for(int y=top+1;y<=118;y++)put(out,x,y,z,"air");
  }
  // Wood stage and the opaque backing sit behind the projected image.
  for(int x=-12;x<=12;x++)for(int z=-11;z<=-3;z++)put(out,x,99,z,"spruce_planks");
  for(int x=-9;x<=9;x++)for(int y=101;y<=111;y++)put(out,x,y,-9,"dark_oak_planks");
  for(int y=100;y<=113;y++)for(int x:new int[]{-10,10})put(out,x,y,-8,"stripped_spruce_log");
  for(int x=-10;x<=10;x++)put(out,x,113,-8,"spruce_slab");
  for(var s:seats())put(out,s.x,s.y,s.z,"spruce_stairs","north");
  for(int row=0;row<5;row++)for(int x=-14;x<=14;x++)if(Math.abs(x)>1)put(out,x,99+row,4+row*5,"mossy_cobblestone");
  for(int z=4;z<=29;z++)for(int x=-1;x<=1;x++)put(out,x,99+(z-4)/5,z,"smooth_stone");
  for(int row=1;row<5;row++)for(int x=-1;x<=1;x++)put(out,x,99+row,4+row*5,"stone_brick_stairs","north");
  for(int z=29;z<=40;z++)for(int x=-3;x<=3;x++){for(int y=99;y<=103;y++)put(out,x,y,z,y==103?"spruce_planks":"dirt");for(int y=104;y<=110;y++)put(out,x,y,z,"air");}
  for(int x:new int[]{-5,5})for(int y=100;y<=108;y++)put(out,x,y,38,"oak_log");for(int x=-5;x<=5;x++)put(out,x,109,38,"oak_leaves");
  for(int[] p:new int[][]{{-24,-6},{24,-5},{-25,10},{25,12},{-24,26},{24,28},{-18,39},{18,40}})tree(out,p[0],p[1]);
  var random=new Random(50);for(int n=0;n<220;n++){int x=random.nextInt(63)-31,z=random.nextInt(61)-15;if(Math.abs(x)<18&&z<42)continue;if(x*x/1225.0+(z-15)*(z-15)/1225.0>0.63)continue;put(out,x,100,z,new String[]{"fern","dandelion","poppy","short_grass"}[n%4]);}
  for(int z:new int[]{-2,8,18,28,38})for(int x:new int[]{-18,18}){for(int y=100;y<=102;y++)put(out,x,y,z,"spruce_fence");put(out,x,103,z,"lantern");}
  // Small sheltered pond, with a solid floor and banks.
  for(int x=24;x<=29;x++)for(int z=-13;z<=-8;z++){put(out,x,97,z,"stone");put(out,x,98,z,"water");put(out,x,99,z,"air");}
  return List.copyOf(out);
 }
 private static void tree(List<Placement> out,int x,int z){
  int crown=106+Math.floorMod(x*7+z*3,3);
  for(int y=100;y<=crown;y++)put(out,x,y,z,"oak_log");
  for(int dy=-2;dy<=3;dy++)for(int dx=-4;dx<=4;dx++)for(int dz=-4;dz<=4;dz++){
   double shape=dx*dx/15.0+dz*dz/12.0+(dy-0.4)*(dy-0.4)/8.0;
   if(shape>1||dx==0&&dz==0&&dy<=0)continue;
   if(shape>0.72&&Math.floorMod(x*13+z*7+dx*3+dz*5+dy,5)==0)continue;
   put(out,x+dx,crown+dy,z+dz,"oak_leaves");
  }
 }
 private static void put(List<Placement> out,int x,int y,int z,String block){put(out,x,y,z,block,"");}
 private static void put(List<Placement> out,int x,int y,int z,String block,String facing){if(x==0&&y==100&&z==-8)return;out.add(new Placement(x,y,z,block,facing));}
}
