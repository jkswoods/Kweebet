package dev.kweebet;
import java.util.*;
import java.nio.file.*;
import static dev.kweebet.KweebetState.*;
/** Source-render fixtures only; excluded from the player JAR and native acceptance. */
public final class WorldSceneExamples {
    public static void main(String[] args)throws Exception{
        var scenes=new LinkedHashMap<String,Object>();
        for(String kind:List.of("Duck","Plinko","Blackjack","Crash")){
            var m=new Machine();m.kind=kind;var r=new Round();m.round=r;r.id=1;r.phase="Playing";r.started=1000;r.generatedReturns=true;
            var s=new Seat();r.seats.put("a",s);
            if(kind.equals("Duck")){for(String id:List.of("b","c","d")){var bot=new Seat();bot.bot=true;r.seats.put(id,bot);}r.finish.addAll(List.of("b","d","a","c"));r.winner="b";}
            if(kind.equals("Plinko"))s.plinkoPath=List.of(1,0,1,1,0,1,1,1);
            if(kind.equals("Blackjack")){r.dealer.addAll(List.of(5,9));s.hand.addAll(List.of(0,24,2));}
            scenes.put(kind,WorldScene.scene(m,kind.equals("Crash")?13500:3200));
        }
        Files.writeString(Path.of("data/world-source-scenes.json"),JSON.toJson(Map.of("scope","Synthetic scene fixtures projected by production WorldScene; source render only, not native gameplay", "scenes",scenes)));
    }
}
