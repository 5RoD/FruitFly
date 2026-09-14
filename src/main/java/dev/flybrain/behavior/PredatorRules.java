package dev.flybrain.behavior;
import java.util.LinkedHashMap;
import java.util.List;
/** Tick-based encounter invariants. Call only on unpaused simulation ticks. */
public final class PredatorRules {
 private PredatorRules() {}
 public static int feedingDamage(int tick) { return tick==0?2:tick>0 && tick<=40 && tick%10==0?1:0; }
 public static float scale(float blood) { return blood>=24?1.25f:blood>=16?1.16f:blood>=8?1.08f:1; }
 public static final class FreshGate {
  private long last=-1; private int fresh;
  public boolean update(long sequence) {
   if(sequence<0){fresh=0;last=-1;return false;}
   if(sequence!=last){last=sequence;fresh=Math.min(2,fresh+1);}
   return fresh>=2;
  }
 }
 public static final class Memory<T> {
  private record Visit(int tick,int count){}
  private final LinkedHashMap<T,Visit> places=new LinkedHashMap<>();
  public void observe(T place,int tick){
   expire(tick);Visit prior=places.remove(place);places.put(place,new Visit(tick,prior==null?1:Math.min(255,prior.count()+1)));
   if(places.size()>8)places.remove(places.entrySet().stream().min(java.util.Comparator.<java.util.Map.Entry<T,Visit>>comparingInt(e->e.getValue().count()).thenComparingInt(e->e.getValue().tick())).orElseThrow().getKey());
  }
  private void expire(int tick){places.values().removeIf(visit->tick-visit.tick()>=12000);}
  public int size(int tick){expire(tick);return places.size();}
  public List<T> places(int tick){expire(tick);return places.entrySet().stream().sorted(java.util.Comparator.<java.util.Map.Entry<T,Visit>>comparingInt(e->e.getValue().count()).thenComparingInt(e->e.getValue().tick())).map(java.util.Map.Entry::getKey).toList();}
 } public static final class Reentry {
  private int outside; private boolean armed;
  public boolean update(boolean inside){
   if(!inside){outside=Math.min(40,outside+1);if(outside==40)armed=true;return false;}
   outside=0;if(!armed)return false;armed=false;return true;
  }
 }
 public static final class SceneLatch {
  private boolean spent;
  public SceneLatch(boolean spent){this.spent=spent;}
  public boolean spent(){return spent;}
  public boolean start(){if(spent)return false;spent=true;return true;}
  public void reset(){spent=false;}
 }
 /** Bounded, tick-scaled drives updated from sensed evidence, never hidden target positions. */
 public static final class Drives {
  private double hunger=.55,fear=.1,curiosity=.65,confidence=.5;
  private int stare;
  private static double clamp(double value){return Math.max(0,Math.min(1,value));}
  public void advance(boolean visible,boolean watched,boolean blocked,double neuralEscape){
   stare=watched?Math.min(40,stare+1):0;
   hunger=clamp(hunger+1.0/24000);
   fear=clamp(fear+(watched?.007:-.0015)+Math.max(0,Math.min(1,neuralEscape))*.001);
   curiosity=clamp(curiosity+(visible?.0015:-.0005)-fear*.0005);
   confidence=clamp(confidence+(visible?.0015:.0002)-(blocked?.008:0)-fear*.001);
  }
  public void reacted(boolean approached,boolean looked,boolean withdrew){fear=clamp(fear+(approached?.15:looked?.08:0));confidence=clamp(confidence+(withdrew?.08:approached?-.1:0));}
  public void hurt(){fear=clamp(fear+.35);confidence=clamp(confidence-.25);}
  public void fed(double health){if(Double.isFinite(health)&&health>0){hunger=clamp(hunger-health/12);confidence=clamp(confidence+.03);}}
  public boolean startled(){return stare>=40;}
  public double hunger(){return hunger;}
  public double fear(){return fear;}
  public double curiosity(){return curiosity;}
  public double confidence(){return confidence;}
 } /** A bounded visible withdrawal and alternate return, with a cooldown surviving interruption. */
 public static final class Feint {
  private int phase,until,next;
  public int phase(){return phase;}
  public boolean begin(int now){if(phase!=0||now<next)return false;phase=1;until=now+60;next=now+600;return true;}
  public void advance(int now,boolean recentEvidence,boolean moved){
   if(!recentEvidence){interrupt();return;}
   if(now<until)return;
   if(phase==1&&moved){phase=2;until=now+120;}else phase=0;
  }
  public void interrupt(){phase=0;}
 }}




