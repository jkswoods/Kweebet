package dev.kweebet;

import com.hypixel.hytale.codec.*;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.*;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.RefSystem;
import com.hypixel.hytale.component.dependency.*;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.protocol.SoundCategory;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.RotationTuple;
import com.hypixel.hytale.server.core.asset.type.model.config.*;
import com.hypixel.hytale.server.core.asset.type.soundevent.config.SoundEvent;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.modules.entity.component.*;
import com.hypixel.hytale.server.core.modules.entity.tracker.NetworkId;
import com.hypixel.hytale.server.core.universe.world.*;
import com.hypixel.hytale.server.core.universe.world.chunk.section.BlockSection;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.joml.Vector3d;
import java.util.*;

/** Tracked, non-interactive transient props, rebuilt from saved rounds after load. */
final class WorldDisplays extends RefSystem<EntityStore> {
    static ComponentType<EntityStore,Tag> tagType;
    public static final class Tag implements Component<EntityStore> {
        String machine="",role="";
        public static final BuilderCodec<Tag> CODEC=BuilderCodec.builder(Tag.class,Tag::new)
            .append(new KeyedCodec<>("Machine",Codec.STRING),(t,v)->t.machine=v,t->t.machine).add()
            .append(new KeyedCodec<>("Role",Codec.STRING),(t,v)->t.role=v,t->t.role).add().build();
        public Tag(){}
        Tag(String machine,String role){this.machine=machine;this.role=role;}
        String key(){return machine+"|"+role;}
        @Override public Tag clone(){return new Tag(machine,role);}
    }
    static final class Live {
        final Ref<EntityStore> ref;String asset="";float scale;long born;long round=-1;
        Live(Ref<EntityStore> r){ref=r;}
    }
    static final class Runtime {
        final Map<String,Live> pieces=new HashMap<>();
        final Set<Ref<EntityStore>> duplicates=new HashSet<>();
        final Map<String,Observed> sounds=new HashMap<>();
        final Set<String> bounds=new HashSet<>();final Map<String,Long> nextBounds=new HashMap<>();
        boolean queued;long next;
    }
    record Observed(long id,String phase,int cards,int row,Set<String> winners){}
    Runtime runtime(Store<EntityStore>s){return KweebetPlugin.state(s).worldDisplays;}
    @Override public Set<Dependency<EntityStore>> getDependencies(){return Set.of(new SystemDependency<>(Order.BEFORE,EntityStore.UUIDSystem.class));}
    @Override public Query<EntityStore> getQuery(){return tagType;}
    @Override public void onEntityAdded(Ref<EntityStore>ref,AddReason why,Store<EntityStore>s,CommandBuffer<EntityStore>b){
        var tag=s.getComponent(ref,tagType);var rt=runtime(s);
        if(rt.pieces.putIfAbsent(tag.key(),new Live(ref))!=null)rt.duplicates.add(ref);
    }
    @Override public void onEntityRemove(Ref<EntityStore>ref,RemoveReason why,Store<EntityStore>s,CommandBuffer<EntityStore>b){
        var tag=s.getComponent(ref,tagType);var rt=runtime(s);var live=rt.pieces.get(tag.key());
        if(live!=null&&live.ref.equals(ref))rt.pieces.remove(tag.key());rt.duplicates.remove(ref);
    }
    void request(Store<EntityStore>s,long now){
        var rt=runtime(s);if(rt.queued||now<rt.next)return;rt.next=now+33;rt.queued=true;
        // Use the time at which Pulse advanced the authoritative game, never a future
        // display-only clock that could briefly run beyond an as-yet-unsettled crash.
        s.getExternalData().getWorld().execute(()->{rt.queued=false;reconcile(s,now,false);});
    }
    // The explicit fixture flag is only used by isolated tests, never by native player actions.
    void reconcile(Store<EntityStore>s,long now,boolean fixture){
        var rt=runtime(s);var w=s.getExternalData().getWorld();
        for(var ref:List.copyOf(rt.duplicates))if(ref.isValid())s.removeEntity(ref,RemoveReason.REMOVE);
        rt.duplicates.clear();var desired=new HashSet<String>();
        for(var entry:KweebetPlugin.state(s).machines.entrySet()){
            String at=entry.getKey();var m=entry.getValue();var xyz=coordinates(at);if(xyz==null)continue;
            int x=xyz[0],y=xyz[1],z=xyz[2];
            var section=w.getChunkStore().getChunkSectionReferenceAtBlock(x,y,z);
            if(section==null||!section.isValid()){rt.sounds.remove(at);continue;}
            if(w.getChunkStore().getStore().getArchetype(section).contains(com.hypixel.hytale.server.core.universe.world.storage.ChunkStore.REGISTRY.getNonTickingComponentType())){rt.sounds.remove(at);continue;}
            if(!m.kind.equals(KweebetPlugin.kind(KweebetPlugin.block(w,x,y,z)))){rt.bounds.remove(at);continue;}
            boolean nearby=fixture||w.getPlayerRefs().stream().anyMatch(p->p.getTransform().getPosition().distanceSquared(x+.5,y+.5,z+.5)<48*48);
            if(!nearby){rt.sounds.remove(at);continue;}
            if(!rt.bounds.contains(at)&&now>=rt.nextBounds.getOrDefault(at,0L)){
                rt.nextBounds.put(at,now+2000);if(MachineBounds.repair(w,x,y,z))rt.bounds.add(at);
            }
            var blocks=w.getChunkStore().getStore().getComponent(section,BlockSection.getComponentType());
            var rotation=RotationTuple.get(blocks.getRotationIndex(x&31,y&31,z&31));
            var turn=new Rotation3f();rotation.applyRotationTo(turn);
            long round=m.round==null?0:m.round.id;boolean known=rt.sounds.containsKey(at);
            for(var piece:WorldScene.scene(m,now)){
                var pieceTurn=rotation(turn,piece);
                String key=at+"|"+piece.role();var live=rt.pieces.get(key);
                var offset=new Vector3d(piece.x()/32,piece.y()/32,piece.z()/32);rotation.applyRotationTo(offset);
                var pos=offset.add(x+.5,y,z+.5);
                // Large furniture can cross a section edge; never enqueue dormant holders there.
                if(!activeAt(s,pos))continue;desired.add(key);
                if(live==null||!live.ref.isValid()){
                    live=spawn(s,at,piece,pos,pieceTurn);if(live==null)continue;
                    live.born=known&&m.kind.equals("Blackjack")?now+(m.round!=null&&now-m.round.started<1000?cardIndex(piece.role())*100:0):0;live.round=round;
                }
                if(live.round!=round){live.round=round;live.born=m.kind.equals("Blackjack")&&m.round!=null&&now-m.round.started<1000?m.round.started+cardIndex(piece.role())*100:0;}
                if(m.kind.equals("Blackjack")&&live.born>0){
                    double progress=Math.clamp((now-live.born)/300.0,0,1),ease=1-Math.pow(1-progress,3);
                    var from=new Vector3d(0,40*WorldScene.HEIGHT/32.0,28/32.0);rotation.applyRotationTo(from);from.add(x+.5,y,z+.5);
                    pos=from.lerp(pos,ease);
                }
                update(s,live,piece,pos,pieceTurn);
            }
            sounds(s,rt,at,m,now,x,y,z);
        }
        // Remove loaded props outside the nearby, loaded machine set. NonSerialized props
        // are rebuilt from the saved round when a machine becomes nearby/loaded again.
        for(var entry:List.copyOf(rt.pieces.entrySet()))if(!desired.contains(entry.getKey())){
            var ref=entry.getValue().ref;if(ref.isValid())s.removeEntity(ref,RemoveReason.REMOVE);
        }
    }
    static int[] coordinates(String at){try{var p=at.split(":");if(p.length!=3)return null;return new int[]{Integer.parseInt(p[0]),Integer.parseInt(p[1]),Integer.parseInt(p[2])};}catch(NumberFormatException e){return null;}}
    static boolean activeAt(Store<EntityStore>s,Vector3d pos){
        var chunks=s.getExternalData().getWorld().getChunkStore();var section=chunks.getChunkSectionReferenceAtBlock((int)Math.floor(pos.x),(int)Math.floor(pos.y),(int)Math.floor(pos.z));
        return section!=null&&section.isValid()&&!chunks.getStore().getArchetype(section).contains(com.hypixel.hytale.server.core.universe.world.storage.ChunkStore.REGISTRY.getNonTickingComponentType());
    }
    static int cardIndex(String role){try{return Integer.parseInt(role.substring(role.lastIndexOf('-')+1));}catch(NumberFormatException e){return 0;}}
    static Rotation3f rotation(Rotation3f base,WorldScene.Piece piece){
        var result=new Rotation3f(base);
        if(piece.roll()!=0)result.mul(new org.joml.Quaterniond().rotationZ(piece.roll()));
        if(piece.yaw()!=0)result.mul(new org.joml.Quaterniond().rotationY(piece.yaw()));
        return result;
    }
    Live spawn(Store<EntityStore>s,String at,WorldScene.Piece piece,Vector3d pos,Rotation3f rotation){
        var model=model(piece);if(model==null)return null;
        var holder=EntityStore.REGISTRY.newHolder();
        holder.addComponent(tagType,new Tag(at,piece.role()));holder.addComponent(UUIDComponent.getComponentType(),UUIDComponent.randomUUID());
        // Verified0.7.4 registry marker: native EntitySavingSystem excludes these props.
        holder.addComponent(EntityStore.REGISTRY.getNonSerializedComponentType(),NonSerialized.get());
        holder.addComponent(TransformComponent.getComponentType(),new TransformComponent(pos,rotation));
        holder.addComponent(ModelComponent.getComponentType(),new ModelComponent(model));
        holder.addComponent(PersistentModel.getComponentType(),new PersistentModel(model.toReference()));
        holder.addComponent(BoundingBox.getComponentType(),new BoundingBox(model.getBoundingBox()));
        holder.addComponent(NetworkId.getComponentType(),new NetworkId(s.getExternalData().takeNextNetworkId()));
        holder.addComponent(Intangible.getComponentType(),Intangible.INSTANCE);holder.addComponent(Invulnerable.getComponentType(),Invulnerable.INSTANCE);
        var ref=s.addEntity(holder,AddReason.SPAWN);var live=runtime(s).pieces.get(at+"|"+piece.role());
        if(ref==null||!ref.isValid())return null;
        if(live==null)throw new IllegalStateException("World display was not tracked");
        live.asset=piece.asset();live.scale=piece.scale();return live;
    }
    static Model model(WorldScene.Piece p){var asset=ModelAsset.getAssetMap().getAsset(p.asset());return asset==null?null:Model.createScaledModel(asset,p.scale());}
    static void update(Store<EntityStore>s,Live live,WorldScene.Piece piece,Vector3d pos,Rotation3f rotation){
        var transform=s.getComponent(live.ref,TransformComponent.getComponentType());
        if(transform!=null){if(transform.getPosition().distanceSquared(pos)>1e-10)transform.setPosition(pos);if(!transform.getRotation().equals(rotation))transform.setRotation(rotation);}
        if(!piece.asset().equals(live.asset)||piece.scale()!=live.scale){
            var model=model(piece);if(model==null)return;
            s.putComponent(live.ref,ModelComponent.getComponentType(),new ModelComponent(model));
            s.putComponent(live.ref,PersistentModel.getComponentType(),new PersistentModel(model.toReference()));
            live.asset=piece.asset();live.scale=piece.scale();
        }
    }
    static void spatial(Store<EntityStore>s,String role,int x,int y,int z){
        var map=SoundEvent.getAssetMap();String id="CW_World"+role;
        if(map.getAsset(id)!=null)SoundUtil.playSoundEvent3d(map.getIndex(id),SoundCategory.SFX,x+.5,y+1.2,z+.5,s);
    }
    static void sounds(Store<EntityStore>s,Runtime rt,String at,KweebetState.Machine m,long now,int x,int y,int z){
        var r=m.round;if(r==null){rt.sounds.putIfAbsent(at,new Observed(0,"Idle",0,0,Set.of()));return;}int cards=r.dealer.size()+r.seats.values().stream().mapToInt(v->v.hand.size()).sum();
        int row=r.phase.equals("Playing")?m.kind.equals("Duck")?(int)((now-r.started)/1800):now-r.started<200?-1:(int)WorldScene.plinkoProgress(now-r.started):8;
        Set<String> winners=new HashSet<>();for(String actor:r.seats.keySet())if(WorldScene.won(m,actor))winners.add(actor);
        var before=rt.sounds.put(at,new Observed(r.id,r.phase,cards,row,Set.copyOf(winners)));
        boolean victory=winTransition(before,r.id,winners);
        if(victory){
            spatial(s,"Win",x,y,z);
            ParticleUtil.spawnParticleEffect("CW_WinFireworks",new Vector3d(x+.5,y+(m.kind.equals("Plinko")?1.6:.8),z+.5),s);
        }
        boolean fresh=r.started>0&&(before!=null?before.id()!=r.id:now-r.started<250);
        if(fresh)spatial(s,"Start",x,y,z);
        if(fresh&&m.kind.equals("Duck")&&r.phase.equals("Playing"))spatial(s,"Duck",x,y,z);
        if(!victory&&(fresh||before!=null&&before.id()==r.id&&!before.phase().equals("Finished"))&&r.phase.equals("Finished"))spatial(s,"Finish",x,y,z);
        if(before!=null&&before.id()==r.id){
            if(m.kind.equals("Blackjack")&&cards>before.cards())spatial(s,"Action",x,y,z);
            if(m.kind.equals("Plinko")&&r.phase.equals("Playing")&&row>before.row()&&row>=0&&row<8)spatial(s,"Peg"+(row%4),x,y,z);
            if(m.kind.equals("Duck")&&r.phase.equals("Playing")&&row>before.row())spatial(s,"Duck",x,y,z);
        }
    }
    static boolean winTransition(Observed before,long round,Set<String> winners){
        return before!=null&&!winners.isEmpty()&&(before.id()!=round||!before.winners().containsAll(winners));
    }
}
