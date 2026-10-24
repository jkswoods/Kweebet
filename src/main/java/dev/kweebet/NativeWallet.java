package dev.kweebet;

import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;

/** Exact ordinary stackable items only; native methods retain unmatched stacks/metadata. */
final class NativeWallet implements Engine.Wallet {
    final ItemContainer inventory;
    NativeWallet(ItemContainer inventory){this.inventory=inventory;}
    static void supported(String id){
        var item=Item.getAssetMap().getAsset(id);
        Engine.require(item!=null&&item.getMaxStack()>1,"Choose a loaded plain stackable item, not equipment or a machine");
        var stack=new ItemStack(id,1);Engine.require(stack.getMaxDurability()==0,"Durable items are unsupported");
    }
    @Override public boolean take(String id,int count){supported(id);Engine.amount(count);return inventory.removeItemStack(new ItemStack(id,count),true,true).succeeded();}
    @Override public boolean give(String id,int count){supported(id);Engine.amount(count);var tx=inventory.addItemStack(new ItemStack(id,count),true,true,true);if(tx.succeeded()&&!ItemStack.isEmpty(tx.getRemainder()))throw new IllegalStateException("Native all-or-nothing insertion returned a remainder");return tx.succeeded();}
    static int count(ItemContainer inv,String id){int n=0;for(short i=0;i<inv.getCapacity();i++){var item=inv.getItemStack(i);if(item!=null&&item.getItemId().equals(id))n=Math.addExact(n,item.getQuantity());}return n;}
}
