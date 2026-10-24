package dev.kweebet;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.interaction.interaction.CooldownHandler;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.client.SimpleBlockInteraction;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.joml.Vector3i;
public final class OpenInteraction extends SimpleBlockInteraction {
    public static final BuilderCodec<OpenInteraction> CODEC=BuilderCodec.builder(OpenInteraction.class,OpenInteraction::new,SimpleBlockInteraction.CODEC).build();
    @Override protected void interactWithBlock(World w,CommandBuffer<EntityStore>b,InteractionType t,InteractionContext c,ItemStack held,Vector3i at,CooldownHandler cooldown){var ref=c.getEntity();var p=b.getComponent(ref,PlayerRef.getComponentType());if(p==null)return;w.execute(()->{if(ref.isValid())KweebetPlugin.instance.open(ref.getStore(),ref,p,MachineBounds.key(w,at.x,at.y,at.z));});}
    @Override protected void simulateInteractWithBlock(InteractionType t,InteractionContext c,ItemStack h,World w,Vector3i at){}
}
