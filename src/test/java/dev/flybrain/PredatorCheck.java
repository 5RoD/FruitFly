package dev.flybrain;
import dev.flybrain.behavior.PredatorRules;
public final class PredatorCheck {
 public static void main(String[] args) {
  FeintCheck.run();
  var gate = new PredatorRules.FreshGate();
  assert !gate.update(-1); assert !gate.update(1); assert !gate.update(1);
  assert gate.update(2); assert gate.update(2); assert !gate.update(-1);
  assert !gate.update(2); assert !gate.update(2); assert gate.update(3);
  assert PredatorRules.feedingDamage(0)==2;
  int damage=0; for(int i=0;i<=50;i++) damage+=PredatorRules.feedingDamage(i);
  assert damage==6 : damage;
  assert PredatorRules.scale(7.99f)==1; assert PredatorRules.scale(8)==1.08f;
  assert PredatorRules.scale(16)==1.16f; assert PredatorRules.scale(24)==1.25f;
  var memory=new PredatorRules.Memory<String>();
  for(int i=0;i<10;i++) memory.observe("place"+i, i);
  assert memory.size(10)==8; assert memory.size(12010)==0;
  var frequent=new PredatorRules.Memory<String>();for(int i=0;i<8;i++)frequent.observe("frequent",i);for(int i=8;i<18;i++)frequent.observe("new"+i,i);
  assert frequent.size(18)==8;assert frequent.places(18).get(7).equals("frequent");assert frequent.size(12018)==0;
  var trigger=new PredatorRules.Reentry();
  assert !trigger.update(true); for(int i=0;i<39;i++)assert !trigger.update(false);
  assert !trigger.update(true); for(int i=0;i<40;i++)assert !trigger.update(false);
  assert trigger.update(true); assert !trigger.update(true);
  var latch=new PredatorRules.SceneLatch(false);
  assert latch.start();assert !latch.start();assert new PredatorRules.SceneLatch(latch.spent()).spent();latch.reset();assert latch.start();
  var drives=new PredatorRules.Drives();
  for(int i=0;i<39;i++){drives.advance(true,true,false,0);assert !drives.startled();}
  drives.advance(true,true,false,0);assert drives.startled();drives.advance(true,false,false,0);assert !drives.startled();
  double fearBefore=drives.fear();drives.reacted(true,false,false);assert drives.fear()>fearBefore;
  drives.hurt();assert drives.fear()>.3;double hungry=drives.hunger();drives.fed(6);assert drives.hunger()<hungry;
  for(int i=0;i<100000;i++)drives.advance(i%2==0,i%7==0,i%3==0,1);
  for(double value:new double[]{drives.hunger(),drives.fear(),drives.curiosity(),drives.confidence()})assert value>=0&&value<=1;
  System.out.println("PredatorCheck PASS: distinct-fresh freeze gate, feeding cap, growth thresholds, memory expiry/cap, reentry dwell, one-shot reset, bounded drives and 40-tick reaction");
 }
}





