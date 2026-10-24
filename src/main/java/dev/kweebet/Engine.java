package dev.kweebet;

import java.security.SecureRandom;
import java.util.*;
import static dev.kweebet.KweebetState.*;

/** All methods run on the owning world thread. Plan on a copy, transfer once, then commit. */
final class Engine {
    static final int LIMIT=1_000_000,SEATS=8,CRASH_CAP=1000;
    static final SecureRandom RANDOM=new SecureRandom();
    interface Wallet {boolean take(String item,int count);boolean give(String item,int count);}
    record Request(String action,long version,int amount,int auto,String item,String target){}
    record Transfer(String item,int count,boolean out){}
    record Result(String message,String cue){}
    static void require(boolean b,String msg){if(!b)throw new IllegalArgumentException(msg);}
    static int amount(int n){require(n>0&&n<=LIMIT,"Amount must be 1–1000000");return n;}
    static int get(Map<String,Integer> map,String item){return map.getOrDefault(item,0);}
    static void plus(Map<String,Integer> map,String item,int n){int v=Math.addExact(get(map,item),n);require(v>=0&&v<=LIMIT,"Item stock limit exceeded");if(v==0)map.remove(item);else map.put(item,v);}
    static void owner(Machine m,String actor){require(actor.equals(m.owner),"Only the placing owner may manage this machine");}
    static boolean live(Machine m){return m.round!=null&&!m.round.phase.equals("Finished");}
    static boolean obligations(Machine m){return live(m)||!m.claims.isEmpty();}
    static Machine copy(Machine m){return JSON.fromJson(JSON.toJson(m),Machine.class);}
    static Terms copy(Terms t){return JSON.fromJson(JSON.toJson(t),Terms.class);}
    static Map<String,Integer> total(Machine m){
        Map<String,Integer> t=new TreeMap<>(m.bank);
        for(var claim:m.claims.values())for(var e:claim.entrySet())plus(t,e.getKey(),e.getValue());
        if(live(m)){
            var r=m.round;plus(t,r.terms.item,r.pot);
            for(var s:r.seats.values())plus(t,r.terms.item,s.reserved);
            if(r.bonus>0&&!r.generatedReturns)plus(t,r.terms.bonusItem,r.bonus);
        }
        return t;
    }
    /** Physical items plus consumed stakes minus generated rewards: exact across every transition. */
    static Map<String,Long> ledger(Machine m){
        Map<String,Long> result=new TreeMap<>();total(m).forEach((id,n)->ledgerPlus(result,id,n));
        m.consumed.forEach((id,n)->ledgerPlus(result,id,n));m.generated.forEach((id,n)->ledgerPlus(result,id,-n));return result;
    }
    static void ledgerPlus(Map<String,Long> map,String id,long delta){long value=Math.addExact(map.getOrDefault(id,0L),delta);if(value==0)map.remove(id);else map.put(id,value);}
    static void supply(Machine m,String item,int delta){if(delta>0)ledgerPlus(m.generated,item,delta);else if(delta<0)ledgerPlus(m.consumed,item,-(long)delta);}
    static void settleSeat(Machine m,String who,Seat s,int returned,String result){
        var r=m.round;
        if(r.generatedReturns)supply(m,r.terms.item,returned-s.stake);
        else{int backing=s.stake+s.reserved;require(returned<=backing,"Unfunded payout");plus(m.bank,r.terms.item,backing-returned);}
        r.pot-=s.stake;s.reserved=0;s.returned=returned;s.done=true;s.result=result;claim(m,who,r.terms.item,returned);
    }
    static void validate(Machine m){
        require(m!=null&&m.version>=1&&Set.of("Duck","Blackjack","Crash","Plinko").contains(m.kind),"Invalid machine");UUID.fromString(m.owner);
        require(m.config!=null&&m.bank!=null&&m.claims!=null&&m.claims.size()<=10000&&m.history!=null&&m.history.size()<=20,"Invalid balances");
        require(m.generated!=null&&m.consumed!=null,"Invalid supply accounting");
        for(var map:List.of(m.generated,m.consumed))for(var entry:map.entrySet())require(entry.getKey()!=null&&!entry.getKey().isBlank()&&entry.getValue()!=null&&entry.getValue()>0,"Invalid generated/consumed item record");
        terms(m.config,m.kind);
        for(var map:m.claims.values())for(var n:map.values())amount(n);
        for(var n:m.bank.values())amount(n);
        if(m.round!=null){var r=m.round;require(r.terms!=null&&r.seats!=null&&r.seats.size()<=SEATS&&Set.of("Lobby","Playing","Finished").contains(r.phase),"Invalid round");terms(r.terms,m.kind);require(r.pot>=0&&r.pot<=LIMIT&&r.bonus>=0&&r.bonus<=LIMIT,"Invalid escrow");
            for(var e:r.seats.entrySet()){UUID.fromString(e.getKey());var s=e.getValue();amount(s.stake);require(s.reserved>=0&&s.reserved<=LIMIT&&s.returned>=0&&s.returned<=LIMIT&&s.hand!=null,"Invalid seat");}
            if(m.kind.equals("Plinko")&&r.started>0)for(var seat:r.seats.values())Plinko.bucket(seat.plinkoPath);
            if(r.phase.equals("Finished"))require(r.pot==0&&r.bonus==0&&r.seats.values().stream().allMatch(s->s.reserved==0),"Finished round retains funds");
        }
        total(m);
    }
    static void terms(Terms t,String kind){require(t.item!=null&&!t.item.isBlank()&&t.bonusItem!=null&&!t.bonusItem.isBlank(),"Choose exact item IDs");amount(t.min);amount(t.max);require(t.max>=t.min&&t.max<=1000&&t.bonus>=0&&t.bonus<=10000&&t.invited!=null&&t.invited.size()<=64,"Invalid limits");if(kind.equals("Blackjack"))require(t.min%2==0&&t.max%2==0,"Blackjack requires even stakes for exact 3:2 profit");if(kind.equals("Plinko"))require(t.min%4==0&&t.max%4==0,"Plinko limits must be multiples of four for exact payouts");if(!kind.equals("Duck"))require(t.bonus==0,"Bonus bundle currently belongs to Duck Race");}
    static void claim(Machine m,String player,String item,int count){if(count>0)plus(m.claims.computeIfAbsent(player,k->new TreeMap<>()),item,count);}
    static Result execute(Machine real,String actor,String name,Request q,Wallet wallet,long now){
        require(real.version==q.version,"This screen is stale. Refresh before committing items.");
        var m=copy(real);Transfer transfer=null;String cue="Handling",message="Done";
        switch(q.action){
            case "deposit"->{owner(m,actor);int n=amount(q.amount);plus(m.bank,q.item,n);transfer=new Transfer(q.item,n,false);message="Bankroll funded";cue="Bet";}
            case "withdraw"->{owner(m,actor);int n=amount(q.amount);require(get(m.bank,q.item)>=n,"Only uncommitted stock may be withdrawn");plus(m.bank,q.item,-n);transfer=new Transfer(q.item,n,true);message="Available stock withdrawn";cue="Claim";}
            case "configure"->{owner(m,actor);require(!obligations(m),"Resolve round and collect claims before changing terms");m.config.item=q.item;m.config.min=amount(q.amount);m.config.max=amount(q.auto);terms(m.config,m.kind);message="Stake terms saved";}
            case "bonus"->{owner(m,actor);require(!obligations(m),"Terms are frozen while obligations exist");require(m.kind.equals("Duck"),"Duck Race bonus only");require(q.amount>=0&&q.amount<=10000,"Bonus must be 0–10000");m.config.bonusItem=q.item;m.config.bonus=q.amount;message="Separate bonus saved; fund this exact item before opening";}
            case "access"->{owner(m,actor);require(!obligations(m),"Terms are frozen");if(q.target==null||q.target.isBlank())m.config.inviteOnly=!m.config.inviteOnly;else{UUID.fromString(q.target);if(!m.config.invited.remove(q.target))m.config.invited.add(q.target);}message="Access profile updated";}
            case "accepting"->{owner(m,actor);m.accepting=!m.accepting;message=m.accepting?"Open for new games":"New rounds paused; accepted games continue";}
            case "open"->{owner(m,actor);require(!live(m),"A round is already open");openLobby(m,now);message="Lobby open for 20 seconds; terms locked";cue="Start";}
            case "join","play"->{
                if(q.action.equals("play")&&!live(m))openGame(m,now);
                var r=lobby(m,now);require(!r.terms.inviteOnly||actor.equals(m.owner)||r.terms.invited.contains(actor),"This game is invite only");require(!r.seats.containsKey(actor)&&r.seats.size()<SEATS,"Already joined or table full");int stake=amount(q.amount);require(stake>=r.terms.min&&stake<=r.terms.max,"Stake outside advertised limits");if(m.kind.equals("Duck")&&!r.generatedReturns)require(stake==r.terms.min,"Duck entries use equal fixed minimum stakes");if(m.kind.equals("Blackjack"))require(stake%2==0,"Choose an even item stake");if(m.kind.equals("Plinko"))require(stake%4==0,"Plinko stakes must be multiples of four");if(m.kind.equals("Crash"))require(q.auto==0||q.auto>=101&&q.auto<=CRASH_CAP,"Auto cash-out must be 1.01x–10.00x or off");
                var s=new Seat();s.name=name==null?actor.substring(0,8):name.substring(0,Math.min(24,name.length()));s.stake=stake;s.auto=q.auto;
                s.reserved=r.generatedReturns?0:m.kind.equals("Blackjack")?stake*3/2:(m.kind.equals("Crash")||m.kind.equals("Plinko"))?stake*9:0;
                require(get(m.bank,r.terms.item)>=s.reserved,"Underfunded: maximum profit must be reserved before any charge");plus(m.bank,r.terms.item,-s.reserved);r.pot=Math.addExact(r.pot,stake);r.seats.put(actor,s);transfer=new Transfer(r.terms.item,stake,false);message="Stake accepted; exact terms locked";cue="Bet";
                if(q.action.equals("play")&&(r.generatedReturns||m.kind.equals("Plinko"))){
                    if(m.kind.equals("Duck")&&r.generatedReturns)for(int i=1;i<4;i++){
                        var bot=new Seat();bot.bot=true;bot.name=new String[]{"","Coral duck","Sky duck","Jade duck"}[i];bot.stake=stake;
                        String id=new UUID(0,i).toString();if(id.equals(actor))id=new UUID(1,i).toString();r.seats.put(id,bot);
                    }
                    start(m,now);
                }
            }
            case "cancel"->{owner(m,actor);require(m.round!=null&&m.round.phase.equals("Lobby"),"Only an unstarted lobby can be cancelled");cancel(m);message="Unstarted stakes became owned refund claims";cue="Finish";}
            case "hit","stand","double"->{var r=playing(m,"Blackjack");require(actor.equals(r.turn)&&now<r.turnDeadline,"It is not your live turn");var s=r.seats.get(actor);require(s!=null&&!s.done,"Hand finished");
                if(q.action.equals("double")){require(s.hand.size()==2&&!s.doubled,"Double is available on the first two cards only");int extra=s.stake,additional=r.generatedReturns?0:s.stake*2-s.reserved;require(get(m.bank,r.terms.item)>=additional,"House cannot fund double; no extra stake charged");plus(m.bank,r.terms.item,-additional);s.reserved+=additional;r.pot+=extra;s.stake+=extra;s.doubled=true;transfer=new Transfer(r.terms.item,extra,false);s.hand.add(draw(r));s.done=true;}
                else if(q.action.equals("stand"))s.done=true;
                else{s.hand.add(draw(r));if(points(s.hand)>=21)s.done=true;}
                if(s.done)nextTurn(m,now);message="Card action accepted";cue="Action";}
            case "cash"->{var r=playing(m,"Crash");var s=r.seats.get(actor);require(s!=null&&!s.done,"No live wager to cash out");int at=multiplier(r,now);require(at<r.crashAt||r.crashAt>CRASH_CAP,"The server crash deadline has passed");cash(m,actor,at);if(r.generatedReturns)finish(m,"Cash-out · "+s.result);message="Cash-out locked: "+s.returned+" total items including stake";cue="Claim";}
            case "claim"->{var c=m.claims.get(actor);int n=c==null?0:get(c,q.item);require(n>0,"No owned claim for this item");plus(c,q.item,-n);if(c.isEmpty())m.claims.remove(actor);transfer=new Transfer(q.item,n,true);message="Collected "+n+" items";cue="Claim";}
            default->throw new IllegalArgumentException("Unknown action");
        }
        m.version++;validate(m);var expected=ledger(real);if(transfer!=null)ledgerPlus(expected,transfer.item,transfer.out?-transfer.count:transfer.count);require(expected.equals(ledger(m)),"Accounting invariant failed");
        if(transfer!=null)require(transfer.out?wallet.give(transfer.item,transfer.count):wallet.take(transfer.item,transfer.count),transfer.out?"Inventory full; owned funds unchanged":"Not enough matching plain items; nothing charged");
        commit(real,m);return new Result(message,cue);
    }
    static void commit(Machine a,Machine b){a.owner=b.owner;a.kind=b.kind;a.version=b.version;a.serial=b.serial;a.accepting=b.accepting;a.config=b.config;a.bank=b.bank;a.claims=b.claims;a.generated=b.generated;a.consumed=b.consumed;a.round=b.round;a.history=b.history;}
    static Map<String,Integer> collectAvailable(Machine m,String actor,Wallet wallet,long now){
        Map<String,Integer> paid=new TreeMap<>();
        for(String item:new ArrayList<>(m.claims.getOrDefault(actor,Map.of()).keySet())){
            int count=get(m.claims.getOrDefault(actor,Map.of()),item);
            try{execute(m,actor,"",new Request("claim",m.version,0,0,item,""),wallet,now);paid.put(item,count);}
            catch(IllegalArgumentException ex){if(!"Inventory full; owned funds unchanged".equals(ex.getMessage()))throw ex;}
        }
        return paid;
    }
    static void openLobby(Machine m,long now){
        var r=new Round();r.id=++m.serial;r.terms=copy(m.config);r.deadline=now+20_000;
        if(r.terms.bonus>0){require(get(m.bank,r.terms.bonusItem)>=r.terms.bonus,"Prefund the advertised bonus before opening");plus(m.bank,r.terms.bonusItem,-r.terms.bonus);r.bonus=r.terms.bonus;}
        m.round=r;
    }
    static void openGame(Machine m,long now){
        var r=new Round();r.id=++m.serial;r.terms=copy(m.config);r.deadline=now+1;r.generatedReturns=true;r.bonus=r.terms.bonus;m.round=r;
    }
    static Round lobby(Machine m,long now){require(m.round!=null&&m.round.phase.equals("Lobby")&&now<m.round.deadline,"Lobby is closed");return m.round;}
    static Round playing(Machine m,String kind){require(m.kind.equals(kind)&&m.round!=null&&m.round.phase.equals("Playing"),"No active "+kind+" round");return m.round;}
    static void cancel(Machine m){var r=m.round;for(var e:r.seats.entrySet()){var s=e.getValue();if(!s.bot)claim(m,e.getKey(),r.terms.item,s.stake);plus(m.bank,r.terms.item,s.reserved);s.reserved=0;s.done=true;s.result="Refund";s.returned=s.bot?0:s.stake;}if(r.bonus>0&&!r.generatedReturns)plus(m.bank,r.terms.bonusItem,r.bonus);r.pot=0;r.bonus=0;finish(m,"Lobby cancelled; stakes refunded as claims");}
    static void finish(Machine m,String text){m.round.phase="Finished";m.history.add(0,"#"+m.round.id+" · "+text);while(m.history.size()>20)m.history.removeLast();}
    static int draw(Round r){require(r.deckIndex<r.deck.size(),"Shoe exhausted");return r.deck.get(r.deckIndex++);}
    static int points(List<Integer> cards){int n=0,aces=0;for(int card:cards){int rank=card%13;if(rank==0){n+=11;aces++;}else n+=Math.min(rank+1,10);}while(n>21&&aces-->0)n-=10;return n;}
    static boolean natural(List<Integer> cards){return cards.size()==2&&points(cards)==21;}
    static void start(Machine m,long now){var r=m.round;r.phase="Playing";r.started=now;
        if(m.kind.equals("Duck")){r.finish.addAll(r.seats.keySet());Collections.shuffle(r.finish,RANDOM);r.winner=r.finish.getFirst();}
        else if(m.kind.equals("Crash")){r.crashAt=sampleCrash(RANDOM.nextDouble());}
        else if(m.kind.equals("Plinko")){for(var seat:r.seats.values())seat.plinkoPath=Plinko.path();}
        else{for(int i=0;i<312;i++)r.deck.add(i%52);Collections.shuffle(r.deck,RANDOM);for(int i=0;i<2;i++){for(var s:r.seats.values())s.hand.add(draw(r));r.dealer.add(draw(r));}for(var s:r.seats.values())s.done=natural(s.hand);if(natural(r.dealer))settleBlackjack(m);else nextTurn(m,now);}
    }
    // P(reaching x before crash) exactly .97/x above 1x; ceil thresholds to hundredths, cap pays at 10x on the surviving tail.
    static int sampleCrash(double uniform){require(uniform>=0&&uniform<1,"Invalid random sample");return Math.max(100,Math.min(CRASH_CAP+1,(int)Math.ceil(97.0/(1.0-uniform))));}
    static int multiplier(Round r,long now){return Math.min(CRASH_CAP,100+(int)Math.max(0,(now-r.started)/50));}
    static void nextTurn(Machine m,long now){var r=m.round;r.turn="";for(var e:r.seats.entrySet())if(!e.getValue().done){r.turn=e.getKey();r.turnDeadline=now+30_000;return;}settleBlackjack(m);}
    static void settleBlackjack(Machine m){var r=m.round;boolean dealerNatural=natural(r.dealer);if(!dealerNatural)while(points(r.dealer)<17)r.dealer.add(draw(r));int dp=points(r.dealer);
        for(var e:r.seats.entrySet()){var s=e.getValue();int p=points(s.hand),ret;String result;
            if(p>21){ret=0;result="Bust";}else if(dealerNatural){ret=natural(s.hand)?s.stake:0;result=ret>0?"Push":"Dealer blackjack";}
            else if(natural(s.hand)&&!s.doubled){ret=s.stake*5/2;result="Blackjack 3:2 profit";}
            else if(dp>21||p>dp){ret=s.stake*2;result="Win 1:1 profit";}else if(p==dp){ret=s.stake;result="Push";}else{ret=0;result="Dealer wins";}
            settleSeat(m,e.getKey(),s,ret,result);
        }finish(m,"Blackjack · dealer "+dp);}
    static void cash(Machine m,String player,int at){var r=m.round;var s=r.seats.get(player);int ret=(int)((long)s.stake*at/100);settleSeat(m,player,s,ret,String.format(Locale.ROOT,"%.2fx · %d total",at/100.0,ret));if(r.generatedReturns)r.endMultiplier=at;}
    static boolean tick(Machine real,long now){if(!live(real))return false;
        var current=real.round;
        if(current.phase.equals("Lobby")&&now<current.deadline)return false;
        if(current.phase.equals("Playing")){
            if(real.kind.equals("Duck")&&now<current.started+(current.generatedReturns?4500:8000))return false;
            if(real.kind.equals("Blackjack")&&now<current.turnDeadline)return false;
            if(real.kind.equals("Plinko")&&now<current.started+Plinko.DURATION)return false;
            if(real.kind.equals("Crash")){int at=multiplier(current,now);boolean autoDue=current.seats.values().stream().anyMatch(s->!s.done&&s.auto>0&&s.auto<=at&&s.auto<current.crashAt);if(!autoDue&&at<current.crashAt&&at<CRASH_CAP)return false;}
        }
        var m=copy(real);var r=m.round;boolean changed=false;
        if(r.phase.equals("Lobby")&&now>=r.deadline){if(r.seats.size()<(m.kind.equals("Duck")?2:1))cancel(m);else start(m,now);changed=true;}
        else if(r.phase.equals("Playing")){
            if(m.kind.equals("Duck")&&now>=r.started+(r.generatedReturns?4500:8000)){
                if(r.generatedReturns){for(var e:r.seats.entrySet()){var s=e.getValue();boolean won=e.getKey().equals(r.winner);if(!s.bot){settleSeat(m,e.getKey(),s,won?s.stake*4:0,won?"Winner":"Finished");if(won&&r.bonus>0){claim(m,e.getKey(),r.terms.bonusItem,r.bonus);supply(m,r.terms.bonusItem,r.bonus);}}else{s.done=true;s.result=won?"Winner":"Finished";}}}
                else{claim(m,r.winner,r.terms.item,r.pot);if(r.bonus>0)claim(m,r.winner,r.terms.bonusItem,r.bonus);for(var e:r.seats.entrySet()){e.getValue().done=true;e.getValue().result=e.getKey().equals(r.winner)?"Winner":"Finished";}}
                r.pot=0;r.bonus=0;finish(m,"Duck Race · "+r.seats.get(r.winner).name+" wins");changed=true;}
            else if(m.kind.equals("Plinko")&&now>=r.started+Plinko.DURATION){
                for(var entry:r.seats.entrySet()){var seat=entry.getValue();int bucket=Plinko.bucket(seat.plinkoPath),returned=Plinko.payout(seat.stake,bucket);settleSeat(m,entry.getKey(),seat,returned,"Bucket "+(bucket+1)+" · "+Plinko.multiplier(bucket)+" · "+returned+" total");}
                finish(m,"Plinko · returns ready");changed=true;
            }
            else if(m.kind.equals("Blackjack")&&now>=r.turnDeadline){if(!r.turn.isEmpty())r.seats.get(r.turn).done=true;nextTurn(m,now);changed=true;}
            else if(m.kind.equals("Crash")){int at=multiplier(r,now);for(var e:r.seats.entrySet()){var s=e.getValue();if(!s.done&&s.auto>0&&s.auto<=at&&s.auto<r.crashAt){cash(m,e.getKey(),s.auto);changed=true;}}
                if(r.generatedReturns&&r.seats.values().stream().allMatch(s->s.done)){finish(m,String.format(Locale.ROOT,"Cash-out %.2fx",r.endMultiplier/100.0));changed=true;}
                else if(at>=r.crashAt||at==CRASH_CAP){for(var e:r.seats.entrySet()){var s=e.getValue();if(!s.done){if(r.crashAt>CRASH_CAP)cash(m,e.getKey(),CRASH_CAP);else settleSeat(m,e.getKey(),s,0,"Crashed · 0 returned");}}finish(m,String.format(Locale.ROOT,"Crash %.2fx",Math.min(CRASH_CAP,r.crashAt)/100.0));changed=true;}}
        }
        if(changed){m.version++;validate(m);require(ledger(real).equals(ledger(m)),"Tick accounting invariant failed");commit(real,m);}return changed;
    }
}
