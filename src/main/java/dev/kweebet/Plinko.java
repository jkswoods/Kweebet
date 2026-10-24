package dev.kweebet;
import java.util.*;

/** Eight fair server choices; nine disclosed, exactly payable total-return buckets. */
final class Plinko {
    static final int ROWS=8,DURATION=4000;
    private static final int[] QUARTERS={40,12,6,3,1,3,6,12,40};
    static int bucket(List<Integer> path){Engine.require(path!=null&&path.size()==ROWS,"Invalid Plinko path");int slot=0;for(int step:path){Engine.require(step==0||step==1,"Invalid peg choice");slot+=step;}return slot;}
    static int payout(int stake,int slot){Engine.require(stake>0&&stake%4==0,"Plinko stakes must be multiples of four");Engine.require(slot>=0&&slot<=ROWS,"Invalid payout bucket");return Math.multiplyExact(stake/4,QUARTERS[slot]);}
    static String multiplier(int slot){return switch(QUARTERS[slot]){case 40->"10×";case 12->"3×";case 6->"1.5×";case 3->"0.75×";default->"0.25×";};}
    static String preview(int stake){var text=new ArrayList<String>();for(int i=0;i<=ROWS;i++)text.add(Integer.toString(payout(stake,i)));return String.join(" / ",text);}
    static List<Integer> path(){var p=new ArrayList<Integer>();for(int i=0;i<ROWS;i++)p.add(Engine.RANDOM.nextBoolean()?1:0);return p;}
    static double[] position(List<Integer> path,double progress){
        double clamped=Math.clamp(progress,0,ROWS);int row=(int)clamped;double x=640;for(int i=0;i<row;i++)x+=path.get(i)==1?69:-69;
        if(row==ROWS)return new double[]{x,556};
        double t=clamped-row,y=70+row*64;
        if(row==0&&t<.2)return new double[]{x,22+48*t/.2};
        if(row==0)t=(t-.2)/.8;
        double ease=t*t*(3-2*t);x+=(path.get(row)==1?69:-69)*ease;
        double fall=row==7?38:64;
        return new double[]{x,y+fall*t-18*Math.sin(Math.PI*t)};
    }
}
