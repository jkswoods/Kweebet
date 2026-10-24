package dev.kweebet;

import com.hypixel.hytale.protocol.BlockPosition;
import com.hypixel.hytale.server.core.asset.type.blockhitbox.BlockBoundingBoxes;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.BlockOperations;
import com.hypixel.hytale.server.core.universe.world.chunk.section.BlockSection;
import com.hypixel.hytale.server.core.util.FillerBlockUtil;
import java.util.*;

/** Loaded-only collision migration. Never replaces another block or touches funds. */
final class MachineBounds {
    record Cell(int x,int y,int z){}
    static BlockSection section(World w,Cell p){var c=w.getChunkStore();var r=c.getChunkSectionReferenceAtBlock(p.x,p.y,p.z);return r==null||!r.isValid()?null:c.getStore().getComponent(r,BlockSection.getComponentType());}
    static Cell origin(World w,Cell p){
        if(section(w,p)==null)return p;
        var b=BlockOperations.resolveBaseBlockPosition(w.getChunkStore(),new BlockPosition(p.x,p.y,p.z));
        return b==null?p:new Cell(b.x,b.y,b.z);
    }
    static String key(World w,int x,int y,int z){var p=origin(w,new Cell(x,y,z));return KweebetPlugin.key(p.x,p.y,p.z);}
    static Set<Cell> footprint(BlockType type,Cell root,int rotation){
        var cells=new HashSet<Cell>();cells.add(root);
        var boxes=BlockBoundingBoxes.getAssetMap().getAsset(type.getHitboxTypeIndex());
        if(boxes==null)throw new IllegalStateException("Missing Kweebet collision");
        FillerBlockUtil.forEachFillerBlock(boxes.get(rotation),(x,y,z)->cells.add(new Cell(root.x+x,root.y+y,root.z+z)));return cells;
    }
    static boolean repair(World w,int x,int y,int z){
        var root=new Cell(x,y,z);var base=section(w,root);if(base==null||base.getFiller(x&31,y&31,z&31)!=0)return false;
        int index=base.get(x&31,y&31,z&31),rot=base.getRotationIndex(x&31,y&31,z&31);
        var type=BlockType.getAssetMap().getAsset(index);if(type==null||!type.getId().startsWith("CW_"))return false;
        var expected=footprint(type,root,rot);
        // Complete preflight before mutation: only empty or this root's own fillers.
        for(var p:expected){var b=section(w,p);if(b==null)return false;
            if(p.equals(root))continue;
            if(b.get(p.x&31,p.y&31,p.z&31)==BlockType.EMPTY_ID&&b.getFiller(p.x&31,p.y&31,p.z&31)==0)continue;
            if(b.get(p.x&31,p.y&31,p.z&31)!=index||b.getFiller(p.x&31,p.y&31,p.z&31)==0||!origin(w,p).equals(root))return false;
        }
        var obsolete=new ArrayList<Cell>();
        // Covers every historical Kweebet cabinet/rotation, including the
        // former tall Duck scoreboard. Nothing is force-loaded or guessed away.
        for(int dx=-4;dx<=4;dx++)for(int dy=0;dy<=5;dy++)for(int dz=-4;dz<=4;dz++){
            var p=new Cell(x+dx,y+dy,z+dz);var b=section(w,p);if(b==null)return false;
            if(!expected.contains(p)&&b.get(p.x&31,p.y&31,p.z&31)==index&&b.getFiller(p.x&31,p.y&31,p.z&31)!=0&&origin(w,p).equals(root))obsolete.add(p);
        }
        var chunks=w.getChunkStore();var empty=BlockType.getAssetMap().getAsset("Empty");
        for(var p:obsolete)BlockOperations.setBlock(chunks,chunks.getChunkSectionReferenceAtBlock(p.x,p.y,p.z),p.x,p.y,p.z,BlockType.EMPTY_ID,empty,0,0,198|16|8);
        // Force native invalidation/update even for the unchanged primary ID.
        // Skip recursive removal; the preflight guarantees safe filler creation.
        BlockOperations.setBlock(chunks,chunks.getChunkSectionReferenceAtBlock(x,y,z),x,y,z,index,type,rot,0,198|16);
        return expected.stream().allMatch(p->origin(w,p).equals(root));
    }
}
