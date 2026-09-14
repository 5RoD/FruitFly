package dev.flybrain;
import dev.flybrain.behavior.PredatorRules;
public final class FeintCheck {
 public static void main(String[] args){run();System.out.println("FeintCheck PASS lifecycle, cooldown, physical withdrawal and interruption");}
 public static void run(){
  var feint=new PredatorRules.Feint();assert feint.begin(0);assert feint.phase()==1;
  feint.advance(59,true,true);assert feint.phase()==1;feint.advance(60,true,true);assert feint.phase()==2;
  feint.advance(179,true,true);assert feint.phase()==2;feint.advance(180,true,true);assert feint.phase()==0;
  assert !feint.begin(599);assert feint.begin(600);feint.interrupt();assert feint.phase()==0;assert !feint.begin(601);
  assert feint.begin(1200);feint.advance(1260,true,false);assert feint.phase()==0;
  assert feint.begin(1800);feint.advance(1801,false,true);assert feint.phase()==0;
 }
}
