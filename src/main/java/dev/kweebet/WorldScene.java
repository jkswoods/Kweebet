package dev.kweebet;

import java.util.*;
import static dev.kweebet.KweebetState.*;

/** A presentation-only projection. Never changes a round or exposes hidden cards/deadlines. */
final class WorldScene {
    static final double HEIGHT=.68;
    record Piece(String role,String asset,double x,double y,double z,float scale,float roll,float yaw) {
        Piece(String role,String asset,double x,double y,double z,float scale){this(role,asset,x,y,z,scale,0,0);}
        Piece(String role,String asset,double x,double y,double z,float scale,float roll){this(role,asset,x,y,z,scale,roll,0);}
    }
    static Piece p(String role,String asset,double x,double y,double z){return new Piece(role,asset,x,y,z,1);}
    static List<Piece> scene(Machine m,long now){
        var out=new ArrayList<Piece>();var r=m.round;
        switch(m.kind){
            case "Duck"->{
                var entries=r==null?List.<Map.Entry<String,Seat>>of():new ArrayList<>(r.seats.entrySet());
                int count=Math.max(4,entries.size());
                for(int i=0;i<count;i++){
                    double x=count>4?-24.5+i*7:-21+i*14,z=38;float yaw=0;
                    if(i<entries.size()&&r.started>0&&!r.finish.isEmpty()){
                        int rank=r.finish.indexOf(entries.get(i).getKey());
                        double t=r.phase.equals("Finished")?1:Math.clamp((now-r.started)/((r.generatedReturns?3300.0:6200.0)+rank*220),0,1);
                        // Every lane stays inset. Resting offsets retain a visible, correct finishing order.
                        z=38-(80-rank*2.2)*t;
                        if(r.phase.equals("Finished")){
                            double back=duckReturn(r,now),ease=back*back*(3-2*back);
                            z+=(38-z)*ease;if(back>0&&back<1)yaw=(float)Math.PI;
                        }
                    }
                    out.add(new Piece("duck"+i,"CW_DisplayDuck"+i,x,39*HEIGHT,z,1,0,yaw));
                    if(i<entries.size()&&!entries.get(i).getValue().bot)
                        out.add(new Piece("player-marker"+i,"CW_PlayerMarker",x,39*HEIGHT+12,z,.22f));
                }
            }
            case "Plinko"->{
                var seats=r==null?List.<Seat>of():new ArrayList<>(r.seats.values());
                if(seats.isEmpty())out.add(new Piece("ball0","CW_DisplayBall",0,92*HEIGHT,1,.8f));
                for(int i=0;i<seats.size();i++){
                    var path=seats.get(i).plinkoPath;if(path.isEmpty())continue;
                    double progress=r.phase.equals("Finished")?8:plinkoProgress(now-r.started);
                    double[] at=ball(path,progress);
                    if(r.phase.equals("Playing")&&now-r.started<200)at=new double[]{0,92-2.5*Math.pow(Math.max(0,now-r.started)/200.0,2)};
                    out.add(new Piece("ball"+i,"CW_DisplayBall",at[0],at[1]*HEIGHT,1-i*.15,.8f,(float)(-at[0]/2.24)));
                    int row=(int)progress;double fraction=progress-row;
                    if(r.phase.equals("Playing")&&now-r.started>=200&&row<8&&fraction<.33){
                        double hitX=0;for(int j=0;j<row;j++)hitX+=path.get(j)==1?3.5:-3.5;
                        out.add(new Piece("peg-impact"+i,"CW_PegFlash",hitX,(85-row*6.5)*HEIGHT,2.5,(float)(1.2-fraction*2.4)));
                    }
                }
            }
            case "Blackjack"->{
                if(r==null){out.add(new Piece("dealer0","CW_CardBack",-12,35.5*HEIGHT,12.5,.3f));out.add(new Piece("dealer1","CW_CardBack",12,35.5*HEIGHT,12.5,.3f));break;}
                cards(out,"dealer-",r.dealer,0,12.5,76,!r.phase.equals("Finished"));
                int count=r.seats.size(),i=0;
                for(var e:r.seats.entrySet()){
                    double x=count<=1?0:-30+(i%4)*20,z=count<=4?-12.5:-4-(i/4)*11;
                    cards(out,"hand"+i+"-",e.getValue().hand,x,z,count<=1?64:16,false);i++;
                }
            }
            case "Crash"->{
                int value=crashValue(m,now);String digits=String.format(Locale.ROOT,"%d.%02d",value/100,value%100);
                String colour=r==null||!r.phase.equals("Finished")?"Green":r.endMultiplier>0?"Gold":"Red";
                double width=(digits.length()-1)*10.5+4.5+8,x=width/2;int n=0;
                for(char digit:digits.toCharArray()){
                    double cell=digit=='.'?4.5:10.5;
                    out.add(new Piece(digit=='.'?"decimal":"digit"+n++,digit=='.'?"CW_Dot"+colour:"CW_Digit"+colour+digit,
                                      x-cell/2,58*HEIGHT,6.8,.16f));x-=cell;
                }
                out.add(new Piece("times","CW_Times"+colour,x-4,58*HEIGHT,6.8,.12f));
            }
            default->throw new IllegalArgumentException("Unknown game");
        }
        if(won(m,null))out.add(new Piece("winner","CW_Winner",0,m.kind.equals("Crash")?65:m.kind.equals("Plinko")?82:50,-4,.3f));
        return List.copyOf(out);
    }
    static double duckReturn(Round r,long now){return r.started<=0?1:Math.clamp((now-r.started-(r.generatedReturns?4500:8000)-500)/900.0,0,1);}
    /** Only settled, profitable real players celebrate; pushes/refunds/bots do not. */
    static boolean won(Machine m,String actor){
        var r=m.round;if(r==null)return false;
        return r.seats.entrySet().stream().anyMatch(e->{var s=e.getValue();return (actor==null||actor.equals(e.getKey()))&&!s.bot&&s.done&&!s.result.equals("Refund")&&
            (m.kind.equals("Duck")?r.phase.equals("Finished")&&e.getKey().equals(r.winner):s.returned>s.stake);});
    }
    static boolean active(Machine m,String actor){var r=m.round;var s=r==null?null:r.seats.get(actor);return Engine.live(m)&&s!=null&&s.result.isEmpty();}
    static boolean settled(Machine m,String actor){var r=m.round;var s=r==null?null:r.seats.get(actor);return s!=null&&!s.bot&&s.done&&!s.result.isEmpty();}
    static int net(Machine m,String actor){
        if(!settled(m,actor))return 0;var r=m.round;var s=r.seats.get(actor);
        int returned=m.kind.equals("Duck")&&!r.generatedReturns&&actor.equals(r.winner)?r.seats.values().stream().mapToInt(v->v.stake).sum():s.returned;
        return returned-s.stake;
    }
    static String resultTitle(Machine m,String actor){
        if(!settled(m,actor))return "";var s=m.round.seats.get(actor);
        if(s.result.equals("Refund"))return "REFUNDED";
        if(m.kind.equals("Blackjack")&&Engine.points(s.hand)>21)return "BUST";
        return net(m,actor)>0?"WINNER":net(m,actor)<0?"YOU LOST":"PUSH";
    }
    static String resultDelta(Machine m,String actor){
        if(!settled(m,actor))return "";int n=net(m,actor);var r=m.round;
        String delta=(n>=0?"+":"")+n+" "+KweebetPage.itemName(r.terms.item);
        if(m.kind.equals("Duck")&&actor.equals(r.winner)&&r.terms.bonus>0&&!r.seats.get(actor).result.equals("Refund"))delta+="  +"+r.terms.bonus+" "+KweebetPage.itemName(r.terms.bonusItem);
        return delta;
    }
    static int potential(Machine m,String actor,long now){
        if(!active(m,actor))return 0;var r=m.round;var s=r.seats.get(actor);
        return switch(m.kind){case "Crash"->(int)((long)s.stake*Engine.multiplier(r,now)/100);case "Duck"->r.generatedReturns?s.stake*4:r.pot;case "Blackjack"->Engine.natural(s.hand)&&!s.doubled?s.stake*5/2:s.stake*2;default->s.stake*10;};
    }
    static void cards(List<Piece> out,String prefix,List<Integer> cards,double cx,double z,double span,boolean hideHole){
        float scale=span>=60?.3f:.1875f;
        double step=cards.size()<2?0:Math.min(23,(span-64*scale)/Math.max(1,cards.size()-1));
        for(int i=0;i<cards.size();i++)out.add(new Piece(prefix+i,hideHole&&i==1?"CW_CardBack":"CW_Card"+cards.get(i),cx+(i-(cards.size()-1)/2.0)*step,35.6*HEIGHT+i*.025,z,scale));
    }
    static int crashValue(Machine m,long now){
        var r=m.round;if(r==null||r.phase.equals("Lobby"))return 100;
        return r.phase.equals("Finished")?r.endMultiplier>0?r.endMultiplier:Math.min(Engine.CRASH_CAP,r.crashAt):Engine.multiplier(r,now);
    }
    static double[] ball(List<Integer> path,double progress){
        Plinko.bucket(path);double t=Math.clamp(progress,0,8);int row=Math.min(7,(int)t);double f=t-row;
        double x=0;for(int i=0;i<row;i++)x+=(path.get(i)==1?3.5:-3.5);
        double y0=89.5-row*6.5,y1=row==7?29:89.5-(row+1)*6.5;
        // A peg gives an upward impulse; gravity accelerates the fall to the next
        // locked path node. Direction changes also change the visible rolling spin.
        double impulse=12;
        return new double[]{x+(path.get(row)==1?3.5:-3.5)*f,y0+impulse*f-(impulse+y0-y1)*f*f};
    }
    static double plinkoProgress(long elapsed){return Math.clamp((elapsed-200)/450.0,0,8);}
}
