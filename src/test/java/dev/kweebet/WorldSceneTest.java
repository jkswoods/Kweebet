package dev.kweebet;

import java.util.*;
import static dev.kweebet.KweebetState.*;

/** Actual outcome/geometry and disclosure checks; not rendered native or multiplayer evidence. */
public final class WorldSceneTest {
    static int checks;
    static void check(boolean condition,String text){if(!condition)throw new AssertionError(text);checks++;}
    static Machine machine(String kind){var m=new Machine();m.kind=kind;m.owner=UUID.randomUUID().toString();return m;}
    static Round round(Machine m){var r=new Round();r.id=1;r.generatedReturns=true;r.phase="Playing";r.started=1000;r.terms=new Terms();m.round=r;return r;}
    static WorldScene.Piece piece(List<WorldScene.Piece> scene,String role){return scene.stream().filter(p->p.role().equals(role)).findFirst().orElseThrow();}
    public static void main(String[]args){
        for(int bits=0;bits<256;bits++){
            var m=machine("Plinko");var r=round(m);var seat=new Seat();r.seats.put("test",seat);
            for(int i=0;i<8;i++)seat.plinkoPath.add((bits>>i)&1);
            int bucket=Plinko.bucket(seat.plinkoPath);var end=WorldScene.ball(seat.plinkoPath,8);
            check(end[0]==-28+bucket*7&&end[1]==29,"Ball rests in actual paid world bucket");
            boolean inside=true;for(int frame=0;frame<=240;frame++){var xy=WorldScene.ball(seat.plinkoPath,frame/30.0);inside&=xy[0]>=-28&&xy[0]<=28&&xy[1]>=29&&xy[1]<=93;}
            check(inside,"Every path remains inside the actual physical peg field");r.phase="Finished";
            check(piece(WorldScene.scene(m,9000),"ball0").x()==-28+bucket*7,"Saved finished ball cannot resample outcome");
        }
        var bj=machine("Blackjack");var r=round(bj);r.dealer=new ArrayList<>(List.of(0,12));var seat=new Seat();seat.hand.addAll(List.of(4,9));r.seats.put("a",seat);
        var hidden=WorldScene.scene(bj,1000);check(piece(hidden,"dealer-1").asset().equals("CW_CardBack"),"Hidden hole card never selects its real face asset");
        r.dealer.set(1,27);check(hidden.equals(WorldScene.scene(bj,1000)),"Changing a hidden card produces byte-equivalent public scene");
        r.phase="Finished";check(piece(WorldScene.scene(bj,2000),"dealer-1").asset().equals("CW_Card27"),"Settlement exposes actual dealer face");
        for(int c=0;c<52;c++){seat.hand=List.of(c);check(piece(WorldScene.scene(bj,3000),"hand0-0").asset().equals("CW_Card"+c),"Rank/suit projection preserves all52 cards");}
        var crash=machine("Crash");r=round(crash);r.crashAt=900;var publicScene=WorldScene.scene(crash,2000);r.crashAt=400;
        check(publicScene.equals(WorldScene.scene(crash,2000)),"Future Crash threshold never affects live public scene");
        check(WorldScene.crashValue(crash,2000)==120,"Live screen shows actual current multiplier");
        r.phase="Finished";r.endMultiplier=200;check(WorldScene.crashValue(crash,999999)==200,"Cash-out screen retains paid multiplier instead of future crash");
        r.endMultiplier=0;check(WorldScene.crashValue(crash,999999)==400,"Loss screen shows the actual settled crash");
        var own=new Seat();own.stake=4;own.name="Fixture";r.seats.put("me",own);r.phase="Playing";
        check(WorldScene.active(crash,"me")&&WorldScene.potential(crash,"me",2000)==4,"Active Crash potential uses current multiplier and integer return");
        r.crashAt=700;check(WorldScene.potential(crash,"me",2000)==4,"Potential cannot disclose a future crash");
        own.done=true;own.returned=4;own.result="Push";
        check(!WorldScene.active(crash,"me")&&!WorldScene.won(crash,"me"),"Push and completed bets do not masquerade as active wins");
        own.returned=8;own.result="Cash out";check(WorldScene.won(crash,"me"),"Settled profit celebrates the real player");
        own.bot=true;check(!WorldScene.won(crash,null),"Bot profits never announce a player win");own.bot=false;
        own.result="Refund";check(!WorldScene.won(crash,null),"Refund never triggers a victory");
        check(!WorldDisplays.winTransition(null,r.id,Set.of("me")),"Loading an already won round never replays fanfare");
        var observed=new WorldDisplays.Observed(r.id,"Playing",0,0,Set.of());
        check(WorldDisplays.winTransition(observed,r.id,Set.of("me")),"A newly settled actual win triggers once");
        observed=new WorldDisplays.Observed(r.id,"Finished",0,0,Set.of("me"));
        check(!WorldDisplays.winTransition(observed,r.id,Set.of("me")),"Repeated polling does not repeat victory effects");
        check(WorldDisplays.winTransition(observed,r.id,Set.of("me","other")),"A later second player win is still celebrated");
        own.result="Cashed out";own.returned=12;
        check(WorldScene.resultTitle(crash,"me").equals("WINNER")&&WorldScene.resultDelta(crash,"me").equals("+8 Iron ingots"),"Result shows actual net gain, not gross returned stake");
        own.returned=1;check(WorldScene.resultTitle(crash,"me").equals("YOU LOST")&&WorldScene.resultDelta(crash,"me").equals("-3 Iron ingots"),"Partial return shows exact net loss");
        own.returned=4;check(WorldScene.resultTitle(crash,"me").equals("PUSH")&&WorldScene.resultDelta(crash,"me").equals("+0 Iron ingots"),"Equal return is a push, never a win or loss");
        own.done=false;own.result="";check(!WorldScene.settled(crash,"me")&&WorldScene.resultTitle(crash,"me").isEmpty(),"Active wager cannot reveal a settlement banner");
        own.done=true;own.result="Bust";own.returned=0;own.hand=List.of(9,9,2);crash.kind="Blackjack";
        check(WorldScene.resultTitle(crash,"me").equals("BUST")&&WorldScene.resultDelta(crash,"me").equals("-4 Iron ingots"),"Bust is prominent and uses actual paid stake");
        own.stake=8;own.doubled=true;check(WorldScene.resultDelta(crash,"me").equals("-8 Iron ingots"),"Double loss includes both actual debits");
        own.result="Refund";own.returned=8;check(WorldScene.resultTitle(crash,"me").equals("REFUNDED")&&WorldScene.net(crash,"me")==0,"Refund remains neutral and cannot masquerade as Bust");
        var duck=machine("Duck");r=round(duck);r.finish.addAll(List.of("b","d","a","c"));r.winner="b";
        for(String id:List.of("a","b","c","d"))r.seats.put(id,new Seat());
        String before=JSON.toJson(duck);var race=WorldScene.scene(duck,3000);
        check(piece(race,"duck1").z()<piece(race,"duck3").z()&&piece(race,"duck3").z()<piece(race,"duck0").z(),"Live visible race order agrees with server finishing order");
        r.phase="Finished";var finish=WorldScene.scene(duck,5800);check(piece(finish,"duck1").z()==-42,"Actual winning duck reaches finish line");
        check(piece(finish,"duck0").x()==-21&&piece(finish,"duck3").x()==21,"Outer ducks stay inside approved raised guards");
        var returning=piece(WorldScene.scene(duck,6450),"duck1");check(returning.z()>-42&&returning.z()<38&&returning.yaw()!=0,"Ducks turn and move back only after settlement and a result hold");
        var reset=piece(WorldScene.scene(duck,6900),"duck1");check(reset.z()==38&&reset.yaw()==0,"Every completed duck returns to the start facing the next race");
        check(r.winner.equals("b")&&r.finish.equals(List.of("b","d","a","c")),"Presentation reset never changes the saved paid finishing order");
        String saved=JSON.toJson(duck);WorldScene.scene(duck,10000);check(saved.equals(JSON.toJson(duck)),"Scene projection has no authority over saved outcome, inventory or claims");
        System.out.println("PASS "+checks+" world outcome/geometry/secrecy checks; pure JVM, native rendering pending");
    }
}
