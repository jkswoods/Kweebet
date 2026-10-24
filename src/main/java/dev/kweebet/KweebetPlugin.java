package dev.kweebet;

import com.hypixel.hytale.component.*;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.EntityEventSystem;
import com.hypixel.hytale.component.system.tick.TickingSystem;
import com.hypixel.hytale.protocol.SoundCategory;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.asset.type.soundevent.config.SoundEvent;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.event.events.ecs.*;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.Interaction;
import com.hypixel.hytale.server.core.plugin.*;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.*;
import com.hypixel.hytale.server.core.universe.world.chunk.section.BlockSection;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.*;

public class KweebetPlugin extends JavaPlugin {
    static KweebetPlugin instance;static ResourceType<EntityStore,KweebetState> stateType;
    static WorldDisplays displays;
    static final Set<KweebetPage> pages=java.util.concurrent.ConcurrentHashMap.newKeySet();
    public KweebetPlugin(JavaPluginInit init){super(init);}
    @Override protected void setup(){
        instance=this;stateType=getEntityStoreRegistry().registerResource(KweebetState.class,"CasinoWorksWorldV1",KweebetState.CODEC);
        WorldDisplays.tagType=getEntityStoreRegistry().registerComponent(WorldDisplays.Tag.class,"CasinoWorldDisplayV1",WorldDisplays.Tag.CODEC);
        displays=new WorldDisplays();getEntityStoreRegistry().registerSystem(displays);
        getCodecRegistry(Interaction.CODEC).register("CW_Open",OpenInteraction.class,OpenInteraction.CODEC);
        getEntityStoreRegistry().registerSystem(new Pulse());getEntityStoreRegistry().registerSystem(new Placement());getEntityStoreRegistry().registerSystem(new Breaking());getEntityStoreRegistry().registerSystem(new Damage());
    }
    static KweebetState state(Store<EntityStore>s){return s.getResource(stateType);}
    static String key(int x,int y,int z){return x+":"+y+":"+z;}
    static String kind(String id){return switch(id){case "CW_DuckRace"->"Duck";case "CW_Blackjack"->"Blackjack";case "CW_Crash"->"Crash";case "CW_Plinko"->"Plinko";default->null;};}
    static String block(World w,int x,int y,int z){var section=w.getChunkStore().getChunkSectionReferenceAtBlock(x,y,z);if(section==null||!section.isValid())return null;var blocks=w.getChunkStore().getStore().getComponent(section,BlockSection.getComponentType());if(blocks==null)return null;var b=BlockType.getAssetMap().getAsset(blocks.get(x&31,y&31,z&31));return b==null?null:b.getId();}
    static boolean usable(Store<EntityStore>s,PlayerRef p,String at){try{var v=at.split(":");int x=Integer.parseInt(v[0]),y=Integer.parseInt(v[1]),z=Integer.parseInt(v[2]);var m=state(s).machines.get(at);return m!=null&&m.kind.equals(kind(block(s.getExternalData().getWorld(),x,y,z)))&&p.getTransform().getPosition().distanceSquared(x+.5,y+.5,z+.5)<=64;}catch(RuntimeException ex){return false;}}
    static void cue(Store<EntityStore>s,Ref<EntityStore>r,String role){var map=SoundEvent.getAssetMap();String id="CW_SFX_"+role;if(map.getAsset(id)!=null)SoundUtil.playSoundEvent2d(r,map.getIndex(id),SoundCategory.UI,s);}
    void open(Store<EntityStore>s,Ref<EntityStore>ref,PlayerRef p,String at){
        if(!usable(s,p,at)){p.sendMessage(Message.raw("Kweebet: ownership not registered yet, or machine unavailable. Place a freshly crafted machine."));return;}
        var player=s.getComponent(ref,Player.getComponentType());if(player==null)return;var page=new KweebetPage(p,at);player.getPageManager().openCustomPage(ref,s,page);cue(s,ref,"Handling");
    }
    static final class Pulse extends TickingSystem<EntityStore>{
        @Override public void tick(float dt,int index,Store<EntityStore>s){long now=System.currentTimeMillis();var state=state(s);
            if(state.recoveryPending){for(var m:state.machines.values())if(Engine.live(m)&&m.round.phase.equals("Lobby")){Engine.cancel(m);m.version++;}state.recoveryPending=false;}
            for(var m:state.machines.values())Engine.tick(m,now);
            displays.request(s,now);
            for(var p:pages)if(p.store==s)p.pulse(now);
        }
    }
    static final class Placement extends EntityEventSystem<EntityStore,PlaceBlockEvent>{
        Placement(){super(PlaceBlockEvent.class);}@Override public Query<EntityStore>getQuery(){return PlayerRef.getComponentType();}
        @Override public void handle(int i,ArchetypeChunk<EntityStore>chunk,Store<EntityStore>s,CommandBuffer<EntityStore>b,PlaceBlockEvent e){if(e.isCancelled()||e.getItemInHand()==null)return;String kind=kind(e.getItemInHand().getItemId());if(kind==null)return;var at=e.getTargetBlock();String k=key(at.x,at.y,at.z);var old=state(s).machines.get(k);if(old!=null&&(!old.bank.isEmpty()||Engine.obligations(old))){e.setCancelled(true);return;}var p=chunk.getComponent(i,PlayerRef.getComponentType());if(p==null){e.setCancelled(true);return;}String owner=p.getUuid().toString();int x=at.x,y=at.y,z=at.z;
            s.getExternalData().getWorld().execute(()->{if(kind.equals(kind(block(s.getExternalData().getWorld(),x,y,z)))){var m=new KweebetState.Machine();m.owner=owner;m.kind=kind;state(s).machines.put(k,m);}});
        }
    }
    static boolean blocked(Store<EntityStore>s,PlayerRef p,int x,int y,int z){var m=state(s).machines.get(MachineBounds.key(s.getExternalData().getWorld(),x,y,z));return m!=null&&(p==null||!p.getUuid().toString().equals(m.owner)||!m.bank.isEmpty()||Engine.obligations(m));}
    static final class Breaking extends EntityEventSystem<EntityStore,BreakBlockEvent>{
        Breaking(){super(BreakBlockEvent.class);}@Override public Query<EntityStore>getQuery(){return PlayerRef.getComponentType();}
        @Override public void handle(int i,ArchetypeChunk<EntityStore>c,Store<EntityStore>s,CommandBuffer<EntityStore>b,BreakBlockEvent e){var p=c.getComponent(i,PlayerRef.getComponentType());var at=e.getTargetBlock();if(blocked(s,p,at.x,at.y,at.z)){e.setCancelled(true);if(p!=null)p.sendMessage(Message.raw("Kweebet: only owner may remove; finish/refund round, collect all claims and withdraw available stock first."));}}
    }
    static final class Damage extends EntityEventSystem<EntityStore,DamageBlockEvent>{
        Damage(){super(DamageBlockEvent.class);}@Override public Query<EntityStore>getQuery(){return PlayerRef.getComponentType();}
        @Override public void handle(int i,ArchetypeChunk<EntityStore>c,Store<EntityStore>s,CommandBuffer<EntityStore>b,DamageBlockEvent e){var at=e.getTargetBlock();if(blocked(s,c.getComponent(i,PlayerRef.getComponentType()),at.x,at.y,at.z))e.setCancelled(true);}
    }
}
