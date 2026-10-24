# Game rules

The server locks outcomes, deadlines and exact item returns. New rounds start immediately and generate rewards; no house stock is required. Closing a page or disconnecting keeps the same committed result. A full bag keeps the exact reward owned until collection succeeds. There is no real-money payment integration.

## Duck Race

Your yellow duck races three server-controlled ducks immediately. Each has an equal 1-in-4 chance. The animation lasts 4.5 seconds; its result was recorded at the start. A win returns **4× the bet in total**, including the bet. A loss returns zero. Other players may watch; this mode has no player lobby or pooled-entry wait.

## Blackjack

Deal starts immediately with six freshly shuffled decks. The dealer's hole card stays hidden during your turn. Choose Hit, Stand or Double. A 30-second action timeout stands so a disconnected player cannot hold the table indefinitely; this is not a game-start countdown.

| Result | Total return on a 4-item bet |
| --- | --- |
| Natural Blackjack; dealer has no natural | 10 |
| Ordinary win | 8 |
| Push, including both naturals | 4 |
| Bust or loss | 0 |

Dealer stands on soft17. Aces reduce from11 to1 as needed. Double pays one equal extra bet, draws once and stands; a 4+4 doubled win returns16 total. Splitting, insurance and surrender are not included.

## Crash

Bet starts the multiplier immediately. **Take** locks the displayed total if the request reaches the server before the crash. A tie loses. Press Take yourself; new games have no auto-take setting. Cash-out finishes a new game immediately so another Bet does not wait for a hidden countdown or crash.

Multiplier rises0.01× per50ms. Server threshold samples `max(100, min(1001, ceil(97 / (1 − U))))` hundredths for uniform `U` in `[0,1)`. Above1× the nominal survival profile is approximately0.97/x, with strict deadline ordering. An instant1.00× loss is possible; the surviving tail pays at the10× cap. Total return is `floor(bet × multiplier)`, including the bet, in whole items. The nominal edge is3%; integer rounding affects small bets.

## Plinko

Drop starts immediately. Eight independent server choices select one of nine buckets. Total return multipliers left to right: **10× / 3× / 1.5× / 0.75× / 0.25× / 0.75× / 1.5× / 3× / 10×**. Use amounts divisible by four. All256 paths give expected return1014/1024 (99.0234375%). See [Plinko](plinko.md).

Accepted round terms stay locked until that round finishes. The server decides outcomes and deadlines; this version does not implement a public commitment/reveal fairness protocol. Multiplayer spectator behaviour is still being evaluated.
