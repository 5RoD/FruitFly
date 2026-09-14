package dev.flybrain.movement;

import dev.flybrain.entity.CreatureEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import java.util.*;

/** Incremental loaded-only A*: real creature volume, axis-aligned edges, no world mutations. */
public final class PredatorNavigation {
    private record Node(BlockPos pos, double cost, double score) {}
    private record Segment(BlockPos from,BlockPos to) {}
    private final Map<Segment,Integer> failedSegments=new LinkedHashMap<>();
    private int budgetTick=-1,penaltyReset;
    private long tickDeadline;
    private final CreatureEntity mob;
    private final PriorityQueue<Node> open = new PriorityQueue<>(Comparator.comparingDouble(Node::score));
    private final Map<BlockPos, Double> costs = new HashMap<>();
    private final Map<BlockPos, BlockPos> parents = new HashMap<>();
    private final ArrayDeque<Vec3d> route = new ArrayDeque<>();
    private BlockPos origin, goal;
    private Vec3d destination, previous,startPosition;
    private int blocked;
    private boolean searching;
    private Vec3d climbBase,climbNormal;
    private int climbStep,climbHeight;
    private boolean probingClimb,surfaceRoute;
    private final ArrayDeque<Vec3d> climbPoints=new ArrayDeque<>();

    public PredatorNavigation(CreatureEntity mob) { this.mob=mob; }
    private long deadline(){
        if(budgetTick!=mob.visualAge()){budgetTick=mob.visualAge();tickDeadline=System.nanoTime()+1_000_000;if(budgetTick-penaltyReset>=200){failedSegments.clear();penaltyReset=budgetTick;}}
        return tickDeadline;
    }
    private void penalize(BlockPos to){
        Segment segment=new Segment(mob.getBlockPos().toImmutable(),to.toImmutable());failedSegments.merge(segment,3,(a,b)->Math.min(12,a+b));
        if(failedSegments.size()>64)failedSegments.remove(failedSegments.keySet().iterator().next());
    }
    public void clear() { open.clear(); costs.clear(); parents.clear();route.clear();goal=null;destination=null;searching=false;blocked=0;probingClimb=false;surfaceRoute=false;climbPoints.clear(); }
    public boolean failed() { return goal!=null && !searching && route.isEmpty(); }
    public void toward(Vec3d target) {
        if(!mob.predator.routeAllowed(target)||target.squaredDistanceTo(mob.getPos())>4096) { clear();return; }
        BlockPos next=BlockPos.ofFloored(target);
        if(goal==null || goal.getSquaredDistance(next)>4) {
            clear(); goal=next;destination=target;origin=mob.getBlockPos();startPosition=mob.getPos();
            costs.put(origin,0d);open.add(new Node(origin,0,heuristic(origin)));searching=true;
            probingClimb=target.y-mob.getY()>=4;climbBase=null;climbStep=0;climbHeight=(int)Math.ceil(target.y-mob.getY());
        }
    }
    private double heuristic(BlockPos pos) { return Math.abs(pos.getX()-goal.getX())+Math.abs(pos.getY()-goal.getY())+Math.abs(pos.getZ()-goal.getZ()); }
    private Vec3d point(BlockPos pos) { return pos.equals(origin)?startPosition:new Vec3d(pos.getX()+.5,pos.getY(),pos.getZ()+.5); }
    private boolean free(BlockPos pos) { return free(point(pos)); }
    private boolean free(Vec3d pos) {
        if(!mob.predator.routeAllowed(pos))return false;
        Box box=mob.getBoundingBox().offset(pos.subtract(mob.getPos())).contract(.001);
        for(int x=(int)Math.floor(box.minX);x<=Math.floor(box.maxX);x++)
            for(int z=(int)Math.floor(box.minZ);z<=Math.floor(box.maxZ);z++)
                if(!mob.getWorld().isChunkLoaded(x>>4,z>>4))return false;
        return box.minY>=mob.getWorld().getBottomY() && box.maxY<mob.getWorld().getTopY()
                && mob.getWorld().getWorldBorder().contains(box) && mob.getWorld().isSpaceEmpty(mob,box);
    }
    private boolean supported(BlockPos pos) {
        Box box=mob.getBoundingBox().offset(point(pos).subtract(mob.getPos())).offset(0,-.15,0);
        return !mob.getWorld().isSpaceEmpty(mob,box);
    }
    private Vec3d contact(Vec3d pos) {
        Box box=mob.getBoundingBox().offset(pos.subtract(mob.getPos())).contract(.01);
        for(Direction dir:new Direction[]{Direction.UP,Direction.NORTH,Direction.SOUTH,Direction.EAST,Direction.WEST}) {
            Vec3d normal=Vec3d.of(dir.getVector()).multiply(-1);
            if(!mob.getWorld().isSpaceEmpty(mob,box.offset(normal.multiply(-.16))))return normal;
        }
        return Vec3d.ZERO;
    }
    /** Pick a loaded collision-free retreat using the last sensed position, preferring occlusion. */
    public Vec3d coverAway(Vec3d evidence,Vec3d previousGoal){
        Vec3d away=mob.getPos().subtract(evidence).normalize();
        Vec3d best=mob.getPos();double bestScore=-Double.MAX_VALUE;
        long deadline=deadline();
        for(int i=0;i<8&&System.nanoTime()<deadline;i++){
            double angle=(i%2==0?1:-1)*((i+1)/2)*Math.PI/4;
            Vec3d direction=new Vec3d(away.x*Math.cos(angle)-away.z*Math.sin(angle),0,away.x*Math.sin(angle)+away.z*Math.cos(angle));
            if(direction.lengthSquared()<.01)direction=new Vec3d(1,0,0);
            Vec3d point=mob.getPos().add(direction.multiply(i<4?6:9));
            if(!free(point))continue;
            var hit=mob.getWorld().raycast(new net.minecraft.world.RaycastContext(evidence.add(0,1.5,0),point.add(0,mob.getHeight()*.5,0),net.minecraft.world.RaycastContext.ShapeType.COLLIDER,net.minecraft.world.RaycastContext.FluidHandling.NONE,mob));
            double score=point.distanceTo(evidence)+(hit.getType()==net.minecraft.util.hit.HitResult.Type.BLOCK?20:0)+(previousGoal!=null&&previousGoal.squaredDistanceTo(point)>16?4:0);
            if(score>bestScore){bestScore=score;best=point;}
        }
        return best;
    }
    public Vec3d alternateApproach(Vec3d lastSensed,Vec3d previousApproach){
        Vec3d best=mob.getPos();double score=-1;long limit=deadline();
        for(int i=0;i<8&&System.nanoTime()<limit;i++){
            double angle=i*Math.PI/4;Vec3d point=lastSensed.add(Math.cos(angle)*5,0,Math.sin(angle)*5);
            if(free(point)&&point.squaredDistanceTo(previousApproach)>score){best=point;score=point.squaredDistanceTo(previousApproach);}
        }
        return best;
    }
    public Vec3d ceilingNear(Vec3d near) {
        if(System.nanoTime()>=deadline())return near;
        if(!free(near))return near;
                var hit=mob.getWorld().raycast(new net.minecraft.world.RaycastContext(near.add(0,mob.getHeight()+.05,0),near.add(0,mob.getHeight()+8,0),net.minecraft.world.RaycastContext.ShapeType.COLLIDER,net.minecraft.world.RaycastContext.FluidHandling.NONE,mob));
        if(hit.getType()==net.minecraft.util.hit.HitResult.Type.BLOCK){Vec3d point=new Vec3d(near.x,hit.getPos().y-mob.getHeight()-.02,near.z);if(free(point))return point;}
        return near;
    }
    private boolean continuousWall(BlockPos pos,Vec3d normal) {
        if(normal.y!=0||normal.lengthSquared()==0)return false;
        for(int y=0;y<4;y++){Vec3d p=point(pos.up(y));if(!free(p)||contact(p).dotProduct(normal)<.9)return false;}
        return true;
    }
    private void probeClimb(long deadline) {
        if(climbBase==null){
            Vec3d center=mob.getPos().add(0,mob.getHeight()*.5,0);
            double radius=mob.getWidth()*.5;
            for(Direction side:Direction.Type.HORIZONTAL){
                if(System.nanoTime()>=deadline)return;
                Vec3d direction=Vec3d.of(side.getVector());
                Vec3d endpoint=center.add(direction.multiply(radius+.8));
                if(!mob.getWorld().isChunkLoaded(BlockPos.ofFloored(endpoint)))continue;
                var hit=mob.getWorld().raycast(new net.minecraft.world.RaycastContext(center,center.add(direction.multiply(radius+.8)),net.minecraft.world.RaycastContext.ShapeType.COLLIDER,net.minecraft.world.RaycastContext.FluidHandling.NONE,mob));
                if(hit.getType()!=net.minecraft.util.hit.HitResult.Type.BLOCK)continue;
                Vec3d base=hit.getPos().subtract(direction.multiply(radius+.02)).subtract(0,mob.getHeight()*.5,0);
                if(!free(base)||!mob.getWorld().isSpaceEmpty(mob,mob.getBoundingBox().stretch(base.subtract(mob.getPos())).contract(.001)))continue;
                climbBase=base;climbNormal=direction.multiply(-1);climbPoints.add(base);break;
            }
            if(climbBase==null){probingClimb=false;return;}
        }
        while(probingClimb&&System.nanoTime()<deadline){
            Vec3d next=climbBase.add(0,++climbStep,0),prior=climbPoints.peekLast();
            if(!free(next)||contact(next).dotProduct(climbNormal)<.9||!mob.getWorld().isSpaceEmpty(mob,mob.getBoundingBox().offset(prior.subtract(mob.getPos())).stretch(next.subtract(prior)).contract(.001))){probingClimb=false;climbPoints.clear();return;}
            climbPoints.add(next);
            if(climbStep>=climbHeight){route.addAll(climbPoints);climbPoints.clear();probingClimb=false;searching=false;surfaceRoute=true;return;}
        }
    }
    public void tick(double modulation) {
        long deadline=deadline();
        if(probingClimb)probeClimb(deadline);
        while(searching && !probingClimb && !open.isEmpty() && System.nanoTime()<deadline) {
            Node node=open.remove();if(node.cost()>costs.getOrDefault(node.pos(),Double.POSITIVE_INFINITY))continue;
            if(heuristic(node.pos())<=1 && free(node.pos())) {
                for(BlockPos p=node.pos();!p.equals(origin);p=parents.get(p)) route.addFirst(point(p));
                                Vec3d end=route.isEmpty()?mob.getPos():route.peekLast();
                if(free(destination)&&mob.getWorld().isSpaceEmpty(mob,mob.getBoundingBox().offset(end.subtract(mob.getPos())).stretch(destination.subtract(end)).contract(.001)))route.addLast(destination);
                searching=false;break;
            }
            if(costs.size()>=4096){open.clear();break;}
            for(Direction direction:Direction.values()) {
                BlockPos p=node.pos().offset(direction);
                if(p.getSquaredDistance(origin)>4096 || !free(p))continue;
                Vec3d delta=point(p).subtract(point(node.pos()));
                Box swept=mob.getBoundingBox().offset(point(node.pos()).subtract(mob.getPos())).stretch(delta).contract(.001);
                if(!mob.getWorld().isSpaceEmpty(mob,swept))continue;
                boolean ground=supported(p);Vec3d normal=contact(point(p));
                boolean climb=destination.y-origin.getY()>=4 && continuousWall(p,normal);
                double cost=node.cost()+(ground?1:climb?1.6:3)+failedSegments.getOrDefault(new Segment(node.pos(),p),0);
                if(cost>=costs.getOrDefault(p,Double.POSITIVE_INFINITY))continue;
                costs.put(p,cost);parents.put(p,node.pos());open.add(new Node(p,cost,cost+heuristic(p)));
            }
        }
        if(open.isEmpty())searching=false;
        Vec3d desired=Vec3d.ZERO;
        if(!route.isEmpty()) {
            Vec3d delta=route.peek().subtract(mob.getPos());
            if(delta.length()<(route.size()==1?.04:.2)){route.remove();blocked=0;}
            else desired=delta.normalize().multiply(Math.min(delta.length()*.5,.11+.09*modulation));
            if(previous!=null && previous.squaredDistanceTo(mob.getPos())<.0001)blocked++;else blocked=0;
            if(blocked>20){if(!route.isEmpty())penalize(BlockPos.ofFloored(route.peek()));Vec3d retry=destination;clear();toward(retry);}
        }
        if(surfaceRoute&&route.isEmpty()){surfaceRoute=false;goal=null;}
        previous=mob.getPos();
        Vec3d normal=contact(mob.getPos());
        boolean groundContact=!mob.getWorld().isSpaceEmpty(mob,mob.getBoundingBox().offset(0,-.08,0));
        String mode=groundContact && desired.y<=.02?"ground":normal.y<0?"ceiling":surfaceRoute && normal.y==0 && normal.lengthSquared()>0?"wall":"air";
        mob.setSurface(mode,mode.equals("ground")||normal.lengthSquared()==0?new Vec3d(0,1,0):normal);
        if(mode.equals("ground"))desired=new Vec3d(desired.x,-.08,desired.z);
        mob.setVelocity(mob.getVelocity().lerp(desired,.22));
        if(desired.horizontalLengthSquared()>.0001) {
            float wanted=(float)Math.toDegrees(Math.atan2(-desired.x,desired.z));
            float turn=net.minecraft.util.math.MathHelper.wrapDegrees(wanted-mob.getYaw());
            mob.setYaw(mob.getYaw()+Math.max(-8,Math.min(8,turn)));mob.setBodyYaw(mob.getYaw());mob.setHeadYaw(mob.getYaw());
        }
    }
}










