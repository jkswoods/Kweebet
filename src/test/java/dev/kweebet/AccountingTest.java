package dev.kweebet;
import java.util.*;
import static dev.kweebet.KweebetState.*;

/** Focused conservation/state tests: no claim that these are native or multiplayer observations. */
public final class AccountingTest {
    static int checks;static final String OWNER=UUID.randomUUID().toString(),A=UUID.randomUUID().toString(),B=UUID.randomUUID().toString(),C=UUID.randomUUID().toString(),D=UUID.randomUUID().toString();
    static final String IRON="Ingredient_Bar_Iron",COPPER="Ingredient_Bar_Copper";
    static void check(boolean b,String msg){if(!b)throw new AssertionError(msg);checks++;}
    static final class Wallet implements Engine.Wallet {
        Map<String,Integer> items=new TreeMap<>();boolean full;
        Wallet(int n){items.put(IRON,n);items.put(COPPER,n);}
        public boolean take(String item,int n){if(Engine.get(items,item)<n)return false;Engine.plus(items,item,-n);return true;}
        public boolean give(String item,int n){if(full)return false;Engine.plus(items,item,n);return true;}
    }
    static Machine machine(String kind){var m=new Machine();m.owner=OWNER;m.kind=kind;return m;}
    static void action(Machine m,String actor,Wallet w,String action,int amount,int auto,String item,long now){Engine.execute(m,actor,"Player "+actor.substring(0,5),new Engine.Request(action,m.version,amount,auto,item,""),w,now);}
    static void refused(Runnable run,String message){try{run.run();throw new AssertionError("Expected refusal: "+message);}catch(IllegalArgumentException e){checks++;}}
    static Machine reload(Machine m){var s=new KweebetState();s.machines.put("0:1:2",m);String saved=s.save();var fresh=new KweebetState();fresh.load(saved);check(fresh.save().equals(saved),"Normal DTO reload preserves full round/claim state");return fresh.machines.get("0:1:2");}
    static void blackjack(Wallet house){
        int[][] player={{0,9},{9,8},{9,7},{9,5},{9,12},{0,9}};
        int[][] dealer={{9,8},{9,7},{9,7},{9,8},{9,8},{0,12}};
        int[] expected={10,8,4,0,8,4};
        for(int i=0;i<player.length;i++){
            var m=machine("Blackjack");var w=new Wallet(100);action(m,OWNER,house,"deposit",100,0,IRON,0);action(m,OWNER,house,"open",0,0,IRON,0);action(m,A,w,"join",4,0,IRON,1);
            m.round.phase="Playing";m.round.dealer=new ArrayList<>(Arrays.stream(dealer[i]).boxed().toList());m.round.seats.get(A).hand=new ArrayList<>(Arrays.stream(player[i]).boxed().toList());
            Engine.settleBlackjack(m);Engine.validate(m);check(Engine.get(m.claims.getOrDefault(A,Map.of()),IRON)==expected[i],"Blackjack exact branch total "+i);check(Engine.get(m.bank,IRON)+expected[i]==104,"Blackjack funded conservation "+i);
        }
        var m=machine("Blackjack");var w=new Wallet(100);action(m,OWNER,house,"deposit",8,0,IRON,0);action(m,OWNER,house,"open",0,0,IRON,0);action(m,A,w,"join",4,0,IRON,1);m.round.phase="Playing";m.round.turn=A;m.round.turnDeadline=30000;m.round.seats.get(A).hand=new ArrayList<>(List.of(9,4));m.round.dealer=new ArrayList<>(List.of(9,7));m.round.deck=new ArrayList<>(List.of(4));
        action(m,A,w,"double",0,0,IRON,100);check(w.items.get(IRON)==92&&m.claims.get(A).get(IRON)==16,"Funded double charges one extra stake and exact 2x full doubled wager");
        var unfunded=machine("Blackjack");var u=new Wallet(100);action(unfunded,OWNER,house,"deposit",6,0,IRON,0);action(unfunded,OWNER,house,"open",0,0,IRON,0);action(unfunded,A,u,"join",4,0,IRON,1);unfunded.round.phase="Playing";unfunded.round.turn=A;unfunded.round.turnDeadline=30000;unfunded.round.seats.get(A).hand=new ArrayList<>(List.of(2,4));
        refused(()->action(unfunded,A,u,"double",0,0,IRON,100),"double lacks liability");check(u.items.get(IRON)==96&&unfunded.round.seats.get(A).stake==4,"Unfunded double changes no inventory or stake");
    }
    static void plinko(){
        int[] counts=new int[9];int summed=0;
        for(int bits=0;bits<256;bits++){
            var m=machine("Plinko");var owner=new Wallet(40);var player=new Wallet(100);action(m,OWNER,owner,"deposit",36,0,IRON,0);action(m,OWNER,owner,"open",0,0,IRON,0);action(m,A,player,"join",4,0,IRON,1);check(m.round.seats.get(A).reserved==36,"Plinko reserves worst-case9x profit before accepting");Engine.tick(m,20000);var path=new ArrayList<Integer>();for(int row=0;row<8;row++)path.add((bits>>row)&1);m.round.seats.get(A).plinkoPath=path;
            Engine.commit(m,reload(m));check(m.round.seats.get(A).plinkoPath.equals(path),"Started Plinko path survives reload unchanged");int bucket=Plinko.bucket(path),expected=new int[]{40,12,6,3,1,3,6,12,40}[bucket];counts[bucket]++;summed+=expected;
            check(!Engine.tick(m,23999),"Plinko does not settle before four seconds");Engine.tick(m,24000);check(m.claims.get(A).get(IRON)==expected,"Plinko exact integer bucket return "+bucket);check(Engine.get(m.bank,IRON)+expected==40&&m.round.pot==0&&m.round.seats.get(A).reserved==0,"Plinko settlement conserves escrow plus reservation");
            check(!Engine.tick(m,25000),"Finished Plinko cannot settle twice");player.full=true;String snapshot=JSON.toJson(m);refused(()->action(m,A,player,"claim",0,0,IRON,25001),"full Plinko claim");check(snapshot.equals(JSON.toJson(m)),"Full Plinko inventory keeps owned payout");player.full=false;action(m,A,player,"claim",0,0,IRON,25002);check(player.items.get(IRON)==96+expected&&m.claims.isEmpty(),"Exact Plinko claim pays once");
            var end=Plinko.position(path,8);check(end[0]==88+bucket*138&&end[1]==556,"Animated drop lands over the same visible bucket that funded settlement pays");
            boolean within=true;for(int frame=0;frame<=120;frame++){var xy=Plinko.position(path,frame/15.0);within&=xy[0]>=16&&xy[0]<=1264&&xy[1]>=16&&xy[1]<=624;}check(within,"All30fps path samples remain inside the native board");
        }
        check(Arrays.equals(counts,new int[]{1,8,28,56,70,56,28,8,1}),"All256 paths give the exact disclosed binomial bucket weights");check(summed==1014,"Plinko payout profile has exact1014/1024 expected return");
        var m=machine("Plinko");var owner=new Wallet(100);var player=new Wallet(100);action(m,OWNER,owner,"open",0,0,IRON,0);refused(()->action(m,A,player,"join",4,0,IRON,1),"unfunded Plinko bet");check(player.items.get(IRON)==100&&m.round.seats.isEmpty(),"Unfunded Plinko never charges");action(m,OWNER,owner,"deposit",36,0,IRON,1);refused(()->action(m,A,player,"join",5,0,IRON,2),"fractional Plinko payout stake");check(player.items.get(IRON)==100,"Invalid Plinko denomination unchanged");action(m,A,player,"join",4,0,IRON,3);action(m,OWNER,owner,"cancel",0,0,IRON,4);check(m.claims.get(A).get(IRON)==4&&m.bank.get(IRON)==36,"Unstarted Plinko refunds stake and releases backing");
    }
    static void quickPlay(){
        for(String kind:List.of("Duck","Blackjack","Crash","Plinko")){
            var m=machine(kind);var house=new Wallet(1000);var a=new Wallet(100);var b=new Wallet(100);
            long version=m.version;action(m,A,a,"play",4,0,IRON,101);
            check(m.serial==1&&a.items.get(IRON)==96&&m.round.seats.get(A).stake==4,"Non-owner starts game with one atomic Play: "+kind);
            check(!m.round.phase.equals("Lobby")&&m.round.started==101&&m.round.generatedReturns,"Every new game starts immediately with no lobby or countdown");
            check(m.round.seats.get(A).reserved==0&&m.bank.isEmpty(),"No house stock or profit reservation required");
            refused(()->Engine.execute(m,A,"A",new Engine.Request("play",version,4,0,IRON,""),a,102),"duplicate old Play event");check(a.items.get(IRON)==96,"Double activation cannot charge twice");
            if(kind.equals("Plinko")){
                var path=List.copyOf(m.round.seats.get(A).plinkoPath);check(path.size()==8&&m.round.started==101,"One-click Plinko commits all eight server choices at paid start");
                Engine.commit(m,reload(m));check(m.round.seats.get(A).plinkoPath.equals(path),"Quick drop retains its immutable path across codec reload");Engine.tick(m,4101);int expected=Plinko.payout(4,Plinko.bucket(path));check(m.claims.get(A).get(IRON)==expected,"Quick drop settles exact generated return");
                a.full=true;String full=JSON.toJson(m);check(Engine.collectAvailable(m,A,a,4102).isEmpty()&&full.equals(JSON.toJson(m)),"Automatic payout retains full-inventory claim unchanged");a.full=false;
                check(Engine.collectAvailable(m,A,a,4103).get(IRON)==expected&&a.items.get(IRON)==96+expected&&m.claims.isEmpty(),"One-click game returns winnings automatically through the same owned-claim transfer");check(Engine.collectAvailable(m,A,a,4104).isEmpty()&&a.items.get(IRON)==96+expected,"Repeated automatic payout never duplicates items");
            }else if(kind.equals("Duck")){
                check(m.round.seats.size()==4&&m.round.seats.values().stream().filter(s->s.bot).count()==3&&m.round.pot==4,"Immediate Duck race has three server competitors and only the real player's stake escrow");
                String winner=m.round.winner;var before=Engine.ledger(m);Engine.commit(m,reload(m));check(m.round.winner.equals(winner),"Immediate solo race keeps its server-selected result across reload");
                Engine.tick(m,4601);int ret=winner.equals(A)?16:0;check(m.claims.getOrDefault(A,Map.of()).getOrDefault(IRON,0)==ret&&m.claims.keySet().stream().allMatch(A::equals),"Solo Duck returns disclosed4x only to the human winner; no bot-owned claims");check(Engine.ledger(m).equals(before),"Generated Duck reward/loss retains exact supply accounting");
            }else if(Engine.live(m)){
                String snap=JSON.toJson(m);refused(()->action(m,B,b,"play",4,0,IRON,102),"entry after immediate start");check(JSON.toJson(m).equals(snap)&&b.items.get(IRON)==100,"Spectator cannot join a started outcome or be charged for it");
            }
        }
        var m=machine("Plinko");var house=new Wallet(100);var empty=new Wallet(0);action(m,OWNER,house,"deposit",36,0,IRON,0);String before=JSON.toJson(m);refused(()->action(m,A,empty,"play",4,0,IRON,1),"missing player ingots");check(JSON.toJson(m).equals(before),"Failed wallet transfer does not create a round or retain sampled path");
        var player=new Wallet(100);refused(()->action(m,A,player,"play",5,0,IRON,1),"nonintegral quick drop");check(JSON.toJson(m).equals(before)&&player.items.get(IRON)==100,"Invalid single-click amount leaves state and inventory unchanged");
        m.accepting=false;Engine.commit(m,reload(m));action(m,A,player,"play",4,0,IRON,2);check(Engine.live(m)&&player.items.get(IRON)==96,"A retired saved pause flag cannot block new play or duplicate the debit");check(m.bank.get(IRON)==36,"Removing the pause gate preserves old deposited balances");
        String legacy=JSON.toJson(machine("Duck")).replace("\"accepting\":true,","");check(JSON.fromJson(legacy,Machine.class).accepting,"Old saves default to accepting without changing financial state");
    }
    static void generatedReturns(){
        for(int bucket=0;bucket<9;bucket++){
            var m=machine("Plinko");var w=new Wallet(4);action(m,A,w,"play",4,0,IRON,10);
            var path=new ArrayList<Integer>();for(int i=0;i<8;i++)path.add(i<bucket?1:0);m.round.seats.get(A).plinkoPath=path;
            var expectedLedger=Engine.ledger(m);int payout=Plinko.payout(4,bucket);Engine.tick(m,4010);
            check(m.claims.get(A).get(IRON)==payout&&m.bank.isEmpty(),"Empty-house generated Plinko pays exact bucket "+bucket);
            check(Engine.ledger(m).equals(expectedLedger),"Generated Plinko reward/loss matches the supply ledger");
            w.full=true;String snapshot=JSON.toJson(m);check(Engine.collectAvailable(m,A,w,4011).isEmpty()&&JSON.toJson(m).equals(snapshot),"Generated payout remains owned unchanged in a full bag");
            Engine.commit(m,reload(m));w.full=false;Engine.collectAvailable(m,A,w,4012);check(w.items.get(IRON)==payout&&m.claims.isEmpty(),"Generated owned reward survives reload and pays once");
            check(Engine.collectAvailable(m,A,w,4013).isEmpty(),"Generated return cannot be collected twice");
        }
        var m=machine("Blackjack");var w=new Wallet(8);action(m,A,w,"play",4,0,IRON,10);
        // A fixed test shoe isolates the exact double branch after the immediate-start assertion above.
        m.round.phase="Playing";m.round.turn=A;m.round.turnDeadline=30010;m.round.pot=4;m.round.seats.get(A).done=false;m.round.seats.get(A).hand=new ArrayList<>(List.of(2,6));m.round.dealer=new ArrayList<>(List.of(9,7));m.round.deck=new ArrayList<>(List.of(9));m.round.deckIndex=0;m.claims.clear();m.generated.clear();m.consumed.clear();
        action(m,A,w,"double",0,0,IRON,20);check(w.items.getOrDefault(IRON,0)==0&&m.round.seats.get(A).doubled&&m.round.seats.get(A).stake==8,"Generated Double charges one extra stake without house stock");check(m.claims.get(A).get(IRON)==16&&m.generated.get(IRON)==8&&m.bank.isEmpty(),"Generated Double records exact net reward and never deposits losses/returns in legacy stock");
        var duck=machine("Duck");duck.config.bonus=3;var player=new Wallet(4);action(duck,A,player,"play",4,0,IRON,10);duck.round.winner=A;Engine.tick(duck,4510);check(duck.claims.get(A).get(IRON)==16&&duck.claims.get(A).get(COPPER)==3&&duck.generated.get(COPPER)==3,"Optional Duck bonus is generated with no funding step");
        var old=machine("Crash");old.bank.put(IRON,40);String legacy=JSON.toJson(old).replace("\"generated\":{},","").replace("\"consumed\":{},","");old=JSON.fromJson(legacy,Machine.class);Engine.validate(old);var oldWallet=new Wallet(4);action(old,A,oldWallet,"play",4,0,IRON,10);check(old.bank.get(IRON)==40&&old.round.generatedReturns,"Legacy owner stock stays recoverable and is unused by new instant rounds");
        for(boolean auto:List.of(false,true)){
            var crash=machine("Crash");var bag=new Wallet(4);action(crash,A,bag,"play",4,auto?200:0,IRON,10);crash.round.crashAt=300;
            if(auto)Engine.tick(crash,5010);else action(crash,A,bag,"cash",0,0,IRON,5010);
            check(crash.round.phase.equals("Finished")&&crash.round.endMultiplier==200&&crash.claims.get(A).get(IRON)==8,"Generated manual/auto cash-out finishes immediately at the displayed total");
            check(crash.generated.get(IRON)==4&&crash.bank.isEmpty(),"Crash reward creates exact net profit without legacy stock");
            Engine.collectAvailable(crash,A,bag,5011);long id=crash.serial;action(crash,A,bag,"play",4,0,IRON,5012);check(crash.serial==id+1&&crash.round.started==5012,"Cash-out can be followed immediately by another game with no hidden crash wait");
        }
        var loss=machine("Crash");var lostBag=new Wallet(4);action(loss,A,lostBag,"play",4,0,IRON,10);loss.round.crashAt=200;String before=JSON.toJson(loss);refused(()->action(loss,A,lostBag,"cash",0,0,IRON,5010),"generated cash at crash boundary");check(JSON.toJson(loss).equals(before),"Late cash request cannot change the chosen outcome or supply counters");Engine.tick(loss,5010);check(loss.consumed.get(IRON)==4&&loss.claims.isEmpty()&&loss.bank.isEmpty(),"New Crash loss consumes its stake without filling owner stock");
        var noItems=machine("Duck");String empty=JSON.toJson(noItems);refused(()->action(noItems,A,new Wallet(0),"play",4,0,IRON,10),"unpaid immediate race");check(JSON.toJson(noItems).equals(empty),"Failed real debit cannot commit a generated race, bots or winner");
    }
    public static void main(String[]args){
        var house=new Wallet(10000);var a=new Wallet(100);var b=new Wallet(100);var c=new Wallet(100);var d=new Wallet(100);var duck=machine("Duck");
        action(duck,OWNER,house,"bonus",3,0,COPPER,0);refused(()->action(duck,OWNER,house,"open",0,0,IRON,0),"bonus unfunded");check(duck.round==null,"Unfunded open changes no state");
        action(duck,OWNER,house,"deposit",3,0,COPPER,0);action(duck,OWNER,house,"open",0,0,IRON,0);
        refused(()->action(duck,A,a,"configure",4,64,IRON,1),"owner-only/frozen config");
        Wallet[] wallets={a,b,c,d};String[] ids={A,B,C,D};for(int i=0;i<4;i++)action(duck,ids[i],wallets[i],"join",4,0,IRON,1);
        check(duck.round.pot==16&&a.items.get(IRON)==96,"Four 4-Iron entries escrow 16 Iron");
        long old=duck.version;action(duck,OWNER,house,"deposit",1,0,COPPER,2);refused(()->Engine.execute(duck,A,"A",new Engine.Request("join",old,4,0,IRON,""),a,3),"stale request");
        refused(()->action(duck,A,a,"join",4,0,IRON,3),"duplicate entrant");check(a.items.get(IRON)==96,"Repeated request does not debit twice");
        Engine.tick(duck,20000);check(duck.round.phase.equals("Playing")&&!duck.round.winner.isEmpty(),"Server picks started immutable Duck result");String winner=duck.round.winner;Engine.commit(duck,reload(duck));check(duck.round.winner.equals(winner),"Reload retains winner");Engine.tick(duck,28000);check(duck.claims.get(winner).get(IRON)==16&&duck.claims.get(winner).get(COPPER)==3,"Winner owns exact pot and separate funded bonus");
        var winning=wallets[Arrays.asList(ids).indexOf(winner)];winning.full=true;String before=JSON.toJson(duck);
        refused(()->action(duck,winner,winning,"claim",0,0,IRON,29000),"full inventory");check(JSON.toJson(duck).equals(before),"Full inventory keeps exact owned claim and revision");
        winning.full=false;action(duck,winner,winning,"claim",0,0,IRON,29000);refused(()->action(duck,winner,winning,"claim",0,0,IRON,29001),"already collected");check(winning.items.get(IRON)==112,"Claim includes stake once");
        Engine.commit(duck,reload(duck));check(!duck.claims.get(winner).containsKey(IRON),"Reload cannot re-collect paid Iron claim");action(duck,winner,winning,"claim",0,0,COPPER,29002);check(duck.claims.isEmpty(),"Bonus separately collected");
        var poor=machine("Crash");action(poor,OWNER,house,"open",0,0,IRON,0);int start=a.items.get(IRON);refused(()->action(poor,A,a,"join",10,200,IRON,1),"unfunded Crash");check(a.items.get(IRON)==start&&poor.round.pot==0,"Unfunded bet refuses before charge");
        action(poor,OWNER,house,"deposit",90,0,IRON,1);action(poor,A,a,"join",10,200,IRON,2);check(poor.bank.isEmpty()&&poor.round.seats.get(A).reserved==90,"Crash reserves full 10x profit liability");
        refused(()->action(poor,OWNER,house,"withdraw",1,0,IRON,3),"reserved owner withdrawal");
        Engine.tick(poor,20000);poor.round.crashAt=500;Engine.tick(poor,25000);check(poor.claims.get(A).get(IRON)==20,"10 Copper at 2x means 20 total (same unit arithmetic)");check(poor.bank.get(IRON)==80,"Unused Crash reservation returns to owner bank");Engine.tick(poor,40000);check(poor.round.phase.equals("Finished"),"Crash ends after committed result");
        var crash=machine("Crash");action(crash,OWNER,house,"deposit",90,0,IRON,0);action(crash,OWNER,house,"open",0,0,IRON,0);action(crash,A,a,"join",10,200,IRON,1);Engine.tick(crash,20000);crash.round.crashAt=200;Engine.tick(crash,25000);check(crash.claims.isEmpty()&&crash.bank.get(IRON)==100,"Auto exactly at crash loses, all backed items settle to bank");
        var manual=machine("Crash");action(manual,OWNER,house,"deposit",90,0,IRON,0);action(manual,OWNER,house,"open",0,0,IRON,0);action(manual,A,a,"join",10,0,IRON,1);Engine.tick(manual,20000);manual.round.crashAt=300;action(manual,A,a,"cash",0,0,IRON,25000);check(manual.claims.get(A).get(IRON)==20,"Manual 2x returns 20 total including stake");refused(()->action(manual,A,a,"cash",0,0,IRON,25001),"repeated cash-out");
        var late=machine("Crash");action(late,OWNER,house,"deposit",90,0,IRON,0);action(late,OWNER,house,"open",0,0,IRON,0);action(late,A,a,"join",10,0,IRON,1);Engine.tick(late,20000);late.round.crashAt=200;refused(()->action(late,A,a,"cash",0,0,IRON,25000),"cash at deadline");
        var cancelled=machine("Duck");action(cancelled,OWNER,house,"open",0,0,IRON,0);action(cancelled,B,b,"join",4,0,IRON,1);action(cancelled,OWNER,house,"cancel",0,0,IRON,2);check(cancelled.claims.get(B).get(IRON)==4,"Unstarted cancellation refunds exact stake once");Engine.commit(cancelled,reload(cancelled));check(cancelled.round.pot==0,"Cancellation reload has no leftover escrow");
        plinko();blackjack(house);quickPlay();generatedReturns();check(Engine.points(List.of(0,13,8))==21,"Ace reduction: A A 9");check(Engine.points(List.of(0,5))==17,"Soft17 total");
        check(Engine.sampleCrash(0)==100&&Engine.sampleCrash(.99999)==1001,"Bounded Crash distribution includes instant loss and capped surviving tail");
        int reached200=0;for(int i=0;i<100000;i++)if(Engine.sampleCrash((i+.5)/100000.0)>200)reached200++;check(reached200>48000&&reached200<49000,"Disclosed Crash survival near .97/2, strict boundary and threshold ceiling");
        System.out.println("PASS "+checks+" focused accounting/state checks; simulated wallets, not native multiplayer acceptance");
    }
}
