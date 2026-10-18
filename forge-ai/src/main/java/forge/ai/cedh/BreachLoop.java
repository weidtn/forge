package forge.ai.cedh;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import forge.card.MagicColor;
import forge.card.mana.ManaCostShard;
import forge.game.Game;
import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.player.PlayerCollection;
import forge.game.spellability.SpellAbility;
import forge.game.spellability.SpellAbilityStackInstance;
import forge.game.zone.ZoneType;

/**
 * Underworld Breach + Lion's Eye Diamond + Brain Freeze, reduced to counts and played forward with the line's own
 * step rules. The planner starts the loop only if this projection reaches the kill, and every Brain Freeze instance
 * is aimed by the same rule the projection uses, so the two can't drift apart.
 * <ul>
 * <li>Fuel: each escape exiles 3 other graveyard cards that aren't line pieces; a Brain Freeze aimed at us adds what
 * it mills, and LED's discard adds the rest of our hand.</li>
 * <li>Mana: LED makes 3, Brain Freeze costs 2. Brain Freeze is cast whenever 2 mana float, else LED is (re)cast,
 * else cracked.</li>
 * <li>Kill: with Thassa's Oracle in hand or graveyard, mill ourselves to its devotion and cast it; otherwise mill
 * every opponent to 0 (they lose on their next draw) and stop.</li>
 * </ul>
 */
final class BreachLoop {
    /** Never exiled for escape: the loop casts them again (Breach and Oracle included). */
    static final Set<String> PIECES = Set.of(ComboLibrary.BREACH, ComboLibrary.LED, ComboLibrary.BRAIN_FREEZE,
            ComboLibrary.ORACLE);

    private static final int MILL = 3;
    private static final int ESCAPE_EXILE = 3;
    private static final int LED_MANA = 3;
    private static final int BRAIN_FREEZE_MANA = 2;
    private static final int ORACLE_MANA = 2;
    /** Bound on projected actions; a live loop needs a few dozen. */
    private static final int MAX_ACTIONS = 300;
    /** Target index meaning "us"; opponents are 0..n-1. */
    private static final int US = -1;

    private enum Where { HAND, BATTLEFIELD, GRAVEYARD, ELSEWHERE }

    private final PlayerCollection opponents;
    private final int[] oppLibrary;
    private final int blueLands;
    private final int oracleDevotion;
    private int fuel;
    private int handFuel;
    private int ourLibrary;
    private int pool;
    private int storm;
    /** Instances of the current Brain Freeze (original plus storm copies) not yet aimed. */
    private int unassigned;
    private Where led;
    private Where brainFreeze;
    private Where oracle;
    private boolean oracleMode;

    private BreachLoop(PlayerCollection opponents, int[] oppLibrary, int blueLands, int oracleDevotion) {
        this.opponents = opponents;
        this.oppLibrary = oppLibrary;
        this.blueLands = blueLands;
        this.oracleDevotion = oracleDevotion;
    }

    private BreachLoop copy() {
        BreachLoop s = new BreachLoop(opponents, oppLibrary.clone(), blueLands, oracleDevotion);
        s.fuel = fuel;
        s.handFuel = handFuel;
        s.ourLibrary = ourLibrary;
        s.pool = pool;
        s.storm = storm;
        s.unassigned = unassigned;
        s.led = led;
        s.brainFreeze = brainFreeze;
        s.oracle = oracle;
        s.oracleMode = oracleMode;
        return s;
    }

    /**
     * The board as the loop sees it: Brain Freeze instances already on the stack count as resolved, and the rest of
     * their storm batch is still to be aimed. Oracle mode is on iff Oracle is in hand or graveyard and that kill is
     * reachable.
     */
    static BreachLoop of(Player p) {
        Game game = p.getGame();
        PlayerCollection opponents = p.getOpponents();
        int[] oppLibrary = new int[opponents.size()];
        for (int i = 0; i < oppLibrary.length; i++) {
            oppLibrary[i] = opponents.get(i).getCardsIn(ZoneType.Library).size();
        }
        Card oracleCard = find(p, ComboLibrary.ORACLE);
        int devotion = p.getDevotionMod() + blueShards(oracleCard);
        for (Card c : p.getCardsIn(ZoneType.Battlefield)) {
            devotion += blueShards(c);
        }
        BreachLoop s = new BreachLoop(opponents, oppLibrary, blueLands(p), devotion);
        s.fuel = fuel(p.getCardsIn(ZoneType.Graveyard));
        s.handFuel = fuel(p.getCardsIn(ZoneType.Hand));
        s.ourLibrary = p.getCardsIn(ZoneType.Library).size();
        s.pool = p.getManaPool().totalMana();
        s.storm = game.getStack().getSpellsCastThisTurn().size();
        if (where(p, ComboLibrary.BREACH) == Where.HAND) {
            s.storm++; // cast before the loop starts
        }
        s.led = where(p, ComboLibrary.LED);
        s.brainFreeze = where(p, ComboLibrary.BRAIN_FREEZE);
        s.oracle = oracleCard == null ? Where.ELSEWHERE : where(p, ComboLibrary.ORACLE);

        // storm copies made by the resolving trigger wait on the frozen stack until the trigger is done
        List<SpellAbilityStackInstance> stack = new ArrayList<>(game.getStack().getFrozenStack());
        game.getStack().forEach(stack::add);
        int onStack = 0;
        for (SpellAbilityStackInstance si : stack) {
            SpellAbility sa = si.getSpellAbility();
            if (isBrainFreeze(sa)) {
                onStack++;
                Player t = sa.getTargets().getFirstTargetedPlayer();
                if (t == p) {
                    s.mill(US);
                } else if (opponents.contains(t)) {
                    s.mill(opponents.indexOf(t));
                }
            }
        }
        if (onStack > 0) {
            // the batch is the last Brain Freeze cast plus one copy per spell cast before it
            int total = 0;
            int i = 0;
            for (SpellAbility cast : game.getStack().getSpellsCastThisTurn()) {
                i++;
                if (isBrainFreeze(cast)) {
                    total = i;
                }
            }
            s.unassigned = Math.max(0, total - onStack);
        }

        s.oracleMode = s.oracle == Where.HAND || s.oracle == Where.GRAVEYARD;
        if (s.oracleMode && !s.finishes()) {
            s.oracleMode = false;
        }
        return s;
    }

    private static boolean isBrainFreeze(SpellAbility sa) {
        return sa.getApi() == ApiType.Mill && sa.getHostCard() != null
                && ComboLibrary.BRAIN_FREEZE.equals(sa.getHostCard().getName());
    }

    private static int fuel(Iterable<Card> cards) {
        int n = 0;
        for (Card c : cards) {
            if (!PIECES.contains(c.getName())) {
                n++;
            }
        }
        return n;
    }

    private static int blueShards(Card c) {
        int n = 0;
        if (c != null) {
            for (ManaCostShard sh : c.getManaCost()) {
                if (sh.isColor(MagicColor.BLUE)) {
                    n++;
                }
            }
        }
        return n;
    }

    private static int blueLands(Player p) {
        int n = 0;
        for (Card c : p.getCardsIn(ZoneType.Battlefield)) {
            if (c.isLand() && c.isUntapped() && c.getManaAbilities().anyMatch(m -> m.canProduce("U"))) {
                n++;
            }
        }
        return n;
    }

    private static Card find(Player p, String name) {
        for (ZoneType zone : new ZoneType[] {ZoneType.Hand, ZoneType.Battlefield, ZoneType.Graveyard, ZoneType.Stack}) {
            Card c = ComboPlanner.findByName(p.getCardsIn(zone), name);
            if (c != null) {
                return c;
            }
        }
        return null;
    }

    /** A spell on the stack counts as where it lands on resolution. */
    private static Where where(Player p, String name) {
        Card c = find(p, name);
        if (c == null) {
            return Where.ELSEWHERE;
        }
        return switch (c.getZone().getZoneType()) {
            case Hand -> Where.HAND;
            case Battlefield -> Where.BATTLEFIELD;
            case Graveyard -> Where.GRAVEYARD;
            default -> c.isPermanent() ? Where.BATTLEFIELD : Where.GRAVEYARD;
        };
    }

    private void mill(int target) {
        if (target == US) {
            int milled = Math.min(MILL, ourLibrary);
            ourLibrary -= milled;
            fuel += milled;
        } else {
            oppLibrary[target] = Math.max(0, oppLibrary[target] - MILL);
        }
    }

    private boolean opponentsMilledOut() {
        for (int lib : oppLibrary) {
            if (lib > 0) {
                return false;
            }
        }
        return true;
    }

    private int mostLibrary() {
        int best = US;
        for (int i = 0; i < oppLibrary.length; i++) {
            if (best == US || oppLibrary[i] > oppLibrary[best]) {
                best = i;
            }
        }
        return best;
    }

    /** Fuel the next Brain Freeze needs once this batch resolves: its own escape, plus LED's if the mana is short. */
    private int fuelForNextBrainFreeze() {
        return pool < BRAIN_FREEZE_MANA && led == Where.GRAVEYARD ? 2 * ESCAPE_EXILE : ESCAPE_EXILE;
    }

    /**
     * Where the next unassigned instance goes. Oracle mode: us until our library is down to Oracle's devotion. Mill
     * mode: an opponent if the rest of the batch can mill them all out; else us while fuel is short; else an opponent.
     */
    private int pick() {
        if (oracleMode) {
            return ourLibrary > oracleDevotion ? US : mostLibrary();
        }
        int needed = 0;
        for (int lib : oppLibrary) {
            needed += (lib + MILL - 1) / MILL;
        }
        if (needed > unassigned && fuel < fuelForNextBrainFreeze() && ourLibrary > 0) {
            return US;
        }
        return mostLibrary();
    }

    boolean canCastBrainFreeze() {
        return pool >= BRAIN_FREEZE_MANA
                && (brainFreeze == Where.HAND || brainFreeze == Where.GRAVEYARD && fuel >= ESCAPE_EXILE);
    }

    boolean canCastLed() {
        return led == Where.HAND || led == Where.GRAVEYARD && fuel >= ESCAPE_EXILE;
    }

    boolean ledOnBattlefield() {
        return led == Where.BATTLEFIELD;
    }

    /** Oracle mode, library down to its devotion, and Oracle castable (escape fuel, UU from pool or Islands). */
    boolean oracleReady() {
        return oracleMode && ourLibrary <= oracleDevotion && pool + blueLands >= ORACLE_MANA
                && (oracle == Where.HAND || oracle == Where.GRAVEYARD && fuel >= ESCAPE_EXILE);
    }

    private boolean won() {
        return oracleMode ? oracleReady() : opponentsMilledOut();
    }

    private void castBrainFreeze() {
        if (brainFreeze == Where.GRAVEYARD) {
            fuel -= ESCAPE_EXILE;
        }
        brainFreeze = Where.GRAVEYARD; // where it is once the batch resolves
        pool -= BRAIN_FREEZE_MANA;
        storm++;
        unassigned = storm;
    }

    private void castLed() {
        if (led == Where.GRAVEYARD) {
            fuel -= ESCAPE_EXILE;
        }
        led = Where.BATTLEFIELD;
        storm++;
    }

    private void crackLed() {
        led = Where.GRAVEYARD;
        pool += LED_MANA;
        fuel += handFuel;
        handFuel = 0;
        if (brainFreeze == Where.HAND) {
            brainFreeze = Where.GRAVEYARD;
        }
        if (oracle == Where.HAND) {
            oracle = Where.GRAVEYARD;
        }
    }

    /** Plays the loop forward with the step rules; true if it reaches the kill before running dry. */
    boolean finishes() {
        BreachLoop s = copy();
        for (int i = 0; i < MAX_ACTIONS; i++) {
            if (s.unassigned > 0) {
                s.mill(s.pick());
                s.unassigned--;
            } else if (s.won()) {
                return true;
            } else if (s.canCastBrainFreeze()) {
                s.castBrainFreeze();
            } else if (s.canCastLed()) {
                s.castLed();
            } else if (s.ledOnBattlefield()) {
                s.crackLed();
            } else {
                return false;
            }
        }
        return false;
    }

    /** The line's condition: the kill isn't reached yet (mill mode) and the loop gets there from here. */
    boolean live() {
        return (oracleMode || !opponentsMilledOut()) && finishes();
    }

    /**
     * Target of the next Brain Freeze instance: a storm copy being put on the stack, or, with none on the stack, the
     * Brain Freeze the planner is about to cast (its cost is paid first in the projection).
     */
    static Player target(Player p) {
        BreachLoop s = of(p);
        if (s.unassigned == 0) {
            s.castBrainFreeze();
        }
        int t = s.pick();
        return t == US ? p : s.opponents.get(t);
    }
}
