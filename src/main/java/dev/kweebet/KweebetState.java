package dev.kweebet;

import com.google.gson.Gson;
import com.hypixel.hytale.codec.*;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Resource;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.*;

/** One world resource. Item inventory and this resource still have independent save boundaries. */
public final class KweebetState implements Resource<EntityStore> {
    static final Gson JSON=new Gson();
    public static final BuilderCodec<KweebetState> CODEC=BuilderCodec.builder(KweebetState.class,KweebetState::new)
        .append(new KeyedCodec<>("CasinoWorldV1",Codec.STRING),(s,v)->s.load(v),KweebetState::save).add().build();
    transient boolean recoveryPending;
    transient WorldDisplays.Runtime worldDisplays=new WorldDisplays.Runtime();
    Map<String,Machine> machines=new TreeMap<>();
    static final class Terms {
        String item="Ingredient_Bar_Iron",bonusItem="Ingredient_Bar_Copper";
        int min=4,max=64,bonus=0;boolean inviteOnly;
        Set<String> invited=new TreeSet<>();
    }
    static final class Machine {
        String owner,kind;long version=1,serial;boolean accepting=true;
        Terms config=new Terms();
        Map<String,Integer> bank=new TreeMap<>();
        Map<String,Map<String,Integer>> claims=new TreeMap<>();
        Map<String,Long> generated=new TreeMap<>(),consumed=new TreeMap<>();
        Round round;
        List<String> history=new ArrayList<>();
    }
    static final class Round {
        long id,deadline,started,turnDeadline;
        String phase="Lobby",turn="",winner="";
        Terms terms;
        LinkedHashMap<String,Seat> seats=new LinkedHashMap<>();
        int pot,bonus,crashAt,deckIndex,endMultiplier;boolean generatedReturns;
        List<Integer> deck=new ArrayList<>(),dealer=new ArrayList<>();
        List<String> finish=new ArrayList<>();
    }
    static final class Seat {
        String name,result="";int stake,reserved,returned,auto;
        boolean done,doubled,bot;
        List<Integer> hand=new ArrayList<>(),plinkoPath=new ArrayList<>();
    }
    String save(){return JSON.toJson(machines);}
    void load(String data){
        var type=new com.google.gson.reflect.TypeToken<TreeMap<String,Machine>>(){}.getType();
        Map<String,Machine> parsed=JSON.fromJson(data,type);
        if(parsed==null||parsed.size()>10000)throw new IllegalArgumentException("Invalid Casino world data");
        for(var e:parsed.entrySet())Engine.validate(e.getValue());
        machines=parsed;recoveryPending=true;
    }
    @Override public KweebetState clone(){var s=new KweebetState();s.load(save());return s;}
}
