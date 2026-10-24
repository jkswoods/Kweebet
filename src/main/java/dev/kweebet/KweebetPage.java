package dev.kweebet;

import com.hypixel.hytale.codec.*;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.*;
import com.hypixel.hytale.protocol.packets.interface_.*;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.pages.InteractiveCustomUIPage;
import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.ui.*;
import com.hypixel.hytale.server.core.ui.builder.*;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.*;
import static dev.kweebet.KweebetState.*;

/** Compact controls over the actual world game; secondary owner/rules pages. */
final class KweebetPage extends InteractiveCustomUIPage<KweebetPage.Action> {
    public static final class Action {
        String action="",version="",stake="4",auto="0",item="Ingredient_Bar_Iron",fundItem="Ingredient_Bar_Iron",amount="4",min="4",max="64",target="",bonusItem="Ingredient_Bar_Copper",bonusAmount="0";
        static final BuilderCodec<Action> CODEC=BuilderCodec.builder(Action.class,Action::new)
            .append(new KeyedCodec<>("Action",Codec.STRING),(a,v)->a.action=v,a->a.action).add()
            .append(new KeyedCodec<>("Version",Codec.STRING),(a,v)->a.version=v,a->a.version).add()
            .append(new KeyedCodec<>("@Stake",Codec.STRING),(a,v)->a.stake=v,a->a.stake).add()
            .append(new KeyedCodec<>("@Auto",Codec.STRING),(a,v)->a.auto=v,a->a.auto).add()
            .append(new KeyedCodec<>("@Item",Codec.STRING),(a,v)->a.item=v,a->a.item).add()
            .append(new KeyedCodec<>("@FundItem",Codec.STRING),(a,v)->a.fundItem=v,a->a.fundItem).add()
            .append(new KeyedCodec<>("@Amount",Codec.STRING),(a,v)->a.amount=v,a->a.amount).add()
            .append(new KeyedCodec<>("@Min",Codec.STRING),(a,v)->a.min=v,a->a.min).add()
            .append(new KeyedCodec<>("@Max",Codec.STRING),(a,v)->a.max=v,a->a.max).add()
            .append(new KeyedCodec<>("@BonusItem",Codec.STRING),(a,v)->a.bonusItem=v,a->a.bonusItem).add()
            .append(new KeyedCodec<>("@BonusAmount",Codec.STRING),(a,v)->a.bonusAmount=v,a->a.bonusAmount).add()
            .append(new KeyedCodec<>("@Target",Codec.STRING),(a,v)->a.target=v,a->a.target).add().build();
    }
    enum View { PLAY,MANAGE,SETTINGS,ITEMS,RULES }
    static final int FRAME_MS=33;
    static final String[] COLORS={"Yellow","Coral","Sky blue","Jade","Violet","Rose","Mint","Pearl"};
    final String at;
    Store<EntityStore> store; Ref<EntityStore> ref; boolean dismissed,validStake=true,validAuto=true;
    View view=View.PLAY;boolean advanced,validAmount=true,noticeError;
    String stockItem="",draftItem="",draftBonus="",pickFor="stock";int stockAmount=1;
    int selectedStake=4,selectedAuto,plinkoRow=-1;
    long shownVersion=-1,layoutRound=-1,heardRound=-1,nextFrame,nextStatus,nextPresence;
    String heardPhase="",notice="";
    final Map<String,Long> dealtAt=new HashMap<>();
    final List<CardMotion> cards=new ArrayList<>();
    final Map<String,String> lastMotion=new HashMap<>();
    long fullUpdates,animationUpdates,openedAt,firstAnimation,lastAnimation,heardStarted,lastAutoVersion=-1;
    record CardMotion(String selector,int x,int y,int width,int height,long began,boolean hidden){}
    KweebetPage(PlayerRef player,String at){super(player,CustomPageLifetime.CanDismiss,Action.CODEC);this.at=at;}
    boolean current(){if(dismissed||ref==null||!ref.isValid())return false;var p=store.getComponent(ref,Player.getComponentType());return p!=null&&p.getPageManager().getCustomPage()==this;}
    String actor(){return playerRef.getUuid().toString();}
    Machine machine(){return KweebetPlugin.state(store).machines.get(at);}
    NativeWallet wallet(){return new NativeWallet(InventoryComponent.getCombined(store,ref,InventoryComponent.HOTBAR_STORAGE_BACKPACK));}
    Terms terms(Machine m){return Engine.live(m)?m.round.terms:m.config;}
    static String title(String kind){return switch(kind){case "Duck"->"DUCK RACE";case "Blackjack"->"BLACKJACK";case "Plinko"->"PLINKO";default->"CRASH";};}
    static String itemName(String id){
        if(id.startsWith("Ingredient_Bar_"))return words(id.substring(15))+" ingots";
        if(id.startsWith("Ore_"))return words(id.substring(4))+" ore";
        return words(id.replaceFirst("^Ingredient_",""));
    }
    static String words(String id){return id.replace('_',' ').replaceAll("([a-z])([A-Z])","$1 $2");}
    static String units(int n,String item){return n+" "+itemName(item);}
    static int reserve(String kind,int stake){return kind.equals("Blackjack")?stake*3/2:(kind.equals("Crash")||kind.equals("Plinko"))?stake*9:0;}
    static int step(String kind){return kind.equals("Plinko")?4:kind.equals("Blackjack")?2:1;}
    static String rank(int card){return new String[]{"A","2","3","4","5","6","7","8","9","10","J","Q","K"}[card%13];}
    static String suit(int card){return new String[]{"S","H","D","C"}[card/13];}
    static Anchor anchor(int x,int y,int w,int h){var a=new Anchor();a.setLeft(Value.of(x));a.setTop(Value.of(y));a.setWidth(Value.of(w));a.setHeight(Value.of(h));return a;}
    @Override public void build(Ref<EntityStore> reference,UICommandBuilder commands,UIEventBuilder events,Store<EntityStore> s){
        store=s;ref=reference;openedAt=System.currentTimeMillis();var m=machine();selectedStake=terms(m).min;
        if(m.round!=null){heardRound=m.round.id;heardPhase=m.round.phase;}else heardRound=0;
        if(m.round!=null&&m.round.started>0)heardStarted=m.round.id;
        commands.append("Pages/Kweebet/Main.ui");KweebetPlugin.pages.add(this);autoCollect(m,System.currentTimeMillis(),true);
        renderState(commands,events,true,System.currentTimeMillis());
    }
    EventData data(String action,Machine m){return EventData.of("Action",action).append("Version",Long.toString(m.version))
        .append("@Stake","#Stake.Value").append("@Auto","#Auto.Value").append("@Item","#Item.Value")
        .append("@FundItem","#FundItem.Value").append("@Amount","#Amount.Value").append("@Min","#Min.Value")
        .append("@Max","#Max.Value").append("@Target","#Target.Value").append("@BonusItem","#BonusItem.Value").append("@BonusAmount","#BonusAmount.Value");}
    void bind(UIEventBuilder e,String selector,String action,Machine m){e.addEventBinding(CustomUIEventBindingType.Activating,selector,data(action,m),false);}
    void bindings(UIEventBuilder e,Machine m){
        e.addEventBinding(CustomUIEventBindingType.Activating,"#Close",EventData.of("Action","close"),false);
        for(var pair:Map.ofEntries(Map.entry("#Primary","primary"),Map.entry("#Minus","minus"),Map.entry("#Plus","plus"),Map.entry("#Hit","hit"),Map.entry("#Stand","stand"),Map.entry("#Double","double"),Map.entry("#ManageButton","manage"),Map.entry("#RulesButton","rules"),Map.entry("#BackToPlay","back"),Map.entry("#CloseRules","back"),Map.entry("#Deposit","deposit"),Map.entry("#Withdraw","withdraw"),Map.entry("#Configure","configure"),Map.entry("#Bonus","bonus"),Map.entry("#Access","access"),Map.entry("#Invite","invite"),Map.entry("#Cancel","cancel"),Map.entry("#Accepting","accepting"),Map.entry("#Settings","settings"),Map.entry("#BackToStock","back"),Map.entry("#RecoverStored","stock"),Map.entry("#ChooseStock","choose-stock"),Map.entry("#ChooseStake","choose-stake"),Map.entry("#ChooseBonus","choose-bonus"),Map.entry("#CloseItems","close-items"),Map.entry("#StockMinus","stock-minus"),Map.entry("#StockPlus","stock-plus"),Map.entry("#Advanced","advanced")).entrySet())bind(e,pair.getKey(),pair.getValue(),m);
        e.addEventBinding(CustomUIEventBindingType.ValueChanged,"#Stake",data("stake",m),false);
        e.addEventBinding(CustomUIEventBindingType.ValueChanged,"#Auto",data("auto",m),false);
        e.addEventBinding(CustomUIEventBindingType.ValueChanged,"#Amount",data("amount",m),false);
    }
    Map.Entry<String,Integer> claim(Machine m){return m.claims.getOrDefault(actor(),Map.of()).entrySet().stream().findFirst().orElse(null);}
    boolean cashable(Machine m){var r=m.round;var seat=r==null?null:r.seats.get(actor());return m.kind.equals("Crash")&&r!=null&&r.phase.equals("Playing")&&seat!=null&&!seat.done;}
    boolean myTurn(Machine m){var r=m.round;return m.kind.equals("Blackjack")&&r!=null&&r.phase.equals("Playing")&&actor().equals(r.turn);}
    void renderState(UICommandBuilder c,UIEventBuilder e,boolean forms,long now){
        var m=machine();if(m==null)return;var t=terms(m);var r=m.round;
        if(r==null||layoutRound!=r.id){dealtAt.clear();lastMotion.clear();}
        c.set("#Title.Text",title(m.kind)+(view==View.SETTINGS?" SETTINGS":""));c.set("#ManageButton.Visible",m.owner.equals(actor()));
        c.set("#PlayView.Visible",view==View.PLAY);c.set("#ManageView.Visible",view==View.MANAGE);c.set("#RulesView.Visible",view==View.RULES);c.set("#SettingsView.Visible",view==View.SETTINGS);c.set("#ItemsView.Visible",view==View.ITEMS);
        for(String kind:List.of("Duck","Blackjack","Crash","Plinko"))c.set("#"+kind+"Game.Visible",m.kind.equals(kind));
        if(selectedStake<t.min||selectedStake>t.max){selectedStake=t.min;validStake=true;c.set("#Stake.Value",Integer.toString(selectedStake));}
        if(forms){
            c.set("#Stake.Value",Integer.toString(selectedStake));c.set("#Auto.Value",Integer.toString(selectedAuto));
            draftItem=m.config.item;draftBonus=m.config.bonusItem;if(stockItem.isEmpty())stockItem=m.config.item;
            c.set("#Item.Value",draftItem);c.set("#FundItem.Value",stockItem);c.set("#Min.Value",Integer.toString(m.config.min));c.set("#Max.Value",Integer.toString(m.config.max));
            c.set("#BonusItem.Value",draftBonus);c.set("#BonusAmount.Value",Integer.toString(m.config.bonus));
            c.set("#Amount.Value",Integer.toString(stockAmount));c.set("#Target.Value","");
        }
        bindings(e,m);renderOwner(c,m);c.set("#RulesText.Text",rules(m));
        if(view==View.ITEMS)renderChoices(c,e,m);
        cards.clear();
        /* Retained board widgets support old source previews; default play presents the world.
        if(m.kind.equals("Duck"))renderDucks(c,m,now);
        if(m.kind.equals("Plinko"))renderPlinko(c,m,now);
        if(m.kind.equals("Blackjack"))renderBlackjack(c,m,now);
        if(m.kind.equals("Crash")){
            c.clear("#Wagers");if(r!=null){int i=0;for(var entry:r.seats.entrySet()){
                var seat=entry.getValue();String selector="#Wagers["+i+++ "]";c.append("#Wagers","Pages/Kweebet/Wager.ui");
                c.set(selector+" #Name.Text",seat.name+(entry.getKey().equals(actor())?" (you)":""));
                c.set(selector+" #Detail.Text",seat.result.isEmpty()?units(seat.stake,r.terms.item)+(seat.auto==0?" · Manual":" · Auto "+String.format(Locale.ROOT,"%.2fx",seat.auto/100.0)):seat.result);
            }}
        }
        */
        var panel=new Anchor();panel.setWidth(Value.of(view==View.PLAY?1120:view==View.SETTINGS?1040:1360));panel.setHeight(Value.of(view==View.PLAY?noticeError?212:184:view==View.SETTINGS?440:900));panel.setBottom(Value.of(view==View.PLAY?60:30));
        c.setObject("#Root.Anchor",panel);c.set("#GameStage.Visible",false);
        var metrics=new Anchor();metrics.setWidth(Value.of(1120));metrics.setHeight(Value.of(64));metrics.setBottom(Value.of(noticeError?282:254));c.setObject("#Metrics.Anchor",metrics);c.set("#Metrics.Visible",view==View.PLAY);
        layoutRound=r==null?0:r.id;shownVersion=m.version;fullUpdates++;
        lastMotion.clear();renderControls(c,m,now);animate(c,m,now);
    }
    void renderOwner(UICommandBuilder c,Machine m){
        if(!m.owner.equals(actor()))return;
        var r=m.round;int available=Engine.get(m.bank,stockItem),inBag=NativeWallet.count(wallet().inventory,stockItem);
        c.set("#StockIcon.ItemId",stockItem);c.set("#ChooseStock.Text",itemName(stockItem)+" · change item");
        c.set("#StockBalance.Text",available+" stored items");c.set("#StockWallet.Text",units(inBag,stockItem)+" in your bag");
        String hint="Items saved by an earlier build. New games generate their returns and need no deposits.";
        c.set("#FundingHint.Text",hint);c.set("#OpenState.Text","");
        c.set("#Accepting.Visible",false);
        c.set("#Deposit.Visible",false);c.set("#RecoverStored.Visible",false);c.set("#Withdraw.Disabled",!validAmount||stockAmount>available);
        c.set("#StockMinus.Disabled",validAmount&&stockAmount<=1);c.set("#StockPlus.Disabled",validAmount&&stockAmount>=10000);
        c.set("#StakeItemIcon.ItemId",draftItem);c.set("#ChooseStake.Text",itemName(draftItem)+" · change item");
        c.set("#BonusIcon.ItemId",draftBonus);c.set("#ChooseBonus.Text",itemName(draftBonus)+" · change item");
        c.set("#LimitsHint.Text",m.kind.equals("Duck")?"Four ducks race immediately. Your duck pays 4x total when it wins.":m.kind.equals("Plinko")?"Use multiples of four so every bucket pays exact items.":m.kind.equals("Blackjack")?"Use even amounts so Blackjack pays exact items.":"Players choose a bet between these amounts.");
        c.set("#Access.Text","Allow everyone");c.set("#Access.Visible",false);c.set("#RecoveryRow.Visible",false);
        c.set("#InviteSettings.Visible",false);c.set("#AdvancedSettings.Visible",false);c.set("#BonusSettings.Visible",false);c.set("#BonusHint.Text","Saved bonus: "+units(m.config.bonus,m.config.bonusItem));
        c.set("#OwnerNotice.Text",notice);c.set("#SettingsNotice.Text",notice);
        c.set("#History.Text",String.join("\n",m.history.stream().limit(5).toList()));
        c.set("#Configure.Disabled",Engine.obligations(m));c.set("#Bonus.Visible",m.kind.equals("Duck"));c.set("#Bonus.Disabled",Engine.obligations(m));
        c.set("#Access.Disabled",Engine.obligations(m));c.set("#Invite.Disabled",Engine.obligations(m));c.set("#Cancel.Disabled",r==null||!r.phase.equals("Lobby"));
    }
    int suggestedStock(Machine m,String item){return Math.clamp(Engine.get(m.bank,item),1,10000);}
    List<String> itemChoices(Machine m){
        var ids=new LinkedHashSet<String>();ids.add(m.config.item);ids.add("Ingredient_Bar_Iron");ids.add("Ingredient_Bar_Copper");ids.addAll(m.bank.keySet());if(m.config.bonus>0)ids.add(m.config.bonusItem);
        var bag=wallet().inventory;for(short i=0;i<bag.getCapacity();i++){var stack=bag.getItemStack(i);if(!ItemStack.isEmpty(stack)&&stack.isStackableWith(new ItemStack(stack.getItemId(),1)))ids.add(stack.getItemId());}
        return ids.stream().filter(id->{try{NativeWallet.supported(id);return true;}catch(IllegalArgumentException|IllegalStateException ex){return false;}}).toList();
    }
    void renderChoices(UICommandBuilder c,UIEventBuilder e,Machine m){
        Engine.owner(m,actor());c.set("#ItemsTitle.Text",pickFor.equals("stock")?"CHOOSE STOCK ITEM":pickFor.equals("stake")?"CHOOSE BET ITEM":"CHOOSE BONUS ITEM");c.clear("#ItemChoices");
        int i=0;for(String id:itemChoices(m)){
            String selector="#ItemChoices["+i+++ "]";c.append("#ItemChoices","Pages/Kweebet/ItemChoice.ui");c.set(selector+" #Icon.ItemId",id);c.set(selector+" #Pick.Text",itemName(id));c.set(selector+" #Count.Text",NativeWallet.count(wallet().inventory,id)+" in bag");bind(e,selector+" #Pick","pick:"+id,m);
        }
    }
    void renderControls(UICommandBuilder c,Machine m,long now){
        var t=terms(m);var r=m.round;var seat=r==null?null:r.seats.get(actor());boolean live=Engine.live(m),lobby=live&&r.phase.equals("Lobby"),cash=cashable(m),turn=myTurn(m);
        var owed=claim(m);boolean collecting=owed!=null&&!cash&&!turn;
        c.set("#GameReadout.Text",readout(m,actor(),now));c.set("#BetItem.Text",itemName(t.item));
        c.set("#CardsActions.Visible",turn);c.set("#StakeActionBar.Visible",!turn);c.set("#StakeControls.Visible",!collecting&&!cash);
        var bar=new Anchor();bar.setWidth(Value.of(collecting||cash?460:960));bar.setHeight(Value.of(64));c.setObject("#StakeActionBar.Anchor",bar);
        c.set("#AutoControls.Visible",false);
        c.set("#Minus.Disabled",selectedStake<=t.min||live&&seat!=null);
        c.set("#Plus.Disabled",selectedStake+step(m.kind)>t.max||live&&seat!=null);
        c.set("#PaymentIcon.ItemId",collecting?owed.getKey():t.item);
        var bag=wallet();int inBag=NativeWallet.count(bag.inventory,collecting?owed.getKey():t.item);
        c.set("#Wallet.Text","");
        c.set("#AvailableBet.Text",units(NativeWallet.count(bag.inventory,t.item),t.item));
        c.set("#PotentialPanel.Visible",WorldScene.active(m,actor()));c.set("#PotentialWin.Text",potential(m,actor(),now));
        boolean disabled=false;String primary;
        if(cash){int total=(int)((long)seat.stake*Engine.multiplier(r,now)/100);primary="TAKE "+total;}
        else if(collecting)primary="COLLECT "+owed.getValue();
        else if(live&&seat!=null){primary=lobby?"YOU'RE IN":"PLAYING";disabled=false;}
        else if(live&&!lobby){primary="NEXT ROUND";disabled=true;}
        else if(!validStake||!validAuto){primary="CHOOSE STAKE";disabled=true;}
        else if(inBag<selectedStake){primary="NEED "+selectedStake;disabled=true;}
        else if(lobby&&!r.generatedReturns&&(Engine.get(m.bank,t.item)<reserve(m.kind,selectedStake))){primary="SAVED ROUND";disabled=true;}
        else primary=(m.kind.equals("Plinko")?"DROP ":m.kind.equals("Blackjack")?"DEAL ":m.kind.equals("Duck")?"RACE ":"BET ")+selectedStake;
        c.set("#Primary.Text",primary);c.set("#Primary.Disabled",disabled);
        c.set("#Hit.Disabled",!turn);c.set("#Stand.Disabled",!turn);c.set("#Double.Disabled",!turn||seat==null||seat.hand.size()!=2||seat.doubled||!r.generatedReturns&&Engine.get(m.bank,t.item)<seat.stake*2-seat.reserved||inBag<seat.stake);
        c.set("#Double.Text",seat==null?"Double":"Double +"+seat.stake);
        c.set("#Payout.Text","");c.set("#Round.Text","");c.set("#Notice.Text",noticeError?notice:"");c.set("#Notice.Visible",noticeError);
        var footer=new Anchor();footer.setHeight(Value.of(noticeError?92:64));c.setObject("#Footer.Anchor",footer);
        boolean result=view==View.PLAY&&WorldScene.settled(m,actor());c.set("#ResultPanel.Visible",result);
        if(result){boolean positive=WorldScene.net(m,actor())>=0;c.set("#WinResult.Visible",positive);c.set("#LossResult.Visible",!positive);String prefix=positive?"#Win":"#Loss";c.set(prefix+"Title.Text",WorldScene.resultTitle(m,actor()));c.set(prefix+"Delta.Text",WorldScene.resultDelta(m,actor()));}
        if(m.kind.equals("Plinko"))for(int i=0;i<9;i++)c.set("#Bucket"+i+" #Return.Text",validStake?Integer.toString(Plinko.payout(selectedStake,i))+" back":"");
    }
    static String readout(Machine m,String actor,long now){
        var r=m.round;var seat=r==null?null:r.seats.get(actor);
        return switch(m.kind){
            case "Duck"->{
                int lane=r==null?0:new ArrayList<>(r.seats.keySet()).indexOf(actor);
                yield lane<0?"Watch the ducks race":"YOUR DUCK: "+COLORS[lane].toUpperCase(Locale.ROOT)+" · LANE "+(lane+1);
            }
            case "Blackjack"->{
                String dealer=r==null||r.dealer.isEmpty()?"—":r.phase.equals("Finished")?Integer.toString(Engine.points(r.dealer)):Engine.points(r.dealer.subList(0,1))+" + ?";
                yield "YOU: "+(seat==null||seat.hand.isEmpty()?"—":Engine.points(seat.hand))+"     DEALER: "+dealer;
            }
            case "Crash"->String.format(Locale.ROOT,"%.2f×",WorldScene.crashValue(m,now)/100.0);
            default->seat!=null&&r.phase.equals("Finished")?seat.result:"Drop a ball · watch it land";
        };
    }
    String potential(Machine m,String actor,long now){return (m.kind.equals("Plinko")?"Up to ":"")+units(WorldScene.potential(m,actor,now),terms(m).item);}
    void status(UICommandBuilder c,Machine m,long now){
        var r=m.round;var s=r==null?null:r.seats.get(actor());String text;
        if(r==null)text="Choose your stake and play";
        else if(r.phase.equals("Lobby"))text="Finishing a saved waiting round";
        else if(r.phase.equals("Playing"))text=switch(m.kind){case "Duck"->"Follow the PLAYER marker above your duck";case "Plinko"->"The ball is dropping";case "Blackjack"->actor().equals(r.turn)?"Hit to draw · Stand to keep · Double adds one bet":r.turn.isEmpty()?"Dealer's turn":r.seats.get(r.turn).name+" is playing";default->"Press TAKE before it crashes";};
        else if(s==null)text="Ready for the next game";
        else if(m.kind.equals("Duck"))text=s.result.equals("Refund")?"Saved race refunded":actor().equals(r.winner)?"YOUR DUCK WINS":r.seats.containsKey(r.winner)?r.seats.get(r.winner).name+" wins":"Race finished";
        else text=s.returned>0?units(s.returned,r.terms.item)+" back":m.kind.equals("Crash")?"CRASHED · better luck next time":s.result;
        c.set("#Round.Text",text);
    }
    void renderDucks(UICommandBuilder c,Machine m,long now){
        var r=m.round;var entries=r==null?List.<Map.Entry<String,Seat>>of():new ArrayList<>(r.seats.entrySet());
        for(int i=0;i<8;i++){
            String sel="#Lane"+i;boolean occupied=i<entries.size(),playing=occupied&&r.phase.equals("Playing");
            boolean compact=entries.size()>4;int rowHeight=compact?68:128;
            var row=new Anchor();row.setHeight(Value.of(rowHeight));c.setObject(sel+".Anchor",row);c.set(sel+".Visible",i<Math.max(4,entries.size()));
            var track=new Anchor();track.setWidth(Value.of(900));track.setHeight(Value.of(rowHeight-8));c.setObject(sel+" #Track.Anchor",track);
            c.set(sel+" #Name.Text",occupied?entries.get(i).getValue().name:i==0?"Your duck":i<4?new String[]{"","Coral duck","Sky duck","Jade duck"}[i]:"Open lane");
            c.set(sel+" #DuckLive.Visible",playing);c.set(sel+" #DuckRest.Visible",occupied&&!playing||entries.isEmpty()&&i<4);
            String result="";if(occupied){var seat=entries.get(i).getValue();result=r.phase.equals("Finished")?(r.finish.isEmpty()?seat.result:"#"+(r.finish.indexOf(entries.get(i).getKey())+1)):COLORS[i];}
            c.set(sel+" #Result.Text",result);
        }
    }
    void renderPlinko(UICommandBuilder c,Machine m,long now){
        var r=m.round;for(int i=0;i<9;i++){c.set("#Bucket"+i+" #Multiplier.Text",Plinko.multiplier(i));c.set("#Bucket"+i+".Background",Value.ref("Pages/Kweebet/Styles.ui","Bucket"));}
        var seats=r==null?List.<Seat>of():new ArrayList<>(r.seats.values());
        for(int i=0;i<8;i++){
            boolean exists=i<seats.size()&&!seats.get(i).plinkoPath.isEmpty(),playing=exists&&r.phase.equals("Playing");
            c.set("#BallLive"+i+".Visible",playing);c.set("#BallRest"+i+".Visible",exists&&!playing||i==0&&seats.isEmpty());
            if(i==0&&seats.isEmpty())c.setObject("#BallRest0.Anchor",anchor(624,6,32,32));
            if(exists&&r.phase.equals("Finished")&&r.seats.containsKey(actor())&&seats.get(i)==r.seats.get(actor()))c.set("#Bucket"+Plinko.bucket(seats.get(i).plinkoPath)+".Background",Value.ref("Pages/Kweebet/Styles.ui","BucketWin"));
        }
    }
    void renderBlackjack(UICommandBuilder c,Machine m,long now){
        var r=m.round;boolean finished=r!=null&&r.phase.equals("Finished");
        addCards(c,"#DealerCards",r==null?List.of():r.dealer,"dealer",!finished,true,now,r);
        c.set("#DealerTotal.Text",finished?"DEALER · "+Engine.points(r.dealer):"DEALER");
        var mine=r==null?null:r.seats.get(actor());addCards(c,"#MyCards",mine==null?List.of():mine.hand,actor(),false,true,now,r);
        c.set("#MyTotal.Text",mine==null||mine.hand.isEmpty()?"YOUR HAND":"YOUR HAND · "+Engine.points(mine.hand));
        c.set("#Turn.Text",r!=null&&r.phase.equals("Playing")?"Hit, stand or double. Dealer stands on 17.":"Blackjack pays 3:2 · Dealer stands on soft 17");
        c.clear("#OtherHands");if(r!=null){int index=0;for(var entry:r.seats.entrySet())if(!entry.getKey().equals(actor())){
            String sel="#OtherHands["+index+++ "]";var seat=entry.getValue();c.append("#OtherHands","Pages/Kweebet/Hand.ui");c.set(sel+" #Name.Text",seat.name);c.set(sel+" #Detail.Text",seat.result);c.set(sel+" #Total.Text",seat.hand.isEmpty()?"":Integer.toString(Engine.points(seat.hand)));addCards(c,sel+" #Cards",seat.hand,entry.getKey(),false,false,now,r);
        }}
    }
    void addCards(UICommandBuilder c,String parent,List<Integer> hand,String player,boolean hideHole,boolean large,long now,Round round){
        c.clear(parent);int count=hand.isEmpty()?2:hand.size(),width=large?150:56,height=large?210:80,space=Math.min(large?174:64,(large?1100:750)/Math.max(1,count));
        int available=large?1280:900,first=(available-(count-1)*space-width)/2;
        for(int i=0;i<count;i++){
            String selector=parent+"["+i+"]";c.append(parent,large?"Pages/Kweebet/Card.ui":"Pages/Kweebet/SmallCard.ui");
            boolean hidden=hand.isEmpty()||hideHole&&i==1;String key=(round==null?0:round.id)+":"+player+":"+i;long began=0;
            if(!hand.isEmpty()){
                Long old=dealtAt.get(key);if(old==null){long start=round!=null&&now-round.started<1500?round.started+i*120:layoutRound==(round==null?0:round.id)?now:0;dealtAt.put(key,start);old=start;}began=old;
            }
            c.set(selector+" #Back.Visible",hidden);c.set(selector+" #Face.Visible",!hidden);
            if(!hidden){int card=hand.get(i);c.set(selector+" #Rank.Text",rank(card));c.set(selector+" #Suit.Background",Value.ref("Pages/Kweebet/Styles.ui","Suit"+suit(card)));}
            cards.add(new CardMotion(selector,first+i*space,3,width,height,began,hidden));
        }
    }
    boolean move(UICommandBuilder c,String selector,int x,int y,int w,int h){
        String value=x+":"+y+":"+w+":"+h;if(value.equals(lastMotion.put(selector,value)))return false;c.setObject(selector+".Anchor",anchor(x,y,w,h));return true;
    }
    boolean animate(UICommandBuilder c,Machine m,long now){
        if(view!=View.PLAY||!m.kind.equals("Crash"))return false;
        boolean changed=false;String readout=readout(m,actor(),now);
        if(!readout.equals(lastMotion.put("readout",readout))){c.set("#GameReadout.Text",readout);changed=true;}
        if(cashable(m)){
            var seat=m.round.seats.get(actor());String text="TAKE "+(int)((long)seat.stake*Engine.multiplier(m.round,now)/100);
            c.set("#PotentialWin.Text",potential(m,actor(),now));
            if(!text.equals(lastMotion.put("cash",text))){c.set("#Primary.Text",text);changed=true;}
        }
        return changed;
    }
    void pulse(long now){
        if(!current()){KweebetPlugin.pages.remove(this);return;}
        if(now>=nextPresence){nextPresence=now+250;if(!KweebetPlugin.usable(store,playerRef,at)){close();return;}}
        var m=machine();if(m==null)return;autoCollect(m,now,true);
        if(m.version!=shownVersion){var c=new UICommandBuilder();var e=new UIEventBuilder();renderState(c,e,false,now);sendUpdate(c,e,false);}
        if(now>=nextStatus){nextStatus=now+1000;var c=new UICommandBuilder();renderControls(c,m,now);if(view==View.MANAGE||view==View.SETTINGS)renderOwner(c,m);sendUpdate(c,new UIEventBuilder(),false);}
        if(now>=nextFrame){if(nextFrame<now-100)nextFrame=now;nextFrame+=FRAME_MS;var c=new UICommandBuilder();if(animate(c,m,now)){sendUpdate(c,new UIEventBuilder(),false);animationUpdates++;if(firstAnimation==0)firstAnimation=now;lastAnimation=now;}}
    }
    void autoCollect(Machine m,long now,boolean sound){
        if(lastAutoVersion==m.version)return;
        try{var paid=Engine.collectAvailable(m,actor(),wallet(),now);if(!paid.isEmpty()){
            notice=String.join(", ",paid.entrySet().stream().map(e->units(e.getValue(),e.getKey())).toList())+" added to your bag";
            if(sound)KweebetPlugin.cue(store,ref,"Claim");
        }}catch(IllegalArgumentException|IllegalStateException ex){notice="Your winnings are stored here. Collect when ready.";}
        lastAutoVersion=m.version;
    }
    String rules(Machine m){return switch(m.kind){
        case "Plinko"->"Choose an amount and press Drop. The ball starts immediately. Eight server choices decide its bucket.\n\nTotal returns: 10x / 3x / 1.5x / 0.75x / 0.25x / 0.75x / 1.5x / 3x / 10x. Use multiples of four for exact item returns.\n\nReturns are generated by the game; no house stock is needed. Expected return 99.0234375%. Closing the page keeps the same result. A full bag keeps your reward safe.";
        case "Duck"->"Press Race and your yellow duck races three server ducks immediately. Every duck has an equal 1-in-4 chance.\n\nIf your duck wins, you receive 4x your bet in total, including your stake, plus any displayed bonus. A loss returns zero.\n\nThe game generates rewards; no funding or lobby is needed. The server locks the result at the start. A full bag keeps your reward safe.";
        case "Blackjack"->"Press Deal to start immediately. Hit, stand or double on your turn.\n\nSix shuffled decks. Dealer stands on soft17. Natural blackjack returns 2.5x total, an ordinary win 2x, a push your bet and a loss zero.\n\nDouble adds one equal bet, draws once and stands. A 30-second turn timeout stands. The game generates returns; no house stock is needed. A full bag keeps your reward safe.";
        default->"Press Bet and the multiplier starts immediately. Take the shown return before it crashes. The server decides the deadline; a request at or after it loses.\n\nReturns include your stake and round down to whole items. Press Take yourself to lock the return.\n\n3% nominal edge; an instant1.00x crash is possible. Cap10.00x. The game generates returns with no house stock. A full bag keeps your reward safe.";
    };}
    void sendState(boolean forms,long now){var c=new UICommandBuilder();var e=new UIEventBuilder();renderState(c,e,forms,now);sendUpdate(c,e,false);}
    @Override public void handleDataEvent(Ref<EntityStore> reference,Store<EntityStore> s,Action a){s.getExternalData().getWorld().execute(()->{
        if(!current())return;
        if(a.action.equals("close")){close();return;}
        if(!KweebetPlugin.usable(s,playerRef,at))return;
        var m=machine();long now=System.currentTimeMillis();boolean forms=false;String picked=null;noticeError=false;
        try{
            Engine.tick(m,now);
            if(a.action.startsWith("pick:")){
                Engine.owner(m,actor());String id=a.action.substring(5);Engine.require(itemChoices(m).contains(id),"That item is no longer available");picked=id;
                if(pickFor.equals("stock")){stockItem=id;stockAmount=suggestedStock(m,id);validAmount=true;view=View.MANAGE;}
                else{if(pickFor.equals("stake"))draftItem=id;else draftBonus=id;view=View.SETTINGS;}
            }else
            switch(a.action){
                case "manage"->{Engine.owner(m,actor());view=View.SETTINGS;advanced=false;notice="";forms=true;}
                case "stock","deposit","withdraw","accepting"->throw new IllegalArgumentException("This control has been removed");
                case "settings"->{Engine.owner(m,actor());view=View.SETTINGS;advanced=false;notice="";forms=true;}
                case "advanced"->{Engine.owner(m,actor());advanced=!advanced;}
                case "choose-stock","choose-stake","choose-bonus"->{Engine.owner(m,actor());pickFor=a.action.substring(7);view=View.ITEMS;notice="";}
                case "close-items"->{Engine.owner(m,actor());view=pickFor.equals("stock")?View.MANAGE:View.SETTINGS;}
                case "amount","stock-plus","stock-minus"->{
                    Engine.owner(m,actor());try{int n=Integer.parseInt(a.amount);validAmount=n>0&&n<=10000;if(validAmount)stockAmount=n;}catch(NumberFormatException ex){validAmount=false;}
                    if(!a.action.equals("amount")){stockAmount=Math.clamp(stockAmount+(a.action.equals("stock-plus")?1:-1),1,10000);validAmount=true;}
                    var c=new UICommandBuilder();if(!a.action.equals("amount"))c.set("#Amount.Value",Integer.toString(stockAmount));renderOwner(c,m);sendUpdate(c,new UIEventBuilder(),false);return;
                }
                case "rules"->view=view==View.RULES?View.PLAY:View.RULES;
                case "back"->view=View.PLAY;
                case "stake","auto","plus","minus"->{
                    var t=terms(m);try{int n=Integer.parseInt(a.stake);validStake=n>=t.min&&n<=t.max&&n%step(m.kind)==0;if(validStake)selectedStake=n;}catch(NumberFormatException ex){validStake=false;}
                    selectedAuto=0;validAuto=true;
                    if(a.action.equals("plus")||a.action.equals("minus")){selectedStake=Math.clamp(selectedStake+(a.action.equals("plus")?step(m.kind):-step(m.kind)),t.min,t.max);validStake=true;}
                    var c=new UICommandBuilder();if(a.action.equals("plus")||a.action.equals("minus"))c.set("#Stake.Value",Integer.toString(selectedStake));renderControls(c,m,now);sendUpdate(c,new UIEventBuilder(),false);return;
                }
                default->{
                    String action=a.action,item=terms(m).item;int amount=0,auto=0;
                    if(action.equals("primary")){
                        if(cashable(m))action="cash";
                        else if(claim(m)!=null&&!myTurn(m)){action="claim";item=claim(m).getKey();}
                        else{if(Engine.live(m))return;action="play";amount=Integer.parseInt(a.stake);auto=0;}
                    }else if(Set.of("deposit","withdraw").contains(action)){Engine.owner(m,actor());item=stockItem;Engine.require(item.equals(a.fundItem),"Stock item changed. Try again.");amount=Integer.parseInt(a.amount);NativeWallet.supported(item);}
                    else if(action.equals("bonus")){Engine.owner(m,actor());item=m.config.bonusItem;amount=0;NativeWallet.supported(item);}
                    else if(action.equals("configure")){Engine.owner(m,actor());item=a.item.strip();amount=Integer.parseInt(a.min);auto=Integer.parseInt(a.max);NativeWallet.supported(item);}
                    else if(action.equals("invite"))action="access";
                    var result=Engine.execute(m,actor(),playerRef.getUsername(),new Engine.Request(action,Long.parseLong(a.version),amount,auto,item,a.action.equals("invite")?a.target.strip():""),wallet(),now);
                    notice=switch(action){case "play"->"Good luck!";case "deposit"->"Added "+units(amount,item)+" to prize stock";case "withdraw"->"Moved "+units(amount,item)+" to your bag";case "configure"->"Bet settings saved";case "bonus"->amount==0?"Winner bonus turned off":"Winner bonus saved: "+units(amount,item);default->result.message();};KweebetPlugin.cue(s,reference,result.cue());autoCollect(m,now,!result.cue().equals("Claim"));
                    if(action.equals("configure")||action.equals("bonus"))forms=true;
                    if(action.equals("cancel")){heardRound=m.round.id;heardPhase=m.round.phase;}
                }
            }
        }catch(IllegalArgumentException|IllegalStateException ex){noticeError=true;notice=ex.getMessage()==null?"Choose a valid amount":ex.getMessage();if(notice.contains("stale"))notice="The table changed. Try again.";}
        sendState(forms,now);
        if(picked!=null){var c=new UICommandBuilder();if(pickFor.equals("stock")){c.set("#FundItem.Value",stockItem);c.set("#Amount.Value",Integer.toString(stockAmount));}else c.set(pickFor.equals("stake")?"#Item.Value":"#BonusItem.Value",picked);sendUpdate(c,new UIEventBuilder(),false);}
    });}
    @Override public void onDismiss(Ref<EntityStore> reference,Store<EntityStore> s){
        dismissed=true;KweebetPlugin.pages.remove(this);
        KweebetPlugin.instance.getLogger().atInfo().log("CW_UI2 full=%d motion=%d motionSpanMs=%d openMs=%d (server patches, not measured client FPS)",fullUpdates,animationUpdates,Math.max(0,lastAnimation-firstAnimation),System.currentTimeMillis()-openedAt);
    }
}
